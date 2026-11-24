package radiosmile.client.audio;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;

import net.minecraft.core.BlockPos;

import radiosmile.SmileSRadio;
import radiosmile.client.config.RadioClientConfig;

/**
 * 多音源 PCM 混音器，输出到一块固定格式的声卡通道。
 *
 * <h2>为什么必须混音而不是每台收音机一条声卡线</h2>
 * 早期实现里「每台收音机各自准备数据、共用一条 {@code SourceDataLine}」，
 * 且通道格式跟着最近一帧的采样率/声道数变化。多台收音机同时播放时，
 * 不同频道的采样率不同（44.1k / 48k），导致 {@code ensureLine()}
 * 在播放循环里<b>反复 close/open 声卡</b>——设备频繁重建，听感就是巨量卡顿。
 *
 * <p>现在改成标准做法：
 * <ol>
 *   <li>输出格式固定为 48kHz 立体声 16-bit，声卡<b>只打开一次</b></li>
 *   <li>每个音源的数据先重采样/转声道到统一格式</li>
 *   <li>按采样位置叠加（饱和相加），再乘各自音量</li>
 *   <li>统一写入声卡</li>
 * </ol>
 *
 * <h2>为什么按"块"混音而不是按"样本"</h2>
 * 各音源到达时刻不同、块大小不同。这里把每个音源的数据先解成统一的
 * 48kHz 立体声帧缓冲，再按累积长度混进输出缓冲，凑够一个写块就写给声卡。
 *
 * <h2>为什么抖动缓冲在混音之前、设备缓冲很小</h2>
 * 声像（{@code panL}/{@code panR}）是在混音时乘上去的，音频一旦写进
 * {@code SourceDataLine} 的缓冲就再也改不了。因此「设备缓冲长度」就是
 * 「转头到听见声像变化的硬下限」。设备缓冲只留约 150ms，抗抖动的职责交给
 * 混音前的 {@link SourceBuffer}（攒够 {@code bufferMillis} 才出声），
 * 这样缓冲再深也不会拖慢声像。
 */
public final class PcmSink {
	public static final PcmSink INSTANCE = new PcmSink();

	/** 统一输出格式。 */
	private static final int OUT_RATE = 48000;
	private static final int OUT_CHANNELS = 2;
	/** 每次写给声卡的帧数（约 20ms @48k）。 */
	private static final int WRITE_FRAMES = 960;

	/**
	 * 设备缓冲固定时长（毫秒）——声像延迟的硬下限。
	 *
	 * <p>声像是在混音时乘上去的，音频一旦写进 {@link SourceDataLine} 的缓冲就再也改不了。
	 * 之前设备缓冲取 {@code RadioClientConfig.bufferMillis}（默认 1000ms），等于让声卡里
	 * 常驻一整秒「声像已定死」的音频——转头要等它播完才听得出方向变化，这就是
	 * 「朝向自动切换有延迟」的根因。现在设备缓冲只保留刚够喂饱声卡的一小段，
	 * 抗抖动的职责下移给混音前的 {@link SourceBuffer}（见 {@link #jitterTargetFrames()}），
	 * 缓冲再深也不会拖慢声像。
	 */
	private static final int DEVICE_BUFFER_MILLIS = 150;

	/** 设备缓冲至少 4 个写块（80ms），避免驱动把缓冲压得过小导致频繁欠载。 */
	private static final int DEVICE_BUFFER_FRAMES = WRITE_FRAMES * 4;

	/** 抖动缓冲目标时长下限：再小就抗不住网络抖动（一个 250ms 的块都装不下）。 */
	private static final int JITTER_MIN_MILLIS = 200;
	/** 抖动缓冲目标时长上限：再大就纯粹是延迟了，与「跟手」的目标背离。 */
	private static final int JITTER_MAX_MILLIS = 1500;

	private final Object lock = new Object();
	/** 待混音的来源数据：每个音源一个累加器。 */
	private final Map<Long, SourceBuffer> sources = new ConcurrentHashMap<>();
	/** 各收音机方块的独立音量。 */
	private final Map<Long, Float> blockVolumes = new ConcurrentHashMap<>();
	/** 各收音机方块的世界坐标，用于距离衰减。 */
	private final Map<Long, net.minecraft.core.BlockPos> blockPositions = new ConcurrentHashMap<>();
	private volatile float blockVolume = 1.0F;
	/** 原版 BLOCKS 声道音量（主线程更新）。 */
	private volatile float vanillaBlocksVolume = 1.0F;
	/** 原版总音量（主线程更新）。 */
	private volatile float vanillaMasterVolume = 1.0F;

	private SourceDataLine line;
	private Thread mixerThread;
	private volatile boolean running;
	/**
	 * 静音标志：为 true 时混音线程仍往声卡写静音数据，但设备保持打开。
	 *
	 * <p>保持写入是为了让声卡时钟持续运行——完全停写再恢复会有明显延迟与爆音。
	 */
	private volatile boolean silent = true;
	/** 进入静音后是否已清空过声卡缓冲（避免重复 flush）。 */
	private volatile boolean silenceFlushed;
	private final AtomicLong underruns = new AtomicLong();

	/**
	 * 「调台噪」发生器：换台 / 重载 / 静音时盖住音频跳变。
	 *
	 * <p>放在混音器里而不是用原版音效，是因为原版音效走 OpenAL，
	 * 与本模组的 {@code SourceDataLine} 是两条独立时钟，盖不准。
	 * 详见 {@link TuningNoise}。
	 */
	private final TuningNoise tuningNoise = new TuningNoise(OUT_RATE);

	/**
	 * 「网络差底噪」：链路吃紧时持续的轻微嘶声，提示玩家信号不稳。
	 *
	 * <p>强度由抖动缓冲剩余深度驱动（见 {@link #updateNetworkNoise}）。
	 * 正常播放时必须完全听不到——那不是效果音，而是故障提示。
	 */
	private final NetworkNoise networkNoise = new NetworkNoise();

	/**
	 * 正在淡出的音源：方块 → 剩余淡出帧数。
	 *
	 * <p>用于「换台时让旧音频淡出，而不是硬切」。硬切会在交界处留下断点，
	 * 即使同时有调台噪，听感仍是「咔」地一下；淡出后噪声能自然接管。
	 */
	private final Map<Long, Integer> fadeOutFrames = new ConcurrentHashMap<>();

	/**
	 * 让某个音源在指定时长内淡出，然后停止。
	 *
	 * <p>与 {@link #removeSource} 的区别：后者立即丢弃数据（硬切），
	 * 前者让现有数据继续播完但音量线性降到 0，之后再移除。
	 * 换台时用它可以与调台噪重叠，避免断点。
	 *
	 * @param blockPos 音源方块
	 * @param millis   淡出时长（毫秒）
	 */
	public void fadeOutSource(long blockPos, int millis) {
		int frames = Math.max(1, OUT_RATE * Math.max(1, millis) / 1000);
		this.fadeOutFrames.put(blockPos, frames);
	}

	/** 丢弃声卡内已缓冲、尚未播出的音频。 */
	private void flushLine() {
		SourceDataLine current = this.line;
		if (current != null) {
			try {
				current.flush();
			} catch (Exception ignored) {
				// 设备已关闭
			}
		}
	}

	private PcmSink() {
	}

	/** 单个音源的待混音数据（已转为 48k 立体声交错浮点）。 */
	private static final class SourceBuffer {
		float[] samples = new float[OUT_RATE];
		int length;
		/**
		 * 是否已攒够抖动缓冲目标、可以参与混音（目标见 PcmSink#jitterTargetFrames）。
		 *
		 * <p>代替旧的「起播后固定等 2 秒」粗办法：每个音源各自预热，
		 * 预热期间不出数，声卡仍由静音数据喂着，时钟不会停。
		 *
		 * <p>读写都在 {@code this.lock} 内，理论上已经安全；仍声明为 {@code volatile}
		 * 是为了让「已预热」这件事对混音线程立即可见——一个写块才 20ms，
		 * 因缓存行残留而多憋一块，听感上就是起播慢一拍。
		 */
		volatile boolean primed;

		/**
		 * <b>稳态深度基线</b>（帧数）：用于判断"缓冲是不是正在变薄"。
		 *
		 * <p>为什么需要它：直播流的水位恒定在起播水位（约 300ms），
		 * 用"相对配置目标"判断会永久误报（HANDOVER 第八节 C 的 v1 死因）。
		 * 改成"相对自己的稳态"后，正常直播流不会报警，
		 * 只有真正掉下去（丢包 / 上游卡 / 切台后接不上）才报。
		 *
		 * <p>更新规则见 {@link PcmSink#isBufferUnhealthy()}：
		 * 变厚立即跟随、变薄缓慢下移，于是"持续变薄"最终会被接受为新稳态，
		 * 只留下"突然变薄"这一个报警信号。
		 *
		 * <p><b>只跟随平滑后的水位</b>（见 {@link #smoothFrames}）：
		 * 跟随瞬时峰值会把「整块到达」的最高点当成稳态，
		 * 之后每个周期的正常回落都会被误判成"正在变薄"。
		 */
		int steadyFrames;

		/**
		 * 平滑后的水位（帧数）：滤掉「整块推送」造成的锯齿后再做变薄判断。
		 *
		 * <p>服务端按实时节奏整块推送（约 250ms 一块），缓冲水位是锯齿形的：
		 * 一块到达时顶到峰值，随后被声卡连续抽走一整块的量。
		 * 任何拿<b>瞬时</b>深度做的"变薄"判断都会被这个锯齿骗到。
		 * 平滑时间常数约 1 秒（远长于块节奏），锯齿被抹平，
		 * 平滑值稳定停在周期均值上——只有数据真的停了它才会往下掉。
		 *
		 * <p>{@code smoothInit} 首次置位时直接取当前深度，避免从 0 缓慢爬升。
		 */
		float smoothFrames;
		boolean smoothInit;

		/**
		 * 本音源<b>累计送给混音的帧数</b>（只增不减，诊断用）。
		 *
		 * <p>为什么需要它：客户端「收到的数据块数」和「真的能出声」是两件事。
		 * 解码器若一直搜不到帧同步（例如订阅起点落在坏数据里），块会源源不断到达、
		 * 流对象看起来完全正常，但混音器一帧都收不到——听感就是<b>永久静音</b>，
		 * 而且日志里没有任何异常。
		 *
		 * <p>用它做「冷启动看门狗」的判据：有流 + 有块 + 这个值为 0 = 解码卡死，
		 * 由 {@code AutoSubscriber} 强制重建一次解码器。
		 *
		 * <p>{@link #reset()} 刻意<b>不</b>清它：换台/清缓冲不是"从来没出过声"。
		 */
		long deliveredFrames;

		/**
		 * 这个缓冲里是否<b>只有效果音</b>、没有真实节目数据。
		 *
		 * <p>丢包/换台空窗时我们会为归属方块挂一个空块承载噪声。
		 * 这种块的长度天然很小（每块只塞 20ms 噪声），
		 * 若照常套用「丢包前过渡」的 reserve 衰减，它会被当成
		 * 「缓冲快吃空了」而压到极低音量——现象正是「换台噪声莫名其妙没了」。
		 *
		 * <p>所以纯效果音块必须跳过 reserve 衰减：它本来就不是供给不足，
		 * 而是每 20ms 现生成 20ms 的量。
		 */


		/**
		 * 清空数据并回到未预热状态。
		 *
		 * <p>与 {@link #compact} 不同：这里同时要求重新攒够目标帧数再出声，
		 * 用于换台 / flush —— 否则新频道会在缓冲还很浅时立刻开始播，
		 * 一抖就断供。capacity 故意不缩，避免反复重建数组。
		 */
		void reset() {
			this.length = 0;
			this.primed = false;
		}

		void append(float[] src, int len) {
			if (this.length + len > this.samples.length) {
				int newCap = Math.max(this.samples.length * 2, this.length + len);
				float[] grown = new float[newCap];
				System.arraycopy(this.samples, 0, grown, 0, this.length);
				this.samples = grown;
			}
			System.arraycopy(src, 0, this.samples, this.length, len);
			this.length += len;
		}

		void compact(int consumed) {
			if (consumed >= this.length) {
				this.length = 0;
				return;
			}
			System.arraycopy(this.samples, consumed, this.samples, 0, this.length - consumed);
			this.length -= consumed;
		}

		/**
		 * 丢弃队首的若干浮点数（按整帧对齐调用）。
		 *
		 * <p>与 {@link #compact} 的区别在于语义：compact 是「消费掉了」，
		 * dropFront 是「溢出丢数据」。两者实现相同，但分开命名便于排查——
		 * 早期把溢出处理写成 compact(len/2)，等于把缓冲腰斩，导致音频错乱。
		 */
		void dropFront(int floats) {
			this.compact(floats);
		}
	}

	// ------------------------------------------------------------------ 音量

	public void setBlockVolume(float volume) {
		float clamped = Math.max(0.0F, Math.min(1.0F, volume));
		boolean goingSilent = clamped <= 0.0001F && this.blockVolume > 0.0001F;
		this.blockVolume = clamped;
		if (goingSilent) {
			this.flush();
		}
	}

	public void setVolumeFor(long blockPos, float volume) {
		this.blockVolumes.put(blockPos, Math.max(0.0F, Math.min(1.0F, volume)));
	}

	/**
	 * 取某个方块的音量。
	 *
	 * <p><b>不能回退到全局 {@link #blockVolume}</b>：那样新打开的方块会继承
	 * 上一个方块的音量。未设置过的方块返回 1.0（默认满音量）。
	 */
	public float volumeFor(long blockPos) {
		Float own = this.blockVolumes.get(blockPos);
		return own != null ? own : 1.0F;
	}

	public void clearVolumeFor(long blockPos) {
		this.blockVolumes.remove(blockPos);
	}

