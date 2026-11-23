package radiosmile.radio;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import radiosmile.SmileSRadio;

/**
 * 网络流媒体源：服务端代为拉取上游 HTTP 流并转发给客户端。
 *
 * <p><b>为什么由服务端代理</b>：让所有玩家听到同一时刻的内容。
 * 若每个客户端各自连上游，各自缓冲深度不同，就会错开好几秒。
 * 服务端只开一条连接，把所有字节按同一节奏分发。
 *
 * <p><b>ICY 元数据</b>：很多网络电台（含测试地址）会启用 {@code icy-metadata}，
 * 在音频字节中每 {@code icy-metaint} 字节插入一段带长度前缀的元数据
 * （如当前曲名）。这里用 {@link IcyInputStream} 把这些块剔掉，
 * 只把纯音频字节转发给客户端——否则客户端解码器会被垃圾数据打断。
 */
public final class StreamAudioSource implements AudioSource {
	private static final int CONNECT_TIMEOUT_MS = 8000;
	private static final int READ_TIMEOUT_MS = 8000;
	private static final int MAX_REDIRECTS = 5;

	private final String url;
	private HttpURLConnection connection;
	private volatile InputStream stream;
	private volatile boolean finished;
	/** 是否已被显式关闭（区别于「暂时读不到数据」）。 */
	private volatile boolean closed;

	/**
	 * 流会话号：<b>每次成功（重新）连接上游都会递增</b>。
	 *
	 * <p>用途是告诉下游「字节流在这里断过一次」。
	 * 网络直播流重连后，新连接的字节与断线前的字节<b>不是连续的</b>
	 * （中间缺了断线期间的内容），但 MP3 帧自包含，解码器会把它们
	 * 当成同一条流继续解——听感就是声音突然「卡回」到断线前的位置附近。
	 *
	 * <p>因此客户端一旦发现会话号变化，必须<b>重建解码器</b>，
	 * 丢掉断线前残留的解码状态与已缓冲数据。
	 */
	private volatile int sessionId;
	private int icyMetaInt;

