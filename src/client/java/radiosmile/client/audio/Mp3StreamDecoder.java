package radiosmile.client.audio;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;

import javazoom.jl.decoder.Bitstream;
import javazoom.jl.decoder.BitstreamException;
import javazoom.jl.decoder.Decoder;
import javazoom.jl.decoder.Header;
import javazoom.jl.decoder.SampleBuffer;

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
		try {
			this.sink.write(data, offset, length);
			this.sink.flush();
		} catch (Exception e) {
			// 管道关闭属正常退出路径
			this.closed = true;
		}
	}

	/**
	 * 解码循环：逐帧解出 PCM 并交给 {@link PcmSink}。
	 *
	 * <p>JLayer 的 {@code SampleBuffer} 输出 16-bit 小端 PCM，可直接送声卡。
	 */
	private void decodeLoop() {
		Bitstream bitstream = new Bitstream(new BufferedInputStream(this.source, 1 << 16));
		Decoder decoder = new Decoder();
		// 需要丢弃的采样帧数（用于对齐播放位置）
		long framesToSkip = -1;
		long skippedFrames = 0;
		try {
			while (!this.closed) {
				Header header;
				try {
					header = bitstream.readFrame();
				} catch (BitstreamException e) {
					// 数据不完整：等更多数据再试
					if (this.closed) {
						break;
					}
					continue;
				}
				if (header == null) {
					break;
				}
				this.sampleRate = header.frequency();
				this.channels = header.mode() == Header.SINGLE_CHANNEL ? 1 : 2;

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
				bitstream.closeFrame();
			}
		} catch (Throwable t) {
			// 流结束 / 断开都走这里，不视为错误
		} finally {
			try {
				bitstream.close();
			} catch (Exception ignored) {
				// 忽略
			}
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