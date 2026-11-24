package radiosmile.radio;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;

import radiosmile.SmileSRadio;

/**
 * 网络流媒体源：服务端代为拉取上游 HTTP 流并转发给客户端。
 *
 * <p><b>为什么由服务端代理</b>：让所有玩家听到同一时刻的内容。
 * 若每个客户端各自连上游，各自缓冲深度不同，就会错开好几秒。
 * 服务端只开一条连接，把所有字节按同一节奏分发。
 *
 * <h2>上游断线后<b>自动重连</b>（用户要求恢复）</h2>
 * 早期这里有一套指数退避重连状态机，后被整体删除——当时的理由是
 * 「服务端重连 + 客户端重订阅双重抖动」。现在客户端已不再因断流重订阅，
 * 抖动来源消失；而实测断网后收音机既不报 NOSIGNAL 也永远不恢复，
 * 玩家只能重连服务器，体验不可接受。所以恢复重连，职责收拢为：
 * <ul>
 *   <li><b>服务端</b>负责把管子接回去（指数退避 3s -> 30s，无限重试），
 *       重连成功 {@link #sessionId} 递增，客户端自动重建解码器、无缝续播；</li>
 *   <li><b>客户端</b>只负责显示故障（NOSIGNAL + 底噪），见 {@code AutoSubscriber}。</li>
 * </ul>
 *
 * <h2>读不到数据时怎么办</h2>
 * 返回<b>空数组</b>（= 暂时没有数据）而不是 {@code null}（= 流已结束）：
 * {@code null} 会让上层读线程直接退出、整条订阅作废。
 * 建连期间与上游卡顿都走空数组，让上层按自己的节奏继续轮询。
 *
 * <h2>ICY 元数据</h2>
 * 很多网络电台会启用 {@code icy-metadata}，在音频字节中每 {@code icy-metaint} 字节
 * 插入一段带长度前缀的元数据（如当前曲名）。这里用 {@link IcyInputStream} 把这些块剔掉，
 * 只把纯音频字节转发给客户端——否则客户端解码器会被垃圾数据打断。
 */
public final class StreamAudioSource implements AudioSource {
	private static final int CONNECT_TIMEOUT_MS = 8000;
	private static final int READ_TIMEOUT_MS = 8000;
	private static final int MAX_REDIRECTS = 5;

	private final String url;
	private HttpURLConnection connection;
	private volatile InputStream stream;
	/** 是否已被显式关闭（区别于「暂时读不到数据/正在重连」）。 */
	private volatile boolean closed;

	// ---------------- 自动重连（指数退避） ----------------

	/** 首次重连前的等待（毫秒）。 */
	private static final long RECONNECT_BASE_MS = 3000L;
	/** 重连间隔上限（毫秒）。断网几小时也别把上游打爆。 */
	private static final long RECONNECT_MAX_MS = 30000L;
	/** 重连失败日志的最小间隔（毫秒）：断网几小时时避免刷满日志。 */
	private static final long FAILURE_LOG_INTERVAL_MS = 60000L;

	/** 下次重连的时刻（0 表示还没有失败记录）。 */
	private volatile long nextReconnectAt;
	/** 连续失败次数：决定退避时长，成功后清零。 */
	private volatile int reconnectAttempts;
	/** 上次打印「重连失败」日志的时刻，防止刷屏。 */
	private volatile long lastFailureLogAt;

	/** 连接断开后安排下次重连：指数退避 3s、6s、12s、24s、30s、30s…… */
	private void scheduleReconnect() {
		if (this.closed) {
			return;
		}
		int attempts = this.reconnectAttempts++;
		long delay = Math.min(RECONNECT_BASE_MS << Math.min(attempts, 20), RECONNECT_MAX_MS);
		this.nextReconnectAt = System.currentTimeMillis() + delay;
	}

	/** 到点了就同步重连一次（在读线程上执行，阻塞的只是读线程自己）。 */
	private void reconnectIfDue() {
		if (this.closed || this.stream != null
				|| System.currentTimeMillis() < this.nextReconnectAt) {
			return;
		}
		try {
			this.connect();
			// connect() 成功时已递增 sessionId 并打日志，客户端会自动重建解码器
		} catch (IOException e) {
			if (this.closed) {
				return;
			}
			long now = System.currentTimeMillis();
			if (now - this.lastFailureLogAt >= FAILURE_LOG_INTERVAL_MS) {
				this.lastFailureLogAt = now;
				SmileSRadio.LOGGER.warn("网络电台重连失败（继续自动重试）：{} —— {}",
						this.url, e.toString());
			}
			scheduleReconnect();
		}
	}

