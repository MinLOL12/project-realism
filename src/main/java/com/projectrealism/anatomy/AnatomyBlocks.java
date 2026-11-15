package com.projectrealism.anatomy;

import com.projectrealism.ProjectRealism;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;

/**
 * Registers the persistent in-world anatomical carcass block and syncing BlockEntity type.
 */
public final class AnatomyBlocks {
	public static final Block CARCASS = Registry.register(
			Registries.BLOCK,
			ProjectRealism.id("carcass"),
			new CarcassBlock(AbstractBlock.Settings.create()
					.mapColor(MapColor.TERRACOTTA_BROWN)
					.strength(1.2f)
					.sounds(BlockSoundGroup.WOOL)
					.nonOpaque()
					.noCollision()));

	public static final BlockEntityType<CarcassBlockEntity> CARCASS_ENTITY = Registry.register(
			Registries.BLOCK_ENTITY_TYPE,
			ProjectRealism.id("carcass"),
			BlockEntityType.Builder.create(CarcassBlockEntity::new, CARCASS).build(null));

	private AnatomyBlocks() {
	}

	public static void register() {
		ProjectRealism.LOGGER.info("Registered persistent anatomical carcass block & entity.");
	}
}
