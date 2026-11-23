package radiosmile.client.radio;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import radiosmile.radio.RadioStation;

/**
 * 客户端电台数据缓存：服务端下发的频道列表、计时基准、图标。
 */
public final class ClientRadioData {
	private static volatile List<RadioStation> stations = List.of();
	private static volatile long startEpochMillis = System.currentTimeMillis();
	private static volatile boolean loaded;
	private static final Map<String, ResourceLocation> ICONS = new HashMap<>();
	private static final Map<String, byte[]> RAW_ICONS = new HashMap<>();

	private ClientRadioData() {
	}

	public static List<RadioStation> stations() {
		return stations;
	}

	/**
	 * 按 id 找频道；找不到返回 {@code null}。
	 *
	 * <p>用于区分「配置里没有这个频道」与「有但暂时放不出来」——
	 * 前者要显示 NOSIGNAL 并持续报噪音（见 {@code ClientRadioNetwork}）。
	 */
	public static RadioStation findStation(String stationId) {
		if (stationId == null || stationId.isBlank()) {
			return null;
		}
		for (RadioStation station : stations) {
			if (station.id().equals(stationId)) {
				return station;
			}
		}
		return null;
	}

	public static long startEpochMillis() {
		return startEpochMillis;
	}

	public static boolean isLoaded() {
		return loaded;
	}

	/** 接收服务端下发的列表。 */
	public static void acceptStations(long startEpoch, String json) {
		try {
			JsonArray arr = JsonParser.parseString(json).getAsJsonArray();
			List<RadioStation> parsed = new ArrayList<>();
			for (JsonElement el : arr) {
				if (el.isJsonObject()) {
					parsed.add(RadioStation.fromJson(el.getAsJsonObject()));
				}
			}
			stations = List.copyOf(parsed);
			startEpochMillis = startEpoch;
			loaded = true;
		} catch (Exception e) {
			radiosmile.SmileSRadio.LOGGER.error("解析电台列表失败", e);
		}
	}

	/** 接收图标字节并注册为动态贴图。 */
	public static void acceptIcon(String stationId, byte[] bytes) {
		RAW_ICONS.put(stationId, bytes);
		if (bytes.length == 0) {
			return;
		}
		try {
			var image = new com.mojang.blaze3d.platform.NativeImage(
					com.mojang.blaze3d.platform.NativeImage.Format.RGBA, 1, 1, false);
			var digest = net.minecraft.client.renderer.texture.DynamicTexture.class;
			// 通过 ImageIO 载入 PNG 再转 NativeImage
			java.awt.image.BufferedImage buffered = javax.imageio.ImageIO.read(
					new java.io.ByteArrayInputStream(bytes));
			if (buffered == null) {
				return;
			}
			com.mojang.blaze3d.platform.NativeImage nativeImage =
					new com.mojang.blaze3d.platform.NativeImage(buffered.getWidth(), buffered.getHeight(), false);
			for (int y = 0; y < buffered.getHeight(); y++) {
				for (int x = 0; x < buffered.getWidth(); x++) {
					int argb = buffered.getRGB(x, y);
					nativeImage.setPixelRGBA(x, y, argb);
				}
			}
			image.close();

			ResourceLocation id = new ResourceLocation("radio-smile",
					"dynamic/icon_" + sanitize(stationId));
			var textureManager = Minecraft.getInstance().getTextureManager();
			var dynamic = new net.minecraft.client.renderer.texture.DynamicTexture(nativeImage);
			textureManager.register(id, dynamic);
			ICONS.put(stationId, id);
		} catch (Exception e) {
			radiosmile.SmileSRadio.LOGGER.warn("载入频道图标失败 {}", stationId, e);
		}
	}

	private static String sanitize(String value) {
		return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_/.-]", "_");
	}

	public static ResourceLocation iconOf(String stationId) {
		return ICONS.get(stationId);
	}

	public static byte[] rawIcon(String stationId) {
		return RAW_ICONS.get(stationId);
	}

	public static void clear() {
		stations = List.of();
		loaded = false;
		ICONS.clear();
		RAW_ICONS.clear();
	}
}
