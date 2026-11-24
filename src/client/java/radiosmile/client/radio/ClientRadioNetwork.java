package radiosmile.client.radio;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;

import radiosmile.net.RadioNetworking;

/** 客户端网络处理。 */
public final class ClientRadioNetwork {
	/** 已收到的音频块计数，用于诊断。 */
	private static final java.util.concurrent.atomic.AtomicLong AUDIO_CHUNK_COUNT =
			new java.util.concurrent.atomic.AtomicLong();

	private ClientRadioNetwork() {
	}

	public static void register() {
		ClientPlayNetworking.registerGlobalReceiver(RadioNetworking.S2C_STATIONS,
				(Minecraft client, ClientPacketListener handler, FriendlyByteBuf buf, PacketSender sender) -> {
					long epoch = buf.readLong();
					String json = buf.readUtf(1 << 20);
					client.execute(() -> ClientRadioData.acceptStations(epoch, json));
				});

		ClientPlayNetworking.registerGlobalReceiver(RadioNetworking.S2C_SELECT_ACK,
				(Minecraft client, ClientPacketListener handler, FriendlyByteBuf buf, PacketSender sender) -> {
					String stationId = buf.readUtf();
					String hash = buf.readUtf();
					long epoch = buf.readLong();
					float volume = buf.readFloat();
					int formatId = buf.readVarInt();
					long skipMillis = buf.readVarLong();
					net.minecraft.core.BlockPos ackPos = buf.readBlockPos();
					client.execute(() -> {
						ClientRadioPlayback.onAck(stationId, hash, epoch);
						// 握手包里的解码元数据：客户端只需要「跳过的时长」。
						// formatId 仍然要读出来（协议字段还在，跳过不读会让后续字节错位），
						// 但<b>不再往下传</b>：客户端只解 MP3，没有第二条解码路径可分流。
						radiosmile.radio.AudioFormat announced = radiosmile.radio.AudioFormat.byId(formatId);
						if (announced != radiosmile.radio.AudioFormat.MP3
								&& announced != radiosmile.radio.AudioFormat.UNKNOWN) {
							// 只提示不拦截：最终以实到数据块为准（握手值理论上不会和实到数据不一致，
							// 但拦截点放在唯一解码的地方更好维护）。
							radiosmile.SmileSRadio.LOGGER.warn(
									"握手告知的音频格式 {} 不是 MP3，客户端只接受 MP3", announced);
						}
						radiosmile.client.audio.ClientAudioPlayer.setPendingMetadata(skipMillis);
						// 应用该方块自己的音量（各收音机独立）
						radiosmile.client.audio.PcmSink.INSTANCE.setBlockVolume(volume);

						// <b>顺序很重要</b>：先让调台噪淡入，再让旧音频淡出。
						//
						// 之前是「先 stop 再播噪声」，听感是旧音频戛然而止、
						// 然后才冒出沙沙声——两件事被硬生生分成两步。
						// 正确的电台手感是「一边沙沙响着，一边旧台淡出」，
						// 所以噪声必须提前到旧音频真正停止【之前】开始。
						//
						// 回执带方块坐标，噪声立刻知道该按哪台收音机的
						// 音量/距离/声像播放，与节目完全一致。
						radiosmile.client.audio.PcmSink.INSTANCE.playTuningNoise(ackPos);

						// 紧接着旧音频会被淡出并移除，新订阅的数据要等建连 + 预热，
						// 这中间同样是一段「没有任何音源供数」的空窗——一并声明掉，
						// 免得换台时也响一段弱网底噪。
						radiosmile.client.audio.PcmSink.INSTANCE.beginTransitionSilence();

						// 然后让旧音频淡出（不是硬切），与噪声重叠约 120ms，
						// 这样交界处没有断点。服务端此刻已开始为新频道建连
						// （网络流约 1 秒），旧内容不再是「当前节目」。
						if (ackPos != null && !ackPos.equals(net.minecraft.core.BlockPos.ZERO)) {
							radiosmile.client.audio.ClientAudioPlayer.fadeOutAndStop(ackPos, 120);
						}
					});
				});

		ClientPlayNetworking.registerGlobalReceiver(RadioNetworking.S2C_ICON,
				(Minecraft client, ClientPacketListener handler, FriendlyByteBuf buf, PacketSender sender) -> {
					String stationId = buf.readUtf();
					byte[] bytes = buf.readByteArray(1 << 20);
					client.execute(() -> ClientRadioData.acceptIcon(stationId, bytes));
				});

		// 音频数据块
		ClientPlayNetworking.registerGlobalReceiver(RadioNetworking.S2C_AUDIO_CHUNK,
				(Minecraft client, ClientPacketListener handler, FriendlyByteBuf buf, PacketSender sender) -> {
					net.minecraft.core.BlockPos pos = buf.readBlockPos();
					String stationId = buf.readUtf();
					int trackIndex = buf.readVarInt();
					int sessionId = buf.readVarInt();
					int formatId = buf.readVarInt();
					byte[] data = buf.readByteArray(1 << 20);
					AUDIO_CHUNK_COUNT.incrementAndGet();
					// 不能在渲染线程喂音频：管道满时 feed() 会阻塞，直接卡住画面。
					// 这里交给专用的音频摄入线程。
					radiosmile.client.audio.ClientAudioPlayer.enqueue(
							pos, stationId, trackIndex, sessionId,
							radiosmile.radio.AudioFormat.byId(formatId), data);
				});

		// 「本地推流增益」：把该音源的混音电平乘上一个系数。
		//
		// 本地曲目是"给游戏内直接播放"处理过的成品，母带电平高；网络电台是
		// 原样透传的上游流，电平低一档。服务端按 stations.json 的 outputGain
		// 只压本地，两边响度才对得齐，也给混音留出余量（满刻度叠加底噪会削波）。
		//
		// <b>由服务端主动下发、值变才发</b>，所以改配置不需要重新预热缓存。
		// 这条通道是新开的：旧客户端没注册它，服务端 canSend 为 false 会直接跳过，
		// 不会污染音频字节流。
		// 不 hop 主线程：只写一个并发 map，值必须在本块混音前就位。
		ClientPlayNetworking.registerGlobalReceiver(RadioNetworking.S2C_OUTPUT_GAIN,
				(Minecraft client, ClientPacketListener handler, FriendlyByteBuf buf, PacketSender sender) -> {
					net.minecraft.core.BlockPos pos = buf.readBlockPos();
					float gain = buf.readFloat();
					radiosmile.client.audio.PcmSink.INSTANCE.setStreamGain(pos.asLong(), gain);
				});

		// 「空音量包」：服务端声明「接下来这段是换曲预载/重载，请输出数字静音」。
		//
		// <b>它不带任何音频字节</b>，绝不能被喂给解码器；作用只有一个——
		// 把混音器的过渡静音窗往后铺，让它在预载期间不把「没有任何音源供数」
		// 当成弱网去响底噪（见 PcmSink#beginTransitionSilence）。
		//
		// <b>不 hop 到主线程</b>：窗口是时间敏感的（覆盖范围只有几百毫秒），
		// 再等一个客户端 tick（最多 50ms）会让开头露在窗外。
		// 这个方法只写 volatile 时间戳与混音线程独占的计数器，可以在网络线程直接调。
		ClientPlayNetworking.registerGlobalReceiver(RadioNetworking.S2C_AUDIO_SILENCE,
				(Minecraft client, ClientPacketListener handler, FriendlyByteBuf buf, PacketSender sender) -> {
					buf.readBlockPos();          // 方块坐标：目前不参与判定，读掉以免字节错位
					buf.readUtf();               // 频道 id：同上，为日志对齐预留
					buf.readVarInt();            // 曲目序号：换曲仍以数据块为准
					int millis = buf.readVarInt();
					// <b>这里绝不触发调台噪</b>：本包的职责就是「声明静音、防止客户端
					// 把预载空窗误判成服务器故障而去响底噪」。换曲是台内正常衔接，
					// 不是调台跳变，不该有噪声盖在上面；调台噪只在真正的换台
					// （S2C_SELECT_ACK / handleChunk 换台路径）触发。
					radiosmile.client.audio.PcmSink.INSTANCE.beginTransitionSilence(millis);
				});

		// 服务端重载了电台配置：接下来会有一轮重建订阅带来的跳变。
		// 播一段调台噪把它盖住，并把各方块的故障显示清掉（重载后即将重新订阅）。
		ClientPlayNetworking.registerGlobalReceiver(RadioNetworking.S2C_RELOADED,
				(Minecraft client, ClientPacketListener handler, FriendlyByteBuf buf, PacketSender sender) -> {
					client.execute(() -> {
						radiosmile.SmileSRadio.LOGGER.info("服务端已重载电台配置，播放跳变将由调台噪掩盖");
						radiosmile.client.audio.PcmSink.INSTANCE.playTuningNoise();
						// 重载会重建<b>每个频道</b>的订阅（网络台还要含建连），
						// 空窗比普通换曲长得多，所以这里直接给一个更长的窗。
						// 上限由 PcmSink 自己钳住（TRANSITION_SILENCE_MAX_MILLIS），
						// 真坏掉的台仍会按时响底噪。
						radiosmile.client.audio.PcmSink.INSTANCE.beginTransitionSilence(2000);
					});
				});

		// 订阅状态
		ClientPlayNetworking.registerGlobalReceiver(RadioNetworking.S2C_AUDIO_STATUS,
				(Minecraft client, ClientPacketListener handler, FriendlyByteBuf buf, PacketSender sender) -> {
					net.minecraft.core.BlockPos pos = buf.readBlockPos();
					int status = buf.readVarInt();
					client.execute(() -> {
						// 把状态记进方块实体的「客户端显示状态」：
						// 渲染层据此点亮红灯并在液晶屏上写出 NOSIGNAL。
						//
						// <b>只有"网络电台没信号"才点亮屏幕</b>（本轮收窄）。
						// 屏幕上的 NOSIGNAL 是给玩家的一句话："上游网络断了"。
						// 而下面这些状态<b>不是</b>信号问题，点亮会误导：
						//   - TOO_FAR    ：玩家自己走远了（走进范围就会自动恢复）
						//   - STOPPED    ：服务端主动停推（重载 / 服主操作）
						//   - UNKNOWN_STATION / NO_AUDIO / UNSUPPORTED_FORMAT：
						//                  服主配置或转码问题，与"信号"无关
						// 它们仍然要打日志（排查需要），只是不上屏幕。
						markFault(client, pos, displayStatusFor(client, pos, status));
						if (status != radiosmile.net.AudioStatus.OK) {
							// <b>同一方块的同一种故障只报一次</b>：重试期间服务端可能反复回同一个状态码，
							// 每次打 WARN 会把日志刷满。状态没变就不再重复（变化时仍会报）。
							radiosmile.client.audio.ClientAudioPlayer
									.logFaultOnce(pos, status);
							// <b>走哪条入口要按语义分流</b>——这里是 HANDOVER 第十二节
							// 「一个函数不要承担两种语义」的直接应用：
							//
							//   TOO_FAR：玩家走远了，是<b>位置</b>问题，不是链路故障。
							//     它等价于"暂时不要这个台"（走进 48 格 AutoSubscriber 会自动重订阅），
							//     所以走 stop()：撤销播放意图，安静。
							//     早期它和别的故障一样走 stopForFailure()，修好底噪之后会变成
							//     "底噪响在一台玩家根本听不到的收音机上"，而且意图卡在 true 不撤。
							//
							//   其它非 OK：玩家仍然想听，只是暂时失败——走 stopForFailure()，
							//     保留播放意图，让故障提示音与自动重连都有依据。
							// <b>UNKNOWN_STATION 也要"持续噪音"</b>（用户明确要求）：
							// 方块记着一个不存在的频道时，玩家听到的应该是底噪，
							// 而不是死寂——死寂分不清"设备坏了"和"台没了"。
							// stopForFailure 保留播放意图，底噪因此有依据持续响。
						if (status == radiosmile.net.AudioStatus.TOO_FAR) {
							radiosmile.client.audio.ClientAudioPlayer.stop(pos);
						} else if (status == radiosmile.net.AudioStatus.TIMEOUT) {
							// <b>TIMEOUT 走「轻量故障路径」，不拆流、不重订阅。</b>
							//
							// 服务端在上游断流时主动宣告 TIMEOUT（见 AudioStreamManager#pump）。
							// 此时服务器仍然认为客户端在播放、订阅原样保留，
							// 上游的重连由服务端 StreamAudioSource 的自动退避重连负责；
							// 客户端这边只需要点亮故障表现（灯 + NOSIGNAL + 底噪），
							// 等 Stream 的流对象被真实音频块唤醒（handleChunk）后自动灭灯。
							//
							// 若这里也走 stopForFailure（拆流），AutoSubscriber 会在 6 秒后
							// 重订阅 → 服务端拆掉旧订阅重建新源 → 打断正在退避重连的旧源，
							// 形成「每 6 秒重来一次」的双重抖动。
							if (radiosmile.client.audio.ClientAudioPlayer.wantsStream(pos)) {
								radiosmile.client.audio.PcmSink.INSTANCE.setPlaybackWanted(true);
								radiosmile.client.audio.PcmSink.INSTANCE.markFaultReported();
								radiosmile.client.audio.PcmSink.INSTANCE.setNetworkNoiseSource(pos);
							}
						} else {
							// stopForFailure 内部已经处理了"保留播放意图 + 注册噪音归属方块"，
							// 这里不要再重复调一遍 PcmSink（会绕开 REQUESTED 判据）。
							radiosmile.client.audio.ClientAudioPlayer.stopForFailure(pos);
						}
						} else {
							// OK：链路恢复。让混音器按自己的落音速率淡出底噪，
							// 不要硬切（硬切听感是"沙"地一下断掉）。
							radiosmile.client.audio.PcmSink.INSTANCE.onLinkRecovered();
							// <b>网络电台：客户端自己从上游拉流</b>（架构约定：
							// 服务端只维护订阅状态机，音频不经服务端）。
							// 订阅 OK = 服务端认可播放，这里起拉流线程直连上游；
							// 本地电台则确保没有残留的拉流任务。
							if (client.level != null
									&& client.level.getBlockEntity(pos)
											instanceof radiosmile.block.entity.RadioBlockEntity be) {
								radiosmile.radio.RadioStation station =
										radiosmile.client.radio.ClientRadioData.findStation(be.getStation());
								if (station != null && station.isStreaming()) {
									radiosmile.client.audio.ClientAudioPlayer.startStreamFeed(
											pos, station.id(), station.file());
								} else {
									radiosmile.client.audio.ClientAudioPlayer.stopStreamFeed(pos);
								}
							}
						}
					});
				});
	}

