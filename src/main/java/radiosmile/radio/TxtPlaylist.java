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
 *   # 第一行是【打乱顺序】开关，可省略（省略时 = false，按下面列出的顺序播放）
 *   true
 *   # 之后每行一个来源，支持：
 *   #   1) 本地文件：相对 audio/ 的路径，或绝对路径
 *   song_a.mp3
 *   folder/song_b.ogg
 *   https://example.com/song.mp3
 *   2) 空行与 # 开头的注释行会被忽略
 * </pre>
 *
 * <h2>第一行：打乱顺序（布尔值）</h2>
 * <table border="1">
 *   <tr><th>值</th><th>含义</th></tr>
 *   <tr><td>{@code false} / {@code 0} / 省略</td>
 *       <td><b>不打乱</b>：严格按文件里从上到下的顺序循环（默认）</td></tr>
 *   <tr><td>{@code true} / {@code 1}</td>
 *       <td><b>打乱顺序</b>：按固定 seed 打乱后循环，
 *           保证所有客户端听到的顺序完全一致</td></tr>
 * </table>
 *
 * <p>为什么用布尔而不是 {@code sequence}/{@code random}：
 * 一个 txt 里只有两种互斥状态，布尔值最直接、最少歧义；
 * 而且"顺序"其实是默认行为，不需要专门写一个词去表达它。
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
		lines = stripBom(lines);

		boolean random = false;
		// <b>播放顺序 = txt 行从上到下，严格保持书写顺序。</b>
		//
		// 未下载完成的网络条目属于<b>挂起（pending）</b>状态：
		// 它们<b>不进入</b>可播列表，所以"播放到那一行就自动跳过"是天然成立的，
		// 播放层不需要任何额外判断。后台下载完成后它们会自己并入可播列表。
		//
		// <b>不要在这里做任何"分桶/排序"</b>：曾经实现过「本地 > 已缓存网络 >
		// 未缓存网络」的三级优先级，但那会打乱按行顺序——用户明确要求
		// "顺序播放就是按 txt 行从上到下"，混播（本地+网络交替）时尤其不能乱。
		// 挂起跳过本身已经解决了"未缓存条目不卡住播放"的问题，不需要再排序。
		List<Path> entries = new ArrayList<>();
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
			// <b>第一行内容行是「打乱顺序」布尔开关</b>（见类注释）。
			// 若第一行既不是 true/false 也不是 0/1，就当成普通条目处理（默认不打乱）。
			if (firstContentLine) {
				firstContentLine = false;
				Boolean shuffle = parseShuffleFlag(line);
				if (shuffle != null) {
					random = shuffle;
					continue;
				}
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
				// <b>挂起</b>：不加入 entries（因此播放时会被跳过），
				// 只把它排进后台下载队列。下载完成后下次解析自然命中缓存。
				// 重复请求是幂等的（同一 URL 不会重复排队）。
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
	 * <b>启动检查：把 txt 里指向「不存在的本地文件」的行注释掉。</b>
	 *
	 * <h2>为什么本地文件也要处理</h2>
	 * 网络源的失效表现为 HTTP 4xx（见 {@link RemoteAudioCache#commentOutInTxt}），
	 * 本地源的失效就是<b>文件不在了</b>：服主删了曲子、改了文件名、
	 * 或从 Windows 上传时路径大小写不一致。这类行留在 txt 里同样是"每次解析都失败一次"，
	 * 而且在日志里只是几行 WARN，服主打开 txt 看到的还是正常一行。
	 *
	 * <p>所以这里与网络侧保持同一套做法：<b>不删、只注释</b>，并写明原因：
	 * <pre>
	 *   # [已禁用：文件不存在] rock/02 - foo.mp3
	 * </pre>
	 * 把文件放回去之后，删掉行首前缀即可恢复。
	 *
	 * <h2>只在启动时做</h2>
	 * 本方法<b>不会</b>在播放路径（{@link #parse}）里执行。原因有两个：
	 * <ol>
	 *   <li>播放路径每订阅一次就要解析一次，每次都可能因为磁盘临时抖动
	 *       把一行好好的条目注释掉；</li>
	 *   <li>服主可能正在上传大文件，写文件会与上传互相踩。</li>
	 * </ol>
	 * 启动检查是一次性的、有明确边界的时机，误判代价最小。
	 *
	 * <h2>相对路径基准</h2>
	 * 与解析完全一致（见 {@link #resolveEntry}）：<b>先按 txt 所在目录</b>，
	 * 再按 {@code audio/}，最后按绝对路径——所以
	 * {@code audio/lists/rock.txt} 里的 {@code ../hit.mp3} 会命中 {@code audio/hit.mp3}。
	 *
	 * @param base     配置根目录（{@code config/radio-smile}），会递归找出其中的 txt
	 * @param audioDir 相对路径的第二基准目录（通常 {@code config/radio-smile/audio}）
	 * @return 本次被注释掉的行数
	 */
	public static synchronized int sanitizeLocalMissing(Path base, Path audioDir) {
		if (base == null || !Files.isDirectory(base)) {
			return 0;
		}
		List<Path> txtFiles = new ArrayList<>();
		try (java.util.stream.Stream<Path> walk = Files.walk(base, 3)) {
			walk.filter(Files::isRegularFile)
					.filter(TxtPlaylist::isTxtPlaylist)
					.forEach(txtFiles::add);
		} catch (IOException e) {
			SmileSRadio.LOGGER.debug("扫描 txt 播放列表失败", e);
			return 0;
		}
		int total = 0;
		for (Path txt : txtFiles) {
			total += commentMissingIn(txt, audioDir);
		}
		if (total > 0) {
			// 内容改了，解析缓存必须失效，否则下一次解析仍用旧行
			clearCache();
		}
		return total;
	}

	/**
	 * 注释掉单个 txt 里所有「指向不存在的本地文件」的行（见 {@link #sanitizeLocalMissing}）。
	 *
	 * <h2>安全阀：一条都解析不出来时不动手</h2>
	 * 「所有本地条目都不存在」有两种可能：
	 * <ol>
	 *   <li>服主真的把文件全删了；</li>
	 *   <li>路径基准不对（列表被挪了位置、写了 {@code ../audio/xxx} 之类）——
	 *       这是<b>我们的解析没覆盖到</b>，不是文件没了。</li>
	 * </ol>
	 * 两种情况从文件系统上无法区分。而在第 2 种情况里动手注释是<b>破坏性</b>的：
	 * 整个列表会被注释干净，频道随之变成"没有可用音频"从列表里消失，
	 * 或者只剩一两首——后者看起来正是「随机播放失效（只剩一首无法打乱）」。
	 *
	 * <p>所以规则是：<b>只有至少一条本地条目解析成功时，才注释那些失败的</b>。
	 * 一条都不成功就只记 WARN，把判断权留给服主。
	 */
	private static int commentMissingIn(Path txt, Path audioDir) {
		List<String> lines;
		try {
			lines = stripBom(Files.readAllLines(txt, StandardCharsets.UTF_8));
		} catch (IOException e) {
			SmileSRadio.LOGGER.debug("读取 txt 失败 {}", txt, e);
			return 0;
		}
		boolean firstContentLine = true;
		int localEntries = 0;
		int missing = 0;
		for (int i = 0; i < lines.size(); i++) {
			String raw = lines.get(i) == null ? "" : lines.get(i).trim();
			// 剥掉行尾注释后再判断（"song.mp3  # 备注"）
			int hash = indexOfLineComment(raw);
			String line = hash >= 0 ? raw.substring(0, hash).trim() : raw;
			if (line.isEmpty()) {
				continue;
			}
			if (firstContentLine) {
				firstContentLine = false;
				// 第一行内容行是「打乱顺序」开关，不是条目
				if (parseShuffleFlag(line) != null) {
					continue;
				}
			}
			// 网络条目交给下载流程判定（4xx 才注释），这里只管本地文件
			if (line.startsWith("http://") || line.startsWith("https://")) {
				continue;
			}
			localEntries++;
			if (resolveEntry(line, txt, audioDir) != null) {
				continue;
			}
			lines.set(i, "# [已禁用：文件不存在] " + raw);
			missing++;
		}
		if (missing == 0) {
			return 0;
		}
		if (missing == localEntries) {
			SmileSRadio.LOGGER.warn(
					"txt 播放列表 {} 的 {} 条本地条目全部解析失败，未做任何注释——"
							+ "这更像是路径基准不对（列表可能被移动过），而不是文件被删。"
							+ "请检查列表里的路径写法（相对 txt 所在目录 / audio/ / 配置根目录均可）",
					txt.getFileName(), localEntries);
			return 0;
		}
		writeAtomically(txt, lines);
		SmileSRadio.LOGGER.info("已在 txt 播放列表 {} 中注释掉 {} 条不存在的本地文件",
				txt.getFileName(), missing);
		return missing;
	}

	/**
	 * 原子写入 txt：先写同目录临时文件再替换。
	 *
	 * <p>这份文本正被播放线程读，直接原地覆写会让它有机会读到半截内容
	 * （表现为"播放列表突然变空、播不下去"）。同目录内原子替换可以避免这个窗口。
	 */
	static void writeAtomically(Path txt, List<String> lines) {
		try {
			Path temp = txt.resolveSibling(txt.getFileName() + ".tmp");
			Files.write(temp, lines, StandardCharsets.UTF_8);
			try {
				Files.move(temp, txt, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
						java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			} catch (java.nio.file.AtomicMoveNotSupportedException e) {
				Files.move(temp, txt, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			SmileSRadio.LOGGER.warn("写入 txt 播放列表失败 {}", txt, e);
		}
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

	/**
	 * 去掉文件开头可能存在的 <b>UTF-8 BOM</b>（U+FEFF）。
	 *
	 * <h2>为什么必须处理</h2>
	 * Windows 的记事本、以及不少编辑器在"另存为 UTF-8"时会写入 BOM（EF BB BF）。
	 * Java 的 {@code Files.readAllLines(UTF_8)} <b>不会</b>自动剥离它，
	 * 于是第一行会变成 {@code "﻿false"}。
	 *
	 * <p>而 {@code String.trim()} 只去掉 {@code <= U+0020} 的字符，
	 * <b>U+FEFF 不在其中</b>——结果就是第一行的开关<b>静默失效</b>，
	 * 被当成一个普通条目忽略掉。服主会困惑"我明明写了 true 怎么没打乱"，
	 * 而这个不可见字符在编辑器里根本看不出来。
	 *
	 * <p>这既是跨平台问题（Windows 写、Linux 读），也是本模块独有的风险：
	 * 第一行有特殊语义，BOM 恰好落在它头上。
	 */
	private static List<String> stripBom(List<String> lines) {
		if (lines == null || lines.isEmpty()) {
			return lines;
		}
		String first = lines.get(0);
		if (first != null && !first.isEmpty() && first.charAt(0) == '\uFEFF') {
			List<String> fixed = new ArrayList<>(lines);
			fixed.set(0, first.substring(1));
			return fixed;
		}
		return lines;
	}

	/**
	 * 解析第一行的「打乱顺序」开关。
	 *
	 * <p>接受 {@code true}/{@code false}、{@code 1}/{@code 0}（大小写不敏感）。
	 *
	 * <p><b>兼容旧的单词写法</b>：{@code random}/{@code shuffle} 仍按"打乱"处理，
	 * {@code sequence}/{@code order}/{@code loop} 按"不打乱"处理——
	 * 这样服主已有的 txt 不会因为这次格式简化而突然改变行为。
	 *
	 * @return {@code true}=打乱，{@code false}=按顺序；该行不是开关时返回 {@code null}
	 */
	private static Boolean parseShuffleFlag(String raw) {
		if (raw == null) {
			return null;
		}
		String v = raw.trim().toLowerCase(java.util.Locale.ROOT);
		switch (v) {
			case "true", "1", "random", "shuffle":
				return Boolean.TRUE;
			case "false", "0", "sequence", "order", "loop":
				return Boolean.FALSE;
			default:
				return null;
		}
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
	 * <h2>解析顺序（三个基准 + 绝对路径）</h2>
	 * <ol>
	 *   <li><b>txt 所在目录</b>——最常见的写法：列表和音频放一起，写文件名即可；</li>
	 *   <li><b>{@code audio/}</b>——历史写法，列表放在子目录（如 {@code audio/lists/}）时
	 *       仍能引用 {@code audio/} 下的文件；</li>
	 *   <li><b>配置根目录</b>——把 {@code audio/} 也写进路径（如 {@code audio/01.mp3}）的写法。
	 *       早期没有这一档，这类条目会被判成"文件不存在"；启动检查一旦据此把它注释掉，
	 *       整个列表就只剩个别曲目，看起来正是「随机播放失效」（只剩一首时无从打乱）。</li>
	 *   <li>绝对路径兜底。</li>
	 * </ol>
	 *
	 * <p><b>顺序即优先级</b>：同名文件同时存在于 txt 目录与 {@code audio/} 时，
	 * 取 txt 目录那一份——列表自己旁边的那份显然是作者想要的。
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
		// 本地路径：先相对 txt 所在目录，再相对 audioDir。
		// 反斜杠统一换成 /：服主可能在 Windows 上写 txt、再上传到 Linux 服务器，
		// 而 Linux 上 \ 是合法文件名字符、不是分隔符（详见 RadioStationRegistry）。
		String normalized = line.replace('\\', '/');
		Path direct = txt.getParent() == null ? null : txt.getParent().resolve(normalized);
		if (direct != null && Files.isRegularFile(direct)) {
			return direct.normalize();
		}
		if (audioDir != null) {
			Path inAudio = audioDir.resolve(normalized);
			if (Files.isRegularFile(inAudio)) {
				return inAudio.normalize();
			}
		}
		// 第三基准：配置根目录（允许写成 "audio/xxx.mp3"）
		Path base = RadioStationRegistry.baseDir();
		if (base != null) {
			Path inBase = base.resolve(normalized);
			if (Files.isRegularFile(inBase)) {
				return inBase.normalize();
			}
		}
		// 绝对路径兜底
		try {
			Path absolute = Path.of(normalized);
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
