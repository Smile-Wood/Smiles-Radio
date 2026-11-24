package radiosmile.radio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

import radiosmile.SmileSRadio;

/**
 * 播放列表的「按轮播放」时间轴。
 *
 * <h2>为什么是「轮」而不是一条无限循环</h2>
 * 早期把整个列表拼成一条<b>无限的</b>循环时间轴（{@code (now-epoch) % 总时长}）。
 * 那对 {@code sequence} 没问题，但 {@code random} 就成了「同一份打乱顺序永远重复」——
 * 听众很快会发现每轮顺序完全一样。
 *
 * <p>现在改为<b>按轮播放</b>：每轮是曲池的一个排列，<b>全部播完后生成新的一轮再继续</b>。
 * 随机模式每轮重新洗牌；顺序模式每轮仍是自然序（等价于原来的循环）。
 *
 * <h2>为什么不会内存泄漏</h2>
 * 「每轮列表」<b>不持久保存</b>：任意轮的顺序都是 {@code (seed, 轮次)} 的<b>纯函数</b>，
 * 需要时现算（O(曲池大小)）。只有最近几轮会被放进一个小 LRU 缓存以避免重复计算，
 * 所以内存占用与「已经播了多少轮」无关，长时间运行不会累积。
 *
 * <h2>为什么必须保持纯函数</h2>
 * 多人同步要求「同一时刻所有人听到同一首的同一位置」。
 * 因此轮次由 {@code elapsed / 每轮总时长} 得出，顺序由 {@code (seed, 轮次)} 唯一决定——
 * 任何客户端、任何时刻加入，算出的结果都一致。
 *
 * <h2>跨轮不重复（冷却）</h2>
 * 随机模式生成新一轮时，避免与<b>上一轮最后 {@link #MAX_COOLDOWN} 首</b>重复，
 * 这样两轮交界处不会出现「同一首歌刚播完马上又播」。
 * 曲池太小（{@code 5 首以内}）时按可用空间自动收窄冷却长度，无法满足的部分排除在外。
 *
 * <h2>时长必须按「转码后的 MP3」算</h2>
 * 实际推流用的是 {@code cache/baked/*.mp3}（模组内只推 MP3，见 {@link AudioFormat}）。
 * 时间轴必须与<b>真正发出去的字节</b>对应，否则每首歌的边界都会漂移。
 */
public final class PlaylistTimeline {
	/**
	 * 单首歌的时间轴条目。
	 *
	 * @param file           实际推流的文件（优先转码后的 MP3）
	 * @param durationMillis 该文件的播放时长（毫秒）
	 * @param byteSize       该文件的字节数，用于算整条列表的平均码率
	 */
	public record Track(Path file, long durationMillis, long byteSize) {
	}

	/** 跨轮冷却的目标首数（用户要求「5 首之内不重复」）。 */
	public static final int MAX_COOLDOWN = 5;

	/** 轮次缓存大小：只需覆盖「当前轮 + 前一轮」，多留两个以防并发/跳跃访问。 */
	private static final int ROUND_CACHE_SIZE = 4;

	/** 起点轮次的取值范围：够大且不会让后续加法溢出。 */
	private static final int ROUND_OFFSET_MODULUS = 1_000_003;

	/** 上一次构建选中的首曲，用于保证「重载后第一首一定换掉」。 */
	private static volatile Path lastFirstTrack;

	/** 曲池（去重后的实际推流文件，顺序由调用方的自然序/随机决定仅用于无随机时）。 */
	private final List<Track> pool;
	private final boolean random;
	private final long seed;
	/** 每轮总时长（毫秒）：所有轮都是同一曲池的排列，因此恒等。 */
	private final long roundTotalMillis;

