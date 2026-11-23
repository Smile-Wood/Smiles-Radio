package radiosmile.client.audio;

import java.util.Random;

/**
 * 「调台噪」发生器：换台 / 重载时盖住音频跳变与空窗。
 *
 * <h2>为什么需要它</h2>
 * 换台、换曲、/reload 时，服务端要重新定位、客户端要重置解码器与声卡缓冲，
 * 音频在交界处必然出现<b>突变</b>——「啪」的一声、几百毫秒空白、或者半拍错位。
 * 这些东西无法完全消除（是流式重连的固有代价），但可以<b>盖住</b>。
 *
 * <p>手法照抄真实收音机：调台时喇叭里是"沙沙"的调谐噪声，等信号锁定才冒出人声。
 * 听众会把这段噪声理解为"这个设备本来就这样"，而不觉得是 bug。
 *
 * <h2>为什么在混音器里合成，而不是播放一个原版音效</h2>
 * 原版音效走 OpenAL，与本模组的 {@code SourceDataLine} 是<b>两条独立的时钟与缓冲</b>，
 * 无法保证噪声正好盖住跳变那一刻，可能提前或滞后几百毫秒，反而更怪。
 * 在混音线程里直接叠加，噪声与音频共享同一条时间轴和同一个设备缓冲，覆盖是精确的。
 *
 * <h2>关键：时长必须跟随空窗，不能固定</h2>
 * 早期版本用固定 220ms。实测（录屏频谱分析）发现换台空窗长度是<b>可变</b>的：
 * 旧音频结束后到新音频接上，实测可达 600ms 以上——固定时长必然盖不住尾部，
 * 留下一段死寂，听感是「噪声突然响了一下，然后突然断掉、中间空一大块」。
 *
 * <p>因此改为<b>跟随模式</b>：由混音器每块告知「当前还有没有节目在出声」，
 * 只要没声音就持续维持噪声；有声音了才进入衰减。
 * 再叠加一个最长上限，避免上游彻底坏掉时噪声无限响下去。
 *
 * <h2>音量</h2>
 * 刻意做得比节目<b>轻</b>（实测旧参数比节目响 8dB，正是「突然出现」的原因）。
 * 它是背景填补，不是主角。
 */
public final class TuningNoise {
	/** 空窗期间维持的噪声强度（相对满幅）：比节目轻，只起填补作用。 */
	private static final float HOLD_LEVEL = 0.075F;
	/**
	 * 最长连续发声时间（毫秒）。
	 *
	 * <p>防止上游彻底坏掉（频道数据一直不来）时噪声无限响下去——
	 * 那种情况下应该安静地失败，而不是一直沙沙响。
	 */
	private static final int MAX_HOLD_MILLIS = 4000;
	/** 淡入时长（毫秒）：太短会有爆音。 */
	private static final int FADE_IN_MILLIS = 8;
	/** 衰减到静音所需时间（毫秒）：节目接上后自然退场。 */
	private static final int FADE_OUT_MILLIS = 90;

	/** 低通系数：越小越闷。留一点高频，听起来才像"沙"。 */
	private static final float LOWPASS = 0.35F;
	/** 高通系数：去掉低频轰鸣，避免听起来像雷声。 */
	private static final float HIGHPASS = 0.97F;

	private final Random random = new Random();
	private final int rate;

	/** 当前是否被触发、处于「跟随空窗」模式。 */
	private volatile boolean active;
	/** 触发时刻，用于最长时长保护。 */
	private long startedAt;
	/** 当前输出增益（0~HOLD_LEVEL），做淡入淡出。 */
	private float level;
	/** 淡入已进行的帧数。 */
	private int fadeInProgress;
	/** 连续多少块检测到节目音频：用于判定「真的接上了」，避免断续时噪声忽响忽停。 */
	private int stableAudioBlocks;

	// 两级滤波器状态
	private float lowpassState;
	private float highpassPrevIn;
	private float highpassPrevOut;

	public TuningNoise(int sampleRate) {
		this.rate = Math.max(8000, sampleRate);
	}

