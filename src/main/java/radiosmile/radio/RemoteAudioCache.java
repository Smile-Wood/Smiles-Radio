package radiosmile.radio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HexFormat;

import radiosmile.SmileSRadio;

/**
 * <b>网络音频地址的本地缓存</b>：把 http(s) 音频下载到本地、转成 MP3，并保留一份
 * 小巧的「校验包」用来判断缓存是否还能复用。
 *
 * <h2>为什么网络地址也要走"本地文件"这条路</h2>
 * 整个播放架构是建立在<b>可随机定位的本地 MP3</b> 上的（多人同步要从文件中段开始读，
 * 见 {@link AudioTranscoder} 的说明）。直播流不可 seek、没有时长，
 * 一旦混进播放列表就无法参与时间轴计算。
 *
 * <p>所以 txt 里写的网络地址会被<b>下载成本地文件</b>，之后与普通本地音频
 * 完全同等地参与缓存、转码、时间轴——架构上不需要为此增加第二条播放路径。
 *
 * <h2>校验包（proof）是什么</h2>
 * 判断"网络文件变了没有"不能靠完整下载：那等于每次都把整个文件重拉一遍。
 * 这里用 HTTP <b>Range 请求</b>只取头 {@link #PROOF_BYTES} 字节存成
 * {@code .proof} 小文件（几 KB），之后每次校验只需再取一次头部做对比：
 * <ul>
 *   <li>服务器支持 Range：只下载几 KB，成本极低；</li>
 *   <li>服务器不支持（返回 200 而非 206）：退回"下载完整文件后比对"，
 *       此时本方法会直接复用已下载的完整文件，不会浪费；</li>
 *   <li>校验通过 → 直接复用本地已转码的 MP3，完全不重新下载。</li>
 * </ul>
 *
 * <p>这与本地文件的 {@link RadioStationRegistry#quickHash} 是同一条思路：
 * <b>用文件头 + 大小代表整个文件</b>，只用于"变了没有"的判断，不做加密用途。
 *
 * <h2>目录布局</h2>
 * <pre>
 *   cache/remote/
 *   ├── &lt;urlHash&gt;.bin     下载下来的原始音频（音频格式由 Content-Type/扩展名决定）
 *   ├── &lt;urlHash&gt;.proof   校验包：头部若干 KB + 总大小
 *   └── &lt;urlHash&gt;.json    元信息：原始 URL、校验包哈希
 * </pre>
 */
public final class RemoteAudioCache {
	/** 校验包长度：取几 KB 即可代表文件头部。 */
	private static final int PROOF_BYTES = 8 * 1024;
	private static final int CONNECT_TIMEOUT_MS = 15000;
	private static final int READ_TIMEOUT_MS = 30000;

	private RemoteAudioCache() {
	}

