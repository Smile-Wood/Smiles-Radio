package radiosmile.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;

import radiosmile.client.config.RadioClientConfig;
import radiosmile.client.radio.ClientRadioNetwork;
import radiosmile.client.render.RadioBlockEntityRenderer;
import radiosmile.registry.ModBlockEntities;

public class SmileSRadioClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 触发配置加载（首次运行会写出默认配置文件）
		RadioClientConfig.get();

		RadioClientHooks.install();
		ClientRadioNetwork.register();

		// 收音机的「故障显示」层：故障时点亮红灯 + 液晶屏写原因。
		// 正常播放时这个渲染器完全不出图（见 RadioBlockEntityRenderer#shouldRender）。
		BlockEntityRendererRegistry.register(
				ModBlockEntities.RADIO, RadioBlockEntityRenderer::new);
	}
}
