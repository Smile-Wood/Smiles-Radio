package radiosmile.net;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;

import radiosmile.SmileSRadio;
import radiosmile.radio.AudioSource;
import radiosmile.radio.FileAudioSource;
import radiosmile.radio.RadioStation;
import radiosmile.radio.RadioStationRegistry;
import radiosmile.radio.StreamAudioSource;

/**
 * 服务端音频流推送调度。
 *
 * <p>每个 tick 检查所有订阅，到时间就读取一块音频并推给对应玩家。
 * 分块大小自适应，因此不会一次性灌满客户端或网络。
 *
 * <p><b>同步保证</b>：本地文件按服务端统一的播放位置读取
 * （{@code (now - epoch) % duration}），所以任何时刻加入的玩家都从同一位置开始，
 * 听到的内容一致。网络直播流无法 seek，只能从加入时刻起转发——
 * 这类频道天然不具备「精确对齐」能力，属预期限制。
 */
public final class AudioStreamManager {
	private static final Map<UUID, AudioSubscription> SUBSCRIPTIONS = new ConcurrentHashMap<>();
	/**
	 * 每玩家最多同时订阅的收音机数量。
	 *
	 * <p>原来写 2，导致玩家放第三台收音机时直接 STOPPED；两台在播时
	 * 重开界面（会重新订阅）也会被拒。现在放宽到 8，足以覆盖正常使用，
	 * 同时仍防止恶意客户端开无限订阅。
	 */
	private static final int MAX_SUBSCRIPTIONS_PER_PLAYER = 8;
	/**
	 * 可听范围（格），与唱片机一致。
	 *
	 * <p>唱片机判定的常量半径为 64（即 8 格）用于「是否播放」，
	 * 但实际衰减距离是 16 格；收音机这里取 48 格作为硬性可听边界：
	 * 超出直接停止推送，省带宽也避免远处串音。
	 */
	public static final double HEAR_RANGE = 48.0;

	private AudioStreamManager() {
	}

	/** 建立订阅。失败时给客户端发状态码并返回 null。 */
	public static AudioSubscription subscribe(ServerPlayer player, BlockPos pos, String stationId,
			int bandwidthKbps, int clientMaxChunk) {
		// <b>幂等</b>：同频道、同方块的重复订阅直接复用现有订阅。
		//
		// 早期无条件先 unsubscribe 再重建，配合客户端的「数据超时自动重订阅」，
		// 会形成致命的死循环：
		//   网络流建连要 1 秒 -> 期间没数据 -> 客户端判超时 -> 重订阅
		//   -> 杀掉正在建连的实例、重新建连 -> 又没数据 -> 又超时 ...
		// 实测日志里每秒都在重建连，永远等不到数据。
		//
		// 复用现有订阅后，重复请求变成空操作，建连有机会走完。
		for (AudioSubscription existing : SUBSCRIPTIONS.values()) {
			if (existing.player().getUUID().equals(player.getUUID())
					&& existing.pos().equals(pos)
					&& existing.station().id().equals(stationId)
					&& existing.isActive()) {
				SmileSRadio.LOGGER.debug("订阅已存在，复用：{} @{}", stationId, pos);
				// <b>必须回 OK</b>：这是客户端唯一能清掉液晶屏 ERR 的入口
				// （见 ClientRadioNetwork.markFault：OK → 0）。
				//
				// 早期这里直接 return，一个状态包都不发。看起来"什么都没变所以不用回"，
				// 但客户端的重订阅**恰恰是在它确认故障之后才发起的**
				// （AutoSubscriber 的 isStalled 分支）。此时它要的不是"重建订阅"，
				// 而是"告诉我现在到底行不行"。闷声复用 = 让 ERR408 永久留在屏幕上：
				// 订阅确实活着、数据也恢复了，可客户端永远等不到那个 OK。
				//
				// 回 OK 不改变任何状态机——复用等价于"从客户端视角看订阅成功"，
				// 这里只是把这个结论明确告知。
				sendStatus(player, pos, AudioStatus.OK);
				return existing;
			}
		}
		unsubscribe(player, pos);

		RadioStation station = findByid(stationId);
		if (station == null) {
			sendStatus(player, pos, AudioStatus.UNKNOWN_STATION);
			return null;
		}

		// 距离校验：与唱片机一致，48 格可听范围
		if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > HEAR_RANGE * HEAR_RANGE) {
			sendStatus(player, pos, AudioStatus.TOO_FAR);
			return null;
		}

		long count = SUBSCRIPTIONS.values().stream()
				.filter(s -> s.player().getUUID().equals(player.getUUID()))
				.count();
		if (count >= MAX_SUBSCRIPTIONS_PER_PLAYER) {
			SmileSRadio.LOGGER.warn("[停止原因A] 订阅数超限 {}/{} @{}", count, MAX_SUBSCRIPTIONS_PER_PLAYER, pos);
			sendStatus(player, pos, AudioStatus.STOPPED);
			return null;
		}

