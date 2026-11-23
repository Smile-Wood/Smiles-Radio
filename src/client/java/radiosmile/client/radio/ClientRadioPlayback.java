package radiosmile.client.radio;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import radiosmile.SmileSRadio;
import radiosmile.radio.RadioStation;

/**
 * 客户端播放控制（首版为接口占位 + 同步时间计算）。
 *
 * <h2>同步算法（重点）</h2>
 * 要保证「所有人听到的一样」，关键是所有客户端用<b>同一个固定基准时间</b>推算当前应播到第几毫秒，
 * 而不是各自从「自己开始播放的时刻」计时。服务端在 {@code S2C_STATIONS} / {@code S2C_SELECT_ACK}
 * 里下发 {@code startEpochMillis}，客户端这样算：
 *
 * <pre>{@code
 * long elapsed   = System.currentTimeMillis() - startEpochMillis;
 * long trackPos  = totalTrackLengthMs <= 0 ? 0 : Math.floorMod(elapsed, totalTrackLengthMs);
 * }</pre>
 *
 * 然后从 {@code trackPos} 处开始播放（seek）。这样无论玩家何时打开界面，算出的位置都一致。
 *
 * <p>注意：原需求里「总长度减去启动时间再取余」的写法，如果直接用「玩家进入时的当前时间」做减法，
 * 每个人进入时间不同，算出的位置就不同，无法对齐；必须以服务端下发的固定 epoch 为基准。
 *
 * <h2>首版状态</h2>
 * 音频解码与输出（MP3 → PCM → 声卡）留待下一步接入（实现在
 * {@code ClientAudioPlayer}），这里先提供：
 * <ul>
 *   <li>当前播放位置计算 {@link #currentOffsetMillis}（可直接用于后续 seek）</li>
 *   <li>选中频道状态与音量状态</li>
 *   <li>{@link #start} / {@link #stop} 的接入点</li>
 * </ul>
 */
public final class ClientRadioPlayback {
	private static String stationId = "";
	private static String fileHash = "";
	private static long startEpochMillis;
	private static float blockVolume = 1.0F;
	/** 音频总长度（毫秒）；接入音频后由解码器填入。 */
	private static volatile long trackLengthMillis;

	private ClientRadioPlayback() {
	}

	public static void onAck(String selectedStationId, String hash, long epoch) {
		stationId = selectedStationId;
		fileHash = hash;
		startEpochMillis = epoch;
		SmileSRadio.LOGGER.debug("服务端确认频道 {}，计时基准={}", selectedStationId, epoch);
		start();
	}

	/**
	 * 按固定基准计算当前应播放到的位置（毫秒）。
	 *
	 * <p><b>网络电台不走这个计算</b>：直播流是「此刻正在发生的内容」，
	 * 客户端从连上那刻开始顺序播放即可，与服务端计时基准无关。
	 * 若强行按 epoch 取模，反而会因为网络延迟与缓冲差异算出错误位置。
	 *
	 * @return 本地音频的播放位置；网络流或总长未知时返回 0
	 */
	public static long currentOffsetMillis() {
		// 网络流媒体：实时播放，不参与服务端计时对齐
		RadioStation station = findStation(stationId);
		if (station != null && station.isStreaming()) {
			return 0L;
		}
		long length = trackLengthMillis;
		if (length <= 0 || startEpochMillis <= 0) {
			return 0;
		}
		long elapsed = System.currentTimeMillis() - startEpochMillis;
		return Math.floorMod(elapsed, length);
	}

	/** 启动播放。音频接入后在此实现 seek + 流式播放。 */
	public static void start() {
		RadioStation station = findStation(stationId);
		if (station == null) {
			SmileSRadio.LOGGER.warn("未知频道 {}", stationId);
			return;
		}
		if (station.isStreaming()) {
			SmileSRadio.LOGGER.debug("频道 {} 是网络流媒体：{}", station.id(), station.file());
			return;
		}
		SmileSRadio.LOGGER.debug("频道 {} 本地音频开始播放，偏移 {} ms",
				station.id(), currentOffsetMillis());
	}

	public static void stop() {
		stationId = "";
		fileHash = "";
		trackLengthMillis = 0;
	}

	public static String currentStationId() {
		return stationId;
	}

	public static String currentFileHash() {
		return fileHash;
	}

	public static float blockVolume() {
		return blockVolume;
	}

	public static void setBlockVolume(float volume) {
		blockVolume = Math.max(0.0F, Math.min(1.0F, volume));
	}

	/**
	 * 最终输出音量 = 方块音量 × 原版音量。
	 * 与原版相乘后天然不会突破原版的音量上限。
	 */
	public static float effectiveVolume() {
		float vanilla = Minecraft.getInstance().options.getSoundSourceVolume(
				net.minecraft.sounds.SoundSource.BLOCKS);
		return blockVolume * vanilla;
	}

	public static void setTrackLengthMillis(long length) {
		trackLengthMillis = length;
	}

	private static RadioStation findStation(String id) {
		for (RadioStation station : ClientRadioData.stations()) {
			if (station.id().equals(id)) {
				return station;
			}
		}
		return null;
	}

	public static ResourceLocation iconOf(String id) {
		return ClientRadioData.iconOf(id);
	}
}