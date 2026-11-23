package radiosmile.block.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

import radiosmile.registry.ModBlockEntities;

/**
 * 收音机方块实体。
 *
 * <p>按需求保存：
 * <ul>
 *   <li>{@code station} 当前频道 id</li>
 *   <li>{@code volume} 本方块独立音量（0.0 ~ 1.0，最终仍会再乘原版音量，不会突破原版上限）</li>
 * </ul>
 */
public class RadioBlockEntity extends BlockEntity {
	private static final String KEY_STATION = "station";
	private static final String KEY_VOLUME = "volume";

	private String station = "";
	private float volume = 1.0F;

	/**
	 * 客户端专用的「故障」显示状态：非 0 表示当前放不出来，值就是
	 * {@link radiosmile.net.AudioStatus} 的状态码。
	 *
	 * <p><b>不入 NBT、不参与同步</b>：它是每个客户端各自观察到的结果
	 * （A 走远了听不到、B 还听得到），不是方块的客观属性；
	 * 由客户端收到 {@code S2C_AUDIO_STATUS} 后自行写入，
	 * 供 {@code RadioBlockEntityRenderer} 决定红灯与液晶屏。
	 */
	private int clientFaultStatus;

	public int getClientFaultStatus() {
		return this.clientFaultStatus;
	}

	/** 由客户端调用：记录/清除故障显示状态。 */
	public void setClientFaultStatus(int status) {
		if (this.clientFaultStatus == status) {
			return;
		}
		this.clientFaultStatus = status;
		// 只是视觉状态，不需要 setChanged（不写盘）也不需要 sync（不跨端同步）
	}

	public RadioBlockEntity(BlockPos pos, BlockState state) {
		this(ModBlockEntities.RADIO, pos, state);
	}

	public RadioBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
		super(type, pos, state);
	}

	public String getStation() {
		return this.station;
	}

	public void setStation(String station) {
		this.station = station == null ? "" : station;
		this.setChanged();
		this.sync();
	}

	public float getVolume() {
		return this.volume;
	}

	public void setVolume(float volume) {
		this.volume = Math.max(0.0F, Math.min(1.0F, volume));
		this.setChanged();
		this.sync();
	}

	private void sync() {
		if (this.level != null && !this.level.isClientSide) {
			this.level.sendBlockUpdated(this.worldPosition, this.getBlockState(), this.getBlockState(), 3);
		}
	}

	@Override
	protected void saveAdditional(CompoundTag tag) {
		super.saveAdditional(tag);
		tag.putString(KEY_STATION, this.station);
		tag.putFloat(KEY_VOLUME, this.volume);
	}

	@Override
	public void load(CompoundTag tag) {
		super.load(tag);
		this.station = tag.getString(KEY_STATION);
		this.volume = tag.contains(KEY_VOLUME) ? tag.getFloat(KEY_VOLUME) : 1.0F;
	}

	@Override
	public CompoundTag getUpdateTag() {
		CompoundTag tag = new CompoundTag();
		this.saveAdditional(tag);
		return tag;
	}

	@Override
	public ClientboundBlockEntityDataPacket getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}
}
