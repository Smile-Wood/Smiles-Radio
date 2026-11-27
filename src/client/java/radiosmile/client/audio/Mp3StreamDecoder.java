package radiosmile.client.audio;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

import net.minecraft.core.BlockPos;

import radiosmile.SmileSRadio;

/**
 * 流式 MP3 解码器。
 *
 * <p>把服务端推来的 MP3 字节喂进管道，另一头用 JLayer 逐帧解码成 PCM。
 *
 * <p>为什么用 JLayer：MC 自带的 {@code OggAudioStream} 只支持 OGG，
 * JDK 的 {@code javax.sound} 不支持 MP3（实测抛 {@code UnsupportedAudioFileException}），
 * 而网络电台绝大多数是 MP3。JLayer 是纯 Java 实现，已随模组打包。
 */
public final class Mp3StreamDecoder implements AutoCloseable {
	/** 解码后的 PCM 采样率。JLayer 输出与源 MP3 一致的采样率。 */
	private final PipedOutputStream sink;
	private final PipedInputStream source;
	private final Thread decodeThread;
	private volatile boolean closed;
	private volatile int sampleRate = 44100;
	private volatile int channels = 2;

	/**
	 * 解码线程是否「自然收尾」（读到 EOF）。
	 *
	 * <p>与 {@link #closed} 区分开：{@code closed} 是「上层叫停」，
	 * 这里是「数据本身没了」。两者对上层含义相同（不要再往里喂），
	 * 但排查时能一眼看出是哪一种。
	 */
	private volatile boolean ended;

	/** 来源方块，用于应用该收音机的独立音量。 */
	private final long blockPos;
	/** 播放速度倍率：1.0 = 正常。用于让连续流按服务端节奏平滑播放。 */
	private volatile double speed = 1.0;

	/** 需要丢弃的播放时长（毫秒），用于对齐服务端统一时间轴。 */
	private final long skipMillis;

	public Mp3StreamDecoder() {
		this(44100, 2, 0L, 0L);
	}

	public Mp3StreamDecoder(long blockPos) {
		this(44100, 2, blockPos, 0L);
	}

	public Mp3StreamDecoder(long blockPos, long skipMillis) {
		this(44100, 2, blockPos, skipMillis);
	}

	public Mp3StreamDecoder(int initialSampleRate, int initialChannels, long blockPos, long skipMillis) {
		this.blockPos = blockPos;
		this.skipMillis = Math.max(0L, skipMillis);
		this.sampleRate = initialSampleRate;
		this.channels = initialChannels;
		try {
			// 管道容量给足，避免解码线程与网络线程互相等待
			this.source = new PipedInputStream(1 << 16);
			this.sink = new PipedOutputStream(this.source);
		} catch (Exception e) {
			throw new IllegalStateException("初始化 MP3 解码管道失败", e);
		}
		this.decodeThread = new Thread(this::decodeLoop, "RadioSmile-MP3Decode");
		this.decodeThread.setDaemon(true);
		this.decodeThread.start();
	}

	/** 喂入 MP3 字节（来自网络的原始数据块）。 */
	public void feed(byte[] data, int offset, int length) {
		if (this.closed || data == null || length <= 0) {
			return;
		}
		// <b>解码线程已经不在，就不能再往管道里写</b>：
		// 管道有 64KB 缓冲，没人消费时写满即<b>永久阻塞</b>——
		// 而调用方是全局唯一的音频摄入线程，一阻塞整条链路（包括换台）都停摆。
		// 这里是最后一道闸门：宁可丢掉这一块，也不能把整个客户端卡死。
		if (!this.decodeThread.isAlive() || this.ended) {
			this.closed = true;
			return;
		}
		try {
			this.sink.write(data, offset, length);
			this.sink.flush();
			this.bytesFed.addAndGet(length);
		} catch (Exception e) {
			// 管道关闭属正常退出路径
			this.closed = true;
		}
	}

