package com.projectrealism.primitive;

import com.projectrealism.ProjectRealism;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.MapColor;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.util.Identifier;

/**
 * The ground-litter blocks. Three stone types, one pebble size each, all of them non-colliding so you
 * walk over them rather than into them.
 */
public final class PrimitiveBlocks {
	private static final AbstractBlock.Settings ROCK_SETTINGS = AbstractBlock.Settings.create()
			.mapColor(MapColor.STONE_GRAY)
			.strength(0.4f, 1.0f)
			.sounds(BlockSoundGroup.STONE)
			.nonOpaque()
			.dropsNothing();

	public static final Block GRANITE_ROCK = register("granite_rock",
			new GroundRockBlock(ROCK_SETTINGS, PrimitiveItems.GRANITE_ROCK, 1));
	public static final Block LIMESTONE_ROCK = register("limestone_rock",
			new GroundRockBlock(ROCK_SETTINGS.mapColor(MapColor.TERRACOTTA_WHITE),
					PrimitiveItems.LIMESTONE_ROCK, 1));
	public static final Block FLINT_ROCK = register("flint_rock",
			new GroundRockBlock(ROCK_SETTINGS.mapColor(MapColor.DEEPSLATE_GRAY), PrimitiveItems.FLINT_ROCK, 1));

	/** A stick lying on the ground. Pops into an item entity when a player walks over it. */
	public static final Block FALLEN_STICK = register("fallen_stick",
			new RockDropBlock(AbstractBlock.Settings.create()
					.mapColor(MapColor.OAK_TAN)
					.strength(0.2f, 0.2f)
					.sounds(BlockSoundGroup.WOOD)
					.nonOpaque()
					.dropsNothing(),
					new net.minecraft.item.ItemStack(net.minecraft.item.Items.STICK, 1)));

	private PrimitiveBlocks() {
	}

	private static Block register(String path, Block block) {
		Identifier id = ProjectRealism.id(path);
		return Registry.register(Registries.BLOCK, id, block);
	}

	public static void register() {
		ProjectRealism.LOGGER.debug("Primitive blocks registered.");
	}

	/** True for the handful of blocks rocks are allowed to sit on. */
	public static boolean isRockSurface(Block block) {
		return block == Blocks.GRASS_BLOCK
				|| block == Blocks.DIRT
				|| block == Blocks.COARSE_DIRT
				|| block == Blocks.ROOTED_DIRT
				|| block == Blocks.PODZOL
				|| block == Blocks.GRAVEL
				|| block == Blocks.STONE
				|| block == Blocks.ANDESITE
				|| block == Blocks.DIORITE
				|| block == Blocks.GRANITE
				|| block == Blocks.TUFF
				|| block == Blocks.DEEPSLATE
				|| block == Blocks.CALCITE
				|| block == Blocks.SAND
				|| block == Blocks.RED_SAND
				|| block == Blocks.MUD
				|| block == Blocks.CLAY
				|| block == Blocks.MOSS_BLOCK;
	}
}