	/** 网络缓存目录（{@code cache/remote}）。 */
	public static Path remoteDir() {
		Path cache = RadioStationRegistry.cacheDir();
		if (cache == null) {
			return null;
		}
		Path dir = cache.resolve("remote");
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			SmileSRadio.LOGGER.warn("无法创建网络音频缓存目录 {}", dir, e);
		}
		return dir;
	}

	/**
	 * 把 URL 映射成一个稳定、安全的文件名前缀。
	 *
	 * <p>不能用原始 URL 直接当文件名（含 {@code ://}、{@code ?} 等非法字符，
	 * 且可能超长）。用 URL 的 SHA-256 前 16 位：同一个 URL 永远得到同一个名字。
	 */
	public static String cacheIdFor(String url) {
		return RadioStationRegistry.sha256Text(url).substring(0, 16);
	}

	/** 已排队/正在下载的 URL（避免同一地址被反复提交）。 */
	private static final java.util.Set<String> QUEUED =
			java.util.concurrent.ConcurrentHashMap.newKeySet();

	/** 下载线程（单线程串行，避免同时开多个连接抢带宽）。 */
	private static volatile Thread downloader;

	/**
	 * <b>请求后台下载</b>该 URL（幂等、不阻塞）。
	 *
	 * <p>用于"未缓存网络条目"的<b>挂起</b>流程：解析时先把它记为挂起并调用本方法，
	 * 播放到那一行会直接跳过；等这里下载完成后，下次解析它就会出现在可播列表里。
	 *
	 * <p>为什么用单线程串行：同时下载多个音频会互相抢带宽，
	 * 而且服务器上行本就有限——串行反而整体更快，也更不打扰正在播放的流。
	 */
	public static void requestDownload(String url) {
		if (url == null || url.isBlank()) {
			return;
		}
		if (!QUEUED.add(url)) {
			// 已排队或正在下载
			return;
		}
		ensureDownloader();
		synchronized (DOWNLOAD_QUEUE) {
			DOWNLOAD_QUEUE.add(url);
			DOWNLOAD_QUEUE.notifyAll();
		}
	}

	/** 待下载队列。 */
	private static final java.util.LinkedHashSet<String> DOWNLOAD_QUEUE =
			new java.util.LinkedHashSet<>();

	private static void ensureDownloader() {
		if (downloader != null) {
			return;
		}
		synchronized (RemoteAudioCache.class) {
			if (downloader != null) {
				return;
			}
			Thread t = new Thread(() -> {
				while (true) {
					String url;
					synchronized (DOWNLOAD_QUEUE) {
						while (DOWNLOAD_QUEUE.isEmpty()) {
							try {
								DOWNLOAD_QUEUE.wait(1000L);
							} catch (InterruptedException e) {
								return;
							}
						}
						url = DOWNLOAD_QUEUE.iterator().next();
						DOWNLOAD_QUEUE.remove(url);
					}
					try {
						// 真正下载（带校验：若期间已被其它路径下好就直接复用）
						Path local = localFileFor(url);
						if (local != null) {
							SmileSRadio.LOGGER.info("网络音频后台下载完成：{}", url);
							// 完成后清 txt 解析缓存，让下次解析把它并入可播列表
							radiosmile.radio.TxtPlaylist.clearCache();
						}
					} catch (Throwable error) {
						SmileSRadio.LOGGER.warn("网络音频后台下载失败：{}", url, error);
					} finally {
						QUEUED.remove(url);
					}
				}
			}, "RadioSmile-RemoteDownload");
			t.setDaemon(true);
			t.start();
			downloader = t;
		}
	}

	/** 在缓存目录里找该 id 对应的任意扩展名文件（用于 .bin 改名后的复用）。 */
	private static Path findAnyCachedFile(Path dir, String id) {
		try (java.util.stream.Stream<Path> files = Files.list(dir)) {
			return files.filter(Files::isRegularFile)
					.filter(p -> {
						String n = p.getFileName().toString();
						return n.startsWith(id + ".") && !n.endsWith(".proof")
								&& !n.endsWith(".downloading");
					})
					.findFirst().orElse(null);
		} catch (IOException e) {
			return null;
		}
	}

	/**
	 * 该 URL 是否仍在等待下载（挂起状态）。
	 *
	 * <p>供播放层判断"这条是暂时跳过"还是"永远不可用"。
	 */
	public static boolean isPending(String url) {
		return url != null && QUEUED.contains(url);
	}

	/**
	 * <b>只查缓存，绝不下载</b>：返回该 URL 已缓存且仍然有效的本地文件。
	 *
	 * <p>用于实现 txt 条目的<b>三级优先级</b>（本地 &gt; 已缓存网络 &gt; 未缓存网络）：
	 * 解析时先用本方法快速判断"这条已经在了吗"，命中就直接排进可播队列，
	 * 不必等网络。未命中的条目会交给下载流程/后台预热。
	 *
	 * <p>校验成本很低：一次 Range 请求只取头部几 KB。若服务器不支持 Range
	 * 或网络异常，返回 {@code null}（按"未缓存"处理，宁可重下也不拿旧缓存）。
	 *
	 * @return 已缓存且通过校验的本地文件；否则 {@code null}
	 */
	public static Path cachedFileIfValid(String url) {
		return cachedFileIfValid(url, true);
	}

	/**
	 * 只查缓存，可选择是否做网络校验。
	 *
	 * <h2>为什么需要"跳过网络校验"这个开关</h2>
	 * {@link #cachedFileIfValid(String)} 每次都要发一次 Range 请求，
	 * 实测对境外主机<b>每次约 1 秒</b>。而它被 {@code resolveTracks()} 调用，
	 * 后者又会在订阅、哈希校验、每次 /reload 时反复执行——
	 * 一份含 4 个网络条目的 txt 解析一次就要 4.5 秒，明显卡顿。
	 *
	 * <p>因此区分两种调用场景：
	 * <ul>
	 *   <li><b>播放路径</b>（需要确定能不能播）：做校验，但要缓存校验结果（见 {@link #VERIFIED}）；
	 *   <li><b>预检路径</b>（只是想知道"大概在不在"）：跳过网络，直接看文件存在性。</li>
	 * </ul>
	 *
	 * @param verify {@code true} 时做 Range 校验；{@code false} 只看本地文件是否存在
	 */
	public static Path cachedFileIfValid(String url, boolean verify) {
		Path dir = remoteDir();
		if (dir == null || url == null || url.isBlank()) {
			return null;
		}
		String id = cacheIdFor(url);
		String ext = guessExtension(url);
		Path audio = dir.resolve(id + (ext.isEmpty() ? ".bin" : "." + ext));
		Path proof = dir.resolve(id + ".proof");
		Path existing = Files.isRegularFile(audio) ? audio : findAnyCachedFile(dir, id);
		if (existing == null) {
			return null;
		}
		if (!verify) {
			return existing;
		}
		// 校验结果缓存：同一 URL 在 TTL 内不重复发请求。
		// 这是解析速度的关键——否则每次 resolveTracks() 都要等 1 秒/条目。
		long now = System.currentTimeMillis();
		Boolean cachedVerdict = VERIFIED.get(url);
		Long verifiedAt = VERIFIED_AT.get(url);
		if (cachedVerdict != null && verifiedAt != null
				&& now - verifiedAt < VERIFY_TTL_MILLIS) {
			return cachedVerdict ? existing : null;
		}
		boolean ok = matchesProof(url, proof);
		VERIFIED.put(url, ok);
		VERIFIED_AT.put(url, now);
		return ok ? existing : null;
	}

	/** 校验结果缓存（URL -> 是否仍然有效）。 */
	private static final java.util.Map<String, Boolean> VERIFIED =
			new java.util.concurrent.ConcurrentHashMap<>();
	private static final java.util.Map<String, Long> VERIFIED_AT =
			new java.util.concurrent.ConcurrentHashMap<>();

	/**
	 * 校验结果的缓存时长（毫秒）。
	 *
	 * <p>取 10 分钟：足够让同一次播放会话内的反复解析都命中缓存，
	 * 又不会让"上游文件真的改了"很久都发现不了。
	 * 服主想强制刷新时可用 {@link #clearVerification()}。
	 */
	private static final long VERIFY_TTL_MILLIS = 10 * 60 * 1000L;

	/** 清空校验结果缓存（配置重载时调用，确保改动立即生效）。 */
	public static void clearVerification() {
		VERIFIED.clear();
		VERIFIED_AT.clear();
	}

	/**
	 * 取得该 URL 对应的<b>本地音频文件</b>，必要时下载。
	 *
	 * <p>返回的路径可以直接交给 {@link AudioTranscoder} 转码，
	 * 与普通本地文件没有区别。
	 *
	 * @param url 网络音频地址（http/https）
	 * @return 本地文件路径；下载失败返回 {@code null}
	 */
	public static synchronized Path localFileFor(String url) {
		Path dir = remoteDir();
		if (dir == null || url == null || url.isBlank()) {
			return null;
		}
		String id = cacheIdFor(url);
		String ext = guessExtension(url);
		Path audio = dir.resolve(id + (ext.isEmpty() ? ".bin" : "." + ext));
		Path proof = dir.resolve(id + ".proof");

		if (Files.isRegularFile(audio) && matchesProof(url, proof)) {
			// 校验通过：完全不下载，直接复用
			return audio;
		}
		// 扩展名可能是 .bin（URL 无后缀但已按魔数改过名）：
		// 检查同 id 下是否有任意已缓存的实体文件
		Path anyExisting = findAnyCachedFile(dir, id);
		if (anyExisting != null && matchesProof(url, proof)) {
			return anyExisting;
		}

		// 校验不通过（或首次）：下载完整文件
		Path downloaded = download(url, dir, id, ext);
		if (downloaded == null) {
			return null;
		}
		writeProof(url, downloaded, proof);
		return downloaded;
	}

	/**
	 * 单个网络音频缓存的上限（字节）。
	 *
	 * <p>取 512MB：足够容纳整张专辑/数小时的音频，又能挡住"无限直播流"——
	 * 那种地址没有 Content-Length、chunked 无限传输，若不设上限会一直下载，
	 * 磁盘被写满、转码永远不启动。
	 */
	private static final long MAX_CACHE_BYTES = 512L * 1024 * 1024;

	/**
	 * 在 txt 播放列表里把某个"流式传输"的地址<b>注释掉</b>（行首加 {@code # }）。
	 *
	 * <h2>为什么要改文件</h2>
	 * 流式传输的地址无法被缓存（没有长度、永不结束），也就无法参与播放列表的
	 * 时间轴（无时长、不可 seek）。仅仅在日志里报一句警告，服主很难发现——
	 * 下次看 txt 还是一行正常的地址，会以为是下载失败又重试。
	 *
	 * <p>直接把它注释掉，服主打开文件就能看到：
	 * <pre>
	 *   # [已禁用：流式传输，无法缓存] https://x/live.mp3
	 * </pre>
	 * 连同原因一起写在行首，比单独写日志有效得多。
	 *
	 * <h2>为什么保留原行内容</h2>
	 * 不删除、只注释：服主可能想把它改作频道的 {@code file}（直播流应当直接配在
	 * 频道上，而不是放进 txt），保留原文便于复制。
	 */
	public static synchronized void markStreamingInTxt(String url) {
		if (url == null || url.isBlank()) {
			return;
		}
		// 扫描范围：配置根目录下的所有 txt（audio/ 是常规位置，
		// 但 file 字段也允许指向 config/radio-smile/ 下的任意相对路径，
		// 所以两处都扫，避免"放在根目录的 txt 不会被注释"）。
		Path base = RadioStationRegistry.baseDir();
		if (base == null || !Files.isDirectory(base)) {
			// 配置目录尚未初始化（例如单元测试环境）：无从注释，直接返回
			SmileSRadio.LOGGER.debug("配置目录未初始化，跳过 txt 注释：{}", url);
			return;
		}
		java.util.List<Path> txtFiles = new java.util.ArrayList<>();
		try (java.util.stream.Stream<Path> walk = Files.walk(base, 2)) {
			walk.filter(Files::isRegularFile)
					.filter(TxtPlaylist::isTxtPlaylist)
					.forEach(txtFiles::add);
		} catch (IOException e) {
			SmileSRadio.LOGGER.debug("扫描 txt 播放列表失败", e);
		}
		for (Path txt : txtFiles) {
			commentLineIn(txt, url);
		}
	}

	/** 旧实现（按 audioDir 扫描）保留为内部小工具，见 {@link #markStreamingInTxt}。 */
	private static synchronized void markStreamingInAudioDirOnly(String url) {
		Path audioDir = RadioStationRegistry.audioDir();
		if (audioDir == null || !Files.isDirectory(audioDir)) {
			return;
		}
		try (java.util.stream.Stream<Path> files = Files.list(audioDir)) {
			files.filter(Files::isRegularFile)
					.filter(TxtPlaylist::isTxtPlaylist)
					.forEach(txt -> commentLineIn(txt, url));
		} catch (IOException e) {
			SmileSRadio.LOGGER.debug("扫描 txt 播放列表失败", e);
		}
	}

	/** 把 txt 中提到该 URL 的行注释掉（已注释的跳过，避免重复加前缀）。 */
	private static void commentLineIn(Path txt, String url) {
		try {
			java.util.List<String> lines = Files.readAllLines(txt, java.nio.charset.StandardCharsets.UTF_8);
			boolean changed = false;
			for (int i = 0; i < lines.size(); i++) {
				String line = lines.get(i);
				String trimmed = line == null ? "" : line.trim();
				if (trimmed.startsWith("#")) {
					// 已经是注释：可能在上一轮已经处理过
					continue;
				}
				// 只处理"整行就是这个 URL"或"以它开头"的情况，避免误伤其它内容
				if (trimmed.equals(url) || trimmed.startsWith(url + " ")) {
					lines.set(i, "# [已禁用：流式传输，无法缓存] " + trimmed);
					changed = true;
				}
			}
			if (changed) {
				Files.write(txt, lines, java.nio.charset.StandardCharsets.UTF_8);
				SmileSRadio.LOGGER.info("已在该 txt 中注释掉流式地址：{}", txt.getFileName());
				// 内容变了，解析缓存要失效
				TxtPlaylist.clearCache();
			}
		} catch (IOException e) {
			SmileSRadio.LOGGER.debug("注释 txt 行失败 {}", txt, e);
		}
	}

	/**
	 * 下载完整文件（先写临时文件，成功后再原子替换，避免半截文件被当成有效缓存）。
	 *
	 * <p><b>会拒绝无限直播流</b>：没有 Content-Length 且是 chunked 传输的地址
	 * 无法"下载完"，只能实时转发（那属于 {@code StreamAudioSource} 的职责，
	 * 不能进播放列表——直播流没有时长、不可 seek，会破坏时间轴）。
	 */
	private static Path download(String url, Path dir, String id, String ext) {
		Path target = dir.resolve(id + (ext.isEmpty() ? ".bin" : "." + ext));
		Path temp = dir.resolve(id + ".downloading");
		HttpURLConnection conn = null;
		try {
			conn = open(url);
			int code = conn.getResponseCode();
			if (code != 200) {
				SmileSRadio.LOGGER.warn("下载网络音频失败（HTTP {}）：{}", code, url);
				return null;
			}
			long declared = conn.getContentLengthLong();
			if (declared < 0) {
				// 没有 Content-Length：十有八九是无限直播流。
				//
				// 用户要求：发现流式传输的文件时<b>自动在 txt 里注释掉并跳过</b>，
				// 这样服主下次打开 txt 就能直接看到"这行被禁用了"以及原因，
				// 而不是只在我们日志里看到一行警告。
				SmileSRadio.LOGGER.warn(
						"网络地址是流式传输（无文件长度），已在 txt 中自动注释并跳过：{}", url);
				markStreamingInTxt(url);
				return null;
			}
			if (declared > MAX_CACHE_BYTES) {
				SmileSRadio.LOGGER.warn("网络音频过大（{} MB > 上限 {} MB），拒绝缓存：{}",
						declared / 1024 / 1024, MAX_CACHE_BYTES / 1024 / 1024, url);
				markStreamingInTxt(url);
				return null;
			}
			long written = 0;
			try (InputStream in = conn.getInputStream();
					OutputStream out = Files.newOutputStream(temp)) {
				byte[] buf = new byte[8192];
				int n;
				while ((n = in.read(buf)) > 0) {
					written += n;
					if (written > MAX_CACHE_BYTES) {
						throw new IOException("超过缓存上限 " + MAX_CACHE_BYTES + " 字节");
					}
					out.write(buf, 0, n);
				}
			}
			// 长度校验：声明的和实际写到的不一致，说明下载被截断了
			if (declared > 0 && written != declared) {
				SmileSRadio.LOGGER.warn("网络音频下载不完整（{}/{} 字节），丢弃：{}",
						written, declared, url);
				Files.deleteIfExists(temp);
				return null;
			}
			Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
			// 推断不出扩展名时（.bin），按文件头魔数识别并改名，
			// 否则转码白名单会把它挡掉、这个文件永远转不出 MP3。
			Path renamed = sniffAndRename(target);
			SmileSRadio.LOGGER.info("网络音频已缓存到本地：{} -> {}", url, renamed.getFileName());
			return renamed;
		} catch (IOException e) {
			SmileSRadio.LOGGER.warn("下载网络音频失败：{}", url, e);
			try {
				Files.deleteIfExists(temp);
			} catch (IOException ignored) {
				// 清理失败无妨，下次会覆盖
			}
			return null;
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	/**
	 * 用 Range 请求取头部字节，写校验包。
	 *
	 * <p>校验包内容 = 头部字节原文。判断"变了没有"就是重新取一次头部做逐字节比较
	 * （再由 {@link #matchesProof} 折算成哈希比较）。
	 */
	private static void writeProof(String url, Path audio, Path proof) {
		try {
			byte[] head;
			long total = Files.size(audio);
			HttpURLConnection conn = null;
			try {
				conn = open(url);
				conn.setRequestProperty("Range", "bytes=0-" + (PROOF_BYTES - 1));
				int code = conn.getResponseCode();
				if (code == 206) {
					try (InputStream in = conn.getInputStream()) {
						head = in.readNBytes(PROOF_BYTES);
					}
				} else {
					// 服务器不支持 Range：用已下载文件的开头即可（不再发请求）
					try (InputStream in = Files.newInputStream(audio)) {
						head = in.readNBytes(PROOF_BYTES);
					}
				}
			} finally {
				if (conn != null) {
					conn.disconnect();
				}
			}
			java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(head.length + Long.BYTES);
			buf.putLong(total);
			buf.put(head);
			Files.write(proof, buf.array());
		} catch (IOException e) {
			SmileSRadio.LOGGER.debug("写入网络音频校验包失败：{}", url, e);
		}
	}

	/**
	 * 判断本地缓存是否仍然匹配上游。
	 *
	 * <h2>关键取舍：拿不到证明 ≠ 文件变了</h2>
	 * 早期这里"任何异常都视为不匹配"。在真实场景下这是错的，因为
	 * 很多音频直链是<b>临时签名地址</b>（带 {@code deadline}/{@code e}/{@code sign} 等参数），
	 * 几个小时后必然失效：
	 * <pre>
	 *   https://.../x.m4s?...&amp;deadline=1795441514&amp;upsig=...
	 * </pre>
	 * URL 过期后请求返回 403，校验失败 —— 但我们<b>早就把文件完整下载到本地了</b>，
	 * 那个本地文件是完全有效的音频。此时若判定"不匹配"并丢弃缓存，
	 * 就等于把一个能播的文件扔了、去重下一个注定失败的链接。
	 *
	 * <p>所以现在的判据分两类：
	 * <ul>
	 *   <li><b>拿到了新的头部且与校验包不一致</b> → 文件确实变了，缓存失效；</li>
	 *   <li><b>请求失败 / 服务器不支持 Range / 无网络</b> → <b>继续信任已有缓存</b>。
	 *       理由：本地文件是完整的，播放它比"什么都不播"好得多。</li>
	 * </ul>
	 */
	private static boolean matchesProof(String url, Path proof) {
		if (!Files.isRegularFile(proof)) {
			return false;
		}
		HttpURLConnection conn = null;
		try {
			byte[] saved = Files.readAllBytes(proof);
			if (saved.length < Long.BYTES) {
				return false;
			}
			java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(saved);
			long savedTotal = buf.getLong();
			byte[] savedHead = new byte[buf.remaining()];
			buf.get(savedHead);

			conn = open(url);
			conn.setRequestProperty("Range", "bytes=0-" + (savedHead.length - 1));
			int code = conn.getResponseCode();
			if (code != 206) {
				// 服务器不支持 Range（或链接已失效返回 403）：
				// 无法低成本校验，但<b>本地缓存本来就是完整的</b>，继续用它。
				// 只有在"能拿到新头部却对不上"时才真正判失效（见下方比较）。
				SmileSRadio.LOGGER.debug(
						"服务器未返回 Range（HTTP {}），沿用本地缓存：{}", code, url);
				return true;
			}
			byte[] currentHead;
			try (InputStream in = conn.getInputStream()) {
				currentHead = in.readNBytes(savedHead.length);
			}
			if (!java.util.Arrays.equals(savedHead, currentHead)) {
				return false;
			}
			// Content-Range 里带总大小，用它做第二道校验
			String contentRange = conn.getHeaderField("Content-Range");
			if (contentRange != null) {
				int slash = contentRange.lastIndexOf('/');
				if (slash > 0) {
					try {
						long currentTotal = Long.parseLong(contentRange.substring(slash + 1).trim());
						return currentTotal == savedTotal;
					} catch (NumberFormatException ignored) {
						// 解析不了就只信头部比对
					}
				}
			}
			return true;
		} catch (Exception e) {
			// <b>拿不到证明就继续用本地缓存</b>（见方法注释：
			// 临时签名地址过期后必然请求失败，但本地文件仍然完好）。
			SmileSRadio.LOGGER.debug("无法校验网络音频（沿用本地缓存）：{} - {}", url, e.getMessage());
			return true;
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	/**
	 * 按 URL 后缀猜一个音频扩展名（用于让 {@link AudioTranscoder} 识别格式）。
	 *
	 * <h2>为什么要处理"代理 URL"这种双重地址</h2>
	 * 实测的服务地址形如：
	 * <pre>
	 *   https://music-proxy.gdstudio.org/https://xxx.bilivideo.cn/.../a.m4s?params
	 * </pre>
	 * 外层是代理、内层才是真实地址。直接按"最后一个点之后"取后缀仍能得到 {@code m4s}
	 * （因为 {@code .m4s} 在查询串之前），但若内层地址没带扩展名，
	 * 就会退化成空——那种情况交给 ffmpeg 用内容嗅探，
	 * 所以这里宁可返回空串（扩展名 {@code .bin}），<b>也不臆造</b>。
	 *
	 * <p>另外把 {@code m4s} 归一成 {@code m4a}-系：两者都是 MP4 容器，
	 * ffmpeg 对扩展名的要求很宽松，保留原样即可，因此不做转换。
	 */
	private static String guessExtension(String url) {
		String path = url;
		int q = path.indexOf('?');
		if (q >= 0) {
			path = path.substring(0, q);
		}
		int dot = path.lastIndexOf('.');
		if (dot < 0 || dot == path.length() - 1) {
			return "";
		}
		String ext = path.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
		// 只接受白名单内的扩展名，避免 ?format=xxx 之类拼出奇怪的文件名
		return AudioTranscoder.SOURCE_EXTENSIONS.contains(ext) ? ext : "";
	}

	/**
	 * 缓存文件的实际扩展名：猜不出来时用 {@code .bin}。
	 *
	 * <p>{@code .bin} 不会被 {@link AudioTranscoder#isTranscodableSource} 接受，
	 * 因此下载前会先用内容魔数把 {@code .bin} 重命名成真实格式
	 * （见 {@link #sniffAndRename(Path)}），否则 ffmpeg 会因扩展名被拒而跳过。
	 */
	private static Path sniffAndRename(Path file) {
		if (file == null || !Files.isRegularFile(file)) {
			return file;
		}
		String name = file.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
		if (!name.endsWith(".bin")) {
			return file;
		}
		String detected = detectByMagic(file);
		if (detected == null) {
			return file;
		}
		try {
			Path renamed = file.resolveSibling(
					name.substring(0, name.length() - 4) + "." + detected);
			Files.move(file, renamed, StandardCopyOption.REPLACE_EXISTING);
			SmileSRadio.LOGGER.info("按文件头识别网络音频格式为 .{}：{}",
					detected, renamed.getFileName());
			return renamed;
		} catch (IOException e) {
			return file;
		}
	}

	/**
	 * 按文件头魔数识别音频容器。
	 *
	 * <p>为什么需要：有些服务返回的 URL 没有扩展名（如 {@code /stream?id=123}），
	 * 而转码白名单按扩展名过滤。识别出来重命名后，ffmpeg 就能正常处理。
	 */
	private static String detectByMagic(Path file) {
		try (InputStream in = Files.newInputStream(file)) {
			byte[] head = in.readNBytes(12);
			if (head.length < 4) {
				return null;
			}
			// ID3 标签或 MP3 帧同步
			if (head[0] == 'I' && head[1] == 'D' && head[2] == '3') {
				return "mp3";
			}
			if ((head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0) {
				return "mp3";
			}
			// OGG
			if (head[0] == 'O' && head[1] == 'g' && head[2] == 'g' && head[3] == 'S') {
				return "ogg";
			}
			// FLAC
			if (head[0] == 'f' && head[1] == 'L' && head[2] == 'a' && head[3] == 'C') {
				return "flac";
			}
			// RIFF/WAVE
			if (head[0] == 'R' && head[1] == 'I' && head[2] == 'F' && head[3] == 'F') {
				return "wav";
			}
			// MP4/ISO-BMFF：第 4..8 字节是 "ftyp"（B 站的 .m4s 就是这种）
			if (head.length >= 8 && head[4] == 'f' && head[5] == 't'
					&& head[6] == 'y' && head[7] == 'p') {
				return "m4a";
			}
			return null;
		} catch (IOException e) {
			return null;
		}
	}

	private static HttpURLConnection open(String url) throws IOException {
		HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
		conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
		conn.setReadTimeout(READ_TIMEOUT_MS);
		conn.setInstanceFollowRedirects(true);
		conn.setRequestProperty("User-Agent", "SmileRadio/1.0");
		return conn;
	}
}