	/**
	 * 把「服务端状态码」翻译成「屏幕上该显示什么」。
	 *
	 * <h2>为什么需要这一层</h2>
	 * 状态码是<b>传输层</b>的事实（订阅成功/失败及原因），
	 * 而屏幕上的 {@code NOSIGNAL} 是给玩家看的一句<b>话</b>：
	 * "上游网络断了"。两者<b>不是一一对应</b>的关系。
	 *
	 * <p>早期直接把所有非 OK 都显示成 NOSIGNAL，于是：
	 * <ul>
	 *   <li>玩家走远了（{@code TOO_FAR}）→ 屏幕显示"无信号"，
	 *       但其实是玩家自己的位置问题，走进范围就恢复；</li>
	 *   <li>服主把音频文件删了（{@code NO_AUDIO}）→ 也显示"无信号"，
	 *       这个提示会把人往"查网络"的方向带偏，而真因在文件。</li>
	 * </ul>
	 *
	 * <h2>规则</h2>
	 * <ul>
	 *   <li>{@code OK} → 清除显示（不点亮）。</li>
	 *   <li>{@code TIMEOUT} → 点亮 NOSIGNAL。<b>但仅当该方块当前频道是网络电台</b>：
	 *       本地文件不存在"信号"这回事，读不到是配置/转码问题，
	 *       显示 NOSIGNAL 属于误导。</li>
	 *   <li>其它 → <b>不点亮</b>（日志照打，屏幕保持干净）。</li>
	 * </ul>
	 *
	 * @return 要写入方块实体的状态；返回 {@code OK} 表示不显示故障
	 */
	private static int displayStatusFor(Minecraft client, net.minecraft.core.BlockPos pos, int status) {
		if (status == radiosmile.net.AudioStatus.OK) {
			return radiosmile.net.AudioStatus.OK;
		}

		// <b>未知频道 / 配置已失效的频道：一定要显示 NOSIGNAL</b>（用户明确要求）。
		//
		// 场景：方块上存着某个频道 id，但服主重载配置后那个条目没了或变无效。
		// 这时玩家面对的是一台"有频道、却什么都放不出来"的收音机——
		// 如果屏幕保持干净，他会以为设备坏了；显示 NOSIGNAL 才是对的。
		if (status == radiosmile.net.AudioStatus.UNKNOWN_STATION) {
			return status;
		}

		// 只有"数据超时/链路断了"才是信号问题
		if (status != radiosmile.net.AudioStatus.TIMEOUT) {
			return radiosmile.net.AudioStatus.OK;
		}
		// 再确认频道类型：本地/自定义电台不该出现"无信号"
		if (client == null || client.level == null || !client.level.isLoaded(pos)) {
			// 读不到方块就保守不显示，避免误报
			return radiosmile.net.AudioStatus.OK;
		}
		if (client.level.getBlockEntity(pos)
				instanceof radiosmile.block.entity.RadioBlockEntity be) {
			String stationId = be.getStation();
			// <b>方块记着的频道已经不在配置里了</b>（重载后失效 / 一直被写坏）：
			// 同样按"未知频道"报警。这条覆盖了"客户端没收到 UNKNOWN_STATION 状态包"
			// 的情况（例如重载时玩家正好不在线、或状态包在途丢失）。
			//
			// <b>但列表未加载时绝不能判"未知"</b>：那会把所有频道都误判成未知，
			// 于是每 0.5 秒点亮一次、收到音频又清掉 —— 表现正是"NOSIGNAL 一直闪"。
			// 进服时已主动请求列表（见 AudioLifecycleHooks），这里只做保守兜底。
			if (radiosmile.client.radio.ClientRadioData.isLoaded()
					&& !stationId.isBlank()
					&& radiosmile.client.radio.ClientRadioData.findStation(stationId) == null) {
				return radiosmile.net.AudioStatus.UNKNOWN_STATION;
			}
			for (radiosmile.radio.RadioStation station : radiosmile.client.radio.ClientRadioData.stations()) {
				if (station.id().equals(stationId)) {
					return station.isStreaming()
							? status
							: radiosmile.net.AudioStatus.OK;
				}
			}
		}
		// 读不到方块（区块没加载等）：保守不显示
		return radiosmile.net.AudioStatus.OK;
	}

