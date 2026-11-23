package radiosmile.net;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;

import radiosmile.SmileSRadio;
import radiosmile.radio.AudioSource;
import radiosmile.radio.RadioStation;

/**
 * 一个玩家的音频订阅状态。
 *
 * <h2>为什么要有独立读线程</h2>
 * 网络流的 {@code read()} 会<b>阻塞</b>直到上游送来数据。若在服务端主线程里直接读，
 * 每推一块就冻住整个世界 tick —— 表现为服务器卡顿、后台网络异常。
 * 因此这里开一个守护线程专职读数据，主线程的 {@link #tick()} 只做两件不阻塞的事：
 * 从队列取块、发包。
 *
 * <h2>为什么要按音频时长配速</h2>
 * 上游是实时产出的（例如 64kbps ≈ 8KB/s）。若服务端按网络带宽猛灌，
 * 会「一次发一大块、然后长时间没数据」，客户端听起来就是一顿一顿。
 * 这里按每块承载的<b>音频时长</b>计算发送间隔，让下发速率与播放速率一致，
 * 客户端缓冲才能平稳填充。
 */
public final class AudioSubscription {
	/** 队列上限（字节）：约等于客户端缓冲时长对应的数据量，超出即反压上游读取。 */
	private static final int MAX_QUEUED_BYTES = 64 * 1024;

	/**
	 * 允许的落后容差（毫秒）。
	 *
	 * <p>发送线程每 5ms 轮询一次、块间隔约 250ms，正常情况下偏差在个位数毫秒。
	 * 超过这个值说明客户端缓冲已经被吃空，此时重新锚定即可，不补发。
	 */
	private static final long LATE_TOLERANCE_MILLIS = 200L;

	private final ServerPlayer player;
	private final BlockPos pos;
	private final RadioStation station;
	private final AudioSource source;
	private final int bandwidthKbps;
	private final int clientMaxChunk;

	/**
	 * 待发数据块。
	 *
	 * <p><b>必须把曲目序号冻结在入队时刻</b>：队列最多积压 {@link #MAX_QUEUED_BYTES}（64KB ≈ 4 秒），
	 * 若发包时才去读音源「当前」序号，标签会比数据早约 4 秒——客户端据此提前
	 * {@code trackChanged} 重建解码器并 flush 声卡，换曲时会有约 1 秒静音，
	 * 且上一首最后几秒的字节会被喂进新解码器。
	 */
	// 包级可见：同包的 AudioStreamManager.pump() 要用它取冻结的曲目序号。
	// 不加 public 是为了不把这个内部传输结构暴露到包外。
	record Chunk(byte[] data, int trackIndex) {
	}

	private final Deque<Chunk> queue = new ArrayDeque<>();
	private int queuedBytes;
	private final Thread reader;
	private volatile boolean closed;

	private int lastChunkSize;
	private long totalBytesSent;
	private long nextSendAt = System.currentTimeMillis();
	/**
	 * 配速时间轴起点。
	 *
	 * <p>不在构造时固定下来：只有首包发出那一刻才知道「这包之前究竟推了多少音频」，
	 * 起点要据此回推（见 {@link #markSent}）。若直接拿构造时刻当起点，首包之后就会空转一段；
	 * 若拿读线程的预读位置当起点，时间轴会被推到未来 4 秒（见 markSent 的说明）。
	 */
	private long streamStartAt;
	/** 首包是否已发出。首包负责确定 {@link #streamStartAt}。 */
	private boolean streamStarted;
	/** 已发送音频累计时长（毫秒），由 {@link #sentBytesFromStart} 换算而来，是闭环配速的锚点。 */
	private long audioSentMillis;
	/**
	 * 已发送字节数（相对订阅起点，只累加不重置）。
	 *
	 * <p>换算成毫秒就是「真正推给客户端的音频量」。用它而不是 {@code source.positionMillis()}：
	 * 后者是读线程的<b>预读</b>位置，领先发放位置整整一个队列（{@link #MAX_QUEUED_BYTES}）。
	 */
	private long sentBytesFromStart;
	/** 需要跳过的时长（毫秒）：预热阶段全速补发这些内容，客户端解码后丢弃。 */
	private long primeRemainingMillis;
	/** 是否处于预热阶段。 */
	private boolean priming;

