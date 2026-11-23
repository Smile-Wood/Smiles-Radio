package radiosmile.client.config;

import radiosmile.SmileSRadio;
import smile_libbox.api.ui.ConfigSpec;
import smile_libbox.api.ui.Ui;

/**
 * 构造配置界面的描述。
 *
 * <p>只填数据，渲染交给前置库的 {@code ConfigScreen}：行高、滚动、重置键等
 * 都由库实现，和原版控件一致。
 *
 * <p><b>数值项一律用 {@code number(...)}（boxOnly）</b>：这些值范围都很大
 * （带宽 8~1024、分块 512~65536、缓冲 200~5000），用滑条根本拖不准，
 * 所以只留数字输入框——没有滑条、也没有右侧的 T/≡ 切换键，控件列整条给输入框。
 * 键入时实时校验、非法标红，回车或关界面时写回。
 *
 * <p>每个控件都要给「当前值」和「默认值」两份：默认值用于右侧的重置键。
 */
public final class RadioConfigScreenFactory {
	private RadioConfigScreenFactory() {
	}

	public static Object create(Object parent) {
		RadioClientConfig config = RadioClientConfig.get();
		RadioClientConfig defaults = RadioClientConfig.defaults();

		ConfigSpec spec = new ConfigSpec(
				Ui.tr("config.radio-smile.title"),
				parent,
				config::save);

		// ---- 流设置 ----
		ConfigSpec.Page stream = spec.page(Ui.tr("config.radio-smile.tab.stream"));

		stream.toggle("enableStreaming",
				Ui.tr("config.radio-smile.enableStreaming"),
				Ui.tr("config.radio-smile.enableStreaming.tip"),
				config.enableStreaming,
				defaults.enableStreaming,
				value -> config.enableStreaming = value);

		stream.number("maxBandwidthKbps",
				Ui.tr("config.radio-smile.maxBandwidth"),
				Ui.tr("config.radio-smile.maxBandwidth.tip"),
				config.maxBandwidthKbps, 8, 1024,
				defaults.maxBandwidthKbps,
				value -> config.maxBandwidthKbps = value);

		stream.number("maxChunkBytes",
				Ui.tr("config.radio-smile.maxChunk"),
				Ui.tr("config.radio-smile.maxChunk.tip"),
				config.maxChunkBytes, 512, 65536,
				defaults.maxChunkBytes,
				value -> config.maxChunkBytes = value);

		stream.number("bufferMillis",
				Ui.tr("config.radio-smile.buffer"),
				Ui.tr("config.radio-smile.buffer.tip"),
				config.bufferMillis, 200, 5000,
				defaults.bufferMillis,
				value -> config.bufferMillis = value);

		// ---- 调试 ----
		ConfigSpec.Page debug = spec.page(Ui.tr("config.radio-smile.tab.debug"));
		debug.toggle("showNetworkStats",
				Ui.tr("config.radio-smile.showStats"),
				Ui.tr("config.radio-smile.showStats.tip"),
				config.showNetworkStats,
				defaults.showNetworkStats,
				value -> config.showNetworkStats = value);
		debug.text(Ui.tr("config.radio-smile.hint.bandwidth"));

		SmileSRadio.LOGGER.debug("配置界面已构建");
		return Ui.config(parent, spec);
	}
}
