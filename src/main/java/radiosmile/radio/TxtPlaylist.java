package radiosmile.radio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import radiosmile.SmileSRadio;

/**
 * <b>txt 重定向播放列表</b>：用一个纯文本文件列出音频来源，每行一个。
 *
 * <h2>文件格式</h2>
 * <pre>
 *   # 第一行是模式，可省略（省略时按 sequence 处理）
 *   sequence
 *   # 之后每行一个来源，支持：
 *   #   1) 本地文件：相对 audio/ 的路径，或绝对路径
 *   2) song_a.mp3
 *   3) folder/song_b.ogg
 *   https://example.com/stream.mp3
 *   4) 空行与 # 开头的注释行会被忽略
 * </pre>
 *
 * <h2>第一行支持的取值</h2>
 * <table border="1">
 *   <tr><th>值</th><th>含义</th></tr>
 *   <tr><td>{@code sequence} / {@code order} / {@code loop}</td>
 *       <td>按文件里列出的顺序循环（默认）</td></tr>
 *   <tr><td>{@code random} / {@code shuffle}</td>
 *       <td>按固定 seed 打乱后循环（保证所有客户端顺序一致）</td></tr>
 * </table>
 *
 * <h2>网络地址怎么处理</h2>
 * <b>下载到本地并转成 MP3</b>（见 {@link RemoteAudioCache}），
 * 之后与本地音频完全一样地参与缓存与时间轴。
 * 这样播放架构里<b>不需要</b>第二条"直播流"路径——
 * 直播流不可 seek、没有时长，混进播放列表会直接破坏多人同步。
 *
 * <h2>为什么用 txt 而不是别的格式</h2>
 * 服主在服务器上直接改一行就能增删曲目，不需要懂 JSON 语法、也不容易写错括号。
 * 校验只需"文件头 + 大小"，与普通音频缓存用同一套判据。
 */
public final class TxtPlaylist {
	/** 默认可接受的 txt 扩展名。 */
	private static final java.util.Set<String> TXT_EXTENSIONS =
			java.util.Set.of("txt", "list", "m3u", "m3u8");

	private TxtPlaylist() {
	}

	/** 该文件是否是重定向播放列表（按扩展名判断）。 */
	public static boolean isTxtPlaylist(Path path) {
		return path != null && TXT_EXTENSIONS.contains(
				AudioTranscoder.extensionOf(path));
	}

	/**
	 * 解析结果。
	 *
	 * @param entries 有序的来源列表（本地已解析为绝对路径；网络地址已下载并转成本地文件）
	 * @param random  是否随机顺序
	 */
	public record Parsed(List<Path> entries, boolean random) {
	}

	/**
	 * 解析结果缓存：txt 路径（+ 文件头哈希）-> 解析结果。
	 *
	 * <h2>为什么必须缓存</h2>
	 * 解析包含网络地址时要<b>下载</b>，而 {@link #parse} 会被
	 * {@code resolveTracks()} 反复调用（每次订阅、每次哈希校验、每次 /reload）。
	 * 不缓存的话，一个含网络地址的频道会在主线程上反复触发 HTTP 请求。
	 *
	 * <p>键里带上 txt 的 {@code quickHash}：txt 内容一改，哈希就变，
	 * 自然失效并重新解析——这正是"遵循缓存刷新原则"。
	 */
	private static final java.util.Map<String, Parsed> CACHE =
			new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * 带缓存的解析入口（默认用）。
	 *
	 * <p>命中缓存则完全不碰网络；未命中才真正解析/下载。
	 * 下载失败也<b>不缓存空结果</b>，让下次还能重试。
	 */
	public static Parsed parseCached(Path txt, Path audioDir) {
		if (txt == null || !Files.isRegularFile(txt)) {
			return null;
		}
		String key = txt.toAbsolutePath() + "|"
				+ RadioStationRegistry.quickHash(txt);
		Parsed hit = CACHE.get(key);
		if (hit != null) {
			return hit;
		}
		Parsed parsed = parse(txt, audioDir);
		if (parsed != null && !parsed.entries().isEmpty()) {
			CACHE.put(key, parsed);
		}
		return parsed;
	}

	/** 清空解析缓存（配置重载时调用，确保 txt 内容变更能立即生效）。 */
	public static void clearCache() {
		CACHE.clear();
	}

