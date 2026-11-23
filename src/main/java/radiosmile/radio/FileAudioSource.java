package radiosmile.radio;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

import radiosmile.SmileSRadio;

/**
 * 本地音频文件源：顺序读取原始字节（不解码），对任何格式一视同仁。
 *
 * <p>服务端只搬运字节，由客户端解码，所以这里不需要任何解码库。
 *
 * <p>实际只会被喂进 MP3：本地音频在预热阶段就转成 MP3 了（见 {@link AudioTranscoder}）。
 * {@code seekable} 参数保留着，但「不可中段解码的格式」已经从系统里消失了——
 * 那条路曾经是 OGG 的专用通道，也是 {@code Failed to read Ogg file} 的来源。
 */
public final class FileAudioSource implements AudioSource {
	private final Path file;
	private final RandomAccessFile raf;
	private final long durationMillis;
	/** 本文件在播放列表中的序号；单文件恒为 0。 */
	private final int trackIndex;

	/** 精确码率（字节/秒）：由文件体积与时长算出，用于服务端配速与读块大小。 */
	private final int bytesPerSecond;

	/** 单文件（非播放列表）构造：序号恒为 0。 */
	public FileAudioSource(Path file, long startOffsetMillis, boolean seekable) throws IOException {
		this(file, startOffsetMillis, seekable, 0);
	}

	public FileAudioSource(Path file) throws IOException {
		this(file, -1L);
	}

	/**
	 * 创建音频源并 seek 到指定播放位置。
	 *
	 * <p><b>为什么必须 seek</b>：多个玩家在不同时刻连入同一台收音机时，
	 * 只有让所有人从「同一个绝对播放位置」开始，才能听到一致的内容。
	 * 位置由服务端统一下发（{@code (now - epoch) % duration}），
	 * 这里按比例换算成字节偏移直接定位。
	 *
	 * <p>MP3 用「按比例换算字节偏移 + 向后对齐帧同步字」定位：
	 * 字节位置与时间大致线性，{@code offset / duration × fileSize} 已足够接近，
	 * 再对齐到帧边界保证解码器拿到的第一帧是完整的。
	 *
	 * @param startOffsetMillis 目标播放位置（毫秒）；{@code < 0} 表示从头播放
	 */
	/**
	 * 需要客户端丢弃的前段时长（毫秒）。
	 *
	 * <p><b>正常情况下恒为 0</b>：MP3 帧自包含，能按字节偏移直接定位到帧边界，
	 * 不需要「从头推、客户端丢弃前段」这种绕路。
	 *
	 * <p>这段逻辑保留的是<b>兜底能力</b>：万一拿到一个定位失败的文件
	 * （时长解析不出来，没法换算字节偏移），至少还能从头播并靠客户端丢弃对齐，
	 * 而不是直接没声音。
	 */
	private final long skipMillis;

	public FileAudioSource(Path file, long startOffsetMillis) throws IOException {
		this(file, startOffsetMillis, false);
	}

	/**
	 * @param file              音频文件
	 * @param startOffsetMillis 目标播放位置（毫秒）
	 * @param seekable          是否允许按字节偏移直接定位（MP3 为 {@code true}；
	 *                          只应传入 MP3，其他格式定位后无法连续解码）
	 */
	public FileAudioSource(Path file, long startOffsetMillis, boolean seekable, int trackIndex) throws IOException {
		this.file = file;
		this.trackIndex = trackIndex;
		this.raf = new RandomAccessFile(file.toFile(), "r");
		this.durationMillis = radiosmile.radio.AudioDuration.millis(file);

		// 精确码率：体积 / 时长。服务端配速的毫秒换算是以它为基数的，
		// 若让读线程的「全速预读速度」去估，会被放大约 6 倍（预读 80KB/s vs 实播 16KB/s）。
		int bps = 0;
		if (this.durationMillis > 0) {
			try {
				bps = (int) (this.raf.length() * 1000L / this.durationMillis);
			} catch (IOException ignored) {
				// 退回自适应估算
			}
		}
		this.bytesPerSecond = bps > 512 ? bps : 0;

		if (seekable && startOffsetMillis > 0 && this.durationMillis > 0) {
			// MP3：直接按字节偏移定位，客户端无需丢弃
			long target = startOffsetMillis % this.durationMillis;
			long fileSize = this.raf.length();
			long byteOffset = fileSize * target / this.durationMillis;
			// 向前找最近的 MP3 帧同步字，保证从帧边界开始
			byteOffset = this.alignToMp3Frame(byteOffset, fileSize);
			this.raf.seek(byteOffset);
			this.skipMillis = 0L;
			SmileSRadio.LOGGER.info("MP3 定位到 {} ms（字节偏移 {}/{}）",
					target, byteOffset, fileSize);
		} else {
			this.skipMillis = (startOffsetMillis > 0 && this.durationMillis > 0)
					? startOffsetMillis % this.durationMillis
					: 0L;
			if (this.skipMillis > 0) {
				SmileSRadio.LOGGER.info("音频将从 {} ms 处开始（客户端跳过前段）", this.skipMillis);
			}
		}
	}

