package radiosmile.net;

import java.nio.charset.StandardCharsets;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;

import radiosmile.SmileSRadio;
import radiosmile.radio.RadioStation;
import radiosmile.radio.RadioStationRegistry;

/** 服务端网络处理：响应列表请求、选择频道、图标请求。 */
public final class RadioServerNetwork {
	private RadioServerNetwork() {
	}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(RadioNetworking.C2S_REQUEST_STATIONS,
				(server, player, handler, buf, sender) -> sendStations(server, player));

		ServerPlayNetworking.registerGlobalReceiver(RadioNetworking.C2S_SELECT_STATION,
				(server, player, handler, buf, sender) -> {
					String stationId = buf.readUtf();
					net.minecraft.core.BlockPos pos = buf.readBlockPos();
					server.execute(() -> {
						persistSelection(player, pos, stationId);
						sendSelectAck(player, stationId, pos);
					});
				});

		ServerPlayNetworking.registerGlobalReceiver(RadioNetworking.C2S_REQUEST_ICON,
				(server, player, handler, buf, sender) -> {
					String stationId = buf.readUtf();
					server.execute(() -> sendIcon(player, stationId));
				});

		// 音频订阅：客户端声明带宽期望与单块上限，服务端按自适应块大小推送
		ServerPlayNetworking.registerGlobalReceiver(RadioNetworking.C2S_AUDIO_SUBSCRIBE,
				(server, player, handler, buf, sender) -> {
					net.minecraft.core.BlockPos pos = buf.readBlockPos();
					String stationId = buf.readUtf();
					int bandwidthKbps = buf.readVarInt();
					int maxChunk = buf.readVarInt();
					server.execute(() -> AudioStreamManager.subscribe(
							player, pos, stationId, bandwidthKbps, maxChunk));
				});

		ServerPlayNetworking.registerGlobalReceiver(RadioNetworking.C2S_AUDIO_UNSUBSCRIBE,
				(server, player, handler, buf, sender) -> {
					net.minecraft.core.BlockPos pos = buf.readBlockPos();
					server.execute(() -> AudioStreamManager.unsubscribe(player, pos));
				});

