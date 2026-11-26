package radiosmile.radio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import radiosmile.SmileSRadio;

/**
 * 逐曲<b>响度归一化</b>：把每首曲子的播放电平拉到统一的听感目标。
 *
 * <h2>为什么需要它</h2>
 * 固定增益只能让「某一类母带」落在目标上。同样是 20 dB 衰减，
 * -9.9 LUFS 的曲子正好到 -30，-15 LUFS 的曲子就只有 -35——
 * 混播列表里响度会一首一个样。要真正对齐，必须<b>按曲实测</b>。
 *
 * <h2>增益算在哪儿、存在哪儿</h2>
 * <ul>
 *   <li><b>算</b>：预热阶段用 ffmpeg 的 {@code loudnorm} 分析一次，得到整轨积分响度与真峰；</li>
 *   <li><b>存</b>：结果写进缓存元数据 {@code cache/loudness/<哈希>.lufs}，
 *       与音频一起按 {@link RadioStationRegistry#quickHash} 命名——
 *       换文件必然换哈希，不会把旧曲子的电平套到新曲子上；</li>
 *   <li><b>用</b>：播放时用电平换算增益（{@code 10^((目标-实测)/20)}），
 *       随音频块下发，运行时不碰磁盘。</li>
 * </ul>
 *
 * <h2>为什么存 LUFS 而不是存算好的增益</h2>
 * 增益依赖当前配置的目标电平。存 LUFS 的话，改 {@code targetLoudness}
 * 立刻对所有曲子生效，不用重新预热；存增益就把「配置」烤进了缓存，
 * 改一次要重跑一遍全库分析。缓存是<b>内容</b>，增益是<b>运行时参数</b>。
 *
 * <h2>不放大超过余量</h2>
 * 目标电平比素材还低时是衰减（绝大多数情况）；素材本身偏轻时才会放大，
 * 此时用实测真峰卡住上限（{@link #TRUE_PEAK_CEILING_DB}），避免削波。
 */
public final class LoudnessNormalizer {

	/** 缓存元数据目录名（位于 cache 目录下）。 */
	private static final String META_DIR = "loudness";

	/** 元数据文件后缀。 */
	private static final String META_SUFFIX = ".lufs";

	/**
	 * 记录到元数据里的真峰参考上限（dBFS）。
	 *
	 * <p>当前只作为测量结果的一部分存下来：本类<b>不做放大</b>（见 {@link #gainFrom}），
	 * 所以它现在不参与增益计算。留着的目的是——将来若要支持"把偏轻的素材抬到目标"，
	 * 直接用这份已测好的真峰算可抬升余量即可，<b>不必重新解码整个曲库</b>
	 * （全库实测一次是几十秒到几分钟的成本，而元数据几乎不占空间）。
	 */
	private static final float TRUE_PEAK_CEILING_DB = -1.0F;

	/** 增益下限（dB）。极端响的素材（母带压到 -6 LUFS）也不至于被压成听不见。 */
	private static final float MIN_GAIN_DB = -40.0F;

	/**
	 * <b>还没有实测数据</b>时的暂用电平（≈ -18.4 dB）。
	 *
	 * <p>什么时候会用到：预热线程还没轮到这首曲子、服务器上没有 ffmpeg、
	 * 或者实测失败。这些都是「暂时算不出增益」，不是「不需要增益」，
	 * 所以不能放 1.0 让它以母带原电平（-11 LUFS 上下）炸出来。
	 *
	 * <p>取值依据：参考曲库实测母带集中在 -11 ~ -13 LUFS，
	 * {@code 10^((-30 - (-11.3)) / 20) ≈ 0.116}，取整到 0.12。
	 * 它<b>只是过渡值</b>——真正生效的是按曲实测的结果，
	 * 所以这个数字不需要精确，只需要「不刺耳、不削波」。
	 */
	public static final float UNMEASURED_GAIN = 0.12F;

	/** 实测结果：整轨积分响度与真峰（dBFS）。 */
	private record Measured(float lufs, float tp) {
	}