		// 旧存档兼容：方块是早期版本放的，那时该方块还没有方块实体，
		// 世界里只有方块本体。这里在主线程补建 BE：
		// 先设为空气再设回收音机，强制走一遍「放置」流程触发 newBlockEntity()。
		// （直接 setBlock 成相同状态是空操作，不会创建 BE。）
		var level = player.level();
		if (level.isLoaded(pos)
				&& !(level.getBlockEntity(pos) instanceof radiosmile.block.entity.RadioBlockEntity)) {
			var blockState = level.getBlockState(pos);
			net.minecraft.resources.ResourceLocation id =
					net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(blockState.getBlock());
			if (id != null && "radio-smile".equals(id.getNamespace()) && "radio".equals(id.getPath())) {
				SmileSRadio.LOGGER.debug("[自愈] 补建方块实体 @{}", pos);
				level.removeBlockEntity(pos);
				level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(),
						net.minecraft.world.level.block.Block.UPDATE_ALL);
				level.setBlock(pos, blockState, net.minecraft.world.level.block.Block.UPDATE_ALL);
				SmileSRadio.LOGGER.debug("[自愈] 结果 = {}", level.getBlockEntity(pos));
			}
		}

		AudioSource source = openSource(station);
		if (source == null) {
			sendStatus(player, pos, AudioStatus.NO_AUDIO);
			return null;
		}

		AudioSubscription subscription = new AudioSubscription(
				player, pos.immutable(), station, source, bandwidthKbps, clientMaxChunk);
		// 让客户端尽快到达「当前应播位置」：先把历史部分全速补发过去
		long skip = source.skipMillis();
		if (skip > 0) {
			subscription.setPrimeMillis(skip);
			SmileSRadio.LOGGER.info("开始预热 {} ms 的历史音频", skip);
		}
		SUBSCRIPTIONS.put(key(player, pos), subscription);
		sendStatus(player, pos, AudioStatus.OK);
		SmileSRadio.LOGGER.info("玩家 {} 订阅频道 {} @{}（bw={}KB/s）",
				player.getName().getString(), stationId, pos, bandwidthKbps);
		return subscription;
	}

	/**
	 * 按频道 id 或 URL 打开音频源。
	 *
	 * <p>播放列表（{@code sequence}/{@code random}）不再固定取第 0 首：整条列表被拼成
	 * 一条连续时间轴（见 {@link radiosmile.radio.PlaylistTimeline}），按
	 * {@code (now - epoch) % 总时长} 定位到「此刻该播第几首、歌内第几毫秒」。
	 * 这样中途加入、以及播完若干首之后，所有人算出的曲目与位置都一致；
	 * 播到最后一首末尾时取模自然回到第 0 首（循环），随机模式的顺序由固定 seed 决定。
	 */
	private static AudioSource openSource(RadioStation station) {
		try {
			if (station.isStreaming()) {
				// 网络直播流无法 seek：从连上那一刻开始顺序播放。
				//
				// <b>但必须确认它确实是 MP3</b>：网络流是原样透传、不经过转码的，
				// 而模组里只剩下 MP3 解码器。若服主把 .ogg 直播流地址填进来，
				// 客户端会收到 UNKNOWN 格式、按 MP3 去解，JLayer 搜不到同步字 →
				// 「订阅成功但完全没声音」这种最难排查的静默失败。
				// 宁可明确拒绝并告知原因，也不要产出无声的假成功。
				if (radiosmile.radio.AudioFormat.ofFile(station.file())
						!= radiosmile.radio.AudioFormat.MP3) {
					SmileSRadio.LOGGER.error(
							"网络流不是 MP3，拒绝播放（模组内已无 OGG 解码器，请改用 MP3 直播流地址）：{}",
							station.file());
					return null;
				}
				return new StreamAudioSource(station.file());
			}
			List<Path> tracks = RadioStationRegistry.resolveTracks(station);
			if (tracks.isEmpty()) {
				return null;
			}

			// 播放列表：定位到「第几轮、本轮第几首、歌内第几毫秒」
			if (station.isPlaylist() && tracks.size() > 1) {
				radiosmile.radio.PlaylistTimeline timeline =
						radiosmile.radio.PlaylistTimeline.of(station.id(), tracks, station.isRandom(),
								station.seed(), RadioStationRegistry.headBeforeReload(station.id()));
				if (timeline != null) {
					// 轮次与轮内位置都由统一的计时基准算出，因此任何时刻加入的玩家
					// 都会落在同一轮、同一首、同一位置（多人同步的前提）。
					long position = currentPlaybackOffsetMillis(station.id());
					int round = timeline.roundOf(position);
					long positionInRound = timeline.positionInRound(position);
					long[] located = timeline.locate(round, positionInRound);
					int index = (int) located[0];
					long offsetInTrack = located[1];
					// 首曲从「当前该播到的位置」开始（保证多人对齐），后续换曲从 0 开始
					AudioSource trackSource = openTrack(
							timeline.fileAt(round, index), offsetInTrack, index);
					if (trackSource == null) {
						// 该曲目没有 MP3 转码产物：直接拒绝整条订阅，
						// 不要再落到下面的单文件分支重试（只会把同一个错误再打一遍日志）。
						return null;
					}
					SmileSRadio.LOGGER.info("播放列表定位：第 {} 轮 第 {}/{} 首，歌内 {} ms",
							round + 1, index + 1, timeline.size(), offsetInTrack);
					return new radiosmile.radio.PlaylistAudioSource(
							timeline, round, index, trackSource);
				}
			}

			Path original = tracks.get(0);
			return openTrack(original, currentPlaybackOffsetMillis(station.id()), 0);
		} catch (IOException e) {
			SmileSRadio.LOGGER.warn("打开音频源失败：{}", station.file(), e);
			return null;
		}
	}

	/**
	 * 打开单个音频文件，<b>优先用预热好的 MP3</b>。
	 *
	 * <p>为什么必须优先 MP3：OGG 无法从中段解码（Vorbis 的 MDCT 重叠相加依赖前序包状态，
	 * 页序号跳变即报 {@code Failed to read Ogg file}，见 HANDOVER 七-1），
	 * 而多人同步依赖「跳到当前应播位置」。MP3 帧自包含，可从任意帧边界独立解码，
	 * 所以本地音频一律先转码成 MP3 再流式推送。
	 *
	 * <p><b>这里必须自己查一次 {@link radiosmile.radio.AudioTranscoder#mp3For}，
	 * 不能假定调用方传进来的已经是 MP3</b>：单文件频道（{@code mode=block}）传进来的是
	 * {@code tracks.get(0)}，即<b>服主放的原始文件</b>（多为 .ogg）；播放列表分支才会先经
	 * {@code PlaylistTimeline.fileAt()} 换成转码产物。早期这个查询被删掉后，
	 * 单文件频道直接把原始文件推给客户端，OGG 解码即失败（tiehen 就是这样挂的）。
	 *
	 * <p>现在 OGG 链路已整体移除（客户端不再有 OGG 解码器），因此这里只接受 MP3：
	 * 查不到转码产物就拒绝，而不是退回原始文件。
	 *
	 * @param original     原始音频文件（可能是 OGG/WAV，也可能是已转码的 MP3）
	 * @param offsetMillis 目标播放位置
	 * @param trackIndex   播放列表序号（单文件为 0）
	 */
	private static AudioSource openTrack(Path original, long offsetMillis, int trackIndex) throws IOException {
		// 已经是 MP3（转码产物，或频道本身就配了 .mp3）就不必再查：
		// mp3For() 内部要算一次整文件 SHA-256，对 20MB 文件是主线程上几十毫秒的浪费。
		Path playable = original;
		if (!isMp3(original)) {
			// 优先用转码产物：启动时已按哈希预热，正常情况这里一定能命中。
			Path bakedMp3 = radiosmile.radio.AudioTranscoder.mp3For(original);
			if (bakedMp3 != null) {
				playable = bakedMp3;
			}
		}
		if (isMp3(playable)) {
			return new radiosmile.radio.FileAudioSource(playable, offsetMillis, true, trackIndex);
		}
		// <b>不再降级推原始 OGG</b>。
		//
		// 早期这里会退回「原始文件从头播 + 客户端解码后丢弃前段」。那条路径看似可用，
		// 实际有两个死穴：
		//   1. OGG 无法从中段解码（HANDOVER 七-1），多人同步根本做不到；
		//   2. 客户端摄入队列是断头丢弃策略，OGG 丢任意一块就永久失步，
		//      直接报 {@code Failed to read Ogg file N}（用户实测 tiehen 就是这样挂的）。
		// 而 MP3 帧自包含、可自行重同步，所以只推 MP3 才有可靠行为。
		//
		// 因此这里明确返回 null 由上层拒绝该频道，而不是静默降级：
		// 宁可不播、并让服主看到清晰的原因，也不要放出一段看似能响、实则不同步且随时崩的音频。
		SmileSRadio.LOGGER.error(
				"频道音频缺少 MP3 转码产物，拒绝播放（请确认已安装 ffmpeg 且预转码成功）：{}",
				original.getFileName());
		return null;
	}

	/** 按扩展名判断是否为 MP3。 */
	private static boolean isMp3(Path file) {
		return file != null && file.getFileName().toString()
				.toLowerCase(java.util.Locale.ROOT).endsWith(".mp3");
	}

	/**
	 * 当前应播放到的位置（毫秒）。
	 *
	 * <p>算法：{@code (now - startEpoch) % duration}。关键是基准
	 * {@code startEpochMillis} 由服务端在启动/重载时统一设定，所有客户端共享同一值，
	 * 因此不同时刻加入的玩家算出的位置天然对齐。
	 *
	 * @return 播放位置毫秒；时长未知或基准缺失时返回 0（从头播放）
	 */
	private static long currentPlaybackOffsetMillis(String stationId) {
		long epoch = RadioStationRegistry.startEpochMillis();
		if (epoch <= 0) {
			return 0L;
		}
		// 频道级偏移：/radio play 用它把某个频道整体挪到目标曲目，
		// 其他频道偏移恒为 0，互不影响。
		return Math.max(0L, System.currentTimeMillis() - epoch)
				+ RadioStationRegistry.stationShiftMillis(stationId);
	}

	/**
	 * 取消某玩家在某方块的订阅。
	 *
	 * <p><b>不用 key 直接 remove</b>：key 是坐标哈希，历史版本算法变化或极端碰撞时
	 * 会漏删，导致订阅数虚高、后续订阅被上限拒掉（现象就是「调了两台就停摆」）。
	 * 这里直接按「玩家 + 坐标」全表扫描，语义确定，不依赖哈希正确性。
	 */
	public static void unsubscribe(ServerPlayer player, BlockPos pos) {
		List<AudioSubscription> matched = new ArrayList<>();
		for (Map.Entry<UUID, AudioSubscription> entry : SUBSCRIPTIONS.entrySet()) {
			AudioSubscription sub = entry.getValue();
			if (sub.player().getUUID().equals(player.getUUID())
					&& sub.pos().equals(pos)) {
				matched.add(sub);
			}
		}
		for (AudioSubscription sub : matched) {
			SUBSCRIPTIONS.remove(key(sub.player(), sub.pos()));
			sub.deactivate();
			sub.closeSource();
		}
	}

	/** 玩家断开时清理其所有订阅。 */
	public static void unsubscribeAll(ServerPlayer player) {
		Iterator<Map.Entry<UUID, AudioSubscription>> it = SUBSCRIPTIONS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, AudioSubscription> entry = it.next();
			AudioSubscription sub = entry.getValue();
			if (sub.player().getUUID().equals(player.getUUID())) {
				sub.deactivate();
				sub.closeSource();
				it.remove();
			}
		}
	}

	/**
	 * 后台推送线程。
	 *
	 * <p><b>为什么不挂在服务端 tick 上</b>：{@code END_SERVER_TICK} 只在世界正常推进时触发，
	 * 一旦服务端掉 tick（卡顿、单步调试）、或玩家在后台导致暂停/降频，推送就会停摆，
	 * 听感上就是周期性断续。音频是<b>实时流</b>，节奏必须由自己的时钟驱动，
	 * 与游戏 tick 彻底解耦。
	 *
	 * <p>线程只做「取已解码的块 + 发包」，不读网络、不做重活，因此不会反过来拖慢服务端。
	 */
	private static volatile Thread pusherThread;
	private static volatile boolean pusherRunning = true;

	/** 启动后台推送线程（幂等）。服务端初始化时调用一次即可。 */
	public static void startPusher() {
		ensurePusher();
	}

	private static void ensurePusher() {
		if (pusherThread != null) {
			return;
		}
		synchronized (AudioStreamManager.class) {
			if (pusherThread != null) {
				return;
			}
			Thread t = new Thread(() -> {
				while (pusherRunning) {
					try {
						pumpAll();
						// 5ms 粒度足够（块间隔最小 50ms），又不会空转烧 CPU
						Thread.sleep(5L);
					} catch (InterruptedException e) {
						return;
					} catch (Throwable e) {
						SmileSRadio.LOGGER.warn("音频推送线程异常", e);
					}
				}
			}, "RadioSmile-AudioPusher");
			t.setDaemon(true);
			t.start();
			pusherThread = t;
		}
	}

	/** 把到期的数据块推出去（由后台线程调用，不再依赖服务端 tick）。 */


	private static void pumpAll() {
		if (SUBSCRIPTIONS.isEmpty()) {
			return;
		}

		long now = System.currentTimeMillis();
		List<AudioSubscription> dead = new ArrayList<>();

		for (AudioSubscription sub : SUBSCRIPTIONS.values()) {
			if (!sub.isActive()) {
				dead.add(sub);
				continue;
			}
			if (!sub.isDue(now)) {
				// <b>还没到发送节拍，但可能已经很久没发过任何东西了。</b>
				//
				// 这是换曲场景下很容易漏掉的一条：{@code isDue()} 由配速锚点决定，
				// 换曲/轮转时锚点可能被推到较远的未来，于是这里一路 {@code continue}，
				// 客户端那边长时间收不到包 → 误判超时 → 点 NOSIGNAL + 响底噪。
				//
				// 因此补一条<b>与配速无关</b>的心跳路径：只要距上次发包超过
				// {@link #HEARTBEAT_INTERVAL_MILLIS}，就发一个空包维持连接。
				// 它不参与配速（不调 markSent），所以不会影响正常音频的节奏。
				if (sub.isHeartbeatDue(now) && !sub.source().isFinished()) {
					sendHeartbeat(sub);
				}
				continue;
			}
			try {
				pump(sub, now);
			} catch (IOException e) {
				SmileSRadio.LOGGER.warn("[停止原因B] 推送音频失败：{} @{}", sub.station().id(), sub.pos(), e);
				sendStatus(sub.player(), sub.pos(), AudioStatus.STOPPED);
				sub.deactivate();
				dead.add(sub);
			}
		}

		for (AudioSubscription sub : dead) {
			SUBSCRIPTIONS.remove(key(sub.player(), sub.pos()));
			sub.closeSource();

		}
	}

	private static void pump(AudioSubscription sub, long now) throws IOException {
		ServerPlayer player = sub.player();

		// 玩家下线 / 走远 / 方块被拆：终止
		if (player.hasDisconnected()) {
			SmileSRadio.LOGGER.warn("[停止原因0] 玩家已断开 @{}", sub.pos());
			sub.deactivate();
			return;
		}
		// 走远则停止推送。留 4 格滞回，避免在边界上反复断开重连
		double stopRange = (HEAR_RANGE + 4.0) * (HEAR_RANGE + 4.0);
		double distSq = player.distanceToSqr(sub.pos().getX() + 0.5, sub.pos().getY() + 0.5, sub.pos().getZ() + 0.5);
		if (distSq > stopRange) {
			SmileSRadio.LOGGER.warn("[停止原因E] 距离过远 @{} 距离²={} 上限²={}",
					sub.pos(), (long) distSq, (long) stopRange);
			sendStatus(player, sub.pos(), AudioStatus.TOO_FAR);
			sub.deactivate();
			return;
		}
		if (!ServerPlayNetworking.canSend(player, RadioNetworking.S2C_AUDIO_CHUNK)) {
			SmileSRadio.LOGGER.warn("[停止原因F] 通道不可发送 @{}", sub.pos());
			sub.deactivate();
			return;
		}

		// 方块被拆 / 被替换 / 频道被改：必须停止推送。
		// 之前注释写了「方块被拆」但实际没检查，导致拆掉收音机后声音还在播。
		var level = player.level();
		if (!level.isLoaded(sub.pos())) {
			sub.deactivate();
			return;
		}
		var state = level.getBlockState(sub.pos());
		// 用注册名比较而不是静态字段：静态字段在类未初始化时可能为 null，
		// 会导致 state.is(null) 返回 false，误判为「方块不是收音机」。
		net.minecraft.resources.ResourceLocation blockId =
				net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
		boolean isRadio = blockId != null && "radio-smile".equals(blockId.getNamespace())
				&& "radio".equals(blockId.getPath());
		if (!isRadio) {
			SmileSRadio.LOGGER.warn("[停止原因C] 方块不是收音机 @{} 实际={} (id={})",
					sub.pos(), state.getBlock(), blockId);
			sendStatus(player, sub.pos(), AudioStatus.STOPPED);
			sub.deactivate();
			return;
		}
		// 注意：这里<b>不因方块实体缺失而中断</b>。
		//
		// 音频能否播放取决于「方块还是不是收音机」（上面已校验）与「玩家是否在范围内」，
		// 与方块实体的存在与否无关——频道 id 已随订阅请求带来，无需再读 NBT。
		// 早期版本在这里 return，导致旧存档方块（无 BE）永远收不到音频，
		// 表现为「订阅成功但完全没声音」。BE 的补建由 subscribe() 在主线程负责，
		// 失败也不影响播放。
		radiosmile.block.entity.RadioBlockEntity be = null;
		if (level.getBlockEntity(sub.pos()) instanceof radiosmile.block.entity.RadioBlockEntity existing) {
			be = existing;
		}
		// 频道变了（别的玩家换了台）才停播。
		//
		// 注意竞态：玩家自己刚选完台时，「C2S_SELECT_STATION 写 NBT」与
		// 「C2S_AUDIO_SUBSCRIBE 建立订阅」是两个独立包，服务端处理顺序不保证。
		// 若订阅先到，此时 NBT 里的频道还是旧的（或空），直接判为不匹配会
		// 立刻发 STOPPED——现象就是「订阅成功但没声音」。
		// 因此这里不因不匹配而停播，而是把方块 NBT 修正为本次订阅的频道；
		// 真正的「换台打断旧广播」由客户端在订阅新频道时主动退订旧频道保证。
		// 以本次订阅的频道为准写回 NBT。
		//
		// subscribe 是「玩家此刻想要听这个台」的明确意图，直接采信即可：
		// 若 NBT 里是空的（select 包还没处理）就补写；若是别的台，
		// 说明玩家正在换台，同样以新订阅为准。
		// 只有「别人改了频道」才应打断，而那由客户端再次打开界面时自然处理。
		if (be != null) {
			String beStation = be.getStation();
			if (!sub.station().id().equals(beStation)) {
				be.setStation(sub.station().id());
			}
		}

		// <b>不做位置校正</b>。
		//
		// 曾经的实现会在「实际位置与时间轴偏差过大」时 seek 到目标位置，
		// 但 seek 会<b>直接丢弃</b>偏差对应的全部音频（实测一次校正丢掉 13 秒、约 208KB），
		// 这不是「对齐」而是「删数据」——听感就是中间成片消失。
		//
		// 正确做法：订阅时一次定位到正确位置，之后纯顺序流式播放。
		// 读取进度与时间轴的微小差异由配速自然消化，不靠跳转纠正。

		// 主线程只做非阻塞取块 + 发包。数据由订阅自带的读线程预先灌好，
		// 因此这里绝不会因为等网络而卡住服务器 tick。
		AudioSubscription.Chunk chunk = sub.pollChunk();
		if (chunk == null || chunk.data().length == 0) {
			// <b>没有数据时要发「心跳空包」，不能一言不发。</b>
			//
			// <h2>为什么必须发</h2>
			// 换曲、播放列表轮转、上游短暂卡顿时，读线程会有<b>几百毫秒</b>拿不到数据。
			// 早期这里直接 return，客户端那边就是"服务端没消息"——
			// 而客户端判断「流僵住」和「要不要响底噪」都看 {@code lastChunkAt}，
			// 于是：按时长积累到 8 秒 → 判超时 → <b>点亮 NOSIGNAL + 响底噪</b>，
			// 可实际上服务一切正常，只是中间换了一首歌。
			//
			// <p>用户的原话是「要么中间做到无缝，要么就发空包（让客户端知道服务还活着）」。
			// 做到无缝需要客户端预解码下一首并拼接 PCM，改动面大且容易在换曲处
			// 产生爆音；<b>发空包是更稳的方案</b>：它精确表达了"我还活着，只是这段没数据"。
			//
			// <h2>空包不会污染音频</h2>
			// 客户端收到空数组时<b>只刷新心跳、不喂解码器</b>（见 ClientAudioPlayer.handleChunk），
			// 所以不会在 MP3 流里插入垃圾字节。
			//
			// <h2>为什么不需要限流</h2>
			// 本方法由推送线程按 {@code isDue()} 的节奏调用（约每 250ms 一次），
			// 天然不会刷屏；空包只有十几个字节。
			//
			// <h2>但不能无限发</h2>
			// 若上游真的死了（{@code source.isFinished()}），读线程会退出，
			// 这时继续发空包只会让客户端以为"一切正常"而永远不报故障。
			// 因此只在<b>流还没结束</b>时发；真结束时不发，让客户端按既有逻辑判超时。
			if (!sub.source().isFinished()) {
				sendHeartbeat(sub);
			}
			return;
		}
		byte[] data = chunk.data();
		// 前几块打日志：确认发出的是有效 MP3 数据（帧头校验）
		if (sub.totalBytesSent() < 16384) {
			// 只认 MP3 的两种合法开头：ID3 标签、或帧同步字 0xFF 0xEx。
			// 早期这里还把 'OggS' 算作合法帧头，于是推错格式时日志会显示"帧头=true"，
			// 恰好把问题掩盖掉了——校验条件本身就不该给不该存在的格式留位置。
			boolean mp3Sync = (data[0] & 0xFF) == 0xFF
					|| (data.length > 3 && data[0] == 'I' && data[1] == 'D' && data[2] == '3');
			SmileSRadio.LOGGER.info("[发送] {} {} 字节 首字节=0x{} 帧头={}",
					sub.station().id(), data.length, Integer.toHexString(data[0] & 0xFF), mp3Sync);
		}

		// 发包必须回到服务端主线程：Minecraft 的网络层不是线程安全的，
		// 从自建线程直接 send 会被丢弃或引发并发问题（现象：订阅成功但没有声音）。
		ServerPlayer target = sub.player();
		String stationId = sub.station().id();
		BlockPos targetPos = sub.pos();
		var server = player.getServer();
		if (server == null) {
			return;
		}
		// 格式与跳过时长由服务端明确告知。
		// 注意：服务端<b>始终从文件头</b>推送（保证解码器拿到完整容器头），
		// 同步靠客户端解码后丢弃前 skipMillis 毫秒的 PCM 实现。
		radiosmile.radio.AudioFormat format = sub.source().format();
		// 曲目序号随块下发：播放列表换曲后客户端必须重建解码器，
		// 否则新曲的 MP3 帧会被继续喂进旧解码器（听感是两首歌糊在一起）。
		//
		// <b>必须用块里冻结的序号，不能现读音源当前序号</b>：
		// 队列积压约 4 秒，现读会让标签比字节流早约 4 秒，
		// 客户端提前重建解码器并 flush 声卡 → 每次换曲约 1 秒静音。
		int trackIndex = chunk.trackIndex();
		// 流会话号：网络流重连后会变，客户端据此重建解码器
		int sessionId = sub.sessionId();
		server.execute(() -> {
			try {
				if (!ServerPlayNetworking.canSend(target, RadioNetworking.S2C_AUDIO_CHUNK)) {
					SmileSRadio.LOGGER.warn("无法发送音频块：通道不可用 {}", target.getName().getString());
					return;
				}
			FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
			buf.writeBlockPos(targetPos);
			buf.writeUtf(stationId);
			buf.writeVarInt(trackIndex);
			buf.writeVarInt(sessionId);
			buf.writeVarInt(format.id());
			buf.writeByteArray(data);
				ServerPlayNetworking.send(target, RadioNetworking.S2C_AUDIO_CHUNK, buf);
			} catch (Throwable t) {
				SmileSRadio.LOGGER.error("[发送失败] 音频块发包异常", t);
			}
		});

		sub.markSent(now, data.length, data);
	}

	/**
	 * 发送一个<b>心跳空包</b>：复用音频块通道，但载荷长度为 0。
	 *
	 * <p>它唯一的作用是告诉客户端「服务端还活着，只是这段没有音频数据」。
	 * 客户端收到后<b>只刷新心跳时间、不喂解码器</b>，因此不会污染 MP3 字节流。
	 *
	 * <p><b>为什么复用 S2C_AUDIO_CHUNK 而不是新开一个通道</b>：
	 * 客户端对这个通道的处理已经包含"切台/换曲/会话变化"的完整判定逻辑，
	 * 空包走同一条路能顺带把这些状态同步过去（例如换曲时的 trackIndex 变化
	 * 会让客户端重建解码器——正是我们想要的）。
	 *
	 * <p>字段顺序必须与正常音频块<b>完全一致</b>，否则客户端读取会错位：
	 * pos / stationId / trackIndex / sessionId / formatId / data。
	 */
	private static void sendHeartbeat(AudioSubscription sub) {
		ServerPlayer target = sub.player();
		if (target == null || target.hasDisconnected()) {
			return;
		}
		// <b>立刻记时间</b>，否则推送线程每 5ms 转一圈就会判定"又该发心跳了"，
		// 变成每 5ms 一个空包刷屏。
		sub.markHeartbeatSent(System.currentTimeMillis());
		String stationId = sub.station().id();
		BlockPos targetPos = sub.pos();
		radiosmile.radio.AudioFormat format = sub.source().format();
		int trackIndex = sub.trackIndex();
		int sessionId = sub.sessionId();
		var server = target.getServer();
		if (server == null) {
			return;
		}
		server.execute(() -> {
			try {
				if (!ServerPlayNetworking.canSend(target, RadioNetworking.S2C_AUDIO_CHUNK)) {
					return;
				}
				FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
				buf.writeBlockPos(targetPos);
				buf.writeUtf(stationId);
				buf.writeVarInt(trackIndex);
				buf.writeVarInt(sessionId);
				buf.writeVarInt(format.id());
				// 空载荷：客户端据此识别为心跳
				buf.writeByteArray(new byte[0]);
				ServerPlayNetworking.send(target, RadioNetworking.S2C_AUDIO_CHUNK, buf);
			} catch (Throwable t) {
				SmileSRadio.LOGGER.error("[心跳] 空包发送异常", t);
			}
		});
	}

	public static void sendStatus(ServerPlayer player, BlockPos pos, int status) {
		if (!ServerPlayNetworking.canSend(player, RadioNetworking.S2C_AUDIO_STATUS)) {
			return;
		}
		FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeBlockPos(pos);
		buf.writeVarInt(status);
		ServerPlayNetworking.send(player, RadioNetworking.S2C_AUDIO_STATUS, buf);
	}

	private static RadioStation findByid(String id) {
		for (RadioStation station : RadioStationRegistry.stations()) {
			if (station.id().equals(id)) {
				return station;
			}
		}
		return null;
	}

	/**
	 * 生成订阅键：玩家 + 方块坐标。
	 *
	 * <p>原先只把坐标对 UUID 的高位做异或，<b>不同坐标可能碰撞</b>
	 * （例如 (0,0,0) 与某些坐标异或后相同），导致旧订阅移除不掉、
	 * 订阅数虚高，进而被上限拒掉。这里把坐标完整混入两个 64 位半区，
	 * 再叠加一个分隔常量，避免与玩家 UUID 本身冲突。
	 */
	private static UUID key(ServerPlayer player, BlockPos pos) {
		long p = pos.asLong();
		// 混合坐标：高位与低位交叉参与，降低碰撞概率
		long mix = p * 0x9E3779B97F4A7C15L;
		return new UUID(
				player.getUUID().getMostSignificantBits() ^ mix,
				player.getUUID().getLeastSignificantBits() ^ Long.rotateLeft(mix, 32) ^ 0x5DEECE66DL);
	}

	/**
	 * 只重建某一个频道的订阅（{@code /radio play} 跳曲后使用）。
	 *
	 * <p>跳曲只影响一个频道，没必要把全服订阅都拆掉重连——
	 * 那会让听其他频道的玩家也经历一次无谓的断流。
	 *
	 * @param stationId 目标频道 id
	 * @return 重建成功的订阅数
	 */
	public static int rebuildStation(String stationId) {
		List<SubscriptionSnapshot> snapshots = new ArrayList<>();
		for (AudioSubscription sub : SUBSCRIPTIONS.values()) {
			if (sub.station().id().equals(stationId)) {
				snapshots.add(new SubscriptionSnapshot(
						sub.player(), sub.pos(), sub.station().id(),
						sub.bandwidthKbps(), sub.clientMaxChunk()));
			}
		}
		if (snapshots.isEmpty()) {
			return 0;
		}
		for (SubscriptionSnapshot snap : snapshots) {
			AudioSubscription sub = SUBSCRIPTIONS.remove(key(snap.player(), snap.pos()));
			if (sub != null) {
				// 先让客户端播一段调台噪，把随后的跳变盖住（与全量重载一致）。
				if (ServerPlayNetworking.canSend(snap.player(), RadioNetworking.S2C_RELOADED)) {
					ServerPlayNetworking.send(snap.player(), RadioNetworking.S2C_RELOADED,
							new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer()));
				}
				sub.deactivate();
				sub.closeSource();
			}
		}
		int rebuilt = 0;
		for (SubscriptionSnapshot snap : snapshots) {
			ServerPlayer player = snap.player();
			if (player == null || player.hasDisconnected()) {
				continue;
			}
			if (subscribe(player, snap.pos(), snap.stationId(),
					snap.bandwidthKbps(), snap.clientMaxChunk()) != null) {
				rebuilt++;
			}
		}
		SmileSRadio.LOGGER.info("频道 {}：已按新的播放位置重建 {} 个订阅", stationId, rebuilt);
		return rebuilt;
	}

	/**
	 * 重新加载配置后<b>重建所有正在进行的订阅</b>。
	 *
	 * <h2>为什么必须重建，而不是只改计时基准</h2>
	 * 播放位置来自 {@code (now - epoch) % 时长}，但这个值只在<b>订阅创建时</b>用一次
	 * （{@code FileAudioSource} 构造时按它 seek 到文件某个偏移）。
	 * 一旦订阅建立，音源就带着<b>已经固定的文件指针</b>顺序往下读，
	 * 之后再改 {@code startEpochMillis} 对它毫无影响——
	 * 这就是「重载后进度没有重置」的真正原因：基准变了，但没人重新定位。
	 *
	 * <p>所以这里把现有订阅全部拆掉、再按新基准重新建立一次。
	 * 效果等价于「所有人都重新调了一次台」，进度从新基准正确开始。
	 *
	 * <p>重建是<b>逐个玩家</b>做的：先记下每个人的「方块+频道」，全部关闭后再逐个重建。
	 * 不能边遍历边删（会破坏快照），也不能先重建后关闭（同坐标会有两份订阅）。
	 */
	public static void rebuildAll() {
		// 1. 快照：记下每个订阅的玩家、方块、频道
		List<SubscriptionSnapshot> snapshots = new ArrayList<>();
		for (AudioSubscription sub : SUBSCRIPTIONS.values()) {
			snapshots.add(new SubscriptionSnapshot(
					sub.player(), sub.pos(), sub.station().id(),
					// 保留该玩家原本声明的带宽与块大小习惯，避免重建后行为突变
					sub.bandwidthKbps(), sub.clientMaxChunk()));
		}

		// 2. 全部关闭并清空
		for (AudioSubscription sub : SUBSCRIPTIONS.values()) {
			sub.deactivate();
			sub.closeSource();
		}
		SUBSCRIPTIONS.clear();

		if (snapshots.isEmpty()) {
			return;
		}

		// 3. 按新基准逐个重建
		int rebuilt = 0;
		for (SubscriptionSnapshot snap : snapshots) {
			ServerPlayer player = snap.player();
			// 玩家可能已经下线 / 走远 / 频道被删：这些情况下 subscribe 会自行拒绝并回状态
			if (player == null || player.hasDisconnected()) {
				continue;
			}
			if (subscribe(player, snap.pos(), snap.stationId(),
					snap.bandwidthKbps(), snap.clientMaxChunk()) != null) {
				rebuilt++;
			}
		}
		SmileSRadio.LOGGER.info("重载完成：已按新计时基准重建 {} 个订阅", rebuilt);
	}

	/** 重建时需要的订阅快照。 */
	private record SubscriptionSnapshot(ServerPlayer player, BlockPos pos, String stationId,
			int bandwidthKbps, int clientMaxChunk) {
	}

	/** 服务端关闭时清理全部订阅。 */
	public static void shutdown() {
		for (AudioSubscription sub : SUBSCRIPTIONS.values()) {
			sub.deactivate();
			sub.closeSource();
		}
		SUBSCRIPTIONS.clear();
	}
}