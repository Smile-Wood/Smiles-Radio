package radiosmile.radio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import radiosmile.SmileSRadio;

/**
 * 把本地音频转码成<b>可随机定位</b>的 MP3，并在服务端启动/重载时预热。
 *
 * <h2>为什么要转码</h2>
 * 多人同步要求所有人从同一位置开始听，这需要能「跳到文件中段」。
 * 但 OGG 的 Vorbis 解码依赖前序包的连续状态（MDCT 重叠相加），
 * 页序号一跳变就报 {@code Failed to read Ogg file}，<b>根本无法从中段解码</b>。
 *
 * <p>MP3 则不同：每一帧自包含（帧头含比特率/采样率），从任意帧边界都能独立解码。
 * 实测从第 50 帧起可连续解出 100 帧。网络电台之所以一直正常，正因为它就是 MP3。
 *
 * <h2>什么时候转</h2>
 * <b>服务端启动 / 重载时</b>按源文件哈希批量预热。这样订阅时直接流式传输，
 * 首个听众也无需等待。已转过的（哈希相同）直接复用缓存，不重复转码。
 *
 * <p>转码在后台线程池串行执行，不阻塞服务端启动流程。
 */
public final class AudioTranscoder {
	/** 转码产物存放目录名（位于 cache 目录下）。 */
	private static final String BAKED_DIR = "baked";
	/** 输出码率。128kbps 与常见网络电台相当，音质与体积平衡。 */
	private static final String BITRATE = "128k";

	/**
	 * 可转码的源文件扩展名——<b>这是全模组唯一的「什么算音频」白名单</b>。
	 *
	 * <h2>为什么定义在这里</h2>
	 * 一条音频能不能用，唯一的标准是<b>ffmpeg 能不能转码</b>，而不是它叫什么名字。
	 * 这个判断的语义归属于转码方，所以白名单放在这里；
	 * {@link RadioPlaylist} 只是引用它，<b>不自己另存一份</b>——
	 * 两处白名单必然会漂移，而漂移的表现是最难查的那种：
	 * 同一个文件「单文件频道能放、放进目录就消失」。
	 *
	 * <p>正因如此，这里刻意<b>不</b>按「模组有没有对应解码器」来筛选：
	 * 模组只推 MP3（见 {@link AudioFormat}），但上述格式都会被转成 MP3，
	 * 所以它们都是合法输入。反过来，{@code ogg} 出现在这里<b>不</b>意味着恢复了 OGG 推流路径。
	 *
	 * <p>只收「容器/编码明确、ffmpeg 普遍支持」的格式；图片、文本、字幕等一律不在其中，
	 * 避免把 .txt 也拖去跑一遍失败的 ffmpeg。
	 */
	public static final java.util.Set<String> SOURCE_EXTENSIONS = java.util.Set.of(
			// 原生可推：无需转码
			"mp3",
			// 常见无损 / 有损音频源格式，全部经 ffmpeg 转成 MP3 后再推流
			"wav", "ogg", "oga", "flac", "m4a", "aac", "opus",
			"wma", "aiff", "aif", "alac", "ape", "wv",
			// <b>流媒体分片格式</b>：B 站等站点的音轨分片就是 .m4s
			// （实际是 MP4/ISO-BMFF 容器，实测 magic 为 `ftypiso5`）。
			// 有些站点还会把 Content-Type 误标成 audio/mpeg，
			// 所以<b>不能靠响应头判断格式，只能看扩展名 + 交给 ffmpeg</b>。
			// 注意：这里收 \"m4s\" 只是让 ffmpeg 有机会试着解码，
			// 真正能不能转仍由 ffmpeg 决定——这正是本白名单的一贯原则。
			"m4s",
			// 常见视频容器（有些音频直链其实封装在视频容器里，取音轨即可）
			"mp4", "mkv", "webm");

	/**
	 * 该文件是否是「可转码的音频源」。
	 *
	 * <p>只看扩展名：真正能不能转由 ffmpeg 决定，扩展名只用来把
	 * 图片/文本之类的非音频文件提前拦掉，省下一次注定失败的转码。
	 */
	public static boolean isTranscodableSource(Path source) {
		return source != null && SOURCE_EXTENSIONS.contains(extensionOf(source));
	}