	/**
	 * 向后搜索最近的 MP3 帧同步字，使读起点落在帧边界上。
	 *
	 * <p>本方法会临时读写文件，但调用点都在 {@link #seekTo(long)} 内部、
	 * 且已持有对象锁，因此不会与读线程冲突。读完立即把指针交回调用方设置。
	 */
	private long alignToMp3Frame(long offset, long fileSize) throws IOException {
		long pos = Math.max(0, offset - 4096);
		long saved = this.raf.getFilePointer();
		try {
			this.raf.seek(pos);
			byte[] buf = new byte[(int) Math.min(65536, fileSize - pos)];
			int read = this.raf.read(buf);
			for (int i = 0; i < read - 4; i++) {
				if ((buf[i] & 0xFF) == 0xFF && (buf[i + 1] & 0xE0) == 0xE0) {
					return pos + i;
				}
			}
			return offset;
		} finally {
			// 恢复原位置，避免影响调用方的后续 seek
			try {
				this.raf.seek(saved);
			} catch (IOException ignored) {
				// 忽略
			}
		}
	}

	@Override
	public long skipMillis() {
		return this.skipMillis;
	}

	@Override
	public int trackIndex() {
		return this.trackIndex;
	}

	@Override
	public int bytesPerSecond() {
		return this.bytesPerSecond;
	}



	@Override
	/**
	 * 读取数据。
	 *
	 * <p><b>必须同步</b>：读线程在后台持续调用本方法，而主线程的
	 * {@link #seekTo(long)} 会移动同一个 {@code RandomAccessFile} 的指针。
	 * 两者不互斥的话，读线程会在跳转后从错误位置继续读，
	 * 结果是数据错乱甚至完全读不到内容（表现为「没声音 / 偶尔是噪声」）。
	 */
	public synchronized byte[] read(int maxBytes) throws IOException {
		if (maxBytes <= 0) {
			return new byte[0];
		}
		byte[] buffer = new byte[maxBytes];
		int read = this.raf.read(buffer);
		if (read <= 0) {
			return null;
		}
		this.bytesRead += read;
		if (read == buffer.length) {
			return buffer;
		}
		byte[] trimmed = new byte[read];
		System.arraycopy(buffer, 0, trimmed, 0, read);
		return trimmed;
	}

	@Override
	public long durationMillis() {
		return this.durationMillis;
	}

	@Override
	public long totalBytes() {
		try {
			return this.raf.length();
		} catch (IOException e) {
			return -1L;
		}
	}

	/** 已从文件读出的字节数，用于换算「真实播放位置」。 */
	private long bytesRead;

	/**
	 * 当前真实播放位置（毫秒）。
	 *
	 * <p>按「已读字节 / 文件总字节 × 总时长」换算。这比用估算的每块时长累加准确得多——
	 * 后者会累积误差，最终必须靠跳段来纠正，听感就是「随机跳段」。
	 *
	 * @return 位置毫秒；时长或文件大小未知时返回 -1
	 */
	@Override
	public long positionMillis() {
		if (this.durationMillis <= 0) {
			return -1L;
		}
		try {
			long size = this.raf.length();
			if (size <= 0) {
				return -1L;
			}
			return this.bytesRead * this.durationMillis / size;
		} catch (IOException e) {
			return -1L;
		}
	}

	/**
	 * 跳到指定播放位置（仅 MP3 等可随机定位的格式有效）。
	 *
	 * @param positionMillis 目标位置
	 * @return 是否成功
	 */
	@Override
	public synchronized boolean seekTo(long positionMillis) {
		if (this.durationMillis <= 0 || positionMillis < 0) {
			return false;
		}
		try {
			long size = this.raf.length();
			long target = positionMillis % this.durationMillis;
			long byteOffset = size * target / this.durationMillis;
			// 对齐探测会读文件，必须在跳转前完成，且不能干扰读指针
			byteOffset = this.alignToMp3Frame(byteOffset, size);
			this.raf.seek(byteOffset);
			this.bytesRead = byteOffset;
			return true;
		} catch (IOException e) {
			return false;
		}
	}

	@Override
	public radiosmile.radio.AudioFormat format() {
		return radiosmile.radio.AudioFormat.ofFile(this.file.getFileName().toString());
	}

	@Override
	public boolean isFinished() {
		try {
			return this.raf.getFilePointer() >= this.raf.length();
		} catch (IOException e) {
			return true;
		}
	}

	/**
	 * 循环回绕。
	 *
	 * <p><b>不能简单 seek(0)</b>：那样每次循环都会把播放位置重置到开头，
	 * 与实际时间流脱节，多人听到的位置会逐渐漂移。
	 * 正确做法是重新按服务端计时基准定位——即「此刻应该播到文件的哪个位置」。
	 */
	@Override
	public void reopen() throws IOException {
		if (this.durationMillis <= 0) {
			this.raf.seek(0);
			return;
		}
		long epoch = radiosmile.radio.RadioStationRegistry.startEpochMillis();
		long elapsed = epoch > 0 ? System.currentTimeMillis() - epoch : 0L;
		long target = elapsed % this.durationMillis;
		long fileSize = this.raf.length();
		long byteOffset = Math.max(0, fileSize * target / this.durationMillis - 2048);
		this.raf.seek(byteOffset);
	}

	public Path file() {
		return this.file;
	}

	@Override
	public void close() {
		try {
			this.raf.close();
		} catch (IOException e) {
			SmileSRadio.LOGGER.debug("关闭音频文件失败 {}", this.file, e);
		}
	}
}