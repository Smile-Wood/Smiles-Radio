package radiosmile;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import radiosmile.radio.RadioStationRegistry;

/**
 * {@code /radio reload}：重新加载电台配置并重置播放进度。
 *
 * <h2>为什么需要这条命令</h2>
 * 原版 {@code /reload} 只重载<b>数据包</b>（配方、战利品表、函数…）。
 * 本模组的配置放在存档的 {@code serverconfig/radio-smile/} 目录下，
 * 与数据包无关，因此 {@code /reload} 天然管不到它——
 * 服主改了 {@code stations.json} 之后只能重启服务器。
 *
 * <p>更隐蔽的一点是<b>播放进度</b>：进度由 {@code startEpochMillis} 决定
 * （{@code (now - epoch) % 时长}）。这个基准只在配置加载时重置，
 * 所以「重载后从头开始播」在早期版本里根本不成立，进度会一直延续旧的。
 *
 * <h2>命令形式</h2>
 * 用 {@code /radio <子命令>} 而不是几个平铺的顶层命令：
 * 后续要加 {@code /radio list}、{@code /radio stopall} 之类不会污染命令树。
 *
 * <h2>与 /reload 的关系</h2>
 * 两者都能触发配置重载（{@code /reload} 走资源重载监听器，见 {@link RadioReloadListener}），
 * 但这条命令是<b>显式、可确认</b>的：会回显频道数与新的计时基准，服主能立刻确认生效。
 */
public final class RadioCommand {
	private RadioCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("radio")
				// 权限等级 2：与 /reload 一致，普通玩家不能用
				.requires(source -> source.hasPermission(2));

		root.then(Commands.literal("reload").executes(RadioCommand::reload));
		root.then(Commands.literal("status").executes(RadioCommand::status));

		dispatcher.register(root);
	}

	/** 重新加载配置（含计时基准重置）。 */
	private static int reload(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		MinecraftServer server = source.getServer();

		RadioStationRegistry.load(server.getWorldPath(
				net.minecraft.world.level.storage.LevelResource.ROOT));

		// 关键：只改计时基准是不够的——已建立的订阅持有固定的文件指针，
		// 必须重建才能让所有人真正从新位置开始播（详见 AudioStreamManager#rebuildAll）。
		radiosmile.net.AudioStreamManager.rebuildAll();

		// 通知所有客户端「发生了重载」：让它们播调台噪盖住这次跳变。
		notifyClientsReloaded(server);

		int count = RadioStationRegistry.stations().size();
		long epoch = RadioStationRegistry.startEpochMillis();
		source.sendSuccess(() -> Component.translatable(
				"command.radio-smile.reloaded", count, epoch), true);
		return count;
	}

	/**
	 * 通知所有在线客户端「服务端重载了电台配置」。
	 *
	 * <p>客户端收到后播一段调台噪，把重建订阅造成的跳变盖住——
	 * 否则听感就是「音乐突然跳到别的位置」，比有噪声更难理解。
	 */
	static void notifyClientsReloaded(MinecraftServer server) {
		for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (!net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
					.canSend(player, radiosmile.net.RadioNetworking.S2C_RELOADED)) {
				continue;
			}
			var buf = new net.minecraft.network.FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
			net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(
					player, radiosmile.net.RadioNetworking.S2C_RELOADED, buf);
		}
	}

	/** 只报告当前状态，不重载。 */
	private static int status(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		int count = RadioStationRegistry.stations().size();
		long epoch = RadioStationRegistry.startEpochMillis();
		source.sendSuccess(() -> Component.translatable(
				"command.radio-smile.status", count, epoch), false);
		return count;
	}
}