	/** 哈希 -> 实测结果（内存缓存，避免每块音频都读盘）。 */
	private static final Map<String, Measured> MEASURED = new ConcurrentHashMap<>();

	/** 文件 -> 已算好的增益（-1 表示无法归一化）。泵线程每块都会问，必须 O(1)。 */
	private static final Map<String, Float> GAIN_CACHE = new ConcurrentHashMap<>();

	/** 正在测量的哈希，避免重复排队。 */
	private static final java.util.Set<String> IN_PROGRESS = ConcurrentHashMap.newKeySet();

	private static volatile boolean ffmpegMissingLogged;

	private LoudnessNormalizer() {
	}

	/**
	 * 本条音源应当乘上的增益。
	 *
	 * @param playedFile 正在播放的文件；可能是<b>转码产物</b>而非源文件，
	 *                   这里会先反查回源文件再取元数据（见 {@link AudioTranscoder#sourceOf}）。
	 *                   网络流传 {@code null} 或任意上游路径，都无从实测
	 * @return {@code 0.0~1.0} 的增益；<b>返回负数表示「无法归一化」</b>
	 *         （未实测 / 关闭了归一化 / 没有 ffmpeg），调用方按
	 *         {@link RadioStationRegistry#targetLoudness()} 决定是原样播放
	 *         还是用 {@link #UNMEASURED_GAIN} 兜底
	 */
	public static float gainFor(Path playedFile) {
		if (playedFile == null) {
			return -1.0F;
		}
		float target = RadioStationRegistry.targetLoudness();
		// 目标电平 >= 0 视为「关闭归一化」：LUFS 是负数，正数没有意义
		if (target >= 0.0F) {
			return -1.0F;
		}
		// 非 MP3 源推的是转码产物，元数据按源文件哈希命名——必须还原
		Path source = AudioTranscoder.sourceOf(playedFile);
		String key = source.toAbsolutePath().toString();
		Float cached = GAIN_CACHE.get(key);
		if (cached != null) {
			return cached;
		}
		Measured m = measuredFor(source);
		float gain = m == null ? -1.0F : gainFrom(m, target);
		GAIN_CACHE.put(key, gain);
		return gain;
	}

	/**
	 * 由实测电平与目标电平算出增益。
	 *
	 * <h2>只衰减，不放大</h2>
	 * 增益被夹在 {@code 0.0~1.0}。理由不是"算不出来"，而是<b>整条链路都是这个语义</b>：
	 * 客户端 {@code PcmSink.setStreamGain} 按 {@code 0~1} 夹取，并且把「满增益」
	 * 当作"不衰减"直接删表项。若这里返回 1.7 想抬升，客户端会夹成 1.0——
	 * 表面「归一化生效了」，实际没抬起来，是最难查的那种静默失效。
	 *
	 * <p>代价：比目标还轻的素材（如 -35 LUFS 的老录音）会停在原电平，比目标低几 dB。
	 * 要真正抬起来，得先改客户端契约（允许 >1 并按真峰限幅），那是另一个决定。
	 * 实测真峰一并存进元数据，就是为了将来要放开放大时不必重新解码全库。
	 */
	private static float gainFrom(Measured m, float target) {
		float db = target - m.lufs();
		// 素材本来就比目标轻：不放大，保持原样
		if (db > 0.0F) {
			db = 0.0F;
		}
		if (db < MIN_GAIN_DB) {
			db = MIN_GAIN_DB;
		}
		return (float) Math.pow(10.0, db / 20.0);
	}