	/**
	 * 流会话号。
	 *
	 * <p>重连成功（含首连）都会递增：重连后的字节与断线前不连续，
	 * 客户端按会话号变化重建解码器，不会把断线前后当成同一条流去解。
	 */
	private volatile int sessionId;
	private int icyMetaInt;

	/**
	 * <b>异步建连</b>：立即返回，真正连上游在后台线程做。
	 *
	 * <h2>为什么必须异步</h2>
	 * 上游握手实测要 <b>1 秒左右</b>（DNS + TLS + 等首包），
	 * 而 {@code StreamAudioSource} 的构造发生在 {@code AudioStreamManager.subscribe()} 里，
	 * 后者是通过 {@code server.execute()} 跑在<b>服务端主线程</b>的。
	 * 同步建连会把整个服务端 tick 冻住 1 秒——本地游戏表现为明显卡顿，
	 * 多人服务器上则是所有人一起卡。
	 *
	 * <p>异步化之后，订阅立即成功、状态立即回给客户端，数据在连上后由读线程自然取到。
	 * 代价是「刚开始订阅时 {@link #read} 可能因为还在建连而短暂返回空数组」，
	 * 这与「上游暂时没数据」是同一种情况，上层读线程本来就能正确等待。
	 */
	public StreamAudioSource(String url) {
		this.url = url;
		Thread connector = new Thread(this::connectQuietly, "RadioSmile-StreamConnect");
		connector.setDaemon(true);
		connector.start();
	}

	/**
	 * 首次建连（异步，见构造器）。
	 *
	 * <p>失败<b>不再永久放弃</b>：安排重连，由读线程按退避节奏重试。
	 * 玩家在此期间看到底噪与 {@code NOSIGNAL}，连上后自动恢复。
	 */
	private void connectQuietly() {
		try {
			this.connect();
		} catch (IOException e) {
			if (!this.closed) {
				SmileSRadio.LOGGER.warn("连接网络电台失败（将自动重试）：{}", this.url);
				this.lastFailureLogAt = System.currentTimeMillis();
				scheduleReconnect();
			}
		}
	}

	private void connect() throws IOException {
		String target = this.url;
		for (int i = 0; i <= MAX_REDIRECTS; i++) {
			HttpURLConnection conn = (HttpURLConnection) URI.create(target).toURL().openConnection();
			conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
			conn.setReadTimeout(READ_TIMEOUT_MS);
			// 请求 ICY 元数据：拿到 metaint 才能在正确位置剔除元数据块
			conn.setRequestProperty("Icy-MetaData", "1");
			conn.setRequestProperty("User-Agent", "SmileRadio/1.0");
			conn.setInstanceFollowRedirects(false);

			int code = conn.getResponseCode();
			if (code >= 300 && code < 400) {
				String location = conn.getHeaderField("Location");
				conn.disconnect();
				if (location == null || location.isBlank()) {
					throw new IOException("重定向缺少 Location: " + target);
				}
				target = URI.create(target).resolve(location).toString();
				continue;
			}
			if (code != 200) {
				conn.disconnect();
				throw new IOException("上游返回 " + code + ": " + target);
			}

			this.connection = conn;
			this.icyMetaInt = parseInt(conn.getHeaderField("icy-metaint"), 0);
			InputStream raw = conn.getInputStream();
			this.stream = this.icyMetaInt > 0 ? new IcyInputStream(raw, this.icyMetaInt) : raw;
			// 每成功连上（含重连）都递增会话号：
			// 重连后的字节与断线前不连续，下游必须据此重置解码状态。
			this.sessionId++;
			// 连上了：退避清零，下次断线从最短间隔重新开始
			this.reconnectAttempts = 0;
			SmileSRadio.LOGGER.info("已连接网络电台 {}（icy-metaint={}，会话 {}）",
					target, this.icyMetaInt, this.sessionId);
			return;
		}
		throw new IOException("重定向次数过多: " + this.url);
	}

	/** 安静地关闭当前连接。 */
	private void closeConnectionQuietly() {
		InputStream in = this.stream;
		if (in != null) {
			try {
				in.close();
			} catch (Exception ignored) {
				// 关闭失败无所谓，下面还要 disconnect
			}
		}
		HttpURLConnection conn = this.connection;
		if (conn != null) {
			try {
				conn.disconnect();
			} catch (Exception ignored) {
				// 连接可能已失效
			}
		}
		this.connection = null;
	}

