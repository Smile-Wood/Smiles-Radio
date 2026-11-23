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

		// ---------------------------------------------------------------- 连接生命周期
		//
		// <b>服务端只对"连接"负责，不对"上游网络电台"负责</b>（本轮架构调整）：
		//
		//   玩家掉线 / 退出游戏 / 断网被判定为下线  → DISCONNECT → 清空该玩家全部订阅
		//   玩家重新连回服务器（算重新上线）        → JOIN       → 一切从零，客户端重新订阅
		//
		// 这里"清空订阅"是<b>唯一</b>的重置入口。它同时带来一个重要性质：
		// 玩家只要还连着服务器，就不会因为偶尔收不到上游音频而被踢掉订阅——
		// 那种情况客户端只显示底噪 + NOSIGNAL，等网好或等玩家重连。
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.DISCONNECT.register(
				(handler, server) -> {
					var player = handler.getPlayer();
					if (player == null) {
						return;
					}
					// 上来就全清：包括音频源（网络流会 close 掉那条 HTTP 连接）。
					radiosmile.net.AudioStreamManager.unsubscribeAll(player);
					LOGGER.info("玩家 {} 已下线，已清理其全部音频订阅", player.getName().getString());
				});

		// 玩家上线（含重连回来）：订阅表里不该有它的残留。
		//
		// DISCONNECT 已经清过一次，这里是<b>幂等的保险</b>：
		// 服务端崩溃恢复、或 DISCONNECT 因异常没跑到时，残留订阅会让
		// "订阅数超限"误判、并让 AudioStreamManager 继续往一个已失效的连接发包。
		net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.JOIN.register(
				(handler, sender, server) -> {
					var player = handler.getPlayer();
					if (player == null) {
						return;
					}
					radiosmile.net.AudioStreamManager.unsubscribeAll(player);
					LOGGER.info("玩家 {} 已上线，音频订阅从零开始", player.getName().getString());
				});

		// 服务端停止时统一关闭所有音频源
		ServerLifecycleEvents.SERVER_STOPPING.register(server ->
				radiosmile.net.AudioStreamManager.shutdown());

		LOGGER.info("Smile's Radio 已加载");
	}

	public static ResourceLocation id(String path) {
		return new ResourceLocation(MOD_ID, path);
	}
}