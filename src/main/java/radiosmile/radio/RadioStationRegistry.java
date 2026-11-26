package radiosmile.radio;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import radiosmile.SmileSRadio;

/**
 * 服务端电台配置的加载、校验与临时文件（拼接音频）缓存管理。
 *
 * <p>目录布局（位于世界存档目录下 {@code serverconfig/radio-smile/}）：
 * <pre>
 *   stations.json     电台列表（服务端广播）
 *   audio/            原始音频文件（file 字段指向这里）
 *   icons/            图标文件（icon 字段指向这里）
 *   cache/            拼接后的临时音频与校验清单
 * </pre>
 *
 * <p>服务端启动或重载时：若 cache 存在则校验各 station 的 file 哈希；只有变化时才重新生成。
 * 无论是否重新生成，都会把播放计时基准（{@link #startEpochMillis}）重置为当前时刻，
 * 从而保证所有客户端用同一个基准计算播放偏移。
 */
public final class RadioStationRegistry {
	/**
	 * 配置文件写出用的 Gson。
	 *
	 * <p><b>必须关闭 HTML 转义</b>（{@code disableHtmlEscaping}）：默认实现会把
	 * {@code =}、{@code <}、{@code >}、{@code &} 转成 {@code =} 之类的转义序列，
	 * 于是配置文件里出现 {@code mode=block} 这种东西——
	 * 人能读但完全没法一眼看懂，而这正是我们写"说明"字段要避免的。
	 */
	private static final Gson GSON = new GsonBuilder()
			.setPrettyPrinting()
			.disableHtmlEscaping()
			.create();
	private static final String INDEX_NAME = "stations.json";
	private static final String CACHE_MANIFEST = "manifest.json";
	/** 示例配置文件名：永远不被加载，仅作为格式参考；文件名不以 .json 结尾以外无特殊含义。 */
	/**
	 * 示例条目的保留 id：id 等于该值的条目永远不会被加载、也不会参与哈希校验。
	 * 服主可以放心把示例留在 stations.json 里作为格式参考，改掉 id 即正式生效。
	 */
	public static final String EXAMPLE_ID = "example";
	/** 示例音频 / 图标所在目录名，会被哈希与加载流程整体排除。 */
	private static final String EXAMPLE_ASSETS_DIR = "example";

	private static volatile List<RadioStation> stations = List.of();
	private static volatile Map<String, String> fileHashes = Map.of();

	/**
	 * <b>配置校验失败的频道 id</b>：有配置条目，但音频/文件不可用。
	 *
	 * <h2>为什么要把"配置失败"与"没配置"分开</h2>
	 * 用户要求两句截然不同的处理：
	 * <ol>
	 *   <li><b>配置失败就不显示这个频道</b>——它根本不该出现在选台列表里，
	 *       玩家没法选一个注定放不出来的台；</li>
	 *   <li>但<b>已经配置好、被重载搞坏</b>的频道（以及完全不存在的未知频道）
	 *       要<b>持续噪音 + 显示 NOSIGNAL</b>——因为玩家的收音机方块上
	 *       还记着那个频道 id，不能让他面对一块"什么都不显示"的死方块。</li>
	 * </ol>
	 *
	 * <p>判据的差别就在这：<b>列表要不要展示</b>用「配置是否有效」；
	 * <b>方块要不要报警</b>用「该方块记的频道能否播出来」。
	 */
	private static volatile java.util.Set<String> invalidStationIds = java.util.Set.of();

	/** 配置校验失败（音频不可用）的频道 id 集合（不可变快照）。 */
	public static java.util.Set<String> invalidStationIds() {
		return invalidStationIds;
	}
	/** 播放计时基准：所有客户端用它计算「当前应播放到第几毫秒」。 */
	private static volatile long startEpochMillis = System.currentTimeMillis();
	private static Path baseDir;

	private RadioStationRegistry() {
	}

	public static List<RadioStation> stations() {
		return stations;
	}

	public static long startEpochMillis() {
		return startEpochMillis;
	}

