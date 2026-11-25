package radiosmile.client.audio;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.core.BlockPos;

import radiosmile.SmileSRadio;
import radiosmile.client.config.RadioClientConfig;

/**
 * 客户端音频播放管理：按方块坐标维护解码器与播放通道。
 *
 * <p>一个方块一个播放实例，因此不同收音机可以同时播放不同频道；
 * 同一频道被多个方块选中时也能各自独立调音量。
 *
 * <p><b>只解 MP3</b>。服务端在预热阶段已把本地音频（OGG/WAV）统一转码成 MP3
 * （见 {@code AudioTranscoder}），网络电台本身也基本是 MP3，所以协议上只会下发 MP3。
 *
 * <p>客户端原先还有一条 OGG 解码链路（包一层原版 {@code OggAudioStream}），现已<b>彻底删除</b>。
 * 它在这个架构里必然失败，原因三条：
 * <ol>
 *   <li><b>OGG 无法从中段解码</b>（HANDOVER 七-1）：Vorbis 的 MDCT 重叠相加依赖前序页的连续状态，
 *       而多人同步必须「跳到当前应播位置」，页序号一跳变就报 {@code Failed to read Ogg file}；</li>
 *   <li>本类的摄入队列 {@link #INGEST} 是「满了就丢最旧」的<b>断头丢弃</b>策略，
 *       OGG 丢任意一块就永久失步——用户实测的 {@code Failed to read Ogg file 21} 正来自这里；</li>
 *   <li>MP3 帧自包含，丢块后能自行重新同步。这正是网络电台一直正常的原因。</li>
 * </ol>
 *
 * <p>因此收到非 MP3 的数据时，本类<b>不做任何兜底解码</b>：记一次 WARN，
 * 并把该方块从混音器里移除（静音）。用错的解码器硬解只会得到噪声与永久失步，
 * 不如让服主一眼看到「这段音频没被转码成 MP3」这个真正的原因。
 *
 * <p>拒绝是<b>按流</b>而不是「按块」的：同一个流判定失败后标记为 rejected，
 * 后续块直接丢弃、不再判定也不再打日志（否则每秒会有 4 条 WARN 把日志刷满）。
 */
public final class ClientAudioPlayer {
	private static final Map<Long, Stream> STREAMS = new ConcurrentHashMap<>();

	/** 需要跳过的播放时长（毫秒）：服务端从头推流，客户端解码后丢弃这段 PCM。 */
	private static volatile long pendingSkipMillis;

	/**
	 * 音频摄入队列：网络线程只管入队，绝不在渲染线程喂解码器。
	 *
	 * <p>{@code feed()} 在管道写满时会阻塞；若在渲染线程调用，
	 * 一次网络抖动就能让画面卡住。这里用单线程消费，既避开阻塞又保证顺序。
	 */
	private static final java.util.concurrent.BlockingQueue<Ingest> INGEST =
			new java.util.concurrent.LinkedBlockingQueue<>(256);
	private static volatile Thread ingestThread;

	/**
	 * 惰性启动摄入线程。
	 *
	 * <p><b>不能放在 static 初始化块里</b>：类初始化由 JVM 串行化，
	 * 若初始化过程中又启动线程去访问本类或 {@link PcmSink}，两个类的初始化会互相等待。
	 * 改为首次入队时启动，避开初始化期。
	 */
	private static void ensureIngestThread() {
		if (ingestThread != null) {
			return;
		}
		synchronized (ClientAudioPlayer.class) {
			if (ingestThread != null) {
				return;
			}
			Thread t = new Thread(() -> {
				while (true) {
					try {
						Ingest item = INGEST.take();
						handleChunk(item.pos, item.stationId, item.trackIndex, item.sessionId,
								item.format, item.data);
					} catch (InterruptedException e) {
						return;
					} catch (Throwable e) {
						SmileSRadio.LOGGER.warn("音频摄入异常", e);
					}
				}
			}, "RadioSmile-AudioIngest");
			t.setDaemon(true);
			t.start();
			ingestThread = t;
		}
	}

	private record Ingest(BlockPos pos, String stationId, int trackIndex, int sessionId,
			radiosmile.radio.AudioFormat format, byte[] data) {
	}

	/**
	 * 记录握手包下发的解码元数据。
	 *
	 * <p>跳过时长由服务端在订阅确认时一次性给出，不需要每块重复传。
	 *
	 * <p><b>这里不再接收格式</b>：格式只用于在 MP3 / OGG 之间分流，
	 * 而 OGG 链路已删除，客户端只剩一条 MP3 解码路径，留着这个参数
	 * 只会诱导后人再把第二条解码路径接回去。跳过时长仍然有用
	 * （服务端从头推流，客户端解码后丢弃这段 PCM 来对齐时间轴）。
	 */
	public static void setPendingMetadata(long skipMillis) {
		pendingSkipMillis = Math.max(0L, skipMillis);
		SmileSRadio.LOGGER.debug("握手元数据: 跳过={} ms（客户端只解 MP3）", pendingSkipMillis);
	}

	/** 由网络线程调用：只入队，立即返回，不阻塞渲染。 */
	public static void enqueue(BlockPos pos, String stationId, int trackIndex, int sessionId,
			radiosmile.radio.AudioFormat format, byte[] data) {
		if (!RadioClientConfig.get().enableStreaming) {
			return;
		}
		ensureIngestThread();
		// 队列满说明消费不过来：丢弃最旧的，避免延迟无限累积。
		// 这个「断头丢弃」策略只对 MP3 成立——MP3 帧自包含，丢掉队首后
		// JLayer 会向后搜到下一个帧同步继续解；OGG 做不到这一点，
		// 丢任意一块就永久失步（这正是 'Failed to read Ogg file N' 的来源，
		// 也是客户端不再支持 OGG 的原因之一）。
		Ingest item = new Ingest(pos, stationId, trackIndex, sessionId, format, data);
		if (!INGEST.offer(item)) {
			INGEST.poll();
			INGEST.offer(item);
		}
	}

	private ClientAudioPlayer() {
	}

	/** 一个方块上的播放实例。 */
	private static final class Stream {
		Mp3StreamDecoder mp3;
		boolean decided;
		/** 已判定此流的数据客户端无法解码（非 MP3），不再尝试。 */
		boolean rejected;
		/** 当前播放的频道 id，用于识别切台。 */
		String stationId = "";
		int trackIndex = -1;
		/**
		 * 当前流会话号。网络流重连后会变——此时必须重建解码器。
		 *
		 * <p>重连后的字节与断线前<b>不连续</b>，但 MP3 帧自包含，
		 * 旧解码器会把它当成同一条流继续解，听感是声音突然「卡回」
		 * 到断线前的位置附近。会话号变化即视为「这是一条新流」。
		 */
		int sessionId = -1;
		long chunks;
		/**
		 * 最近一次收到数据块的时刻（毫秒）。
		 *
		 * <p>用于判断「流僵住」：服务端断网时不会发任何包，客户端这边的流对象却还在，
		 * 只凭「流存在」无法发现异常。用「多久没收到数据」才可靠。
		 */
		volatile long lastChunkAt = System.currentTimeMillis();

		long blockPosKey;