	/**
	 * 已喂入的字节数，用于识别「字节在流进来、却一个采样都解不出」的僵死状态。
	 *
	 * <h2>为什么需要它（这不是防御性编程，是真实故障）</h2>
	 * JLayer 在首帧之后会切到 {@code STRICT_SYNC}：后续每一帧都必须与首帧
	 * 的 {@code syncword} 匹配，而 {@code syncword = header & 0xFFF80CC0}
	 * <b>把码率索引也算在内</b>（见 jlayer {@code Header.read_header}）。
	 * 因此当字节流中途换成<b>另一个码率的文件</b>（实测 128kbps → 320kbps），
	 * 它<b>永远不会</b>再认为找到一个合法帧：整个后续字节流都被
	 * {@code do{...}while(!sync)} 吃掉，不报错、不产帧、线程也不死。
	 * 症状就是「看着还在收包，却彻底没声音」，而且永远不会自己恢复。
	 *
	 * <p>所以除了「会话号变了就重建解码器」之外，这里再加一道兜底：
	 * 喂进来的字节累计到阈值却还没解出任何一帧，就判定僵死，由上层重建。
	 */
	private final java.util.concurrent.atomic.AtomicLong bytesFed =
			new java.util.concurrent.atomic.AtomicLong();

	/** 上一次成功解出帧时 {@link #bytesFed} 的值。 */
	private volatile long bytesFedAtLastFrame;

	/**
	 * 僵死判据：连续这么多字节没有解出任何一帧（正常约每 1KB 一帧）。
	 *
	 * <p>取 128 KB 而不是更小：管道本身只有 64 KB，{@code feed()} 写满就会阻塞，
	 * 所以「已喂入」最多领先「已解码」一个管道容量。留两倍余量，
	 * 既不会被订阅时的高速预载误判，也远小于「一帧都不出」这种真僵死。
	 */
	private static final long STALL_BYTES = 128L * 1024L;

	/**
	 * 解码线程是否还能继续解数据。
	 *
	 * <p>上层（{@code ClientAudioPlayer.Stream}）每块都会问一次：
	 * 一旦为 {@code false} 就重建解码器。没有这个自愈入口的话，
	 * 解码线程一旦意外结束、或掉进上面那种「永远匹配不上同步字」的状态，
	 * 这条流会<b>永久静音</b>——直到玩家手动换一次台
	 * （那正是玩家反馈的「reload 之后不播了，必须换台才有效」）。
	 */
	public boolean isAlive() {
		if (this.closed || this.ended || !this.decodeThread.isAlive()) {
			return false;
		}
		// 僵死：字节一直在进，却长时间一帧都解不出来
		return this.bytesFed.get() - this.bytesFedAtLastFrame < STALL_BYTES;
	}

	/**
	 * 连续多少次解码失败后强制「重新找同步」。
	 *
	 * <p>JLayer 遇到<b>抛异常</b>的坏帧会自己跳过一个再试，但连续坏数据会让它
	 * 长时间停在「读了就扔」的状态。到阈值就把 Bitstream 整个丢掉重建，
	 * 让它在管道当前位置重新做一次 {@code INITIAL_SYNC}。
	 *
	 * <p>注意这条路径只覆盖「抛异常」的坏数据；真正让玩家「重载后彻底没声音」的
	 * 是<b>不抛异常</b>的那种僵死（码率变化导致 strict sync 永远匹配不上），
	 * 由 {@link #isAlive()} 的字节计数兜住，两者互补。
	 */
	private static final int RESYNC_AFTER_FAILURES = 32;

	/** 失败后让线程歇一下，避免「抛异常 → 立刻重试」的空转（会吃满一个核）。 */
	private static final long FAILURE_BACKOFF_MILLIS = 4L;

