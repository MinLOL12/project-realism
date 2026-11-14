package com.projectrealism.wood;

import com.projectrealism.ProjectRealism;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;

import java.util.EnumMap;
import java.util.Map;

/**
 * Registers all wood-processing blocks for Oak, Birch, Spruce, and Dark Oak.
 */
public final class WoodBlocks {
	private static final Map<WoodSpecies, Block> NOTCHED_LOGS = new EnumMap<>(WoodSpecies.class);
	private static final Map<WoodSpecies, Block> FALLEN_TRUNKS = new EnumMap<>(WoodSpecies.class);
	private static final Map<WoodSpecies, Block> FRESH_LOGS = new EnumMap<>(WoodSpecies.class);
	private static final Map<WoodSpecies, Block> SPLIT_WOOD = new EnumMap<>(WoodSpecies.class);
	private static final Map<WoodSpecies, Block> ROUGH_PLANKS = new EnumMap<>(WoodSpecies.class);

	static {
		for (WoodSpecies sp : WoodSpecies.values()) {
			NOTCHED_LOGS.put(sp, register("notched_" + sp.id() + "_log",
					new NotchedLogBlock(AbstractBlock.Settings.create()
							.mapColor(MapColor.OAK_TAN)
							.strength(2.0f * sp.hardness())
							.sounds(BlockSoundGroup.WOOD)
							.nonOpaque()
							.dropsNothing(), sp)));

			FALLEN_TRUNKS.put(sp, register(sp.id() + "_trunk",
					new FallenTrunkBlock(AbstractBlock.Settings.create()
							.mapColor(MapColor.OAK_TAN)
							.strength(1.8f * sp.hardness())
							.sounds(BlockSoundGroup.WOOD)
							.nonOpaque()
							.dropsNothing(), sp)));

			FRESH_LOGS.put(sp, register("fresh_" + sp.id() + "_log",
					new FreshLogBlock(AbstractBlock.Settings.create()
							.mapColor(MapColor.OAK_TAN)
							.strength(1.8f * sp.hardness())
							.sounds(BlockSoundGroup.WOOD), sp)));

			SPLIT_WOOD.put(sp, register(sp.id() + "_split_wood",
					new SplitWoodBlock(AbstractBlock.Settings.create()
							.mapColor(MapColor.OAK_TAN)
							.strength(1.0f * sp.hardness())
							.sounds(BlockSoundGroup.WOOD)
							.nonOpaque()
							.dropsNothing(), sp)));

			ROUGH_PLANKS.put(sp, register("rough_" + sp.id() + "_plank",
					new RoughPlankBlock(AbstractBlock.Settings.create()
							.mapColor(MapColor.OAK_TAN)
							.strength(1.0f * sp.hardness())
							.sounds(BlockSoundGroup.WOOD)
							.nonOpaque()
							.dropsNothing(), sp)));
		}
	}

	private WoodBlocks() {
	}

	private static Block register(String path, Block block) {
		return Registry.register(Registries.BLOCK, ProjectRealism.id(path), block);
	}

	public static Block notchedLog(WoodSpecies sp) {
		return NOTCHED_LOGS.get(sp);
	}

	public static Block fallenTrunk(WoodSpecies sp) {
		return FALLEN_TRUNKS.get(sp);
	}

	public static Block freshLog(WoodSpecies sp) {
		return FRESH_LOGS.get(sp);
	}

	public static Block splitWood(WoodSpecies sp) {
		return SPLIT_WOOD.get(sp);
	}

	public static Block roughPlank(WoodSpecies sp) {
		return ROUGH_PLANKS.get(sp);
	}

	public static void register() {
		ProjectRealism.LOGGER.info("Wood processing blocks registered.");
	}
}
