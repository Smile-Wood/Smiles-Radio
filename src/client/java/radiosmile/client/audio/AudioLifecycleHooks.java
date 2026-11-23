package radiosmile.client.audio;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import radiosmile.SmileSRadio;

/**
 * 客户端音频生命周期清理。
 *
 * <p><b>为什么必须有</b>：音频走的是 {@code SourceDataLine} 这条独立于 MC
 * SoundEngine 的通道，退出世界时游戏只会停掉自己的声音，不会管我们这条线。
 * 之前从没挂任何清理钩子，导致退回标题界面后声音还在放。
 */
public class AudioLifecycleHooks implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 断开连接（回标题 / 断线）：彻底停掉所有播放与声卡通道
		// 断开连接（回标题 / 断线）：彻底停播并释放声卡。
		// 这是唯一需要真正 close 设备的时机——平时换台只静音不关设备。
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			SmileSRadio.LOGGER.info("断开连接，释放音频设备");
			ClientAudioPlayer.stopAll();
			radiosmile.client.audio.PcmSink.INSTANCE.releaseDevice();
		});

		// <b>进入世界 / 重连回到服务器</b>：清掉本地残留的流表（但不关设备，保持热备）。
		//
		// 这一步是"重连后能自动恢复播放"的关键前提：
		// 服务端在玩家下线时已经清空该玩家的订阅，所以重连回来时<b>服务端那边是空的</b>，
		// 必须让客户端也把 {@code STREAMS} 清空，AutoSubscriber 才会认为
		// 「本地没有这条订阅」从而重新发起订阅。
		//
		// 若不清：旧的 Stream 对象还留在表里，{@code isStreaming()} 返回 true，
		// AutoSubscriber 会认为"已经在播了"，于是永远不再订阅——
		// 现象就是「断网重连后收音机永久沉默」。
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			SmileSRadio.LOGGER.info("已连接服务器，清空本地音频流表（将重新订阅）");
			ClientAudioPlayer.stopAll();
		});
	}
}
