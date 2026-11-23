package radiosmile.registry;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;

import radiosmile.SmileSRadio;

/**
 * 模组物品注册表。
 *
 * <p>必须在 {@link ModBlocks#register()} 之后调用，因为方块物品引用了方块实例。
 */
public final class ModItems {
	public static final Item RADIO = register("radio", new BlockItem(ModBlocks.RADIO, new Item.Properties()));

	private ModItems() {
	}

	/** 触发本类的静态初始化，从而完成上方所有物品的注册。 */
	public static void register() {
	}

	private static Item register(String name, Item item) {
		return Registry.register(BuiltInRegistries.ITEM, SmileSRadio.id(name), item);
	}
}
