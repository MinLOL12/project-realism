package com.projectrealism.thermo;

import com.projectrealism.ProjectRealism;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;

/**
 * Registers physical thermodynamic hearth and ash residue blocks and block entities.
 */
public final class ThermoBlocks {
	public static final Block HEARTH = Registry.register(
			Registries.BLOCK,
			ProjectRealism.id("hearth"),
			new HearthBlock(AbstractBlock.Settings.create()
					.mapColor(MapColor.STONE_GRAY)
					.strength(1.6f)
					.sounds(BlockSoundGroup.STONE)
					.nonOpaque()
					.luminance(state -> state.get(HearthBlock.LIT) ? 14 : (state.get(HearthBlock.HAS_EMBERS) ? 6 : 0))));

	public static final Block WOOD_ASH_PILE = Registry.register(
			Registries.BLOCK,
			ProjectRealism.id("wood_ash_pile"),
			new AshPileBlock(AbstractBlock.Settings.create()
					.mapColor(MapColor.LIGHT_GRAY)
					.strength(0.25f)
					.sounds(BlockSoundGroup.SAND)
					.nonOpaque()));

	public static final BlockEntityType<HearthBlockEntity> HEARTH_ENTITY = Registry.register(
			Registries.BLOCK_ENTITY_TYPE,
			ProjectRealism.id("hearth"),
			BlockEntityType.Builder.create(HearthBlockEntity::new, HEARTH).build(null));

	private ThermoBlocks() {
	}

	public static void register() {
		ProjectRealism.LOGGER.info("Thermodynamic hearth & ash blocks registered.");
	}
}