		/**
		 * 喂一个音频块。
		 *
		 * @return {@code false} 表示这一块客户端拒绝解码（非 MP3），调用方应静音并停掉本实例；
		 *         {@code true} 表示该块已交给解码器（或被正常丢弃）
		 */
		boolean feed(radiosmile.radio.AudioFormat format, byte[] data) {
			// 已经判定为不可解码：本实例不再重建解码器，只是继续吞掉后续块。
			// 若这里每次都重建，下一个块又会重新走一遍判定，服务端每 ~250ms 一颗块
			// 就会刷一条 WARN 并把日志埋掉——真正的原因反而看不见了。
			if (this.rejected) {
				return false;
			}
			if (!this.decided) {
				// 格式由服务端告知；只有 UNKNOWN 时才回退到内容猜测
				radiosmile.radio.AudioFormat actual = format;
				if (actual == radiosmile.radio.AudioFormat.UNKNOWN) {
					actual = guess(data);
				}
				// 只接受 MP3。UNKNOWN 是「内容里没找到 MP3 特征」而不是「不是 MP3」：
				// 首块可能正好切在某帧中间，此时 JLayer 的逐帧同步搜索反而能救回来，
				// 所以 UNKNOWN 放行交给解码器；明确是别的格式则一律拒绝，绝不尝试兜底解码。
				if (actual != radiosmile.radio.AudioFormat.MP3
						&& actual != radiosmile.radio.AudioFormat.UNKNOWN) {
					this.rejected = true;
					// rejected 是粘性的：本实例后续的块不会再走到这里，
					// 所以这条 WARN 每个流只出现一次，不会随 ~250ms 一颗块刷屏。
					SmileSRadio.LOGGER.warn(
							"收到不支持的音频格式 {} @{}，已拒绝：客户端只解 MP3"
									+ "（服务端应先把本地音频转码成 MP3 再推流）",
							actual, BlockPos.of(this.blockPosKey));
					return false;
				}
				// 格式标成 UNKNOWN、内容却明显是 OGG 的兜底拦截。
				// 这种数据喂给 JLayer 只会一直搜不到帧同步，表现是「一点声音都没有」且没有任何提示；
				// 这里靠字节魔数（不引用已被删除的格式枚举）直接点名原因，方便服主立刻定位。
				// 这正是用户实测 tiehen 无声那条链路：原始 OGG 被当成 MP3 推了过来。
				if (looksLikeOgg(data)) {
					this.rejected = true;
					SmileSRadio.LOGGER.warn(
							"收到 OGG 数据 @{}，已拒绝：客户端只解 MP3。"
									+ "请确认服务端已完成 OGG→MP3 转码预热（需要 ffmpeg）",
							BlockPos.of(this.blockPosKey));
					return false;
				}
				this.decided = true;
				SmileSRadio.LOGGER.info("音频格式: {}（只走 MP3 解码）", actual);
				this.mp3 = new Mp3StreamDecoder(this.blockPosKey, pendingSkipMillis);
				PcmSink.INSTANCE.start();
			}
			if (this.mp3 != null) {
				this.mp3.feed(data, 0, data.length);
			}
			return true;
		}

		void close() {
			if (this.mp3 != null) {
				this.mp3.close();
				this.mp3 = null;
			}
		}
	}

	/**
	 * 按内容判断音频格式。
	 *
	 * <p><b>不能只看开头几个字节</b>：服务端是按固定字节数切块推送的，
	 * 首块很可能从 MP3 帧中间开始（实测该电台首块前 31 字节是流首部填充，
	 * 之后才是第一个帧同步）。因此这里在前若干字节内扫描特征：
	 * <ul>
	 *   <li>{@code ID3} 标签 → MP3</li>
	 *   <li>{@code 0xFF 0xEx} 且后随合法的比特率/采样率索引 → MP3 帧同步</li>
	 * </ul>
	 * 判不出来时返回 UNKNOWN，由调用方按 MP3 处理——JLayer 会逐帧搜索同步字，
	 * 首块的错位字节会被丢弃，不会导致整个流失败。
	 *
	 * <p>这是<b>兜底</b>路径：正常情况下格式由服务端随数据下发（见 {@link radiosmile.radio.AudioFormat}），
	 * 因为服务端会定位到当前应播位置，首块可能从文件中段开始、
	 * 看不到文件开头的标记，单靠内容是猜不准的。
	 *
	 * <p><b>这里永远不会返回 OGG</b>：OGG 解码链路已从客户端删除，
	 * 返回一个没人能处理的值只会让调用方白白重建一次解码器。
	 * 真正的 OGG 拒绝在 {@link #looksLikeOgg(byte[])} 里做，那里只返回布尔值、
	 * 不引用任何格式枚举，因此服务端删掉 OGG 枚举值之后本类仍然能编译。
	 */
	private static radiosmile.radio.AudioFormat guess(byte[] data) {
		if (data == null || data.length < 4) {
			return radiosmile.radio.AudioFormat.UNKNOWN;
		}
		// 注意：这里刻意<b>不识别 OGG</b>。识别出来也没有解码器可用，反而要走一遍重建。
		// 真正的 OGG 拒绝在 looksLikeOgg() 里做，那里只返回布尔值、不涉及格式枚举。
		int scan = Math.min(data.length - 4, 2048);
		for (int i = 0; i < scan; i++) {
			if (data[i] == 'I' && data[i + 1] == 'D' && data[i + 2] == '3') {
				return radiosmile.radio.AudioFormat.MP3;
			}
			if ((data[i] & 0xFF) == 0xFF && (data[i + 1] & 0xE0) == 0xE0) {
				int h2 = data[i + 2] & 0xFF;
				int bitrateIndex = (h2 >> 4) & 0x0F;
				int sampleRateIndex = (h2 >> 2) & 0x03;
				if (bitrateIndex != 0 && bitrateIndex != 15 && sampleRateIndex != 3) {
					return radiosmile.radio.AudioFormat.MP3;
				}
			}
		}
		return radiosmile.radio.AudioFormat.UNKNOWN;
	}

	/**
	 * 数据里是否带 OGG 页头魔数 {@code OggS}。
	 *
	 * <p><b>为什么需要它</b>：服务端若没转码成功，推来的就是原始 OGG，
	 * 而握手包在没有格式信息时把格式标成 UNKNOWN，UNKNOWN 又是放行的（见 {@code Stream#feed}），
	 * 于是 OGG 字节被塞进 JLayer：JLayer 会一直搜不到 MP3 帧同步、一帧也解不出来，
	 * 表现是「完全没有声音，日志里也看不出原因」。
	 * 这里主动认出来并明确拒绝，比让解码器静默空转有用得多。
	 *
	 * <p><b>只用字节魔数、不引用格式枚举</b>：服务端那边 OGG 枚举值已删除，
	 * 客户端若还引用它就会编译失败；布尔判定没有这个耦合。
	 */
	private static boolean looksLikeOgg(byte[] data) {
		if (data == null || data.length < 4) {
			return false;
		}
		// 首块可能正好切在 OGG 页中间，页头不一定在最前面，所以要扫完整个块
		// （服务端单块上限 8KB，只在建流时扫一次，开销可忽略）。
		// MP3 压缩数据里偶然出现 "OggS" 这 4 个字节的概率约 1/2^32，可以不管。
		int scan = Math.min(data.length - 4, 1 << 13);
		for (int i = 0; i < scan; i++) {
			if (data[i] == 'O' && data[i + 1] == 'g' && data[i + 2] == 'g' && data[i + 3] == 'S') {
				return true;
			}
		}
		return false;
	}


