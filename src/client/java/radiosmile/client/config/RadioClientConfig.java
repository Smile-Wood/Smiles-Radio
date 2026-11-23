package radiosmile.client.config;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import radiosmile.SmileSRadio;

/**
 * 客户端配置：音频流带宽与其它本地设置。
 *
 * <p>这些值只影响本机，不会同步到服务端，因此用本地 JSON 保存。
 * 界面由前置库 {@code smile_libbox} 的 {@code ConfigSpec}/{@code ConfigScreen} 渲染。
 */
public final class RadioClientConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "radio-smile-client.json";

	private static RadioClientConfig instance;

	// ---- 配置项 ----

	/**
	 * 音频流带宽上限（KB/s）。
	 *
	 * <p>这是<b>期望值</b>，不是硬上限：服务端会据此决定每次发送的分块大小，
	 * 实际块大小还会根据网络状况自动调整。调低可减少占用，但更容易卡顿。
	 */
	public int maxBandwidthKbps = 64;

	/** 单个音频分块的最大字节数。块越大网络开销越小，但延迟与内存占用越高。 */
	public int maxChunkBytes = 8192;

	/** 允许服务端向本机推送音频流（关闭后收音机静音，仅显示界面）。 */
	public boolean enableStreaming = true;

	/** 在界面上显示网络状态（分块大小、缓冲量等调试信息）。 */
	public boolean showNetworkStats = false;

	/** 音频缓冲区目标时长（毫秒）。越大越抗抖动，但延迟越高。 */
	public int bufferMillis = 1000;

	private RadioClientConfig() {
	}

	/** 一份全默认值的实例，供配置界面计算「重置」目标值。 */
	public static RadioClientConfig defaults() {
		return new RadioClientConfig();
	}

	public static RadioClientConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	private static RadioClientConfig load() {
		Path file = path();
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				RadioClientConfig loaded = GSON.fromJson(reader, RadioClientConfig.class);
				if (loaded != null) {
					loaded.clamp();
					return loaded;
				}
			} catch (Exception e) {
				SmileSRadio.LOGGER.warn("读取客户端配置失败，使用默认值", e);
			}
		}
		RadioClientConfig fresh = new RadioClientConfig();
		fresh.save();
		return fresh;
	}

	public void save() {
		this.clamp();
		try (Writer writer = Files.newBufferedWriter(path(), StandardCharsets.UTF_8)) {
			GSON.toJson(this, writer);
		} catch (IOException e) {
			SmileSRadio.LOGGER.error("保存客户端配置失败", e);
		}
	}

	/** 越界值收敛，避免手改配置导致异常。 */
	private void clamp() {
		this.maxBandwidthKbps = Math.max(8, Math.min(1024, this.maxBandwidthKbps));
		this.maxChunkBytes = Math.max(512, Math.min(65536, this.maxChunkBytes));
		this.bufferMillis = Math.max(200, Math.min(5000, this.bufferMillis));
	}

	/**
	 * 根据带宽上限推算建议的分块字节数。
	 *
	 * <p>目标是一秒内约发送 4 块（250ms 一块），块大小由带宽换算；
	 * 同时受 {@link #maxChunkBytes} 与协议上限约束。
	 */
	public int suggestedChunkBytes() {
		int perSecond = this.maxBandwidthKbps * 1024;
		int perChunk = Math.max(512, perSecond / 4);
		return Math.min(this.maxChunkBytes, perChunk);
	}
}
