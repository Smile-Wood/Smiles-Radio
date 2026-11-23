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

	private RadioNetworking() {
	}

	public static void registerServer() {
		RadioServerNetwork.register();
	}
}
