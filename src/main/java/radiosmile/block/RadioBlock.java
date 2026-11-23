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
	 * 右键交互：<b>任何物品</b>下都会打开收音机界面，且<b>绝不触发放置</b>。
	 *
	 * <h2>实际生效的是 {@code UseBlockCallback}，不是这个方法</h2>
	 * 真正的拦截在 {@code RadioServerNetwork} / {@code RadioClientHooks} 注册的
	 * {@code UseBlockCallback} 里（详见 {@link #shouldOpenScreenFromUseBlockEvent}）。
	 *
	 * <p>原因是原版 {@code ServerPlayerGameMode#useItemOn} 的分支顺序：
	 * <pre>
	 *   if (sneaking &amp;&amp; hasItem)  -> <b>完全跳过 block.use()</b>，直接走 item.useOn()（= 放置方块）
	 *   else block.use(); if (result.consumesAction()) return;   // 这里返回 SUCCESS 才拦住 useOn
	 *   item.useOn();
	 * </pre>
	 * 也就是说 <b>潜行 + 手持物品</b> 时这个方法根本不会被调用——
	 * 而"潜行着放方块"恰恰是最常见的放置姿势。若只靠本方法，
	 * 潜行右键会直接放方块且不开界面，违反需求。
	 *
	 * <p>{@code UseBlockCallback} 注入在 {@code MultiPlayerGameMode#interactBlock} 的
	 * <b>最前面</b>（早于潜行判定与物品逻辑），返回 SUCCESS 即让原版整个分支直接返回，
	 * 因此同时满足"任何物品都能开界面"与"绝不放置"。
	 *
	 * <h2>那这个方法还留着做什么</h2>
	 * 兜底。{@code UseBlockCallback} 是客户端侧事件，专用服务器/命令/其它模组
	 * 直接调用 {@code BlockState#use} 时仍会走到这里；两端都返回 SUCCESS
	 * 可以保证"不会退回放置"这一不变式在任何入口下都成立。
	 *
	 * <p><b>GUI 只在客户端打开</b>：若在服务端调用任何界面代码，
	 * 专用服务器会直接崩溃（客户端类在服务端不存在）。
	 */
	@SuppressWarnings("deprecation")
	@Override
	public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
			InteractionHand hand, BlockHitResult hit) {
		// 服务端：<b>吃掉交互</b>——阻止原版把"手持方块右键"变成"放置方块"。
		if (!level.isClientSide) {
			return InteractionResult.SUCCESS;
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

	/**
	 * {@code UseBlockCallback} 的统一判定：命中的是否是需要打开界面的收音机方块。
	 *
	 * <p>抽成静态方法，让客户端与服务端注册同一个判据，
	 * 避免两处各写一份 {@code getBlock() == ...} 而慢慢分叉。
	 *
	 * @param level 发生交互的维度（可能为空，事件回调里需防御）
	 * @param pos   被点击的方块坐标
	 * @return 是否应当由本模组接管这次右键
	 */
	public static boolean shouldOpenScreenFromUseBlockEvent(
			net.minecraft.world.level.Level level, BlockPos pos) {
		if (level == null || pos == null) {
			return false;
		}
		// 用方块实例比较：本模组的收音机只有一个方块实例，最直接也最快。
		// 不用注册名比较——那会漏掉未来的变种方块（如染色收音机）。
		return level.getBlockState(pos).getBlock() instanceof RadioBlock;
	}
}
