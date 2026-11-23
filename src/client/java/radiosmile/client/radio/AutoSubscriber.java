package radiosmile.client.radio;

import java.util.HashMap;
import java.util.Map;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import radiosmile.SmileSRadio;
import radiosmile.block.entity.RadioBlockEntity;
import radiosmile.client.audio.ClientAudioPlayer;

/**
 * 自动订阅附近的收音机。
 *
 * <p><b>为什么需要</b>：订阅原本只在「打开界面点确认」时发起。
 * 玩家重进世界、走回车边、或区块重新加载后，没有任何代码重新订阅，
 * 表现就是「收音机明明设了频道却不响」。
 *
 * <p>这里每 {@link #SCAN_INTERVAL_TICKS} tick 扫一次玩家周围已加载的收音机方块实体，
 * 对「有频道但本机未订阅」的补发订阅请求；离开范围或频道失效的自动退订。
 * 扫描开销很小：只遍历玩家附近已加载区块的方块实体。
 */
public final class AutoSubscriber implements ClientModInitializer {
	/**
	 * 扫描间隔（tick）。
	 *
	 * <p>取 10 tick（约 0.5 秒）：既能让玩家走近时及时听到声音，
	 * 又不会因为频繁遍历方块实体造成负担（只扫玩家附近已加载区块）。
	 */
	private static final int SCAN_INTERVAL_TICKS = 10;
	/** 扫描半径（格）：与可听范围一致，稍留余量。 */
	private static final int SCAN_RADIUS = 52;

	/** 已订阅的方块 -> 频道，用于判断是否变化。 */
	private final Map<BlockPos, String> subscribed = new HashMap<>();

	/**
	 * 最近一次对某个方块发起重订阅的时刻，用于退避。
	 *
	 * <p>没有它的话，「数据超时」判定会每 0.5 秒触发一次重订阅，
	 * 而网络流建连需要 1 秒——请求永远赶不上建连，形成死循环。
	 */
	private final Map<BlockPos, Long> lastResubscribeAt = new HashMap<>();

	/**
	 * 已经为哪些方块打过「开始自动重试」的日志。
	 *
	 * <p>用来把重试日志压成<b>每次故障一行</b>：断网时重订阅每 6 秒一次，
	 * 若每次都打 WARN，一次故障就能刷满日志。恢复后从这里移除，下次故障才会再报。
	 */
	private final java.util.Set<BlockPos> retryLogged = new java.util.HashSet<>();

	/**
	 * 已经为哪些方块打过「开始自动重试」的日志。
	 *

	/**
	 * 同一方块两次重订阅之间的最小间隔（毫秒）。
	 *
	 * <p>取 6 秒：比网络流建连（约 1 秒）+ 首块缓冲（约 0.3 秒）宽裕得多，
	 * 保证每次重试都有机会真正连上；又不至于让真断线的恢复等太久。
	 */
	private static final long RESUBSCRIBE_INTERVAL_MS = 6000L;
	private int tickCounter;