	/**
	 * 记录/清除某个方块的故障显示状态。
	 *
	 * <p>只写客户端方块实体（不写盘、不跨端同步）：故障是<b>每个客户端各自观察到的结果</b>
	 * （A 走远了听不到、B 还听得到），不是方块的客观属性。
	 */
	private static void markFault(Minecraft client, net.minecraft.core.BlockPos pos, int status) {
		if (client.level == null) {
			return;
		}
		// 区块可能尚未加载：那时没有可显示的方块，忽略即可
		if (!client.level.isLoaded(pos)) {
			return;
		}
		if (client.level.getBlockEntity(pos)
				instanceof radiosmile.block.entity.RadioBlockEntity be) {
			be.setClientFaultStatus(status == radiosmile.net.AudioStatus.OK ? 0 : status);
			// 同步一份"屏幕上是否亮着 ERR"的影子标志给 ClientAudioPlayer，
			// 让它能在数据恢复时省掉一次主线程排队（见 clearClientFaultOnResume）。
			// 真值仍然只存在方块实体里，这里只是加速用的镜像。
			radiosmile.client.audio.ClientAudioPlayer.noteFaultShown(
					status != radiosmile.net.AudioStatus.OK);
		}
	}

	/** 标记某个方块为故障（供本地检测到的失败复用，例如解码失败）。 */
	public static void markLocalFault(net.minecraft.core.BlockPos pos, int status) {
		markFault(net.minecraft.client.Minecraft.getInstance(), pos, status);
	}

