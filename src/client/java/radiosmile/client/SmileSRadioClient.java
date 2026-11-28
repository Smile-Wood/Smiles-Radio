package radiosmile.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry;
import net.fabricmc.loader.api.FabricLoader;

import radiosmile.SmileSRadio;
import radiosmile.client.config.RadioClientConfig;
import radiosmile.client.radio.ClientRadioNetwork;
import radiosmile.client.render.RadioBlockEntityRenderer;
import radiosmile.registry.ModBlockEntities;

public class SmileSRadioClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 触发配置加载（首次运行会写出默认配置文件）
		RadioClientConfig.get();

		// 前置库只用于渲染配置界面，缺了不影响播放；提示放在客户端，
		// 服务端不会执行这里，也就不会为「没装库」刷日志。
		if (!FabricLoader.getInstance().isModLoaded("smile_libbox")) {
			SmileSRadio.LOGGER.warn("未检测到 Smile's Lib Box：配置界面不可用，"
					+ "可在 config/radio-smile-client.json 里手动改；播放与联机不受影响");
		}

		RadioClientHooks.install();
		ClientRadioNetwork.register();

		// 收音机的「故障显示」层：故障时点亮红灯 + 液晶屏写原因。
		// 正常播放时这个渲染器完全不出图（见 RadioBlockEntityRenderer#shouldRender）。
		BlockEntityRendererRegistry.register(
				ModBlockEntities.RADIO, RadioBlockEntityRenderer::new);
	}
}
