package radiosmile.client.audio;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

/**
 * 在主线程跟踪原版音量。
 *
 * <p>音频输出线程不能直接读 {@code Minecraft.options}（渲染线程状态，跨线程访问会卡死客户端），
 * 因此这里每 tick 取一次快照交给混音器。
 *
 * <p>需要两个音量：
 * <ul>
 *   <li>{@link SoundSource#BLOCKS}：方块类音效音量（收音机属于这一类）</li>
 *   <li>{@link SoundSource#MASTER}：总音量</li>
 * </ul>
 * 最终音量 = 方块音量 × 总音量 × 该收音机的独立音量，三者相乘，
 * 天然不会突破原版音量上限。
 */
public class VanillaVolumeTracker implements ClientModInitializer {
	private float lastBlocks = -1.0F;
	private float lastMaster = -1.0F;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			try {
				if (client == null || client.options == null) {
					return;
				}
				float blocks = client.options.getSoundSourceVolume(SoundSource.BLOCKS);
				float master = client.options.getSoundSourceVolume(SoundSource.MASTER);
				if (blocks != this.lastBlocks || master != this.lastMaster) {
					this.lastBlocks = blocks;
					this.lastMaster = master;
					PcmSink.INSTANCE.updateVanillaVolume(blocks, master);
				}

				// 听者位置与朝向都要在主线程取：距离衰减用位置，立体声声像用朝向
				if (client.player != null) {
					PcmSink.INSTANCE.updateListener(
							client.player.getX(), client.player.getY(), client.player.getZ(),
							client.player.getYRot());
				}
			} catch (Throwable ignored) {
				// 选项尚未就绪
			}
		});
	}
}
