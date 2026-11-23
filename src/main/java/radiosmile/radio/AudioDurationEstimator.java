package radiosmile.radio;

/**
 * 估算一段音频字节所代表的播放时长（毫秒）。
 *
 * <p><b>为什么必须精确</b>：服务端按「本块承载多少毫秒音频」决定下一次发送时间。
 * 若只是用「平均码率」粗估，块小的时候误差会被放大，发送节奏就会忽快忽慢——
 * 客户端缓冲时而堆积、时而吃空，听感就是周期性卡顿。
 *
 * <h2>为什么只解析 MP3 帧</h2>
 * 这里算的是<b>服务端真正发出去的字节</b>，而服务端只推 MP3：
 * 本地音频经预热转码成 MP3，网络流是 MP3，OGG 链路已被彻底移除
 * （理由见 {@link AudioFormat}）。所以只要能从 MP3 帧头读出比特率与采样率，
 * 就能按 {@code 144×比特率/采样率} 算帧长并累加出准确时长；
 * 扫不出帧就说明数据还没对齐到帧边界（例如首块从帧中间开始），
 * 这时退回平均码率估算即可，后续块统计上会自我修正。
 */
public final class AudioDurationEstimator {
	private static final int[] MP3_BITRATE_V1_L3 = {
			0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0
	};
	private static final int[] MP3_SAMPLE_RATE_V1 = {44100, 48000, 32000, 0};

	private AudioDurationEstimator() {
	}

	/**
	 * 估算该段字节的播放时长。
	 *
	 * @param data          音频数据
	 * @param fallbackBps   兜底码率（字节/秒），用于无法解析时
	 * @return 时长毫秒；始终返回正数
	 */
	public static long estimateMillis(byte[] data, int fallbackBps) {
		if (data == null || data.length == 0) {
			return 1L;
		}
		long mp3 = estimateMp3(data);
		if (mp3 > 0) {
			return mp3;
		}
		// 兜底：按平均码率
		return Math.max(1L, data.length * 1000L / Math.max(1, fallbackBps));
	}

	/** 逐帧解析 MP3，累加每帧时长。 */
	private static long estimateMp3(byte[] data) {
		int i = 0;
		// 跳过 ID3v2 头
		if (data.length > 10 && data[0] == 'I' && data[1] == 'D' && data[2] == '3') {
			int size = ((data[6] & 0x7F) << 21) | ((data[7] & 0x7F) << 14)
					| ((data[8] & 0x7F) << 7) | (data[9] & 0x7F);
			i = 10 + size;
		}
		int frames = 0;
		long totalMs = 0;
		while (i + 4 <= data.length) {
			if ((data[i] & 0xFF) != 0xFF || (data[i + 1] & 0xE0) != 0xE0) {
				i++;
				continue;
			}
			int h1 = data[i + 1] & 0xFF;
			int h2 = data[i + 2] & 0xFF;
			int versionBits = (h1 >> 3) & 0x03;
			int layerBits = (h1 >> 1) & 0x03;
			int bitrateIndex = (h2 >> 4) & 0x0F;
			int sampleRateIndex = (h2 >> 2) & 0x03;
			int padding = (h2 >> 1) & 0x01;

			if (versionBits != 3 || layerBits != 1
					|| bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) {
				i++;
				continue;
			}
			int bitrate = MP3_BITRATE_V1_L3[bitrateIndex];
			int sampleRate = MP3_SAMPLE_RATE_V1[sampleRateIndex];
			if (bitrate <= 0 || sampleRate <= 0) {
				i++;
				continue;
			}
			int frameLength = 144000 * bitrate / sampleRate + padding;
			if (frameLength <= 4) {
				i++;
				continue;
			}
			// 每帧含 1152 个采样
			totalMs += 1152L * 1000L / sampleRate;
			frames++;
			i += frameLength;
		}
		return frames > 0 ? totalMs : -1;
	}
}
