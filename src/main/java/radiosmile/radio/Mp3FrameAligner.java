package radiosmile.radio;

/**
 * 让音频分块尽量对齐 MP3 帧边界。
 *
 * <p><b>为什么需要</b>：服务端按自适应块大小切分字节流，切点可能落在一帧 MP3 数据中间。
 * 接收端解码器虽然能重新搜索帧同步，但每块开头被丢弃的残帧会累积成爆音与卡顿；
 * 若块切得过碎（小于一帧 192 字节），甚至可能永远凑不出完整帧。
 *
 * <p><b>做法</b>：在切点附近向后搜索帧同步字（{@code 0xFF 0xEx} + 合法索引），
 * 把这一帧完整包含进当前块。如果搜索窗口内找不到（例如本来就是 OGG 或纯数据），
 * 就退回按原大小切分，不强行对齐。
 *
 * <p>只处理 MP3（通过魔数与帧头合法性判断），其它格式走原逻辑。
 */
public final class Mp3FrameAligner {
	/** 向后搜索同步字的最大距离：要能覆盖一帧最大长度（320kbps/32kHz ≈ 1441 字节）。 */
	private static final int MAX_SEARCH = 2048;
	/** 至少要留这么多字节给下一块，避免把流切得过于零碎。 */
	private static final int MIN_TAIL = 256;

	private static final int[] BITRATE_V1_L3 = {
			0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0
	};
	private static final int[] SAMPLE_RATE_V1 = {44100, 48000, 32000, 0};

	private Mp3FrameAligner() {
	}

	/**
	 * 计算对齐后的切分长度。
	 *
	 * @param buffer    待发送的完整缓冲
	 * @param available 缓冲中有效字节数
	 * @param desired   期望的块大小
	 * @return 实际应发送的字节数（≤ available）。若无法对齐则返回 {@code desired} 与 available 的较小值
	 */
	public static int align(byte[] buffer, int available, int desired) {
		if (available <= desired + MAX_SEARCH) {
			// 剩余数据不足以在窗口内搜索，直接全发
			return Math.min(desired, available);
		}
		int limit = Math.min(available - 4, desired + MAX_SEARCH);
		for (int i = desired; i < limit; i++) {
			if (isValidFrameStart(buffer, i)) {
				// 切在这一帧之前，让该帧完整留在下一块里
				int cut = i;
				if (cut - desired > MAX_SEARCH) {
					break;
				}
				if (available - cut < MIN_TAIL) {
					// 切完剩太少，干脆多送一些把尾巴也带上
					return available;
				}
				return cut;
			}
		}
		return Math.min(desired, available);
	}

	private static boolean isValidFrameStart(byte[] buf, int i) {
		if (i + 3 >= buf.length) {
			return false;
		}
		if ((buf[i] & 0xFF) != 0xFF || (buf[i + 1] & 0xE0) != 0xE0) {
			return false;
		}
		int h1 = buf[i + 1] & 0xFF;
		int h2 = buf[i + 2] & 0xFF;
		int versionBits = (h1 >> 3) & 0x03;
		int layerBits = (h1 >> 1) & 0x03;
		int bitrateIndex = (h2 >> 4) & 0x0F;
		int sampleRateIndex = (h2 >> 2) & 0x03;
		if (versionBits != 3 || layerBits != 1) {
			// 只认 MPEG1 Layer3（网络电台最常见）
			return false;
		}
		if (bitrateIndex == 0 || bitrateIndex == 15 || sampleRateIndex == 3) {
			return false;
		}
		int bitrate = BITRATE_V1_L3[bitrateIndex];
		int sampleRate = SAMPLE_RATE_V1[sampleRateIndex];
		if (bitrate <= 0 || sampleRate <= 0) {
			return false;
		}
		int frameLength = 144000 * bitrate / sampleRate + ((h2 >> 1) & 1);
		return i + frameLength <= buf.length;
	}
}