	/**
	 * 重连状态机。
	 *
	 * <p>断网/上游抖动时会走这里：指数退避地重试，直到成功或超过上限。
	 * 重试在<b>读线程</b>里同步进行（每个 read() 调用推进一步），
	 * 因此不额外开线程，也不会阻塞服务端主线程。
	 */
	private volatile boolean reconnecting;
	/** 下次允许尝试重连的时刻。 */
	private volatile long nextReconnectAt;
	/** 已连续重试次数（成功后清零）。 */
	private volatile int reconnectAttempts;
	/** 单次故障最多重试多少次。 */
	private static final int MAX_RECONNECT_ATTEMPTS = 120;
	/**
	 * 退避起始间隔（毫秒）。
	 *
	 * <p>取 500ms 而不是 1 秒：断网恢复往往只断几秒，
	 * 起步慢会显著拉长恢复时间（用户实测「恢复用了非常久」）。
	 */
	private static final long RECONNECT_BASE_DELAY_MS = 500L;
	/**
	 * 退避上限（毫秒）。
	 *
	 * <p>封顶 3 秒而不是 15 秒：这是「感知延迟」的关键——
	 * 断网 10 秒恢复时，最坏情况也只需再等 3 秒就能接上，
	 * 而不是先等满 15 秒的退避。
	 */
	private static final long RECONNECT_MAX_DELAY_MS = 3000L;

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
	 * 代价是「刚开始订阅时 {@link #read} 可能因为还在建连而短暂返回 0/阻塞」，
	 * 这与「上游暂时没数据」是同一种情况，上层读线程本来就能正确等待。
	 */
	public StreamAudioSource(String url) {
		this.url = url;
		Thread connector = new Thread(this::connectQuietly, "RadioSmile-StreamConnect");
		connector.setDaemon(true);
		connector.start();
	}

	private void connectQuietly() {
		try {
			this.connect();
		} catch (IOException e) {
			if (!this.finished) {
				SmileSRadio.LOGGER.warn("连接网络电台失败：{}", this.url, e);
			}
			this.finished = true;
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
			SmileSRadio.LOGGER.info("已连接网络电台 {}（icy-metaint={}，会话 {}）",
					target, this.icyMetaInt, this.sessionId);
			return;
		}
		throw new IOException("重定向次数过多: " + this.url);
	}

	/**
	 * 标记进入重连状态，并按退避安排下一次尝试。
	 *
	 * <p>退避间隔随失败次数翻倍（1s → 2s → 4s → … 上限 15s）：
	 * 断网时不要每秒猛敲上游，但恢复后也要能较快（最迟 15 秒内）接上。
	 */
	private void scheduleReconnect(String reason) {
		// 丢掉坏连接，避免句柄泄漏
		closeConnectionQuietly();
		this.stream = null;

		this.reconnectAttempts++;
		if (this.reconnectAttempts > MAX_RECONNECT_ATTEMPTS) {
			this.finished = true;
			SmileSRadio.LOGGER.error(
					"网络电台重连失败次数超限（{} 次），放弃：{}", MAX_RECONNECT_ATTEMPTS, this.url);
			return;
		}

		long delay = Math.min(RECONNECT_MAX_DELAY_MS,
				RECONNECT_BASE_DELAY_MS << Math.min(4, this.reconnectAttempts - 1));
		this.nextReconnectAt = System.currentTimeMillis() + delay;
		this.reconnecting = true;
		SmileSRadio.LOGGER.warn("网络电台连接中断（{}），{} ms 后第 {} 次重连：{}",
				reason, delay, this.reconnectAttempts, this.url);
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

	/**
	 * 若处于重连状态且已到时间，则发起一次重连。
	 *
	 * <p>由 {@link #read} 驱动（每次空转调用一次），所以不需要额外线程。
	 *
	 * @return 是否仍处于「未连上」状态（true 表示本块没有数据可读）
	 */
	private boolean maintainReconnect() {
		if (!this.reconnecting) {
			return false;
		}
		if (System.currentTimeMillis() < this.nextReconnectAt) {
			return true;
		}
		try {
			this.connect();
			// 成功：重置重试计数，恢复正常读取
			this.reconnecting = false;
			this.reconnectAttempts = 0;
			SmileSRadio.LOGGER.info("网络电台重连成功：{}", this.url);
			return this.stream == null;
		} catch (IOException e) {
			// 还没恢复：继续退避（scheduleReconnect 会自增计数并拉长间隔）
			this.scheduleReconnect("重连失败：" + e.getMessage());
			return true;
		}
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
		// 处于重连中：推进重连状态机；还没连上就先返回空数组（稍后再试）
		if (this.maintainReconnect() || this.stream == null) {
			// 还没连上。这里必须返回「暂时没数据」而不是 null：
			// null 表示「流已结束」，会让上层读线程直接退出、整个订阅作废。
			// 返回空数组则被上层当作「这一轮没读到」，稍后再试。
			return new byte[0];
		}
		InputStream in = this.stream;
		byte[] buffer = new byte[maxBytes];
		int read;
		try {
			read = in.read(buffer);
		} catch (IOException e) {
			// <b>读失败不能直接判死</b>：断网、上游抖动、连接被对端重置都会走到这里。
			// 早期只是把 finished 置 true 就返回 null，读线程随即退出，
			// 订阅变成「还挂着但永远没数据」的静默死亡——用户实测「断网恢复后电台没恢复」
			// 就是这个原因。
			// 现在改为：丢弃这条坏连接，进入重连状态，由上层继续调用 read() 驱动重试。
			if (this.closed) {
				return null;
			}
			this.scheduleReconnect("读取失败：" + e.getMessage());
			return new byte[0];
		}
		if (read <= 0) {
			// 对端正常关闭（直播流被切断）：同样按可恢复处理，而不是终止。
			if (this.closed) {
				this.finished = true;
				return null;
			}
			this.scheduleReconnect("上游关闭连接");
			return new byte[0];
		}
		if (read == buffer.length) {
			return buffer;
		}
		byte[] trimmed = new byte[read];
		System.arraycopy(buffer, 0, trimmed, 0, read);
		return trimmed;
	}

	/** 网络直播流总长未知。 */
	@Override
	public long durationMillis() {
		return -1L;
	}

	/**
	 * 当前的流会话号。
	 *
	 * <p>随每个音频块下发给客户端；变化即表示「上游断过又重连」，
	 * 客户端必须重建解码器，否则会把不连续的字节当连续流来解，
	 * 听感是声音突然卡回到之前的位置。
	 */
	@Override
	public int sessionId() {
		return this.sessionId;
	}

	@Override
	public radiosmile.radio.AudioFormat format() {
		return radiosmile.radio.AudioFormat.ofFile(this.url);
	}

	@Override
	public boolean isFinished() {
		return this.finished;
	}

	@Override
	public void close() {
		// <b>closed 与 finished 必须分开</b>：
		// closed 表示「是被主动关掉的」，此后 read() 立即返回 null，也不再重连；
		// finished 表示「这个流已经彻底不可用了」（含重试超限）。
		// 早期只有 finished，导致读失败与主动关闭无法区分，无法做重连。
		this.closed = true;
		this.finished = true;
		this.reconnecting = false;
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