package radiosmile.radio;

import java.io.IOException;
import java.nio.file.Path;

import radiosmile.SmileSRadio;

/**
 * 播放列表音频源：在一首播完后<b>自动接下一首</b>，末尾回到开头。
 *
 * <h2>为什么需要这个包装</h2>
 * 早期 {@code readLoop} 读到文件末尾时调用 {@code reopen()}，而 {@code reopen()} 的语义是
 * 「按服务端计时基准在<b>同一个文件</b>内重新定位」——对单文件循环是对的，
 * 对播放列表则等于永远原地重播第一首：既不会顺着往下播，
 * {@code random} 模式也不会切到下一首。这正是「播到列表末尾不会重头再播或者继续随机」的原因。
 *
 * <p>这里把「换曲」收进音源自身：{@link #read(int)} 遇到当前曲目读完，
 * 就按 {@link PlaylistTimeline#nextIndex(int)} 打开下一首（末尾回到 0），
 * 然后继续返回数据。上层 {@code AudioSubscription} 的读线程无需知道播放列表的存在。
 *
 * <h2>线程安全</h2>
 * 换曲会替换内部 {@code current}，而 {@code read()} 由订阅的读线程调用、
 * {@code trackIndex()} 由推送线程调用，因此两者都加锁。
 * 与 {@code AudioSubscription} 的分工：一个订阅只有一个读线程，
 * 不会并发 {@code read()}，加锁只为保护「换曲」与「读序号」的交替。
 */
public final class PlaylistAudioSource implements AudioSource {
	private final PlaylistTimeline timeline;
	/** 当前轮次。 */
	private int round;
	/** 当前曲目在本轮内的序号。 */
	private int index;
	/** 当前曲目的底层音源。 */
	private AudioSource current;
	private volatile boolean closed;

	public PlaylistAudioSource(PlaylistTimeline timeline, int startRound, int startIndex,
			AudioSource startSource) {
		this.timeline = timeline;
		this.round = startRound;
		this.index = startIndex;
		this.current = startSource;
	}

	/**
	 * 读取数据；当前曲目读完则自动切到下一首继续读。
	 *
	 * <p>只有「整条列表都无法产出数据」时才返回 {@code null}，
	 * 从而让上层的「流结束」分支只在整个播放列表真的不可用时触发。
	 */
	@Override
	public synchronized byte[] read(int maxBytes) throws IOException {
		while (!this.closed) {
			byte[] data = this.current.read(maxBytes);
			if (data != null && data.length > 0) {
				// 新曲的第一个非空块到手：预载结束。
				this.preloading = false;
				return data;
			}
			// 当前曲目读完：切下一首（末尾回到第 0 首）
			//
			// <b>从这里到下一圈读到数据之间是「预载窗口」</b>：关旧曲、开新曲、
			// 探测时长全在这个窗口里，期间一个字节也产不出来。推送线程靠
			// {@link #isPreloadingTracks()} 认出这段窗口并下发空音量包，
			// 免得客户端把它当弱网响底噪。
			this.preloading = true;
			if (!this.advance()) {
				this.preloading = false;
				return null;
			}
		}
		return null;
	}

	/** 是否正在预载下一首（读线程卡在 {@link #advance()} 里）。 */
	private volatile boolean preloading;

	/**
	 * 是否正在<b>换曲预载</b>。
	 *
	 * <p>由读线程置位、推送线程读取，因此必须是 {@code volatile}——
	 * 两边是不同线程，靠它传递「现在这段没数据是正常的」这一判断。
	 */
	@Override
	public boolean isPreloadingTracks() {
		return this.preloading;
	}

	/**
	 * 切到下一首并打开它。
	 *
	 * @return 是否成功切到一首可读的曲目
	 */
	private boolean advance() {
		int size = this.timeline.size();
		if (size <= 0) {
			return false;
		}
		int nextIndex = this.index + 1;
		int nextRound = this.round;
		if (nextIndex >= size) {
			// 本轮播完：生成下一轮（随机模式会重新洗牌并避开上一轮末尾的曲目）
			nextIndex = 0;
			nextRound = this.round + 1;
			SmileSRadio.LOGGER.info("播放列表进入第 {} 轮", nextRound + 1);
		}
		Path file = this.timeline.fileAt(nextRound, nextIndex);
		if (file == null) {
			return false;
		}
		AudioSource opened = openTrack(file, nextIndex);
		if (opened == null) {
			// 这一首打不开（文件被删/尚在转码）：跳过它继续找，最多绕一整轮
			int probeRound = nextRound;
			int probe = nextIndex;
			for (int i = 0; i < size - 1; i++) {
				probe++;
				if (probe >= size) {
					probe = 0;
					probeRound++;
				}
				AudioSource retry = openTrack(this.timeline.fileAt(probeRound, probe), probe);
				if (retry != null) {
					return this.swapTo(probeRound, probe, retry);
				}
			}
			return false;
		}
		return this.swapTo(nextRound, nextIndex, opened);
	}

	private boolean swapTo(int newRound, int newIndex, AudioSource opened) {
		try {
			this.current.close();
		} catch (Throwable ignored) {
			// 旧曲目关闭失败不影响换曲
		}
		this.round = newRound;
		this.index = newIndex;
		this.current = opened;
		SmileSRadio.LOGGER.info("播放列表切曲：第 {} 轮 第 {}/{} 首（{}）",
				newRound + 1, newIndex + 1, this.timeline.size(),
				this.timeline.fileAt(newRound, newIndex).getFileName());
		return true;
	}