	/**
	 * 解析 txt 播放列表（<b>无缓存</b>，会把网络地址落地成本地文件）。
	 *
	 * <p>注意这是<b>阻塞操作</b>：包含网络地址时真的会下载。
	 * 常规调用请用 {@link #parseCached}。
	 *
	 * @param txt      txt 文件
	 * @param audioDir 相对路径的基准目录（通常就是 {@code audio/}）
	 * @return 解析结果；文件不可读返回 {@code null}
	 */
	public static Parsed parse(Path txt, Path audioDir) {
		return parse(txt, audioDir, true);
	}

	/**
	 * 只解析<b>本地条目</b>，完全跳过网络地址（不下载）。
	 *
	 * <p>专供「配置校验」这类<b>不能阻塞</b>的场合使用：
	 * {@code isPlayable()} 在服务端启动主线程上被调用，
	 * 若这里去下载一个几十 MB 的网络文件，整个服务器会卡住几十秒。
	 *
	 * <p>语义取舍：只含网络地址、且尚未缓存过的 txt 在此时会被判为"暂无本地音频"，
	 * 由后台预热线程下载完成后再被真正接受（见 {@code preheat}）。
	 * 这比"启动时卡死"合理得多。
	 */
	public static Parsed parseLocalOnly(Path txt, Path audioDir) {
		return parse(txt, audioDir, false);
	}

	/**
	 * 解析实现。
	 *
	 * @param allowNetwork {@code false} 时跳过所有 http(s) 条目，绝不发起网络请求
	 */
	private static Parsed parse(Path txt, Path audioDir, boolean allowNetwork) {
		if (txt == null || !Files.isRegularFile(txt)) {
			return null;
		}
		List<String> lines;
		try {
			lines = Files.readAllLines(txt, StandardCharsets.UTF_8);
		} catch (IOException e) {
			SmileSRadio.LOGGER.warn("读取 txt 播放列表失败 {}", txt, e);
			return null;
		}

		boolean random = false;
		// <b>播放顺序 = txt 行从上到下</b>（用户明确要求）。
		//
		// 未下载完成的网络条目属于<b>挂起（pending）</b>状态：
		// 它们不在可播列表里，播放到那一行时<b>直接跳到下一首</b>。
		// 后台预热线程会持续下载，下完清缓存后自然并入可播集合。
		List<Path> entries = new ArrayList<>();
		// 挂起条目：已识别但还没缓存好，交给后台下载
		List<String> pendingUrls = new ArrayList<>();
		boolean firstContentLine = true;

		for (String raw : lines) {
			String line = raw == null ? "" : raw.trim();
			// 去掉行尾注释（支持 "song.mp3  # 备注"），但保留 URL 里的 #
			int hash = indexOfLineComment(line);
			if (hash >= 0) {
				line = line.substring(0, hash).trim();
			}
			if (line.isEmpty()) {
				continue;
			}
			// <b>第一行内容行是模式声明</b>。若它看起来不像音频来源，就按模式解析。
			if (firstContentLine) {
				firstContentLine = false;
				String mode = line.toLowerCase(java.util.Locale.ROOT);
				if (isModeKeyword(mode)) {
					random = mode.startsWith("random") || mode.startsWith("shuffle");
					continue;
				}
				// 第一行不是模式关键字 → 当作普通条目处理（模式默认 sequence）
			}

			boolean isNetwork = line.startsWith("http://") || line.startsWith("https://");
			// allowNetwork=false 时跳过网络地址（不下载），见 parseLocalOnly
			if (!allowNetwork && isNetwork) {
				continue;
			}
			if (!isNetwork) {
				Path resolved = resolveEntry(line, txt, audioDir);
				if (resolved != null) {
					entries.add(resolved);
				}
				continue;
			}

			// 网络条目：<b>只查缓存，不在这里下载</b>（下载放后台，见 pending）。
			//
			// 为什么要区分：下载一个网络音频可能要几十秒，若在解析时同步下载，
			// 这份 txt 的解析会一直阻塞（它被 resolveTracks/isPlayable 反复调用）。
			// 现在改为：缓存命中就立刻并入可播列表；未命中就记为<b>挂起</b>，
			// 播放到那一行时直接跳过，等后台下完再自然生效。
			// <b>解析阶段不做网络校验</b>（verify=false）：
			// 解析会被 resolveTracks() 反复调用，而每次 Range 请求实测约 1 秒/条目，
			// 一份含 4 个网络地址的 txt 就要卡 4.5 秒。
			// 这里只看"本地有没有这个文件"，真正的有效性校验推迟到播放时（那里有 TTL 缓存）。
			Path cached = RemoteAudioCache.cachedFileIfValid(line, false);
			if (cached != null) {
				// <b>优先用转码产物</b>：时间轴要求 MP3 才能算出正确时长。
				// 若转码还没完成，这里照样加入（原始 .m4s），
				// 由 PlaylistTimeline 负责剔除——这样"未转码"不会污染时间轴。
				Path baked = AudioTranscoder.mp3For(cached);
				entries.add(baked != null ? baked : cached);
			} else {
				pendingUrls.add(line);
				// 触发后台下载（幂等；同一 URL 不会重复排队）
				RemoteAudioCache.requestDownload(line);
				SmileSRadio.LOGGER.info("网络音频尚未缓存，挂起并转入后台下载：{}", line);
			}
		}

		if (random && entries.size() > 1) {
			// 用 txt 文件路径派生 seed：同一份列表在所有客户端得到同一顺序
			Collections.shuffle(entries,
					new Random(RadioStation.deriveSeed(txt.getFileName().toString())));
		}
		return new Parsed(List.copyOf(entries), random);
	}