	/**
	 * 后台实测给定文件里<b>还没有元数据</b>的那些。
	 *
	 * <p>由 {@code RadioStationRegistry} 的哈希校验流程顺带调用（那一趟
	 * 已经把所有本地曲目解析出来了）。单线程串行，与转码预热同理：
	 * 同时跑多个 ffmpeg 会抢满 CPU。
	 *
	 * <p>只有真有东西要测时才探测 ffmpeg——没有 ffmpeg 时这套机制静默失效，
	 * 播放回落到 {@link #UNMEASURED_GAIN}，不影响可用性。
	 */
	public static void preheat(List<Path> sources) {
		if (sources == null || sources.isEmpty()) {
			return;
		}
		if (RadioStationRegistry.targetLoudness() >= 0.0F) {
			return;
		}
		List<Path> todo = new ArrayList<>();
		for (Path source : sources) {
			if (source != null && Files.isRegularFile(source)
					&& metaFile(source) != null && readMeta(source) == null) {
				todo.add(source);
			}
		}
		if (todo.isEmpty()) {
			return;
		}

		Thread worker = new Thread(() -> {
			if (!AudioTranscoder.ffmpegAvailable()) {
				if (!ffmpegMissingLogged) {
					ffmpegMissingLogged = true;
					SmileSRadio.LOGGER.warn(
							"没有可用的 ffmpeg，响度归一化不可用（{} 个文件未实测）："
									+ "播放将回落到过渡电平 {}。装上 ffmpeg 后重启即可",
							todo.size(), UNMEASURED_GAIN);
				}
				return;
			}
			long start = System.currentTimeMillis();
			int done = 0;
			for (Path source : todo) {
				if (measureAndStore(source)) {
					done++;
				}
			}
			// 全部测完后清一次增益缓存：预热期间已经播过的曲子要按新值重算
			GAIN_CACHE.clear();
			SmileSRadio.LOGGER.info("响度归一化：实测完成 {}/{}，耗时 {} ms（目标 {} LUFS）",
					done, todo.size(), System.currentTimeMillis() - start,
					RadioStationRegistry.targetLoudness());
		}, "RadioSmile-Loudness");
		worker.setDaemon(true);
		worker.start();
	}

	/**
	 * 清掉内存缓存（配置重载时调用）。
	 *
	 * <p>磁盘上的元数据保留：实测结果只跟文件内容有关，与配置无关。
	 * 要重测某个文件，直接删掉 {@code cache/loudness/} 下对应条目。
	 */
	public static void clearCache() {
		GAIN_CACHE.clear();
		MEASURED.clear();
	}

	/** 某个文件是否已有实测元数据（供日志/诊断）。 */
	public static boolean hasMetadata(Path source) {
		return source != null && readMeta(source) != null;
	}

	// ── 内部：测量与元数据 ────────────────────────────────────────────

	private static Measured measuredFor(Path source) {
		Path meta = metaFile(source);
		if (meta == null) {
			return null;
		}
		String hash = meta.getFileName().toString();
		Measured cached = MEASURED.get(hash);
		if (cached != null) {
			return cached;
		}
		Measured fromDisk = readMeta(source);
		if (fromDisk != null) {
			MEASURED.put(hash, fromDisk);
		}
		return fromDisk;
	}

	/**
	 * 实测一个文件并写入元数据。
	 *
	 * <p>用 {@code loudnorm} 的分析模式（{@code print_format=json}）：
	 * 它一次解码就能给出整轨积分响度 {@code input_i} 与真峰 {@code input_tp}，
	 * 输出是结构化 JSON，比从 {@code ebur128} 的多行摘要里抠数字可靠。
	 */
	private static boolean measureAndStore(Path source) {
		String hash = shortHash(source);
		if (hash == null || !IN_PROGRESS.add(hash)) {
			return false;
		}
		try {
			Measured m = measure(source);
			if (m == null) {
				SmileSRadio.LOGGER.warn("[响度] 实测失败，按固定增益播放：{}", source.getFileName());
				return false;
			}
			writeMeta(source, m);
			MEASURED.put(hash, m);
			return true;
		} finally {
			IN_PROGRESS.remove(hash);
		}
	}