	/**
	 * 设置预热时长。
	 *
	 * <p>为了让中途加入的玩家立刻听到「当前该播的内容」，需要先把目标位置之前的
	 * 音频快速补发过去（客户端丢弃不播）。否则玩家要干等几十秒甚至几分钟。
	 *
	 * <p>预热阶段不限速，以网络能承受的最快速度发送。
	 */
	public void setPrimeMillis(long millis) {
		this.primeRemainingMillis = Math.max(0L, millis);
		this.priming = this.primeRemainingMillis > 0;
	}

	public boolean isPriming() {
		return this.priming;
	}

	/** 扣除已预热的时长。 */
	public void consumePrime(long millis) {
		if (!this.priming) {
			return;
		}
		this.primeRemainingMillis -= millis;
		if (this.primeRemainingMillis <= 0) {
			this.priming = false;
			SmileSRadio.LOGGER.info("预热完成，转入正常配速播放");
		}
	}
	/**
	 * 估算的音频码率（字节/秒），用于配速与读块大小。
	 *
	 * <p>初始值按 64kbps（8KB/s）估算；本地文件可直接从时长与体积算出准确值，
	 * 网络流则随数据量自适应修正。
	 */
	private volatile int bytesPerSecond = 8192;
	/**
	 * 码率是否已由文件体积与时长精确算出。
	 *
	 * <p>本地文件无需也不该再自适应估算：读线程是<b>全速预读</b>进队列的，
	 * 它测出来的是磁盘读取速度而不是播放速度，早期实测会把 16000 B/s 的文件
	 * 估成 106666 B/s。码率是配速锚点的换算基数，一旦被放大 6 倍，
	 * 每个包承载的 250ms 音频就只被记成约 40ms，发包节奏随之失控。
	 * 因此精确值一旦算出就固定下来。
	 */
	private boolean bitrateFixed;

	public AudioSubscription(ServerPlayer player, BlockPos pos, RadioStation station,
			AudioSource source, int bandwidthKbps, int clientMaxChunk) {
		this.player = player;
		this.pos = pos;
		this.station = station;
		this.source = source;
		this.bandwidthKbps = bandwidthKbps;
		this.clientMaxChunk = clientMaxChunk;
		// 码率优先取音源给出的精确值（本地文件由体积/时长算出）。
		//
		// 这个值同时决定 readLoop 的读块大小与 markSent 的毫秒换算，
		// 若让读线程的「全速预读速度」去估，会被放大约 6 倍（预读 80KB/s vs 实播 16KB/s），
		// 于是每包 250ms 只被记成 ~40ms，配速会失控成 60ms/包。
		// 播放列表同样走这里：bytesPerSecond() 会转发当前曲目的精确值。
		// 网络流返回 0，仍由 updateBitrateEstimate 自适应估算（直播流无体积/时长可用）。
		int exactBps = source.bytesPerSecond();
		if (exactBps > 512) {
			this.bytesPerSecond = exactBps;
			this.bitrateFixed = true;
		} else {
			long duration = source.durationMillis();
			if (duration > 0 && source instanceof radiosmile.radio.FileAudioSource fileSource) {
				try {
					long size = java.nio.file.Files.size(fileSource.file());
					int bps = (int) (size * 1000L / duration);
					if (bps > 512) {
						this.bytesPerSecond = bps;
						this.bitrateFixed = true;
					}
				} catch (java.io.IOException ignored) {
					// 用默认值
				}
			}
		}

		this.reader = new Thread(this::readLoop, "RadioSmile-AudioRead");
		this.reader.setDaemon(true);
		this.reader.start();
	}

