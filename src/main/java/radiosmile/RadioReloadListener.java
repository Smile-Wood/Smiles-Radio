package radiosmile;

import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.ResourceManager;

import radiosmile.radio.RadioStationRegistry;

/**
 * 让 {@code /reload} 重新加载电台配置。
 *
 * <h2>解决什么问题</h2>
 * 早期只在 {@link net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents#SERVER_STARTED}
 * 里调用 {@link RadioStationRegistry#load}。但 {@code /reload} 走的是<b>数据包重载</b>，
 * 根本不会触发 SERVER_STARTED——于是：
 * <ul>
 *   <li>改了 {@code stations.json} 后 {@code /reload} 完全无效，必须重启服务器；</li>
 *   <li>更隐蔽的是：<b>播放计时基准 {@code startEpochMillis} 不会重置</b>，
 *       而它决定「此刻该播到第几毫秒」。基准不重置，进度就永远接着旧的一直跑，
 *       服主以为 reload 会从头开始，实际并没有。</li>
 * </ul>
 *
 * <h2>为什么挂在资源重载上</h2>
 * Fabric 没有专门的「服务端 reload 完成」事件，但 {@code /reload} 一定会走
 * 原版的数据包重载流程。注册一个同步资源重载监听器就能拿到这个时机。
 *
 * <p>监听器本身不读任何资源文件（配置在存档的 {@code serverconfig/} 下，
 * 不归资源包管），只借用「重载发生了」这个通知去重新加载电台配置。
 */
public final class RadioReloadListener implements SimpleSynchronousResourceReloadListener {
	/** 监听器标识。 */
	private static final ResourceLocation ID = SmileSRadio.id("radio_config_reload");

	/**
	 * 当前运行的服务端引用。
	 *
	 * <p>重载回调里拿不到「是哪个服务器」，而 {@code MinecraftServer.getServer()} 不是公开 API，
	 * 因此改为在 SERVER_STARTED / SERVER_STOPPING 时记录与清理。
	 */
	private static volatile net.minecraft.server.MinecraftServer server;

	private RadioReloadListener() {
	}

	/** 注册到服务端数据包重载流程（在模组初始化时调用一次）。 */
	public static void register() {
		ResourceManagerHelper.get(PackType.SERVER_DATA).registerReloadListener(
				new RadioReloadListener());
	}

	/** 由主初始化器在服务端启停时维护。 */
	public static void setServer(net.minecraft.server.MinecraftServer running) {
		server = running;
	}

	/**
	 * 当前运行中的服务端（未运行时为 {@code null}）。
	 *
	 * <p>给后台线程用：转码 / 响度补足这类工作跑在独立线程上，
	 * 没有玩家对象可以 {@code getServer()}，只能从这里拿。
	 * <b>调用方必须自己用 {@code server.execute(...)} 跳回主线程</b>再碰游戏状态。
	 */
	public static net.minecraft.server.MinecraftServer currentServer() {
		return server;
	}

	@Override
	public ResourceLocation getFabricId() {
		return ID;
	}

	@Override
	public void onResourceManagerReload(ResourceManager resourceManager) {
		net.minecraft.server.MinecraftServer running = server;
		if (running == null) {
			return;
		}
		// 重新加载电台配置：会重新校验哈希、预热转码，
		// 并把播放计时基准重置为当前时刻（进度从头开始，符合服主对 reload 的预期）。
		RadioStationRegistry.load(running.getWorldPath(
				net.minecraft.world.level.storage.LevelResource.ROOT));

		// 与 /radio reload 保持一致：只改基准不够，必须重建已有订阅，
		// 否则已建好的音源仍带着旧文件指针继续播（进度看起来没重置）。
		radiosmile.net.AudioStreamManager.rebuildAll();
		radiosmile.RadioCommand.notifyClientsReloaded(running);
		SmileSRadio.LOGGER.info("/reload：电台配置已重新加载，订阅已按新基准重建");
	}
}