	/**
	 * 注册音源所在的世界坐标，用于按距离做音量衰减。
	 *
	 * <p>原版用 OpenAL 通道做空间化（{@code Channel.setSelfPosition} + 距离衰减），
	 * 但我们走的是独立的 {@code SourceDataLine}，拿不到 OpenAL 通道，
	 * 所以在混音阶段用软件复现同样的效果：距离越远、音量越小，超出范围静音。
	 */
	public void setSourcePosition(long blockPos, net.minecraft.core.BlockPos pos) {
		if (pos != null) {
			this.blockPositions.put(blockPos, pos.immutable());
			// 新音源立即算一次，避免等下一个 tick 才生效
			if (this.listenerValid) {
				this.rebuildSpatialCache();
			}
		}
	}

	/** 移除某个音源的全部待混音数据（停止 / 切台时调用）。 */
	public void removeSource(long blockPos) {
		synchronized (this.lock) {
			this.sources.remove(blockPos);
		}
		this.blockPositions.remove(blockPos);
	}

	/** 清空所有音源数据。 */
	public void resetSources() {
		synchronized (this.lock) {
			this.sources.clear();
		}
		this.blockPositions.clear();
	}

	/**
	 * 计算某个音源的<b>距离衰减系数</b>（0~1）。
	 *
	 * <p>复现原版 {@code linearAttenuation} 的直觉：以收音机为圆心，
	 * 近距离满音量，到可听边界线性衰减到 0。范围取 48 格（与唱片机一致）。
	 *
	 * <p>必须在调用前于主线程取好听者位置（见 {@link #updateListener}），
	 * 这里不跨线程读 {@code Minecraft}。
	 */
	private float distanceGain(long blockPos) {
		net.minecraft.core.BlockPos pos = this.blockPositions.get(blockPos);
		if (pos == null) {
			return 1.0F;
		}
		return this.distanceGainAt(pos);
	}

	/**
	 * 按<b>世界坐标</b>直接算距离衰减（不依赖音源表）。
	 *
	 * <p>给效果音用：它们在音源被移除之后仍需要按原位置衰减。
	 */
	private float distanceGainAt(net.minecraft.core.BlockPos pos) {
		if (pos == null) {
			return 1.0F;
		}
		double lx = this.listenerX;
		double ly = this.listenerY;
		double lz = this.listenerZ;
		double dx = pos.getX() + 0.5 - lx;
		double dy = pos.getY() + 0.5 - ly;
		double dz = pos.getZ() + 0.5 - lz;
		double distSq = dx * dx + dy * dy + dz * dz;
		double dist = Math.sqrt(distSq);
		// 前 4 格不衰减（贴近时保持满音量），之后线性衰减到 0
		double fullRange = 4.0;
		double maxRange = 48.0;
		if (dist <= fullRange) {
			return 1.0F;
		}
		if (dist >= maxRange) {
			return 0.0F;
		}
		return (float) ((maxRange - dist) / (maxRange - fullRange));
	}

	/** 听者位置（主线程每 tick 更新）。 */
	private volatile double listenerX;
	private volatile double listenerY;
	private volatile double listenerZ;
	/** 听者朝向：yaw 与 head 的 pitch（用于判断「前方/后方」，不影响左右）。 */
	private volatile double listenerYaw;
	private volatile boolean listenerValid;

	/** 每个音源预计算的声像与距离增益（主线程每 tick 算一次）。 */
	private final Map<Long, float[]> spatialCache = new ConcurrentHashMap<>();

	/**
	 * 由主线程调用，更新听者位置与朝向，并<b>预计算</b>所有音源的声像/距离增益。
	 *
	 * <p>之前是在混音循环里逐帧调用 {@link #panFor} 与 {@link #distanceGain}——
	 * 那是每秒 48000 次三角函数，开销可观，而且听者位置本来就只有 20Hz 更新，
	 * 逐帧重算纯属浪费。现在改成每 tick 算一次缓存，混音循环只做数组查表。
	 */
	public void updateListener(double x, double y, double z, double yawDeg) {
		this.listenerX = x;
		this.listenerY = y;
		this.listenerZ = z;
		this.listenerYaw = yawDeg;
		this.listenerValid = true;
		this.rebuildSpatialCache();
	}

	/** 重算所有音源的声像与距离增益。 */
	private void rebuildSpatialCache() {
		for (Long key : this.blockPositions.keySet()) {
			float dist = this.distanceGain(key);
			float[] pan = this.panFor(key);
			// [0]=左声像 [1]=右声像 [2]=距离增益
			float[] target = {pan[0], pan[1], dist};
			float[] existing = this.spatialCache.get(key);
			if (existing == null) {
				// 首次：直接到位，避免从 0 淡入
				this.spatialCache.put(key, target);
			} else {
				// 已有值保留为平滑起点，由混音循环逐步逼近 target
				this.spatialTargets.put(key, target);
			}
		}
	}

	/**
	 * 平滑目标值。
	 *
	 * <p>听者位置只有 20Hz（每个客户端 tick 一次），而音频是 48kHz。
	 * 若直接跳变，转头时会听到声像「一格一格」地跳。这里在混音循环里
	 * 用一阶低通逼近目标，让方位连续过渡。
	 */
	private final Map<Long, float[]> spatialTargets = new ConcurrentHashMap<>();

	/**
	 * 向目标逼近的系数。
	 *
	 * <p>注意：{@link #smoothSpatial} 每处理一个<b>写出块</b>（960 帧 ≈ 20ms）才调用一次，
	 * 不是每个采样。因此这里用块级系数 0.55：时间常数约 25ms，
	 * 约 3.75 个块（75ms）达到 95%，转头时听起来是「跟手」而不是「滑过去」。
	 * 再大（0.8 以上）会听出 20ms 一级的台阶感，再小（0.39）尾部就明显拖后腿。
	 * 若误用采样级的小系数（如 0.0025），需要 8 秒才能跟上，声像会「卡住不动」。
	 */
	private static final float SMOOTH = 0.55F;

	/**
	 * 计算平滑后的空间参数。
	 *
	 * <p>返回新数组而不是就地修改：缓存里的数组可能正被主线程读取，
	 * 就地写会造成读到「半更新」的撕裂值（听感上是声像轻微抖动）。
	 */
	private float[] smoothSpatial(long key, float[] current) {
		float[] target = this.spatialTargets.get(key);
		if (target == null) {
			return current;
		}
		return new float[]{
				current[0] + (target[0] - current[0]) * SMOOTH,
				current[1] + (target[1] - current[1]) * SMOOTH,
				current[2] + (target[2] - current[2]) * SMOOTH
		};
	}

	/**
	 * 计算某音源相对听者的左右声像系数（恒功率声像）。
	 *
	 * <p>把方块位置投影到听者的「右方向」上得到横向分量，
	 * 再用恒功率律映射到左右增益，保证旋转视角时总体响度不忽大忽小。
	 *
	 * <p>横向分量为 0（正前/正后）时左右各 {@code √0.5}，总功率与居中时一致；
	 * 完全在右侧时右声道 1、左声道 0。这正是立体声空间化的标准做法。
	 *
	 * @return {@code [leftGain, rightGain]}
	 */
	private float[] panFor(long blockPos) {
		return this.panForPosition(this.blockPositions.get(blockPos));
	}

	/**
	 * 按<b>世界坐标</b>直接算声像（不依赖音源表）。
	 *
	 * <p>给效果音用：调台噪是从那台收音机的位置发出的，
	 * 必须和它主音频走<b>同一个通道</b>（同样的声像 + 同样的距离衰减），
	 * 否则会出现「声音在左边、噪声在正中」这种明显错位。
	 */
	private float[] panForPosition(net.minecraft.core.BlockPos pos) {
		if (pos == null || !this.listenerValid) {
			float c = (float) Math.sqrt(0.5);
			return new float[]{c, c};
		}
		double dx = pos.getX() + 0.5 - this.listenerX;
		double dz = pos.getZ() + 0.5 - this.listenerZ;

		// 听者<b>右</b>方向。
		//
		// MC 中 +X 是东、+Z 是南，yaw=0 时玩家面朝南。
		// 此时西（-X）在玩家右手边。
		// 朝向向量 forward = (-sin(yaw), cos(yaw))；把它绕 Y 轴<b>逆时针</b>转 90°
		// 才是右方向，即 right = (-forwardZ, forwardX)。
		//
		// 之前写成 right = (forwardZ, -forwardX)（顺时针转），
		// 得到的是「左方向」，于是声源左右完全颠倒——右边来的声音跑到了左边。
		double r = Math.toRadians(this.listenerYaw);
		double fx = -Math.sin(r);
		double fz = Math.cos(r);
		double rightX = -fz;
		double rightZ = fx;

		double horizontal = Math.sqrt(dx * dx + dz * dz);
		if (horizontal < 0.001) {
			// 与听者几乎重合：居中
			float c = (float) Math.sqrt(0.5);
			return new float[]{c, c};
		}
		// 归一化到 [-1, 1]：+1 表示完全在右侧
		double pan = (dx * rightX + dz * rightZ) / horizontal;
		pan = Math.max(-1.0, Math.min(1.0, pan));

		// 恒功率声像：角度从 -90° 到 +90°
		double angle = pan * (Math.PI / 2.0);
		float leftGain = (float) Math.cos((angle + Math.PI / 2.0) / 2.0);
		float rightGain = (float) Math.sin((angle + Math.PI / 2.0) / 2.0);
		return new float[]{leftGain, rightGain};
	}

	/**
	 * 更新原版音量快照。
	 *
	 * @param blocks 方块类音效音量
	 * @param master 总音量
	 */
	public void updateVanillaVolume(float blocks, float master) {
		this.vanillaBlocksVolume = Math.max(0.0F, Math.min(1.0F, blocks));
		this.vanillaMasterVolume = Math.max(0.0F, Math.min(1.0F, master));
	}

	// ------------------------------------------------------------------ 数据入口

	public void offer(short[] samples, int length, int sampleRate, int channels, long blockPos) {
		if (!RadioClientConfig.get().enableStreaming) {
			return;
		}
		// 转成统一格式（48k 立体声浮点交错）
		float[] converted = convert(samples, length, sampleRate, channels);
		if (converted == null || converted.length == 0) {
			return;
		}
		SourceBuffer buffer = this.sources.computeIfAbsent(blockPos, k -> new SourceBuffer());
		// 上限用「本音源自己的抖动目标」算，且放到锁外（读配置不该占着混音器的锁）
		int maxFloats = this.maxBufferedFloats();
		synchronized (this.lock) {
			// 限制积压上限：抖动目标 + 2 秒余量（按<b>本音源自身</b>的长度算）。
			//
			// 之前是固定 2 秒。现在抖动缓冲本身就要占掉 bufferMillis（最多 1.5 秒），
			// 固定 2 秒会让正常抖动直接顶到上限、频繁触发丢弃（听感是周期性「跳一下」）。
			// 上限留一点余量即可，这样深缓冲不会白占延迟配额。
			//
			// 超出时<b>按整块丢弃最旧的数据</b>：之前是 compact(buffer.length / 2)，
			// 即留住后一半 —— 这样会把缓冲从中间切断，导致音频被切成碎片后
			// 错误拼接，听感就是「声音有但顺序全乱」。正确做法是丢弃整段旧数据，
			// 保留近期连续的内容（见 HANDOVER 第七节第 7 条）。
			if (buffer.length + converted.length > maxFloats) {
				int overflow = buffer.length + converted.length - maxFloats;
				// 向上取整到整帧边界，保证不会切在采样中间
				int dropFloats = Math.min(buffer.length,
						((overflow + OUT_CHANNELS - 1) / OUT_CHANNELS) * OUT_CHANNELS);
				buffer.dropFront(dropFloats);
				// <b>丢弃必须留痕</b>：这里丢掉的是<b>最旧</b>的音频，
				// 听感是「一长串音频突然少了一块、后面的接上来」——不会报错、不会静音，
				// 只会让人觉得"音质怪"。历史上这个症状被反复误判成解码/网络问题，
				// 因为没有日志可查。
				//
				// <p>真正该触发它的场景极少（服务端配速严重过快，或客户端长时间卡住）。
				// 一旦出现就说明<b>上游供数比声卡消费快</b>，属于 QoS 异常，必须能看见。
				// 限流到每 5 秒最多一条，避免故障时刷屏。
				long nowMs = System.currentTimeMillis();
				if (nowMs - this.lastOverflowLogAt > 5000L) {
					this.lastOverflowLogAt = nowMs;
					SmileSRadio.LOGGER.warn(
							"抖动缓冲溢出，丢弃 {} ms 最旧音频（缓冲上限 {} ms）——"
									+ "通常意味着服务端发送速率高于实时播放速率",
							Math.round(dropFloats / (float) OUT_CHANNELS / OUT_RATE * 1000),
							Math.round(maxFloats / (float) OUT_CHANNELS / OUT_RATE * 1000));
				}
			}
			buffer.append(converted, converted.length);
			buffer.deliveredFrames += converted.length / OUT_CHANNELS;
		}
	}