	/**
	 * 解码循环：逐帧解出 PCM 并交给 {@link PcmSink}。
	 *
	 * <p>JLayer 的 {@code SampleBuffer} 输出 16-bit 小端 PCM，可直接送声卡。
	 *
	 * <h2>为什么这里不能「一有异常就退出」</h2>
	 * 早期整个循环外面只有一个 {@code catch (Throwable)}，任何一帧解不开
	 * （上游塞了非音频数据、块边界被切断）都会让解码线程<b>静默结束</b>：
	 * 上层以为流还在，继续喂数据，而管道再也读不出一个采样。
	 * 症状是「看着还在收包，却一点声音都没有，换个台就恢复」。
	 * 现在改为：失败计数 → 退避 → 到阈值重建 Bitstream 重新同步。
	 * 另一条同类症状（字节流换了码率、JLayer 卡在 strict sync 里不抛异常）
	 * 由字节计数兜底，见 {@link #isAlive()}。
	 */
	private void decodeLoop() {
		Bitstream bitstream = new Bitstream(new BufferedInputStream(this.source, 1 << 16));
		Decoder decoder = new Decoder();
		int consecutiveFailures = 0;
		boolean failureLogged = false;
		try {
			while (!this.closed) {
				Header header;
				try {
					header = bitstream.readFrame();
				} catch (Throwable e) {
					if (this.closed) {
						break;
					}
					consecutiveFailures++;
					if (!failureLogged) {
						failureLogged = true;
						SmileSRadio.LOGGER.warn("MP3 解码器遇到坏帧，开始重新同步 @{}：{}",
								BlockPos.of(this.blockPos), e.toString());
					}
					if (consecutiveFailures >= RESYNC_AFTER_FAILURES) {
						bitstream = resync(bitstream, consecutiveFailures);
						decoder = new Decoder();
						consecutiveFailures = 0;
					}
					sleepQuietly(FAILURE_BACKOFF_MILLIS);
					continue;
				}
				if (header == null) {
					// 只有管道被关掉才会返回 null（= 流结束），不是异常
					this.ended = true;
					break;
				}
				this.sampleRate = header.frequency();
				this.channels = header.mode() == Header.SINGLE_CHANNEL ? 1 : 2;

				try {
					SampleBuffer output = (SampleBuffer) decoder.decodeFrame(header, bitstream);
					int length = output.getBufferLength();
					if (length > 0) {
						// <b>必须复制</b>：JLayer 的 SampleBuffer 内部缓冲会被后续帧复用。
						// 直接把 getBuffer() 传出去，等混音线程真正消费时数据可能已被下一帧覆盖，
						// 结果是音频顺序彻底错乱（听感：有声音但内容是乱的）。
						// 这个复制是必要的，不是性能浪费。
						short[] pcm = new short[length];
						System.arraycopy(output.getBuffer(), 0, pcm, 0, length);
						PcmSink.INSTANCE.offer(pcm, length, this.sampleRate, this.channels, this.blockPos);
					}
					// 记下「解到这一帧时已经喂了多少字节」：僵死判据的基准点
					this.bytesFedAtLastFrame = this.bytesFed.get();
					bitstream.closeFrame();
					consecutiveFailures = 0;
					// <b>恢复过就不重复报告</b>：同一条流坏一次只报一次，免得刷屏
				} catch (Throwable t) {
					// 这一帧解不开：丢掉它，继续解下一帧。
					// 绝不能让它冒到循环外——那等于永久静音。
					consecutiveFailures++;
					if (!failureLogged) {
						failureLogged = true;
						SmileSRadio.LOGGER.warn("MP3 解码失败，开始重新同步 @{}：{}",
								BlockPos.of(this.blockPos), t.toString());
					}
					try {
						bitstream.closeFrame();
					} catch (Throwable ignored) {
						// 坏帧的收尾本身也可能失败
					}
					if (consecutiveFailures >= RESYNC_AFTER_FAILURES) {
						bitstream = resync(bitstream, consecutiveFailures);
						decoder = new Decoder();
						consecutiveFailures = 0;
					}
					sleepQuietly(FAILURE_BACKOFF_MILLIS);
				}
			}
		} catch (Throwable t) {
			// 走到这里说明是循环本身出了问题（管道异常等），如实记录
			SmileSRadio.LOGGER.warn("MP3 解码线程退出 @{}", BlockPos.of(this.blockPos), t);
			this.ended = true;
		} finally {
			try {
				bitstream.close();
			} catch (Exception ignored) {
				// 忽略
			}
		}
	}

	/**
	 * 丢掉当前 Bitstream，在管道<b>当前位置</b>重新找一个同步字。
	 *
	 * <p><b>不能 close()</b>：{@code Bitstream.close()} 会顺带关掉它包着的管道，
	 * 那之后 {@link #feed} 再也写不进去。这里只是不再引用它，让 GC 回收；
	 * 新实例会在同一个管道上重新扫描 {@code FF Ex} 同步字。
	 *
	 * <p>代价是旧实例内部预读的那几 KB 会丢——那是已经判定为坏的数据，
	 * 丢掉正是目的。
	 */
	private Bitstream resync(Bitstream old, int failures) {
		SmileSRadio.LOGGER.debug("MP3 重新同步（连续 {} 次失败）@{}", failures, BlockPos.of(this.blockPos));
		try {
			return new Bitstream(new BufferedInputStream(this.source, 1 << 16));
		} catch (Throwable t) {
			// 重建失败就继续用旧的：至少不会因为这个问题把线程搞死
			return old;
		}
	}

	private static void sleepQuietly(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	public int sampleRate() {
		return this.sampleRate;
	}

	public int channels() {
		return this.channels;
	}

	@Override
	public void close() {
		this.closed = true;
		try {
			this.sink.close();
		} catch (Exception ignored) {
			// 忽略
		}
		try {
			this.source.close();
		} catch (Exception ignored) {
			// 忽略
		}
	}
}