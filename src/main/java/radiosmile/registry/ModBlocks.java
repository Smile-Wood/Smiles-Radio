package radiosmile.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;

import radiosmile.SmileSRadio;
import radiosmile.block.RadioBlock;

/**
 * 模组方块注册表。
 *
 * <p>静态字段在类被加载时完成注册，{@link #register()} 只用于保证类加载。
 */
public final class ModBlocks {
	public static final Block RADIO = register("radio", new RadioBlock(BlockBehaviour.Properties.of()
			.mapColor(MapColor.METAL)
			.strength(2.0F, 6.0F)
			.sound(SoundType.STONE)
			// 参考火把：不遮挡光照（不压暗草方块）且无碰撞箱（不产生方块阴影）。
			.noOcclusion()
			.noCollission()
			// 活塞推动时直接破坏，与火把一致。
			.pushReaction(PushReaction.DESTROY)));

	private ModBlocks() {
	}

	/** 触发本类的静态初始化，从而完成上方所有方块的注册。 */
	public static void register() {
	}

	private static Block register(String name, Block block) {
		return Registry.register(BuiltInRegistries.BLOCK, SmileSRadio.id(name), block);
	}
}