	/** 采样率/声道转换。 */
	private static float[] convert(short[] in, int length, int rate, int channels) {
		if (length <= 0 || channels <= 0) {
			return null;
		}
		int frames = length / channels;
		if (frames <= 0) {
			return null;
		}
		// 目标帧数（重采样）
		int outFrames = rate == OUT_RATE ? frames
				: (int) ((long) frames * OUT_RATE / Math.max(1, rate));
		if (outFrames <= 0) {
			return null;
		}
		float[] out = new float[outFrames * OUT_CHANNELS];
		for (int i = 0; i < outFrames; i++) {
			// 线性插值重采样
			double srcPos = (double) i * frames / outFrames;
			int i0 = (int) srcPos;
			int i1 = Math.min(frames - 1, i0 + 1);
			double frac = srcPos - i0;

			for (int c = 0; c < OUT_CHANNELS; c++) {
				int srcCh = channels == 1 ? 0 : Math.min(c, channels - 1);
				float s0 = in[i0 * channels + srcCh] / 32768.0F;
				float s1 = in[i1 * channels + srcCh] / 32768.0F;
				out[i * OUT_CHANNELS + c] = (float) (s0 + (s1 - s0) * frac);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------ 输出

	public synchronized void start() {
		this.silent = false;
		if (this.running) {
			return;
		}
		this.running = true;
		this.mixerThread = new Thread(this::mixLoop, "RadioSmile-Mixer");
		this.mixerThread.setDaemon(true);
		this.mixerThread.start();
	}

	/**
	 * 停止全部播放。
	 *
	 * <p>只让混音线程进入静音输出，<b>不关闭声卡</b>——开关设备是重操作，
	 * 换台/暂停走这条路才不会卡。真正的设备释放在 {@link #releaseDevice()}。
	 *
	 * <p>注意：这里清空<b>所有</b>音源，因此只能在「全部停止」时调用。
	 * 单台收音机停播请用 {@link #removeSource(long)}。
	 */
	public synchronized void stop() {
		// 清空音源与混音线程的遍历共用 lock，保持与本轮统一的持锁纪律
		// （ConcurrentHashMap 本身不会坏数据，但混音线程正向遍历时被 clear
		//  会看到「部分清空」的中间态，表现为最后一两个块突然变成静音）。
		synchronized (this.lock) {
			this.sources.clear();
		}
		this.silent = true;
		// <b>必须立刻丢弃设备里已缓冲的音频</b>，否则「按下停止」与实际静音之间
		// 还差一整段设备缓冲（约 150ms 起，配置更大时更久）。
		// 用户要求「空选后按下就立刻停止」，所以这里主动 flush 一次，
		// 让停止是<b>即时生效</b>而不是「播完这批才停」。
		this.flushLine();
		// 效果音归属也一并清掉：方块已经没有频道了，不应再为它保留空音频块。
		this.tuningNoiseBlock = -1L;
		this.tuningNoise.reset();
		// 「网络差底噪」的锚点同样清掉，并且复位它自己的增益：
		// stop() 的语义是<b>用户不要了</b>，此后不该再有任何故障提示音。
		this.networkNoiseBlock = -1L;
		this.networkNoise.reset();
		// 「本流是否出过声」与会话绑定：停了就是一次流的结束，下次播放重新走预热门。
		this.streamFlowing = false;
		this.faultReported = false;
		this.consecutiveUnderruns = 0;
		this.weakNoiseHeld = false;
		this.healthyBlocks = 0;
		this.failingNow = false;
		// 用户关台：过渡静音窗一并作废。留着它只会让「下次开台」白得一段掩护，
		// 而那次开台本来就有自己的窗口。
		this.transitionSilenceUntil = 0L;
		this.transitionSilenceCapUntil = 0L;
		// 关台后不再"连续无数据"，死流计时要归零，否则下次开台一进来就被封顶。
		this.starvedSinceMs = 0L;
	}

	/**
	 * 因<b>故障</b>进入静音，但<b>保留故障信号</b>。
	 *
	 * <h2>为什么必须与 {@link #stop()} 分开</h2>
	 * 这是 HANDOVER 第八节 D 那次「stop() 语义拆分」<b>没有做完的下半截</b>：
	 * {@code ClientAudioPlayer} 早就拆成了 {@code stop()} / {@code stopForFailure()}，
	 * 但两者最终都调 {@code PcmSink.stop()} —— 也就是说
	 * 「用户不要了」和「链路坏了、玩家还想听」在混音器这一层又被合并回了同一个函数。
	 *
	 * <p>后果正是用户反馈的「断网无噪音」：服务端报故障 → {@code stopForFailure()} →
	 * {@code PcmSink.stop()} → {@code silent = true} → 混音线程进入静音分支 →
	 * 那个分支<s>每块都把底噪 reset 掉</s>（现已按 {@code playbackWanted} 分流）。
	 *
	 * <h2>行为差异（只有一处，而且必须只有一处）</h2>
	 * <table border="1">
	 *   <tr><th></th><th>{@link #stop()}</th><th>{@code silenceForFailure()}</th></tr>
	 *   <tr><td>清空音源缓冲 / flush 设备</td><td>是</td><td>是</td></tr>
	 *   <tr><td>{@code silent = true}（混音线程改写静音 data）</td><td>是</td><td>是</td></tr>
	 *   <tr><td>清 {@code tuningNoiseBlock} / 复位调台噪</td><td><b>是</b></td><td><b>否</b></td></tr>
	 *   <tr><td>复位 {@code networkNoise}（故障层）</td><td><b>是</b></td><td><b>否</b></td></tr>
	 * </table>
	 *
	 * <p>{@code tuningNoiseBlock} 也不该在这里清：它现在的语义是「调台噪归属哪台机器」，
	 * 与"这台机器的链路坏了"毫无关系，故障没有任何理由去动它。
	 *
	 * <p><b>线程</b>：只在主线程调用（{@code ClientAudioPlayer.stopInternal()}）。
	 * 它与 {@code stop()} 一样只用 {@code long} 快照和 volatile 标志，混音线程无需额外同步。
	 */
	public synchronized void silenceForFailure() {
		synchronized (this.lock) {
			this.sources.clear();
		}
		this.silent = true;
		// 与 stop() 同理：故障发生的瞬间就该断干净，不能先把设备缓冲里那 150ms 播完。
		this.flushLine();
		// 有意【不】清 tuningNoiseBlock、有意【不】reset tuningNoise / networkNoise：
		// 这是"故障静音"，不是"用户关机"。故障提示必须活到链路恢复为止。
	}

	/**
	 * 没有音源时进入静音，但保留设备。
	 *
	 * <p>用于单台收音机停播的场景：只有在<b>全部</b>音源都没了才静音，
	 * 否则会把还在播放的其它收音机一起静掉。
	 */
	public void silenceIfNoSources() {
		boolean nowSilent = false;
		synchronized (this.lock) {
			if (this.sources.isEmpty()) {
				this.silent = true;
				nowSilent = true;
				this.tuningNoiseBlock = -1L;
				this.streamFlowing = false;
				// <b>底噪锚点与增益必须一起清</b>。
				//
				// 早期这里只清 {@code tuningNoiseBlock}，漏了网络底噪这一层，于是：
				// 拆掉收音机时若「STREAMS 已经是空的」（服务端先停了推送、客户端才拆方块），
				// {@code stopInternal} 会走 {@code else} 分支到这里，
				// 而 {@code networkNoiseBlock} 与 {@code networkNoise} 的增益<b>原封不动</b> →
				// 方块都拆了，底噪还在响（用户实测「拆掉方块会有噪音」）。
				//
				// 语义上这也很明确：<b>一个音源都没有了</b>，
				// 就没有"哪台机器的链路坏了"这回事，故障提示失去了报警对象。
				this.networkNoiseBlock = -1L;
				this.networkNoise.reset();
				this.faultReported = false;
				this.consecutiveUnderruns = 0;
				this.weakNoiseHeld = false;
				this.healthyBlocks = 0;
				this.failingNow = false;
			}
		}
		if (nowSilent) {
			// 与 stop() 同理：进入静音的瞬间就要把设备里的残留丢掉，
			// 否则最后那台收音机的尾音还会播一小段。
			this.flushLine();
		}
	}

	/**
	 * 触发一次「调台噪」，用来盖住换台 / 换曲 / 重载时的音频跳变。
	 *
	 * <p>只是把噪声明明标记为待播，实际混音发生在混音线程里（与音频同一条时间轴），
	 * 因此调用方可以在任意线程调用，不会阻塞，也不会与设备操作争锁。
	 *
	 * <p><b>不要在这里动 {@code silent}</b>：静音分支本身也会渲染噪声
	 * （见 {@code mixLoop}），所以无论当前是否静音，噪声都出得来。
	 * 强行把 {@code silent} 置 false 反而会让「已经没有音源」时误走正常混音路径，
	 * 凭空多出一个空音源的欠载计数。
	 */
	/** 最近一次触发调台噪的方块；用于让噪声跟随该方块的独立音量。 */
	private volatile long tuningNoiseBlock = -1L;

	/**
	 * 「网络差底噪」的<b>独立</b>锚点：它服务于哪个方块。
	 *
	 * <h2>为什么必须与 {@link #tuningNoiseBlock} 分开</h2>
	 * 两者是<b>语义完全不同</b>的东西：
	 * <ul>
	 *   <li>{@code tuningNoiseBlock} 是<b>事件</b>的归属——某次换台/重载发生在哪台机器上；
	 *       它只由 {@link #playTuningNoise(BlockPos)} 设置，并被 {@code stop()} /
	 *       {@code silenceIfNoSources()} 清掉。</li>
	 *   <li>{@code networkNoiseBlock} 是<b>状态</b>的归属——哪台机器的链路现在是坏的；
	 *       它由 {@code setNetworkNoiseSource()} 设置，只在"链路恢复正常 / 用户关台"时清。</li>
	 * </ul>
	 *
	 * <p>早期两者共用 {@code tuningNoiseBlock}，于是底噪的可见性被绑死在"这次会话有没有换过台"上：
	 * 没换过台 → 锚点是 -1 → {@code overlayEffects()} 整层早退 → 断网时一点声音都没有。
	 * 这正是用户反馈的「断网无噪音」，也是 HANDOVER 第十二节那种"一个字段承担两种语义"
	 * 的又一例（前三例已记录在该节表格里）。
	 *
	 * <p><b>线程</b>：只在主线程设置。写入的是一个 {@code long} 快照，混音线程只读，
	 * 因此不需要加锁，也<b>绝不能</b>在混音线程里拿它去查 {@code client.level}。
	 */
	private volatile long networkNoiseBlock = -1L;

	/**
	 * 设置「网络差底噪」跟随哪个方块（音量 / 距离 / 声像）。
	 *
	 * <p>与 {@link #playTuningNoise(BlockPos)} 的关键区别：这是<b>状态</b>不是事件——
	 * 它不会触发任何声音，只是把「这台机器的链路坏了」记下来，等
	 * {@code updateNetworkNoise()} 判定故障后由 {@code overlayEffects()} 使用。
	 *
	 * <p>传 {@code null} 表示"没有具体方块"，此时底噪会退化为"听者正前方 + 只跟随原版音量"
	 * （见 {@code overlayEffects}）。宁可位置不准，也不能因为定位不到而让<b>故障提示</b>彻底消失。
	 *
	 * @param blockPos 处于故障中的收音机；{@code null} 表示不指定
	 */
	public void setNetworkNoiseSource(BlockPos blockPos) {
		this.networkNoiseBlock = blockPos == null ? -1L : blockPos.asLong();
	}

	/**
	 * 触发调台噪，并指定<B>它服务于哪个方块</B>。
	 *
	 * <p>带上方块坐标是为了让噪声跟随该方块的<b>独立音量滑块</b>：
	 * 玩家把某台收音机音量调到 0，换台时就不该轰的一声。
	 * 不传坐标时退化为「只跟随原版音量」。
	 *
	 * @param blockPos 触发换台的那个方块；{@code null} 表示不限
	 */
	public void playTuningNoise(BlockPos blockPos) {
		// 记住归属方块：决定效果音按哪台收音机的音量/距离/声像播放。
		// 即使它的真实音源随后被移除（换台会 flush / removeSource）也要保留——
		// 空窗恰恰是调台噪最该生效的时候。
		this.tuningNoiseBlock = blockPos == null ? -1L : blockPos.asLong();
		this.playTuningNoise();
	}

	public void playTuningNoise() {
		// <b>去重</b>：一次换台会从多处触发——客户端收到握手回执（S2C_SELECT_ACK）
		// 会触发一次，随后第一块新数据到达、handleChunk 发现频道变了又会触发一次。
		// 两次相隔仅几毫秒，早期会表现为「噪声放两遍」。
		//
		// 这里用一个很短的时间窗做去重：窗内的重复触发直接忽略。
		// 窗长必须远小于一次正常换台的间隔，否则连续快速切台会被吞掉；
		// 又不能太短，否则挡不住上述那对事件。
		long now = System.currentTimeMillis();
		if (now - this.lastTuningNoiseAt < TUNING_NOISE_DEDUPE_MILLIS) {
			return;
		}
		this.lastTuningNoiseAt = now;
		this.tuningNoise.trigger();
	}

	/** 最近一次触发调台噪的时刻，用于去重。 */
	private long lastTuningNoiseAt;

	/**
	 * 调台噪去重窗口（毫秒）。
	 *
	 * <p>取 400ms：足够盖住「握手回执」与「首块数据」这对紧密相邻的事件，
	 * 又短于正常人换台的间隔（不可能 400ms 内连着换两次台）。
	 */
	private static final long TUNING_NOISE_DEDUPE_MILLIS = 400L;

	/** 彻底释放设备与线程（断开连接 / 退出世界时调用）。 */
	public synchronized void releaseDevice() {
		this.running = false;
		this.silent = true;
		synchronized (this.lock) {
			this.sources.clear();
		}
		this.vanillaBlocksVolume = 1.0F;
		this.blockVolume = 1.0F;
		this.blockVolumes.clear();
		// 推流增益是随订阅下发的：换世界 / 重连后必须丢掉，否则会拿旧值播新流
		this.streamGains.clear();
		// 两个效果音都要复位，否则下次进入世界会莫名响一下
		this.tuningNoise.reset();
		this.networkNoise.reset();
		// 故障状态与会话一起丢弃：新世界不该继承上个世界的"链路坏了"
		this.networkNoiseBlock = -1L;
		this.streamFlowing = false;
		this.faultReported = false;
		this.consecutiveUnderruns = 0;
		this.weakNoiseHeld = false;
		this.weakNoiseSinceMs = 0L;
		this.weakNoiseSuppressed = false;
		this.healthyBlocks = 0;
		this.failingNow = false;
		this.underruns.set(0);
		this.lastUnderrunsSeen = 0;
		this.closeLine();
		if (this.mixerThread != null) {
			this.mixerThread.interrupt();
			this.mixerThread = null;
		}
	}

	/**
	 * 清空所有缓冲并立即静音（换台 / 取消选择）。
	 *
	 * <p>注意：现在必须把 SourceBuffer 一起<b>退回未预热状态</b>。
	 * 抖动缓冲下移到混音之前以后，「清空」不再等于「下一块立刻能播」——
	 * 若仍标记为已预热，新频道会在缓冲只有 20ms 时就开始输出，
	 * 网络稍一抖动就断供、听感是反复卡顿。退回未预热则重新攒够目标帧数再出声。
	 */
	public void flush() {
		synchronized (this.lock) {
			for (SourceBuffer buffer : this.sources.values()) {
				buffer.reset();
			}
		}
		SourceDataLine current = this.line;
		if (current != null) {
			try {
				current.flush();
			} catch (Exception ignored) {
				// 已关闭
			}
		}
	}

	public long underruns() {
		return this.underruns.get();
	}

	/**
	 * 某个音源到目前为止一共送给混音多少帧（诊断用）。
	 *
	 * <p>返回 0 有两种含义，调用方要配合 {@code ClientAudioPlayer.isStreaming()} 区分：
	 * <ul>
	 *   <li>根本没有音源 → 从没出过声（正常，可能还没起播）；</li>
	 *   <li>有音源、有数据块，但一帧也没送进来 → <b>解码器卡死</b>（异常）。</li>
	 * </ul>
	 *
	 * @param blockPos 音源方块的坐标键（{@code BlockPos#asLong()}）
	 */
	public long deliveredFramesFor(long blockPos) {
		synchronized (this.lock) {
			SourceBuffer buffer = this.sources.get(blockPos);
			return buffer == null ? 0L : buffer.deliveredFrames;
		}
	}

	public int queuedFrames() {
		int max = 0;
		synchronized (this.lock) {
			for (SourceBuffer buffer : this.sources.values()) {
				max = Math.max(max, buffer.length / OUT_CHANNELS);
			}
		}
		return max;
	}

	/**
	 * 混音主循环。
	 *
	 * <p>按固定输出块长度取数：所有音源按共同进度累加，
	 * 缺数据的音源当静音处理（不会因此重置整条声卡）。
	 *
	 * <p>每个音源先要攒够自己的抖动缓冲（{@link SourceBuffer#primed}）才参与混音；
	 * 预热期间与「暂时没供上数据」一样出静音，但声卡时钟一直在跑——
	 * 这正是替代旧「起播后干等 2 秒」的地方。
	 */
	private void mixLoop() {
		try {
			this.ensureLine();
		} catch (Exception e) {
			SmileSRadio.LOGGER.error("打开声卡失败", e);
			return;
		}

		float[] mix = new float[WRITE_FRAMES * OUT_CHANNELS];
		byte[] out = new byte[WRITE_FRAMES * OUT_CHANNELS * 2];
		// 复用的临时缓冲：避免每 20ms 分配新数组（GC 压力会拖慢供数）
		float[] takeBuf = new float[WRITE_FRAMES * OUT_CHANNELS];

		// 不再有「固定等 2 秒」的起播预攒：预热下移到每个 SourceBuffer 自己的
		// 抖动缓冲里（攒够 jitterTargetFrames() 才参与混音）。
		// 混音线程从第一次进入循环起就持续往声卡写数据 —— 没音源时写静音，
		// 声卡时钟从打开那一刻就在跑，不会因为等待而欠载。
		while (this.running) {
			// 静音状态：写静音数据让声卡时钟持续运行，避免恢复时延迟与爆音。
			//
			// 但调台噪例外：它正是要在「已经没声音了」的时候响——
			// 关掉最后一台收音机、或换台后新音频还没到的空窗，
			// 纯静音会让声音断得非常生硬，噪声能把断点盖住。
			if (this.silent) {
				// ============ 静音分支：必须按「意图」分流，不能一律抹掉故障层 ============
				//
				// <h2>为什么不能无条件 networkNoise.reset()（曾经的写法）</h2>
				// 早期这里每块都 reset。理由写在当时是成立的——"已经没有节目在放了，
				// 再响底噪会变成凭空多出来的噪音"。但它漏掉了一件事：
				// <b>静音有三种来源，语义并不相同</b>（HANDOVER 第十二节的同一个教训）：
				// <ol>
				//   <li>用户空选 / 拆方块 / 关台 → {@code stop()}：<b>要绝对安静</b>；</li>
				//   <li>故障 → {@code silenceForFailure()}：<b>玩家还想听</b>，
				//       这段静音本身就是故障现象，底噪正是该响的时候；</li>
				//   <li>还没起播 / 设备刚打开：同理不该响，由 {@code playbackWanted} 与
				//       {@code streamFlowing} 两道门一起挡住。</li>
				// </ol>
				// 一律 reset 等于把第 2 种当成第 1 种，于是"服务端报故障"这条路上
				// 底噪永远不可能响——这正是用户反馈的「断网无噪音」的一半。
				//
				// <h2>分流判据</h2>
				// 只用 {@code playbackWanted}（= 玩家还想听这个台）。它由
				// ClientAudioPlayer 在 stop() 里撤销、在 stopForFailure() 里保留，
				// 语义是单一的，不掺"是否还有音源"。
				//
				// <p>静音分支里<b>绝不渲染调台噪</b>：它不是故障提示，而是"换台拟音"，
				// 且时长上限靠正常路径的 followSilence() 推进。详见 overlayNetworkNoise()。
				boolean faultMaySound = this.playbackWanted;
				if (!faultMaySound) {
					// 用户明确要求安静：不要留任何提示音。
					this.networkNoise.reset();
					this.failingNow = false;
					this.consecutiveUnderruns = 0;
					this.weakNoiseHeld = false;
				} else {
					// 玩家还想听，但没有音源 → 这段静音是故障。
					// 用与正常路径<b>同一个</b> updateNetworkNoise() 判定，
					// 避免"静音时一套判据、正常时另一套"的双份逻辑。
					this.starved = true;
					this.updateNetworkNoise();
					if (this.networkNoise.isAudible() && this.faultSignalAllowed()) {
						// 只叠加底噪：调台噪的时长上限由正常路径的 followSilence() 推进，
						// 静音分支不推进它，跟进来会卡在 active 上无限响。
						java.util.Arrays.fill(mix, 0.0F);
						this.overlayNetworkNoise(mix);
						this.toBytes(mix, out);
						this.write(out);
						continue;
					}
				}
				// 走到这里只有两种可能：
				//   1) 用户已关台（playbackWanted == false）→ 本来就该绝对安静；
				//   2) 玩家还想听、但底噪还没起来（刚进入故障的头几块，或本流还在预热）。
				// 两种情况都写纯静音。
				//
				// 注意这里<b>不渲染调台噪</b>：调台噪的时长上限由正常路径的
				// followSilence() 推进，静音分支不推进它，跟进来会卡在 active 上无限响
				// （见 overlayNetworkNoise 的说明）。所以静音分支只允许底噪发声。
				java.util.Arrays.fill(out, (byte) 0);
				this.write(out);
				continue;
			}
			this.silenceFlushed = false;

			java.util.Arrays.fill(mix, 0.0F);
			boolean any = false;

			// <b>锁只包住「取数据」这一小段</b>。
			//
			// 之前整个混音循环（遍历 + 采样运算 + 平滑）都在锁内，而
			// offer()（解码线程）、flush()/音量调整（GUI 线程）也抢同一把锁。
			// 打开 GUI 时线程竞争加剧，混音线程迟迟拿不到锁 → 声卡供数中断
			// → 听感就是「进入/关闭 GUI 时严重卡顿」。
			synchronized (this.lock) {
				for (Map.Entry<Long, SourceBuffer> entry : this.sources.entrySet()) {
					SourceBuffer buffer = entry.getValue();
					int available = buffer.length / OUT_CHANNELS;
					if (available <= 0 || !buffer.primed) {
						// 还没攒够抖动缓冲：本块不出数（当静音），继续攒
						continue;
					}
					int take = Math.min(available, WRITE_FRAMES);
					int floats = take * OUT_CHANNELS;
					// 锁内只做拷贝
					System.arraycopy(buffer.samples, 0, takeBuf, 0, floats);
					buffer.compact(floats);

					// 混合（也在锁内，但只涉及本块数据，开销很小）
					float[] spatial = this.spatialCache.get(entry.getKey());
					float panL = spatial != null ? spatial[0] : 0.7071F;
					float panR = spatial != null ? spatial[1] : 0.7071F;
					float distGain = spatial != null ? spatial[2] : 1.0F;
					// <b>唯一的增益计算入口</b>：主音频与效果音都调用它，
					// 因此音量/原版音量/距离三者天然一致，不会出现「某一处漏乘」。
					// 「丢包前过渡」：缓冲见底前先平滑淡出，而不是硬切静音。
					float gain = this.sourceGain(entry.getKey(), distGain)
							* this.reserveFadeGain(entry.getKey(), buffer.length / OUT_CHANNELS)
							// 「换台淡出」：本块若处于淡出中，按剩余进度压音量
							* this.fadeOutGain(entry.getKey());
					// 立体声下混为单声道点声源，再按方位映射回左右
					for (int f = 0; f < take; f++) {
						float left = takeBuf[f * OUT_CHANNELS];
						float right = takeBuf[f * OUT_CHANNELS + 1];
						float mono = (left + right) * 0.5F * gain;
						mix[f * OUT_CHANNELS] += mono * panL;
						mix[f * OUT_CHANNELS + 1] += mono * panR;
					}
					any = true;
				}
			}

			// 平滑放在锁外：避免与 GUI/解码线程争锁
			//
			// 抖动缓冲的补水/丢弃<b>必须</b>与上面那段互斥（dropFront 也会整体前移
			// samples），所以由 refillJitterBuffers() 自己再进一次锁；它循环体里只有
			// 整数比较 + 偶尔一次 arraycopy，持锁时间远小于逐采样混合。
			this.refillJitterBuffers();
			this.advanceFadeOuts();
			this.smoothAllSpatial();

			if (!any) {
				// 这一块没有任何音源供上数据 = 真的掉数据。
				// 计一次欠载：这是「网络差底噪」唯一的触发依据（见 updateNetworkNoise）。
				this.underruns.incrementAndGet();
			} else if (!this.streamFlowing) {
				// <b>本流第一次真的出声</b>：预热窗口结束。
				//
				// 把这段窗口里累积的"欠载/缓冲见薄"痕迹全部清掉——它们是起播的正常过程，
				// 不是故障。不清的话，粘性判定（weakNoiseHeld）会让底噪在正常播放时
				// 继续响到 HEALTHY_RESTORE_BLOCKS，听感就是「每次起播都先沙一秒」。
				this.streamFlowing = true;
				this.consecutiveUnderruns = 0;
				this.weakNoiseHeld = false;
				this.healthyBlocks = 0;
				this.failingNow = false;
				this.networkNoise.reset();
			}

			// 调台噪<b>跟随空窗</b>：只要这一块没有节目出声就继续撑住，
			// 节目接上才淡出。固定时长（旧做法）盖不住长度可变的空窗——
			// 实测换台空窗可达 600ms，而固定 220ms 会在末尾留下 265ms 死寂。
			this.tuningNoise.followSilence(any);

			// 网络差底噪的触发判定。判据是「本块有没有音源供上数据」（显式传入），
			// 与静音分支共用同一个 updateNetworkNoise()。
			this.starved = !any;
			this.updateNetworkNoise();

			// <b>效果音独立叠加</b>（不进入音源缓冲区）。
			//
			// 为什么不复用主链的数据缓冲区：试过把噪声预混进 SourceBuffer，
			// 结果踩了三连坑——reserve 衰减把它当「缓冲见底」压掉、
			// any 判定把它当「节目回来了」、
			// 以及 += 累加配合 length=0 造成音量无限增长。
			// 那些问题的本质是「效果音没有真实数据流的生命周期，
			// 却被迫服从缓冲区的预热/丢弃/清零规则」。
			//
			// 现在改为独立叠加：噪声只是往输出上「加一笔」，
			// 完全脱离缓冲区生命周期，不会干扰任何抖动缓冲逻辑。
			// 但仍<b>复用主链的增益与声像函数</b>（sourceGain / panForPosition），
			// 所以音量、距离、方位依旧与节目一致。
			this.overlayEffects(mix);


			if (!any) {
				// 没有音源供数：mix 里此刻只有噪声（或全 0）。
				// 仍然要写出去，让声卡不断流，也保证换台空窗里能听到调台噪。
				this.toBytes(mix, out);
				this.write(out);
				continue;
			}

			this.toBytes(mix, out);
			this.write(out);
		}
	}

	/**
	 * 取值某个音源的淡出系数（不推进进度）。
	 *
	 * <p>进度由 {@link #advanceFadeOuts()} 每块统一推进，
	 * 避免同一块内被多个读取点重复扣减。
	 *
	 * @return 0~1；不在淡出中返回 1
	 */
	private float fadeOutGain(long blockPos) {
		Integer remaining = this.fadeOutFrames.get(blockPos);
		if (remaining == null) {
			return 1.0F;
		}
		if (remaining <= 0) {
			return 0.0F;
		}
		return Math.min(1.0F, (float) remaining / Math.max(1, FADE_OUT_FRAMES));
	}

	/** 换台淡出的总帧数（120ms）。 */
	private static final int FADE_OUT_FRAMES = OUT_RATE * 120 / 1000;

	/**
	 * 推进所有淡出音源的进度，并清理已淡完的。
	 *
	 * <p>每个写块调用一次（与 {@link #refillJitterBuffers} 同频）。
	 */
	private void advanceFadeOuts() {
		if (this.fadeOutFrames.isEmpty()) {
			return;
		}
		java.util.List<Long> done = new java.util.ArrayList<>();
		for (Map.Entry<Long, Integer> entry : this.fadeOutFrames.entrySet()) {
			int left = entry.getValue() - WRITE_FRAMES;
			if (left <= 0) {
				done.add(entry.getKey());
			} else {
				entry.setValue(left);
			}
		}
		for (Long key : done) {
			this.fadeOutFrames.remove(key);
			// 淡出完成：真正移除，释放缓冲
			this.removeSource(key);
		}
	}

	/**
	 * <b>单个音源的增益计算（唯一入口）</b>：
	 * {@code 方块独立音量 × 原版 BLOCKS × 原版 MASTER × 距离衰减}。
	 *
	 * <p>主音频与效果音都走这一个函数。这一点很关键——
	 * 之前效果音另起一条路径，于是每补一个环节都要改两处，
	 * 历史上连续漏过方块音量、距离衰减、声像三次，每次都表现为「噪声不受控」。
	 *
	 * @param blockPos 音源方块
	 * @param distGain 该音源的距离衰减系数（由声像缓存提供）
	 */
	private float sourceGain(long blockPos, float distGain) {
		return this.volumeFor(blockPos)
				* this.vanillaBlocksVolume
				* this.vanillaMasterVolume
				* distGain
				* this.streamGainFor(blockPos);
	}

	/**
	 * 每个音源的<b>推流增益</b>（0.00~1.00），由服务端随音频块下发。
	 *
	 * <h2>为什么需要它</h2>
	 * 本地曲目是"给游戏内直接播放"处理过的成品，母带电平高（实测平均
	 * -9.1 ~ -15.3 dBFS、峰值顶到 0 dBFS）；网络电台是原样透传的上游流，
	 * 电平低一档。不压本地，本地那一路就会盖过网络电台，而且已经满刻度，
	 * 一叠上底噪层就削波。
	 *
	 * <h2>为什么放在这里，而不是烤进缓存</h2>
	 * 它是<b>运行时参数</b>：服务端每包按当前配置给一次，
	 * 玩家改完 {@code outputGain} 下一个包就生效，不用重新预热。
	 * 而方块音量 / 原版音量是<b>本地参数</b>，必须继续在本机乘——
	 * 这条乘法链就是"所有影响响度的量都在同一处"的约定（见 {@link #sourceGain}）。
	 */
	private final Map<Long, Float> streamGains = new ConcurrentHashMap<>();

	/** 由网络线程写入：某个音源当前的推流增益。 */
	public void setStreamGain(long blockPos, float gain) {
		float clamped = Math.max(0.0F, Math.min(1.0F, gain));
		Float old = this.streamGains.get(blockPos);
		// 每块音频都会调一次：值没变就不要反复覆盖，省掉无谓的 map 写
		if (old != null && Math.abs(old - clamped) < 0.0005F) {
			return;
		}
		if (clamped >= 0.9995F) {
			// 满增益是绝大多数网络电台的情况，不必留表项
			this.streamGains.remove(blockPos);
			return;
		}
		this.streamGains.put(blockPos, clamped);
	}

	/** 取某个音源的推流增益；没下发过按 1.0（不衰减）。 */
	private float streamGainFor(long blockPos) {
		Float own = this.streamGains.get(blockPos);
		return own != null ? own : 1.0F;
	}

	/**
	 * 「丢包前过渡」增益：缓冲快见底时提前平滑淡出，而不是硬切静音。
	 *
	 * <h2>为什么需要它</h2>
	 * 网络抖动导致某一块没数据时，混音器会输出一整块纯静音——
	 * 听感是声音<b>戛然而止</b>再突然接上，比自然的淡出刺耳得多。
	 * 而缓冲见底其实是<b>可预知</b>的：只要盯着剩余量，就能在真正吃完之前先降下来。
	 *
	 * <h2>怎么做</h2>
	 * 剩余量低于「安全水位」时线性降增益，到零时增益也到零，
	 * 于是静音是「淡出到位」的结果，而不是硬切。缓冲补回来后按同一曲线自然回升。
	 *
	 * <p>安全水位取 {@link #RESERVE_FADE_FRAMES}（约 120ms）：
	 * 够短，不会在正常抖动时就压低音量；够长，足以覆盖一次块级别的落空。
	 *
	 * <p>代价是缓冲接近水位时音量会略降。因此水位取得比较低，
	 * 正常播放（缓冲稳定在 300ms 以上）时这个系数恒为 1，完全听不出影响。
	 */
	private float reserveFadeGain(long blockPos, int remainingFrames) {
		if (remainingFrames >= RESERVE_FADE_FRAMES) {
			return 1.0F;
		}
		if (remainingFrames <= 0) {
			return 0.0F;
		}
		// 线性过渡；平方一下让转折更柔和（避免线性的"拐点"被听出来）
		float ratio = (float) remainingFrames / RESERVE_FADE_FRAMES;
		return ratio * ratio;
	}

	/**
	 * 「丢包前过渡」的安全水位（帧数）。
	 *
	 * <p>48000Hz 下 5760 帧 = 120ms。正常播放时缓冲约 300ms 以上，
	 * 因此稳态下该系数恒为 1；只有真被吃薄时才起作用。
	 */
	private static final int RESERVE_FADE_FRAMES = 48000 * 120 / 1000;



	/**
	 * 效果音独立叠加层。
	 *
	 * <h2>为什么不进音源缓冲区（放弃 premix）</h2>
	 * 试过把噪声预混进 {@link SourceBuffer}，连踩三坑：
	 * <ol>
	 *   <li>「丢包前过渡」把它误判成「缓冲快见底」，压到 2.8% 音量；</li>
	 *   <li>{@code any} 判定把纯噪声块当成「节目回来了」，噪声自我淡出；</li>
	 *   <li>{@code premix} 用 {@code +=} 而清空只置 {@code length=0}，
	 *       旧样本残留导致音量逐块增长直到削波。</li>
	 * </ol>
	 * 根因是效果音<b>没有真实数据流的生命周期</b>，却被迫服从缓冲区的
	 * 预热/丢弃/清零规则。所以改为独立叠加：只为输出「加一笔」，
	 * 不碰任何缓冲区状态。
	 *
	 * <h2>但仍复用主链的增益与声像</h2>
	 * 调用 {@link #sourceGain} 与 {@link #panForPosition}——
	 * 与主音频<b>同一个函数</b>，因此音量/距离/方位必然一致，
	 * 不会重演「效果音漏乘某个环节」的问题。
	 *
	 * <p>锁外执行，只碰混音线程独占的 {@code mix}。
	 */
	private void overlayEffects(float[] mix) {
		boolean tuningActive = this.tuningNoise.isActive();
		// 故障层是否允许发声由 faultSignalAllowed() 统一判定（playbackWanted + 本流已出声）。
		boolean networkActive = this.networkNoise.isAudible() && this.faultSignalAllowed();
		if (!tuningActive && !networkActive) {
			return;
		}
		if (this.effectScratch == null) {
			this.effectScratch = new float[WRITE_FRAMES];
		}

		// ---------------- 调台噪：仍按 tuningNoiseBlock 定位（语义不变） ----------------
		if (tuningActive) {
			long anchor = this.tuningNoiseBlock;
			// 调台噪<b>没有</b>归属方块时确实不该响：它是"某台机器在换台"的拟音，
			// 离开那台机器的方位就没有意义了（早期就是这样，是对的）。
			if (anchor >= 0) {
				float[] pan = this.panForPosition(this.blockPositions.get(anchor));
				float dist = this.distanceGain(anchor);
				float gain = this.sourceGain(anchor, dist);
				java.util.Arrays.fill(this.effectScratch, 0.0F);
				int n = this.tuningNoise.generateMono(this.effectScratch, WRITE_FRAMES);
				addMonoToMix(mix, this.effectScratch, n, gain, pan[0], pan[1]);
			}
		}

		// ---------------- 网络底噪 ----------------
		if (networkActive) {
			this.overlayNetworkNoise(mix);
		}
	}

	/**
	 * 只叠加「网络差底噪」这一层。
	 *
	 * <p>单独抽出来，是因为它<b>也要在静音分支里发声</b>（服务端报故障后 mixer 处于静音），
	 * 而调台噪<b>不能</b>——调台噪是"换台拟音"，它的时长上限由
	 * {@code TuningNoise.followSilence()} 在<b>正常路径</b>里推进；
	 * 静音分支不跑 {@code followSilence()}，若跟着一起渲染，它会一直卡在 active 上响个不停。
	 * 所以"静音分支只出声底噪"这件事必须由结构保证，而不是在调用处加 if。
	 *
	 * <h2>无锚点时的取舍</h2>
	 * <b>绝不能因为"定位不到"就整层 return</b>（早期 {@code overlayEffects()} 就是那样，
	 * 症状正是「断网无噪音」）：调台噪是<b>效果音</b>，没有方位就不放，合理；
	 * 而网络底噪是<b>故障提示</b>，它唯一的职责是"让玩家知道链路坏了"。
	 * 定位不到时正确的取舍是<b>牺牲方位、保住提示</b>——
	 * 退化为听者正前方（恒功率居中）并只跟随原版音量，而不是彻底没声。
	 *
	 * <p>锁外执行，只碰混音线程独占的 {@code mix}。
	 */
	private void overlayNetworkNoise(float[] mix) {
		if (this.effectScratch == null) {
			this.effectScratch = new float[WRITE_FRAMES];
		}
		long anchor = this.networkNoiseBlock;
		float[] pan;
		float gain;
		if (anchor >= 0) {
			// 有归属：与节目<b>同一个</b>增益/声像函数，保证音量与方位完全一致。
			pan = this.panForPosition(this.blockPositions.get(anchor));
			gain = this.sourceGain(anchor, this.distanceGain(anchor));
		} else {
			// 无归属：居中 + 只乘原版音量（不乘方块独立音量，因为没有方块）。
			float c = (float) Math.sqrt(0.5);
			pan = new float[]{c, c};
			gain = this.vanillaBlocksVolume * this.vanillaMasterVolume;
		}
		java.util.Arrays.fill(this.effectScratch, 0.0F);
		int n = this.networkNoise.generateMono(this.effectScratch, WRITE_FRAMES);
		addMonoToMix(mix, this.effectScratch, n, gain, pan[0], pan[1]);
	}

	/**
	 * 把单声道样本按增益与声像叠加到交错立体声混音缓冲。
	 *
	 * <p>与主音频的混音方式<b>逐字相同</b>（下混为点声源 → 乘增益 → 按恒功率声像分配），
	 * 保证效果音听起来就是「从那个音源位置发出的」。
	 */
	private static void addMonoToMix(float[] mix, float[] mono, int n, float gain,
			float panL, float panR) {
		if (mono == null || n <= 0 || gain <= 0.0001F) {
			return;
		}
		int frames = Math.min(n, mix.length / OUT_CHANNELS);
		for (int f = 0; f < frames; f++) {
			float s = mono[f] * gain;
			mix[f * OUT_CHANNELS] += s * panL;
			mix[f * OUT_CHANNELS + 1] += s * panR;
		}
	}

	/** 叠加用的复用缓冲，避免每 20ms 分配。 */
	private float[] effectScratch;



	/**
	 * 只在<b>真的掉数据</b>时更新「网络差底噪」。
	 *
	 * <h2>为什么不再用「缓冲变薄」当信号</h2>
	 * 早期版本用「抖动缓冲剩余深度」推断链路状况：缓冲薄 → 快断了 → 响底噪。
	 * 这个推断<b>在直播流上是错的</b>：直播流的产出速率与消费速率都是实时 1:1，
	 * 缓冲深度会停在起播那一刻的水位，<b>不会自己长到稳态目标</b>。
	 * 加入「起播门槛 300ms」之后，水位恒定在 300/1000 = 0.30，
	 * 于是底噪被永久拉在 40% 强度上——现象正是「突然出现、而且不消失」。
	 *
	 * <h2>现在的判据</h2>
	 * 只看 {@link #underruns}：混音线程某一块<b>没有任何音源供上数据</b>，
	 * 说明音频真的断了（网络丢包 / 上游卡顿 / 解码跟不上）。这是确凿的掉数据证据，
	 * 不依赖任何水位假设。
	 *
	 * <p>触发后由 {@link NetworkNoise} 自己做淡入淡出（起音快、落音慢），
	 * 所以听感是「沙」地一闪再慢慢退去，而不是硬生生开关。
	 */
	private void updateNetworkNoise() {
		// 本块是否无数据：由调用方（正常路径 / 静音分支）显式告知，见 {@link #starved}。
		if (this.starved) {
			this.consecutiveUnderruns++;
		} else {
			this.consecutiveUnderruns = 0;
		}
		// 「连续无数据」的起始时刻：只有<b>真的连续</b>才累积。
		// 抖动型的欠载（有块、没块交替）会在这里被清零，
		// 所以饥饿宽限（见 {@link #STARVE_GRACE_MILLIS}）不会误伤正常卡顿。
		long nowMs = System.currentTimeMillis();
		if (this.starved) {
			if (this.starvedSinceMs == 0L) {
				this.starvedSinceMs = nowMs;
			}
		} else {
			this.starvedSinceMs = 0L;
		}
		// <b>本流还没出过声</b>时，欠载是预热而不是故障：不给它累积的机会。
		// 只清计数、不动 networkNoise 的增益（那时它本来就是 0）。
		if (!this.streamFlowing && !this.faultReported) {
			this.consecutiveUnderruns = 0;
			this.weakNoiseHeld = false;
			this.failingNow = false;
			this.starvedSinceMs = 0L;
			this.networkNoise.updateTarget(0.0F);
			return;
		}

		// <b>过渡静音窗</b>：切歌 / 切台 / 重载时我们自己 flush 出来的空窗，
		// 不是链路故障。窗内一律按数字静音处理，判据全部清零（详见
		// {@link #transitionSilenceUntil}）。
		//
		// <p>判据顺序很关键：这一段必须<b>早于</b>下面 healthy / thinning 的计算。
		// flush 之后缓冲是 0，那两个判据必然"不健康"，先算就等于先 latch 弱底噪，
		// 之后即使窗内把目标压成 0，{@code weakNoiseHeld} 的粘性也会让它
		// 在窗后接着响 500ms。
		//
		// <p>只在「服务端没有明确报故障」时生效：{@code faultReported} 是被告知的结论，
		// 优先级高于我们从欠载里做的推测。
		if (!this.faultReported && this.inTransitionSilence()) {
			this.consecutiveUnderruns = 0;
			this.weakNoiseHeld = false;
			this.healthyBlocks = 0;
			this.failingNow = false;
			this.starvedSinceMs = 0L;
			this.networkNoise.updateTarget(0.0F);
			return;
		}

		// 抖动缓冲是否回到健康水平（用来撤销「弱底噪」）。
		//
		// 旧写法是两道瞬时判据相与（isJitterBufferThinned + isBufferThinning）。
		// 两者都拿<b>瞬时</b>深度做比较，被「整块推送」的水位锯齿骗到：
		// 健康链路上每个周期都有几十毫秒深度低于阈值，于是永远判「不健康」，
		// 弱底噪被永久粘住——本地回环也复现，与弱网无关（详见 isBufferUnhealthy）。
		// 现在合并为一道基于<b>平滑水位</b>的判据。
		boolean healthy = !this.isBufferUnhealthy();
		if (healthy) {
			this.healthyBlocks++;
		} else {
			this.healthyBlocks = 0;
		}

		// ---- 两档底噪 ----
		//
		// 用户实测录像（16 秒）显示的真实波形是：每 1~1.5 秒出现一次
		// 「缓冲吃空 -> 约 150ms 数字静音 -> 短促噪声 -> 恢复」。
		// 据此确定两档：
		//
		//   1) <b>卡住时</b>（这一块完全没有数据）：大底噪，
		//      直接把那段该死寂填满——比静音好得多，至少玩家知道设备还在工作。
		//   2) <b>不卡但缓冲仍不健康</b>：弱底噪持续提示，
		//      一直响到缓冲真正恢复正常（{@link #HEALTHY_RESTORE_BLOCKS}）才撤。
		//
		// 早期实现只有「连续 8 块无数据才响」一档，于是卡顿间隙的静音
		// 完全没人填补，其余时间也毫无提示——正是用户说的「该响的时候没响」。
		boolean starving = this.consecutiveUnderruns > 0;
		boolean thinned = !healthy;

		// 「弱底噪」有粘性：一旦触发就保持，直到缓冲连续健康一段时间才撤。
		// 否则卡顿是一阵一阵的，底噪会跟着一开一关，听感是「噗噗」抖动。
		//
		// <b>时长封顶</b>：粘住超过 {@link #WEAK_NOISE_MAX_MILLIS} 就强制撤销，
		// 并禁止在缓冲真正健康过一轮之前再次粘住。这是结构性保险——
		// 无论上游判据将来怎么误报，这一档都不可能再变成常驻嘶声。
		if (thinned) {
			if (!this.weakNoiseHeld && !this.weakNoiseSuppressed) {
				this.weakNoiseHeld = true;
				this.weakNoiseSinceMs = nowMs;
			} else if (this.weakNoiseHeld
					&& nowMs - this.weakNoiseSinceMs > WEAK_NOISE_MAX_MILLIS) {
				this.weakNoiseHeld = false;
				this.weakNoiseSuppressed = true;
			}
			this.healthyBlocks = 0;
		} else if (this.healthyBlocks >= HEALTHY_RESTORE_BLOCKS) {
			this.weakNoiseHeld = false;
			this.weakNoiseSuppressed = false;
		}

		boolean failing = starving || this.consecutiveUnderruns >= UNDERRUN_ALERT_BLOCKS;

		// <b>必须连续多块都没数据才算真的掉线</b>。
		//
		// 只看「这一块有没有欠载」会导致网络电台<b>频繁误报</b>：
		// 直播流在上游是实时产出，块边界经常对不齐，单块偶尔落空是正常抖动；
		// 起播攒缓冲、解码器重启的瞬间也必然欠载。
		// 这些都不是「网络差」，却都会响一次噪声——用户实测反馈的
		// 「订阅网络电台还是会断噪」就是这个。
		//
		// 阈值取 8 块（约 160ms）：远大于单块抖动，又远小于起播门槛（300ms），
		// 真断线时仍能及时提示。

		// <b>故障期间持续发声，而不是响一下就走</b>。
		//
		// 早期实现是「检测到掉数据 -> 响 400ms -> 淡出」，结果断网时只听到一声沙，
		// 之后完全安静——玩家无法判断「是还在重连、还是彻底坏了」。
		// 现在改为：只要「连续没有数据」这个条件成立，就一直维持底噪。
		//
		// 停止条件（由外部调用 {@link #clearNetworkNoise()} 触发）：
		//   - 网络恢复、重新收到数据
		//   - 玩家手动切台
		//   - 空选/关闭收音机
		this.failingNow = failing;
		// <b>两档</b>：
		//   卡住（这一块完全没数据）-> 大底噪，填满静音
		//   不卡但缓冲未恢复      -> 弱底噪，持续提示到恢复为止
		//
		// <b>故障噪音没有时长上限</b>（用户明确要求「断网要保持噪音」）。
		//
		// 曾经加过一道「死流封顶」（连续无数据 3.5 秒后强制静音），当时是为了
		// 压制一段常驻白噪；后来查明那段白噪的真正根因是缓冲水位锯齿骗过
		// 瞬时判据（见 {@link #isBufferUnhealthy()}），平滑水位修复后误报消失，
		// 封顶反而把真实断网的提示音也在 3.5 秒后掐掉了——玩家无法判断
		// 「是还在重连、还是彻底坏了」。所以撤掉封顶：故障噪音一直响到
		// 数据恢复或玩家操作为止。
		//
		// <b>饥饿宽限</b>：刚开始掉数据的那 {@link #STARVE_GRACE_MILLIS}，
		// <b>冻结</b>在当前增益上，而不是归零。
		//
		// 宽限的目的是放过「起播、换曲、重连」这类正常空窗——它们开始时增益本来就是 0，
		// 冻结在 0 = 保持静音，行为不变。
		// 但对「缓冲被吃薄 → 弱底噪正在渐起 → 吃空」这条真实故障链，
		// 旧写法（归零）会把已经渗上来的弱底噪先压回去，宽限一过又直接拉满 1.0——
		// 电平路径 0.4 → 跌回 0 → 猛跳 1.0，听感正是用户说的
		// 「极值比维持的要大，会产生跳变」。冻结让电平连续：
		// 0.4 → 保持 → 平滑爬到 1.0，任何时刻都不跳。
		boolean starveGrace = failing && this.starvedSinceMs != 0L
				&& nowMs - this.starvedSinceMs < STARVE_GRACE_MILLIS;

		float target;
		if (starveGrace) {
			// 冻结：目标 = 当前实际增益（updateTarget 对相等的值不做任何移动）
			target = this.networkNoise.gain();
		} else if (failing) {
			target = 1.0F;
		} else if (this.weakNoiseHeld) {
			target = STUTTER_LEVEL;
		} else {
			target = 0.0F;
		}
		this.networkNoise.updateTarget(target);
	}

	/**
	 * 「弱底噪」是否处于保持状态。
	 *
	 * <p>有粘性：触发后一直响到缓冲连续健康 {@link #HEALTHY_RESTORE_BLOCKS} 块才撤，
	 * 避免卡顿一阵一阵时底噪跟着一开一关（听感是「噗噗」抖）。
	 *
	 * <p>另有<b>时长封顶</b>（见 {@link #WEAK_NOISE_MAX_MILLIS}）：
	 * 粘住太久会强制撤销，保证这一档不可能变成常驻嘶声。
	 */
	private boolean weakNoiseHeld;

	/** 「弱底噪」开始粘住的时刻；配合 {@link #WEAK_NOISE_MAX_MILLIS} 做时长封顶。 */
	private long weakNoiseSinceMs;

	/** 封顶撤销后是否禁止再次粘住（缓冲真正连续健康过一轮才解除）。 */
	private boolean weakNoiseSuppressed;

	/**
	 * 是否仍有「播放意图」（即玩家还希望听到这个台）。
	 *
	 * <p>由 {@code ClientAudioPlayer} 在订阅存在/解除时设置。
	 * 用来区分两类「音源为空」：
	 * <ul>
	 *   <li><b>意图仍在但拿不到数据</b>（断网 / 上游卡死 / 重连中）
	 *       -> 是异常，底噪应该响</li>
	 *   <li><b>意图已撤（空选 / 关机）</b> -> 正常静音，不该响</li>
	 * </ul>
	 *
	 * <p>没有这个区分的话，只能二选一：要么断网时底噪不响（早期 bug），
	 * 要么空选后还在沙沙响（同样不对）。
	 */
	private volatile boolean playbackWanted;

	/** 连续健康块数：缓冲持续不薄才允许撤销弱底噪。 */
	private int healthyBlocks;

	/**
	 * 撤销弱底噪所需的连续健康块数。
	 *
	 * <p>约 0.5 秒（25 块 × 20ms）：够跨过卡顿之间的短暂间隙，
	 * 又不会让恢复后底噪拖太久。
	 */
	private static final int HEALTHY_RESTORE_BLOCKS = 25;

	/**
	 * 「弱底噪」的最长持续时长（毫秒）。
	 *
	 * <p>结构性保险：提示音的职责是「告诉你信号不稳」，不是「替你忍受断网」。
	 * 连续响满 8 秒还不见好就闭嘴——真断了还有大底噪（欠载档）与自动重连兜底。
	 * 它保证这一档<b>无论上游判据将来怎么误报，都不可能再变成常驻嘶声</b>
	 * （水位判据已三次被误报坑出「突然出现且不消失」的底噪）。
	 */
	private static final long WEAK_NOISE_MAX_MILLIS = 8000L;

	/**
	 * <b>缓冲层是否「不健康」</b>——弱底噪的判据（原 isBufferThinning 与
	 * isJitterBufferThinned 两道瞬时判据合并到这里）。
	 *
	 * <h2>为什么必须先平滑水位（本轮修「常驻底噪」的根因）</h2>
	 * 服务端按实时节奏<b>整块</b>推送（约 250ms 一块），客户端缓冲水位是锯齿形的：
	 * 一块到达时顶到起播水位（约 300~550ms），随后被声卡连续抽走一整块的量，
	 * 落到 50~300ms 再被下一块顶回去。也就是说<b>健康链路的瞬时水位，
	 * 每个周期都会掉到近期峰值的 55% 以下</b>。
	 *
	 * <p>旧判据拿瞬时深度跟「峰值棘轮基线」比（掉到 55% 报警）、
	 * 或跟「目标的两成」比（起播水位 300ms 时两成只有 200ms，谷值经常更低），
	 * 等于<b>每个推送周期都判一次「正在变薄」</b>。于是 {@code healthyBlocks}
	 * 永远攒不满 25，「弱底噪」（40% 强度）被永久粘住——常驻嘶声。
	 * 这与网络质量<b>无关</b>：本地回环、单机也必然复现，
	 * 正是用户反馈的「常驻底噪，但我在本地回环服务器上」。
	 *
	 * <h2>现在的判据</h2>
	 * 先对水位做时间常数约 200ms 的一阶低通（见 {@link #DEPTH_SMOOTH_ALPHA}），
	 * 锯齿被抹平（衰减到约 ±8%），平滑值稳定停在周期均值上：
	 * <ol>
	 *   <li><b>相对变薄</b>：平滑值低于稳态基线（同样基于平滑值棘轮）的
	 *       {@link #BUFFER_THIN_PERMILLE}——真实断供在深缓冲下 300~400ms 内
	 *       即可检出，弱底噪能赶在节目静音之前渐起；</li>
	 *   <li><b>绝对见底</b>：平滑值低于 {@link #ABSOLUTE_THIN_FRAMES}
	 *       ——平均缓冲真的只剩「一块不到」。</li>
	 * </ol>
	 * 正常播放时两道都判「健康」；只有数据真的停了，平滑值才会跌破阈值。
	 *
	 * <p>没有音源时沿用旧语义：玩家还想听 = 不健康（音源被移除是明确异常）。
	 * 有音源但都还在攒起播缓冲时不报警——真断供会走「无数据」档
	 * （starved / consecutiveUnderruns），那里有更响的提示。
	 */
	private boolean isBufferUnhealthy() {
		boolean any = false;
		boolean primed = false;
		boolean thin = false;
		synchronized (this.lock) {
			for (SourceBuffer buffer : this.sources.values()) {
				any = true;
				if (!buffer.primed) {
					continue;
				}
				primed = true;
				int depth = buffer.length / OUT_CHANNELS;
				if (!buffer.smoothInit) {
					// 首次观察直接取当前深度，不从 0 爬升
					buffer.smoothFrames = depth;
					buffer.smoothInit = true;
				}
				buffer.smoothFrames += (depth - buffer.smoothFrames) * DEPTH_SMOOTH_ALPHA;
				// 稳态基线只跟随<b>平滑值</b>：变厚立即跟随、变薄缓慢下移（1/1024，
				// 半衰期约 14 秒）。跟随瞬时峰值会把整块到达的最高点当成稳态，
				// 之后每个周期的正常回落都会被误判成"正在变薄"——旧实现的死因。
				if (buffer.smoothFrames > buffer.steadyFrames) {
					buffer.steadyFrames = Math.round(buffer.smoothFrames);
				} else {
					buffer.steadyFrames -= Math.max(1, buffer.steadyFrames / 1024);
				}
				thin |= buffer.smoothFrames * 1000F < buffer.steadyFrames * BUFFER_THIN_PERMILLE
						|| buffer.smoothFrames < ABSOLUTE_THIN_FRAMES;
			}
		}
		if (!any) {
			// 没有音源 = 拿不到数据 = 不健康（玩家还想听时）。
			// 空选/关机时 playbackWanted 为 false，安静是正确行为。
			return this.playbackWanted;
		}
		return primed && thin;
	}

	/**
	 * 平滑系数（每写块 0.1 → 时间常数约 <b>200ms</b>）。
	 *
	 * <p>旧值 0.02（时间常数 1 秒）在客户端直连架构下有一个致命滞后：
	 * 上游断供后缓冲在几百毫秒~1 秒内排空，而平滑值要滞后约 1 秒才跟上——
	 * 「变薄」永远在<b>静音之后</b>才被发现，弱底噪根本没机会在节目还在播的时候
	 * 渐起（用户实测：噪声在缓冲没了、没声音了才出来）。
	 *
	 * <p>取 0.1 的安全余量：对自定义电台约 250ms 的整块推送锯齿（4Hz 基频、
	 * 峰谷 ±40%），一阶低通衰减到约 ±8%，平滑值仍稳稳贴着周期均值，
	 * 距 {@link #BUFFER_THIN_PERMILLE} 阈值有一倍以上余量，不会误报；
	 * 而真实断供在深缓冲（约 1s）下 300~400ms 内即可检出，
	 * 弱底噪能在节目还出声时就开始渐起。
	 */
	private static final float DEPTH_SMOOTH_ALPHA = 0.1F;

	/**
	 * 「绝对见底」线（帧，125ms）：平滑水位低于它说明平均缓冲只剩不到一块的量，
	 * 丢一个包就断。正常播放的周期均值最低也有约 175ms，不会误触。
	 */
	private static final int ABSOLUTE_THIN_FRAMES = OUT_RATE / 8;

	/**
	 * 判定「相对稳态变薄」的阈值（千分比）。
	 *
	 * <p>取 <b>650‰</b>（即平滑水位掉到稳态的 65% 以下）：配合 200ms 的平滑
	 * 时间常数，真实断供能更早被检出（节目还在出声时弱底噪就开始渐起）；
	 * 而平滑后的正常水位与均值几乎重合（≈1000‰，锯齿衰减后最低约 850‰），
	 * 距阈值仍有明显余量，不会误报。
	 */
	private static final int BUFFER_THIN_PERMILLE = 650;

	/** 弱底噪强度（相对卡住时的满强度）。够听出「信号不稳」又不盖过节目。 */
	private static final float STUTTER_LEVEL = 0.4F;

	/**
	 * 滑动窗口长度（块数）：约 240ms。
	 *
	 * <p>比早期的 0.5 秒更短，让「卡顿结束」能被快速感知——
	 * 窗口太长会让网络恢复后底噪还持续响很久。
	 */
	private static final int THIN_WINDOW_BLOCKS = 12;
	/** 窗口内出现多少次「见薄」才算卡顿。 */
	private static final int THIN_ALERT_COUNT = 3;
	/** 每个窗口衰减掉的计数：让卡顿痕迹在 2~3 个窗口内消失（约 0.5 秒）。 */
	private static final int THIN_DECAY = 2;

	/** 当前窗口内累计的「见薄」次数（带衰减）。 */
	private int recentThinnedBlocks;
	/** 窗口计数器。 */
	private int thinWindowCounter;

	/**
	 * 本流是否<b>已经真的出过声</b>。
	 *
	 * <h2>为什么需要这道门</h2>
	 * 「网络差底噪」的判据是"连续多少块没有节目音频"（{@link #updateNetworkNoise()}）。
	 * 但有一条**合法**的连续无音频窗口：起播/换台后抖动缓冲正在预热，
	 * {@code SourceBuffer.primed} 还是 false，必须攒够 {@code startupTargetFrames()}
	 * （约 300 ms）才第一次出声。这段窗口里 {@code any == false} 是<b>完全正常</b>的，
	 * 却会被记成连续欠载；再叠加 {@code weakNoiseHeld} 的粘性
	 * （{@link #HEALTHY_RESTORE_BLOCKS} = 25 块才撤），
	 * 结果是每次正常起播/换台都先响约 1 秒假底噪 —— 那是把"故障提示"变成了"起播音效"。
	 *
	 * <p>所以：<b>只有本流真的出过声之后</b>，"没声音"才可能是故障。
	 * 在此之前的所有欠载一律不计入故障判定。
	 *
	 * <p><b>但服务端明确上报的故障不受此门限制</b>（{@link #silenceForFailure()} 会直接
	 * 打开 {@link #faultReported}）：那种场景不需要我们用欠载去"推测"链路坏了，
	 * 服务端已经告诉我们了。这也让"订阅成功但一个音频块都还没来就失败"能正确发声。
	 */
	private boolean streamFlowing;

	/**
	 * 服务端（或客户端本地判定）<b>明确上报</b>过故障。
	 *
	 * <p>与 {@link #streamFlowing} 的区别是"推断"与"被告知"：
	 * 前者是我们从欠载推出来的，后者是 {@code S2C_AUDIO_STATUS} 或本地 TIMEOUT 给的结论。
	 * 明确故障不需要等 {@code streamFlowing} 打开就能发声。
	 */
	private volatile boolean faultReported;

	/**
	 * 故障提示音<b>是否允许发声</b>——两条混音路径共用这<b>一个</b>判据。
	 *
	 * <p>必须同时满足：
	 * <ol>
	 *   <li>{@code playbackWanted}：玩家还想听这个台。不满足就是"用户要求安静"，
	 *       任何提示音都不许响（S5/S7 场景，必须绝对 0 帧）；</li>
	 *   <li>{@link #streamFlowing} 或 {@link #faultReported}：本流要么已经真的出过声
	 *       （所以"现在没声"是异常），要么服务端已经明确告诉我们它坏了
	 *       （所以不需要我们用欠载去推测）。<b>两者都不满足 = 还在预热</b>，
	 *       此时响底噪就是"每次起播先沙一秒"。</li>
	 * </ol>
	 *
	 * <p>把判据收在一个函数里，是为了避免"正常路径判一套、静音分支判另一套"
	 * ——那正是 HANDOVER 第十二节反复踩坑的形状。
	 */
	private boolean faultSignalAllowed() {
		return this.playbackWanted && (this.streamFlowing || this.faultReported);
	}

	/** 当前是否处于「持续故障」状态（供状态查询）。 */
	private volatile boolean failingNow;

	/**
	 * 故障是否仍在持续。
	 *
	 * <p>供 UI/渲染层判断是否该显示故障指示。
	 */
	public boolean isFailingNow() {
		return this.failingNow;
	}

	/**
	 * 主动结束底噪（网络恢复 / 切台 / 空选时调用）。
	 *
	 * <p>这是底噪唯一的「停止」入口——正常播放时 {@link #updateNetworkNoise()}
	 * 因为连续有数据会把目标设为 0，但那条路径只在“确实收到数据”时生效；
	 * 本办法用于「明确知道故障已结束」的场景，确保立刻安静。
	 */
	/**
	 * 设置「是否仍有播放意图」。
	 *
	 * <p>由 {@link ClientAudioPlayer} 在流存在时置 true、全部停止时置 false。
	 * 用于区分「异常断流」与「正常空选」——前者该响底噪，后者该安静。
	 */
	public void setPlaybackWanted(boolean wanted) {
		this.playbackWanted = wanted;
		if (!wanted) {
			// 意图撤销 = 用户要求安静。这里立刻把故障层收干净，不必等混音线程下一个块，
			// 否则「按下停止」与「最后一声沙」之间还会差一个 20ms 块。
			this.faultReported = false;
			this.failingNow = false;
			this.consecutiveUnderruns = 0;
			this.weakNoiseHeld = false;
			this.networkNoise.reset();
		}
	}

	/**
	 * 标记「服务端/本地已明确上报故障」，让故障提示音可以不等 {@code streamFlowing} 就发声。
	 *
	 * <p>用于服务端报 {@code S2C_AUDIO_STATUS != OK} 与本地 TIMEOUT 判定这两条路径：
	 * 它们不需要我们用"欠载"去推测链路坏了，结论已经给出来了。
	 * 因此即使本流一个音频块都还没到（{@code streamFlowing == false}），也该响。
	 */
	public void markFaultReported() {
		this.faultReported = true;
	}

	/**
	 * 链路已恢复：撤销故障状态，并让底噪自然淡出。
	 *
	 * <p>与 {@link #clearNetworkNoise()} 的区别：后者是"立刻闭嘴"（切台/空选用），
	 * 本方法是"故障结束"——底噪按它自己的落音速率淡出，听感是"沙声慢慢退去"而不是硬切。
	 * 两者语义不同，所以拆成两个入口（HANDOVER 第十二节）。
	 */
	public void onLinkRecovered() {
		this.faultReported = false;
		this.consecutiveUnderruns = 0;
		this.weakNoiseHeld = false;
		this.healthyBlocks = 0;
		this.networkNoise.updateTarget(0.0F);
	}

	public void clearNetworkNoise() {
		this.consecutiveUnderruns = 0;
		this.failingNow = false;
		this.faultReported = false;
		this.starvedSinceMs = 0L;
		// 卡顿计数也要清：否则「网络恢复」后旧的卡顿历史还会让底噪响一阵
		this.recentThinnedBlocks = 0;
		this.thinWindowCounter = 0;
		this.networkNoise.reset();
	}

	// ------------------------------------------------------- 过渡静音窗（切歌/切台）

	/**
	 * 一次「换曲 / 换台 / 重载」过渡的默认静音窗长度（毫秒）。
	 *
	 * <p>取值依据：空窗 = 拆解码器 + {@link #flush()} + 重新攒够抖动缓冲。
	 * 最后一项由 {@code startupTargetFrames()} 决定（默认约 300ms），
	 * 再叠加解码器重建与首帧解码的开销，实测 300~700ms。
	 * 取 900ms 留余量，又不至于把真正的故障也一起盖掉。
	 */
	public static final int TRANSITION_SILENCE_MILLIS = 900;

	/**
	 * 过渡静音窗的<b>总上限</b>（毫秒）：从这一窗第一次打开起算，最多掩护这么久。
	 *
	 * <h2>为什么必须有上限</h2>
	 * 窗口可以被反复延长：服务端在换曲预载期间会连发「空音量包」
	 * （{@code S2C_AUDIO_SILENCE}），每包都把窗口往后推一点。
	 * 若没有总上限，上游彻底卡死时这些包会一直来，底噪就<b>永远不会响</b>——
	 * 玩家听到的是无限静音，比听到底噪更糟（无法判断"是在加载还是坏了"）。
	 *
	 * <p>取 3000ms：正常换曲空窗（300~900ms）绰绰有余，
	 * 而且远小于客户端判 {@code STALL_TIMEOUT} 的 4 秒——
	 * 也就是说「窗口到顶」总是早于「客户端判超时」，两者不会打架。
	 */
	public static final int TRANSITION_SILENCE_MAX_MILLIS = 3000;

	/**
	 * 刚开始掉数据时，先<b>闭着嘴</b>忍这么久（毫秒），之后才允许响底噪。
	 *
	 * <h2>为什么必须先静音，而不是一饿就响</h2>
	 * 用户实录（14:15:08 起 17.5 秒）里那 850ms 底噪是硬证据：
	 * 10.93s 起 4~16kHz 噪声底在 50ms 内从 -22 dB 跳到 -8.5 dB，
	 * 之后 11.00~11.80s 一直死平在 -8.5±0.5 dB，11.85s 开始淡出、11.90s 归零。
	 * 而服务端日志在 14:15:18~19（对应 10.9~11.9s）<b>一条事件都没有</b>——
	 * 没有切曲、没有换台，纯客户端饿出来的。清理它的是玩家在 14:15:20
	 * 手动 {@code radio reload}。
	 *
	 * <p>也就是说「起播/换曲的空窗」和「网络真的坏了」在头几百毫秒里
	 * 根本没法区分，而现在一饿就按满强度响，等于把前者也当成后者。
	 * 真实故障是持续几秒的，600ms 的宽限不会漏报，却能让切曲空窗保持干净。
	 *
	 * <p>取值 600ms：比实测空窗（约 300~900ms）略短，保证
	 * 「真空窗跨完还不恢复」时仍会响；又明显长于单块抖动（20ms）与
	 * 欠载计数阈值（{@link #UNDERRUN_ALERT_BLOCKS} 约 160ms）。
	 */
	public static final int STARVE_GRACE_MILLIS = 600;

	/**
	 * 本次「连续无数据」的起始时刻（0 表示当前并不处于连续无数据状态）。
	 *
	 * <p>每个有数据的块都会把它清零，因此它衡量的是<b>不间断</b>的饥饿时长，
	 * 而不是累计值——零星卡顿永远不会触发封顶。
	 */
	private volatile long starvedSinceMs;

	/**
	 * 「过渡静音窗」结束时刻（{@link System#currentTimeMillis()} 口径）。
	 *
	 * <h2>它解决什么</h2>
	 * 切歌 / 切台 / {@code /radio reload} 时，客户端会主动
	 * {@code stream.close()} + {@code PcmSink.flush()}，于是混音器接下来有
	 * <b>几百毫秒没有任何音源供上数据</b>。这段窗口在
	 * {@link #updateNetworkNoise()} 眼里与「丢包」<b>完全一样</b>：
	 * <ul>
	 *   <li>{@code starved = true} → {@code consecutiveUnderruns} 每块 +1；</li>
	 *   <li>8 块（约 160ms）后 {@code failing = true} → 底噪<b>直接拉到满强度</b>；</li>
	 *   <li>此时 {@code streamFlowing} 早就是 true，预热门根本拦不住；</li>
	 *   <li>抖动缓冲被 flush 归零，{@code isBufferUnhealthy()} 判「不健康」
	 *       → {@code weakNoiseHeld} 也粘住，
	 *       节目接上后还要再沙 {@link #HEALTHY_RESTORE_BLOCKS} 块（约 500ms）才退。</li>
	 * </ul>
	 * 听感正是「一换歌就沙一阵」——这不是弱网，是<b>我们自己制造的</b>空窗。
	 *
	 * <h2>窗内做什么</h2>
	 * 一律按<b>数字静音</b>处理：不判欠载、不 latch 弱底噪、目标增益归零。
	 * 混音器照常把全 0 的 PCM 帧写给声卡（{@link #toBytes} 输出幅度 0，
	 * 声卡时钟因此不停），跳变仍由调台噪盖住。
	 *
	 * <p>窗一过判据立刻恢复——真断线仍在约 160ms 后响底噪，不会被永久掩盖。
	 *
	 * <p><b>唯一例外</b>：{@code faultReported}（服务端/本地<b>明确告知</b>的故障）
	 * 优先级更高，窗内也照响。重载后若服务端已报 NOSIGNAL，不该被一次切台顺手盖掉。
	 */
	private volatile long transitionSilenceUntil;

	/** 本窗的总上限时刻；{@code 0} 表示尚未开过窗。见 {@link #TRANSITION_SILENCE_MAX_MILLIS}。 */
	private volatile long transitionSilenceCapUntil;

	/**
	 * 打开 / 延长一个过渡静音窗（默认 {@link #TRANSITION_SILENCE_MILLIS}）。
	 *
	 * <p>必须在「已经决定要 flush / 已经 flush」<b>之后</b>调用：
	 * 顺序反了的话噪声会先响起来再被收掉，听感是切换瞬间「沙」一下。
	 *
	 * <h2>可以重复调用</h2>
	 * 服务端换曲预载期间会连发空音量包，每包都调一次这里；
	 * 同一窗内的重复调用表现为<b>延长</b>，而不是重新起算——
	 * 总时长仍受 {@link #TRANSITION_SILENCE_MAX_MILLIS} 约束。
	 *
	 * <p><b>线程</b>：主线程与音频摄入线程都可以调用。只写几个 volatile 时间戳
	 * 与混音线程独占的计数器，与 {@link #clearNetworkNoise()} 的线程纪律一致。
	 */
	public void beginTransitionSilence() {
		this.beginTransitionSilence(TRANSITION_SILENCE_MILLIS);
	}

	/**
	 * 打开 / 延长一个指定长度的过渡静音窗。
	 *
	 * @param millis 本次希望掩护的时长（毫秒）；{@code <= 0} 时退回默认值
	 */
	public void beginTransitionSilence(int millis) {
		int span = millis > 0 ? millis : TRANSITION_SILENCE_MILLIS;
		long now = System.currentTimeMillis();
		if (now >= this.transitionSilenceCapUntil) {
			// 上一窗已经彻底结束（或从未开过）：重新起算总上限。
			// 判据用 cap 而不是 until——until 会被「延长」不断推后，
			// 用它会导致一窗接一窗永远开不完。
			this.transitionSilenceCapUntil = now + TRANSITION_SILENCE_MAX_MILLIS;
		}
		long until = Math.min(now + span, this.transitionSilenceCapUntil);
		this.transitionSilenceUntil = Math.max(this.transitionSilenceUntil, until);
		this.consecutiveUnderruns = 0;
		this.weakNoiseHeld = false;
		this.healthyBlocks = 0;
		// 卡顿滑动窗口也要清：否则窗一过，flush 前攒下的「见薄」痕迹
		// 还会让弱底噪再响一阵，正好落在新节目的开头。
		this.recentThinnedBlocks = 0;
		this.thinWindowCounter = 0;
		this.failingNow = false;
		// 切换窗内的「无数据」是我们自己 flush 出来的，不能算进死流计时，
		// 否则窗一结束就可能已经攒够封顶时长。
		this.starvedSinceMs = 0L;
		// <b>立刻收声，不能只 updateTarget(0)</b>：落音步长约 0.014/块，
		// 从满强度淡出要 1.5 秒——噪声会一直盖到新节目里。
		// reset() 是瞬时归零，只用于换台这种「上一段必须立刻消失」的场景。
		this.networkNoise.reset();
	}

	/**
	 * 是否仍在过渡静音窗内。
	 *
	 * <p>只在混音线程与主线程读同一个 volatile 时间戳，不需要锁。
	 */
	public boolean inTransitionSilence() {
		long until = this.transitionSilenceUntil;
		return until > 0L && System.currentTimeMillis() < until;
	}

	/**
	 * 连续多少个写块没有数据才判定为「真的掉数据」。
	 *
	 * <p>一块约 20ms，8 块约 160ms。这个值要在两个方向上都留余量：
	 * 太小会被直播流的正常块抖动误触发，太大会让真断线迟迟不提示。
	 */
	private static final int UNDERRUN_ALERT_BLOCKS = 8;
	/** 上次打印「缓冲溢出」告警的时刻，用于限流（每次溢出都打会刷屏）。 */
	private volatile long lastOverflowLogAt;

	/** 当前已连续欠载的块数。 */
	private int consecutiveUnderruns;
	private long lastUnderrunsSeen;

	/**
	 * 本块是否有音源供上数据。
	 *
	 * <p>由 {@code mixLoop} 在调 {@link #updateNetworkNoise()} 之前设置。
	 * 早期是让 {@code updateNetworkNoise()} 自己去读 {@code underruns} 计数器的
	 * <b>块内增量</b>来判断，但静音分支并不 {@code incrementAndGet()}，
	 * 于是它会把"静音"读成"本块没欠载 = 健康"从而撤销底噪。
	 * 改成显式输入后，两条路径可以用同一套判据，而 {@code underruns()} 这个
	 * 对外统计口径也保持原样（不为了内部判定去污染它）。
	 */
	private boolean starved;

	/**
	 * 浮点混音结果 → 16-bit 小端字节（含饱和）。
	 *
	 * <p>正常混音与「没有音源供数」两条路径共用，保证幅度行为一致。
	 */
	private void toBytes(float[] mix, byte[] out) {
		for (int i = 0; i < mix.length; i++) {
			int v = Math.round(mix[i] * 32767.0F);
			if (v > 32767) {
				v = 32767;
			} else if (v < -32768) {
				v = -32768;
			}
			out[i * 2] = (byte) (v & 0xFF);
			out[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
		}
	}

	/** 把所有音源的声像/距离向目标值平滑一步（锁外执行）。 */
	private void smoothAllSpatial() {
		for (Long key : this.spatialCache.keySet()) {
			float[] snapshot = this.spatialCache.get(key);
			if (snapshot != null) {
				this.spatialCache.put(key, this.smoothSpatial(key, snapshot));
			}
		}
	}
	/** 写入声卡。 */
	private void write(byte[] data) {
		SourceDataLine current = this.line;
		if (current == null) {
			return;
		}
		try {
			current.write(data, 0, data.length);
		} catch (Exception e) {
			SmileSRadio.LOGGER.debug("写入声卡失败", e);
		}
	}

	/** 按固定格式打开声卡（只打开一次）。 */
	private void ensureLine() throws Exception {
		if (this.line != null) {
			return;
		}
		AudioFormat fmt = new AudioFormat(OUT_RATE, 16, OUT_CHANNELS, true, false);
		DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
		SourceDataLine newLine = (SourceDataLine) AudioSystem.getLine(info);
		// <b>设备缓冲与 bufferMillis 解耦</b>：这里只留 DEVICE_BUFFER_MILLIS。
		//
		// 之前这里按 bufferMillis（默认 1000ms）开缓冲，等于让声卡里常驻一整秒
		// 「声像已经算死」的音频——转头要等它播完才听得出方向变化。
		// 抗抖动不靠设备缓冲，改由混音前的 SourceBuffer 负责（见 jitterTargetFrames()）。
		int deviceFrames = Math.max(DEVICE_BUFFER_FRAMES,
				OUT_RATE * DEVICE_BUFFER_MILLIS / 1000);
		newLine.open(fmt, deviceFrames * OUT_CHANNELS * 2);
		newLine.start();
		this.line = newLine;
		this.applyVolume();
		// 报设备<b>实际</b>拿到的缓冲（部分驱动会向上取整），别报我们请求的值
		SmileSRadio.LOGGER.info("声卡已打开：{}Hz {}ch，设备缓冲 {}ms（抖动缓冲 {}ms）",
				OUT_RATE, OUT_CHANNELS,
				newLine.getBufferSize() / OUT_CHANNELS / 2 * 1000 / OUT_RATE,
				Math.max(JITTER_MIN_MILLIS, Math.min(JITTER_MAX_MILLIS, RadioClientConfig.get().bufferMillis)));
	}

	private void applyVolume() {
		SourceDataLine current = this.line;
		if (current == null || !current.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
			return;
		}
		try {
			FloatControl gain = (FloatControl) current.getControl(FloatControl.Type.MASTER_GAIN);
			gain.setValue(0.0F);
		} catch (Exception e) {
			SmileSRadio.LOGGER.debug("设置音量失败", e);
		}
	}

	private void closeLine() {
		SourceDataLine current = this.line;
		this.line = null;
		if (current != null) {
			try {
				current.stop();
				current.flush();
				current.close();
			} catch (Exception ignored) {
				// 忽略
			}
		}
	}

	// ------------------------------------------------------------ 抖动缓冲

	/**
	 * 抖动缓冲目标帧数。
	 *
	 * <p><b>为什么要下移到混音之前</b>：早先靠设备缓冲（{@code bufferMillis}，默认 1000ms）
	 * 抗抖动，结果声卡里积压的永远是「声像已算好」的音频，转头必须等它播完才听到变化，
	 * 这就是「声音朝向自动切换有延迟」。现在设备缓冲固定只有 {@link #DEVICE_BUFFER_MILLIS}，
	 * 抗抖动改由每个音源在<b>混音前</b>攒数据承担——攒的这段还没乘声像，
	 * 随时可以按最新朝向混音，所以缓冲再深也不拖慢声像。
	 *
	 * <p>时长取 {@code RadioClientConfig.bufferMillis}（默认 1000，夹在 200~1500ms）。
	 * 「超过目标 2 倍才丢弃」本身就是丢数据的阈值，夹住上界是为了让它保持在
	 * 1.5 秒以内，不至于把延迟配额全吃掉。
	 */
	private int jitterTargetFrames() {
		int millis = Math.max(JITTER_MIN_MILLIS, Math.min(JITTER_MAX_MILLIS, RadioClientConfig.get().bufferMillis));
		return OUT_RATE * millis / 1000;
	}

	/**
	 * <b>起播</b>门槛帧数：比稳态目标低得多，只要求「不会一开口就断」。
	 *
	 * <p><b>为什么要和稳态目标分开</b>：稳态需要攒满 {@code bufferMillis}（默认 1 秒）
	 * 才抗得住抖动，但那是「已经在播」时的需求。若起播也等满 1 秒，
	 * 每次切台/切网络电台都要白等一秒——而这段等待期内播放器其实是空的，
	 * 没有任何抖动需要抵抗。
	 *
	 * <p>尤其是网络直播流：上游本身就带编码与网络缓冲（实测上游 ~400ms 才吐首字节），
	 * 客户端再攒满 1 秒等于<b>双重缓冲</b>，纯属白等。
	 *
	 * <p>因此策略是：<b>先攒够起播门槛就出声</b>，随后由解码线程继续补，
	 * 很快回到稳态目标；稳态抗抖能力完全不变。
	 */
	private int startupTargetFrames() {
		// 起播门槛永远不超过稳态目标：bufferMillis 调得很小时（如 200ms），
		// 稳态才 200ms，起播没理由比它更大（那样反而更慢）。
		return Math.min(this.jitterTargetFrames(), Math.max(OUT_RATE / 8, OUT_RATE * STARTUP_MILLIS / 1000));
	}

	/** 起播只要求约 300ms 音频：低于此容易一开口就被抽干。 */
	private static final int STARTUP_MILLIS = 300;

	/**
	 * 单个音源的积压上限（浮点数个数）= 抖动目标 + 2 秒余量。
	 *
	 * <p>上限按<b>每个音源各自</b>算，不共享：多台收音机同时播放时，
	 * 共用一个总额会让每台都存不下目标量，预热永远达不到、谁都不出声。
	 */
	private int maxBufferedFloats() {
		return (this.jitterTargetFrames() + OUT_RATE * 2) * OUT_CHANNELS;
	}

	/**
	 * 抖动缓冲的补水 / 超量丢弃，每个写块调一次。
	 *
	 * <p><b>循环体必须在 {@link #lock} 内</b>：{@link SourceBuffer} 的
	 * {@code length}/{@code samples} 是解码线程（{@code offer→append}）与混音线程
	 * （{@code compact}）共享的可变状态，这里的 {@code dropFront} 同样要整体前移
	 * {@code samples}。锁外做前移会与 {@code compact} 交错，同一段 PCM 被复制两次
	 * 或整段跳过（听感是周期性「跳一下」）；{@code length} 读到脏值还会让丢弃量算大，
	 * {@code System.arraycopy} 越界抛异常会把混音线程直接打死、声卡彻底没声。
	 *
	 * <p>持锁时间仍然很小：循环体只有整数比较与偶尔一次 {@code arraycopy}，
	 * 没有任何逐帧浮点运算（那是 HANDOVER 第七节第 11 条要求留在锁外的东西），
	 * 配置读取（{@link #jitterTargetFrames()}）也已在进锁前完成。
	 *
	 * <ul>
	 *   <li><b>起播</b>：攒够目标帧数才标记 {@code primed}，此前不参与混音；</li>
	 *   <li><b>补水</b>：低于目标只是正常消耗（每块都会掉一点），不打断出声；
	 *       真被抽干（不足一帧）才退回未预热，重新攒够目标再出声——
	 *       否则会变成「播 20ms 停一下」的周期性卡顿。</li>
	 *   <li><b>丢弃</b>：积压超过目标 2 倍（解码比播放快、或线程被系统挂起过）时，
	 *       从队首丢到目标附近，并把丢弃量<b>按整帧对齐</b>（HANDOVER 第七节第 7 条），
	 *       否则会把采样腰斩、左右声道互换。</li>
	 * </ul>
	 */
	private void refillJitterBuffers() {
		// 目标帧数在锁外算：jitterTargetFrames() 要读客户端配置，
		// 首次调用还可能触发配置加载，不该占着混音器的锁。
		int targetFloats = this.jitterTargetFrames() * OUT_CHANNELS;
		// 起播只要求攒够较小的一份（见 startupTargetFrames）；稳态仍以 targetFloats 为准。
		int startupFloats = this.startupTargetFrames() * OUT_CHANNELS;
		int excessLimit = targetFloats * 2;
		// 判定与变更（含 dropFront 的整体前移）必须与 offer()/compact() 互斥
		synchronized (this.lock) {
			for (SourceBuffer buffer : this.sources.values()) {
				int length = buffer.length;
				if (!buffer.primed) {
					// 首次起播用低门槛：先出声，再由解码线程补齐到稳态目标。
					// 欠载后重新预热也用这个门槛，避免每次断一下就等满 1 秒才恢复。
					if (length >= startupFloats) {
						buffer.primed = true;
					}
					continue;
				}
				if (length < OUT_CHANNELS) {
					// <b>真的抽干了</b>（连一帧都不剩）才算欠载：退回未预热、重新攒够目标再出声。
					//
					// 注意不能写成「低于目标就退回」：缓冲本来就是在目标上下摆动的，
					// 每消耗一个写块都会低于目标，那样会变成「播 20ms 停一下」的周期性卡顿。
					// 低于目标属于正常消耗，继续出声，由解码线程自然补回。
					buffer.primed = false;
					continue;
				}
				if (length > excessLimit) {
					// 积压翻倍：一次丢回目标附近（按整帧对齐，多丢一个写块的量）
					int dropFloats = ((length - targetFloats) / OUT_CHANNELS + WRITE_FRAMES) * OUT_CHANNELS;
					if (dropFloats > length) {
						dropFloats = length;
					}
					buffer.dropFront(dropFloats);
				}
			}
		}
	}

}
