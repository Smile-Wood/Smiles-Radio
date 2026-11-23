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
 * <h2>服务端<b>不维护</b>网络电台的生命周期（本轮架构调整）</h2>
 * 这个类只管「连上之后把字节搬下来」，<b>不做任何重连</b>。
 *
 * <p>职责划分是刻意这样定的：
 * <ul>
 *   <li><b>服务端</b>只负责<b>自定义电台（本地文件）</b>与<b>方块数据</b>；
 *       网络电台只是它顺手转发的一根管子，管子断了就断了，
 *       服务端既不修、也不重试、更不因此去打扰玩家。</li>
 *   <li><b>客户端</b>负责「连不上服务器」这件事的处理：只要还没和服务器彻底断开，
 *       表现就是<b>底噪变大 + 屏幕 NOSIGNAL</b>，<b>不会</b>发起重订阅。</li>
 *   <li>真正的恢复路径只有一条：<b>玩家重新连回服务器</b>。
 *       那时服务端会先清理该玩家的全部订阅（视为下线），客户端重新订阅，一切从头开始。</li>
 * </ul>
 *
 * <p>早期这里有一套指数退避重连状态机（每 3 秒重试、最多 120 次）。
 * 它带来两个问题：① 日志在断网期间被几十上百行「第 N 次重连」刷满；
 * ② 与客户端的重订阅叠加，形成"服务端在重连、客户端也在重订阅"的双重抖动。
 * 按上面的职责划分，这套机制整体删除——服务端不管网络电台的死活。
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
	/** 流已彻底不可用（建连失败 / 读到 EOF / 已关闭）。此后 {@link #read} 返回 null。 */
	private volatile boolean finished;
	/** 是否已被显式关闭（区别于「暂时读不到数据」）。 */
	private volatile boolean closed;

	/**
	 * 流会话号。
	 *
	 * <p>保留协议字段（客户端按它判断"字节流是否连续"），但本类<b>不再重连</b>，
	 * 因此一条 {@code StreamAudioSource} 的会话号恒为 1。
	 * 玩家重新连回服务器时会新建一条订阅、也就新建一个本类实例，
	 * 客户端那边的 {@code STREAMS} 早已清空，不会因会话号相同而误判。
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
	 * 首次建连（只有这一次）。
	 *
	 * <p>失败就是失败：把流标记为结束，<b>不重试</b>。
	 * 玩家会看到底噪与 {@code NOSIGNAL}，这符合「服务端不管网络电台死活」的约定。
	 */
	private void connectQuietly() {
		try {
			this.connect();
		} catch (IOException e) {
			if (!this.closed) {
				SmileSRadio.LOGGER.warn("连接网络电台失败（不重试）：{}", this.url, e);
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
		// 还没连上（建连进行中）或流已彻底结束：返回「暂时没有数据」。
		//
		// <b>已结束也返回空数组、不返回 null</b>：让读线程继续按自己的节奏轮询，
		// 而不是把整条订阅判死。订阅是否结束由服务端的管理层决定，
		// 不由这个管子断没断决定（服务端不管网络电台死活）。
		if (this.finished) {
			InputStream in = this.stream;
			if (in == null) {
				// 从未连上：给上层一个等待节奏，避免读线程空转烧 CPU
				sleepQuietly(50L);
				return new byte[0];
			}
		}
		InputStream in = this.stream;
		if (in == null) {
			// 建连尚未完成：稍等再试，避免忙等
			sleepQuietly(50L);
			return new byte[0];
		}
		byte[] buffer = new byte[maxBytes];
		int read;
		try {
			read = in.read(buffer);
		} catch (IOException e) {
			// 读失败：这条连接已经废了。<b>不重连</b>——按职责划分，
			// 管子断了服务端就不管了（见类注释）。标记结束后由上层继续轮询空数组。
			if (this.closed) {
				return null;
			}
			if (!this.finished) {
				SmileSRadio.LOGGER.info("网络电台连接已断开（上游不再恢复）：{}", this.url);
			}
			this.finished = true;
			closeConnectionQuietly();
			this.stream = null;
			return new byte[0];
		}
		if (read <= 0) {
			// 对端正常关闭（直播流被切断）：同样不重连。
			if (this.closed) {
				this.finished = true;
				return null;
			}
			if (!this.finished) {
				SmileSRadio.LOGGER.info("上游关闭连接（不再恢复）：{}", this.url);
			}
			this.finished = true;
			closeConnectionQuietly();
			this.stream = null;
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

	@Override
	public boolean isFinished() {
		return this.finished;
	}

	@Override
	public void close() {
		// <b>closed 与 finished 必须分开</b>：
		// closed 表示「是被主动关掉的」，此后 read() 立即返回 null；
		// finished 表示「这个流已经彻底不可用了」。
		// 早期只有 finished，导致读失败与主动关闭无法区分。
		this.closed = true;
		this.finished = true;
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
