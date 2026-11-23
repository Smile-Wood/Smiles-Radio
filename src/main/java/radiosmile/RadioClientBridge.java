package radiosmile;

/**
 * 主源集与客户端源集之间的桥接点。
 *
 * <p>项目使用 Fabric 的 splitEnvironmentSourceSets，主源集无法直接引用客户端类，
 * 因此客户端初始化时把自己的实现注册到这里，服务端环境下为 {@code null}。
 */
public final class RadioClientBridge {
	/** 打开收音机界面（仅客户端可用）。参数为方块当前频道 id 与方块坐标。 */
	public static volatile ScreenOpener openScreen;
	/** 服务端回执时的客户端回调（参数：频道 id、音频 hash、计时基准）。 */
	public static volatile SelectCallback onSelectAck;

	private RadioClientBridge() {
	}

	/** 打开界面并把「当前频道 + 方块坐标」传进去。 */
	@FunctionalInterface
	public interface ScreenOpener {
		void open(String currentStationId, net.minecraft.core.BlockPos pos);
	}

	@FunctionalInterface
	public interface SelectCallback {
		void accept(String stationId, String fileHash, long startEpochMillis);
	}
}
