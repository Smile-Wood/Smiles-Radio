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
	 * 网络电台订阅状态机。
	 *
	 * <p><b>架构约定（用户明确）</b>：网络电台的音频流由<b>客户端自己从上游拉取</b>，
	 * 服务端不代理、不推流、不检查网络——对服务端来说网络电台只是一个
	 * 「是否在播放」的订阅状态。这里登记的条目只用于：
	 * <ul>
	 *   <li>订阅数限制（与本地电台共用上限）；</li>
	 *   <li>走远 / 方块拆除时通知客户端停止拉流（TOO_FAR / STOPPED）。</li>
	 * </ul>
	 * 音频字节完全不经服务端；断流判定、底噪、NOSIGNAL、重连全在客户端。
	 */
	private static final Map<UUID, StreamingSub> STREAMING_SUBS = new ConcurrentHashMap<>();

	/** 网络电台的一条订阅登记（见 {@link #STREAMING_SUBS}）。 */
	private record StreamingSub(ServerPlayer player, BlockPos pos, String stationId) {
	}

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

		// <b>网络电台：纯订阅状态机</b>（架构约定：客户端自己从上游拉流）。
		//
		// 服务端不做任何音频工作——不建源、不读流、不推送、不重连。
		// 这里只登记「这个玩家在这个方块听这个网络台」，然后回 OK；
		// 客户端收到 OK 后自己连接上游 URL（电台列表里已下发）开始拉流。
		// 后续走远 / 方块拆除由 tick 的状态机检测发 TOO_FAR / STOPPED。
		if (station.isStreaming()) {
			UUID subKey = key(player, pos);
			StreamingSub existing = STREAMING_SUBS.get(subKey);
			if (existing != null && existing.stationId().equals(stationId)) {
				// 幂等：重复订阅直接回 OK（同本地电台的复用语义）
				sendStatus(player, pos, AudioStatus.OK);
				// 增益随订阅回复一起下发（幂等路径同样发）：客户端若因换台残留了
				// 旧台的推流增益，这次重订阅就是清掉它的权威时机。
				writeOutputGain(player, pos, stationId, STREAM_GAIN);
				return null;
			}
			long count = SUBSCRIPTIONS.values().stream()
					.filter(s -> s.player().getUUID().equals(player.getUUID()))
					.count()
					+ STREAMING_SUBS.values().stream()
							.filter(s -> s.player().getUUID().equals(player.getUUID()))
							.count();
			if (count >= MAX_SUBSCRIPTIONS_PER_PLAYER) {
				SmileSRadio.LOGGER.warn("[停止原因A] 订阅数超限 {}/{} @{}",
						count, MAX_SUBSCRIPTIONS_PER_PLAYER, pos);
				sendStatus(player, pos, AudioStatus.STOPPED);
				return null;
			}
			STREAMING_SUBS.put(subKey, new StreamingSub(player, pos.immutable(), stationId));
			sendStatus(player, pos, AudioStatus.OK);
			// <b>增益随订阅回复下发</b>（用户提议）：网络电台的增益不该由客户端自己猜，
			// 服务端在订阅成立的那一刻就说清楚——透传流恒为 1.0。
			//
			// 为什么必须在订阅时发：网络电台此后不再有音频块，pump 的
			// 「增益变了才补发」路径永远不会为它触发。若客户端表里残留着
			// 这个方块此前放本地台时收到的 outputGain（如 0.25），换到网络台后
			// 没有任何包来覆盖它，整路就会被永久压到 25%——这正是
			// 「网络电台声音小」的根因。订阅时下发 = 权威覆盖，客户端侧的
			// 建流/停流兜底清理退居第二道防线。
			writeOutputGain(player, pos, stationId, STREAM_GAIN);
			SmileSRadio.LOGGER.info("网络电台订阅登记 {} @{}（客户端自行拉流）", stationId, pos);
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
		SmileSRadio.LOGGER.debug("玩家 {} 订阅频道 {} @{}（bw={}KB/s）",
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
		// 网络电台的订阅登记一并清除（换台/退订时客户端自己停拉流）
		STREAMING_SUBS.entrySet().removeIf(entry -> {
			StreamingSub sub = entry.getValue();
			return sub.player().getUUID().equals(player.getUUID()) && sub.pos().equals(pos);
		});
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
		STREAMING_SUBS.values().removeIf(sub -> sub.player().getUUID().equals(player.getUUID()));
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
		if (SUBSCRIPTIONS.isEmpty() && STREAMING_SUBS.isEmpty()) {
			return;
		}

		long now = System.currentTimeMillis();
		List<AudioSubscription> dead = new ArrayList<>();

		for (AudioSubscription sub : SUBSCRIPTIONS.values()) {
			if (!sub.isActive()) {
				dead.add(sub);
				continue;
			}
			if (sub.isDue(now)) {
				// 到发送节拍：走正常推送路径
				try {
					pump(sub, now);
				} catch (IOException e) {
					SmileSRadio.LOGGER.warn("[停止原因B] 推送音频失败：{} @{}", sub.station().id(), sub.pos(), e);
					sendStatus(sub.player(), sub.pos(), AudioStatus.STOPPED);
					sub.deactivate();
					dead.add(sub);
				}
				continue;
			}
			// <b>还没到发送节拍：什么都不发。</b>
			//
			// 网络电台流没有心跳包（用户明确的架构约定）：服务端只做
			// 客户端驱动的订阅状态机 + 按配速推流，不额外造「我还活着」的空包。
			// 换曲/轮转的空窗只有 1~2 秒，远小于客户端判超时的 8 秒，
			// 不会误报；上游断流时客户端本来就收不到任何包，
			// 正好按它自己的超时点亮 NOSIGNAL——故障判定完全在客户端。
		}

		for (AudioSubscription sub : dead) {
			SUBSCRIPTIONS.remove(key(sub.player(), sub.pos()));
			sub.closeSource();

		}

		// 网络电台订阅状态机：走远 / 方块拆除 → 通知客户端停止拉流。
		// 音频不经服务端，这里只管「是否还该播」。
		sweepStreamingSubs();
	}

	/**
	 * 巡检网络电台订阅：玩家下线 / 走远 / 方块被拆时移除登记并通知客户端。
	 *
	 * <p>由推送线程每 5ms 调用一次；单个条目的开销只有一次距离平方计算，
	 * 没有节流的必要。移除登记后状态包只发一次（条目已不在表里，不会重复）。
	 */
	private static void sweepStreamingSubs() {
		if (STREAMING_SUBS.isEmpty()) {
			return;
		}
		double stopRange = (HEAR_RANGE + 4.0) * (HEAR_RANGE + 4.0);
		Iterator<Map.Entry<UUID, StreamingSub>> it = STREAMING_SUBS.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, StreamingSub> entry = it.next();
			StreamingSub sub = entry.getValue();
			ServerPlayer player = sub.player();
			if (player.hasDisconnected()) {
				it.remove();
				continue;
			}
			double distSq = player.distanceToSqr(sub.pos().getX() + 0.5,
					sub.pos().getY() + 0.5, sub.pos().getZ() + 0.5);
			if (distSq > stopRange) {
				SmileSRadio.LOGGER.info("[网络电台] 玩家走远，停止订阅：{} @{}",
						sub.stationId(), sub.pos());
				sendStatus(player, sub.pos(), AudioStatus.TOO_FAR);
				it.remove();
				continue;
			}
			var level = player.level();
			if (!level.isLoaded(sub.pos())) {
				continue;
			}
			var state = level.getBlockState(sub.pos());
			net.minecraft.resources.ResourceLocation blockId =
					net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock());
			boolean isRadio = blockId != null
					&& "radio-smile".equals(blockId.getNamespace())
					&& "radio".equals(blockId.getPath());
			if (!isRadio) {
				SmileSRadio.LOGGER.info("[网络电台] 方块已拆除/替换，停止订阅：{} @{}",
						sub.stationId(), sub.pos());
				sendStatus(player, sub.pos(), AudioStatus.STOPPED);
				it.remove();
			}
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
			// <b>没有数据：什么都不发（网络电台流没有心跳包）。</b>
			//
			// 架构约定（用户明确）：服务端只做客户端驱动的订阅状态机 + 按配速推流，
			// 与客户端之间不传「我还活着」的心跳包。故障判定完全在客户端：
			// 它的抖动缓冲（缓存在客户端，不在服务端）吃空后底噪抬高，
			// 8 秒收不到真实音频块就点亮 NOSIGNAL——不需要服务端代劳。
			//
			// 换曲/轮转的空窗只有 1~2 秒，远小于客户端 8 秒的超时窗，
			// 不会因为「没包」而误报。
			//
			// 唯一例外是下面的「空音量包」（S2C_AUDIO_SILENCE）：
			// 它不是心跳，而是换曲预载的<b>静音声明</b>——防止客户端把
			// 预载空窗当成服务器故障去响弱网底噪（用户特意设计的语义）。
			if (sub.source().isPreloadingTracks()) {
				if (sub.isSilenceDue(now)) {
					sendSilence(sub, PRELOAD_SILENCE_MILLIS);
				}
			}
			return;
		}
		byte[] data = chunk.data();

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
		// 换曲后的第一块：先声明一段「换曲空窗」，再发这块音频。
		//
		// 客户端收到这块数据时会关旧解码器 + flush 声卡 + 重新攒抖动缓冲，
		// 中间几百毫秒没有任何音源供数。那段时间它自己的判据分不清
		// 「换曲」和「丢包」，会点亮本该只在断网时出现的底噪。
		// 由服务端提前说一句「这是预载」最准确——时限也只有服务端清楚。
		boolean trackSwitched = sub.noteSentTrackIndex(trackIndex);
		// 流会话号：网络流重连后会变，客户端据此重建解码器
		int sessionId = sub.sessionId();
		server.execute(() -> {
			try {
				if (!ServerPlayNetworking.canSend(target, RadioNetworking.S2C_AUDIO_CHUNK)) {
					SmileSRadio.LOGGER.warn("无法发送音频块：通道不可用 {}", target.getName().getString());
					return;
				}
				// 顺序<b>必须</b>是「空音量包 -> 音频块」：两个包在同一个
				// server.execute 里发出，Fabric 的自定义载荷走可靠有序通道，
				// 客户端会先开窗、后处理换曲，不会出现「先 flush 再开窗」的时序空档。
				if (trackSwitched) {
					writeSilence(target, stationId, targetPos, trackIndex,
							TRACK_SWITCH_SILENCE_MILLIS);
				}
				// 推流增益变了就补一包：同一块里先发增益、再发音频，
				// 客户端拿到的电平顺序才和波形一致。没变时只是一个浮点比较。
				//
				// <b>竞态保护</b>：泵线程把本 execute 入队后、主线程执行它之前，
				// 玩家可能已经换台（unsubscribe → 新台 subscribe → 发 1.0 增益包）。
				// 若此时仍无条件发旧台的 0.025，会在 1.0 之后到达客户端，
				// 把网络台增益表项重新写成 0.025——这正是「网络台声音只有 2.5%」的根因
				// （AD 修复的第一道防线只防静态残留，未防住泵闭包的异步滞后）。
				// 判据：订阅对象还在 SUBSCRIPTIONS 表里就说明它仍然有效。
				if (SUBSCRIPTIONS.containsValue(sub)) {
					// 用块里冻结的文件算增益（不是现读音源当前文件）：
					// 队列积压约 4 秒，现读会让上一首的最后几秒套用下一首的增益。
					float gain = outputGainFor(sub, chunk.file());
					if (sub.noteSentGain(gain)) {
						writeOutputGain(target, targetPos, stationId, gain);
					}
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
	 * 换曲后的第一块音频之前，声明多长的「过渡静音窗」（毫秒）。
	 *
	 * <p>这段时间正好是客户端关旧解码器 + {@code flush()} 声卡 +
	 * 重新攒够抖动缓冲所需的时间（起播门槛约 300ms，再加重建解码器）。
	 * 取 900ms 留余量；客户端另有总上限
	 * （{@code PcmSink.TRANSITION_SILENCE_MAX_MILLIS} = 3 秒），真故障仍会按时报出来。
	 */
	private static final int TRACK_SWITCH_SILENCE_MILLIS = 900;

	/**
	 * 换曲预载期间，每个空音量包声明掩护的时长（毫秒）。
	 *
	 * <p>略大于发包间隔（{@link AudioSubscription#SILENCE_INTERVAL_MILLIS} = 200ms），
	 * 使相邻两包声明的窗口<b>相互重叠</b>——包与包之间不能留缝，
	 * 缝里客户端会重新走回「没数据 = 掉线」的判定。
	 */
	private static final int PRELOAD_SILENCE_MILLIS = 400;

	/**
	 * 命令切歌（{@code /radio play}、{@code /radio reload <频道>}、配置重载）
	 * 拆订阅时声明的过渡静音窗长度（毫秒）。
	 *
	 * <h2>为什么命令路径比自动换曲要多留一点</h2>
	 * 自动换曲只是 {@code PlaylistAudioSource.advance()} 在后台打开下一首，
	 * 订阅对象还在、网络通道不断；而命令路径是<b>整个订阅被拆掉重建</b>：
	 * 关音源 → 重新 {@code subscribe()} → 重新 seek 到新位置 → 客户端重建解码器
	 * → 重新攒抖动缓冲。这几步加起来明显更长。
	 *
	 * <p>用户实测的正是这一段：14:15:20 一次 {@code radio reload}，
	 * 客户端 4~16kHz 噪声底在 10.95s 于 50ms 内跳 12 dB，之后 850ms
	 * 死平在 -8.5 dB 直到新流接上。取 1200ms（850ms 实测 + 余量），
	 * 客户端另有 {@code PcmSink.TRANSITION_SILENCE_MAX_MILLIS} = 3s 的总上限兜底。
	 */
	private static final int COMMAND_SWITCH_SILENCE_MILLIS = 1200;

	/**
	 * 网络电台订阅的增益（恒为 1.0）。
	 *
	 * <p>理由见 {@link #outputGainFor}：网络流是原样透传，电平本来就低一档，
	 * 压它等于篡改别人的流。这个值随订阅回复下发（见 {@code subscribe} 的
	 * 网络电台分支），让客户端的增益表在订阅成立时就被服务端权威刷新。
	 */
	private static final float STREAM_GAIN = 1.0F;

	/**
	 * 这个订阅当前该下发的推流增益（0.00~1.00）。
	 *
	 * <h2>本地曲目：按「这一块属于哪首」归一化</h2>
	 * 先问 {@link radiosmile.radio.LoudnessNormalizer}：它按该曲目的
	 * <b>实测响度</b>与配置的目标电平算出增益（实测结果存在缓存元数据里，
	 * 预热阶段由 ffmpeg 写入，运行时不碰磁盘）。
	 * 只有<b>还没实测</b>的曲目（刚放进来的新文件、缺 ffmpeg）才回落到
	 * 固定的 {@link RadioStationRegistry#outputGain()}。
	 *
	 * <p>{@code chunkFile} 是<b>入队时冻结</b>的文件，不是现读音源的当前文件：
	 * 队列积压可达 4 秒，现读会让上一首最后几秒套用下一首的增益
	 * （同一个坑在曲目序号上已经踩过一次，见 {@code AudioSubscription.Chunk}）。
	 *
	 * <h2>网络电台：恒为 1.0</h2>
	 * 直播流是原样透传的，服务端拿不到可分析的整体内容（而且客户端是直连上游，
	 * 字节根本不经过这里），没有实测依据就不该凭猜测压它。
	 *
	 * <h2>为什么每包重新取</h2>
	 * 取的配置与元数据都是运行时可变的，所以改配置之后下一个包就生效，
	 * <b>不需要重新预热缓存</b>；换曲时增益自然变化，泵线程会随下一块补发。
	 */
	private static float outputGainFor(AudioSubscription sub, java.nio.file.Path chunkFile) {
		if (sub == null || sub.station() == null || sub.station().isStreaming()) {
			return 1.0F;
		}
		float normalized = radiosmile.radio.LoudnessNormalizer.gainFor(chunkFile);
		if (normalized >= 0.0F) {
			return normalized;
		}
		return RadioStationRegistry.outputGain();
	}

	/**
	 * 单独下发「本地推流增益」（0.00~1.00）。
	 *
	 * <h2>为什么单开通道，而不是塞进音频块</h2>
	 * 音频块的字段布局是和旧客户端共用的：往里加一个 float，旧客户端会把它
	 * 当成数据长度读，<b>整条字节流错位</b>，声音直接变噪音。
	 * 单开通道时旧客户端根本没注册这个接收器，{@code canSend} 返回 false，
	 * 这里静默跳过——它只是听不到衰减，播放本身不会坏。
	 *
	 * <p>也正因为独立成包，改配置后不需要重新预热缓存：
	 * 下一块音频前发现值变了就补发一包，客户端立刻按新增益混音。
	 */
	private static void writeOutputGain(ServerPlayer target, BlockPos pos, String stationId, float gain) {
		if (target == null || target.hasDisconnected()) {
			return;
		}
		if (!ServerPlayNetworking.canSend(target, RadioNetworking.S2C_OUTPUT_GAIN)) {
			return;
		}
		// 载荷带 stationId：增益是「某一台节目的电平系数」，不是方块的固有属性。
		//
		// 早期载荷只有 (坐标, 增益)，客户端只能按方块盲写，换台时旧台滞后的
		// 增益包会覆盖新台的值。当时的补救是客户端「每块重置为 1.0」，
		// 但那是拿「满增益」当清理信号——1.0 是合法业务值，结果把本地台
		// 自己的 outputGain 也抹掉了，压缩配置完全失效。
		//
		// 客户端与服务端必须同版本（Fabric 握手会挡掉混用），所以这里
		// 直接演进载荷格式，不需要保留旧布局的降级分支。
		FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeBlockPos(pos);
		buf.writeUtf(stationId == null ? "" : stationId);
		buf.writeFloat(gain);
		ServerPlayNetworking.send(target, RadioNetworking.S2C_OUTPUT_GAIN, buf);
	}

	/**
	 * 发送一个<b>空音量包</b>（无音频字节，只是一句「这段请按数字静音处理」）。
	 *
	 * <p>用于换曲预载期：读线程卡在打开下一首里，队列排空，
	 * 客户端若只看到「没数据」就会当弱网响底噪。
	 *
	 * <p><b>客户端不认识这个通道时自动降级</b>（{@code canSend} 返回 false）：
	 * 旧客户端会保持原行为（提前一点响底噪），不会被污染音频——
	 * 这正是单开通道而不是复用数据块的价值。
	 *
	 * @param sub    订阅
	 * @param millis 本次声明掩护的毫秒数
	 */
	private static void sendSilence(AudioSubscription sub, int millis) {
		ServerPlayer target = sub.player();
		if (target == null || target.hasDisconnected()) {
			return;
		}
		// 与心跳同理：立刻记时间，否则推送线程每 5ms 转一圈就判定「又该发了」。
		sub.markSilenceSent(System.currentTimeMillis());
		String stationId = sub.station().id();
		BlockPos targetPos = sub.pos();
		int trackIndex = sub.trackIndex();
		var server = target.getServer();
		if (server == null) {
			return;
		}
		server.execute(() -> {
			try {
				writeSilence(target, stationId, targetPos, trackIndex, millis);
			} catch (Throwable t) {
				SmileSRadio.LOGGER.error("[空音量包] 发送异常", t);
			}
		});
	}

	/**
	 * 真正写包（<b>不含线程跳转</b>），必须在服务端主线程调用。
	 *
	 * <p>抽出来是为了能在 {@code pump()} 的 {@code server.execute} 块里
	 * <b>与音频块顺序发出</b>：两个包若各自 {@code execute} 一次，
	 * 入队顺序与执行顺序就不再保证，「先开窗、后换曲」的时序会反过来。
	 */
	private static void writeSilence(ServerPlayer target, String stationId, BlockPos pos,
			int trackIndex, int millis) {
		// 重建路径可能在玩家已下线后才走到这里（快照里留着 null / 离线玩家）
		if (target == null || target.hasDisconnected()) {
			return;
		}
		if (!ServerPlayNetworking.canSend(target, RadioNetworking.S2C_AUDIO_SILENCE)) {
			return;
		}
		FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeBlockPos(pos);
		buf.writeUtf(stationId);
		// 曲目序号只为日志对齐：客户端不靠它做任何判定（换曲仍以数据块为准）。
		buf.writeVarInt(trackIndex);
		buf.writeVarInt(millis);
		ServerPlayNetworking.send(target, RadioNetworking.S2C_AUDIO_SILENCE, buf);
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
				// 与自动换曲同样先声明一段「空音量」：命令切歌的空隙比自动换曲更长
				// （整条订阅被拆掉重建），不声明的话客户端只看到「没数据」，
				// 会按弱网故障响底噪——这正是用户听到的那 850ms 嘶声。
				// 这里已经在服务端主线程，直接 writeSilence，不必再 execute 一次。
				writeSilence(snap.player(), snap.stationId(), snap.pos(), sub.trackIndex(),
						COMMAND_SWITCH_SILENCE_MILLIS);
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
			// 与 rebuildStation 同理：整批订阅要拆掉重建，先给每个客户端声明过渡静音，
			// 否则重载期间所有人都会听到「没数据 = 弱网」的底噪。
			writeSilence(sub.player(), sub.station().id(), sub.pos(), sub.trackIndex(),
					COMMAND_SWITCH_SILENCE_MILLIS);
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