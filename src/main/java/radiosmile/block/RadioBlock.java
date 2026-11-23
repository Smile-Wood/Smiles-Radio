package radiosmile.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import radiosmile.RadioClientBridge;
import radiosmile.block.entity.RadioBlockEntity;

/**
 * 收音机方块（Radio）。
 *
 * <p>支撑特性参考原版火把：
 * <ul>
 *   <li>无碰撞箱（{@code noCollission()}）：实体可直接穿过，也不会产生方块阴影。</li>
 *   <li>不遮挡光照（{@code noOcclusion()}）：不影响下方草方块的光照。</li>
 *   <li>需要下方为完整实心面才能放置；支撑消失时自身掉落成物品。</li>
 *   <li>活塞推动时直接破坏（{@link PushReaction#DESTROY}）。</li>
 * </ul>
 *
 * <p>带有水平朝向状态 {@code facing}：放置时朝向玩家，模型按朝向旋转。
 *
 * <p>右键打开收音机界面；频道与音量保存在 {@link RadioBlockEntity} 的 NBT 中。
 */
public class RadioBlock extends BaseEntityBlock {
	public static final net.minecraft.world.level.block.state.properties.DirectionProperty FACING =
			HorizontalDirectionalBlock.FACING;

	public RadioBlock(Properties properties) {
		super(properties);
		registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH));
	}

	@Override
	protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	public BlockState getStateForPlacement(BlockPlaceContext context) {
		return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
	}

	/** 方块实体模型必须显式声明为 MODEL，否则 BaseEntityBlock 默认是 INVISIBLE。 */
	@Override
	public RenderShape getRenderShape(BlockState state) {
		return RenderShape.MODEL;
	}

	@Override
	public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
		return new RadioBlockEntity(pos, state);
	}

	/** 只有下方是完整实心面才算有支撑，与火把一致。 */
	public static boolean hasSupport(LevelReader level, BlockPos pos) {
		return Block.canSupportCenter(level, pos.below(), Direction.UP);
	}

	@SuppressWarnings("deprecation")
	@Override
	public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
		return hasSupport(level, pos);
	}

	@SuppressWarnings("deprecation")
	@Override
	public BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
			LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
		return direction == Direction.DOWN && !canSurvive(state, level, pos)
				? Blocks.AIR.defaultBlockState()
				: super.updateShape(state, direction, neighborState, level, pos, neighborPos);
	}

	/** 无碰撞箱：实体可直接穿过，也不会产生方块阴影。 */
	@SuppressWarnings("deprecation")
	@Override
	public VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
		return Shapes.empty();
	}

	/**
	 * 右键交互。
	 *
	 * <p><b>GUI 只在客户端打开</b>：{@link #use} 会在服务端与客户端分别执行，
	 * 若在服务端调用任何界面代码，专用服务器会直接崩溃（客户端类在服务端不存在）。
	 * 因此这里用 {@code level.isClientSide()} 严格隔离；服务端只返回 {@link InteractionResult#PASS}，
	 * 不参与任何界面逻辑。
	 */
	@SuppressWarnings("deprecation")
	@Override
	public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
			InteractionHand hand, BlockHitResult hit) {
		// 服务端绝不做界面相关处理
		if (!level.isClientSide) {
			return InteractionResult.PASS;
		}

		RadioClientBridge.ScreenOpener opener = RadioClientBridge.openScreen;
		if (opener != null) {
			// 把方块 NBT 里保存的频道传给界面，进入时默认高亮
			String current = "";
			if (level.getBlockEntity(pos) instanceof RadioBlockEntity be) {
				current = be.getStation();
			}
			opener.open(current, pos);
		}
		return InteractionResult.SUCCESS;
	}
}