	/**
	 * 频道级的额外播放偏移（毫秒）。
	 *
	 * <p>由 {@code /radio play <频道> <序号>} 设定：把该频道「此刻该播到哪」整体挪一段，
	 * 于是这个频道的所有听众同时跳到目标曲目开头，其他频道不受影响。
	 * 重载配置时清空——那时计时基准也会重置，旧偏移没有意义。
	 */
	private static final Map<String, Long> stationShifts = new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * 重载瞬间各频道「正在播」的曲目。
	 *
	 * <p>重建时间轴时要避开它，否则重载后第一首还是刚才那首。
	 * 取值在 {@link #load} 清缓存之前抓取，只在下次构建时间轴时使用。
	 */
	private static final Map<String, Path> headsBeforeReload = new java.util.concurrent.ConcurrentHashMap<>();

	/** 取某频道的额外播放偏移（毫秒），未设定时为 0。 */
	public static long stationShiftMillis(String stationId) {
		Long shift = stationId == null ? null : stationShifts.get(stationId);
		return shift == null ? 0L : shift;
	}

	/** 设定某频道的额外播放偏移（毫秒）。 */
	public static void setStationShiftMillis(String stationId, long millis) {
		if (stationId != null) {
			stationShifts.put(stationId, millis);
		}
	}

	/** 清空所有频道的额外播放偏移。 */
	public static void clearStationShifts() {
		stationShifts.clear();
	}

	/**
	 * 只刷新一个频道（{@code /radio reload <频道>}）。
	 *
	 * <p>与 {@link #load} 的区别：<b>不动计时基准、不清其他频道的缓存</b>。
	 * 因此其他频道正在播的内容完全不受影响，只有目标频道的订阅会被重建
	 * （重建由 {@code AudioStreamManager.rebuildStation} 完成）。
	 *
	 * <p>做三件事：记住它此刻播的是哪首（新时间轴要避让）、清掉它自己的
	 * 时间轴缓存（这样曲目列表变化会立刻生效）、清掉偏移（回到统一基准）。
	 *
	 * @param stationId 目标频道 id
	 * @return 该频道是否存在
	 */
	public static synchronized boolean reloadStation(String stationId) {
		RadioStation target = null;
		for (RadioStation station : stations) {
			if (station.id().equals(stationId)) {
				target = station;
				break;
			}
		}
		if (target == null) {
			return false;
		}
		long elapsed = Math.max(0L, System.currentTimeMillis() - startEpochMillis);
		headsBeforeReload.clear();
		captureHead(target, elapsed);
		// txt 重定向列表可能被改过，一并清掉；它按内容哈希缓存，重算成本很低。
		TxtPlaylist.clearCache();
		PlaylistTimeline.clearCacheFor(stationId);
		stationShifts.remove(stationId);
		SmileSRadio.LOGGER.info("频道 {}：已单独刷新（计时基准不变）", stationId);
		return true;
	}

	/** 重载瞬间该频道正在播的曲目（实际推流的 MP3），没有则为 {@code null}。 */
	public static Path headBeforeReload(String stationId) {
		return stationId == null ? null : headsBeforeReload.get(stationId);
	}

	/** 配置根目录名。 */
	private static final String CONFIG_DIR_NAME = "radio-smile";

	public static Path baseDir() {
		return baseDir;
	}

	/**
	 * 解析配置文件根目录：{@code <游戏根目录>/config/radio-smile/}。
	 *
	 * <p>用 Fabric 的 {@code getConfigDir()} 而不是自己拼 `.`：
	 * 专用服务器与开发环境的"根目录"不同（{@code run/} vs 服务器目录），
	 * Fabric 已经帮我们处理了。
	 */
	private static Path resolveConfigRoot() {
		return net.fabricmc.loader.api.FabricLoader.getInstance()
				.getConfigDir().resolve(CONFIG_DIR_NAME);
	}

	/**
	 * 把旧位置（存档内 {@code serverconfig/radio-smile/}）的配置迁移到新的全局位置。
	 *
	 * <p>只在<b>新位置还没有 stations.json</b> 且旧位置存在时执行，
	 * 因此不会覆盖服主已经在新位置改好的配置。
	 *
	 * <p>迁移是<b>整棵目录复制</b>（含 audio/ icons/），因为配置文件里的
	 * {@code file}/{@code icon} 都是相对路径，只搬 json 会让它们全部失效。
	 */
	private static void migrateFromWorldIfNeeded(Path worldRoot) {
		if (worldRoot == null) {
			return;
		}
		Path oldDir = worldRoot.resolve("serverconfig").resolve(CONFIG_DIR_NAME);
		Path oldIndex = oldDir.resolve(INDEX_NAME);
		Path newIndex = baseDir.resolve(INDEX_NAME);
		if (Files.exists(newIndex) || !Files.exists(oldIndex)) {
			// 新位置已有配置（不覆盖），或旧位置根本没有（无需迁移）
			return;
		}
		try {
			Files.walkFileTree(oldDir, new java.nio.file.SimpleFileVisitor<Path>() {
				@Override
				public java.nio.file.FileVisitResult visitFile(Path file,
						java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
					Path target = baseDir.resolve(oldDir.relativize(file).toString());
					Files.createDirectories(target.getParent());
					Files.copy(file, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
					return java.nio.file.FileVisitResult.CONTINUE;
				}
			});
			SmileSRadio.LOGGER.info("电台配置已从旧位置迁移到全局 config：{} -> {}", oldDir, baseDir);
		} catch (IOException e) {
			SmileSRadio.LOGGER.warn("电台配置迁移失败（旧位置 {}），请手动搬移到 {}", oldDir, baseDir, e);
		}
	}

	public static Path audioDir() {
		return baseDir == null ? null : baseDir.resolve("audio");
	}

	public static Path iconDir() {
		return baseDir == null ? null : baseDir.resolve("icons");
	}

	public static Path cacheDir() {
		return baseDir == null ? null : baseDir.resolve("cache");
	}

	/**
	 * 加载 / 重载电台配置。服务端启动与 /reload 都走这里。
	 *
	 * <h2>配置位置已从「存档内」迁到「全局 config 下」</h2>
	 * 早期路径是 {@code <世界存档>/serverconfig/radio-smile/}——按<b>存档</b>隔离。
	 * 那带来两个实际麻烦：
	 * <ol>
	 *   <li>换存档、开新世界就要把音频和配置重配一遍（几十 MB 的文件复制）；</li>
	 *   <li>服务端开多个存档/多世界时，每个存档各有一份，改了一处别处不同步。</li>
	 * </ol>
	 * 现在统一放到 {@code <游戏根目录>/config/radio-smile/}（全局唯一），
	 * 与客户端的 {@code config/radio-smile-client.json} 同级，服主只需维护一份。
	 *
	 * <p><b>旧位置会自动迁移</b>：若新位置还没有配置、而旧存档里有，
	 * 首次加载时把整棵目录搬过来（见 {@link #migrateFromWorldIfNeeded}），
	 * 服主无需手动搬文件。
	 *
	 * @param worldRoot 世界存档根目录（仅用于旧位置迁移，不再作为配置主目录）
	 */
	public static synchronized void load(Path worldRoot) {
		baseDir = resolveConfigRoot();
		try {
			Files.createDirectories(baseDir);
			Files.createDirectories(audioDir());
			Files.createDirectories(iconDir());
			Files.createDirectories(cacheDir());
		} catch (IOException e) {
			SmileSRadio.LOGGER.error("无法创建电台目录 {}", baseDir, e);
			return;
		}
		// 旧位置（存档内 serverconfig）→ 新位置，只在"新位置还没有配置"时搬
		migrateFromWorldIfNeeded(worldRoot);

		// 配置重载：清掉各层缓存，保证内容变更立即生效。
		// PlaylistTimeline 也要清：它固化了「起点轮次随机偏移」，
		// 复用旧实例会导致重载后打乱模式的第一首仍然是同一首。
		// 顺序很重要：必须在清缓存<b>之前</b>抓取「此刻正在播哪首」，
		// 新时间轴据此避开它（否则重载后第一首还是刚才那首）。
		captureHeadsBeforeReload();
		TxtPlaylist.clearCache();
		radiosmile.radio.PlaylistTimeline.clearCache();
		clearStationShifts();
		clearIconCache();
		// 响度增益缓存也要清：目标电平可能改了，且曲目集合可能变了。
		// 磁盘上的实测元数据保留——它只跟文件内容有关，与配置无关。
		LoudnessNormalizer.clearCache();

		List<RadioStation> all = readIndex();
		// <b>加载时即校验</b>：把"配置了但放不出来"的频道挑出来。
		//
		// 用户要求「配置失败就不显示这个频道」——所以无效条目<b>不进入 stations</b>，
		// 客户端列表里根本看不到它；同时把 id 记进 invalidStationIds，
		// 供"方块上还记着它"的场景去报警（持续噪音 + NOSIGNAL）。
		//
		// 注意这里<b>不能</b>把无效频道直接丢掉不管：玩家的收音机方块 NBT 里
		// 可能还存着那个 id，需要在播放/显示层据此判定为未知频道。
		java.util.Set<String> invalid = new java.util.LinkedHashSet<>();
		List<RadioStation> valid = new ArrayList<>();
		for (RadioStation station : all) {
			if (isPlayable(station)) {
				valid.add(station);
			} else {
				invalid.add(station.id());
				SmileSRadio.LOGGER.warn("频道 {} 配置无效，将从列表中隐藏：{}",
						station.id(), station.file());
			}
		}
		invalidStationIds = java.util.Collections.unmodifiableSet(invalid);
		stations = java.util.Collections.unmodifiableList(valid);
		rebuildCacheIfChanged();

		// 每次加载 / 重载都重置播放计时基准
		startEpochMillis = System.currentTimeMillis();
		SmileSRadio.LOGGER.info("电台配置已加载：{} 个频道（另有 {} 个配置无效已隐藏），计时基准 {}",
				stations.size(), invalid.size(), startEpochMillis);
	}

	/**
	 * 记录各频道在重载瞬间正在播的曲目。
	 *
	 * <p>用<b>旧</b>计时基准算位置，并且刻意走 {@link PlaylistTimeline#of} 取缓存实例
	 * （此时缓存还没清），因此不会额外扫一遍时长。
	 * 非播放列表频道、曲池只有一首的频道不需要避让，直接跳过。
	 */
	private static void captureHeadsBeforeReload() {
		headsBeforeReload.clear();
		long elapsed = Math.max(0L, System.currentTimeMillis() - startEpochMillis);
		for (RadioStation station : stations) {
			captureHead(station, elapsed);
		}
	}

	/** 记录单个频道在这一刻正在播的曲目（见 {@link #captureHeadsBeforeReload()}）。 */
	private static void captureHead(RadioStation station, long elapsed) {
		if (station == null || !station.isPlaylist()) {
			return;
		}
		List<Path> tracks = resolveTracks(station);
		if (tracks.size() <= 1) {
			return;
		}
		PlaylistTimeline timeline = PlaylistTimeline.of(
				station.id(), tracks, station.isRandom(), station.seed(), null);
		if (timeline == null) {
			return;
		}
		int round = timeline.roundOf(elapsed);
		long[] located = timeline.locate(round, timeline.positionInRound(elapsed));
		Path head = timeline.fileAt(round, (int) located[0]);
		if (head != null) {
			headsBeforeReload.put(station.id(), head);
		}
	}

	private static List<RadioStation> readIndex() {
		Path index = baseDir.resolve(INDEX_NAME);
		if (!Files.exists(index)) {
			writeDefaultIndex(index);
		}
		try (Reader reader = Files.newBufferedReader(index, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);

			// <b>顶层非频道字段：ffmpeg 可执行文件路径</b>（频道列表之外，用户要求）。
			//
			// 用途：某些服务器上 ffmpeg 不在 PATH 里（自建容器、精简系统、
			// 多版本共存需要指定某个特定构建），或根本没有安装名就叫 ffmpeg 的程序。
			// 允许在这里写绝对路径，转码预热就会用它。
			//
			// 只在"配置文件由对象构成"时读取（老格式是裸数组，没有地方放顶层字段），
			// 读不到/为空都表示"用 PATH 里的 ffmpeg"，不影响既有部署。
			if (root.isJsonObject()) {
				readTopLevelFfmpeg(root.getAsJsonObject());
				readTopLevelTargetLoudness(root.getAsJsonObject());
			}

			JsonArray arr = root.isJsonObject()
					? root.getAsJsonObject().getAsJsonArray("stations")
					: root.getAsJsonArray();
			if (arr == null) {
				return List.of();
			}
			List<RadioStation> parsed = new ArrayList<>();
			for (JsonElement el : arr) {
				if (!el.isJsonObject()) {
					continue;
				}
				RadioStation station = RadioStation.fromJson(el.getAsJsonObject());
				if (station.id() == null || station.id().isBlank()) {
					continue;
				}
				// 预留 id 的条目视为示例，不加载
				if (isExampleStation(station)) {
					continue;
				}
				parsed.add(station);
			}
			return Collections.unmodifiableList(parsed);
		} catch (Exception e) {
			SmileSRadio.LOGGER.error("读取电台配置失败 {}", index, e);
			return List.of();
		}
	}

	/**
	 * 逐曲响度归一化的默认目标电平（LUFS）。
	 *
	 * <h2>-30 是怎么来的</h2>
	 * 不是拍的。2026-11-26 拿一段《明日方舟：终末地》实机录像作听感基准，
	 * 把录像音轨与源曲拆成 8 个 1/3 倍频程段做电平对照：
	 *
	 * <table border="1">
	 *   <caption>各频段「录像内 BGM 电平 − 源曲电平」</caption>
	 *   <tr><td>60-120</td><td>120-250</td><td>250-500</td><td>500-1k</td>
	 *       <td>1k-2k</td><td>2k-4k</td><td>4k-8k</td><td>8k-16k</td></tr>
	 *   <tr><td>-22.3</td><td>-19.7</td><td>-19.6</td><td>-21.7</td>
	 *       <td>-21.2</td><td>-19.3</td><td>-20.3</td><td>-14.8</td></tr>
	 * </table>
	 *
	 * <p>八段几乎平行地落在 -20 dB 附近（取更严格的低电平分位后收敛到 -21~-22 dB），
	 * 也就是"游戏里的 BGM 相对母带被整体压低约 20 dB"。源曲实测 -9.9 LUFS，
	 * 压 20 dB 即 -30 LUFS，正是听感基准的绝对电平。
	 *
	 * <p><b>所以这里的目标是"100% 音量时输出 -30 LUFS"</b>，而不是"乘一个安全系数"。
	 * 逐曲实测之后，任何母带的曲子都会被拉到这个电平：实测电平存进缓存元数据，
	 * 播放时按「目标 − 实测」换算。早期那个固定增益（{@code outputGain}）
	 * 适配不了母带差异，已经被它取代，配置里不再保留这个旋钮。
	 */
	public static final float DEFAULT_TARGET_LOUDNESS = -30.0F;

	/**
	 * 逐曲响度归一化的目标电平（LUFS），默认 {@link #DEFAULT_TARGET_LOUDNESS}。
	 *
	 * <p>负数才有效；{@code 0}（或正数）表示<b>关闭归一化</b>，此时本地曲目
	 * 原样播放（不做任何压缩）。
	 */
	private static volatile float targetLoudness = DEFAULT_TARGET_LOUDNESS;

	/** 当前生效的目标电平（LUFS）；{@code >= 0} 表示归一化已关闭。 */
	public static float targetLoudness() {
		return targetLoudness;
	}

	/**
	 * 读取顶层的 {@code targetLoudness}（LUFS，如 {@code -30}；{@code 0} = 关闭）。
	 *
	 * <p>缺失 / 非法都回落到默认值。这是「逐曲归一化」唯一的调节旋钮：
	 * 实测电平存在缓存元数据里，这里只决定<b>要拉到多响</b>。
	 */
	private static void readTopLevelTargetLoudness(JsonObject root) {
		if (!root.has("targetLoudness") || root.get("targetLoudness").isJsonNull()) {
			targetLoudness = DEFAULT_TARGET_LOUDNESS;
			SmileSRadio.LOGGER.info("响度归一化：未配置 targetLoudness，按默认 {} LUFS", DEFAULT_TARGET_LOUDNESS);
			return;
		}
		float raw;
		try {
			raw = root.get("targetLoudness").getAsFloat();
		} catch (Exception e) {
			SmileSRadio.LOGGER.warn("配置项 targetLoudness 不是数字，已按默认 {} 处理：{}",
					DEFAULT_TARGET_LOUDNESS, root.get("targetLoudness"));
			targetLoudness = DEFAULT_TARGET_LOUDNESS;
			return;
		}
		if (Float.isNaN(raw)) {
			targetLoudness = DEFAULT_TARGET_LOUDNESS;
			return;
		}
		if (raw >= 0.0F) {
			targetLoudness = 0.0F;
			SmileSRadio.LOGGER.info("响度归一化已关闭（targetLoudness={}），本地曲目原样播放", raw);
			return;
		}
		// 低于 -70 LUFS 的目标没有意义（人耳听不见），按默认处理
		if (raw < -70.0F) {
			SmileSRadio.LOGGER.warn("配置项 targetLoudness={} 过低，已按默认 {} 处理",
					raw, DEFAULT_TARGET_LOUDNESS);
			targetLoudness = DEFAULT_TARGET_LOUDNESS;
			return;
		}
		targetLoudness = raw;
		SmileSRadio.LOGGER.info("响度归一化：目标 {} LUFS（逐曲按实测电平换算增益）", raw);
	}

	/**
	 * 读取配置顶层的 {@code ffmpeg} 字段并应用。
	 *
	 * <p>处理三种情况，且都必须有明确日志——转码失败是"猜不出来"的故障：
	 * <ul>
	 *   <li>字段缺失 / 空串 → 用 PATH 里的 ffmpeg（默认行为，不打扰）；</li>
	 *   <li>字段指向的文件存在 → 应用，并记一行 INFO；</li>
	 *   <li>字段指向的文件<b>不存在</b> → 打 WARN 并说明"回退到默认，
	 *       若 PATH 里也没有则转码会失败"，而不是悄悄忽略。</li>
	 * </ul>
	 */
	private static void readTopLevelFfmpeg(JsonObject root) {
		if (!root.has("ffmpeg") || root.get("ffmpeg").isJsonNull()) {
			AudioTranscoder.setFfmpegPath(null);
			return;
		}
		String configured;
		try {
			configured = root.get("ffmpeg").getAsString();
		} catch (Exception e) {
			SmileSRadio.LOGGER.warn("配置项 ffmpeg 不是字符串，已忽略：{}", root.get("ffmpeg"));
			AudioTranscoder.setFfmpegPath(null);
			return;
		}
		if (configured == null || configured.isBlank()) {
			AudioTranscoder.setFfmpegPath(null);
			return;
		}
		configured = configured.trim();
		// 含路径分隔符才当文件校验；裸命令名（如 "ffmpeg"）交给系统去 PATH 找
		boolean looksLikePath = configured.contains("/") || configured.contains("\\");
		if (looksLikePath && !Files.isRegularFile(Path.of(configured))) {
			SmileSRadio.LOGGER.warn(
					"配置的 ffmpeg 路径不存在：{} —— 将回退到 PATH 里的 ffmpeg，"
							+ "若那里也没有则无法转码（非 MP3 频道会被拒绝播放）",
					configured);
			AudioTranscoder.setFfmpegPath(null);
			return;
		}
		AudioTranscoder.setFfmpegPath(configured);
		SmileSRadio.LOGGER.info("已按配置指定 ffmpeg：{}", configured);
	}

	/**
	 * 自动创建配置文件 {@code stations.json}，并在其中放入示例条目。
	 *
	 * <p>示例条目的 {@code id} 使用保留值 {@link #EXAMPLE_ID}（即 {@code example}）。
	 * 加载时会跳过所有 id 为 {@code example} 的条目，因此示例：
	 * <ul>
	 *   <li>不会出现在游戏内的电台列表中</li>
	 *   <li>不会被扫描音频哈希，不会产生「文件不存在」噪声警告</li>
	 * </ul>
	 * 服主只需把示例的 id 改成别的名字，它就成为正式电台。
	 */
	private static void writeDefaultIndex(Path index) {
		JsonObject root = new JsonObject();

		// <b>顶层：ffmpeg 可执行文件路径</b>（频道列表之外，用户要求）。
		// 空串 = 用系统 PATH 里的 ffmpeg，绝大多数情况保持默认即可。
		root.addProperty("ffmpeg", "");
		root.addProperty("_说明_ffmpeg", "ffmpeg 可执行文件的完整路径。留空则使用系统 PATH 里的 ffmpeg；"
				+ "仅当服务器上 ffmpeg 不在 PATH、或需要指定某个特定版本时才填"
				+ "（例如 C:\\tools\\ffmpeg\\bin\\ffmpeg.exe 或 /usr/local/bin/ffmpeg）");

		// <b>逐曲响度归一化</b>：实测电平进缓存元数据，播放时按曲换算增益
		root.addProperty("targetLoudness", DEFAULT_TARGET_LOUDNESS);
		root.addProperty("_说明_targetLoudness",
				"逐曲响度归一化的目标电平（LUFS），默认 -30；填 0 或正数表示关闭。"
						+ "开启后服务器会在预热阶段用 ffmpeg 实测每首曲目的响度，"
						+ "结果存进 cache/loudness/（按文件哈希命名），播放时按"
						+ "「目标电平 − 实测电平」自动换算增益下发，"
						+ "多首不同母带的曲子响度就能对齐。"
						+ "实测只在首次预热时做一次，改这个目标值立刻生效、不用重测；"
						+ "要重测某首，删掉 cache/loudness/ 下对应条目再重载配置即可。"
						+ "归一化只作用于本地曲目：网络电台是原样透传的直播流，无法实测");

		// 配置说明：写进文件本身，服主打开就能看懂，不必回来翻文档。
		root.addProperty("_说明_播放模式", "mode 可选值见下面的示例条目；"
				+ "单曲循环=block/false/0，列表循环=sequence/loop/true/1，随机播放=random");
		root.addProperty("_说明_rate", "rate 是列表右侧显示的频率/档位，例如 FM90.7；不填显示 NETWORK");
		root.addProperty("_说明_txt", "file 也可以指向一个 .txt（每行一个本地文件或网络地址）；"
				+ "txt 的第一行是打乱顺序开关：false/0=按顺序（默认），true/1=打乱");
		root.addProperty("_说明_示例", "id 为 " + EXAMPLE_ID + " 的条目只是示范，不会被加载；"
				+ "把 id 改成别的名字即生效");

		JsonArray arr = new JsonArray();
		arr.add(sampleStation(EXAMPLE_ID,
				"示例1：单个音频｜单曲循环（mode=block，也可写 false / 0）",
				EXAMPLE_ASSETS_DIR + "/sample.png", EXAMPLE_ASSETS_DIR + "/sample.mp3",
				"block"));
		arr.add(sampleStation(EXAMPLE_ID,
				"示例2：文件夹列表循环，按文件名 1,2,3…（mode=sequence，也可写 loop / true / 1）",
				EXAMPLE_ASSETS_DIR + "/playlist.png", EXAMPLE_ASSETS_DIR + "/myfolder",
				"sequence"));
		arr.add(sampleStation(EXAMPLE_ID,
				"示例3：文件夹随机播放，固定 seed 保证所有玩家顺序一致（mode=random）",
				EXAMPLE_ASSETS_DIR + "/playlist.png", EXAMPLE_ASSETS_DIR + "/myfolder2",
				"random"));
		arr.add(sampleStation(EXAMPLE_ID,
				"示例4：txt 重定向播放列表，可混写本地文件与网络地址（file 指向 .txt）",
				EXAMPLE_ASSETS_DIR + "/playlist.png", EXAMPLE_ASSETS_DIR + "/mylist.txt",
				"sequence"));
		arr.add(sampleStation(EXAMPLE_ID,
				"示例5：网络直播流（file 直接写 http 地址，不能写进 txt）",
				EXAMPLE_ASSETS_DIR + "/webradio.png", "https://example.com/stream.mp3",
				"block"));
		root.add("stations", arr);

		try {
			Files.writeString(index, GSON.toJson(root), StandardCharsets.UTF_8);
			SmileSRadio.LOGGER.info("已自动创建电台配置 {}，其中 id 为 {} 的条目不会被加载",
					index, EXAMPLE_ID);
			// 顺手生成一份 txt 播放列表示例，让"示例4"能直接参考格式
			writeSampleTxt();
		} catch (IOException e) {
			SmileSRadio.LOGGER.error("写入电台配置失败", e);
		}
	}

	/**
	 * 生成 {@code example/mylist.txt} 示例文件（txt 重定向播放列表的格式参考）。
	 *
	 * <p>放在 {@code example/} 目录下：与示例图片/音频同处一地，
	 * 且该目录被哈希校验与加载流程整体排除，不会产生"文件不存在"的警告。
	 */
	private static void writeSampleTxt() {
		Path exampleDir = baseDir.resolve(EXAMPLE_ASSETS_DIR);
		try {
			Files.createDirectories(exampleDir);
			Path txt = exampleDir.resolve("mylist.txt");
			if (Files.exists(txt)) {
				return;
			}
			String content = String.join("\n",
					"# txt 重定向播放列表示例",
					"#",
					"# 【第一行】打乱顺序开关：",
					"#   false（或 0、或整行省略）= 不打乱，严格按下面从上到下的顺序播放",
					"#   true （或 1）             = 打乱顺序，所有玩家听到的顺序一致",
					"false",
					"",
					"# 【之后每行】一个音频来源，可以是：",
					"#   本地文件（相对 audio/ 目录）：",
					"sample.mp3",
					"#   网络地址（会先下载到本地缓存，再转成 MP3 播放）：",
					"# https://example.com/song.mp3",
					"",
					"# 空行、以及 # 开头的行都会被忽略");
			Files.writeString(txt, content, StandardCharsets.UTF_8);
		} catch (IOException e) {
			SmileSRadio.LOGGER.debug("写入示例 txt 失败", e);
		}
	}

	private static JsonObject sampleStation(String id, String name, String icon, String file, String mode) {
		JsonObject obj = new JsonObject();
		obj.addProperty("id", id);
		obj.addProperty("name", name);
		obj.addProperty("icon", icon);
		obj.addProperty("file", file);
		obj.addProperty("mode", mode);
		// rate 可选：列表右侧显示的频率/档位（如 FM90.7、"第一期"）。
		// 不填则显示 NETWORK（见 RadioStation#displayRate）。
		obj.addProperty("rate", "");
		return obj;
	}

	/**
	 * 若缓存缺失或任一 file 的哈希与清单不一致，则重新生成拼接音频；否则直接复用。
	 */
	/**
	 * 校验音频哈希，并<b>顺带预热转码缓存</b>。
	 *
	 * <p>两者共用同一趟遍历：这里已经为每个频道解析出音频文件、算好 SHA-256，
	 * 正好可以据此判断「转码产物是否已存在」。合并后只需扫一次磁盘，
	 * 也保证「哈希校验」和「转码预热」看到的是完全一致的频道集合。
	 */
	private static synchronized void rebuildCacheIfChanged() {
		Map<String, String> current = new LinkedHashMap<>();
		// 需要转码预热的源文件（非 MP3，且对应产物尚不存在）
		List<Path> needBake = new ArrayList<>();
		// 需要响度实测的源文件（含 MP3——它们不转码，但同样要归一化）
		List<Path> needMeasure = new ArrayList<>();

		for (RadioStation station : stations) {
			if (station.isStreaming()) {
				continue;
			}
			List<Path> tracks = resolveTracks(station);
			if (tracks.isEmpty()) {
				SmileSRadio.LOGGER.warn("频道 {} 没有可用音频：{}", station.id(), station.file());
				continue;
			}
			// 播放列表：把所有曲目的哈希合并成一个频道级哈希，
			// 这样任一首新增/修改/删除都会使缓存失效。
			//
			// <b>用 quickHash 而不是整文件 sha256</b>：这里每个文件都要算，
			// 频道一多就是"每次 /reload 重读所有音频"。音频头 + 文件大小
			// 已经足够判断"变了没有"（见 quickHash 的说明）。
			StringBuilder combined = new StringBuilder();
			for (Path track : tracks) {
				combined.append(track.getFileName().toString()).append(':')
						.append(quickHash(track)).append(';');
			}
			current.put(station.id(), tracks.size() == 1
					? quickHash(tracks.get(0))
					: sha256Text(combined.toString()));

			// 同一趟遍历里收集待转码文件
			for (Path track : tracks) {
				if (AudioTranscoder.needsBake(track)) {
					needBake.add(track);
				}
				// 响度实测覆盖<b>全部</b>本地曲目（含直接用 MP3 推流的那些）：
				// 归一化的对象是「放出来的声音」，不是「需要转码的文件」。
				needMeasure.add(track);
			}
		}

		Map<String, String> cached = readManifest();
		boolean same = !cached.isEmpty() && cached.equals(current);
		if (same) {
			fileHashes = Collections.unmodifiableMap(current);
			SmileSRadio.LOGGER.debug("音频哈希校验通过，复用缓存");
		} else {
			SmileSRadio.LOGGER.info("音频哈希已变更，更新清单（{} 个文件）", current.size());
			writeManifest(current);
			fileHashes = Collections.unmodifiableMap(current);
		}

		// 校验完顺手预热：还没转码成 MP3 的文件在这里统一排队
		AudioTranscoder.preheat(needBake);

		// 响度归一化：还没实测过的曲目在后台补测一次，结果写进缓存元数据。
		// 与转码预热分开排队——本方法返回前不会阻塞服务端启动。
		LoudnessNormalizer.preheat(needMeasure);

		// <b>txt 里的网络地址也要在后台下载</b>：
		// 上面的 resolveTracks() 用的是 parseCached，首次遇到网络条目会同步下载——
		// 那是发生在主线程上的（load() 由服务端启动/reload 调用）。
		// 因此这里单独起一次后台预热，让"首次下载"发生在后台线程，
		// 玩家进入时若还没下完就当"暂时没有音频"，下完后再订阅即可。
		preheatTxtNetworkEntries();
	}

	/**
	 * 该频道<b>配置是否有效</b>（能不能真正放出声）。
	 *
	 * <p>用于「配置失败就不显示这个频道」：
	 * <ul>
	 *   <li><b>网络电台</b>：只要地址是合法 http(s) 就认为有效——
	 *       上游此刻通不通<b>不在配置校验范围内</b>（那是运行时的事，
	 *       断流时客户端显示 NOSIGNAL，而不是把它从列表里删掉）。</li>
	 *   <li><b>本地电台</b>：解析出的音频文件列表非空，且<b>至少有一首能转码/直接播</b>。
	 *       文件不存在、目录为空、格式无法转码 → 无效。</li>
	 * </ul>
	 *
	 * <p>这样「配了一个放不出来的本地台」不会挂在列表里误导玩家，
	 * 而「配好的网络台偶尔断流」仍然留在列表里正常报警。
	 */
	public static boolean isPlayable(RadioStation station) {
		if (station == null || station.id() == null || station.id().isBlank()) {
			return false;
		}
		if (station.isStreaming()) {
			return true;
		}
		// <b>txt 播放列表要特殊处理</b>：resolveTracks() 会下载网络条目，
		// 而本方法在服务端启动主线程上被调用——下载几十 MB 会把开服卡死。
		// 这里只解析本地条目，网络条目交给后台预热（见 preheat 流程）。
		Path direct = resolveAudioPath(station);
		List<Path> tracks;
		if (direct != null && Files.isRegularFile(direct)
				&& TxtPlaylist.isTxtPlaylist(direct)) {
			TxtPlaylist.Parsed local = TxtPlaylist.parseLocalOnly(direct, audioDir());
			tracks = local == null ? List.of() : local.entries();
			if (tracks.isEmpty()) {
				// 还没有任何本地可用条目：先不判定为无效。
				// 含网络地址的 txt 要等后台预热下载完成后才算"能播"，
				// 此时返回 true 让它留在列表里（预热完成后再由实际播放验证）。
				return TxtPlaylist.hasNetworkEntries(direct);
			}
		} else {
			tracks = resolveTracks(station);
		}
		if (tracks.isEmpty()) {
			return false;
		}
		// 至少有一首是可播的（MP3 原文件，或能转码的格式）
		for (Path track : tracks) {
			if (radiosmile.radio.AudioFormat.ofFile(track.getFileName().toString())
					== radiosmile.radio.AudioFormat.MP3) {
				return true;
			}
			if (AudioTranscoder.isTranscodableSource(track)) {
				return true;
			}
		}
		return false;
	}

	/** 是否已启动过 txt 网络预热线程（避免重复提交）。 */
	private static volatile boolean txtPreheatStarted;

	/**
	 * 后台预热 txt 播放列表里的网络条目（下载 + 转码）。
	 *
	 * <p>为什么要单独做：{@code resolveTracks()} 遇到网络地址会同步下载，
	 * 而它在首次 {@link #load} 时是被主线程调用的。若某个 txt 里放了几个
	 * 几十 MB 的网络音频，开服会卡住很久——用户看到的是"服务器卡在启动"。
	 *
	 * <p>这里把下载放到后台单线程：启动立刻返回，音频在后台陆续就绪。
	 * 下载完成后 {@link TxtPlaylist#parseCached} 会命中缓存，播放时无需再等。
	 */
	private static void preheatTxtNetworkEntries() {
		if (txtPreheatStarted) {
			return;
		}
		txtPreheatStarted = true;
		Thread thread = new Thread(() -> {
			// 收集所有 txt 频道（只读配置，不碰世界状态）
			for (RadioStation station : stations) {
				if (station.isStreaming()) {
					continue;
				}
				Path path = resolveAudioPath(station);
				if (path == null || !Files.isRegularFile(path)
						|| !TxtPlaylist.isTxtPlaylist(path)) {
					continue;
				}
				try {
					// parse()（无缓存版）会真正下载网络条目
					TxtPlaylist.Parsed parsed = TxtPlaylist.parse(path, audioDir());
					if (parsed != null) {
						SmileSRadio.LOGGER.info("txt 播放列表已预热：{}（{} 个条目）",
								path.getFileName(), parsed.entries().size());
					}
				} catch (Throwable t) {
					SmileSRadio.LOGGER.warn("预热 txt 播放列表失败 {}", path.getFileName(), t);
				}
			}
			// 预热完成后清一次解析缓存，让下次 resolveTracks() 拿到完整结果
			TxtPlaylist.clearCache();
		}, "RadioSmile-TxtPreheat");
		thread.setDaemon(true);
		thread.start();
	}

	/** 是否为示例条目：id 等于保留值 {@link #EXAMPLE_ID} 时整条忽略。 */
	public static boolean isExampleStation(RadioStation station) {
		return station != null && EXAMPLE_ID.equalsIgnoreCase(station.id());
	}

	/** 是否为示例素材路径（{@code example/} 下的内容永远不参与加载与校验）。 */
	private static boolean isExamplePath(String path) {
		if (path == null || path.isBlank()) {
			return true;
		}
		String normalized = path.replace('\\', '/');
		return normalized.startsWith(EXAMPLE_ASSETS_DIR + "/")
				|| normalized.contains("/" + EXAMPLE_ASSETS_DIR + "/")
				|| normalized.startsWith(EXAMPLE_ASSETS_DIR + "/");
	}

	/**
	 * 解析 {@code file} 字段指向的路径。
	 *
	 * <p>既可能是单个音频文件，也可能是一个目录（播放列表模式）。
	 */
	private static Path resolveAudioPath(RadioStation station) {
		if (station.isStreaming() || station.file() == null || station.file().isBlank()) {
			return null;
		}
		// 示例路径直接视为不存在，避免哈希校验产生噪声
		if (isExamplePath(station.file())) {
			return null;
		}
		String relative = normalizeSeparators(station.file());
		Path direct = baseDir.resolve(relative).normalize();
		if (Files.isRegularFile(direct) || Files.isDirectory(direct)) {
			return direct;
		}
		return audioDir().resolve(relative).normalize();
	}

	/**
	 * 把配置里的路径统一成当前平台的分隔符。
	 *
	 * <h2>为什么必须做这一步</h2>
	 * 服主常在 Windows 上配好文件、再上传到 Linux 服务器运行。
	 * Windows 的 resource pack / 配置里习惯写 {@code audio\song.mp3}，
	 * 而 <b>Linux 上反斜杠是合法的文件名字符，不是分隔符</b>——
	 * 于是 {@code Path.resolve("audio\\song.mp3")} 会去找一个名叫
	 * {@code audio\song.mp3} 的单层文件，永远找不到。
	 *
	 * <p>症状极其隐晦：日志只说"频道没有可用音频"，服主看着目录里明明有文件。
	 * 反过来（Linux 的 {@code /} 用在 Windows 上）Java 本来就能正确处理，
	 * 所以只需要把 {@code \} 换成 {@code /}。
	 *
	 * <p>注意：<b>不处理 URL</b>（网络地址里不会出现反斜杠作为分隔符），
	 * 调用方已先排除 {@code isStreaming()}。
	 */
	private static String normalizeSeparators(String path) {
		return path == null ? null : path.replace('\\', '/');
	}

	/**
	 * 解析一个电台实际要播放的音频文件列表。
	 *
	 * <ul>
	 *   <li>单文件模式：返回长度为 1 的列表</li>
	 *   <li>目录 + {@code sequence}：按文件名自然序（1,2,10）</li>
	 *   <li>目录 + {@code random}：用 {@link RadioStation#seed()} 打乱，
	 *       所有客户端结果一致，从而保证「大家听到同一首」</li>
	 *   <li>网络流：返回空列表（由播放层直接拉流）</li>
	 * </ul>
	 */
	public static List<Path> resolveTracks(RadioStation station) {
		if (station == null || station.isStreaming()) {
			return List.of();
		}
		Path path = resolveAudioPath(station);
		if (path == null) {
			return List.of();
		}
		if (Files.isRegularFile(path)) {
			// <b>txt 重定向播放列表</b>：文件里逐行列出本地文件或网络地址。
			// 网络地址会在这里被下载并缓存到本地（见 TxtPlaylist / RemoteAudioCache），
			// 解析后得到的就是一份普通的本地文件列表。
			// 用带缓存的入口：避免每次 resolveTracks() 都重新访问网络
			if (TxtPlaylist.isTxtPlaylist(path)) {
				TxtPlaylist.Parsed parsed = TxtPlaylist.parseCached(path, audioDir());
				if (parsed == null || parsed.entries().isEmpty()) {
					SmileSRadio.LOGGER.warn("txt 播放列表没有可用条目：{}", path.getFileName());
					return List.of();
				}
				return parsed.entries();
			}
			// 单文件：无论 mode 为何都按单曲循环处理
			return List.of(path);
		}
		if (Files.isDirectory(path)) {
			return RadioPlaylist.scan(path, station.isRandom(), station.seed());
		}
		return List.of();
	}

	public static Path resolveIcon(RadioStation station) {
		if (station.icon() == null || station.icon().isBlank()) {
			return null;
		}
		if (isExamplePath(station.icon())) {
			return null;
		}
		// 与音频路径同理：Windows 写的 \ 在 Linux 上不是分隔符（见 normalizeSeparators）
		String icon = normalizeSeparators(station.icon());
		Path inIcons = iconDir().resolve(icon).normalize();
		if (Files.isRegularFile(inIcons)) {
			return inIcons;
		}
		Path direct = baseDir.resolve(icon).normalize();
		return Files.isRegularFile(direct) ? direct : inIcons;
	}

	private static Map<String, String> readManifest() {
		Path manifest = cacheDir().resolve(CACHE_MANIFEST);
		if (!Files.isRegularFile(manifest)) {
			return Map.of();
		}
		try (Reader reader = Files.newBufferedReader(manifest, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);
			if (!root.isJsonObject()) {
				return Map.of();
			}
			Map<String, String> map = new LinkedHashMap<>();
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
				map.put(e.getKey(), e.getValue().getAsString());
			}
			return map;
		} catch (Exception e) {
			SmileSRadio.LOGGER.warn("读取缓存清单失败，将重新生成", e);
			return Map.of();
		}
	}

	private static void writeManifest(Map<String, String> hashes) {
		JsonObject obj = new JsonObject();
		hashes.forEach(obj::addProperty);
		try {
			Files.writeString(cacheDir().resolve(CACHE_MANIFEST), GSON.toJson(obj), StandardCharsets.UTF_8);
		} catch (IOException e) {
			SmileSRadio.LOGGER.error("写入缓存清单失败", e);
		}
	}

	/** 对内存字符串计算 SHA-256（用于合并播放列表哈希）。 */
	public static String sha256Text(String text) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			digest.update(text.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest.digest());
		} catch (NoSuchAlgorithmException e) {
			SmileSRadio.LOGGER.error("计算哈希失败", e);
			return "";
		}
	}

	/**
	 * <b>轻量哈希</b>：只读文件头 + 文件大小，用于缓存校验。
	 *
	 * <h2>为什么不再对整文件算 SHA-256</h2>
	 * 缓存校验的唯一目的是回答「这个文件变了没有」。
	 * 对一个 20MB 的音频做完整 SHA-256 要几十毫秒，而频道一多、
	 * 每次 {@code /reload} 都要把全部文件重算一遍——纯粹浪费算力，
	 * 而且这些计算发生在<b>服务端启动路径</b>上，会拖慢开服。
	 *
	 * <p>改用「前 {@link #HASH_HEAD_BYTES} 字节 + 文件大小」：
	 * <ul>
	 *   <li>音频文件（MP3/OGG/FLAC…）的头部含编码参数、ID3 等，
	 *       音频被替换/重新导出时几乎必然变化；</li>
	 *   <li>再叠加<b>文件大小</b>，可排除"头部相同但内容不同"的常见情形；</li>
	 *   <li>代价从"读整个文件"降到"读 64KB"，几十 MB 的文件从 ~50ms 降到 ~0.1ms。</li>
	 * </ul>
	 *
	 * <p><b>这不是加密用途</b>：只用于"变了没有"的本地判断，
	 * 不需要抗碰撞强度。真正的唯一性由 {@link #sha256Text(String)} 处理的事情保证。
	 *
	 * <p>读失败（文件不存在/无权限）返回空串，调用方按"无法校验"处理。
	 */
	public static String quickHash(Path file) {
		if (file == null || !Files.isRegularFile(file)) {
			return "";
		}
		try {
			long size = Files.size(file);
			java.security.MessageDigest digest =
					java.security.MessageDigest.getInstance("SHA-256");
			// 先吃进文件大小，再吃头部字节：两者共同决定哈希
			digest.update(java.nio.ByteBuffer.allocate(Long.BYTES).putLong(size).array());
			try (var in = Files.newInputStream(file)) {
				byte[] buf = new byte[(int) Math.min(HASH_HEAD_BYTES, Math.max(1, size))];
				int read = in.readNBytes(buf, 0, buf.length);
				if (read > 0) {
					digest.update(buf, 0, read);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (IOException | NoSuchAlgorithmException e) {
			SmileSRadio.LOGGER.error("计算轻量哈希失败 {}", file, e);
			return "";
		}
	}

	/**
	 * 缓存校验时读取的文件头字节数。
	 *
	 * <p>取 64KB：足够覆盖 MP3 的 ID3/首帧、OGG 的页头与首个 Vorbis 头、
	 * FLAC 的 STREAMINFO 等编码标识；读取成本可忽略（一次 64KB 顺序读）。
	 */
	private static final int HASH_HEAD_BYTES = 64 * 1024;

	public static String sha256(Path file) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			try (var in = Files.newInputStream(file)) {
				byte[] buf = new byte[8192];
				int read;
				while ((read = in.read(buf)) > 0) {
					digest.update(buf, 0, read);
				}
			}
			return HexFormat.of().formatHex(digest.digest());
		} catch (IOException | NoSuchAlgorithmException e) {
			SmileSRadio.LOGGER.error("计算哈希失败 {}", file, e);
			return "";
		}
	}

	/**
	 * 图标下发时的目标边长（像素）。
	 *
	 * <p>界面上图标只按 <b>16×16</b> 绘制（见 {@code RadioScreen} 的 {@code blit}）。
	 * 取 <b>32</b> 而不是 16：留 2 倍余量，避免不同 GUI 缩放/高 DPI 下糊掉，
	 * 而体积仍然只有 500×500 原图的 1/244。
	 */
	private static final int ICON_TARGET_PX = 32;

	/** 图标缩略图缓存：源文件路径 + 修改时间 -> 缩略图字节（避免每次请求都重新缩放）。 */
	private static final Map<String, byte[]> ICON_CACHE = new java.util.HashMap<>();

	/**
	 * 读取并<b>自动降分辨率</b>后的图标字节（用于网络下发给客户端）。
	 *
	 * <h2>为什么要降</h2>
	 * 服主常直接放 <b>500×500</b> 甚至更大的 PNG，而界面只画 16×16——
	 * 一张 500×500 的图约 36KB，缩到 32×32 后通常只有 <b>1~2KB</b>，
	 * 省下 <b>95%+</b> 的带宽。图标是每个玩家打开界面就会请求的，
	 * 频道多时这笔开销很实在。
	 *
	 * <h2>为什么不在这里引入图像库</h2>
	 * 用 JDK 自带的 {@code ImageIO}（{@code javax.imageio}）就够了，
	 * 不额外增加依赖——这是服务端代码，多引一个库就多一份兼容负担。
	 *
	 * <h2>失败时就发原图</h2>
	 * 若缩放失败（格式罕见、ImageIO 不支持），<b>退回原始字节</b>而不是返回空：
	 * 图标显示不出来比多传几十 KB 严重得多。
	 *
	 * @return PNG 字节（已缩放）；无图标返回空数组
	 */
	public static byte[] readIconBytes(RadioStation station) {
		Path icon = resolveIcon(station);
		if (icon == null || !Files.isRegularFile(icon)) {
			return new byte[0];
		}
		// 缓存键含修改时间：图标被替换后自动失效，不需要额外清理逻辑
		String key;
		try {
			key = icon.toAbsolutePath() + "|" + Files.getLastModifiedTime(icon).toMillis();
		} catch (IOException e) {
			key = icon.toAbsolutePath().toString();
		}
		synchronized (ICON_CACHE) {
			byte[] hit = ICON_CACHE.get(key);
			if (hit != null) {
				return hit;
			}
		}
		byte[] result;
		try {
			byte[] original = Files.readAllBytes(icon);
			result = downscaleIcon(original);
			if (result == null) {
				// 缩放失败：退回原图，别让图标直接消失
				result = original;
			}
		} catch (IOException e) {
			SmileSRadio.LOGGER.warn("读取图标失败 {}", icon, e);
			return new byte[0];
		}
		synchronized (ICON_CACHE) {
			ICON_CACHE.put(key, result);
		}
		return result;
	}

	/**
	 * 把图标缩放成 {@link #ICON_TARGET_PX} 见方，输出 PNG 字节。
	 *
	 * <p>保持宽高比：非正方形的图会按长边缩放到目标尺寸，短边等比缩小
	 * （图标显示是 16×16 拉伸，等比缩放能保留原始比例、视觉更自然）。
	 *
	 * @return 缩放后的 PNG；缩放失败返回 {@code null}（调用方退回原图）
	 */
	private static byte[] downscaleIcon(byte[] original) {
		// <b>专用服务器可能没有图形环境</b>：先确保 AWT 处于 headless 模式，
		// 否则 ImageIO/Graphics2D 在某些 JDK 上会抛 HeadlessException。
		// 只在第一次调用时设置（重复设置无害，但日志会更干净）。
		if (!java.awt.GraphicsEnvironment.isHeadless()) {
			try {
				System.setProperty("java.awt.headless", "true");
			} catch (Throwable ignored) {
				// 安全策略不允许改属性时忽略，走下面的兜底
			}
		}
		try {
			java.awt.image.BufferedImage src =
					javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(original));
			if (src == null) {
				return null;
			}
			int w = src.getWidth();
			int h = src.getHeight();
			// 已经足够小就不动它（避免把小图放大、反而变糊）
			if (w <= ICON_TARGET_PX && h <= ICON_TARGET_PX) {
				return original;
			}
			double scale = (double) ICON_TARGET_PX / Math.max(w, h);
			int nw = Math.max(1, (int) Math.round(w * scale));
			int nh = Math.max(1, (int) Math.round(h * scale));

			java.awt.image.BufferedImage dst = new java.awt.image.BufferedImage(
					nw, nh, java.awt.image.BufferedImage.TYPE_INT_ARGB);
			java.awt.Graphics2D g = dst.createGraphics();
			try {
				// <b>必须开高质量插值</b>：默认的最近邻缩小会产生锯齿/摩尔纹，
				// 图标这种小图尤其明显。
				g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
						java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
				g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
						java.awt.RenderingHints.VALUE_RENDER_QUALITY);
				g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
						java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				g.drawImage(src, 0, 0, nw, nh, null);
			} finally {
				g.dispose();
			}
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			javax.imageio.ImageIO.write(dst, "png", out);
			return out.toByteArray();
		} catch (Throwable t) {
			// 无图形环境（headless 服务器）等异常：交给调用方退回原图。
			// 注意专用服务器确实可能没有图形环境，所以这条路径必须能兜住。
			SmileSRadio.LOGGER.debug("图标缩放失败（将发送原图）", t);
			return null;
		}
	}

	/** 清空图标缓存（配置重载时调用）。 */
	public static void clearIconCache() {
		synchronized (ICON_CACHE) {
			ICON_CACHE.clear();
		}
	}

	public static String fileHash(String stationId) {
		return fileHashes.getOrDefault(stationId, "");
	}

	/** 供网络层使用：把 ASCII 字符串编码成可直接塞进数据包的 Base64。 */
	public static String b64(byte[] data) {
		return Base64.getEncoder().encodeToString(data);
	}
}