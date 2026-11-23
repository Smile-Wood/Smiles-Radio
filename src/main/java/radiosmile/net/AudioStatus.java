package radiosmile.net;

/**
 * 音频订阅的状态码，随 {@link RadioNetworking#S2C_AUDIO_STATUS} 下发。
 *
 * <p>用数字而非字符串，省带宽且不受语言影响；客户端按码查翻译键。
 */
public final class AudioStatus {
	/** 订阅成功，随后开始推送数据块。 */
	public static final int OK = 0;
	/** 频道不存在或已被移除。 */
	public static final int UNKNOWN_STATION = 1;
	/** 频道没有可用音频（本地文件缺失 / 播放列表为空）。 */
	public static final int NO_AUDIO = 2;
	/** 距离方块过远（被骗包或走远了）。 */
	public static final int TOO_FAR = 3;
	/** 音频格式不支持（服务端无法解析的容器）。 */
	public static final int UNSUPPORTED_FORMAT = 4;
	/** 服务端已停止推送（重载 / 关服）。 */
	public static final int STOPPED = 5;
	/**
	 * 数据超时：订阅还在，但超过预期时间没有收到任何音频块。
	 *
	 * <p>用于断网 / 上游卡死这类「连接看似有效、实际没数据」的情况。
	 * 由<b>客户端</b>在本地判定（服务端此时本身也无法发包），
	 * 用来点亮红灯并在液晶屏上提示，同时作为自动重连的触发条件。
	 */
	public static final int TIMEOUT = 6;

	private AudioStatus() {
	}

	/**
	 * 液晶屏上显示的故障码，格式 {@code ERRnnn}（如 {@code ERR404}）。
	 *
	 * <h2>为什么用数字码而不是文字</h2>
	 * 液晶屏在贴图上只有 46×10 像素，换算到方块约 0.6×0.19 格，
	 * 实际能放约 7 个字符。中文（"数据超时"）放不下，英文也太长。
	 *
	 * <p>所以用<b>设备式三位数字码</b>：{@code ERR404}。这既是真实设备的观感，
	 * 也符合玩家对 HTTP 状态码的直觉（404 = 找不到）。
	 *
	 * <p><b>只显示在液晶屏上，不输出到聊天栏</b>——故障提示不该打断玩家聊天。
	 *
	 * <h2>编码规则</h2>
	 * 三位数字按 {@link #wireCode(int)} 映射，全部沿用业界已熟知的含义，
	 * 不另造一套需要查表的编号。
	 *
	 * @param status 状态码
	 * @return 形如 {@code ERR404} 的短代码
	 */
	public static String errorCode(int status) {
		// <b>OK 没有错误码</b>，返回空串而不是 "ERR500"。
		//
		// 早期这里无条件拼 "ERR" + wireCode(status)，而 wireCode 的 default 分支是 500，
		// 于是 {@code errorCode(OK)} 会给出 ERR500 —— "正常"被显示成"未知故障"。
		// 当时靠两个外部约定兜住：RadioBlockEntity 把 OK 折成 0、渲染层先判 == OK 就 return。
		// 但那是把不变式放在调用方而不是函数里：任何新调用点漏判就会画出 ERR500。
		// 现在让「正常 → 空串」在函数内部成立。
		if (status == OK) {
			return "";
		}
		return "ERR" + wireCode(status);
	}

	/**
	 * 状态码 → 三位数字码（借用 HTTP/网络故障码的既有语义）。
	 *
	 * <ul>
	 *   <li>{@code 403} Forbidden —— 距离过远（无权收听）</li>
	 *   <li>{@code 404} Not Found —— 频道不存在或已被移除</li>
	 *   <li>{@code 408} Request Timeout —— 数据超时（断网 / 上游卡死）</li>
	 *   <li>{@code 410} Gone —— 服务端已停止推送（重载 / 关服）</li>
	 *   <li>{@code 415} Unsupported Media Type —— 格式不支持</li>
	 *   <li>{@code 422} Unprocessable —— 频道存在但没有可用音频</li>
	 *   <li>{@code 500} Internal Server Error —— 未知错误</li>
	 * </ul>
	 */
	public static int wireCode(int status) {
		return switch (status) {
			case UNKNOWN_STATION -> 404;
			case NO_AUDIO -> 422;
			case TOO_FAR -> 403;
			case UNSUPPORTED_FORMAT -> 415;
			case STOPPED -> 410;
			case TIMEOUT -> 408;
			default -> 500;
		};
	}

	/**
	 * 状态码对应的翻译键。
	 *
	 * <p>注意：<b>不用于聊天栏输出</b>。故障信息只在液晶屏上以
	 * {@link #errorCode(int)} 的短代码呈现，不打扰玩家聊天。
	 * 这个键保留给将来的 GUI 提示（例如鼠标悬停时显示完整原因）。
	 */
	public static String translationKey(int code) {
		return switch (code) {
			case OK -> "gui.radio-smile.status.ok";
			case UNKNOWN_STATION -> "gui.radio-smile.status.unknown_station";
			case NO_AUDIO -> "gui.radio-smile.status.no_audio";
			case TOO_FAR -> "gui.radio-smile.status.too_far";
			case UNSUPPORTED_FORMAT -> "gui.radio-smile.status.unsupported";
			case STOPPED -> "gui.radio-smile.status.stopped";
			case TIMEOUT -> "gui.radio-smile.status.timeout";
			default -> "gui.radio-smile.status.unknown";
		};
	}
}
