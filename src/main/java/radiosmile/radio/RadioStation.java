package radiosmile.radio;

import java.util.Arrays;

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
public record RadioStation(String id, String name, String icon, String file, String mode, long seed,
		String rate) {
	/** 是否为网络流媒体（http/https 地址）。 */
	public boolean isStreaming() {
		return this.file != null
				&& (this.file.startsWith("http://") || this.file.startsWith("https://"));
	}

	/**
	 * 列表里显示的「频率/档位」文案。
	 *
	 * <p>{@code rate} 是给玩家看的自由文本（如 {@code FM105.7}、{@code 第一期}），
	 * 不做任何格式校验——服主想写什么就写什么。
	 *
	 * <p><b>没配置时显示 {@code NETWORK}</b>：这是用户指定的默认值。
	 * 语义上它表示"这不是一个有固定频率/期数的台，而是网络来源"，
	 * 因此对网络电台尤其贴切；本地电台没配 {@code rate} 时同样显示它，
	 * 免得右侧空一块、行列对不齐。
	 */
	public String displayRate() {
		if (this.rate == null || this.rate.isBlank()) {
			return "NETWORK";
		}
		return this.rate.trim();
	}

	/**
	 * 是否为顺序播放列表（列表循环）。
	 *
	 * <h2>mode 字段接受的写法</h2>
	 * 为了让服主不必记住固定单词，下面几组写法都等价：
	 * <table border="1">
	 *   <tr><th>语义</th><th>可用写法</th></tr>
	 *   <tr><td><b>单曲循环</b></td>
	 *       <td>{@code block} / {@code single} / {@code false} / {@code 0}</td></tr>
	 *   <tr><td><b>列表循环</b>（顺序播放整条列表，播完从头）</td>
	 *       <td>{@code sequence} / {@code order} / {@code loop} / {@code true} / {@code 1}</td></tr>
	 *   <tr><td><b>随机播放</b>（固定 seed 打乱，多客户端一致）</td>
	 *       <td>{@code random} / {@code shuffle}</td></tr>
	 * </table>
	 *
	 * <p><b>关于布尔/数字写法的取舍</b>：{@code true}/{@code 1} 映射到「列表循环」而不是
	 * 「随机」，因为读 {@code true} 时最自然的理解是"要按列表循环播放"，
	 * 而不是"随机乱序"。真正的随机必须写 {@code random}——这是<b>不可猜</b>的语义，
	 * 用布尔值表达会让配置变得含糊，所以刻意不提供。
	 */
	public boolean isSequence() {
		return matches("sequence", "order", "loop", "true", "1");
	}

	/** 是否为随机播放列表。 */
	public boolean isRandom() {
		return matches("random", "shuffle");
	}

	/** 是否为单曲循环（mode 未写、或显式写成 block/single/false/0）。 */
	public boolean isBlock() {
		return matches("block", "single", "false", "0");
	}

	/** 是否为播放列表模式（顺序或随机）。 */
	public boolean isPlaylist() {
		return isSequence() || isRandom();
	}

	/**
	 * mode 是否命中给定的任一同义写法（大小写不敏感，忽略首尾空白）。
	 *
	 * <p><b>空值要按"未配置"处理</b>：{@code mode: ""} 与不写 {@code mode} 字段
	 * 在语义上应当一致（都表示"用默认行为"）。早期只判 {@code null}，
	 * 于是写成空串时会落到"未识别"分支，与"缺省"的行为不一致——
	 * 而服主把值删空是很常见的操作。
	 */
	private boolean matches(String... aliases) {
		String normalized = this.mode == null ? "" : this.mode.trim();
		if (normalized.isEmpty()) {
			// 未配置 / 写空：等同于默认的 block（单曲循环）
			return Arrays.stream(aliases).anyMatch(a -> a.equalsIgnoreCase("block"));
		}
		for (String alias : aliases) {
			if (alias.equalsIgnoreCase(normalized)) {
				return true;
			}
		}
		return false;
	}

	public JsonObject toJson() {
		JsonObject obj = new JsonObject();
		obj.addProperty("id", this.id);
		obj.addProperty("name", this.name);
		obj.addProperty("icon", this.icon);
		obj.addProperty("file", this.file);
		obj.addProperty("mode", this.mode);
		obj.addProperty("seed", this.seed);
		obj.addProperty("rate", this.rate == null ? "" : this.rate);
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
						: deriveSeed(id),
				// rate 可选：不填时由 RadioStation.displayRate() 显示 NETWORK
				str(obj, "rate", ""));
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