	/**
	 * 触发一次调台噪（进入跟随模式）。
	 *
	 * <p>重复触发是<b>重新开始</b>：连续快速换台时表现为「持续在调台」，
	 * 不会把多段噪声叠起来推高音量。
	 */
	public void trigger() {
		this.active = true;
		this.startedAt = System.currentTimeMillis();
		this.fadeInProgress = 0;
		this.stableAudioBlocks = 0;
		this.fadeoutHoldBlocks = 0;
		// 重置滤波器，避免上次残留的直流分量造成起始爆音
		this.lowpassState = 0.0F;
		this.highpassPrevIn = 0.0F;
		this.highpassPrevOut = 0.0F;
	}

	/** 当前是否在发声。 */
	public boolean isActive() {
		return this.active || this.level > 0.0005F;
	}

	/**
	 * 每写块调用一次，告知「这一块有没有节目在出声」。
	 *
	 * <p>这就是「跟随空窗」的核心：没声音就撑住，有声音就退场。
	 *
	 * <h2>为什么必须做「连续稳定」判定</h2>
	 * 只看单块是否出声是<b>错的</b>。换台时新音频到达是<b>断续</b>的：
	 * 解码器刚建好、抖动缓冲还在攒，块与块之间经常
	 * 「有 -> 没有 -> 有 -> 没有」地闪。
	 * 若见到一块有音频就立即开始淡出，噪声会跟着反复淡出/淡入，
	 * 听感正是「沙、停、沙、停」——用户实测反馈的「噪声会断」。
	 *
	 * <p>所以要求新音频<b>连续</b>出现 {@link #STABLE_AUDIO_BLOCKS} 块
	 * （约 300ms）才认定真的接上了；中途只要断一块就重置计数，
	 * 继续沙沙响。这样调台过程听起来是<b>一路连续</b>的沙沙声。
	 *
	 * @param hasAudio  当前块是否有节目音频
	 */
	public void followSilence(boolean hasAudio) {
		// 已经退干净了：本段调台结束（等下一次 trigger）
		if (!this.active && this.level <= 0.0005F && this.fadeoutHoldBlocks <= 0) {
			return;
		}
		boolean expired = System.currentTimeMillis() - this.startedAt > MAX_HOLD_MILLIS;
		if (expired) {
			this.active = false;
			this.stableAudioBlocks = 0;
			return;
		}
		if (hasAudio) {
			this.stableAudioBlocks++;
			// <b>只在「刚好达到」阈值那一刻进入观察期</b>，之后不再刷新。
			//
			// 若写成 >=，则连续有数据时每块都会把 fadeoutHoldBlocks 重设成满值，
			// 观察期永远走不完、active 永远为 true，噪声<b>永远不会淡出</b>。
			//
			// 进入观察期而不是立刻衰减，是为了消除起播瞬间的噪声中断：
			// 起播时「连续 300ms 有数据、随后抖动缓冲吃空又断一下」很常见，
			// 若立刻衰减，那一断正好撞在衰减路径上，噪声要重新爬升——
			// 听感就是「刚连上的第一下噪声断了」。
			if (this.stableAudioBlocks == STABLE_AUDIO_BLOCKS) {
				this.fadeoutHoldBlocks = FADEOUT_HOLD_BLOCKS;
				this.active = false;
			}
			return;
		}
		// 这一块没有音频：立刻重新撑住。
		//
		// <b>必须能「撤回」淡出</b>：早期版本一旦判定接上就把 active 置 false 且不再回改，
		// 若节目随即又断（直播流很常见），噪声已经停在淡出路径上、
		// 甚至彻底静音——听感就是「沙沙声中间断了一小下」（实测 5.80s 处中断 60ms）。
		// 现在只要再次检测到空窗，就重新激活并继续发声。
		this.active = true;
		this.stableAudioBlocks = 0;
		this.fadeoutHoldBlocks = 0;
	}

	/** 连续多少块有音频才算「真的接上了」。约 300ms（每块 20ms）。 */
	private static final int STABLE_AUDIO_BLOCKS = 15;

