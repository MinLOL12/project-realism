package com.projectrealism.knapping;

import com.projectrealism.ProjectRealism;
import com.projectrealism.primitive.GroundRockBlock;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;

/**
 * Registers the persistent in-world knapping rock block/entity plus geological ground pickups
 * for chert, quartzite, and basalt.
 */
public final class DynamicKnappingBlocks {
	private static final AbstractBlock.Settings GROUND_SETTINGS = AbstractBlock.Settings.create()
			.mapColor(MapColor.STONE_GRAY)
			.strength(0.4f, 1.0f)
			.sounds(BlockSoundGroup.STONE)
			.noCollision()
			.nonOpaque()
			.dropsNothing();

	public static final Block CHERT_ROCK = register("chert_rock",
			new GroundRockBlock(GROUND_SETTINGS.mapColor(MapColor.OAK_TAN), DynamicKnappingItems.CHERT_ROCK, 1));
	public static final Block QUARTZITE_ROCK = register("quartzite_rock",
			new GroundRockBlock(GROUND_SETTINGS.mapColor(MapColor.WHITE), DynamicKnappingItems.QUARTZITE_ROCK, 1));
	public static final Block BASALT_ROCK = register("basalt_rock",
			new GroundRockBlock(GROUND_SETTINGS.mapColor(MapColor.BLACK), DynamicKnappingItems.BASALT_ROCK, 1));

	public static final Block KNAPPING_STONE = register("knapping_stone",
			new KnappingStoneBlock(AbstractBlock.Settings.create()
					.mapColor(MapColor.STONE_GRAY)
					.strength(1.2f, 2.0f)
					.sounds(BlockSoundGroup.STONE)
					.nonOpaque()));

	public static final BlockEntityType<KnappingStoneBlockEntity> KNAPPING_STONE_ENTITY = Registry.register(
			Registries.BLOCK_ENTITY_TYPE,
			ProjectRealism.id("knapping_stone"),
			BlockEntityType.Builder.create(KnappingStoneBlockEntity::new, KNAPPING_STONE).build(null)
	);

	private DynamicKnappingBlocks() {
	}

	private static Block register(String path, Block block) {
		return Registry.register(Registries.BLOCK, ProjectRealism.id(path), block);
	}

	public static void register() {
		ProjectRealism.LOGGER.info("Dynamic stone knapping blocks registered.");
	}
}
