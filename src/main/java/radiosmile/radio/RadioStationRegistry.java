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
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
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

	public static Path baseDir() {
		return baseDir;
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
	 * @param worldRoot 世界存档根目录
	 */
	public static synchronized void load(Path worldRoot) {
		baseDir = worldRoot.resolve("serverconfig").resolve("radio-smile");
		try {
			Files.createDirectories(baseDir);
			Files.createDirectories(audioDir());
			Files.createDirectories(iconDir());
			Files.createDirectories(cacheDir());
		} catch (IOException e) {
			SmileSRadio.LOGGER.error("无法创建电台目录 {}", baseDir, e);
			return;
		}

		stations = readIndex();
		rebuildCacheIfChanged();

		// 每次加载 / 重载都重置播放计时基准
		startEpochMillis = System.currentTimeMillis();
		SmileSRadio.LOGGER.info("电台配置已加载：{} 个频道，计时基准 {}", stations.size(), startEpochMillis);
	}

	private static List<RadioStation> readIndex() {
		Path index = baseDir.resolve(INDEX_NAME);
		if (!Files.exists(index)) {
			writeDefaultIndex(index);
		}
		try (Reader reader = Files.newBufferedReader(index, StandardCharsets.UTF_8)) {
			JsonElement root = JsonParser.parseReader(reader);
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
		JsonArray arr = new JsonArray();
		arr.add(sampleStation(EXAMPLE_ID, "示例：单个音频（把 id 改成别的名字即可生效）",
				EXAMPLE_ASSETS_DIR + "/sample.png", EXAMPLE_ASSETS_DIR + "/sample.mp3", "block"));
		arr.add(sampleStation(EXAMPLE_ID, "示例：文件夹顺序播放 1,2,3...（id 改成别的名字即可生效）",
				EXAMPLE_ASSETS_DIR + "/playlist.png", EXAMPLE_ASSETS_DIR + "/myfolder", "sequence"));
		arr.add(sampleStation(EXAMPLE_ID, "示例：文件夹随机播放（id 改成别的名字即可生效）",
				EXAMPLE_ASSETS_DIR + "/playlist.png", EXAMPLE_ASSETS_DIR + "/myfolder2", "random"));
		arr.add(sampleStation(EXAMPLE_ID, "示例：网络电台（id 改成别的名字即可生效）",
				EXAMPLE_ASSETS_DIR + "/webradio.png", "https://example.com/stream.mp3", "block"));
		root.add("stations", arr);
		try {
			Files.writeString(index, GSON.toJson(root), StandardCharsets.UTF_8);
			SmileSRadio.LOGGER.info("已自动创建电台配置 {}，其中 id 为 {} 的条目不会被加载",
					index, EXAMPLE_ID);
		} catch (IOException e) {
			SmileSRadio.LOGGER.error("写入电台配置失败", e);
		}
	}

	private static JsonObject sampleStation(String id, String name, String icon, String file, String mode) {
		JsonObject obj = new JsonObject();
		obj.addProperty("id", id);
		obj.addProperty("name", name);
		obj.addProperty("icon", icon);
		obj.addProperty("file", file);
		obj.addProperty("mode", mode);
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
			// 这样任一首新增/修改/删除都会使缓存失效
			StringBuilder combined = new StringBuilder();
			for (Path track : tracks) {
				combined.append(track.getFileName().toString()).append(':').append(sha256(track)).append(';');
			}
			current.put(station.id(), tracks.size() == 1
					? sha256(tracks.get(0))
					: sha256Text(combined.toString()));

			// 同一趟遍历里收集待转码文件
			for (Path track : tracks) {
				if (AudioTranscoder.needsBake(track)) {
					needBake.add(track);
				}
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
		Path direct = baseDir.resolve(station.file()).normalize();
		if (Files.isRegularFile(direct) || Files.isDirectory(direct)) {
			return direct;
		}
		return audioDir().resolve(station.file()).normalize();
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
		Path inIcons = iconDir().resolve(station.icon()).normalize();
		if (Files.isRegularFile(inIcons)) {
			return inIcons;
		}
		Path direct = baseDir.resolve(station.icon()).normalize();
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

	/** 读取图标字节（用于网络下发给客户端）。 */
	public static byte[] readIconBytes(RadioStation station) {
		Path icon = resolveIcon(station);
		if (icon == null || !Files.isRegularFile(icon)) {
			return new byte[0];
		}
		try {
			return Files.readAllBytes(icon);
		} catch (IOException e) {
			SmileSRadio.LOGGER.warn("读取图标失败 {}", icon, e);
			return new byte[0];
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