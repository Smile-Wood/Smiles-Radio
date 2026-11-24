package radiosmile.client.audio;

import java.util.Random;

/**
 * 「网络差底噪」：链路吃紧时持续播放的轻微嘶声，提示玩家当前信号不稳。
 *
 * <h2>为什么要用声音提示</h2>
 * 网络变差时最容易出现的不是错误，而是「听着还行但开始断续」。
 * 玩家很难判断那是自己耳机的问题、还是服务器的问题、还是网络的问题。
 * 加一层<b>随链路质量变化的底噪</b>，就像真实收音机信号弱时的嘶声——
 * 不打断节目，但玩家能立刻感到「现在信号不好」。
 *
 * <h2>音量与链路质量的关系</h2>
 * 底噪响度由「抖动缓冲剩余深度」驱动，这是最直接的信号：
 * <ul>
 *   <li>缓冲接近目标 → 链路健康 → 完全静音（<b>正常时绝不能听到一丝底噪</b>）</li>
 *   <li>缓冲被吃薄 → 链路吃紧 → 底噪渐起</li>
 *   <li>缓冲见底 / 发生欠载 → 明显嘶声</li>
 * </ul>
 *
 * <p>用连续渐变而不是「好/坏」两档，避免在阈值附近反复开关造成听感上的抖动。
 * 档位之间的过渡也必须是<b>可听见的渐变</b>（起音约 600ms、落音约 1.5 秒）：
 * 缓存变薄时嘶声缓缓渗上来，恢复时缓缓退下去，任何时刻都不做响度跳变。
 *
 * <h2>性能约束</h2>
 * 单独一条混音线程分支、每 20ms 才调一次；内部只有一阶低通与乘法递推，
 * <b>没有逐采样三角函数、没有对象分配</b>（见 {@link TuningNoise} 的同类约束）。
 */
public final class NetworkNoise {
	/** 底噪最大幅度：存在感明确，但不至于盖过节目。 */
	private static final float PEAK = 0.12F;
	/** 低通系数：弱信号嘶声偏闷，不要像白噪那么刺耳。 */
	private static final float LOWPASS = 0.25F;
	/**
	 * 起音步长（每写块，约 20ms）：0 → 满档约 <b>600ms</b> 渐起。
	 *
	 * <p>旧值 0.14（0→弱档仅 60ms、弱档→满档仅 80ms）用户实测听感是
	 * 「响度跳变」——缓存变薄时完全感觉不出过渡，灯闪瞬间噪音猛地炸出来。
	 * 档位切换（静音→弱 0.4→满 1.0）必须是<b>可感知的渐变</b>。
	 */
	private static final float ATTACK = 0.033F;
	/**
	 * 落音步长（每写块，约 20ms）：满档 → 0 约 <b>1.5 秒</b>。
	 *
	 * <p>信号恢复后底噪按这个速率淡出（{@code PcmSink#onLinkRecovered}），
	 * 听感是「沙声慢慢退去」；旧值 0.06 约 340ms，退得太急。
	 */
	private static final float RELEASE = 0.014F;
	/** 单次掉数据提示的起始强度（相对最大值）。 */
	private static final float PULSE_LEVEL = 0.85F;

	private final Random random = new Random();
	/**
	 * 当前实际增益（0~1），向目标值平滑逼近。
	 *
	 * <p><b>{@code volatile}</b>：它会被混音线程之外的线程直接改写——
	 * 切歌/切台时 {@link PcmSink#beginTransitionSilence} 会立刻 {@code reset()}，
	 * 那一刻必须对混音线程<b>立即可见</b>，否则下一块还会按旧增益发出「沙」的一声。
	 * 只在这一处被读（每块一次，不在逐采样循环里），代价可忽略。
	 */
	private volatile float gain;
	/** 低通状态。 */
	private float lowpassState;

	/** 当前是否在出声（供混音器决定是否走这条分支）。 */
	public boolean isAudible() {
		return this.gain > 0.0005F;
	}

	/** 当前实际增益（0~1）。供诊断日志读取，混音判定只看 {@link #isAudible()}。 */
	public float gain() {
		return this.gain;
	}

	/**
	 * 打一次「掉数据」提示。
	 *
	 * <p>与 {@link #updateTarget} 的区别：这是<b>离散事件</b>（某一块真的没数据），
	 * 而不是连续状态。直接给一个满强度脉冲，再由 {@link #updateTarget} 按
	 * 落音速率慢慢退去——听感是「沙」地一闪然后淡出，符合丢包这类瞬时故障。
	 *
	 * <p>连续丢包时会被反复调用，这里做<b>上限钳制</b>：增益不会因为连打而累加，
	 * 只是不断把维持窗口往后推，最终表现为一段持续的轻嘶声。
	 */
	public void pulse() {
		this.gain = Math.max(this.gain, PULSE_LEVEL);
	}

	/**
	 * 根据链路质量更新目标增益。
	 *
	 * <p>由主线程/混音线程每写块调用一次即可，内部做了平滑，
	 * 所以即使调用频率变化也不会突变。
	 *
	 * @param target 目标强度 0~1：0 = 链路健康（静音），1 = 严重吃紧
	 */
	public void updateTarget(float target) {
		float clamped = Math.max(0.0F, Math.min(1.0F, target));
		// 起音慢、落音更慢：健康↔弱档↔满档之间全是可听见的渐变，绝不跳变；
		// 落音慢同时提供迟滞，网络瞬时就恢复时底噪不会立刻消失（避免一喘一喘的「噗噗」声）
		float step = clamped > this.gain ? ATTACK : RELEASE;
		if (this.gain < clamped) {
			this.gain = Math.min(clamped, this.gain + step);
		} else {
			this.gain = Math.max(clamped, this.gain - step);
		}
		// 归零附近直接吸附，保证「健康时绝对静音」
		if (this.gain < 0.001F) {
			this.gain = 0.0F;
		}
	}

	/**
	 * 生成单声道底噪样本（混音线程调用）。
	 *
	 * <p><b>不做增益与声像</b>：样本会被预混进所属方块的音源缓冲区，
	 * 之后与节目一起经过同一条增益链。详见 {@code TuningNoise#generateMono}。
	 *
	 * @param out    输出缓冲（单声道）
	 * @param frames 要生成的帧数
	 * @return 实际生成的样本数
	 */
	public int generateMono(float[] out, int frames) {
		if (!this.isAudible() || out == null || frames <= 0) {
			return 0;
		}
		int usable = Math.min(frames, out.length);
		float amp = PEAK * this.gain;
		for (int i = 0; i < usable; i++) {
			float noise = this.random.nextFloat() * 2.0F - 1.0F;
			// 一阶低通：去掉刺耳的高频，做成"沙沙"的弱信号声
			this.lowpassState += LOWPASS * (noise - this.lowpassState);
			out[i] += this.lowpassState * amp;
		}
		return usable;
	}

	/** 立即静音（断开连接/释放设备时）。 */
	public void reset() {
		this.gain = 0.0F;
		this.lowpassState = 0.0F;
	}
}
