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

	/**
	 * 调用外部 ffmpeg 转码。
	 *
	 * <p>用外部进程而非内嵌编码器：MP3 编码器体积大、许可证也需单独考虑；
	 * ffmpeg 是服主侧常见工具，缺失时自动降级，不影响基本可用性。
	 */
	private static boolean runFfmpeg(Path source, Path target) {
		String key = target.toString();
		if (!IN_PROGRESS.add(key)) {
			return false;
		}
		try {
			ProcessBuilder pb = new ProcessBuilder(
					"ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
					"-i", source.toString(),
					"-vn",
					"-c:a", "libmp3lame", "-b:a", BITRATE,
					target.toString());
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
			Process p = new ProcessBuilder("ffmpeg", "-version")
					.redirectErrorStream(true).start();
			p.getInputStream().readAllBytes();
			return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
		} catch (Exception e) {
			return false;
		}
	}

	/** 已就绪的转码产物数量（用于日志）。 */
	public static int readyCount() {
		return READY.size();
	}
}