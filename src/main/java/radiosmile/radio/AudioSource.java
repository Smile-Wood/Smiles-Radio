package radiosmile.radio;

import java.io.IOException;

/**
 * 一个可流式读取的音频源。
 *
 * <p>两种实现：
 * <ul>
 *   <li><b>本地文件</b>：顺序读取原始字节</li>
 *   <li><b>网络流</b>：服务端代为拉取上游 HTTP 流并转发给客户端</li>
 * </ul>
 *
 * <p>服务端只做「搬运」，不做解码：MP3 的原始字节直接透传给客户端，
 * 由客户端解码播放。这样服务端 CPU 开销极低，且不依赖任何音频解码库。
 * 本地音频在推流前已由 ffmpeg 统一转码成 MP3（见 {@link AudioTranscoder}）。
 */
public interface AudioSource {
	/**
	 * 读取至多 {@code maxBytes} 字节。
	 *
	 * @return 实际读到的字节；返回 {@code null} 或空数组表示流结束
	 */
	byte[] read(int maxBytes) throws IOException;

	/** 该源总时长（毫秒）；未知返回 {@code -1}（网络直播流通常未知）。 */
	long durationMillis();

	/**
	 * 音频容器格式。
	 *
	 * <p>由服务端给出，客户端据此选择解码器。不能靠客户端猜首块字节——
	 * 服务端支持 seek 后首块不在文件头，猜测会误判。
	 */
	default radiosmile.radio.AudioFormat format() {
		return radiosmile.radio.AudioFormat.UNKNOWN;
	}

	/**
	 * 需要客户端跳过的播放时长（毫秒）。
	 *
	 * <p>正常情况下恒为 0：MP3 帧自包含，能按字节偏移直接定位到帧边界，
	 * 不需要「从头推、客户端丢弃前段」那种绕路。
	 * 保留这个能力是为了兜底（时长解析失败时至少还能从头播并对齐）。
	 */
	default long skipMillis() {
		return 0L;
	}

	/**
	 * 当前真实播放位置（毫秒）。
	 *
	 * <p>用于校正漂移：以文件实际读取进度为准，而不是累加估算的块时长。
	 *
	 * @return 位置毫秒；未知返回 -1
	 */
	default long positionMillis() {
		return -1L;
	}

	/**
	 * 跳到指定播放位置。
	 *
	 * @param positionMillis 目标位置（毫秒）
	 * @return 是否成功；不可定位的格式返回 false
	 */
	default boolean seekTo(long positionMillis) {
		return false;
	}

	/** 源数据总字节数；未知返回 -1。用于把播放位置换算成字节偏移。 */
	default long totalBytes() {
		return -1L;
	}

	/**
	 * 本音源在播放列表中的序号。
	 *
	 * <p>客户端按 {@code (坐标, 频道)} 维护解码器，序号变化即「换曲」：必须重建解码器，
	 * 否则上一首的 MP3 帧会被继续解进新曲目的 PCM 里，听感是两首歌糊在一起。
	 * 单文件 / 网络流恒为 0。
	 */
	default int trackIndex() {
		return 0;
	}

	/**
	 * 流会话号：上游断线并重连后递增。
	 *
	 * <p>重连后的字节与断线前的<b>不连续</b>（缺了断线期间的内容），
	 * 但 MP3 帧自包含，解码器会把它们当成同一条流继续解——
	 * 听感是声音突然「卡回」到断线前的位置附近。
	 * 客户端发现会话号变化时必须重建解码器并清空已缓冲数据。
	 *
	 * <p>非流式音源恒为 0。
	 */
	default int sessionId() {
		return 0;
	}

	/**
	 * 精确码率（字节/秒）；未知返回 0。
	 *
	 * <p>服务端按「本块承载多少毫秒」配速，换算基数就是码率。
	 * 本地文件可由体积/时长直接算出精确值；网络流只能靠读速自适应估算。
	 * 返回 0 表示「请用自适应估算」。
	 */
	default int bytesPerSecond() {
		return 0;
	}

	/** 是否已结束（本地文件读完 / 网络流断开）。 */
	boolean isFinished();

	/**
	 * 提示性重置/重开（用于本地文件在循环点回到开头）。
	 *
	 * <p>网络流不支持 seek，实现应为空操作。
	 */
	default void reopen() throws IOException {
	}

	/** 关闭底层资源。实现不应向外抛异常，失败只记日志。 */
	void close();
}