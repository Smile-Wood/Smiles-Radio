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

		// 进入世界时清掉上一局残留的数据（但不关设备，保持热备）
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) ->
				ClientAudioPlayer.stopAll());
	}
}