	/** 订阅某个方块的音频流。 */
	public static void subscribeAudio(net.minecraft.core.BlockPos pos, String stationId) {
		if (!ClientPlayNetworking.canSend(RadioNetworking.C2S_AUDIO_SUBSCRIBE)) {
			return;
		}
		// 这是"玩家想要这个台"的正式声明，先记下来：
		// 若服务端在第一个音频块到达之前就报失败（NO_AUDIO 等），
		// 故障入口要靠它才能知道"玩家还在等"，而不是"用户已经关台"。
		radiosmile.client.audio.ClientAudioPlayer.noteStreamRequested(pos);
		var config = radiosmile.client.config.RadioClientConfig.get();
		FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeBlockPos(pos == null ? net.minecraft.core.BlockPos.ZERO : pos);
		buf.writeUtf(stationId);
		buf.writeVarInt(config.maxBandwidthKbps);
		buf.writeVarInt(config.maxChunkBytes);
		ClientPlayNetworking.send(RadioNetworking.C2S_AUDIO_SUBSCRIBE, buf);
	}

	/** 退订。 */
	public static void unsubscribeAudio(net.minecraft.core.BlockPos pos) {
		// 主动退订 = 不再想要这个台。先撤销意图，避免随后到达的状态包把它重新点亮。
		radiosmile.client.audio.ClientAudioPlayer.clearStreamRequested(pos);
		if (!ClientPlayNetworking.canSend(RadioNetworking.C2S_AUDIO_UNSUBSCRIBE)) {
			return;
		}
		FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeBlockPos(pos == null ? net.minecraft.core.BlockPos.ZERO : pos);
		ClientPlayNetworking.send(RadioNetworking.C2S_AUDIO_UNSUBSCRIBE, buf);
	}