	/**
	 * 判定「接上」之后再观察多少块才真正开始衰减。
	 *
	 * <p>这是为了消除<b>起播瞬间的噪声中断</b>：起播时很常见「连续 300ms 有数据、
	 * 然后抖动缓冲吃空又断一下」。若一判定接上就立刻衰减，那一断就正好撞在
	 * 衰减路径上，噪声要重新爬升——听感是「刚连上的第一下噪声断了」。
	 *
	 * <p>取 10 块（约 200ms）：足够跨过起播阶段的典型抖动窗口，
	 * 又不会让调台噪拖太长。
	 */
	private static final int FADEOUT_HOLD_BLOCKS = 10;

	/** 衰减前的观察期剩余块数；大于 0 时说明已在「接上但还不敢退」的状态。 */
	private int fadeoutHoldBlocks;

	/**
	 * 生成单声道噪声样本。
	 *
	 * <p><b>只输出单声道样本，不做任何增益与声像</b>：
	 * 这些样本会被<b>预混进所属方块的音源缓冲区</b>，
	 * 之后与节目一起经过同一条增益链（方块音量 × 原版音量 × 距离衰减 × 声像）。
	 * 好处是效果音自动继承全部空间与音量语义，不必在这里复刻一遍
	 * ——历史上漏乘方块音量、漏乘距离衰减、漏乘声像，都是「另起一条路径」造成的。
	 *
	 * @param out 输出缓冲（单声道样本）
	 * @param frames 要生成的帧数
	 * @return 实际生成的样本数
	 */
	public int generateMono(float[] out, int frames) {
		if ((!this.active && this.level <= 0.0005F) || out == null || frames <= 0) {
			return 0;
		}
		int usable = Math.min(frames, out.length);
		if (usable <= 0) {
			return 0;
		}

		// 观察期内即使 active 已为 false 也【保持满强度】，不开始衰减。
		// 这是消除起播瞬间噪声中断的关键：判定接上后再等一小会儿，
		// 若期间又断，followSilence 会把 active 拉回 true，噪声全程不断。
		boolean holding = this.fadeoutHoldBlocks > 0;
		if (holding) {
			this.fadeoutHoldBlocks--;
		}
		float target = (this.active || holding) ? HOLD_LEVEL : 0.0F;

		// 每帧的增益步长：淡入用 FADE_IN，淡出用 FADE_OUT。
		//
		// 淡入必须比淡出<b>快得多</b>：淡入慢会在噪声该回来时留下一段渐强的空档，
		// 听感正是「断了一小下」。淡出慢则是为了自然退场，两者取向相反。
		boolean rampingUp = target > this.level;
		int rampFrames = rampingUp ? Math.max(1, this.rate * FADE_IN_MILLIS / 1000)
				: Math.max(1, this.rate * FADE_OUT_MILLIS / 1000);
		float step = HOLD_LEVEL / rampFrames;

		for (int i = 0; i < usable; i++) {
			// 增益向目标逼近：起音/落音都平滑，避免爆音与「咔」的断点
			if (this.level < target) {
				this.level = Math.min(target, this.level + step);
			} else if (this.level > target) {
				this.level = Math.max(target, this.level - step);
			}

			float noise = this.random.nextFloat() * 2.0F - 1.0F;
			// 一阶低通：削掉刺耳高频
			this.lowpassState += LOWPASS * (noise - this.lowpassState);
			float lowpassed = this.lowpassState;
			// 一阶高通：去掉低频轰鸣，只留中频"沙"感
			float highpassed = HIGHPASS * (this.highpassPrevOut + lowpassed - this.highpassPrevIn);
			this.highpassPrevIn = lowpassed;
			this.highpassPrevOut = highpassed;

			out[i] += highpassed * this.level;
		}
		return usable;
	}

	/** 立即静音（断开连接/释放设备时）。 */
	public void reset() {
		this.active = false;
		this.level = 0.0F;
		this.fadeInProgress = 0;
		this.stableAudioBlocks = 0;
		this.fadeoutHoldBlocks = 0;
	}
}
