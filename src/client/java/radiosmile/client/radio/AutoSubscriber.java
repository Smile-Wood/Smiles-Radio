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
 *
 * <h2>这里<b>不负责</b>网络重连（本轮架构调整）</h2>
 * 职责划分：
 * <ul>
 *   <li><b>服务端</b>只管<b>自定义电台（本地文件）</b>与<b>方块数据</b>。
 *       网络电台对它而言只是顺手转发的一根管子，管子断了它<b>不修</b>
 *       （见 {@code StreamAudioSource}）。</li>
 *   <li><b>玩家掉线 / 断网</b> → 服务端判定下线 → 清理该玩家全部订阅。
 *       玩家重连回来 → 算重新上线 → 客户端从零订阅。</li>
 *   <li><b>客户端与服务器的连接只是不顺畅</b>（还没彻底断开）→
 *       <b>不重订阅、不重连</b>，表现只是<b>底噪变大 + 屏幕 NOSIGNAL</b>。</li>
 * </ul>
 *
 * <p>因此本类只在「本地压根没有这条订阅」时才补订阅（{@code notStreaming}），
 * 而「订阅还在、只是没数据」（{@code stale}）只用来<b>显示故障</b>。
 * 早期把两者混为一谈，导致断网期间客户端每 6 秒重发订阅、
 * 服务端反复重建网络流，音频一直抖、日志也刷屏。
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

	/**
	 * 该频道是否是<b>网络电台</b>（http/https 流）。
	 *
	 * <p>判断依据是服务端随列表下发的 {@code file} 字段，
	 * 由 {@link radiosmile.radio.RadioStation#isStreaming()} 统一判定，
	 * 客户端不自己猜协议前缀。
	 *
	 * <p>找不到该频道（列表还没到 / 被移除）时返回 false：
	 * 宁可不点亮"无信号"，也不要在本地电台上误报——见调用点的说明。
	 */
	private static boolean isStreamingStation(String stationId) {
		if (stationId == null || stationId.isBlank()) {
			return false;
		}
		for (radiosmile.radio.RadioStation station : ClientRadioData.stations()) {
			if (station.id().equals(stationId)) {
				return station.isStreaming();
			}
		}
		return false;
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
			// <b>流对象根本不存在</b>：这不是"网络抖动"，而是本机这边没有这条订阅
			// （刚进世界、区块重新加载、服务端清理过订阅等）。
			// 只有这种情况才需要向服务端发起订阅。
			boolean notStreaming = !isNew && !ClientAudioPlayer.isStreaming(entry.getKey());
			// <b>流僵住</b>：流对象还在，但很久没收到数据。
			//
			// <b>这不算"要重订阅"，只是"要显示故障"</b>——这是本轮最重要的语义区分：
			//   - 服务端不管网络电台的死活（上游断了它不修）；
			//   - 客户端只要还没和服务器彻底断连，就<b>不重订阅</b>，
			//     表现只是"底噪变大 + 屏幕 NOSIGNAL"；
			//   - 真正的恢复只有一条路：玩家重新连回服务器
			//     （那时服务端清空该玩家订阅，客户端 JOIN 后一切从头订阅）。
			//
			// 早期这里把 stale 也当成"该重订阅"，于是断网期间客户端每 6 秒
			// 重发一次订阅、服务端反复重建订阅与网络流——既刷日志又让音频永远抖。
			boolean stale = !isNew && ClientAudioPlayer.isStalled(entry.getKey());

			// <b>只有"没有订阅"才补订阅</b>；网络抖动/上游断流一律不动订阅。
			//
			// 退避仍然保留：扫描每 0.5 秒一次，若流对象反复建不起来，
			// 没有退避会每 0.5 秒发一次订阅请求。
			boolean canRetry = System.currentTimeMillis() - this.lastResubscribeAt
					.getOrDefault(entry.getKey(), 0L) >= RESUBSCRIBE_INTERVAL_MS;
			if (isNew || (notStreaming && canRetry)) {
				if (isNew) {
					SmileSRadio.LOGGER.info("[自动订阅] 开始接收 {} @{}", entry.getValue(), entry.getKey());
				} else if (!this.retryLogged.contains(entry.getKey())) {
					// 同一方块在一次故障里只报一次。
					this.retryLogged.add(entry.getKey());
					SmileSRadio.LOGGER.warn("[自动订阅] 订阅缺失，重新建立 {} @{}",
							entry.getValue(), entry.getKey());
				}
				this.lastResubscribeAt.put(entry.getKey(), System.currentTimeMillis());
				ClientRadioNetwork.subscribeAudio(entry.getKey(), entry.getValue());
			}
			// 数据超时：点亮红灯 + 屏幕 NOSIGNAL，让玩家知道「不是设备坏了，是没信号」。
			//
			// <b>注意：这里只显示故障，不做任何"修复"动作</b>——不重订阅、不重连。
			// 上游断流是服务端也不管的事（见 StreamAudioSource 类注释），
			// 玩家的实际体验应该是「底噪变大、屏幕 NOSIGNAL，等网络自己好」，
			// 而不是客户端在后台反复折腾、把音频抖得更厉害。
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
			//
			// <b>而且只对"网络电台"点亮</b>（本轮收窄）：
			// 「无信号」这个概念只属于网络电台——上游 HTTP 流断了，才叫没信号。
			// 自定义电台（本地文件 / 播放列表）不存在"信号"问题：
			// 它的数据就在服务器硬盘上，读不到是服主配置或转码的问题，
			// 显示 NOSIGNAL 会误导玩家（明明不是网络问题）。
			// 所以先用 {@link #isStreamingStation} 判断电台类型再决定是否点亮。
			//
			// <b>另外两类也要报 NOSIGNAL</b>（用户明确要求）：
			//   1) <b>未知频道</b>：方块 NBT 里的 id 已经不在配置里（重载后被删/改名/变无效）。
			//      它永远不会再有任何数据，属于工程性故障，必须持续提示。
			//   2) 配置有效、但<b>已配置过却被重载搞坏</b>的频道——从客户端看就是"未知频道"，
			//      因为服务端已把它从下发列表里摘掉了（见 RadioStationRegistry#isPlayable）。
			// 这两种情况都没有"信号"可言，所以不受 isStreamingStation 限制。
			boolean unknownStation = ClientRadioData.findStation(entry.getValue()) == null;
			if (unknownStation) {
				// 未知频道：直接点亮 + 持续噪音（不再等 8 秒超时窗口，它注定不会好）
				ClientRadioNetwork.markLocalFault(entry.getKey(),
						radiosmile.net.AudioStatus.UNKNOWN_STATION);
				if (radiosmile.client.audio.ClientAudioPlayer.wantsStream(entry.getKey())) {
					radiosmile.client.audio.ClientAudioPlayer.markUnknownStationFailure(entry.getKey());
				}
				continue;
			}
			if (stale && isStreamingStation(entry.getValue())) {
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