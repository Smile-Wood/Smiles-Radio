package radiosmile.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import radiosmile.RadioClientBridge;
import radiosmile.client.gui.RadioScreen;

/** 客户端侧把实现注册进桥接点。 */
public final class RadioClientHooks {
	private RadioClientHooks() {
	}

	public static void install() {
		RadioClientBridge.openScreen = (currentStationId, blockPos) -> {
			Minecraft client = Minecraft.getInstance();
			client.setScreen(new RadioScreen(currentStationId, blockPos));
		};
		RadioClientBridge.onSelectAck = radiosmile.client.radio.ClientRadioPlayback::onAck;
	}
}