	/**
	 * 打开某一首，从第 0 毫秒开始顺序播放（换曲不靠跳过前段）。
	 *
	 * <p>{@code file} 已经是时间轴选好的「实际推流文件」（优先 baked MP3）。
	 */
	private static AudioSource openTrack(Path file, int trackIndex) {
		// 只接受 MP3。OGG 等不可中段解码的格式一律拒绝（返回 null 由 advance() 跳过该曲），
		// 绝不降级推原始 OGG：那条路径在多人同步下必然失步，
		// 且客户端摄入队列丢块后 OGG 无法重同步（致命错误 Failed to read Ogg file N）。
		if (!file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".mp3")) {
			SmileSRadio.LOGGER.warn("播放列表曲目缺少 MP3 转码产物，已跳过该曲：{}", file.getFileName());
			return null;
		}
		try {
			// 从头播（arg2 = 0）；MP3 帧自包含，可安全按字节偏移定位。
			return new FileAudioSource(file, 0L, true, trackIndex);
		} catch (IOException e) {
			SmileSRadio.LOGGER.warn("播放列表曲目打开失败 {}", file.getFileName(), e);
			return null;
		}
	}

	/**
	 * 单轮时长。
	 *
	 * <p>整个播放列表是<b>无限</b>的（一轮接一轮），但对订阅来说「一轮」就是一个循环周期，
	 * 上层用它做定位/回绕计算，所以这里返回每轮总时长。
	 */
	@Override
	public long durationMillis() {
		return this.timeline.roundTotalMillis();
	}

	@Override
	public radiosmile.radio.AudioFormat format() {
		AudioSource source = this.current;
		return source == null ? radiosmile.radio.AudioFormat.UNKNOWN : source.format();
	}

	/**
	 * 需要客户端丢弃的前段时长。
	 *
	 * <p>MP3 路径下为 0：MP3 帧自包含，首曲已由时间轴按字节偏移精确定位，
	 * 后续换曲都从第 0 毫秒开始，不需要丢弃任何 PCM。
	 *
	 * <p>但<b>降级路径</b>（尚未预热 / 未装 ffmpeg，文件是 OGG 等不可中段解码的格式）时，
	 * {@code FileAudioSource} 会把「目标位置」转成 {@code skipMillis} 交给客户端丢弃。
	 * 这里必须如实转发，否则包装器会把这个偏移吞掉——客户端从 0 开始播，
	 * 与其它人不同步（这正是必须修的点）。
	 */
	@Override
	public long skipMillis() {
		AudioSource source = this.current;
		long skip = source == null ? 0L : source.skipMillis();
		return Math.max(0L, skip);
	}

	@Override
	public int trackIndex() {
		return this.index;
	}

	/**
	 * 当前正在播放那一首的文件，供响度归一化按曲取增益。
	 *
	 * <p>直接问 {@code current}，而不是用 {@link #trackIndex()} 去外面按位置查列表：
	 * 换曲时 {@code current} 与 {@code index} 是同一时刻换的，两者天然一致；
	 * 而外部的 {@code resolveTracks()} 顺序一旦与播放列表的打乱/轮转不同步，
	 * 增益就会静默地套到别的曲子上（见 {@link AudioSource#currentFile()}）。
	 *
	 * <p>换曲窗口内 {@code current} 可能为 {@code null}（旧的已关、新的还没开），
	 * 此时返回 {@code null}，调用方回落到兜底增益。
	 */
	@Override
	public java.nio.file.Path currentFile() {
		AudioSource source = this.current;
		return source == null ? null : source.currentFile();
	}

	/**
	 * <b>当前正在播放那一首</b>的码率。
	 *
	 * <h2>这里曾经返回"整条列表的平均码率"，那是错的</h2>
	 * 旧实现的理由是「配速把累计发送字节换算成毫秒，换算基数不能在换曲时跳变」。
	 * 这个担心本身成立，但<b>解法错了</b>：用平均值回避跳变，代价是
	 * <b>每一首的配速都算错</b>。实测 realtest 列表平均 172.8kbps，
	 * 而曲目是 128kbps 与 320kbps 交替——偏差达 +35% / -46%：
	 * 快的那些把客户端缓冲灌满、触发 {@code dropFront} 丢弃最旧音频
	 * （听感「一半音频没了」），慢的那些则让缓冲饿死、大片欠载
	 * （听感「剩下的一半是噪音」）。
	 *
	 * <p>根因的根因是<b>拿字节数去换算毫秒</b>这个做法本身脆弱：
	 * 它要求码率字段在每个瞬间都精确。现在
	 * {@code AudioSubscription.markSent()} 改为直接累加
	 * {@link radiosmile.radio.AudioDurationEstimator} 解析出的<b>本块真实时长</b>，
	 * 配速已与这个字段解耦。因此这里可以如实返回当前曲目的码率，
	 * 让它只服务于「读块大小估算」这一本来用途。
	 */
	@Override
	public int bytesPerSecond() {
		AudioSource source = this.current;
		int live = source == null ? 0 : source.bytesPerSecond();
		// 当前曲目还没打开（或拿不到码率）时退回平均，保证块大小估算不退化
		return live > 512 ? live : this.timeline.averageBytesPerSecond();
	}

	@Override
	public long positionMillis() {
		AudioSource source = this.current;
		return source == null ? -1L : source.positionMillis();
	}

	@Override
	public boolean isFinished() {
		return this.closed;
	}

	/**
	 * 不在此重新定位。
	 *
	 * <p>换曲由 {@link #read(int)} 内部的 {@link #advance()} 负责；
	 * 若这里也去定位，两者会各切一次，表现为「跳歌」。
	 */
	@Override
	public void reopen() throws IOException {
		// 有意留空
	}

	@Override
	public void close() {
		this.closed = true;
		AudioSource source = this.current;
		if (source != null) {
			try {
				source.close();
			} catch (Throwable ignored) {
				// 关闭失败只记日志由底层处理
			}
		}
	}
}
