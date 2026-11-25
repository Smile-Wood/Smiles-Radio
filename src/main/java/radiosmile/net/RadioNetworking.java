package radiosmile.net;

import net.minecraft.resources.ResourceLocation;

import radiosmile.SmileSRadio;

/**
 * 本模组自定义网络通道。
 *
 * <p>通道职责：
 * <ul>
 *   <li>{@link #C2S_REQUEST_STATIONS} 客户端请求电台列表（打开界面 / 点击扫描时）</li>
 *   <li>{@link #S2C_STATIONS} 服务端下发电台列表 + 播放计时基准</li>
 *   <li>{@link #C2S_SELECT_STATION} 客户端确认选择频道</li>
 *   <li>{@link #S2C_SELECT_ACK} 服务端回执（含该频道的音频哈希，用于后续拉流）</li>
 *   <li>{@link #C2S_REQUEST_ICON} 客户端请求某个频道的图标</li>
 *   <li>{@link #S2C_ICON} 服务端下发图标字节</li>
 * </ul>
 */
public final class RadioNetworking {
	public static final ResourceLocation C2S_REQUEST_STATIONS = SmileSRadio.id("c2s_request_stations");
	public static final ResourceLocation S2C_STATIONS = SmileSRadio.id("s2c_stations");
	/** 客户端确认选择频道；附带方块坐标，服务端据此写回该方块的 NBT。 */
	public static final ResourceLocation C2S_SELECT_STATION = SmileSRadio.id("c2s_select_station");
	public static final ResourceLocation S2C_SELECT_ACK = SmileSRadio.id("s2c_select_ack");
	public static final ResourceLocation C2S_REQUEST_ICON = SmileSRadio.id("c2s_request_icon");
	public static final ResourceLocation S2C_ICON = SmileSRadio.id("s2c_icon");

	/**
	 * 订阅音频流。
	 *
	 * <p>客户端带上：频道 id、方块坐标、自己声明的带宽期望（KB/s）与单块上限。
	 * 服务端据此建立订阅并按自适应块大小推送。
	 */
	public static final ResourceLocation C2S_AUDIO_SUBSCRIBE = SmileSRadio.id("c2s_audio_subscribe");
	/** 退订（关闭界面 / 离开方块 / 换台时）。只带方块坐标。 */
	public static final ResourceLocation C2S_AUDIO_UNSUBSCRIBE = SmileSRadio.id("c2s_audio_unsubscribe");
	/**
	 * 音频数据块。
	 *
	 * <p>载荷：方块坐标（定位是哪个收音机）+ 频道 id + 块内音频起始偏移（毫秒）
	 * + 是否关键帧（播放列表换曲时标记，客户端据此丢弃旧缓冲）+ 音频字节。
	 */
	public static final ResourceLocation S2C_AUDIO_CHUNK = SmileSRadio.id("s2c_audio_chunk");
	/**
	 * 订阅被拒绝 / 结束通知。
	 *
	 * <p>载荷：方块坐标 + 原因码。常见原因：频道不存在、音频缺失、超出距离、
	 * 不支持的格式。客户端据此停止等待并提示。
	 */
	public static final ResourceLocation S2C_AUDIO_STATUS = SmileSRadio.id("s2c_audio_status");
	/** 设置某个收音机方块的音量并写回 NBT。载荷：方块坐标 + 音量(0~1)。 */
	public static final ResourceLocation C2S_SET_VOLUME = SmileSRadio.id("c2s_set_volume");

	/**
	 * 服务端已重载电台配置（{@code /radio reload} 或 {@code /reload}）。
	 *
	 * <p>载荷为空。仅用于通知客户端「接下来会有一轮重建订阅带来的跳变」，
	 * 让客户端播一段调台噪把它盖住。客户端的实际重连由服务端重建订阅后
	 * 正常推送的数据块驱动，不依赖这个包。
	 */
	public static final ResourceLocation S2C_RELOADED = SmileSRadio.id("s2c_reloaded");

	/**
	 * 「空音量包」：服务端在<b>换曲预载 / 重载重建</b>期间下发的<b>无音频</b>包。
	 *
	 * <h2>为什么需要它</h2>
	 * 换曲那一刻客户端会关掉旧解码器并 {@code flush()} 声卡，然后重新攒抖动缓冲，
	 * 中间必然有几百毫秒「没有任何音源供上数据」。这段时间与「丢包」在客户端
	 * 的判据里<b>无法区分</b>，于是会被当成弱网：响一段本该只在断网时出现的底噪。
	 *
	 * <p>服务端是<b>知道</b>这次空窗的（它正卡在打开下一首里），所以由它显式声明
	 * 「接下来这段是预载，请输出数字静音、别当故障」是最准确的做法——
	 * 客户端不必靠猜时限，也不必为一个客户端侧的常量去赌各种预载耗时。
	 *
	 * <h2>载荷</h2>
	 * 方块坐标 + 频道 id + 曲目序号（便于日志对齐）+ 本次声明掩护的毫秒数。
	 * <b>没有音频字节</b>：它不参与解码，只影响混音器的故障判定。
	 *
	 * <h2>为什么单开通道而不是复用数据块</h2>
	 * 复用 {@code S2C_AUDIO_CHUNK} 只能靠「载荷为空」表达，而那已经是心跳的语义；
	 * 再叠一层就得分长度、分格式 id，任何一处错位都会被当成 MP3 字节喂给解码器。
	 * 单开通道最坏情况只是「旧客户端不认识这个通道、按原样响底噪」，不会污染音频。
	 */
	public static final ResourceLocation S2C_AUDIO_SILENCE = SmileSRadio.id("s2c_audio_silence");

	/**
	 * 服务端 -> 客户端：推流增益（0.00~1.00），载荷 {@code BlockPos + String + float}。
	 *
	 * <p>第二项是该增益<b>所属的频道 id</b>。增益是「某一台节目的电平系数」，
	 * 不是方块的固有属性——客户端据此判断某个增益包是否仍适用于当前频道，
	 * 换台后迟到的旧台增益因此自动作废，不需要「把增益重置为 1.0」那种
	 * 拿业务值当清理信号的手段。
	 *
	 * <p>独立成通道而不是塞进音频块：不会让旧协议把字段读错、污染音频字节流
	 * （音频块错位会被当成 MP3 字节喂给解码器）。
	 *
	 * <p><b>关于版本兼容</b>：本模组的客户端与服务端必须同版本（Fabric 的
	 * 通道/registry 握手会挡掉混用），所以载荷格式可以直接演进，
	 * 不需要保留旧布局的降级分支。
	 */
	public static final ResourceLocation S2C_OUTPUT_GAIN = SmileSRadio.id("s2c_output_gain");

	private RadioNetworking() {
	}

	public static void registerServer() {
		RadioServerNetwork.register();
	}
}
