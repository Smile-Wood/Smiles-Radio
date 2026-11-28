package radiosmile.registry;

import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import radiosmile.SmileSRadio;

/**
 * 模组的自定义创造模式物品栏标签页。
 */
public final class ModItemGroups {
	public static final CreativeModeTab RADIO_GROUP = Registry.register(
			BuiltInRegistries.CREATIVE_MODE_TAB,
			SmileSRadio.id("radio"),
			FabricItemGroup.builder()
					.title(Component.translatable("itemGroup.radio-smile.radio"))
					.icon(() -> new ItemStack(ModItems.RADIO))
					.displayItems((parameters, entries) -> entries.accept(ModItems.RADIO))
					.build());

	private ModItemGroups() {
	}

	/** 触发本类的静态初始化，从而注册标签页。 */
	public static void register() {
	}
}
