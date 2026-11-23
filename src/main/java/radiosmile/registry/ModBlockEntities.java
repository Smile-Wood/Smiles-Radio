package radiosmile.registry;

import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.entity.BlockEntityType;

import radiosmile.SmileSRadio;
import radiosmile.block.entity.RadioBlockEntity;

public final class ModBlockEntities {
	public static BlockEntityType<RadioBlockEntity> RADIO;

	private ModBlockEntities() {
	}

	/** 必须在方块已注册后调用。 */
	public static void register() {
		RADIO = Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, SmileSRadio.id("radio"),
				FabricBlockEntityTypeBuilder.create(RadioBlockEntity::new, ModBlocks.RADIO).build());
	}
}