	/** 清空方块记录的频道（选择被取消时用）。 */
	public static void clearStation(net.minecraft.core.BlockPos pos) {
		if (!ClientPlayNetworking.canSend(RadioNetworking.C2S_SELECT_STATION)) {
			return;
		}
		FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeUtf("");
		buf.writeBlockPos(pos == null ? net.minecraft.core.BlockPos.ZERO : pos);
		ClientPlayNetworking.send(RadioNetworking.C2S_SELECT_STATION, buf);
	}

	/** 把音量写回服务端的方块 NBT。 */
	public static void setVolume(net.minecraft.core.BlockPos pos, float volume) {
		if (!ClientPlayNetworking.canSend(RadioNetworking.C2S_SET_VOLUME)) {
			return;
		}
		FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeBlockPos(pos == null ? net.minecraft.core.BlockPos.ZERO : pos);
		buf.writeFloat(volume);
		ClientPlayNetworking.send(RadioNetworking.C2S_SET_VOLUME, buf);
	}

	/** 请求列表（打开界面或点击扫描时调用）。 */
	public static void requestStations() {
		if (!ClientPlayNetworking.canSend(RadioNetworking.C2S_REQUEST_STATIONS)) {
			return;
		}
		FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		ClientPlayNetworking.send(RadioNetworking.C2S_REQUEST_STATIONS, buf);
	}