	private static int parseInt(String value, int fallback) {
		if (value == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}

	@Override
	public byte[] read(int maxBytes) throws IOException {
		if (this.closed) {
			return null;
		}
		// 还没连上（首连进行中 / 断线重连中）：到点就重试，其余时间给上层一个等待节奏。
		//
		// <b>断线不再判死</b>（用户要求恢复重连）：读失败后这里会一直轮询重连，
		// 返回空数组让读线程保持运转；订阅也保持 active，连上后自动续播。
		InputStream in = this.stream;
		if (in == null) {
			reconnectIfDue();
			sleepQuietly(50L);
			return new byte[0];
		}
		byte[] buffer = new byte[maxBytes];
		int read;
		try {
			read = in.read(buffer);
		} catch (IOException e) {
			// 读失败（对端断开 / 读超时）：关掉这条连接，安排退避重连。
			// 不再永久放弃——上游可能几秒后就恢复，服务端负责把管子接回去。
			if (this.closed) {
				return null;
			}
			SmileSRadio.LOGGER.info("网络电台连接断开，将自动重连：{}", this.url);
			closeConnectionQuietly();
			this.stream = null;
			scheduleReconnect();
			return new byte[0];
		}
		if (read <= 0) {
			// 对端正常关闭（直播流被切断）：同样走退避重连。
			if (this.closed) {
				return null;
			}
			SmileSRadio.LOGGER.info("上游关闭连接，将自动重连：{}", this.url);
			closeConnectionQuietly();
			this.stream = null;
			scheduleReconnect();
			return new byte[0];
		}
		if (read == buffer.length) {
			return buffer;
		}
		byte[] trimmed = new byte[read];
		System.arraycopy(buffer, 0, trimmed, 0, read);
		return trimmed;
	}

	private static void sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** 网络直播流总长未知。 */
	@Override
	public long durationMillis() {
		return -1L;
	}

	/**
	 * 当前的流会话号。
	 *
	 * <p>本类不再重连，所以一条实例的会话号恒为 1；
	 * 新订阅会新建实例，客户端那时也已清空本地流表，不会误判。
	 */
	@Override
	public int sessionId() {
		return this.sessionId;
	}

	@Override
	public radiosmile.radio.AudioFormat format() {
		return radiosmile.radio.AudioFormat.ofFile(this.url);
	}

	/**
	 * 流是否已彻底结束。
	 *
	 * <p><b>只有被主动关闭（订阅拆除）才为 true</b>：断线走自动重连，
	 * 读线程保持轮询，上层（{@code AudioSubscription.readLoop}）不应退出。
	 */
	@Override
	public boolean isFinished() {
		return this.closed;
	}

	@Override
	public void close() {
		// closed = 被主动关掉（订阅拆除）。此后 read() 立即返回 null、
		// isFinished() 为 true，读线程与重连轮询一并终止。
		this.closed = true;
		closeConnectionQuietly();
	}

	/**
	 * 剔除 ICY 元数据块的输入流。
	 *
	 * <p>协议：每 {@code metaInt} 字节音频之后，紧跟 1 字节长度 N（单位 16 字节），
	 * 再跟 N*16 字节元数据；N 为 0 表示本次没有元数据。
	 * 该流的 {@code read} 只返回纯音频字节，调用方无需感知。
	 */
	static final class IcyInputStream extends FilterInputStream {
		private final int metaInt;
		private int audioRemaining;
		private int metaRemaining;

		IcyInputStream(InputStream in, int metaInt) {
			super(in);
			this.metaInt = metaInt;
			this.audioRemaining = metaInt;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (len <= 0) {
				return 0;
			}
			// 先把待跳过的元数据吃掉
			while (this.metaRemaining > 0) {
				int skipped = (int) this.in.skip(this.metaRemaining);
				if (skipped <= 0) {
					int one = this.in.read();
					if (one < 0) {
						return -1;
					}
					skipped = 1;
				}
				this.metaRemaining -= skipped;
			}

			if (this.audioRemaining <= 0) {
				// 到了元数据边界：读长度字节
				int lengthByte = this.in.read();
				if (lengthByte < 0) {
					return -1;
				}
				this.metaRemaining = lengthByte * 16;
				this.audioRemaining = this.metaInt;
				return this.read(b, off, len);
			}

			int toRead = Math.min(len, this.audioRemaining);
			int read = this.in.read(b, off, toRead);
			if (read > 0) {
				this.audioRemaining -= read;
			}
			return read;
		}

		@Override
		public int read() throws IOException {
			byte[] one = new byte[1];
			int read = this.read(one, 0, 1);
			return read <= 0 ? -1 : (one[0] & 0xFF);
		}
	}
}
