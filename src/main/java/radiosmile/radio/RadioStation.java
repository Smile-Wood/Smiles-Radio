package radiosmile.radio;

import com.google.gson.JsonObject;

/**
 * 一个电台（频道）的静态定义，来自服务端配置文件。
 *
 * <p>字段含义：
 * <ul>
 *   <li>{@code id}   频道唯一标识</li>
 *   <li>{@code name} 显示名称</li>
 *   <li>{@code icon} 图标来源（服务端上的图片文件名）</li>
 *   <li>{@code file} 音频文件名、网络流地址，或<b>一个目录名</b>（相对 {@code audio/}）。
 *       当它指向目录时，目录内所有音频文件构成播放列表。</li>
 *   <li>{@code mode} 播放模式：
 *     <ul>
 *       <li>{@code block} 单个音频循环播放（原有行为）</li>
 *       <li>{@code sequence} 播放列表按文件名<b>自然序</b>循环，如 1,2,3,...,10</li>
 *       <li>{@code random} 播放列表按<b>固定 seed 打乱</b>后循环。
 *           固定 seed 由服务端下发，保证所有客户端顺序完全一致（同步的前提）</li>
 *     </ul>
 *   </li>
 *   <li>{@code seed} 随机播放使用的种子；不填则服务端按 id 派生，保证稳定</li>
 * </ul>
 *
 * <p><b>关于同步</b>：随机播放绝不能在客户端用 {@link java.util.Random} 自行打乱，
 * 否则每个人听到的曲目和进度都不同。这里的 {@code seed} 让所有客户端算出同一个顺序。
 */
public record RadioStation(String id, String name, String icon, String file, String mode, long seed) {
	/** 是否为网络流媒体（http/https 地址）。 */
	public boolean isStreaming() {
		return this.file != null
				&& (this.file.startsWith("http://") || this.file.startsWith("https://"));
	}

	/** 是否为顺序播放列表。 */
	public boolean isSequence() {
		return "sequence".equalsIgnoreCase(this.mode);
	}

	/** 是否为随机播放列表。 */
	public boolean isRandom() {
		return "random".equalsIgnoreCase(this.mode);
	}

	/** 是否为播放列表模式（顺序或随机）。 */
	public boolean isPlaylist() {
		return isSequence() || isRandom();
	}

	public JsonObject toJson() {
		JsonObject obj = new JsonObject();
		obj.addProperty("id", this.id);
		obj.addProperty("name", this.name);
		obj.addProperty("icon", this.icon);
		obj.addProperty("file", this.file);
		obj.addProperty("mode", this.mode);
		obj.addProperty("seed", this.seed);
		return obj;
	}

	public static RadioStation fromJson(JsonObject obj) {
		String id = str(obj, "id");
		return new RadioStation(
				id,
				str(obj, "name"),
				str(obj, "icon"),
				str(obj, "file"),
				str(obj, "mode", "block"),
				obj.has("seed") && !obj.get("seed").isJsonNull()
						? obj.get("seed").getAsLong()
						: deriveSeed(id));
	}

	/** 未配置 seed 时按 id 派生一个稳定值，保证多次启动、多客户端结果一致。 */
	public static long deriveSeed(String id) {
		if (id == null) {
			return 0L;
		}
		long hash = 1125899906842597L;
		for (int i = 0; i < id.length(); i++) {
			hash = 31 * hash + id.charAt(i);
		}
		return hash;
	}

	private static String str(JsonObject obj, String key) {
		return str(obj, key, "");
	}

	private static String str(JsonObject obj, String key, String fallback) {
		return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
	}
}