	/** 接收一个音频块。 */
	private static void handleChunk(BlockPos pos, String stationId, int trackIndex, int sessionId,
			radiosmile.radio.AudioFormat format, byte[] data) {

		if (!RadioClientConfig.get().enableStreaming) {
			return;
		}
		long key = pos.asLong();
		Stream stream = STREAMS.get(key);

		// 换台 / 换曲 / 上游重连：旧解码器都必须彻底拆掉，
		// 否则旧频道（或断线前）的音频会继续播放。
		boolean stationChanged = stream != null && !stationId.equals(stream.stationId);
		boolean trackChanged = stream != null && stream.trackIndex != trackIndex;
		// <b>会话号变化 = 上游断过又重连</b>：字节流不连续，必须重建解码器。
		// 不处理的话，旧解码器会把断线前后的字节当连续流解开，
		// 听感是声音突然「卡回」到断线前的位置附近。
		boolean streamRestarted = stream != null && stream.sessionId >= 0
				&& stream.sessionId != sessionId;
		if (stationChanged || trackChanged || streamRestarted) {
			// 只记「断线重连」——那是真的异常，值得留痕。
			// 换台/换曲不记：播放列表每几分钟一首，日志里刷这种正常事件没有价值。
			if (streamRestarted) {
				SmileSRadio.LOGGER.info("网络流断线重连（会话 {} -> {}），重建解码器 @{}",
						stream.sessionId, sessionId, pos);
			}
			stream.close();
			STREAMS.remove(key);
			// <b>只登记新频道，绝不在这里清增益记录</b>。
			//
			// 服务端的发包顺序是「增益包 → 音频块」，所以走到这里时
			// <b>新台的增益包早已落地</b>；此时若清表，抹掉的正是刚收到的
			// 新台增益（本地台从此按 100% 电平播放）。
			//
			// 旧台的残留记录由<b>频道校验</b>负责失效：混音时拿记录里的
			// stationId 与这里登记的新频道比对，不匹配就返回 1.0。
			// 这样既不会误伤新台，也不需要任何"重置为 1.0"的动作。
			// 换台：登记新频道；换曲/重连：频道没变，保持原登记不动。
			if (stationChanged) {
				PcmSink.INSTANCE.setBlockStation(key, stationId);
			}
			// <b>先起调台噪、再清声卡</b>（用户反馈：换台时先有一小段数字静音）。
			// 噪声由混音线程逐块生成，flush 只丢掉声卡里已排队的旧字节，
			// 不会清掉噪声本身；顺序反了会让「旧音频断裂」与「噪声淡入」之间
			// 多露出几毫秒数字 0。传入 pos 让噪声跟随【该方块自己的音量滑块】。
			PcmSink.INSTANCE.playTuningNoise(pos);
			// 清空声卡缓冲，否则上一台已解码的音频还会播完
			PcmSink.INSTANCE.flush();
			// 切台是底噪的明确终止点（用户要求：手动切台后停）。
			// 注意 clearNetworkNoise() 会把 faultReported 一并撤掉，所以换台后
			// 若新台也连不上，仍然要靠下面重新注册的锚点 + 混音器自己的欠载判定来响。
			PcmSink.INSTANCE.clearNetworkNoise();
			PcmSink.INSTANCE.setNetworkNoiseSource(pos);
			// <b>标记「这是我们自己造成的空窗」</b>：见 PcmSink#beginTransitionSilence()。
			//
			// 上面的 flush() 把抖动缓冲清了，接下来几百毫秒没有任何音源供数——
			// 混音器的判据分不清这和丢包的区别，8 块（约 160ms）后就会把
			// 「弱网底噪」拉到满强度，节目接上后还要再沙 500ms。
			// 听感正是「一换歌就沙一阵」。
			//
			// 服务端在换曲的第一块之前也会发一个空音量包（S2C_AUDIO_SILENCE）
			// 做同样的事；这里是<b>兜底</b>：包丢了、或对面是旧服务端时，
			// 本地判定仍然能把这段空窗认出来。
			PcmSink.INSTANCE.beginTransitionSilence();
			stream = null;
		}
		if (stream == null) {
			// 收到数据本身就是"玩家想要这个台"的强证据（订阅请求可能来自别的路径）。
			noteStreamRequested(pos);
			// <b>登记本方块此刻播放的频道</b>：推流增益的归属校验靠它。
			//
			// <b>这里绝不动增益表</b>。早期这里调 {@code setStreamGain(key, 1.0F)}
			// 想"清掉残留的推流增益"，但服务端下发增益与推送音频是<b>先后两个包</b>：
			// 增益包先到（写进 0.25），紧接着第一块音频到达时走的就是这个分支，
			// 于是刚写进去的 0.25 被立刻抹成 1.0；而服务端记着「0.25 已发过」
			// 不会再发，本地台整场按 100% 电平播放，outputGain 完全失效。
			//
			// 换台时不清增益记录（新台的增益包可能已先到），只登记新频道；
			// 首次建流时登记的就是当前频道。两种情况增益表都保持原样。
			PcmSink.INSTANCE.setBlockStation(key, stationId);
			stream = new Stream();
			stream.trackIndex = trackIndex;
			stream.sessionId = sessionId;
			stream.stationId = stationId;
			stream.blockPosKey = key;
			STREAMS.put(key, stream);
			// 有流 = 播放意图存在。断网若导致取不到数据，底噪才该响；
			// 空选/关机时这个标志会被清掉，那时安静才是对的。
			PcmSink.INSTANCE.setPlaybackWanted(true);
			// 注册方块世界坐标，混音器据此做距离衰减
			PcmSink.INSTANCE.setSourcePosition(key, pos);
			PcmSink.INSTANCE.start();
		}
		// <b>心跳空包：只刷新"服务端还活着"，绝不能喂给解码器。</b>
		//
		// 服务端在换曲/轮转/上游短暂卡顿时会发空载荷的音频块（见
		// AudioStreamManager.sendHeartbeat）。它存在的原因：客户端判断
		// "流僵住/该不该响底噪"看的是 {@code lastChunkAt}，若服务端一言不发，
		// 换首歌就会被误判成超时 → 点 NOSIGNAL + 响底噪。
		//
		// <p>这里<b>必须早于 feed() 返回</b>：空数组喂给 JLayer 的管道没有意义，
		// 而且 <b>trackIndex 变化已在上面处理过</b>（重建解码器、flush 声卡），
		// 所以心跳仍能正确同步"换曲"这件事，只是不产生音频。
		//
		// <b>不要在这里 clearClientFaultOnResume</b>：空包不代表"数据恢复"，
		// 它只代表"服务端还活着"。真正恢复要等真实音频块到达（下面那条路径）。
		stream.chunks++;
		stream.lastChunkAt = System.currentTimeMillis();
		if (data == null || data.length == 0) {
			// 心跳：刷新时间戳后立即返回，不触碰解码器
			return;
		}
		// <b>不在这里清底噪</b>。
		//
		// 早期把「收到一块数据」当成「网络已恢复」就清掉底噪，
		// 但卡顿本来就是【一阵一阵】的：音频块约 250ms 一个，
		// 卡一下 -> 底噪起来 -> 收到一块 -> 被清掉 -> 又卡 -> 又起来……
		// 结果底噪永远起不来，听感是时有时无甚至完全消失。
		//
		// 正确的「恢复」判据是【抖动缓冲连续健康】，
		// 由 PcmSink.updateNetworkNoise() 内部的 HEALTHY_RESTORE_BLOCKS 负责——
		// 缓冲真的恢复正常后它自己会停，不需要这里抢着清。
		//
		// 注意区分两件事：
		//   - <b>音频层</b>「底噪要不要停」 → 由混音器按缓冲健康度自己决定（上面那条）；
		//   - <b>显示层</b>「液晶屏 ERR 要不要灭」 → 数据真的回来了就该灭。
		// 后者不能也交给混音器：它只管听感，完全不知道有块屏幕。
		// 而「数据重新到达」正是故障结束最直接的证据（本地 TIMEOUT 的判据就是"多久没数据"）。
		clearClientFaultOnResume(pos);
		// <b>故障噪音的"结束"跳变</b>：断流期间 TIMEOUT 轻量路径注册过的方块，
		// 收到第一块真实数据 = 服务端重连成功、链路恢复——
		// 此时让混音器按落音速率淡出底噪（不是硬切），并撤销故障态。
		// 只在跳变处调一次：恢复初期缓冲还没攒厚，弱底噪档要继续响，
		// 每块都清会把它杀掉（听感变成断流一恢复就立刻完全无声）。
		clearFaultNoiseOnResume(pos);
		if (!stream.feed(format, data)) {
			// 数据不可解码。这里刻意<b>不调用 stop(pos)</b>，而是保留这个（已标记 rejected 的）流。
			//
			// 两个约束必须同时满足：
			//   1. 混音器里不能留着上一次的缓冲——PcmSink 是所有方块共用的单一声卡通道，
			//      残留数据会一直重放（听感是卡住的一小段或持续噪音）→ 必须 removeSource；
			//   2. 日志不能刷屏——服务端每 ~250ms 一颗块，若把流删掉，下一颗块又会重建流、
			//      重新判定、重新 WARN，等于每秒 4 条。保留 rejected 流则判定只做一次。
			//
			// 保留的流同时让 isStreaming() 继续为 true，AutoSubscriber 不会反复重订阅
			// （重订阅也只会再拿到同样的坏数据）。真正能恢复的做法是服主修好转码后重连。
			PcmSink.INSTANCE.removeSource(key);
		}
	}