	public static void selectStation(String stationId, net.minecraft.core.BlockPos pos) {
		if (!ClientPlayNetworking.canSend(RadioNetworking.C2S_SELECT_STATION)) {
			return;
		}
		// <b>调台噪在点台瞬间就起，不等服务端回执</b>（用户实测反馈：
		// 「声音没了噪音才起」「淡入的时机还是太晚了」）。
		//
		// 拧旋钮的那一刻，喇叭里就该是沙沙声——旧台此刻还在放，
		// 噪声与它重叠，正是真实收音机的手感（一边沙沙响着一边旧台淡出）。
		// 若等 S2C_SELECT_ACK 才触发，旧节目已经放到了最后一刻，
		// 噪声起音时节目正好消失，听感是「声音没了噪音才冒出来」。
		//
		// 坐标此时就是本方块的坐标（换台不改方块位置）；
		// 回执到达后的那次触发落在去重窗内，只会「重新撑住」不重置增益。
		// 若选择失败（服务端不回执），旧音频继续供数，
		// 「连续稳定」判定会把噪声自然淡出——不会卡在沙沙声里。
		radiosmile.client.audio.PcmSink.INSTANCE.playTuningNoise(pos);
		FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeUtf(stationId);
		buf.writeBlockPos(pos == null ? net.minecraft.core.BlockPos.ZERO : pos);
		ClientPlayNetworking.send(RadioNetworking.C2S_SELECT_STATION, buf);
	}

	public static void requestIcon(String stationId) {
		if (!ClientPlayNetworking.canSend(RadioNetworking.C2S_REQUEST_ICON)) {
			return;
		}
		FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeUtf(stationId);
		ClientPlayNetworking.send(RadioNetworking.C2S_REQUEST_ICON, buf);
	}
}