	/** 取小写扩展名（不含点）；没有扩展名返回空串。 */
	public static String extensionOf(Path source) {
		if (source == null || source.getFileName() == null) {
			return "";
		}
		String name = source.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
		int dot = name.lastIndexOf('.');
		return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
	}

	/** 正在转码的目标文件，避免重复任务。 */
	private static final java.util.Set<String> IN_PROGRESS =
			java.util.concurrent.ConcurrentHashMap.newKeySet();
	/** 已完成的转码：源文件键 -> MP3 路径。 */
	private static final Map<String, Path> READY = new ConcurrentHashMap<>();

	private AudioTranscoder() {
	}

	public static Path bakedDir() {
		Path cache = RadioStationRegistry.cacheDir();
		if (cache == null) {
			return null;
		}
		Path dir = cache.resolve(BAKED_DIR);
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			SmileSRadio.LOGGER.warn("无法创建转码缓存目录 {}", dir, e);
		}
		return dir;
	}

	/**
	 * 源文件对应的缓存键（文件名前段 + 内容哈希前 12 位）。
	 *
	 * <p>哈希用 {@link RadioStationRegistry#quickHash}（文件头 + 大小），
	 * 与缓存校验流程<b>同一条判定链</b>——两处必须用同一个函数，
	 * 否则会出现"校验认为没变、但缓存键已经变了"的错位：
	 * 表现为每次启动都重新转码一遍，却始终命中不了缓存。
	 */
	private static String cacheKey(Path source) {
		String name = source.getFileName().toString();
		String base = name;
		int dot = name.lastIndexOf('.');
		if (dot > 0) {
			base = name.substring(0, dot);
		}
		String hash = RadioStationRegistry.quickHash(source);
		if (hash.isEmpty()) {
			return null;
		}
		return base + "-" + hash.substring(0, 12) + ".mp3";
	}

	/**
	 * 取已转码好的 MP3。
	 *
	 * <p>只查缓存，不做转码——转码由 {@link #preheat} 在启动时完成。
	 *
	 * @return MP3 路径；尚未转好返回 {@code null}（调用方走降级路径）
	 */
	public static Path mp3For(Path source) {
		Path dir = bakedDir();
		if (dir == null || source == null || !Files.isRegularFile(source)) {
			return null;
		}
		String key = cacheKey(source);
		if (key == null) {
			return null;
		}
		Path cached = READY.get(key);
		if (cached != null && Files.isRegularFile(cached)) {
			return cached;
		}
		Path target = dir.resolve(key);
		if (Files.isRegularFile(target)) {
			READY.put(key, target);
			return target;
		}
		return null;
	}

	/**
	 * 判断某个源文件是否还需要转码。
	 *
	 * <p>由哈希校验流程调用（它已经算过 SHA-256，正好复用）。
	 *
	 * @return true 表示需要转码（非 MP3 且产物不存在）
	 */
	public static boolean needsBake(Path source) {
		if (source == null || !Files.isRegularFile(source)) {
			return false;
		}
		// 本身就是 MP3：直接按帧定位即可，无需转码
		if (isMp3(source)) {
			return false;
		}
		Path dir = bakedDir();
		if (dir == null) {
			return false;
		}
		String key = cacheKey(source);
		if (key == null) {
			return false;
		}
		Path target = dir.resolve(key);
		if (Files.isRegularFile(target)) {
			READY.put(key, target);
			return false;
		}
		return true;
	}

	/**
	 * 预热给定文件：把它们转成 MP3 供订阅时直接流式传输。
	 *
	 * <p>由哈希校验流程收集好清单后调用（{@link #needsBake}）。
	 * 在后台单线程串行执行——同时跑多个 ffmpeg 会抢满 CPU，拖慢服务端。
	 * 统一只打两行日志：开始与完成，避免刷屏。
	 *
	 * @param sources 待转码的源文件
	 */
	public static void preheat(List<Path> sources) {
		Path dir = bakedDir();
		if (dir == null || sources == null || sources.isEmpty()) {
			return;
		}
		if (!ffmpegAvailable()) {
			// 不再提「降级方案」：转码不可用时本地非 MP3 频道会被直接拒绝播放
			// （见 AudioStreamManager.openTrack），而不是放出一段看着能响、实则不同步的音频。
			//
			// 这里必须把「哪些格式受影响」写清楚：服主看到 .flac 放不出来时，
			// 第一反应会是「格式不支持」，实际上缺的是 ffmpeg——
			// 不点明这一点，这次故障就会被误诊成格式问题。
			SmileSRadio.LOGGER.warn(
					"ffmpeg 未安装，以下源格式无法转码，相关频道将被拒绝播放：{}（共 {} 个文件待转）。"
							+ "请安装 ffmpeg 后重启服务器，或改用 .mp3 文件",
					SOURCE_EXTENSIONS, sources.size());
			return;
		}

		List<Path> pending = new ArrayList<>(sources);
		SmileSRadio.LOGGER.info("转码预热：{} 个文件，后台开始", pending.size());

		Thread worker = new Thread(() -> {
			long totalStart = System.currentTimeMillis();
			int done = 0;
			for (Path source : pending) {
				Path target = dir.resolve(cacheKey(source));
				if (runFfmpeg(source, target)) {
					READY.put(cacheKey(source), target);
					done++;
				} else {
					SmileSRadio.LOGGER.warn("[预热] 转码失败 {}", source.getFileName());
					try {
						Files.deleteIfExists(target);
					} catch (IOException ignored) {
						// 忽略
					}
				}
			}
			SmileSRadio.LOGGER.info("转码预热完成：成功 {}/{}，耗时 {} ms",
					done, pending.size(), System.currentTimeMillis() - totalStart);
		}, "RadioSmile-Preheat");
		worker.setDaemon(true);
		worker.start();
	}

	private static boolean isMp3(Path file) {
		// 复用 extensionOf：扩展名解析只应有一处实现，否则大小写/多点文件名
		// （例如 "a.b.FLAC"）在不同地方会得出不同结论。
		return "mp3".equals(extensionOf(file));
	}

	// ------------------------------------------------------------ 响度核对与补足

	/**
	 * 目标平均电平（dBFS，{@code volumedetect} 的 {@code mean_volume}）。
	 *
	 * <h2>为什么必须做这件事</h2>
	 * 用户实测两首曲目的差异是 <b>16 dB</b>：
	 * {@code 8f3fb510….mp3} 的 {@code mean=-35.1 dB / peak=-20.8 dB}，
	 * 而 {@code b1cf95ca….mp3} 是 {@code mean=-18.9 dB / peak=-6.9 dB}。
	 * 这种差距靠播放端音量滑条是救不回来的——调小到安静的能听，
	 * 响的那首就炸耳；调大到响的合适，安静的又听不见。
	 *
	 * <p>所以只能在<b>产出音频的那一刻</b>把它补齐：转码时量一次，
	 * 不够就带增益重烤，再量，直到进入容差。
	 *
	 * <p>取 -20 dBFS：略高于常见流媒体的平均电平（约 -21 ~ -24），
	 * 让安静曲目抬到"能听清"的程度，同时给峰值留足余量。
	 */
	private static final float TARGET_MEAN_DB = -20.0F;

	/** 达标容差（dB）：落在目标 ±该值之内就不再动，避免无意义地反复重烤。 */
	private static final float LOUDNESS_TOLERANCE_DB = 1.5F;

	/** 单次最多补多少 dB：防止把底噪极大的文件整体抬成噪声墙。 */
	private static final float MAX_APPLIED_GAIN_DB = 18.0F;

	/** 输出峰值上限（dBFS）：-1 dB 留一点编码余量，配合 {@code alimiter} 防削波。 */
	private static final float LEVEL_CEILING_DB = -1.0F;

	/** 最多重烤几轮（每轮都会重新测量，直到进入容差）。 */
	private static final int MAX_LOUDNESS_PASSES = 3;

	/** 响度核对结果的旁注文件后缀（与 MP3 同目录、同名前缀）。 */
	private static final String LEVEL_SUFFIX = ".loud";

	/**
	 * 一次测量的结果。
	 *
	 * @param mean   平均电平（dBFS）
	 * @param peak   峰值（dBFS）
	 * @param gain   实际补偿的增益；{@link #GIVE_UP_GAIN} 表示"量过了，抬不动"
	 * @param target 测量时使用的目标电平：目标改了，旧结论自动作废
	 */
	private record Level(float mean, float peak, float gain, float target) {
	}

	/** 旁注里 {@code gain} 取这个值 = 源文件没有可抬的余量，别再重复试。 */
	private static final float GIVE_UP_GAIN = -1.0F;

	/**
	 * 核对并补足给定源文件的响度<b>（服务端启动 / 每次重载配置时调用）</b>。
	 *
	 * <h2>判定依据是<b>源文件</b>，不是缓存产物</h2>
	 * 缓存产物可能来自旧版本（例如带 {@code -n1} 后缀的那批实验残留）、
	 * 也可能是被别人手工换过的，它的电平不代表"这首歌到底有多响"。
	 * 源文件才是唯一可信的事实来源，所以这里量的始终是 {@code source}。
	 *
	 * <h2>"每次加载都看一遍"为什么不会很慢</h2>
	 * 达标结论会被写进旁注 {@code <key>.loud}，而缓存键本身<b>包含源文件内容哈希</b>
	 * （见 {@link #cacheKey}）：源文件一变，键就变，旁注自然失效。
	 * 因此只要旁注存在且记录了"已达标"，这次加载就只是一次小文件读取，
	 * 一次 ffmpeg 都不用跑。只有首次、或源文件被换过、或之前没补够的，才会真的重烤。
	 *
	 * <p>全程在后台线程串行执行，和预热一样不阻塞服务端。
	 *
	 * @param sources 需要核对的源文件（传频道里所有曲目即可，内部自己筛）
	 */
	public static void ensureLoudness(List<Path> sources) {
		Path dir = bakedDir();
		if (dir == null || sources == null || sources.isEmpty()) {
			return;
		}
		if (!ffmpegAvailable()) {
			// preheat() 已经就 ffmpeg 缺失详细警告过一次，这里不重复刷屏
			return;
		}
		List<Path> pending = new ArrayList<>(sources);
		Thread worker = new Thread(() -> {
			int checked = 0;
			int fixed = 0;
			long start = System.currentTimeMillis();
			for (Path source : pending) {
				try {
					if (normalizeOne(source)) {
						fixed++;
					}
					checked++;
				} catch (Throwable t) {
					SmileSRadio.LOGGER.warn("[响度] 核对失败 {}", source.getFileName(), t);
				}
			}
			if (fixed > 0) {
				SmileSRadio.LOGGER.info(
						"[响度] 核对 {} 首，补足 {} 首到 {} dBFS（耗时 {} ms）",
						checked, fixed, TARGET_MEAN_DB, System.currentTimeMillis() - start);
				applyNow(fixed);
			} else {
				SmileSRadio.LOGGER.debug("[响度] 核对 {} 首，全部达标", checked);
				// 这一轮没有要刷的东西：解除「本会话已自动刷新」的锁，
				// 这样以后新增 / 换了曲目再补足时，"立即生效"仍然会执行。
				// 它只用来打断「补→刷→补」的紧循环，不该永久失效。
				AUTO_APPLIED.set(false);
			}
		}, "RadioSmile-Loudness");
		worker.setDaemon(true);
		worker.start();
	}

	/**
	 * 补足之后立刻让新缓存生效。
	 *
	 * <h2>为什么必须主动做这一步</h2>
	 * 时间轴在构建时就把「实际推流文件」定死了（{@code PlaylistTimeline#streamFileOf}
	 * 优先取 baked MP3）。缓存虽然已经重烤，但<b>正在播的时间轴还指着旧文件</b>——
	 * 只写缓存不刷时间轴，玩家听到的仍然是那首小声的，
	 * 也就是用户说的「声音永远也好不了」。
	 *
	 * <p>做法：逐个频道清时间轴缓存（{@code reloadStation} 不动计时基准，
	 * 不会让所有人回到开头），再在服务端线程重建订阅。
	 * 重建前会先发过渡静音包（见 {@code AudioStreamManager.rebuildStation}），
	 * 所以听众听到的是一小段干净的静音，而不是底噪。
	 *
	 * <p>只在<b>真的补过东西</b>时调用，并且整个会话最多一次：
	 * 万一旁注写不进去（目录只读），避免陷入「补→刷→补」的循环。
	 */
	private static void applyNow(int fixed) {
		if (!AUTO_APPLIED.compareAndSet(false, true)) {
			return;
		}
		var server = radiosmile.RadioReloadListener.currentServer();
		if (server == null) {
			SmileSRadio.LOGGER.info("[响度] 已补足 {} 首，重启或 /radio reload 后生效（当前没有运行中的服务端）",
					fixed);
			AUTO_APPLIED.set(false);
			return;
		}
		server.execute(() -> {
			int n = 0;
			for (RadioStation station : RadioStationRegistry.stations()) {
				if (station.isStreaming()) {
					continue;
				}
				// 先让时间轴忘掉旧路径，再让订阅重建——顺序反了就还是旧的
				RadioStationRegistry.reloadStation(station.id());
				radiosmile.net.AudioStreamManager.rebuildStation(station.id());
				n++;
			}
			SmileSRadio.LOGGER.info("[响度] 已刷新 {} 个频道，补足的曲目立即生效", n);
		});
	}

	/** 自动刷新生效只做一次（见 {@link #applyNow}）。 */
	private static final java.util.concurrent.atomic.AtomicBoolean AUTO_APPLIED =
			new java.util.concurrent.atomic.AtomicBoolean(false);

	/**
	 * 核对单个源文件；不足则带增益重烤，直到进入容差。
	 *
	 * @return 是否真的重烤过（即缓存被更新）
	 */
	private static boolean normalizeOne(Path source) {
		if (source == null || !Files.isRegularFile(source)) {
			return false;
		}
		String key = cacheKey(source);
		Path dir = bakedDir();
		if (key == null || dir == null) {
			return false;
		}
		Path target = dir.resolve(key);
		Path sidecar = dir.resolve(key + LEVEL_SUFFIX);

		// 快速路径：旁注已记录且达标。key 含内容哈希，所以这里不会被旧结论骗到。
		Level cached = readLevel(sidecar);
		// 但旁注说"补过"、产物却不见了（缓存被单独清掉），不能跳——
		// 那种情况下实际推流的会退回源文件，依旧是那首小声的。
		if (cached != null && cached.gain() > 0F && !Files.isRegularFile(target)) {
			cached = null;
		}
		// 达标，或已经试过并确认没有余量（见 GIVE_UP_GAIN）：都不必再动
		if (cached != null
				&& (cached.gain() < 0F || cached.mean() >= TARGET_MEAN_DB - LOUDNESS_TOLERANCE_DB)) {
			return false;
		}

		// 以<b>源文件</b>为准测量
		Level src = measure(source);
		if (src == null) {
			return false;
		}

		// 峰值余量：抬完不能超过 -1 dBFS，否则只能靠限幅硬压，音质会变差
		float headroom = LEVEL_CEILING_DB - src.peak();
		float cap = Math.min(MAX_APPLIED_GAIN_DB, headroom);

		float applied = 0F;
		Level probe = src;
		boolean rebaked = false;
		for (int pass = 0; pass < MAX_LOUDNESS_PASSES; pass++) {
			float need = TARGET_MEAN_DB - probe.mean();
			if (need <= LOUDNESS_TOLERANCE_DB) {
				break;                       // 已经够了
			}
			float next = Math.min(applied + need, cap);
			if (next <= applied + 0.01F) {
				// 已经顶到上限（源文件本身没余量）：再烤也是同一个结果
				break;
			}
			applied = next;
			if (!bakeWithGain(source, target, applied)) {
				return rebaked;
			}
			rebaked = true;
			Level fresh = measure(target);
			if (fresh == null) {
				break;
			}
			probe = fresh;
		}

		// 没烤过有两种可能：本来就够响（记 gain=0），或者根本没余量可抬（记"尽力"）。
		// 后者必须单独标出来，否则每次加载都会为它白跑一遍 volumedetect。
		float recorded;
		if (rebaked) {
			recorded = applied;
		} else if (probe.mean() >= TARGET_MEAN_DB - LOUDNESS_TOLERANCE_DB) {
			recorded = 0F;
		} else {
			recorded = GIVE_UP_GAIN;
			SmileSRadio.LOGGER.debug("[响度] {} 平均 {} dBFS 但峰值已到 {} dBFS，没有可抬的余量",
					source.getFileName(), probe.mean(), probe.peak());
		}
		writeLevel(sidecar, probe, recorded);
		return rebaked;
	}

	/**
	 * 带音量补偿重烤一次 MP3。
	 *
	 * <p>先 {@code volume} 抬电平，再用 {@code alimiter} 把峰值压回 {@link #LEVEL_CEILING_DB}。
	 * 顺序不能反：先限幅再抬音量等于白限。限幅器只是兜底——按
	 * {@link #normalizeOne} 里的峰值余量计算，正常情况下它几乎不介入。
	 *
	 * <p>某些 ffmpeg 构建没编进 {@code alimiter}，这时退化成只 {@code volume}：
	 * 宁可略有削波风险，也不要因为一个滤镜缺失就整首补不了。
	 */
	private static boolean bakeWithGain(Path source, Path target, float gainDb) {
		String gain = String.format(Locale.ROOT, "volume=%.2fdB", gainDb);
		String limit = String.format(Locale.ROOT,
				"alimiter=level_in=1:level_out=1:limit=%.3f:attack=5:release=50",
				Math.pow(10.0, LEVEL_CEILING_DB / 20.0));
		if (runFfmpeg(source, target, gain + "," + limit)) {
			return true;
		}
		// 重试：不带滤镜器件的纯音量版本
		try {
			Files.deleteIfExists(target);
		} catch (IOException ignored) {
			// 删不掉也无所谓，runFfmpeg 带 -y 会覆盖
		}
		return runFfmpeg(source, target, gain);
	}

	/**
	 * 用 {@code volumedetect} 量一个文件的平均电平与峰值。
	 *
	 * @return 测不出来（文件损坏 / ffmpeg 不可用）返回 {@code null}
	 */
	private static Level measure(Path file) {
		try {
			ProcessBuilder pb = new ProcessBuilder(
					ffmpegCommand(), "-hide_banner", "-nostats",
					"-i", file.toString(),
					"-af", "volumedetect",
					"-f", "null", "-");
			pb.redirectErrorStream(true);
			Process process = pb.start();
			String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!process.waitFor(300, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return null;
			}
			float mean = parseDetect(output, "mean_volume:");
			float peak = parseDetect(output, "max_volume:");
			if (Float.isNaN(mean)) {
				return null;
			}
			return new Level(mean, Float.isNaN(peak) ? mean : peak, 0F, TARGET_MEAN_DB);
		} catch (IOException e) {
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		}
	}

	/** 从 {@code volumedetect} 输出里取出某个标记后面的 dB 数值。 */
	private static float parseDetect(String output, String marker) {
		int at = output.lastIndexOf(marker);
		if (at < 0) {
			return Float.NaN;
		}
		String rest = output.substring(at + marker.length()).trim();
		int end = 0;
		while (end < rest.length()
				&& (Character.isDigit(rest.charAt(end)) || rest.charAt(end) == '-'
						|| rest.charAt(end) == '.')) {
			end++;
		}
		if (end == 0) {
			return Float.NaN;
		}
		try {
			return Float.parseFloat(rest.substring(0, end));
		} catch (NumberFormatException e) {
			return Float.NaN;
		}
	}

	/** 读旁注；内容不是本版本格式就当没有。 */
	private static Level readLevel(Path sidecar) {
		try {
			if (!Files.isRegularFile(sidecar)) {
				return null;
			}
			String text = Files.readString(sidecar, StandardCharsets.UTF_8).trim();
			float mean = Float.NaN;
			float peak = Float.NaN;
			float gain = 0F;
			float target = Float.NaN;
			for (String part : text.split("\\s+")) {
				int eq = part.indexOf('=');
				if (eq <= 0) {
					continue;
				}
				String k = part.substring(0, eq);
				String v = part.substring(eq + 1);
				try {
					switch (k) {
						case "mean" -> mean = Float.parseFloat(v);
						case "peak" -> peak = Float.parseFloat(v);
						case "gain" -> gain = Float.parseFloat(v);
						case "target" -> target = Float.parseFloat(v);
						default -> { }
					}
				} catch (NumberFormatException ignored) {
					// 单个字段坏了不影响其他字段
				}
			}
			if (Float.isNaN(mean)) {
				return null;
			}
			// 目标电平改过：旧结论不再成立，当成没做过
			if (!Float.isNaN(target) && Math.abs(target - TARGET_MEAN_DB) > 0.05F) {
				return null;
			}
			return new Level(mean, Float.isNaN(peak) ? mean : peak, gain, TARGET_MEAN_DB);
		} catch (IOException e) {
			return null;
		}
	}

	/** 写旁注（记录"这一版源文件核对过、结果如何"）。 */
	private static void writeLevel(Path sidecar, Level level, float appliedGain) {
		String text = String.format(Locale.ROOT,
				"v=1 mean=%.2f peak=%.2f gain=%.2f target=%.1f%n",
				level.mean(), level.peak(), appliedGain, TARGET_MEAN_DB);
		try {
			Files.writeString(sidecar, text, StandardCharsets.UTF_8);
		} catch (IOException e) {
			SmileSRadio.LOGGER.debug("写入响度旁注失败 {}", sidecar, e);
		}
	}

	/**
	 * 调用外部 ffmpeg 转码。
	 *
	 * <p>用外部进程而非内嵌编码器：MP3 编码器体积大、许可证也需单独考虑；
	 * ffmpeg 是服主侧常见工具，缺失时自动降级，不影响基本可用性。
	 */
	private static boolean runFfmpeg(Path source, Path target) {
		return runFfmpeg(source, target, null);
	}

	/**
	 * 调用外部 ffmpeg 转码，可选地在编码前挂一条音频滤镜。
	 *
	 * @param filter {@code -af} 的内容（例如音量补偿 + 限幅）；
	 *               {@code null} / 空白表示不加滤镜
	 */
	private static boolean runFfmpeg(Path source, Path target, String filter) {
		String key = target.toString();
		if (!IN_PROGRESS.add(key)) {
			return false;
		}
		try {
			List<String> args = new ArrayList<>(List.of(
					ffmpegCommand(), "-y", "-hide_banner", "-loglevel", "error",
					"-i", source.toString(),
					"-vn"));
			if (filter != null && !filter.isBlank()) {
				args.add("-af");
				args.add(filter);
			}
			args.add("-c:a");
			args.add("libmp3lame");
			args.add("-b:a");
			args.add(BITRATE);
			args.add(target.toString());
			ProcessBuilder pb = new ProcessBuilder(args);
			pb.redirectErrorStream(true);
			Process process = pb.start();
			// 消费输出，避免管道写满导致进程阻塞
			process.getInputStream().readAllBytes();
			boolean finished = process.waitFor(300, TimeUnit.SECONDS);
			if (!finished) {
				process.destroyForcibly();
				return false;
			}
			return process.exitValue() == 0 && Files.isRegularFile(target)
					&& Files.size(target) > 1024;
		} catch (IOException e) {
			SmileSRadio.LOGGER.info("ffmpeg 不可用：{}", e.getMessage());
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		} finally {
			IN_PROGRESS.remove(key);
		}
	}

	/** 探测 ffmpeg 是否可用。 */
	public static boolean ffmpegAvailable() {
		try {
			Process p = new ProcessBuilder(ffmpegCommand(), "-version")
					.redirectErrorStream(true).start();
			p.getInputStream().readAllBytes();
			return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * ffmpeg 可执行文件路径（{@code null} / 空 = 用 PATH 里的 {@code ffmpeg}）。
	 *
	 * <p>由 {@code stations.json} 顶层的 {@code ffmpeg} 字段设置，见
	 * {@link RadioStationRegistry#setFfmpegPath(String)}。
	 * 供 {@link #ffmpegCommand()} 与 {@link #runFfmpeg} 共用——
	 * <b>两处必须走同一个函数</b>，否则会出现"探测说可用、转码却找不到"
	 * 这种自相矛盾的状态。
	 */
	private static volatile String configuredFfmpegPath;

	/**
	 * 设置 ffmpeg 路径。
	 *
	 * @param path 绝对路径（如 {@code C:\tools\ffmpeg\bin\ffmpeg.exe}），
	 *             或裸命令名（如 {@code ffmpeg}）；空白视为"用默认值"
	 */
	public static void setFfmpegPath(String path) {
		configuredFfmpegPath = (path == null || path.isBlank()) ? null : path.trim();
	}

	/** 当前生效的 ffmpeg 命令（用于日志展示与诊断）。 */
	public static String currentFfmpegPath() {
		String configured = configuredFfmpegPath;
		return configured != null ? configured : "ffmpeg";
	}

	/**
	 * 组装实际要执行的 ffmpeg 命令。
	 *
	 * <p>未配置时回退到 PATH 里的 {@code ffmpeg}——这是绝大多数服主的用法，
	 * 不该强迫他们写路径。
	 */
	private static String ffmpegCommand() {
		String configured = configuredFfmpegPath;
		return configured != null ? configured : "ffmpeg";
	}

	/** 已就绪的转码产物数量（用于日志）。 */
	public static int readyCount() {
		return READY.size();
	}
}