	/** 最近几轮的已算好顺序（含累计时长）。LRU，容量恒定。 */
	private final Map<Integer, Round> roundCache = Collections.synchronizedMap(
			new LinkedHashMap<>(8, 0.75F, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<Integer, Round> eldest) {
					return size() > ROUND_CACHE_SIZE;
				}
			});

	/** 某一轮的具体顺序与累计时长。 */
	private record Round(int index, List<Track> order, long[] cumulative) {
	}

	private PlaylistTimeline(List<Track> pool, boolean random, long seed, Path avoidHead) {
		this.pool = List.copyOf(pool);
		this.random = random;
		this.seed = seed;
		// <b>起点轮次随机化</b>：只在打乱模式下有意义（顺序模式每轮都一样，
		// 从哪轮开始都会播同一首，改了也没有区别）。
		//
		// <p>取值必须满足两点：
		//   <li>同一实例内<b>稳定</b>——否则每次订阅都跳到不同的歌，播放会乱；
		//   <li>每次构建都<b>不同</b>——否则「刷新后第一首还是那首」。</li>
		//
		// <p>旧实现取 {@code now / 一小时} 再乘大奇数散开：它只能保证「不同小时」不同，
		// <b>一小时之内连续 /radio reload 抽到的起点轮次完全相同</b>，
		// 于是重载后顺序没变、第一首还是刚才那首——这正是玩家反馈的现象。
		// 现在改为每次构建现抽一个，并要求首曲避开「当前正在播的那首」
		// （见 pickFreshRoundOffset）。
		this.roundOffset = this.random ? pickFreshRoundOffset(avoidHead) : 0;
		long total = 0L;
		for (Track track : this.pool) {
			// 防御：时长未知（<=0）时给最小正数，否则整轮时长会变成 0、除零卡死。
			total += Math.max(1L, track.durationMillis());
		}
		this.roundTotalMillis = Math.max(1L, total);
	}

	/**
	 * 选一个「首曲不是指定那一首」的起点轮次。
	 *
	 * <p>只把轮次号换掉并不足以保证第一首变化：不同轮次仍可能抽出同一个第一首
	 * （概率约 1/曲池大小，小曲池上很容易撞）。因此抽到候选后直接算出该轮
	 * <b>实际播放顺序</b>（含跨轮冷却修正），比对首曲；相同就重抽，最多 32 次。
	 *
	 * <p>避让目标由 {@code avoidHead} 给出——重载时就是「此刻正在播的那一首」，
	 * 这样玩家听到的第一首一定换了；没有该信息（冷启动）时退回「上一次构建的首曲」。
	 * 同一实例内的结果被固化下来，多个订阅共享同一份顺序，多人同步不受影响。
	 */
	private int pickFreshRoundOffset(Path avoidHead) {
		if (this.pool.size() <= 1) {
			return ThreadLocalRandom.current().nextInt(ROUND_OFFSET_MODULUS);
		}
		// 有「当前正在播」就用它，否则退回「上一次构建的首曲」。
		Path forbidden = avoidHead != null ? avoidHead : lastFirstTrack;
		int candidate = 0;
		for (int attempt = 0; attempt < 32; attempt++) {
			candidate = ThreadLocalRandom.current().nextInt(ROUND_OFFSET_MODULUS);
			Path head = round(candidate).order().get(0).file();
			synchronized (PlaylistTimeline.class) {
				if (!head.equals(forbidden)) {
					lastFirstTrack = head;
					return candidate;
				}
			}
		}
		// 32 次都撞上（理论上只可能发生在曲池极小、且冷却把首曲锁死的情况下）：
		// 接受这个候选，至少轮次换了，静音或抛异常都更糟。
		return candidate;
	}

	/**
	 * 由曲池构建按轮时间轴。
	 *
	 * <p>{@code stationId} 参与缓存键：<b>每个频道各有一份时间轴</b>。
	 * 早期不区分频道，同一份目录被两个频道引用时会共用同一个实例
	 * （同一个起点轮次、同一份顺序），而且「单独刷新某个频道」也无法只清它自己。
	 *
	 * @param stationId 频道 id（缓存键的一部分，仅用于隔离，不参与顺序计算）
	 * @param files     曲池源文件（顺序只在无随机时作为基准）
	 * @param random    是否随机（{@code mode=random} 为 true）
	 * @param seed      随机种子；相同种子下所有客户端算出相同顺序
	 * @param avoidHead 需要避开的首曲（重载瞬间正在播的那首），可为 {@code null}
	 * @return 时间轴；曲池为空返回 {@code null}
	 */
	public static PlaylistTimeline of(String stationId, List<Path> files, boolean random, long seed,
			Path avoidHead) {
		if (files == null || files.isEmpty()) {
			return null;
		}
		String key = cacheKey(stationId, files, random, seed);
		PlaylistTimeline cached = CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		// 构建要逐首整文件扫时长（AudioDuration 逐帧解析），50 首约 0.75~1.5 秒。
		// 订阅发生在服务端主线程，因此按「源文件列表 + 各文件大小/修改时间」缓存。
		//
		// 关键：同一份音频可能同时存在 .ogg 源与 baked .mp3，
		// 若直接按源文件去重，播放列表里会出现「同一首歌的两个副本」——随机时就可能连着播。
		// 因此这里按<b>实际推流文件</b>去重。
		List<Track> tracks = new ArrayList<>(files.size());
		java.util.Set<Path> seen = new java.util.HashSet<>();
		int dropped = 0;
		for (Path file : files) {
			Path actual = streamFileOf(file);
			// <b>时间轴只接受 MP3</b>：不可播的曲目必须在这里剔除，而不是留给运行时跳过。
			//
			// 早期这里无条件 add，把"能不能播"全交给 PlaylistAudioSource 判断。
			// 一旦有曲目是未转码的 .m4s/.ogg，会同时踩两个坑：
			//   1. {@link AudioDuration} 只认 MP3 帧 → 时长 -1 → 兜底成 1ms
			//      → <b>后面所有曲目的起点全部错位</b>（这正是"时序完全乱"的成因）；
			//   2. 运行时 openTrack() 拒绝非 MP3 → advance() 反复跳曲，
			//      与时间轴算出的"第几首"彻底脱节。
			// 剔除后时间轴里每一首都是真实可播、且时长可信的，定位才有意义。
			if (!isMp3(actual)) {
				SmileSRadio.LOGGER.warn(
						"播放列表曲目缺少 MP3 转码产物，已从时间轴剔除（否则会导致时序错乱）：{}",
						file.getFileName());
				dropped++;
				continue;
			}
			Path identity = canonical(actual);
			if (!seen.add(identity)) {
				dropped++;
				continue;
			}
			tracks.add(new Track(actual, durationOf(actual), sizeOf(actual)));
		}
		if (tracks.isEmpty()) {
			SmileSRadio.LOGGER.error("播放列表没有任何可播曲目（全部缺少 MP3 转码产物）：{}",
					tracks.size());
			return null;
		}
		if (dropped > 0) {
			SmileSRadio.LOGGER.debug("播放列表去重：{} 个源文件指向同一份音频，已合并", dropped);
		}
		PlaylistTimeline timeline = new PlaylistTimeline(tracks, random, seed, avoidHead);
		CACHE.put(key, timeline);
		SmileSRadio.LOGGER.debug("播放列表：{} 首，每轮 {} ms，随机={} 冷却={} 首",
				tracks.size(), timeline.roundTotalMillis, random, timeline.cooldownSize());
		return timeline;
	}

	/** 是否为 MP3（时间轴唯一接受的格式）。 */
	private static boolean isMp3(Path file) {
		return file != null && file.getFileName() != null
				&& file.getFileName().toString().toLowerCase(java.util.Locale.ROOT)
						.endsWith(".mp3");
	}

	/** 规范化为用于判重的绝对路径。 */
	private static Path canonical(Path path) {
		try {
			return path.toAbsolutePath().normalize();
		} catch (Throwable t) {
			return path;
		}
	}

	/**
	 * 时间轴缓存。
	 *
	 * <p>键里带每个文件的大小与修改时间：源文件被替换（例如服主重新转码）时键会变，
	 * 自动失效，不需要额外的失效逻辑。
	 */
	private static final Map<String, PlaylistTimeline> CACHE = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * 清空时间轴缓存。
	 *
	 * <p><b>配置重载时必须调用</b>：时间轴实例里固化了
	 * {@link #roundOffset}（起点轮次随机偏移，见其说明）。
	 * 若复用旧实例，重载后起点轮次不会变——用户会看到
	 * 「重载了但打乱的第一首还是那首」。
	 *
	 * <p>另外这也顺带保证"改完配置立即生效"：曲目列表、时长都可能变了。
	 */
	public static void clearCache() {
		CACHE.clear();
	}

	/**
	 * 只清掉某个频道的时间轴缓存（{@code /radio reload <频道>} 使用）。
	 *
	 * <p>单频道刷新不能动全局缓存：其他频道仍在用旧实例播放，
	 * 缓存被清后它们的新订阅会拿到「另一轮」的顺序，与正在听的玩家对不上。
	 *
	 * @param stationId 频道 id
	 */
	public static void clearCacheFor(String stationId) {
		if (stationId == null) {
			return;
		}
		String prefix = stationId + "@";
		CACHE.keySet().removeIf(key -> key.startsWith(prefix));
	}

	private static String cacheKey(String stationId, List<Path> files, boolean random, long seed) {
		StringBuilder sb = new StringBuilder();
		// 频道 id 作为前缀：clearCacheFor() 靠它做前缀匹配。
		sb.append(stationId == null ? "" : stationId).append('@');
		sb.append(random ? "R" : "S").append(seed).append('#');
		for (Path file : files) {
			sb.append(file).append('|');
			try {
				sb.append(Files.size(file)).append('|').append(Files.getLastModifiedTime(file).toMillis());
			} catch (java.io.IOException e) {
				sb.append("0|0");
			}
			sb.append(';');
		}
		return sb.toString();
	}

	/**
	 * 取某个源文件「真正会被推流」的版本。
	 *
	 * <p>只认转码后的 MP3（见 {@link AudioFormat}）。
	 *
	 * <p><b>⚠ 隐式契约：返回值不保证可播。</b>转码产物不存在时这里<b>原样返回源文件</b>
	 * （可能是 .ogg / .flac）。这样做是为了让曲池的「首数」仍如实反映服主实际放的内容，
	 * 同时把「能不能播」留给唯一的地方判断——{@link #isPlayableAt(int, int)} 与
	 * {@code PlaylistAudioSource} 打开曲目时的 MP3 校验。
	 *
	 * <p>因此：<b>任何消费 {@link #fileAt(int, int)} 的代码都必须先检查
	 * {@link #isPlayableAt(int, int)}</b>，否则会把不可播文件直接推给客户端。
	 */
	private static Path streamFileOf(Path source) {
		String name = source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
		// 本身就是 MP3 就不必算整文件哈希去查缓存
		if (name.endsWith(".mp3")) {
			return source;
		}
		Path baked = AudioTranscoder.mp3For(source);
		return baked != null ? baked : source;
	}

	/**
	 * 取播放时长。
	 *
	 * <p>走到这里的文件<b>必然已是 MP3</b>（{@link #of} 已剔除其它格式），
	 * 因此 {@link AudioDuration} 一定能扫出帧。真扫不出来（文件损坏/被截断）
	 * 说明这个文件本身有问题，仍然返回 0 让构造时的下限兜底，
	 * 但会明确报错——而不是像早期那样把整条时间轴悄悄算错。
	 */
	private static long durationOf(Path actual) {
		long millis = AudioDuration.millis(actual);
		if (millis <= 0) {
			SmileSRadio.LOGGER.error(
					"MP3 时长解析失败（文件可能损坏或被截断），该曲在时间轴上按最小值处理：{}",
					actual.getFileName());
		}
		return millis;
	}

	/** 文件字节数；读不到返回 0。 */
	private static long sizeOf(Path actual) {
		try {
			return Files.isRegularFile(actual) ? Files.size(actual) : 0L;
		} catch (java.io.IOException e) {
			return 0L;
		}
	}

	/** 曲池大小。 */
	public int size() {
		return this.pool.size();
	}

	/** 每轮总时长（毫秒）。所有轮恒等。 */
	public long roundTotalMillis() {
		return this.roundTotalMillis;
	}

	/**
	 * 实际生效的跨轮冷却首数。
	 *
	 * <p>取 {@link #MAX_COOLDOWN}，但受曲池大小限制。两个约束必须同时满足：
	 *
	 * <ol>
	 *   <li><b>{@code n > 2g}</b>：修正只允许在「新轮前 g 位」与「{@code [g, n-g)} 区间」
	 *       之间交换，保证上一轮<b>尾部</b>（{@code [n-g, n)}）永不被它自己的修正改动。
	 *       于是算冷却时只需看「上一轮的基础打乱」尾部，
	 *       <b>无需递归回溯更早的轮次</b>——这是整个实现能 O(曲池) 且与轮数无关的关键。</li>
	 *   <li><b>{@code n ≥ 3g}</b>：伙伴区间长度 {@code n-2g} 至少要能容纳 g 个被换出的曲目，
	 *       否则必然有违禁曲目换不出去。实测 n=10、g=4 时伙伴区只有 2 个槽位，
	 *       40 轮里出现了 19 次重复——这条约束就是那次失败换来的。</li>
	 * </ol>
	 *
	 * 合起来即 {@code g ≤ n/3}。{@code 5 首不够}的曲池按用户要求自动收窄
	 * （「5 首不够随机的排除在外」）。
	 */
	public int cooldownSize() {
		int n = this.pool.size();
		if (!this.random || n <= 2) {
			return 0;
		}
		return Math.min(MAX_COOLDOWN, n / 3);
	}

	/**
	 * <b>起始轮次偏移</b>：让第一次播放不是固定从第 0 轮开始。
	 *
	 * <h2>为什么需要它</h2>
	 * 早期 "当前是第几轮" = {@code elapsed / roundTotalMillis}，而 {@code elapsed}
	 * 从服务端启动/重载那一刻算起——于是<b>每次都从第 0 轮开始</b>。
	 * 第 0 轮的顺序由 {@code (seed, 0)} 唯一决定（{@link #mixSeed}），
	 * 结果就是：<b>每次开服/重载后，打乱模式的第一首永远是同一首</b>。
	 * 用户实测反馈的「随机打乱每次第一个都是同一个音频」正是这个。
	 *
	 * <h2>怎么算</h2>
	 * 用一个<b>与种子无关、但每次启动都变</b>的值当起点轮次。
	 * 这里取「当前时间 / 一小时」——同一秒内不可能重复开服，
	 * 但也不需要精确：它只是把起点推到任意一轮上。
	 *
	 * <h2>为什么不会破坏多人同步</h2>
	 * 这个偏移在<b>服务端</b>构造时间轴时算一次并固化，
	 * 客户端不参与任何轮次计算（`PlaylistTimeline` 只存在于服务端）。
	 * 所有玩家拿到的都是服务端算好的同一份顺序，因此仍然完全同步。
	 *
	 * <p>注意：它<b>只影响"从哪一轮开始"</b>，不影响每一轮的内部顺序，
	 * 也不影响跨轮冷却逻辑（那段只看相邻轮次，与绝对轮号无关）。
	 */
	private final int roundOffset;

	/** 当前时刻落在第几轮（含启动偏移）。 */
	public int roundOf(long elapsedMillis) {
		return this.roundOffset
				+ (int) Math.floorDiv(Math.max(0L, elapsedMillis), this.roundTotalMillis);
	}

	/** 当前时刻在本轮内的位置（毫秒）。 */
	public long positionInRound(long elapsedMillis) {
		return Math.floorMod(Math.max(0L, elapsedMillis), this.roundTotalMillis);
	}

	/** 某轮的顺序（纯函数，带小 LRU 缓存，不随轮数累积内存）。 */
	private Round round(int roundIndex) {
		Round cached = this.roundCache.get(roundIndex);
		if (cached != null) {
			return cached;
		}
		List<Track> order = baseOrder(roundIndex);
		if (this.random) {
			applyCooldown(order, roundIndex);
		}
		long[] cumulative = new long[order.size() + 1];
		for (int i = 0; i < order.size(); i++) {
			cumulative[i + 1] = cumulative[i] + Math.max(1L, order.get(i).durationMillis());
		}
		Round built = new Round(roundIndex, List.copyOf(order), cumulative);
		this.roundCache.put(roundIndex, built);
		return built;
	}

	/** 某一轮「只按种子打乱、未做冷却修正」的顺序。纯函数。 */
	private List<Track> baseOrder(int roundIndex) {
		List<Track> order = new ArrayList<>(this.pool);
		if (this.random && order.size() > 1) {
			Collections.shuffle(order, new Random(mixSeed(this.seed, roundIndex)));
		}
		return order;
	}

	/** 把 (种子, 轮次) 混成一个稳定的随机源；不同轮次必然不同。 */
	private static long mixSeed(long seed, int roundIndex) {
		long z = seed + 0x9E3779B97F4A7C15L * (roundIndex + 1L);
		// xorshift 混淆，避免相邻轮次产生相关的序列
		z ^= (z >>> 30);
		z *= 0xBF58476D1CE4E5B9L;
		z ^= (z >>> 27);
		z *= 0x94D049BB133111EBL;
		z ^= (z >>> 31);
		return z;
	}

	/**
	 * 对刚打乱的新一轮做「跨轮不重复」修正。
	 *
	 * <p>禁止集合 = <b>上一轮基础打乱的最后 g 首</b>。
	 * 之所以能用「基础打乱」而不是「上一轮实际播放顺序」：本轮修正只交换
	 * {@code [0,g)} 与 {@code [g, P-g)}，而上一轮的尾部 {@code [P-g, P)} 不在其中，
	 * 所以上一轮实际尾部<b>就等于</b>它的基础尾部（前提 {@code P > 2g}，见 {@link #cooldownSize()}）。
	 *
	 * <p>这避免了「算第 r 轮需要第 r-1 轮、算第 r-1 轮又需要第 r-2 轮…」的无界递归，
	 * 使任意一轮都能 O(曲池大小) 直接算出——这正是「不持久保存每轮列表也不泄漏」的基础。
	 */
	private void applyCooldown(List<Track> order, int roundIndex) {
		int g = cooldownSize();
		if (g <= 0 || roundIndex <= 0) {
			return;
		}
		int n = order.size();
		List<Track> previousBase = baseOrder(roundIndex - 1);
		// 禁止集合：上一轮最后 g 首
		java.util.Set<Track> avoided = new java.util.HashSet<>(
				previousBase.subList(Math.max(0, n - g), n));

		// 只允许在 [g, n-g) 里找替换者，保证尾部不被改动。
		// 每个伙伴<b>只能被换一次</b>：否则两个违禁位可能挑中同一个槽位，
		// 第一次交换把违禁曲目换到那里、第二次又把它换回来，等于没修。
		int partnerFrom = g;
		int partnerTo = n - g;
		int partner = partnerFrom;
		for (int i = 0; i < g; i++) {
			if (!avoided.contains(order.get(i))) {
				continue;
			}
			while (partner < partnerTo && avoided.contains(order.get(partner))) {
				partner++;
			}
			if (partner >= partnerTo) {
				// 伙伴用尽（受 g <= n/3 保证正常不会发生）：保留该重复，
				// 属曲池过小时的已知下限，不继续破坏已排好的顺序。
				break;
			}
			Collections.swap(order, i, partner);
			partner++;
		}
	}

	/**
	 * 把「本轮内位置」换算成「第几首 + 歌内偏移」。
	 *
	 * @param roundIndex  轮次
	 * @param posInRound  本轮内位置（毫秒）
	 * @return {@code [曲目序号, 歌内偏移毫秒]}
	 */
	public long[] locate(int roundIndex, long posInRound) {
		Round current = round(roundIndex);
		if (current.order().isEmpty()) {
			return new long[]{0L, 0L};
		}
		long[] cumulative = current.cumulative();
		long pos = Math.floorMod(posInRound, Math.max(1L, cumulative[cumulative.length - 1]));
		int lo = 0;
		int hi = current.order().size() - 1;
		while (lo < hi) {
			int mid = (lo + hi + 1) >>> 1;
			if (cumulative[mid] <= pos) {
				lo = mid;
			} else {
				hi = mid - 1;
			}
		}
		long offset = pos - cumulative[lo];
		long duration = Math.max(1L, current.order().get(lo).durationMillis());
		if (offset >= duration) {
			offset = duration - 1;
		}
		return new long[]{lo, offset};
	}

	/** 本轮内的下一首序号（末尾回到 0，即进入下一轮）。 */
	public int nextIndexInRound(int roundIndex, int index) {
		int n = round(roundIndex).order().size();
		return n <= 0 ? 0 : Math.floorMod(index + 1, n);
	}

	/** 取某轮某首「实际推流」的文件。 */
	public Path fileAt(int roundIndex, int index) {
		Round current = round(roundIndex);
		if (current.order().isEmpty()) {
			return null;
		}
		return current.order().get(Math.floorMod(index, current.order().size())).file();
	}

	/** 取某轮某首的时长（毫秒）。 */
	public long durationAt(int roundIndex, int index) {
		Round current = round(roundIndex);
		if (current.order().isEmpty()) {
			return 0L;
		}
		List<Track> order = current.order();
		return Math.max(1L, order.get(Math.floorMod(index, order.size())).durationMillis());
	}

	/**
	 * 某轮某首的起始位置（本轮内毫秒）。
	 *
	 * <p>与 {@link #locate} 互逆：给 {@code index} 求「本轮内从第几毫秒开始是这首」。
	 * 供外部跳曲使用（{@code /radio play <频道> <序号>}）：把该值当成目标位置，
	 * 反算出计时偏移即可让所有人同时从这一首的开头开始。
	 *
	 * @param roundIndex 轮次
	 * @param index      曲目序号（超出范围按取模处理）
	 * @return 本轮内起始毫秒；曲池为空返回 0
	 */
	public long trackStartMillis(int roundIndex, int index) {
		Round current = round(roundIndex);
		long[] cumulative = current.cumulative();
		int n = cumulative.length - 1;
		if (n <= 0) {
			return 0L;
		}
		return cumulative[Math.floorMod(index, n)];
	}

	/** 某轮某首是否可推流（即已有 MP3 转码产物）。 */	public boolean isPlayableAt(int roundIndex, int index) {
		Path file = fileAt(roundIndex, index);
		return file != null && file.getFileName().toString()
				.toLowerCase(java.util.Locale.ROOT).endsWith(".mp3");
	}

	/**
	 * 整条列表的平均码率（字节/秒）。
	 *
	 * <p>服务端配速把「累计已发送<b>字节</b>」换算成毫秒，换算基数在订阅创建时就固定了。
	 * 各首码率可能不同，若用「当前这首的码率」换曲瞬间基数跳变、配速立刻失真。
	 * 由于每轮都是同一曲池的排列（总字节、总时长恒定），用曲池平均值是<b>精确</b>的。
	 */
	public int averageBytesPerSecond() {
		long totalBytes = 0L;
		for (Track track : this.pool) {
			totalBytes += track.byteSize();
		}
		if (totalBytes <= 0 || this.roundTotalMillis <= 0) {
			return 0;
		}
		long bps = totalBytes * 1000L / this.roundTotalMillis;
		return bps > 512 && bps < Integer.MAX_VALUE ? (int) bps : 0;
	}
}