	/** 设置某个方块的音量。 */
	public static void setVolume(BlockPos pos, float volume) {
		// 目前所有方块共用一个声卡通道；保留接口以便将来按方块分通道
		PcmSink.INSTANCE.setBlockVolume(volume);
	}

	/**
	 * 让某个方块的音频<b>淡出后停止</b>，用于换台。
	 *
	 * <p>与 {@link #stop(BlockPos)} 的区别：stop 立即丢弃数据（硬切），
	 * 听感是「咔」地一下；本方法让现有数据继续播完但音量线性降到 0，
	 * 与随后淡入的调台噪重叠，交界处没有断点。
	 *
	 * <p>解码器仍然立即关闭（不再产生新音频），只是混音器里已有的
	 * 那一小块数据会被淡出播放完。
	 *
	 * @param pos    方块坐标
	 * @param millis 淡出时长（毫秒）
	 */
	public static void fadeOutAndStop(BlockPos pos, int millis) {
		Stream stream = STREAMS.remove(pos.asLong());
		if (stream == null) {
			return;
		}
		// 先关闭解码器：停止产生新 PCM
		stream.close();
		// 再让混音器把已经排队的音频淡出播完，之后自动移除
		PcmSink.INSTANCE.fadeOutSource(pos.asLong(), millis);
	}

	/**
	 * 因<b>故障</b>停止某个方块的播放，但<b>保留播放意图</b>。
	 *
	 * <h2>与 {@link #stop(BlockPos)} 的区别</h2>
	 * {@code stop()} 表达的是「用户不要这个台了」——空选、拆方块、走远。
	 * 此时播放意图随之撤销，底噪也该跟着安静。
	 *
	 * <p>而服务端报故障（频道缺音频、格式不支持、超时）时，玩家<b>仍然想听</b>，
	 * 只是暂时失败。这种场景必须保留播放意图，否则：
	 * <ul>
	 *   <li>底噪不会响（因为看起来像「用户主动关了」）</li>
	 *   <li>自动重连也失去依据</li>
	 * </ul>
	 * 早期把两类场景都走 {@code stop()}，正是「故障时不响底噪」的根因。
	 *
	 * @param pos 方块坐标
	 */
	public static void stopForFailure(BlockPos pos) {
		long key = pos.asLong();
		// <b>意图的判据是「玩家还在等这个台的数据」，不是「有没有在播」</b>。
		//
		// 早期写成「若还有其它收音机在播才补设 true」，假设"意图已经被 handleChunk 设过了"。
		// 但服务端完全可能在<b>第一个音频块到达之前</b>就报失败
		// （NO_AUDIO / UNSUPPORTED_FORMAT：订阅阶段就拿不到音源）——
		// 那种情况下 STREAMS 是空的、handleChunk 从没跑过，意图停在 false，
		// 于是即使混音器知道"链路坏了"，也会把它当成"用户主动空选"而彻底静音。
		//
		// 反过来，也<b>不能无条件 setPlaybackWanted(true)</b>：用户已经空选关台之后，
		// 一个迟到的状态包会把意图重新点亮，于是"已经关掉的收音机又开始沙沙响"。
		// 这个反向缺陷是在模拟里被 S5 场景抓到的（用户清台 + 迟到的 NO_AUDIO）。
		//
		// 所以用 {@link #REQUESTED} 记录"玩家点过这个台的确认、还没撤销"，
		// 它由订阅请求写入、由 stop() 撤销 —— 与 STREAMS（真实在收流）解耦，
		// 这样"还没收到第一块就失败"与"用户已经关台"两种情况都能正确区分。
		boolean stillRequested = REQUESTED.contains(key);
		stopInternal(pos, true);
		if (stillRequested) {
			PcmSink.INSTANCE.setPlaybackWanted(true);
			PcmSink.INSTANCE.markFaultReported();
			// 告诉混音器这条故障属于哪台机器（音量/距离/声像）。
			PcmSink.INSTANCE.setNetworkNoiseSource(pos);
		}
	}

	/**
	 * 处理「方块上记着的频道根本不存在」这一类故障。
	 *
	 * <h2>为什么单独一个入口</h2>
	 * 它和 {@link #stopForFailure(BlockPos)} 的区别在于<b>数据永远不会来</b>：
	 * <ul>
	 *   <li>{@code stopForFailure}：这是一次"暂时失败"，服务端可能重连、玩家可能走进范围，
	 *       所以保留播放意图让底噪响，也留着恢复的可能；</li>
	 *   <li>本方法：频道 id 在配置里已经不存在了（被删/改名/配置失效），
	 *       <b>再怎么等也不会有数据</b>。但用户要求这种方块"持续噪音 + NOSIGNAL"，
	 *       所以同样要保留意图、注册噪音归属，只是不必再给它"恢复"的期待。</li>
	 * </ul>
	 *
	 * <p>之所以不直接复用 {@code stopForFailure}：那个方法依赖 {@link #REQUESTED}
	 * （玩家点过确认）。而未知频道往往发生在"玩家进服时方块已经坏了"的场景，
	 * 本机可能从没发出过订阅请求，{@code REQUESTED} 是空的——
	 * 复用会导致底噪不响，正好违反用户要求。
	 *
	 * <p><b>线程</b>：主线程（AutoSubscriber 的 tick 回调）。
	 */
	public static void markUnknownStationFailure(BlockPos pos) {
		if (pos == null) {
			return;
		}
		PcmSink.INSTANCE.setPlaybackWanted(true);
		PcmSink.INSTANCE.markFaultReported();
		PcmSink.INSTANCE.setNetworkNoiseSource(pos);
	}

