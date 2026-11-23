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
				return data;
			}
			// 当前曲目读完：切下一首（末尾回到第 0 首）
			if (!this.advance()) {
				return null;
			}
		}
		return null;
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
	 * 整条列表的平均码率，<b>不是当前这首的</b>。
	 *
	 * <p>服务端配速把累计发送字节换算成毫秒，换算基数在订阅创建时就固定了。
	 * 若这里随换曲返回不同值，换算基数会跳变、配速立刻失真。
	 * 平均值对「顺序播完整条列表」这一稳态是单一基数里误差最小的。
	 */
	@Override
	public int bytesPerSecond() {
		return this.timeline.averageBytesPerSecond();
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
