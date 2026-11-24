package radiosmile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import radiosmile.radio.PlaylistTimeline;
import radiosmile.radio.RadioStation;
import radiosmile.radio.RadioStationRegistry;

/**
 * {@code /radio reload}：重新加载电台配置并重置播放进度。
 * {@code /radio reload <频道>}：只刷新一个频道，其他频道不断流。
 * {@code /radio status}：报告频道数与计时基准。
 * {@code /radio play <频道> <序号>}：把某个频道跳到指定曲目的开头。
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

		root.then(Commands.literal("reload")
				.executes(RadioCommand::reload)
				// 带频道名时只刷新那一个频道，其他频道不断流
				.then(Commands.argument("station", StringArgumentType.string())
						.suggests(STATION_SUGGESTIONS)
						.executes(RadioCommand::reloadStation)));
		root.then(Commands.literal("status").executes(RadioCommand::status));
		root.then(Commands.literal("play")
				.then(Commands.argument("station", StringArgumentType.string())
						.suggests(STATION_SUGGESTIONS)
						.then(Commands.argument("track", IntegerArgumentType.integer(1))
								.suggests(TRACK_SUGGESTIONS)
								.executes(RadioCommand::play))));

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
	 * {@code /radio reload <频道>}：只刷新一个频道。
	 *
	 * <p>不重载全局配置、不动计时基准，因此其他频道正在播的内容不受影响；
	 * 只有目标频道的时间轴会被重建，听它的玩家跳到新一轮的开头
	 * （首曲避开刚才那首，见 {@link PlaylistTimeline#of}）。
	 */
	private static int reloadStation(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String query = StringArgumentType.getString(context, "station");
		RadioStation station = findStation(query);
		if (station == null) {
			source.sendFailure(Component.translatable("command.radio-smile.play.unknown", query));
			return 0;
		}
		if (!RadioStationRegistry.reloadStation(station.id())) {
			source.sendFailure(Component.translatable("command.radio-smile.play.unknown", query));
			return 0;
		}
		int listeners = radiosmile.net.AudioStreamManager.rebuildStation(station.id());
		source.sendSuccess(() -> Component.translatable(
				"command.radio-smile.reloaded_station", station.id(), listeners), true);
		return Math.max(1, listeners);
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

	/** 频道名候选：按 id 补全（列表里的名字可能带空格，用引号亦可）。 */
	private static final SuggestionProvider<CommandSourceStack> STATION_SUGGESTIONS =
			(context, builder) -> {
				List<String> ids = new ArrayList<>();
				for (RadioStation station : RadioStationRegistry.stations()) {
					ids.add(station.id());
				}
				return SharedSuggestionProvider.suggest(ids, builder);
			};

	/** 曲目序号候选：按所选频道实际的曲目数补全 1..N。 */
	private static final SuggestionProvider<CommandSourceStack> TRACK_SUGGESTIONS =
			(context, builder) -> {
				RadioStation station = findStation(StringArgumentType.getString(context, "station"));
				if (station == null || !station.isPlaylist()) {
					return SharedSuggestionProvider.suggest(List.of(), builder);
				}
				// 只数文件，不扫时长：补全要即时返回，不能在这里卡住主线程。
				int count = RadioStationRegistry.resolveTracks(station).size();
				List<String> numbers = new ArrayList<>(count);
				for (int i = 1; i <= count; i++) {
					numbers.add(Integer.toString(i));
				}
				return SharedSuggestionProvider.suggest(numbers, builder);
			};

	/**
	 * {@code /radio play <频道> <序号>}：把这个频道跳到指定曲目的开头。
	 *
	 * <h2>怎么跳</h2>
	 * 播放位置是 {@code (now - 基准) + 频道偏移}，因此「跳曲」等价于给这个频道
	 * 算一个新的偏移，使位置正好落在目标曲目的起点（见
	 * {@link RadioStationRegistry#setStationShiftMillis}）。
	 * 偏移对全服生效，所有听这个频道的人会同时跳过去；
	 * 其他频道偏移为 0，不受影响。
	 *
	 * <p>目标取「最近的一次播放时机」：本轮还没播到就从本轮开始，已经过了就等下一轮，
	 * 保证按下去立刻能听到这首歌，而不是跳到它已经播完一半的位置。
	 */
	private static int play(CommandContext<CommandSourceStack> context) {
		CommandSourceStack source = context.getSource();
		String query = StringArgumentType.getString(context, "station");
		int track = IntegerArgumentType.getInteger(context, "track");

		RadioStation station = findStation(query);
		if (station == null) {
			source.sendFailure(Component.translatable("command.radio-smile.play.unknown", query));
			return 0;
		}
		if (!station.isPlaylist()) {
			source.sendFailure(Component.translatable("command.radio-smile.play.not_playlist", station.id()));
			return 0;
		}

		List<Path> tracks = RadioStationRegistry.resolveTracks(station);
		PlaylistTimeline timeline = PlaylistTimeline.of(
				station.id(), tracks, station.isRandom(), station.seed(), null);
		if (timeline == null || timeline.size() <= 0) {
			source.sendFailure(Component.translatable("command.radio-smile.play.no_audio", station.id()));
			return 0;
		}
		if (track < 1 || track > timeline.size()) {
			source.sendFailure(Component.translatable(
					"command.radio-smile.play.bad_track", timeline.size()));
			return 0;
		}

		long epoch = RadioStationRegistry.startEpochMillis();
		long base = Math.max(0L, System.currentTimeMillis() - epoch);
		long roundTotal = Math.max(1L, timeline.roundTotalMillis());
		int roundNow = timeline.roundOf(base);
		long roundStartAbs = base - timeline.positionInRound(base);
		int target = track - 1;

		// 选「最近一次」目标曲目开头：本轮还没到就用本轮，过了就等下一轮。
		long targetAbs = roundStartAbs + roundTotal + timeline.trackStartMillis(roundNow + 1, target);
		for (int r = roundNow; r <= roundNow + 1; r++) {
			long candidate = roundStartAbs + (long) (r - roundNow) * roundTotal
					+ timeline.trackStartMillis(r, target);
			if (candidate >= base) {
				targetAbs = candidate;
				break;
			}
		}

		RadioStationRegistry.setStationShiftMillis(station.id(), targetAbs - base);
		int listeners = radiosmile.net.AudioStreamManager.rebuildStation(station.id());
		Path file = timeline.fileAt(timeline.roundOf(targetAbs), target);
		String fileName = file == null ? "?" : file.getFileName().toString();
		source.sendSuccess(() -> Component.translatable("command.radio-smile.play.started",
				station.id(), track, timeline.size(), fileName, listeners), true);
		return track;
	}

	/** 按 id（优先）或显示名找频道，大小写不敏感。 */
	private static RadioStation findStation(String query) {
		if (query == null || query.isBlank()) {
			return null;
		}
		String q = query.trim();
		for (RadioStation station : RadioStationRegistry.stations()) {
			if (station.id().equalsIgnoreCase(q)) {
				return station;
			}
		}
		for (RadioStation station : RadioStationRegistry.stations()) {
			if (station.name() != null && station.name().equalsIgnoreCase(q)) {
				return station;
			}
		}
		return null;
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
