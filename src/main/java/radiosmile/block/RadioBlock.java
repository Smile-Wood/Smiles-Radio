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

	/**
	 * 该物品是否允许触发收音机界面。
	 *
	 * <h2>允许的两种</h2>
	 * <ol>
	 *   <li><b>空手</b>：最常见的"我要调台"手势，必须永远有效。</li>
	 *   <li><b>拿着收音机物品</b>：视为"我要操作这台机器"。
	 *       注意这会<b>阻止</b>把收音机叠放到已有的收音机上（服务端返回 SUCCESS 吃掉交互），
	 *       这是有意的取舍：拿着同款物品对着收音机连点的场景里，
	 *       想调台的远比想叠放的多，而且叠放在无碰撞箱的方块上本来就少见。</li>
	 * </ol>
	 *
	 * <p>其它任何物品（方块、工具、食物、桶……）一律<b>不</b>触发界面，
	 * 让交互"穿透"回原版行为。
	 *
	 * @param held 该手所持物品堆（空手时是 {@link net.minecraft.world.item.ItemStack#EMPTY}）
	 */
	private static boolean isInteractionItem(net.minecraft.world.item.ItemStack held) {
		if (held == null || held.isEmpty()) {
			// 空手：始终允许
			return true;
		}
		return held.is(radiosmile.registry.ModItems.RADIO);
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
	 * <h2>只有<b>空手</b>或<b>拿着收音机</b>时才打开界面</h2>
	 * 用户要求：手持其它物品右键时<b>不应触发</b>本方块的交互。
	 *
	 * <p>为什么这个细节重要：收音机是<b>无碰撞箱</b>、只有一个薄片造型的方块
	 * （与火把同类）。玩家常常在它周围不小心右键——比如拿着方块想放在旁边、
	 * 拿着镐子想挖别的东西——若每次都被强行弹出选台界面，会非常打断操作，
	 * 而且界面会盖住画面、必须手动关掉。让其它物品"穿透"过去（返回
	 * {@link InteractionResult#PASS}）才是正确的交互手感。
	 *
	 * <h2>为什么用 PASS 而不是 FAIL</h2>
	 * {@code PASS} 表示"本方块不处理这次交互，交回给物品/后续逻辑"，
	 * 于是手持方块时会正常放置、手持食物时会正常吃、手持工具时正常作用于别的目标。
	 * 若返回 {@code FAIL} 会把整个交互吞掉，反而更糟。
	 *
	 * <h2>为什么服务端也要一起判定</h2>
	 * {@code use} 两端都会跑。若只在客户端判空手、服务端仍返回 SUCCESS，
	 * 会出现"客户端以为没交互、服务端以为交互了"的幽灵摆动（手臂动画与音效错位）。
	 * 因此这里把判定放在 {@code level.isClientSide} 分支<b>之前</b>，两端一致。
	 *
	 * <p><b>GUI 只在客户端打开</b>：{@link #use} 会在服务端与客户端分别执行，
	 * 若在服务端调用任何界面代码，专用服务器会直接崩溃（客户端类在服务端不存在）。
	 */
	@SuppressWarnings("deprecation")
	@Override
	public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
			InteractionHand hand, BlockHitResult hit) {
		// <b>先判定"手上拿的是什么"——这段两端都必须执行</b>（见方法注释）
		if (!isInteractionItem(player.getItemInHand(hand))) {
			// 手持其它物品（方块、工具、食物……）：
			// <b>交回给原版</b>，于是拿方块右键能正常放置、拿食物能吃、拿工具正常作用。
			// 这正是"不要拦截放置方块"的实现方式。
			return InteractionResult.PASS;
		}

		// 服务端：<b>必须返回 SUCCESS（而不是 PASS）来"吃掉"这次交互</b>。
		//
		// 若这里返回 PASS，服务端会继续走 {@code ItemStack#useOn}——
		// 玩家手里拿着收音机物品、右键收音机侧面时，服务端就会真的去<b>放置一个收音机</b>，
		// 而客户端此时已经弹出了选台界面。表现是"打开界面后旁边凭空多了个方块"，
		// 且只在服务端发生、客户端不同步，极难排查。
		// 返回 SUCCESS 表示"已处理，别再往下传"，界面本身仍只在客户端打开。
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
}
