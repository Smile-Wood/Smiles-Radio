package radiosmile.radio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import radiosmile.SmileSRadio;

/**
 * 播放列表解析：把一个目录解析成有序的音频文件列表。
 *
 * <p><b>为什么要服务端 / 固定 seed 打乱</b>：
 * 要让所有玩家听到一样，除了时间同步，还要保证「当前播的是同一首」。
 * 如果各客户端各自用 {@link Random} 打乱，顺序会不同。因此随机模式统一用
 * {@link RadioStation#seed()} 作为随机源，任何客户端算出的顺序都一致。
 */
public final class RadioPlaylist {
	/**
	 * 允许出现在播放列表目录里的扩展名，<b>直接引用转码方维护的白名单</b>。
	 *
	 * <p><b>为什么不在这里另存一份</b>：一条音频能不能用，标准是「ffmpeg 能不能转码」。
	 * 这个集合在 {@link AudioTranscoder#SOURCE_EXTENSIONS} 里维护，
	 * 这里再抄一份必然漂移，而漂移的症状极难查——
	 * 同一个 .flac「单文件频道能放、放进目录就消失」。
	 *
	 * <p>目录扫描只负责把<b>非音频文件</b>（图片/文本等）挡在外面，
	 * 不做「这个音频格式能不能播」的判断：那是转码与推流层的事。
	 */
	private static final java.util.Set<String> AUDIO_EXTENSIONS = AudioTranscoder.SOURCE_EXTENSIONS;

	private RadioPlaylist() {
	}

	/**
	 * 扫描目录中的音频文件并按电台模式排序。
	 *
	 * @param dir    音频目录
	 * @param random 是否随机（对应 {@code mode=random}）
	 * @param seed   随机种子
	 * @return 有序的音频文件路径；目录不存在或为空时返回空列表
	 */
	public static List<Path> scan(Path dir, boolean random, long seed) {
		if (dir == null || !Files.isDirectory(dir)) {
			return List.of();
		}
		List<Path> found = new ArrayList<>();
		try (Stream<Path> stream = Files.list(dir)) {
			stream.filter(Files::isRegularFile)
					.filter(RadioPlaylist::isAudio)
					.forEach(found::add);
		} catch (IOException e) {
			SmileSRadio.LOGGER.warn("扫描音频目录失败 {}", dir, e);
			return List.of();
		}

		// 文件名自然序：1,2,10 而不是 1,10,2
		found.sort((a, b) -> NaturalOrder.INSTANCE.compare(
				a.getFileName().toString(), b.getFileName().toString()));

		if (random && found.size() > 1) {
			Collections.shuffle(found, new Random(seed));
		}
		return List.copyOf(found);
	}

	public static boolean isAudio(Path path) {
		// 扩展名解析统一走 AudioTranscoder.extensionOf：白名单和「怎么取扩展名」
		// 都属于同一条判定链，分开写迟早会有一边漏改。
		return AUDIO_EXTENSIONS.contains(AudioTranscoder.extensionOf(path));
	}

	/** 稳定地把任意字符串映射到非负索引。 */
	public static int stableIndex(long seed, int size) {
		if (size <= 0) {
			return 0;
		}
		return Math.floorMod(Long.hashCode(seed), size);
	}
}