	@Override
	public void onInitializeClient() {
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.player == null || client.level == null) {
				// 离开世界：清空本地记录并重置计数，
				// 否则下次进入时可能带着旧计数，导致首次扫描被推迟。
				this.subscribed.clear();
				this.retryLogged.clear();
				this.tickCounter = 0;
				return;
			}
			if (++this.tickCounter < SCAN_INTERVAL_TICKS) {
				return;
			}
			this.tickCounter = 0;
			if (!radiosmile.client.config.RadioClientConfig.get().enableStreaming) {
				return;
			}
			this.scanAndSync(client);
		});
	}

	/**
	 * 读取方块 NBT 里的音量并应用给混音器。
	 *
	 * <p>音量存在方块实体里（服务端权威值），客户端同步后可直接读到。
	 * 若读不到（区块未加载完 / 旧存档无 BE），退回 1.0。
	 */
	private void applyBlockVolume(Minecraft client, net.minecraft.core.BlockPos pos) {
		float volume = 1.0F;
		try {
			if (client.level != null
					&& client.level.getBlockEntity(pos)
					instanceof radiosmile.block.entity.RadioBlockEntity be) {
				volume = be.getVolume();
			}
		} catch (Throwable ignored) {
			// 区块状态异常时用默认值
		}
		radiosmile.client.audio.PcmSink.INSTANCE.setVolumeFor(pos.asLong(), volume);
	}

	private void scanAndSync(Minecraft client) {
		BlockPos playerPos = client.player.blockPosition();
		Map<BlockPos, String> found = new HashMap<>();

		int chunkRadius = (SCAN_RADIUS + 15) / 16;
		int pcx = playerPos.getX() >> 4;
		int pcz = playerPos.getZ() >> 4;

		for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
			for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
				var chunk = client.level.getChunkSource().getChunk(pcx + dx, pcz + dz, false);
				if (chunk == null) {
					continue;
				}
				for (var entry : chunk.getBlockEntities().entrySet()) {
					if (!(entry.getValue() instanceof RadioBlockEntity be)) {
						continue;
					}
					String station = be.getStation();
					if (station == null || station.isBlank()) {
						continue;
					}
					BlockPos pos = entry.getKey();
					if (playerPos.distSqr(pos) > (double) SCAN_RADIUS * SCAN_RADIUS) {
						continue;
					}
					found.put(pos.immutable(), station);
				}
			}
		}

		// 新增 / 频道变化 / <b>播放已中断</b> 的：重新订阅
		for (Map.Entry<BlockPos, String> entry : found.entrySet()) {
			// <b>每次扫描都同步一次方块音量</b>，不能只在订阅时抓一次快照。
			//
			// 音量存在方块 NBT 里，客户端只有主动去读才知道当前值。
			// 早期只在「新订阅 / 重连」时调 applyBlockVolume()，导致只要没打开过界面，
			// 混音器就一直用默认音量 1.0——玩家调好的音量传不进来。
			// 这就是「不打开 UI 就不受收音机音量控制」的根因。
			//
			// 每 10 tick（0.5 秒）读一个 float 字段，开销可忽略。
			this.applyBlockVolume(client, entry.getKey());

			String previous = this.subscribed.get(entry.getKey());
			boolean isNew = previous == null || !previous.equals(entry.getValue());
			// 关键补充：服务端可能因「走远 / 换台 / 重载」主动停推，
			// 但本地记录仍显示「已订阅」，于是再也不发起订阅——表现为
			// 「离开范围再回来，有概率不会重新激活」。
			// 因此以「客户端是否真的在播」为准判断，而不是只看 map。
			boolean notStreaming = !isNew && !ClientAudioPlayer.isStreaming(entry.getKey());
			// <b>流僵住</b>：流对象还在，但很久没收到数据。
			// 服务端断网/上游卡死时正是这种状态——订阅有效但永远没数据，
			// 只判 isStreaming 会认为「一切正常」而不重订阅，
			// 结果断网恢复后电台永久沉默。
			boolean stale = !isNew && ClientAudioPlayer.isStalled(entry.getKey());
			boolean stalled = notStreaming || stale;
			// <b>重订阅必须有退避</b>：扫描每 0.5 秒一次，若每次都重发订阅请求，
			// 服务端会被重建请求淹没（网络流建连要 1 秒，永远赶不上），
			// 实测日志里每秒都在重建连、永远等不到数据。
			// 这里限制同一方块最短重试间隔。
			boolean canRetry = System.currentTimeMillis() - this.lastResubscribeAt
					.getOrDefault(entry.getKey(), 0L) >= RESUBSCRIBE_INTERVAL_MS;
			if (isNew || (stalled && canRetry)) {
				// <b>日志只在「开始」时打一次</b>，不随每次重试重复。
				//
				// 早期这里是 INFO 级、每次重订阅都打一条。真断网时扫描每 0.5 秒一次、
				// 退避 6 秒，一次故障就能刷出几十行「数据超时，重新拉流」——
				// 用户实测反馈「现在会刷屏」。恢复的「结束」日志由音频摄入线程在
				// 真正收到数据时打（见 ClientAudioPlayer 的恢复日志），这里只管开始。
				if (isNew) {
					SmileSRadio.LOGGER.info("[自动订阅] 开始接收 {} @{}", entry.getValue(), entry.getKey());
				} else if (!this.retryLogged.contains(entry.getKey())) {
					// 同一方块在一次故障里只报一次「开始重试」。
					this.retryLogged.add(entry.getKey());
					SmileSRadio.LOGGER.warn("[自动订阅] {} 无数据，开始自动重试 @{}",
							entry.getValue(), entry.getKey());
				}
				this.lastResubscribeAt.put(entry.getKey(), System.currentTimeMillis());
				ClientRadioNetwork.subscribeAudio(entry.getKey(), entry.getValue());
			}
			// 数据超时：立刻点亮红灯 + 液晶屏提示，让玩家知道「不是坏了，是在重连」。
			// 这比干等着什么反应都没有要明确得多。
			//
			// <b>这里只负责"点灯"，不负责"灭灯"</b>——而且是有意为之。
			//
			// 早期注释写的是"恢复后 markFault(OK) 会自动熄灭"，但那个调用**在代码里并不存在**：
			// 整份工程里没有任何地方在恢复时清 clientFaultStatus，
			// 于是 ERR408 一旦点亮就会留到退出世界——正是用户反馈的「恢复了 err 也不消除」。
			//
			// 为什么不在下面写一个 else 分支去清？因为判据本身有 8 秒窗口
			// （ClientAudioPlayer.STALL_TIMEOUT_MILLIS），数据在窗口边缘到达时
			// stale 会一 tick 真一 tick 假；用它来"灭灯"会让屏幕闪烁。
			//
			// 正确的灭灯判据是<b>数据真的回来了</b>，那件事只有音频摄入线程知道，
			// 由 ClientAudioPlayer.handleChunk() 跳回主线程去清
			// （见 clearClientFaultOnResume）。这里保持"只点灯"，
			// 让"点灯"与"灭灯"各自只有一处，符合 HANDOVER 第十二节的原则。
			if (stale) {
				ClientRadioNetwork.markLocalFault(entry.getKey(),
						radiosmile.net.AudioStatus.TIMEOUT);
				// 本地判定为故障：告诉混音器"这是明确故障，不必等本流出过声就可以响"。
				//
				// 但要先确认<b>玩家确实还在等这个台</b>：用户刚点空选、方块 NBT 还没同步回来的
				// 一两个 tick 里，本次扫描仍可能把它算成"有频道但没在收流"。
				// 那种情况下不能顺手把故障提示重新点亮，否则"关掉的收音机又沙沙响一下"。
				if (radiosmile.client.audio.ClientAudioPlayer.wantsStream(entry.getKey())) {
					radiosmile.client.audio.PcmSink.INSTANCE.markFaultReported();
					radiosmile.client.audio.PcmSink.INSTANCE.setPlaybackWanted(true);
					radiosmile.client.audio.PcmSink.INSTANCE.setNetworkNoiseSource(entry.getKey());
				}
			}
		}
		// 消失或频道被清空的：退订
		for (Map.Entry<BlockPos, String> entry : this.subscribed.entrySet()) {
			if (!found.containsKey(entry.getKey())) {
				ClientRadioNetwork.unsubscribeAudio(entry.getKey());
				radiosmile.client.audio.ClientAudioPlayer.stop(entry.getKey());
				this.retryLogged.remove(entry.getKey());
			}
		}
		// 已经在正常收流的不再算「重试中」：撤掉标记，
		// 这样它下次真的断线时还能再报一次「开始自动重试」。
		for (BlockPos pos : found.keySet()) {
			if (radiosmile.client.audio.ClientAudioPlayer.isStreaming(pos)) {
				this.retryLogged.remove(pos);
			}
		}

		this.subscribed.clear();
		this.subscribed.putAll(found);
	}
}