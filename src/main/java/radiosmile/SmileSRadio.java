package radiosmile;

import net.fabricmc.api.ModInitializer;

import net.minecraft.resources.ResourceLocation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

import radiosmile.net.RadioNetworking;
import radiosmile.radio.RadioStationRegistry;
import radiosmile.registry.ModBlockEntities;
import radiosmile.registry.ModBlocks;
import radiosmile.registry.ModItemGroups;
import radiosmile.registry.ModItems;

public class SmileSRadio implements ModInitializer {
	public static final String MOD_ID = "radio-smile";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// 方块必须先于物品注册：方块物品的构造需要方块实例。
		ModBlocks.register();
		ModBlockEntities.register();
		ModItems.register();
		ModItemGroups.register();
		RadioNetworking.registerServer();

		// 服务端启动完成时加载电台配置：
		// 内部会校验音频哈希、更新清单，并<b>顺手按哈希预热 MP3 转码缓存</b>
		// （两者共用同一趟遍历，见 RadioStationRegistry#rebuildCacheIfChanged）。
		// 预热完成后玩家订阅即可直接流式传输，首个听众无需等待转码。
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			// 记录 server 供 /reload 与命令使用（见 RadioReloadListener / RadioCommand）
			RadioReloadListener.setServer(server);
			RadioStationRegistry.load(server.getWorldPath(
					net.minecraft.world.level.storage.LevelResource.ROOT));
		});

		// /reload（数据包重载）也要重新加载电台配置并重置播放计时基准。
		// 早期只监听 SERVER_STARTED，导致 /reload 对电台配置完全无效，
		// 而且【播放进度不会重置】——服主以为重载了，实际时间轴还接着旧基准跑。
		RadioReloadListener.register();

		// /radio reload：显式重载配置并重置播放进度（/reload 的语义太泛，
		// 且不保证与我们这条配置相关；给服主一条可确认的命令）。
		RadioCommand.register();

		// 音频推送不再依赖服务端 tick：由独立推送线程按自己的时钟驱动。
		// 之前的实现挂在 END_SERVER_TICK 上，掉 tick 或后台暂停都会导致断流。
		// 线程在第一次有订阅时惰性启动（见 AudioStreamManager）。
		radiosmile.net.AudioStreamManager.startPusher();

		// 玩家断开时清理其订阅，避免泄漏音频源
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
				(handler, server) -> radiosmile.net.AudioStreamManager.unsubscribeAll(handler.getPlayer()));

		// 服务端停止时统一关闭所有音频源
		ServerLifecycleEvents.SERVER_STOPPING.register(server ->
				radiosmile.net.AudioStreamManager.shutdown());

		LOGGER.info("Smile's Radio 已加载");
	}

	public static ResourceLocation id(String path) {
		return new ResourceLocation(MOD_ID, path);
	}
}