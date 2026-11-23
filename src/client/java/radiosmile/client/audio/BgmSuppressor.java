package radiosmile.client.audio;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;

import radiosmile.SmileSRadio;

/**
 * 收音机播放期间抑制原版背景音乐（BGM）。
 *
 * <h2>为什么要抑制</h2>
 * 收音机是「持续播放的广播内容」，与原版 BGM 同时响会互相干扰，
 * 听感上非常嘈杂。因此只要有收音机在出声，就停掉 BGM。
 *
 * <h2>为什么不改音量而是停播</h2>
 * {@code MusicManager} 只暴露 {@code stopPlaying()}，没有音量接口。
 * 要做到「压低而不停止」需要 Mixin 侵入原版音量计算，
 * 相比之下直接停播既简单又符合直觉（听广播时不要 BGM）。
 *
 * <p>停播是每 tick 检查一次的幂等操作：原版会自己尝试重启 BGM，
 * 我们每次把它按下去即可，收音机停止后 BGM 会自然恢复。
 */
public class BgmSuppressor implements ClientModInitializer {
	/** 原版 BGM 重启的最小间隔通常远大于 1 tick，这里每 tick 检查足够及时。 */
	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null || client.level == null) {
				return;
			}
			if (!ClientAudioPlayer.isPlaying()) {
				// 没有收音机在播：不再干预，原版 BGM 自然恢复
				return;
			}
			try {
				// 幂等：没在播时调用无副作用
				client.getMusicManager().stopPlaying();
			} catch (Throwable t) {
				SmileSRadio.LOGGER.debug("抑制 BGM 失败", t);
			}
		});
	}
}