	private static Measured measure(Path source) {
		Process process = null;
		try {
			ProcessBuilder pb = new ProcessBuilder(
					AudioTranscoder.currentFfmpegPath(),
					"-hide_banner", "-nostats",
					"-i", source.toString(),
					// loudnorm 的 I/TP 参数对「分析结果」没有影响，这里只是为了拿到
					// input_i / input_tp 两个实测值；取和目标一致的值便于日志对照。
					"-af", "loudnorm=I=" + (int) RadioStationRegistry.targetLoudness()
							+ ":TP=" + (int) TRUE_PEAK_CEILING_DB + ":print_format=json",
					"-f", "null", "-");
			pb.redirectErrorStream(true);
			process = pb.start();
			String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!process.waitFor(300, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				return null;
			}
			if (process.exitValue() != 0) {
				return null;
			}
			Float lufs = jsonNumber(out, "input_i");
			Float tp = jsonNumber(out, "input_tp");
			if (lufs == null) {
				return null;
			}
			return new Measured(lufs, tp == null ? 0.0F : tp);
		} catch (IOException e) {
			SmileSRadio.LOGGER.info("[响度] ffmpeg 调用失败：{}", e.getMessage());
			return null;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return null;
		} finally {
			if (process != null && process.isAlive()) {
				process.destroyForcibly();
			}
		}
	}

	/** 从 loudnorm 的 JSON 里取一个数值字段；取不到（如静音的 {@code -inf}）返回 null。 */
	private static Float jsonNumber(String json, String key) {
		Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"?(-?[0-9]+(?:\\.[0-9]+)?)\"?")
				.matcher(json);
		if (!m.find()) {
			return null;
		}
		try {
			return Float.parseFloat(m.group(1));
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Path metaDir() {
		Path cache = RadioStationRegistry.cacheDir();
		if (cache == null) {
			return null;
		}
		Path dir = cache.resolve(META_DIR);
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			SmileSRadio.LOGGER.warn("无法创建响度缓存目录 {}", dir, e);
			return null;
		}
		return dir;
	}

	private static Path metaFile(Path source) {
		String hash = shortHash(source);
		Path dir = metaDir();
		return hash == null || dir == null ? null : dir.resolve(hash + META_SUFFIX);
	}

	/**
	 * 元数据文件名：源文件轻量哈希的前 24 位。
	 *
	 * <p>与转码缓存用<b>同一个</b> {@link RadioStationRegistry#quickHash}：
	 * 两处对「文件变了没有」的判断必须一致，否则会出现
	 * 「转码认为没变、响度却认为变了」这种自相矛盾的状态。
	 * 这里不掺文件名——同一份音频换个名字不该重测。
	 */
	private static String shortHash(Path source) {
		if (source == null || !Files.isRegularFile(source)) {
			return null;
		}
		String hash = RadioStationRegistry.quickHash(source);
		return hash == null || hash.isEmpty() ? null : hash.substring(0, 24);
	}

	private static void writeMeta(Path source, Measured m) {
		Path meta = metaFile(source);
		if (meta == null) {
			return;
		}
		String body = "lufs=" + m.lufs() + "\ntp=" + m.tp() + "\n";
		try {
			Files.writeString(meta, body, StandardCharsets.UTF_8);
		} catch (IOException e) {
			SmileSRadio.LOGGER.warn("写入响度元数据失败 {}", meta, e);
		}
	}

	private static Measured readMeta(Path source) {
		Path meta = metaFile(source);
		if (meta == null || !Files.isRegularFile(meta)) {
			return null;
		}
		try {
			Float lufs = null;
			Float tp = null;
			for (String line : Files.readAllLines(meta, StandardCharsets.UTF_8)) {
				int eq = line.indexOf('=');
				if (eq <= 0) {
					continue;
				}
				String k = line.substring(0, eq).trim();
				String v = line.substring(eq + 1).trim();
				if ("lufs".equals(k)) {
					lufs = Float.parseFloat(v);
				} else if ("tp".equals(k)) {
					tp = Float.parseFloat(v);
				}
			}
			return lufs == null ? null : new Measured(lufs, tp == null ? 0.0F : tp);
		} catch (IOException | NumberFormatException e) {
			// 元数据损坏当作没测过：下次预热会重测，不该因此让播放失败
			return null;
		}
	}
}