	/**
	 * txt 里是否含网络地址（只看文本，<b>不发请求</b>）。
	 *
	 * <p>供 {@code isPlayable()} 在"本地条目还没下载"时判断：
	 * 只要列表里还写着网络地址，就先当作"稍后会可用"，
	 * 不要把它从电台列表里隐藏掉。
	 */
	public static boolean hasNetworkEntries(Path txt) {
		if (txt == null || !Files.isRegularFile(txt)) {
			return false;
		}
		try {
			for (String raw : Files.readAllLines(txt, StandardCharsets.UTF_8)) {
				String line = raw == null ? "" : raw.trim();
				if (line.startsWith("http://") || line.startsWith("https://")) {
					return true;
				}
			}
		} catch (IOException ignored) {
			// 读不到就当没有
		}
		return false;
	}

	/** 是否是可识别的模式关键字。 */
	private static boolean isModeKeyword(String lower) {
		return lower.equals("sequence") || lower.equals("order") || lower.equals("loop")
				|| lower.equals("random") || lower.equals("shuffle");
	}

	/**
	 * 找行尾注释的起始位置。
	 *
	 * <p>不能简单找 {@code #}：URL 里也可能出现 {@code #}（片段标识）。
	 * 规则：{@code #} 前必须有空白，或位于行首，才算注释——
	 * 这样 {@code https://x/a.mp3#t=10} 不会被误当成注释。
	 */
	private static int indexOfLineComment(String line) {
		for (int i = 0; i < line.length(); i++) {
			char c = line.charAt(i);
			if (c != '#') {
				continue;
			}
			if (i == 0) {
				return 0;
			}
			if (Character.isWhitespace(line.charAt(i - 1))) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * 把一行解析成本地文件路径。
	 *
	 * <ul>
	 *   <li>{@code http(s)://} → 下载并缓存到本地（{@link RemoteAudioCache}）</li>
	 *   <li>其它 → 先按 txt 所在目录找，再按 {@code audio/} 找</li>
	 * </ul>
	 */
	private static Path resolveEntry(String line, Path txt, Path audioDir) {
		if (line.startsWith("http://") || line.startsWith("https://")) {
			Path local = RemoteAudioCache.localFileFor(line);
			if (local == null) {
				SmileSRadio.LOGGER.warn("txt 播放列表里的网络地址不可用，已跳过：{}（来自 {}）",
						line, txt.getFileName());
			}
			return local;
		}
		// 本地路径：先相对 txt 所在目录，再相对 audioDir
		Path direct = txt.getParent() == null ? null : txt.getParent().resolve(line);
		if (direct != null && Files.isRegularFile(direct)) {
			return direct.normalize();
		}
		if (audioDir != null) {
			Path inAudio = audioDir.resolve(line);
			if (Files.isRegularFile(inAudio)) {
				return inAudio.normalize();
			}
		}
		// 绝对路径兜底
		try {
			Path absolute = Path.of(line);
			if (Files.isRegularFile(absolute)) {
				return absolute.normalize();
			}
		} catch (Exception ignored) {
			// 非法路径字符串
		}
		SmileSRadio.LOGGER.warn("txt 播放列表里的文件不存在，已跳过：{}（来自 {}）",
				line, txt.getFileName());
		return null;
	}
}
