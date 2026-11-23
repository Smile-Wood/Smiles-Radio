package radiosmile.radio;

import java.util.Locale;

/**
 * 音频容器格式。
 *
 * <p><b>为什么由服务端判定</b>：客户端靠扫描首块字节「猜」格式并不可靠——
 * 服务端会按时间轴定位，首块不再从文件头开始，看不到文件头标记就必然误判。
 * 服务端知道文件真实扩展名与来源，随数据块直接告知，客户端无需猜测。
 *
 * <p><b>为什么只剩 MP3</b>（详见 HANDOVER 七-1）：
 * <ul>
 *   <li>OGG 无法从中段解码：Vorbis 的 MDCT 重叠相加依赖前序包状态，
 *       页序号跳变就报 {@code Failed to read Ogg file}，而多人同步要求「跳到当前应播位置」；</li>
 *   <li>客户端摄入队列是断头丢弃策略，OGG 丢任意一块即永久失步；MP3 帧自包含可自行重同步。</li>
 * </ul>
 * 因此本地音频一律在预热阶段转成 MP3 再推流，模组内<b>不再存在 OGG 解码路径</b>，
 * 枚举里也就没有再保留 OGG 的必要——留着只会让人以为那条链路还能用。
 */
public enum AudioFormat {
	/**
	 * MP3 / MPEG Layer III（客户端用 JLayer 解码）。
	 *
	 * <p><b>协议编码 = 0</b>：{@link #id()} 直接取 {@link #ordinal()}。
	 */
	MP3,
	/**
	 * 未知：客户端按 MP3 兜底处理（JLayer 会自行搜同步字）。
	 *
	 * <p>只会出现在「服务端确实没有把握」的场合：没有扩展名的网络流地址。
	 * 本地文件不会走到这里——转码产物一定是 .mp3。
	 */
	UNKNOWN;

	/**
	 * 按文件名扩展名判定。
	 *
	 * <p><b>.ogg/.oga 明确返回 {@link #UNKNOWN}，不再返回某个 OGG 值</b>：
	 * 模组里已经没有 OGG 解码器，声明 OGG 等于让客户端去找一个不存在的东西。
	 * 本地 .ogg 在推流前一定已经是转码产物 .mp3（见 {@link AudioTranscoder}），
	 * 所以这里遇到 .ogg 只可能是「服主直接把 ogg 地址填成了网络流」——
	 * 网络流原样透传、不转码，那种配置本就不该被支持，返回 UNKNOWN 如实反映这一点。
	 *
	 * @param fileName 文件名或 URL
	 */
	public static AudioFormat ofFile(String fileName) {
		if (fileName == null) {
			return UNKNOWN;
		}
		String lower = fileName.toLowerCase(Locale.ROOT);
		// 去掉 URL 查询串，避免 "?token=..."
		int query = lower.indexOf('?');
		if (query >= 0) {
			lower = lower.substring(0, query);
		}
		if (lower.endsWith(".mp3")) {
			return MP3;
		}
		return UNKNOWN;
	}

	/**
	 * 协议上的编码值。
	 *
	 * <p>编码值就是声明顺序（{@link #ordinal()}），<b>不额外维护映射表</b>：
	 * 多一张表就多一处可能与客户端不一致的真相。
	 * 代价是<b>改动枚举顺序或增删枚举值都会改变线上编码</b>，服务端与客户端必须同时改，
	 * 否则客户端会把 MP3 当成未知格式（现象：新客户端完全没声音）。
	 * 当前约定：{@code MP3 = 0}、{@code UNKNOWN = 1}。
	 */
	public int id() {
		return this.ordinal();
	}

	/**
	 * 按协议编码取格式。
	 *
	 * <p>越界值一律降级为 {@link #UNKNOWN} 而不是抛异常：这发生在网络包解析路径上，
	 * 抛异常会直接打断客户端解码线程。降级后最多是「播不出声」，还能看到日志。
	 */
	public static AudioFormat byId(int id) {
		AudioFormat[] values = values();
		return id >= 0 && id < values.length ? values[id] : UNKNOWN;
	}
}
