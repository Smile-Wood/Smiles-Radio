package radiosmile.net;

/**
 * 自适应音频分块大小计算。
 *
 * <p>块大小不是固定值，而是随三个因素动态调整：
 * <ol>
 *   <li><b>客户端带宽上限</b>：由客户端配置给出（KB/s），换算成「每秒多少字节」</li>
 *   <li><b>发送节奏</b>：目标是每 {@link #TARGET_CHUNKS_PER_SECOND} 块/秒发一次，
 *       块太大导致延迟高，块太小则网络头开销占比过高</li>
 *   <li><b>网络健康度</b>：根据发送是否积压（队列长度）动态收缩或放大</li>
 * </ol>
 *
 * <p>所有调整都被夹在 [{@value #MIN_CHUNK}, {@value #MAX_CHUNK}] 内，
 * 且永不超过客户端声明的单块上限。
 *
 * <p><b>为什么放在服务端算</b>：分块大小决定服务端读多少字节、发多大的包。
 * 若由客户端自行决定，恶意客户端可以索要超大包放大流量；
 * 因此客户端只提供「带宽期望值」，实际块大小由服务端裁量。
 */
public final class AdaptiveChunkSizer {
	/** 每秒目标发送块数：4 块/秒 = 每块约承载 250ms 音频。 */
	public static final int TARGET_CHUNKS_PER_SECOND = 4;

	public static final int MIN_CHUNK = 512;
	public static final int MAX_CHUNK = 32768;

	/** 积压阈值：超过则收缩，低于则放大。 */
	private static final int BACKLOG_SHRINK = 8;
	private static final int BACKLOG_GROW = 2;

	private AdaptiveChunkSizer() {
	}

	/**
	 * 计算下一个分块应取的字节数。
	 *
	 * @param bandwidthKbps   客户端声明的带宽期望（KB/s）
	 * @param clientMaxChunk  客户端声明的单块上限（字节）
	 * @param pendingChunks   当前该玩家积压待发的块数
	 * @param previousChunk   上一次实际发送的块大小；{@code <= 0} 表示首次
	 * @return 本次应发送的字节数
	 */
	public static int nextChunkSize(int bandwidthKbps, int clientMaxChunk,
			int pendingChunks, int previousChunk) {
		int ceiling = clamp(clientMaxChunk, MIN_CHUNK, MAX_CHUNK);

		// 带宽换算：每秒字节数 / 每秒块数 = 每块字节数
		int perSecondBytes = Math.max(1, bandwidthKbps) * 1024;
		int bandwidthBased = perSecondBytes / TARGET_CHUNKS_PER_SECOND;

		int base = clamp(bandwidthBased, MIN_CHUNK, ceiling);

		// 无历史：直接用带宽推算值
		if (previousChunk <= 0) {
			return base;
		}

		int prev = clamp(previousChunk, MIN_CHUNK, ceiling);

		// 积压过多：收缩（每次最多减半），尽快追平
		if (pendingChunks >= BACKLOG_SHRINK) {
			return clamp(Math.max(MIN_CHUNK, prev / 2), MIN_CHUNK, ceiling);
		}

		// 积压很少：缓慢放大，逼近带宽推算值
		if (pendingChunks <= BACKLOG_GROW) {
			int grown = prev + Math.max(64, prev / 4);
			int capped = Math.min(grown, Math.max(base, prev));
			return clamp(capped, MIN_CHUNK, ceiling);
		}

		// 处于中间区间：向带宽推算值平滑靠拢
		int delta = (base - prev) / 2;
		return clamp(prev + delta, MIN_CHUNK, ceiling);
	}

	private static int clamp(int value, int min, int max) {
		if (max < min) {
			return min;
		}
		return Math.max(min, Math.min(max, value));
	}
}