	/**
	 * 后台读取循环：把音频灌进有界队列。
	 *
	 * <p>队列满时 {@code wait()} 阻塞，形成对上游的反压——
	 * 不会把整段流缓存进内存，也不会阻塞主线程。
	 */
	private void readLoop() {
		try {
			while (!this.closed) {
				// 读块大小固定为约 250ms 音频量（按估算码率），
				// 之前用 lastChunkSize*2，而 lastChunkSize 从未被赋值导致恒为 0，
				// 每块只有 1024 字节（约 0.1 秒），配合发包节流把节奏彻底打乱。
				int want = Math.max(MIN_READ, this.bytesPerSecond / 4);
				byte[] data = this.source.read(want);
				if (data != null && data.length == 0) {
					// 「暂时没有数据」与「流已结束」必须区分开：
					// 网络流是异步建连的（见 StreamAudioSource），建连期间 read() 返回空数组。
					// 若把空数组也当成 EOF，订阅会在连上之前就被判死——正是「切到网络电台
					// 要等很久 / 有时干脆没声」的一个原因。
					//
					// <b>但「源已彻底结束」必须能退出这个循环</b>（本轮新增）：
					// 按新的职责划分，服务端<b>不重连</b>网络电台——上游断了就永久结束，
					// 之后 read() 一直返回空数组。若不在这里退出，读线程会每 50ms 空转，
					// 订阅也一直挂着占名额，直到玩家下线才清理。
					//
					// 退出后订阅仍是 active 的（服务端不主动踢），只是不再产生数据：
					// 客户端表现为底噪 + NOSIGNAL，玩家重连服务器时 DISCONNECT/JOIN 会清掉它。
					if (this.source.isFinished()) {
						SmileSRadio.LOGGER.info("音频源已结束，停止读取（订阅保留待玩家重连）：{}",
								this.station.id());
						synchronized (this.queue) {
							this.queue.notifyAll();
						}
						return;
					}
					synchronized (this.queue) {
						this.queue.wait(50);
					}
					continue;
				}
				if (data == null) {
					// 换曲/回绕的两条路径必须分开，早期这里混为一谈，导致播放列表
					// 永远停在第一首（详见 HANDOVER 与 PlaylistAudioSource 的说明）：
					//
					// - 播放列表：PlaylistAudioSource.read() 内部已经「读到 EOF 就接下一首、
					//   末尾回到第 0 首」，它返回 null 只表示整条列表都不可用。
					//   此时绝不能调 reopen()——那会按时间轴在同一首内重新定位，反复重播。
					// - 单文件 / 网络流：本地文件读到末尾调 reopen() 回绕，即单曲循环
					//   （reopen 按服务端计时基准定位，保证多人仍对齐）；
					//   网络流读到 EOF 就是真的断了，直接结束。
					if (this.source instanceof radiosmile.radio.PlaylistAudioSource) {
						synchronized (this.queue) {
							this.queue.notifyAll();
						}
						return;
					}
					if (!this.station.isStreaming()) {
						this.source.reopen();
						continue;
					}
					synchronized (this.queue) {
						this.queue.notifyAll();
					}
					return;
				}
				// 曲目序号在「读到这块数据的那一刻」就冻结下来，随数据一起入队。
				// 换曲后读到的第一块新数据才带新序号，与字节流严格对应。
				int chunkTrackIndex = this.source.trackIndex();
				synchronized (this.queue) {
					while (this.queuedBytes + data.length > MAX_QUEUED_BYTES && !this.closed) {
						this.queue.wait(200);
					}
					if (this.closed) {
						return;
					}
					this.queue.addLast(new Chunk(data, chunkTrackIndex));
					this.queuedBytes += data.length;
					this.queue.notifyAll();
				}
				// 用首批数据估算码率，供配速使用
				this.updateBitrateEstimate(data.length);
			}
		} catch (IOException e) {
			if (!this.closed) {
				SmileSRadio.LOGGER.warn("读取音频流失败：{}", this.station.id(), e);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	private static final int MIN_READ = 1024;
	private long estimateSamples;
	private long estimateBytes;

	private void updateBitrateEstimate(int bytes) {
		if (this.bitrateFixed) {
			return;
		}
		this.estimateSamples++;
		this.estimateBytes += bytes;
		if (this.estimateSamples == 20) {
			// 前 20 次读取通常已覆盖流首部，估算一下平均码率
			long elapsed = Math.max(1, System.currentTimeMillis() - this.createdAt);
			int estimate = (int) Math.min(256 * 1024, (this.estimateBytes * 1000L) / elapsed);
			if (estimate > 2048) {
				this.bytesPerSecond = estimate;
			}
		}
	}

	private final long createdAt = System.currentTimeMillis();

	/**
	 * 取一块待发数据（不阻塞）；无数据返回 null。
	 *
	 * <p>返回的 {@link Chunk} 里带着<b>入队时</b>冻结的曲目序号，
	 * 发送方必须用它而不是重新读音源当前序号（见 {@link Chunk} 的说明）。
	 */
	public synchronized Chunk pollChunk() {
		synchronized (this.queue) {
			Chunk chunk = this.queue.pollFirst();
			if (chunk != null) {
				this.queuedBytes -= chunk.data().length;
				this.queue.notifyAll();
			}
			return chunk;
		}
	}

	/**
	 * 计算并推进下一块的大小。
	 *
	 * <p>块大小以「约 250ms 音频」为目标：太碎则每块都带网络头开销，
	 * 太大则客户端缓冲增长过快、音量与暂停反应变钝。
	 */
	public int nextChunkSize() {
		int byTime = Math.max(MIN_READ, this.bytesPerSecond / 4);
		int size = AdaptiveChunkSizer.nextChunkSize(
				this.bandwidthKbps, this.clientMaxChunk, 0, this.lastChunkSize);
		// 取二者较小值：既受带宽约束，又不超过约 250ms 的音频量
		size = Math.min(size, byTime);
		size = Math.max(MIN_READ, size);
		this.lastChunkSize = size;
		return size;
	}

	public boolean isDue(long now) {
		// 预热阶段不限速：全速补发历史内容，让客户端尽快到达当前播放位置
		return this.priming || now >= this.nextSendAt;
	}

	/**
	 * 心跳发送间隔（毫秒）。
	 *
	 * <p>取 <b>1000ms</b>：远小于客户端判超时的 8 秒（{@code STALL_TIMEOUT_MILLIS}），
	 * 留足 8 倍余量；又远大于正常块间隔（约 250ms），
	 * 所以<b>正常播放时这条路径根本不会触发</b>（真实音频块会不断刷新它）。
	 *
	 * <p>它只在"配速把下一次发送推到很远的未来"时兜底——
	 * 典型场景是换曲瞬间没有可读数据，而 {@code nextSendAt} 还在未来。
	 */
	private static final long HEARTBEAT_INTERVAL_MILLIS = 1000L;

	/** 上次真正发出任何包（音频块或心跳）的时刻。 */
	private volatile long lastSendAt = System.currentTimeMillis();

	/**
	 * 是否该发心跳空包了。
	 *
	 * <p>判据刻意只看「距上次发包多久」，<b>不看配速</b>：
	 * 心跳的目的就是覆盖"配速还没到、但客户端已经等太久"的窗口。
	 */
	public boolean isHeartbeatDue(long now) {
		return now - this.lastSendAt >= HEARTBEAT_INTERVAL_MILLIS;
	}

	/** 记录一次发包（音频块或心跳），用于心跳判定。 */
	public void markHeartbeatSent(long now) {
		this.lastSendAt = now;
	}


	/**
	 * 记一次发送并安排下一次。
	 *
	 * <p><b>配速锚点必须是「已经推给客户端的音频毫秒数」</b>，即
	 * {@code sentBytesFromStart * 1000 / bytesPerSecond}。
	 * 曾经用 {@code source.positionMillis()} 当锚点，那是读线程的<b>预读</b>位置：
	 * 读线程在第一包发出之前就已经把队列灌到 {@link #MAX_QUEUED_BYTES}（64KB ≈ 4 秒），
	 * 于是首包一发出锚点就被推到 4263ms，下一包要等 4 秒——听感正是
	 * 「先响一下、沉寂一段、才进入稳定主推流」。
	 * 已发送量则精确等于客户端该播到的位置，从定义上不会领先。
	 *
	 * <p>每块时长由 {@link radiosmile.radio.AudioDurationEstimator} 实测得出
	 * （逐帧累加 {@code 1152/sampleRate}），与码率换算严格自洽（实测误差 0.5%）。
	 */
	public void markSent(long now, int bytes, byte[] data) {
		// 正常发包也算"活着"，同步刷新心跳计时
		this.lastSendAt = now;
		long intervalMs = radiosmile.radio.AudioDurationEstimator.estimateMillis(data, this.bytesPerSecond);
		intervalMs = Math.max(20L, Math.min(1000L, intervalMs));

		this.totalBytesSent += bytes;
		this.sentBytesFromStart += bytes;

		// <b>锚点必须按「本块真实时长」累加，绝不能用「字节数 ÷ 码率」换算</b>。
		//
		// <h2>这曾经是播放列表「丢一半包 + 另一半变噪音」的根因</h2>
		// 旧写法是 {@code audioSentMillis = sentBytesFromStart * 1000 / bytesPerSecond}。
		// 对码率恒定的单曲没问题，但播放列表里
		// {@link radiosmile.radio.PlaylistAudioSource#bytesPerSecond()} 返回的是
		// <b>整条列表的平均码率</b>，而每首曲目的实际码率可能差一倍以上。
		//
		// <p>实测 realtest 列表：平均 <b>172.8 kbps</b>，但曲目分别是
		// <b>128 kbps</b>（本地转码）与 <b>320 kbps</b>（网易云）。
		// 于是：
		// <ul>
		//   <li>播 128kbps 曲目时，同样的字节被换算成 <b>1.74 倍</b>的毫秒数
		//       → {@code desired} 大幅提前 → 服务端以约 <b>1.35 倍实时</b>速度猛发；</li>
		//   <li>客户端缓冲持续净增长，撞上 3 秒上限后 {@code dropFront} 丢弃最旧数据
		//       → 听感「一长串音频有规律地少了一半」；</li>
		//   <li>播 320kbps 曲目时反向偏低到 <b>0.54 倍速</b>，缓冲被吃空、大片欠载
		//       → 那段时间只剩静音/底噪，听感「丢的那一半变成了噪音」。</li>
		// </ul>
		//
		// <p>现在改为<b>直接累加本块的真实音频时长</b>：MP3 帧头自带比特率与采样率，
		// {@link radiosmile.radio.AudioDurationEstimator} 逐帧累加得到的毫秒数，
		// 就是「这块音频本来要播多久」的<b>精确定义</b>，与 {@code bytesPerSecond}
		// 这个字段是否准确完全无关。因此即使列表里混着 128k/320k、
		// 甚至将来混入 VBR MP3，配速也不会失真。
		//
		// <p>{@code intervalMs} 已钳制在 [20,1000]，累加它同时保证单块异常值
		// 不会把时间轴带飞（与它原本作为「下次发送间隔」的口径保持一致）。
		this.audioSentMillis += intervalMs;

		// 预热阶段不限速：全速补发历史内容，发送完立刻继续下一块。
		// 此时不建立时间轴——预热包客户端是丢弃的，不该占配速；
		// 时间轴留给预热结束后的首包来锚定，否则起点会回推几十分钟。
		if (this.priming) {
			this.consumePrime(intervalMs);
			this.nextSendAt = now;
			return;
		}

		// 首包：把时间轴起点回推到「本包应开始播放的时刻」，即
		// now 减去本包<b>之前</b>已发送的音频时长（audioSentMillis 此时已含本包）。
		// 这样 nextSendAt = streamStartAt + audioSentMillis = now + intervalMs，
		// 首包立即发出、下一包在一个块时长后发出，中间不会空转。
		if (!this.streamStarted) {
			this.streamStarted = true;
			// 本包<b>之前</b>推出去的量：直接用累加值减去本块时长即可，
			// 不再走「字节 ÷ 码率」那条会失真的换算（原因见上面锚点处的说明）。
			long sentBeforeMillis = Math.max(0L, this.audioSentMillis - intervalMs);
			this.streamStartAt = now - sentBeforeMillis;
			this.nextSendAt = this.streamStartAt + this.audioSentMillis;
			return;
		}

		// <b>闭环配速</b>：不累加 now + interval。
		// 开环累加会把每次调度的误差逐次累积成漂移，表现为播放节奏逐渐脱节、
		// 周期性卡顿。这里按「已发送音频总时长」算出锚定时间轴的下一拍，
		// 某拍晚了下一拍会自然补上。
		long desired = this.streamStartAt + this.audioSentMillis;
		if (desired < now - LATE_TOLERANCE_MILLIS) {
			// 落后超过 200ms：客户端缓冲已被吃空。
			// 重新锚定，让后续按正常节拍继续，而不是突发补发一串块
			// （补发会让客户端缓冲一次涌入过多数据，听感更卡）。
			//
			// 新锚点要停在「本包彻底播完」的时刻（now + intervalMs），不能直接用 now：
			// 用 now 会让下一包在 5ms 后立刻发出，等于额外往客户端缓冲里多塞一块，
			// 之后又要靠丢弃纠正。锚到本包末尾则间隔仍是标准的一个块时长。
			this.streamStartAt = now - this.audioSentMillis + intervalMs;
			desired = now + intervalMs;
		}
		this.nextSendAt = desired;
	}

	public ServerPlayer player() {
		return this.player;
	}

	public BlockPos pos() {
		return this.pos;
	}

	public RadioStation station() {
		return this.station;
	}

	public AudioSource source() {
		return this.source;
	}

	/**
	 * 当前曲目序号（播放列表换曲后随之变化）。
	 *
	 * <p>随每个音频块下发，客户端据此识别「换曲」并重建解码器；
	 * 若恒为 0，换曲后客户端会把新曲的 MP3 帧继续喂给旧解码器，
	 * 听感是两首歌糊在一起。
	 */
	public int trackIndex() {
		return this.source.trackIndex();
	}

	/**
	 * 当前流会话号（随块下发）。
	 *
	 * <p>网络流重连后递增。客户端据此重建解码器——
	 * 重连后的字节与断线前不连续，若当成同一条流继续解，
	 * 听感就是声音突然「卡回」到断线前的位置附近。
	 */
	public int sessionId() {
		return this.source.sessionId();
	}

	public long totalBytesSent() {
		return this.totalBytesSent;
	}

	public boolean isActive() {
		return !this.closed;
	}

	/** 该订阅创建时客户端声明的带宽期望（重建订阅时要沿用）。 */
	public int bandwidthKbps() {
		return this.bandwidthKbps;
	}

	/** 该订阅创建时客户端声明的单块上限（重建订阅时要沿用）。 */
	public int clientMaxChunk() {
		return this.clientMaxChunk;
	}

	public int queuedBytes() {
		synchronized (this.queue) {
			return this.queuedBytes;
		}
	}

	public void deactivate() {
		closeSource();
	}

	public void closeSource() {
		if (this.closed) {
			return;
		}
		this.closed = true;
		synchronized (this.queue) {
			this.queue.clear();
			this.queuedBytes = 0;
			this.queue.notifyAll();
		}
		this.reader.interrupt();
		this.source.close();
	}
}