	/**
	 * 玩家「还想要这些台的数据」的集合（方块坐标）。
	 *
	 * <p>与 {@link #STREAMS} 的区别：
	 * <ul>
	 *   <li>{@code STREAMS} = <b>真的在收流</b>（收到过音频块）；</li>
	 *   <li>{@code REQUESTED} = <b>玩家点过确认、还没撤销</b>（订阅请求发出即算）。</li>
	 * </ul>
	 *
	 * <p>必须分成两个东西，否则两种相反的误判无法同时避免：
	 * 用 {@code STREAMS} 当意图 → "还没收到第一块就失败"被当成用户关台；
	 * 无条件当意图 → "用户已关台"被一个迟到状态包重新点亮。
	 *
	 * <p>写入点：{@link #noteStreamRequested}（订阅时，主线程）、故障入口；
	 * 撤销点：{@link #stop}（用户关台）、{@link #stopAll}。
	 */
	private static final java.util.Set<Long> REQUESTED =
			java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** 记录「玩家想要这个台」——在发出 C2S_AUDIO_SUBSCRIBE 时调用（主线程）。 */
	public static void noteStreamRequested(BlockPos pos) {
		if (pos != null) {
			REQUESTED.add(pos.asLong());
		}
	}

	/**
	 * 玩家是否仍然想要这个台的音频。
	 *
	 * <p>供 {@code AutoSubscriber} 在本地判定 TIMEOUT 时先确认"玩家确实还在等"：
	 * 用户刚点了空选、但方块 NBT 还没同步回来的一两个 tick 里，
	 * 扫描可能仍把它算成"有频道但没在收流"从而重订阅；那时不该顺手把故障提示重新点亮。
	 */
	public static boolean wantsStream(BlockPos pos) {
		return pos != null && REQUESTED.contains(pos.asLong());
	}

	/** 撤销「玩家想要这个台」——用户关台 / 主动退订时调用（主线程）。 */
	public static void clearStreamRequested(BlockPos pos) {
		if (pos != null) {
			REQUESTED.remove(pos.asLong());
		}
		if (REQUESTED.isEmpty()) {
			PcmSink.INSTANCE.setPlaybackWanted(false);
			PcmSink.INSTANCE.setNetworkNoiseSource(null);
		}
	}

	/**
	 * 停止某个方块的播放。
	 *
	 * <p><b>必须立即静音</b>：{@link PcmSink} 是所有方块共用的声卡通道，
	 * 队列里可能还积压着上次收到的音频。只从 STREAMS 移除映射是不够的——
	 * 已解码的 PCM 会继续播完，表现为「取消选择后声音还在响」。
	 * 因此这里清空任务队列并停掉声卡输出。
	 */
	public static void stop(BlockPos pos) {
		// <b>用户主动关台</b>：把"还想要这个台"的请求撤销。
		// 必须在 stopInternal() 之前撤销，否则后续到达的状态包仍会看到它。
		REQUESTED.remove(pos.asLong());
		stopInternal(pos, false);
		// 播放意图随之撤销，且此后即使 sources 为空（正常空选状态）也不该再响底噪。
		if (REQUESTED.isEmpty()) {
			PcmSink.INSTANCE.setPlaybackWanted(false);
			PcmSink.INSTANCE.setNetworkNoiseSource(null);
		}
		// 用户已经不要这个台了，液晶屏不该继续显示它的故障码。
		// 调用方（RadioScreen.onConfirm / AutoSubscriber）都在主线程，可以直接写方块实体。
		clearClientFault(pos);
	}

	/**
	 * 停止某个方块播放的<b>公共实现</b>（不管意图是否保留）。
	 *
	 * <p>只做「拆除播放」这件事，不碰播放意图——意图由两个调用方各自决定：
	 * 用户关台走 {@link #stop(BlockPos)}（撤销意图），
	 * 服务端报故障走 {@link #stopForFailure(BlockPos)}（保留意图）。
	 */
	private static void stopInternal(BlockPos pos) {
		stopInternal(pos, false);
	}

	/**
	 * 停止某台收音机播放的<b>公共实现</b>。
	 *
	 * @param forFailure {@code true} = 因故障停止（玩家还想听）；{@code false} = 用户关台。
	 *                   这个参数存在的唯一目的，是把「用户不要了」与「链路坏了」
	 *                   这两个语义<b>一路传到混音器</b>，而不是在 {@code PcmSink.stop()}
	 *                   那一层又被合并回同一个函数（HANDOVER 第八节 D 没做完的下半截）。
	 */
	private static void stopInternal(BlockPos pos, boolean forFailure) {
		// 网络电台拉流任务一并停掉（客户端直连上游：流的生命周期跟播放实例走）。
		stopStreamFeed(pos);
		Stream stream = STREAMS.remove(pos.asLong());
		if (stream != null) {
			stream.close();
		}
		// 只移除本音源的数据，不能把别的收音机一起静掉
		PcmSink.INSTANCE.removeSource(pos.asLong());
		PcmSink.INSTANCE.clearVolumeFor(pos.asLong());
		// <b>不在这里清增益记录</b>：停流可能发生在新频道的增益包<b>已经到达</b>
		// 之后（换台 = ACK 淡出 + 重订阅，服务端的增益包可能先于新音频块落地），
		// 这时清掉会把新台的 0.25 一起抹了。
		//
		// 正确做法是只注销「这个方块当前播放的频道」，让增益的<b>频道校验</b>去失效
		// 旧台记录：下次建流登记新频道后，旧台的增益因归属不匹配自动返回 1.0。
		// 记录本身留在表里无害（方块没有音源时 sourceGain 不会被调用），
		// 且会被后续同名频道的增益包覆盖。
		PcmSink.INSTANCE.setBlockStation(pos.asLong(), null);
		// 立刻停止，不留任何余音：
		// 用户明确要求「空选列表后按下就立刻停止播放」，
		// 那点断电氛围远不如「按了没停」来得困扰。
		if (STREAMS.isEmpty()) {
			if (forFailure) {
				// 故障静音：同样是静音，但<b>保留</b>故障提示音所需的锚点与增益。
				PcmSink.INSTANCE.silenceForFailure();
			} else {
				PcmSink.INSTANCE.stop();
			}
		} else {
			PcmSink.INSTANCE.silenceIfNoSources();
		}
	}