		// 音量写回方块 NBT（否则关掉界面 / 重进世界就丢失）
		ServerPlayNetworking.registerGlobalReceiver(RadioNetworking.C2S_SET_VOLUME,
				(server, player, handler, buf, sender) -> {
					net.minecraft.core.BlockPos pos = buf.readBlockPos();
					float volume = buf.readFloat();
					server.execute(() -> persistVolume(player, pos, volume));
				});
	}

	/** 把音量写回方块 NBT，同样做距离校验。 */
	private static void persistVolume(ServerPlayer player, net.minecraft.core.BlockPos pos, float volume) {
		if (pos == null) {
			return;
		}
		var level = player.level();
		if (!level.isLoaded(pos)) {
			return;
		}
		if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0) {
			return;
		}
		if (level.getBlockEntity(pos) instanceof radiosmile.block.entity.RadioBlockEntity be) {
			be.setVolume(volume);
		}
	}

	/** 下发电台列表 + 播放计时基准（所有人共用同一基准，从而保证听到的一致）。 */
	public static void sendStations(MinecraftServer server, ServerPlayer player) {
		if (!ServerPlayNetworking.canSend(player, RadioNetworking.S2C_STATIONS)) {
			return;
		}
		JsonArray arr = new JsonArray();
		for (RadioStation station : RadioStationRegistry.stations()) {
			JsonObject obj = station.toJson();
			obj.addProperty("hash", RadioStationRegistry.fileHash(station.id()));
			arr.add(obj);
		}
		String json = arr.toString();

		FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeLong(RadioStationRegistry.startEpochMillis());
		buf.writeUtf(json);
		ServerPlayNetworking.send(player, RadioNetworking.S2C_STATIONS, buf);
	}

	/**
	 * 把选中的频道写回方块 NBT，并限定距离防止远程篡改。
	 *
	 * <p>这一步是「再次打开界面默认高亮当前频道」能成立的前提：
	 * 没有持久化，方块就记不住上次选了什么。
	 */
	private static void persistSelection(ServerPlayer player, net.minecraft.core.BlockPos pos,
			String stationId) {
		if (pos == null) {
			return;
		}
		var level = player.level();
		if (!level.isLoaded(pos)) {
			return;
		}
		// 只允许操作 8 格范围内的收音机，避免任意方块被远程改写
		if (player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) > 64.0) {
			SmileSRadio.LOGGER.warn("玩家 {} 尝试修改距离过远的收音机 {}", player.getName().getString(), pos);
			return;
		}
		if (level.getBlockEntity(pos) instanceof radiosmile.block.entity.RadioBlockEntity be) {
			be.setStation(stationId);
		}
	}

	/** 回执里带上该方块的音量，客户端据此显示本机独立的音量值。 */
	private static void sendSelectAck(ServerPlayer player, String stationId, net.minecraft.core.BlockPos pos) {
		float volume = 1.0F;
		if (pos != null && player.level().getBlockEntity(pos)
				instanceof radiosmile.block.entity.RadioBlockEntity be) {
			volume = be.getVolume();
		}
		if (!ServerPlayNetworking.canSend(player, RadioNetworking.S2C_SELECT_ACK)) {
			return;
		}
		String hash = RadioStationRegistry.fileHash(stationId);
		long epoch = RadioStationRegistry.startEpochMillis();

		// 握手包一次性下发所有「客户端解码/同步所需」的元数据，避免每块重复发送：
		// 采样格式、应跳过的时长、计时基准。
		radiosmile.radio.RadioStation station = findByid(stationId);
		radiosmile.radio.AudioFormat format = radiosmile.radio.AudioFormat.UNKNOWN;
		long skipMillis = 0L;
		if (station != null) {
			format = radiosmile.radio.AudioFormat.ofFile(station.file());
			skipMillis = computeSkipMillis(station);
		}

		FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeUtf(stationId);
		buf.writeUtf(hash);
		buf.writeLong(epoch);
		buf.writeFloat(volume);
		buf.writeVarInt(format.id());
		buf.writeVarLong(skipMillis);
		// <b>带上方块坐标</b>：客户端收到回执时就要开始播调台噪，
		// 而那时【还没有任何音频块到达】（网络流建连要 1 秒）。
		// 没有坐标，噪声无法确定方位与音量归属，只能等首块数据才响——
		// 听感就是「等到 HTTP 载入完成才出声」。
		buf.writeBlockPos(pos == null ? net.minecraft.core.BlockPos.ZERO : pos);
		ServerPlayNetworking.send(player, RadioNetworking.S2C_SELECT_ACK, buf);
		SmileSRadio.LOGGER.info("玩家 {} 选择频道 {}（hash={}）", player.getName().getString(), stationId, hash);
	}

	private static radiosmile.radio.RadioStation findByid(String stationId) {
		for (radiosmile.radio.RadioStation s : RadioStationRegistry.stations()) {
			if (s.id().equals(stationId)) {
				return s;
			}
		}
		return null;
	}

	/**
	 * 计算应跳过的播放时长（毫秒）。
	 *
	 * <p>本地音频一律推 MP3，订阅时已按字节偏移精确定位，正常情况下这个值<b>不会被用到</b>
	 * （{@code FileAudioSource} 的 skipMillis 为 0）。这里仍按计时基准算出它，
	 * 是为了守住「协议字段永远有确定值」这条线：客户端拿到的一定是服务端的真实结论，
	 * 而不是一个「待会再说」的空位。
	 *
	 * <p>曾经这个字段承载的是「OGG 从头推流、客户端解码后丢弃前 N 毫秒」的降级语义。
	 * 那条链路已彻底移除——它既做不到多人同步，丢了块也永远追不回来。
	 */
	private static long computeSkipMillis(radiosmile.radio.RadioStation station) {
		if (station == null || station.isStreaming()) {
			return 0L;
		}
		long epoch = RadioStationRegistry.startEpochMillis();
		if (epoch <= 0) {
			return 0L;
		}
		return Math.max(0L, System.currentTimeMillis() - epoch);
	}

	private static void sendIcon(ServerPlayer player, String stationId) {
		if (!ServerPlayNetworking.canSend(player, RadioNetworking.S2C_ICON)) {
			return;
		}
		RadioStation target = null;
		for (RadioStation station : RadioStationRegistry.stations()) {
			if (station.id().equals(stationId)) {
				target = station;
				break;
			}
		}
		byte[] bytes = target == null ? new byte[0] : RadioStationRegistry.readIconBytes(target);

		FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
		buf.writeUtf(stationId);
		buf.writeByteArray(bytes);
		ServerPlayNetworking.send(player, RadioNetworking.S2C_ICON, buf);
	}
}