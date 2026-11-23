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
	public static synchronized Path cachedFileIfValid(String url) {
		Path dir = remoteDir();
		if (dir == null || url == null || url.isBlank()) {
			return null;
		}
		String id = cacheIdFor(url);
		String ext = guessExtension(url);
		Path audio = dir.resolve(id + (ext.isEmpty() ? ".bin" : "." + ext));
		Path proof = dir.resolve(id + ".proof");
		if (!Files.isRegularFile(audio)) {
			return null;
		}
		if (!matchesProof(url, proof)) {
			return null;
		}
		return audio;
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
			SmileSRadio.LOGGER.info("网络音频已缓存到本地：{} -> {}", url, target.getFileName());
			return target;
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
	 * <p>再次用 Range 取头部，与校验包比对。任何异常（断网、服务器不响应）
	 * 一律<b>视为不匹配</b>——宁可重新下载，也不要拿着可能过期的缓存去播。
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
				// 服务器不支持 Range：无法低成本校验，保守地认为需要重新下载
				return false;
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
			SmileSRadio.LOGGER.debug("网络音频校验失败（按需重新下载）：{}", url, e);
			return false;
		} finally {
			if (conn != null) {
				conn.disconnect();
			}
		}
	}

	/** 按 URL 后缀猜一个音频扩展名（用于让 {@link AudioTranscoder} 识别格式）。 */
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

	private static HttpURLConnection open(String url) throws IOException {
		HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
		conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
		conn.setReadTimeout(READ_TIMEOUT_MS);
		conn.setInstanceFollowRedirects(true);
		conn.setRequestProperty("User-Agent", "SmileRadio/1.0");
		return conn;
	}
}