	/**
	 * 当前是否有收音机在出声。
	 *
	 * <p>供 {@link BgmSuppressor} 判断是否需要压制原版 BGM。
	 *
	 * <p><b>必须排除已拒绝的流</b>：被拒绝的流为了「只判定一次、不刷日志」会留在
	 * {@link #STREAMS} 里继续吞块（见 {@link #handleChunk}）。若这里只看
	 * {@code !STREAMS.isEmpty()}，一个放不出声的坏频道会让 BGM 被永久压制——
	 * 现象是「收音机没声音、BGM 也没有」，非常难查。
	 */
	public static boolean isPlaying() {
		for (Stream stream : STREAMS.values()) {
			if (!stream.rejected) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 某个方块当前是否真正在接收音频流。
	 *
	 * <p>用于自动订阅判断「是否已中断」：服务端可能因走远/换台主动停推，
	 * 但客户端记录还停留在「已订阅」，需要据此重新发起订阅。
	 */
	public static boolean isStreaming(BlockPos pos) {
		return STREAMS.containsKey(pos.asLong());
	}

	/**
	 * 丢掉某个方块的解码器，等下一块数据用<b>全新解码器</b>重建。
	 *
	 * <h2>什么时候需要</h2>
	 * 数据块一直在到（{@link #isStreaming} 为真），但解码器<b>一帧也没解出来</b>
	 * （{@link PcmSink#deliveredFramesFor} 为 0）。最典型的场景是冷启动订阅时
	 * 起点恰好落在坏数据/错位字节里，JLayer 在里面搜不到帧同步就一直空转——
	 * 块源源不断，声音一点没有，日志也没有任何异常，只能靠外部踢一脚。
	 *
	 * <p>重建很便宜：下一次 {@code handleChunk} 发现流不存在会新建实例，
	 * 新解码器从那一块的第一帧重新搜同步。服务端仍在推同一位置的数据，
	 * 所以不会跳曲、不会重复。
	 *
	 * <p><b>不动混音器</b>：不 flush、不 removeSource。若新解码器仍解不出东西，
	 * 混音器那边本来也没有可播的东西，清不清都一样；
	 * 而如果只是这一块错位，清缓冲反而会多一次不必要的断音。
	 *
	 * @param pos 方块坐标
	 */
	public static void restartStream(BlockPos pos) {
		if (pos == null) {
			return;
		}
		Stream stream = STREAMS.remove(pos.asLong());
		if (stream != null) {
			try {
				stream.close();
			} catch (Throwable ignored) {
				// 单个解码器关闭失败不应影响重建
			}
		}
	}

	/**
	 * 某个方块的流是否<b>僵住了</b>：流对象还在，但超过 {@link #STALL_TIMEOUT_MILLIS} 没收到任何数据。
	 *
	 * <h2>它现在的唯一用途是<b>显示故障</b>，不再触发重订阅</h2>
	 * 这是本轮架构调整后的重要变化。职责划分：
	 * <ul>
 *   <li>服务端负责把上游管子接回去（StreamAudioSource 自动重连，指数退避）；
 *       断流期间服务端停发心跳，客户端据此判超时并显示故障；</li>
 *   <li>客户端只要还没和服务器彻底断开，就<b>不重订阅、不重连</b>，
 *       只把表现做成「<b>底噪变大 + 屏幕 NOSIGNAL</b>」，
 *       数据回来后由 handleChunk 自动清故障、按会话号重建解码器；</li>
	 * </ul>
	 *
	 * <p>因此 {@code AutoSubscriber} 只用它来<b>点亮故障显示</b>，
	 * 「要不要重新订阅」看的是另一件事：<b>本地有没有这条订阅</b>（{@link #isStreaming}）。
	 *
	 * <p>早期把 stale 也当成"该重订阅"，于是断网期间客户端每 6 秒重发一次订阅、
	 * 服务端反复重建网络流——既刷日志，又让本来就抖的音频抖得更厉害。
	 */
	public static boolean isStalled(BlockPos pos) {
		Stream stream = STREAMS.get(pos.asLong());
		if (stream == null) {
			return false;
		}
		// 已判定为不可解码的流不算 "僵住"：重订阅也只会拿到同样的坏数据，
		// 反复重试反而会刷日志。
		if (stream.rejected) {
			return false;
		}
		return System.currentTimeMillis() - stream.lastChunkAt > STALL_TIMEOUT_MILLIS;
	}

	/**
	 * 多久没收到数据就认为流僵住了（毫秒）。
	 *
	 * <p>取 <b>4 秒</b>。这个值必须大于「网络流建连 + 首块缓冲」的总耗时：
	 * <ul>
	 *   <li>上游 HTTP 握手实测约 1 秒（异步建连）</li>
	 *   <li>客户端抖动缓冲起播门槛约 0.3 秒</li>
	 *   <li>再加配速与网络抖动余量</li>
	 * </ul>
	 *
	 * <p>早期取 2.5 秒——比建连还短，于是每次重连刚发起就被判超时、
	 * 又触发重订阅，形成无限重建连的死循环（实测每秒都在建连）。
	 * 提高后建连有机会走完；用户定为 4 秒——留足建连余量的同时，
	 * 断流后约 4 秒就能点亮 NOSIGNAL，不用干等更久。
	 */
	private static final long STALL_TIMEOUT_MILLIS = 4000L;

	/**
	 * 彻底停止所有播放。
	 *
	 * <p>顺序很重要：先关解码器（停止产生新 PCM），再清声卡（丢弃已排队的 PCM）。
	 * 若反过来，解码线程可能在声卡被关后又写入一帧。
	 */
	public static void stopAll() {
		// 网络电台拉流任务全部停止（客户端直连上游，退出世界时关掉所有连接）
		for (StreamFeed feed : FEEDS.values()) {
			feed.stop();
		}
		FEEDS.clear();
		for (Stream stream : STREAMS.values()) {
			try {
				stream.close();
			} catch (Throwable ignored) {
				// 单个流关闭失败不应影响其它流
			}
		}
		STREAMS.clear();
		REQUESTED.clear();
		PcmSink.INSTANCE.setPlaybackWanted(false);
		PcmSink.INSTANCE.setNetworkNoiseSource(null);
		// 清空待处理队列并关闭声卡通道
		INGEST.clear();
		PcmSink.INSTANCE.stop();
		PcmSink.INSTANCE.resetSources();
	}

	// ------------------------------------------------------------ 故障显示

	/**
	 * 数据恢复后清除液晶屏上的 ERR。
	 *
	 * <h2>线程纪律（<b>关键</b>）</h2>
	 * 本方法由 {@link #handleChunk} 调用，而 {@code handleChunk} 跑在
	 * <b>RadioSmile-AudioIngest</b> 线程上。清显示要读 {@code client.level} 找方块实体
	 * （{@code ClientRadioNetwork.markLocalFault} 的第一件事就是 {@code client.level.isLoaded(pos)}），
	 * <b>绝不能在 ingest 线程做</b>——区块随时可能被卸载，
	 * 跨线程读 {@code ClientLevel} 是明确的数据竞争。
	 *
	 * <p>因此这里统一 hop 到客户端主线程：{@code Minecraft.getInstance().execute(...)}。
	 * 这也是本项目唯一允许从音频线程触碰世界的通道。
	 *
	 * <h2>为什么"每块都调"没有性能问题</h2>
	 * {@code RadioBlockEntity.setClientFaultStatus} 在值相同时直接 return
	 * （见 RadioBlockEntity.java，客户端专用字段、不写盘、不同步），
	 * 所以稳态下这是一个 volatile 读 + 比较，开销可忽略。
	 * 但 {@code execute()} 本身会往主线程队列里塞一个任务，所以先用
	 * {@link #faultShown} 挡一道，只在"屏幕上确实还亮着 ERR"时才真的排队。
	 */
	private static void clearClientFaultOnResume(BlockPos pos) {
		if (!faultShown) {
			// 本地根本没点过灯：不用排队，绝大多数情况走这条。
			return;
		}
		net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
		if (client == null) {
			return;
		}
		BlockPos snapshot = pos.immutable();
		// <b>代际检查</b>：这个任务排进主线程队列后，可能在它执行前又来了一个新的故障状态包。
		// 若直接清，会把刚点亮的新故障抹掉（"故障一闪就灭"）。
		// 所以记下排队时的代际号，执行时若已经变了就放弃这次清除。
		long generation = FAULT_GENERATION.get();
		client.execute(() -> {
			if (generation != FAULT_GENERATION.get()) {
				// 期间有更新的故障状态：清除作废，保留新状态。
				return;
			}
			// <b>先记录"屏幕上已无故障"这个事实</b>。
			//
			// 这一步漏掉过一次，代价是<b>每来一个音频块就重复清一次、重复打一条日志</b>：
			// 因为 {@code faultShown} 是上面那道"要不要排队"的唯一门，
			// 不清它就永远是 true，于是每块都 execute 一次，
			// 日志里出现「音频已恢复」每 0.25 秒一条（实测 119 条/分钟）。
			// 同时 {@code markLocalFault} 内部又会调 {@code noteFaultShown}，
			// 两者原本应当是同一件事，容易在这个函数里被写成"只走一边"。
			faultShown = false;
			radiosmile.client.radio.ClientRadioNetwork.markLocalFault(
					snapshot, radiosmile.net.AudioStatus.OK);
			// <b>本次故障的「结束」日志</b>：只在死亡→存活的那一次跳变打印。
			// 恢复的日志必须在这里而不是每秒的扫描里，否则又会变成刷屏。
			SmileSRadio.LOGGER.info("音频已恢复 @{}", snapshot);
			// 允许该方块下次故障时再报一次日志
			resetFaultLog(snapshot);
		});
	}

	/**
	 * 故障显示代际号：每次 {@link #noteFaultShown(boolean)} 被调用（= 有状态包更新）就自增。
	 *
	 * <p>用来让"排队中的清除任务"在真正执行时判断自己是否已经过期。
	 * 没有它，"数据恢复 → 排队清除 → 又收到一个故障状态包 → 清除任务执行"
	 * 会把刚点亮的新故障抹掉。
	 */
	private static final java.util.concurrent.atomic.AtomicLong FAULT_GENERATION =
			new java.util.concurrent.atomic.AtomicLong();

	/**
	 * 处于「断流故障噪音」状态的方块集合。
	 *
	 * <p>由 TIMEOUT 轻量故障路径（{@code ClientRadioNetwork}）通过
	 * {@link #noteFaultNoise} 登记；服务端重连成功、真实音频块重新到达时，
	 * {@link #clearFaultNoiseOnResume} 检出跳变并让底噪淡出。
	 * 只登记、不阻止任何其它行为——它唯一的作用是标记"噪音该收尾了"这一次跳变。
	 */
	private static final java.util.Set<Long> FAULT_NOISE =
			java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** 登记：该方块正处在断流故障噪音状态（见 {@link #FAULT_NOISE}）。 */
	public static void noteFaultNoise(BlockPos pos) {
		if (pos != null) {
			FAULT_NOISE.add(pos.asLong());
		}
	}

	/** 收到真实数据：若该方块曾处于故障噪音状态，让底噪按落音速率淡出（跳变只触发一次）。 */
	private static void clearFaultNoiseOnResume(BlockPos pos) {
		if (FAULT_NOISE.remove(pos.asLong())) {
			PcmSink.INSTANCE.onLinkRecovered();
		}
	}

	// ------------------------------------------------- 客户端直连网络电台拉流

	/**
	 * 网络电台拉流任务表（每方块一个）。
	 *
	 * <p><b>架构约定（用户明确）</b>：网络电台的音频流由<b>客户端自己从上游拉取</b>——
	 * 服务端只维护「是否在播放」的订阅状态机，音频字节完全不经服务端。
	 * 客户端收到订阅 OK 后在这里起拉流线程：连上游 → 剥 ICY 元数据 →
	 * 解码 → 混音，断流后的退避重连也由拉流线程自己完成（复用
	 * {@link radiosmile.radio.StreamAudioSource}，它不依赖任何服务端类）。
	 *
	 * <p>故障表现与用户设定的链路一致：
	 * 上游断 → 客户端缓冲变薄、底噪抬高 → 4 秒收不到上游数据 →
	 * NOSIGNAL + 闪灯 + 底噪持续 → 退避重连 → 数据回来 → 底噪随缓冲变厚渐弱。
	 */
	private static final Map<Long, StreamFeed> FEEDS = new ConcurrentHashMap<>();

	/** 订阅 OK 后启动（或替换）该方块的网络电台拉流任务。 */
	public static void startStreamFeed(BlockPos pos, String stationId, String url) {
		if (!RadioClientConfig.get().enableStreaming) {
			return;
		}
		if (pos == null || stationId == null || url == null || url.isBlank()) {
			return;
		}
		long key = pos.asLong();
		StreamFeed existing = FEEDS.get(key);
		if (existing != null) {
			if (existing.url.equals(url) && existing.thread.isAlive()) {
				return;
			}
			existing.stop();
		}
		StreamFeed feed = new StreamFeed(pos, stationId, url);
		FEEDS.put(key, feed);
		feed.thread.start();
		SmileSRadio.LOGGER.info("客户端开始拉取网络电台流：{} <- {}", stationId, url);
	}

	/** 停止该方块的网络电台拉流任务（换台/关台/走远/退出时调用）。 */
	public static void stopStreamFeed(BlockPos pos) {
		if (pos == null) {
			return;
		}
		StreamFeed feed = FEEDS.remove(pos.asLong());
		if (feed != null) {
			feed.stop();
		}
	}

	/**
	 * 一个方块的网络电台拉流线程。
	 *
	 * <p>循环体只做三件事：读上游字节（{@link StreamAudioSource#read} 内部已含
	 * 退避重连与 ICY 剥离）→ 更新流心跳（供 4 秒超时判定）→ 喂解码器。
	 * 会话号变化（上游重连成功）时重建解码器，避免断线前后的字节被当成连续流。
	 */
	private static final class StreamFeed implements Runnable {
		final BlockPos pos;
		final String stationId;
		final String url;
		final Thread thread;
		volatile boolean stopped;

		StreamFeed(BlockPos pos, String stationId, String url) {
			this.pos = pos.immutable();
			this.stationId = stationId;
			this.url = url;
			this.thread = new Thread(this, "RadioSmile-ClientStream");
			this.thread.setDaemon(true);
		}

		void stop() {
			this.stopped = true;
			this.thread.interrupt();
		}

		@Override
		public void run() {
			radiosmile.radio.StreamAudioSource source =
					new radiosmile.radio.StreamAudioSource(this.url);
			try {
				while (!this.stopped) {
					byte[] data = source.read(16384);
					if (data == null) {
						// 订阅被主动关闭（close 后 read 返回 null）
						break;
					}
					if (data.length == 0) {
						// 建连中 / 断线重连中：拉流线程自己等，不动解码器。
						// 客户端缓冲此时会被逐渐吃薄——那正是"底噪抬高"的信号来源。
						Thread.sleep(20L);
						continue;
					}
					handleUpstreamChunk(this.pos, this.stationId, source.sessionId(), data);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} catch (Throwable e) {
				if (!this.stopped) {
					SmileSRadio.LOGGER.warn("客户端拉流异常 @{}", this.pos, e);
				}
			} finally {
				source.close();
			}
		}
	}

	/**
	 * 处理一块从上游直接读到的音频字节（在拉流线程上执行）。
	 *
	 * <p>与 {@link #handleChunk} 的换台/重建逻辑对齐，但数据来源是本线程自己的
	 * 上游连接而不是 S2C_AUDIO_CHUNK——没有服务端心跳/空包，也不需要配速。
	 */
	private static void handleUpstreamChunk(BlockPos pos, String stationId,
			int sessionId, byte[] data) {
		long key = pos.asLong();
		Stream stream = STREAMS.get(key);
		// 会话号变化 = 上游断过又重连：字节流不连续，必须重建解码器。
		// 重连空窗用过渡静音窗声明（不响调台噪——重连不是换台）。
		if (stream != null && stream.sessionId >= 0 && stream.sessionId != sessionId) {
			SmileSRadio.LOGGER.info("网络流重连（会话 {} -> {}），重建解码器 @{}",
					stream.sessionId, sessionId, pos);
			stream.close();
			STREAMS.remove(key);
			PcmSink.INSTANCE.flush();
			// <b>重连恢复：只淡出，不开静音窗、不硬切</b>。
			//
			// 会话号变化意味着上游断过、现在接回来了——故障正在「结束」。
			// 早期这里还调 beginTransitionSilence()：它内部会 networkNoise.reset()
			// （瞬时归零）并声明 900ms 数字静音窗——结果淡出从未被听见，
			// 用户实测「噪声结束了才发声」：噪音硬切 → 干等静音窗 → 才有声音。
			// 现在 onLinkRecovered() 让底噪按落音速率自然淡出；flush 后的重攒窗口
			// 由饥饿宽限冻结电平兜底（噪声不跌坑），新音频攒够起播缓冲（约 300ms）
			// 就直接接上，与噪声淡出<b>重叠</b>，而不是排在它后面。
			// 换台路径（handleChunk）才用 clearNetworkNoise() + 静音窗——
			// 那里上一台必须立刻消失，语义不同。
			PcmSink.INSTANCE.onLinkRecovered();
			PcmSink.INSTANCE.setNetworkNoiseSource(pos);
			stream = null;
		}
		// <b>登记频道 + 作废旧增益</b>（不再每块重置为 1.0）。
		//
		// 早期这里是「每块都 setStreamGain(key, 1.0F)」，目的是压制一种竞态：
		// 泵线程的 server.execute 闭包可能在换台后才执行，旧台的 0.025 增益包
		// 会排在新台的 1.0 之后到达，把网络台的增益表项写回 0.025。
		//
		// 但那等于拿「满增益」当清理信号。现在协议里增益包带了 stationId，
		// 混音时会校验频道归属，迟到的旧台增益<b>自动失效</b>，
		// 不需要也不应该再由客户端改写增益值——否则本地台的 outputGain
		// 同样会被这块逻辑抹掉。
		PcmSink.INSTANCE.setBlockStation(key, stationId);
		if (stream == null) {
			// 收到数据本身就是"玩家想要这个台"的强证据
			noteStreamRequested(pos);
			// 网络直播流从连上那一刻起播，没有"跳过历史"的概念；
			// 清掉本地电台握手可能残留的 skip 时长，避免开头被误丢。
			setPendingMetadata(0L);
			stream = new Stream();
			stream.stationId = stationId;
			stream.sessionId = sessionId;
			stream.blockPosKey = key;
			STREAMS.put(key, stream);
			PcmSink.INSTANCE.setPlaybackWanted(true);
			PcmSink.INSTANCE.setSourcePosition(key, pos);
			PcmSink.INSTANCE.start();
		}
		// 刷新流心跳：isStalled 的 4 秒超时判据统一看 lastChunkAt，
		// 本地电台由 S2C_AUDIO_CHUNK 刷新，网络电台由这里（真实上游数据）刷新。
		// 断流期间 read 一直返回空数组 → 这里不再执行 → 4 秒后判超时。
		stream.chunks++;
		stream.lastChunkAt = System.currentTimeMillis();
		clearFaultNoiseOnResume(pos);
		// <b>真实数据回来了：清掉屏幕上的 NOSIGNAL 与红灯</b>（切回正常 rate 显示）。
		//
		// 这一步曾漏掉：clearClientFaultOnResume 早期只挂在 handleChunk
		// （服务端推流路径）上，而网络电台改为客户端直连后数据走本方法——
		// 于是恢复后 clientFaultStatus 永远停在 TIMEOUT：声音有了，
		// 灯还在闪、屏幕还是 NOSIGNAL（用户实测）。
		// clearClientFaultOnResume 内部自带 faultShown 门（没亮灯直接 return，
		// 不会每块都往主线程塞任务）、主线程 hop 与代际保护，从拉流线程调用是安全的。
		clearClientFaultOnResume(pos);
		if (!stream.feed(radiosmile.radio.AudioFormat.MP3, data)) {
			// 数据不可解码：保留 rejected 流（防止重建风暴），只把混音器里的残留清掉
			PcmSink.INSTANCE.removeSource(key);
		}
	}


	/**
	 * 在主线程清除某个方块的故障显示。
	 *
	 * <p>只在<b>已确认是主线程</b>的调用点使用：{@link #stop(BlockPos)}
	 * （GUI 线程与 END_CLIENT_TICK）与故障状态包处理器。
	 */
	private static void clearClientFault(BlockPos pos) {
		net.minecraft.client.Minecraft client = net.minecraft.client.Minecraft.getInstance();
		if (client == null) {
			return;
		}
		// 这里清的是"方块已经没频道了"的最终状态，不需要代际保护：
		// 直接把代际推一格，让任何排队中的待执行清除任务立刻过期（它们清除的也是同一类状态）。
		FAULT_GENERATION.incrementAndGet();
		if (client.isSameThread()) {
			faultShown = false;
			radiosmile.client.radio.ClientRadioNetwork.markLocalFault(
					pos, radiosmile.net.AudioStatus.OK);
		} else {
			// 不是主线程就 hop 过去（防御性：目前所有调用点都是主线程）。
			BlockPos snapshot = pos.immutable();
			client.execute(() -> {
				faultShown = false;
				radiosmile.client.radio.ClientRadioNetwork.markLocalFault(
						snapshot, radiosmile.net.AudioStatus.OK);
			});
		}
	}

	/**
	 * 液晶屏上当前是否亮着 ERR。
	 *
	 * <p>纯粹为了省掉 {@code execute()} 的排队开销，不参与任何判断逻辑——
	 * 真值仍以 {@code RadioBlockEntity.clientFaultStatus} 为准。
	 */
	private static volatile boolean faultShown;

	/** 故障显示状态变化时同步这个影子标志（由 ClientRadioNetwork 调用）。 */
	public static void noteFaultShown(boolean shown) {
		faultShown = shown;
		FAULT_GENERATION.incrementAndGet();
	}

	/**
	 * 上次为哪个方块、哪种状态打过故障日志。
	 *
	 * <p>用途见 {@link #logFaultOnce(BlockPos, int)}。只记最近一次：
	 * 正常情况下同时出问题的收音机很少，用单个槽位足够，也避免再引入一张会漏清理的 Map。
	 */
	private static volatile long lastFaultLoggedPos = Long.MIN_VALUE;
	private static volatile int lastFaultLoggedStatus = Integer.MIN_VALUE;

	/**
	 * <b>同一方块的同一种故障只打一条日志。</b>
	 *
	 * <h2>为什么需要它</h2>
	 * 断网重试期间，服务端可能对同一次失败反复回同一个状态码；
	 * 客户端每次收到都打一条 WARN，一次断网就能刷出几十行完全相同的
	 * 「音频订阅失败 @... status=N」——用户实测反馈「现在会刷屏」。
	 *
	 * <p>判据是「<b>方块 + 状态码</b>都没变」：换了台、或故障类型变了（比如从
	 * NO_AUDIO 变成 TIMEOUT）仍然会照常报，信息不丢。
	 *
	 * <p><b>线程</b>：只在客户端主线程调用（S2C 处理器里已经 {@code client.execute}）。
	 */
	public static void logFaultOnce(BlockPos pos, int status) {
		if (pos == null) {
			return;
		}
		long key = pos.asLong();
		if (key == lastFaultLoggedPos && status == lastFaultLoggedStatus) {
			return;
		}
		lastFaultLoggedPos = key;
		lastFaultLoggedStatus = status;
		SmileSRadio.LOGGER.warn("音频订阅失败 @{} status={}（自动重试中，后续重复不再打印）",
				pos, status);
	}

	/** 故障结束后调用：让同一个方块的故障日志下次还能再报一次。 */
	public static void resetFaultLog(BlockPos pos) {
		if (pos != null && pos.asLong() == lastFaultLoggedPos) {
			lastFaultLoggedPos = Long.MIN_VALUE;
			lastFaultLoggedStatus = Integer.MIN_VALUE;
		}
	}
}
