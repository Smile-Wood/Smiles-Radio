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

		// 音频数据区起点：跳过 ID3v2 标签。
		//
		// <b>这一步不能省</b>：ID3 里全是 UTF-16 文本，而 UTF-16LE 的 BOM 就是
		// {@code FF FE}——{@code 0xFE & 0xE0 == 0xE0}，正好通过「帧同步字」的判据。
		// 早期 alignToMp3Frame() 只认 {@code FF E0+}，于是从 0 开始向后扫描时，
		// 第一个"命中"往往落在标签内部的文本里（实测 tangtang.mp3：标签长度 36，
		// 音频从 46 开始，却定位到了 21），客户端从垃圾字节开始解码 → 持续噪音。
		long audioStart = 0L;
		long fileSize = this.raf.length();
		try {
			audioStart = detectId3v2Size(this.raf, fileSize);
		} catch (IOException ignored) {
			// 读不到就当没有标签：退化为旧行为，不至于拒绝播放
		}
		this.audioDataStart = audioStart;

		// 精确码率：体积 / 时长。服务端配速的毫秒换算是以它为基数的，
		// 若让读线程的「全速预读速度」去估，会被放大约 6 倍（预读 80KB/s vs 实播 16KB/s）。
		// 用「音频数据区」而不是整文件：ID3 里可能塞着几百 KB 封面图，
		// 算进去会把码率抬高、配速随之偏快。
		int bps = 0;
		if (this.durationMillis > 0 && fileSize > audioStart) {
			bps = (int) ((fileSize - audioStart) * 1000L / this.durationMillis);
		}
		this.bytesPerSecond = bps > 512 ? bps : 0;

		if (seekable && startOffsetMillis > 0 && this.durationMillis > 0) {
			// MP3：直接按字节偏移定位，客户端无需丢弃
			long target = startOffsetMillis % this.durationMillis;
			long byteOffset = mapMillisToBytes(target, fileSize);
			// 向前找最近的 MP3 帧同步字，保证从帧边界开始
			long aligned = this.alignToMp3Frame(byteOffset, fileSize);
			if (aligned < 0L) {
				// 一个合法帧都找不到（极端损坏的文件）：退回「从头播 + 客户端丢前段」，
				// 至少不会把垃圾字节当成音频推给客户端。
				this.raf.seek(0L);
				this.skipMillis = target;
				SmileSRadio.LOGGER.warn("MP3 找不到帧同步字，改为从头播放并丢弃前 {} ms：{}",
						target, file.getFileName());
				return;
			}
			this.raf.seek(aligned);
			this.skipMillis = 0L;
			SmileSRadio.LOGGER.info("MP3 定位到 {} ms（字节偏移 {}/{}，音频起点 {}）",
					target, aligned, fileSize, audioStart);
		} else {
			this.skipMillis = (startOffsetMillis > 0 && this.durationMillis > 0)
					? startOffsetMillis % this.durationMillis
					: 0L;
			if (this.skipMillis > 0) {
				SmileSRadio.LOGGER.info("音频将从 {} ms 处开始（客户端跳过前段）", this.skipMillis);
			}
		}
	}

	/** 音频数据区起点（ID3v2 标签之后）；没有标签时为 0。 */
	private final long audioDataStart;

	/**
	 * 解析 ID3v2 标签总长度。
	 *
	 * <p>格式：{@code "ID3" + 版本(2) + 标志(1) + 同步安全整数(4)}，头固定 10 字节。
	 * 同步安全整数每字节只用低 7 位（{@code b & 0x7F}），所以不能按普通 int 读。
	 * ID3v2.4 若置了 footer 位（标志 {@code 0x10}），还要再多 10 字节。
	 *
	 * @return 音频数据起始偏移；没有 ID3v2 标签时返回 0
	 */
	private static long detectId3v2Size(RandomAccessFile raf, long fileSize) throws IOException {
		if (fileSize < 10L) {
			return 0L;
		}
		byte[] head = new byte[10];
		long saved = raf.getFilePointer();
		try {
			raf.seek(0L);
			if (raf.read(head) < 10) {
				return 0L;
			}
		} finally {
			raf.seek(saved);
		}
		if (head[0] != 'I' || head[1] != 'D' || head[2] != '3') {
			return 0L;
		}
		// 版本字节：0xFF 表示非法，按无标签处理
		if ((head[3] & 0xFF) == 0xFF || (head[4] & 0xFF) == 0xFF) {
			return 0L;
		}
		long size = ((long) (head[6] & 0x7F) << 21)
				| ((long) (head[7] & 0x7F) << 14)
				| ((long) (head[8] & 0x7F) << 7)
				| (head[9] & 0x7F);
		long total = 10L + size;
		if (((head[5] & 0xFF) & 0x10) != 0) {
			total += 10L; // footer
		}
		return Math.min(total, fileSize);
	}

	/**
	 * 把「歌内毫秒」换算成字节偏移。
	 *
	 * <p>换算基准是<b>音频数据区</b>（总长减掉 ID3 标签），不是整个文件：
	 * 标签里的封面图与文本不占播放时间，算进去会让定位整体偏前。
	 * 对小偏移尤其致命——4 ms 在 9.5 MB 上按整文件算是 21 字节（落在标签里），
	 * 按音频区算才是正确的帧附近。
	 */
	private long mapMillisToBytes(long targetMillis, long fileSize) {
		long audioBytes = fileSize - this.audioDataStart;
		if (audioBytes <= 0L) {
			return this.audioDataStart;
		}
		long offset = this.audioDataStart
				+ audioBytes * targetMillis / Math.max(1L, this.durationMillis);
		return Math.max(this.audioDataStart, Math.min(offset, fileSize - 1L));
	}

	/**
	 * 向后搜索最近的 MP3 帧同步字，使读起点落在帧边界上。
	 *
	 * <h2>为什么判据不能只有 {@code FF E0+}</h2>
	 * 早期只检查「首字节 0xFF，次字节高 3 位全 1」。这个条件<b>太松</b>：
	 * UTF-16LE 的 BOM（{@code FF FE}）、以及很多随机字节都能通过，
	 * 一旦落在 ID3 标签里就会把标签当音频推出去——客户端听到的是持续噪音。
	 * 现在改为校验完整的帧头（版本 / 层 / 码率索引 / 采样率索引 / emphasis），
	 * 保留值一律拒绝，误判率降到可以忽略。
	 *
	 * <p>搜索下限是 {@link #audioDataStart}：即便偏移算小了，也不会退进标签。
	 *
	 * @return 帧头位置；整个音频区都找不到时返回 -1
	 */
	private long alignToMp3Frame(long offset, long fileSize) throws IOException {
		long floor = this.audioDataStart;
		long pos = Math.max(floor, offset - 8192L);
		long saved = this.raf.getFilePointer();
		try {
			// 分段扫描：单块 64KB，命中就返回，不必把整个文件读进内存。
			// 最多扫到文件尾——正常情况下第一个块就能命中。
			while (pos < fileSize) {
				this.raf.seek(pos);
				int want = (int) Math.min(65536L, fileSize - pos);
				if (want < 4) {
					return -1L;
				}
				byte[] buf = new byte[want];
				int read = this.raf.read(buf);
				if (read < 4) {
					return -1L;
				}
				// 留 3 字节余量：块末尾的帧头可能跨块，交给下一块的开头处理
				for (int i = 0; i < read - 3; i++) {
					if (isMp3FrameHeader(buf, i)) {
						return pos + i;
					}
				}
				pos += Math.max(1, read - 3);
			}
			return -1L;
		} finally {
			// 恢复原位置，避免影响调用方的后续 seek
			try {
				this.raf.seek(saved);
			} catch (IOException ignored) {
				// 忽略
			}
		}
	}

	/**
	 * 校验 MP3 帧头是否<b>合法</b>（不只是"看起来像"）。
	 *
	 * <p>逐字段检查，任何一个字段取到保留值就否决：
	 * <ul>
	 *   <li>{@code sync}：{@code FF} + 高 3 位全 1；</li>
	 *   <li>{@code version}（bit 4-3）：{@code 01} 是保留值 → 拒绝；</li>
	 *   <li>{@code layer}（bit 2-1）：{@code 00} 是保留值 → 拒绝；</li>
	 *   <li>{@code bitrate}（第 3 字节高 4 位）：{@code 0000}（free）与 {@code 1111}（bad）→ 拒绝；</li>
	 *   <li>{@code sampleRate}（第 3 字节 bit 3-2）：{@code 11} 是保留值 → 拒绝；</li>
	 *   <li>{@code emphasis}（第 4 字节低 2 位）：{@code 10} 是保留值 → 拒绝。</li>
	 * </ul>
	 *
	 * <p>这几个条件一起用之后，{@code FF FE}（UTF-16 BOM）会因为码率索引为 0 被拒——
	 * 正是之前把 ID3 文本当成帧头的那个假阳性。
	 */
	private static boolean isMp3FrameHeader(byte[] buf, int i) {
		if ((buf[i] & 0xFF) != 0xFF) {
			return false;
		}
		int b1 = buf[i + 1] & 0xFF;
		if ((b1 & 0xE0) != 0xE0) {
			return false;
		}
		int version = (b1 >> 3) & 0x03;
		if (version == 1) {
			return false; // reserved
		}
		int layer = (b1 >> 1) & 0x03;
		if (layer == 0) {
			return false; // reserved
		}
		int b2 = buf[i + 2] & 0xFF;
		int bitrate = (b2 >> 4) & 0x0F;
		if (bitrate == 0x00 || bitrate == 0x0F) {
			return false; // free / bad
		}
		int sampleRate = (b2 >> 2) & 0x03;
		if (sampleRate == 0x03) {
			return false; // reserved
		}
		int b3 = buf[i + 3] & 0xFF;
		return (b3 & 0x03) != 0x02; // emphasis reserved
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
			long audioBytes = size - this.audioDataStart;
			if (audioBytes <= 0L) {
				return -1L;
			}
			// 按音频数据区换算：ID3 标签不占播放时间，算进去会让上报位置恒定偏前
			long played = Math.max(0L, this.bytesRead - this.audioDataStart);
			return played * this.durationMillis / audioBytes;
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
			long byteOffset = mapMillisToBytes(target, size);
			// 对齐探测会读文件，必须在跳转前完成，且不能干扰读指针
			long aligned = this.alignToMp3Frame(byteOffset, size);
			if (aligned < 0L) {
				// 没有合法帧：从头开始，让客户端自己丢前段（见 skipMillis 的说明）
				return false;
			}
			this.raf.seek(aligned);
			this.bytesRead = aligned;
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

	/** 响度归一化按「这一首」取增益，见 {@link AudioSource#currentFile()}。 */
	@Override
	public Path currentFile() {
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