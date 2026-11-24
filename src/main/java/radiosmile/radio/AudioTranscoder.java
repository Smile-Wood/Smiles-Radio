package radiosmile.radio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
	 * 响度均衡目标（EBU R128）：-16 LUFS、真峰值 -1.5 dBTP、响度范围 11 LU。
	 *
	 * <p>-16 LUFS 是流媒体/播客的通用目标：比广播的 -23 响，又留出足够峰值余量，
	 * 不会因为逐首音量差而出现「这首要拧大、下首又炸」。
	 */
	private static final String LOUDNORM_TARGET = "I=-16:TP=-1.5:LRA=11";

	/**
	 * 转码配置版本号。
	 *
	 * <p><b>改动 ffmpeg 参数（滤波器、码率、均衡目标）时必须改它</b>——
	 * 它参与缓存键，否则老产物会被继续复用，改动等于没生效。
	 */
	private static final String PROFILE = "n1";

	/** 是否启用响度均衡（{@code stations.json} 顶层 {@code normalize}，默认开启）。 */
	private static volatile boolean normalizeEnabled = true;

	/** 设置是否启用响度均衡。关闭后 MP3 源不再转码，纯 MP3 曲库保持原文件。 */
	public static void setNormalizeEnabled(boolean enabled) {
		normalizeEnabled = enabled;
	}

	/** 当前是否启用响度均衡。 */
	public static boolean normalizeEnabled() {
		return normalizeEnabled;
	}

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
		// 配置版本进文件名：参数一改，旧产物自然不再被命中（无需额外失效逻辑）。
		String profile = normalizeEnabled ? "-" + PROFILE : "";
		return base + "-" + hash.substring(0, 12) + profile + ".mp3";
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
		// 响度均衡开启时 MP3 也要过一遍：原始 MP3 直接推流就没有均衡。
		// 关闭均衡时才回到「MP3 无需转码」的老行为。
		if (!normalizeEnabled && isMp3(source)) {
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
		SmileSRadio.LOGGER.info("转码预热：{} 个文件（响度均衡={}），后台开始",
				pending.size(), normalizeEnabled ? "开" : "关");

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

	/**
	 * 调用外部 ffmpeg 转码，并做<b>响度均衡</b>。
	 *
	 * <h2>为什么在转码时做</h2>
	 * 不同来源的音乐响度差得很远（老 CD 抓轨 -18 LUFS、流媒体母带 -9 LUFS），
	 * 直接混在一个播放列表里就会出现「这首听不清、下首炸耳朵」。
	 * 服务器反正要把每个文件过一遍 ffmpeg，顺手做一次 EBU R128 均衡是<b>零额外工作</b>：
	 * 所有人拿到的都是同一响度的音频，客户端不需要任何增益处理。
	 *
	 * <h2>为什么是两遍</h2>
	 * {@code loudnorm} 单遍是<b>动态</b>压缩：边分析边调增益，音乐会有明显的抽吸感。
	 * 两遍法先用 {@code -f null} 只分析、拿到整首的积分响度，再以 {@code linear=true}
	 * 施加<b>一个恒定的增益</b>——只改音量、不动动态，这是音乐该用的做法。
	 *
	 * <p>代价是每个文件解码两次（第一遍不编码，很快）。任何一遍失败都会逐级退回：
	 * 两遍 → 单遍动态均衡 → 原样转码，绝不因为均衡失败而让文件放不出来。
	 */
	private static boolean runFfmpeg(Path source, Path target) {
		String key = target.toString();
		if (!IN_PROGRESS.add(key)) {
			return false;
		}
		try {
			if (normalizeEnabled) {
				String measured = measureLoudness(source);
				if (measured != null && encode(source, target, loudnormFilter(measured))) {
					return true;
				}
				SmileSRadio.LOGGER.debug("[转码] 两遍响度均衡失败，退回单遍：{}", source.getFileName());
				if (encode(source, target, loudnormFilter(null))) {
					return true;
				}
				SmileSRadio.LOGGER.warn(
						"[转码] 响度均衡不可用（ffmpeg 版本过旧？），改为普通转码：{}",
						source.getFileName());
			}
			return encode(source, target, null);
		} finally {
			IN_PROGRESS.remove(key);
		}
	}

	/** 组装 loudnorm 滤波器；带测量值时用线性增益（两遍法），否则用单遍动态模式。 */
	private static String loudnormFilter(String measured) {
		if (measured == null || measured.isEmpty()) {
			return "loudnorm=" + LOUDNORM_TARGET;
		}
		return "loudnorm=" + LOUDNORM_TARGET + ":" + measured + ":linear=true";
	}

	/**
	 * 第一遍：只分析整首的响度，不产生输出。
	 *
	 * <p>{@code print_format=json} 让 ffmpeg 把测量结果以 JSON 打到 stderr，
	 * 这里用正则取出五个字段。取不到（老版本、静音文件返回 {@code -inf}）
	 * 就返回 {@code null}，由调用方退回单遍模式。
	 *
	 * @return 形如 {@code measured_I=-23.4:...} 的参数串；不可用时为 {@code null}
	 */
	private static String measureLoudness(Path source) {
		List<String> args = new ArrayList<>();
		args.add("-nostats");
		args.add("-loglevel");
		args.add("info");
		args.add("-i");
		args.add(source.toString());
		args.add("-vn");
		args.add("-af");
		args.add("loudnorm=" + LOUDNORM_TARGET + ":print_format=json");
		args.add("-f");
		args.add("null");
		args.add("-");
		RunResult result = runProcess(args, null);
		if (!result.ok()) {
			return null;
		}
		String i = jsonNumber(result.output(), "input_i");
		String tp = jsonNumber(result.output(), "input_tp");
		String lra = jsonNumber(result.output(), "input_lra");
		String thresh = jsonNumber(result.output(), "input_thresh");
		String offset = jsonNumber(result.output(), "target_offset");
		if (i == null || tp == null || lra == null || thresh == null || offset == null) {
			return null;
		}
		return "measured_I=" + i + ":measured_TP=" + tp + ":measured_LRA=" + lra
				+ ":measured_thresh=" + thresh + ":offset=" + offset;
	}

	/** 从 ffmpeg 的 JSON 输出里取一个<b>数值</b>字段；缺失或非数值返回 {@code null}。 */
	private static String jsonNumber(String json, String field) {
		if (json == null || json.isEmpty()) {
			return null;
		}
		java.util.regex.Matcher matcher = java.util.regex.Pattern
				.compile("\"" + java.util.regex.Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]*)\"")
				.matcher(json);
		if (!matcher.find()) {
			return null;
		}
		String value = matcher.group(1).trim();
		try {
			Double.parseDouble(value);
			return value;
		} catch (NumberFormatException e) {
			// "-inf"、"nan" 之类：静音或异常文件，不要拿去当参数
			return null;
		}
	}

	/**
	 * 第二/三遍：把源文件编码成 MP3。
	 *
	 * @param filter 音频滤波器链；{@code null} 表示不做均衡（兜底路径）
	 */
	private static boolean encode(Path source, Path target, String filter) {
		List<String> args = new ArrayList<>();
		args.add("-nostats");
		args.add("-loglevel");
		args.add("error");
		args.add("-i");
		args.add(source.toString());
		args.add("-vn");
		if (filter != null) {
			args.add("-af");
			args.add(filter);
		}
		args.add("-c:a");
		args.add("libmp3lame");
		args.add("-b:a");
		args.add(BITRATE);
		args.add(target.toString());
		return runProcess(args, target).ok();
	}

	/** 一次 ffmpeg 调用的结果。输出文本是响度测量要读的（ffmpeg 把 JSON 打到 stderr）。 */
	private record RunResult(boolean ok, String output) {
	}

	/**
	 * 执行一次 ffmpeg。
	 *
	 * @param args   不含可执行文件与 {@code -y}/{@code -hide_banner} 的参数
	 * @param target 期望产物；为 {@code null} 表示不校验产物（测量模式）
	 */
	private static RunResult runProcess(List<String> args, Path target) {
		List<String> command = new ArrayList<>();
		command.add(ffmpegCommand());
		command.add("-y");
		command.add("-hide_banner");
		command.addAll(args);
		try {
			ProcessBuilder pb = new ProcessBuilder(command);
			pb.redirectErrorStream(true);
			Process process = pb.start();
			// 必须消费输出：管道写满会让 ffmpeg 阻塞，表现为"转码卡住"
			String output = new String(process.getInputStream().readAllBytes(),
					java.nio.charset.StandardCharsets.UTF_8);
			if (!process.waitFor(600, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				SmileSRadio.LOGGER.warn("[转码] ffmpeg 超时，已终止：{}", target);
				return new RunResult(false, output);
			}
			boolean ok = process.exitValue() == 0;
			if (ok && target != null) {
				ok = Files.isRegularFile(target) && Files.size(target) > 1024;
			}
			return new RunResult(ok, output);
		} catch (IOException e) {
			SmileSRadio.LOGGER.info("ffmpeg 不可用：{}", e.getMessage());
			return new RunResult(false, "");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return new RunResult(false, "");
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