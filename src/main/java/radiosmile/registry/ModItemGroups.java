package radiosmile.registry;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.world.item.CreativeModeTabs;

/**
 * 把模组内容加入原版创造模式物品栏标签页。
 */
public final class ModItemGroups {
	private ModItemGroups() {
	}

	public static void register() {
		// 功能方块
		ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS)
				.register(entries -> entries.accept(ModItems.RADIO));
	}
}
