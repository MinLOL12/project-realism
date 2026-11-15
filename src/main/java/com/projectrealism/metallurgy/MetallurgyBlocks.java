package com.projectrealism.metallurgy;

import com.projectrealism.ProjectRealism;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;

/**
 * Registers all geological mineral deposits, refractory clay blocks, smelting furnace basins,
 * crucibles, carvable casting molds, and anvil-placed dynamic metal blocks.
 */
public final class MetallurgyBlocks {
	// --- Geological Ore & Clay Deposits ---
	public static final Block NATIVE_COPPER_DEPOSIT = register("native_copper_deposit",
			new MineralDepositBlock(AbstractBlock.Settings.create().mapColor(MapColor.TERRACOTTA_ORANGE)
					.strength(2.4f, 4.0f).sounds(BlockSoundGroup.STONE).requiresTool(),
					MineralDepositBlock.DepositKind.NATIVE_COPPER));
	public static final Block MALACHITE_AZURITE_DEPOSIT = register("malachite_azurite_deposit",
			new MineralDepositBlock(AbstractBlock.Settings.create().mapColor(MapColor.EMERALD_GREEN)
					.strength(2.8f, 4.0f).sounds(BlockSoundGroup.STONE).requiresTool(),
					MineralDepositBlock.DepositKind.MALACHITE_AZURITE));
	public static final Block CHALCOPYRITE_DEPOSIT = register("chalcopyrite_deposit",
			new MineralDepositBlock(AbstractBlock.Settings.create().mapColor(MapColor.GOLD)
					.strength(3.4f, 5.0f).sounds(BlockSoundGroup.DEEPSLATE).requiresTool(),
					MineralDepositBlock.DepositKind.CHALCOPYRITE));
	public static final Block CASSITERITE_DEPOSIT = register("cassiterite_deposit",
			new MineralDepositBlock(AbstractBlock.Settings.create().mapColor(MapColor.TERRACOTTA_BROWN)
					.strength(2.9f, 4.5f).sounds(BlockSoundGroup.STONE).requiresTool(),
					MineralDepositBlock.DepositKind.CASSITERITE));
	public static final Block ARSENOPYRITE_DEPOSIT = register("arsenopyrite_deposit",
			new MineralDepositBlock(AbstractBlock.Settings.create().mapColor(MapColor.LIGHT_GRAY)
					.strength(3.4f, 5.0f).sounds(BlockSoundGroup.DEEPSLATE).requiresTool(),
					MineralDepositBlock.DepositKind.ARSENOPYRITE));
	public static final Block HEMATITE_MAGNETITE_DEPOSIT = register("hematite_magnetite_deposit",
			new MineralDepositBlock(AbstractBlock.Settings.create().mapColor(MapColor.TERRACOTTA_RED)
					.strength(3.8f, 5.5f).sounds(BlockSoundGroup.DEEPSLATE).requiresTool(),
					MineralDepositBlock.DepositKind.HEMATITE_MAGNETITE));
	public static final Block LIMONITE_BOG_DEPOSIT = register("limonite_bog_deposit",
			new MineralDepositBlock(AbstractBlock.Settings.create().mapColor(MapColor.DIRT_BROWN)
					.strength(1.8f, 2.5f).sounds(BlockSoundGroup.GRAVEL),
					MineralDepositBlock.DepositKind.LIMONITE_BOG));
	public static final Block FIRE_CLAY_DEPOSIT = register("fire_clay_deposit",
			new MineralDepositBlock(AbstractBlock.Settings.create().mapColor(MapColor.PALE_YELLOW)
					.strength(0.9f, 1.5f).sounds(BlockSoundGroup.GRAVEL),
					MineralDepositBlock.DepositKind.FIRE_CLAY));

	// --- Refractory & Construction Furnace Walls ---
	public static final Block RAW_CLAY_BLOCK = register("raw_clay_wall",
			new Block(AbstractBlock.Settings.create().mapColor(MapColor.TERRACOTTA_LIGHT_GRAY)
					.strength(0.8f, 1.2f).sounds(BlockSoundGroup.GRAVEL)));
	public static final Block FIRED_CLAY_WALL = register("fired_clay_wall",
			new Block(AbstractBlock.Settings.create().mapColor(MapColor.TERRACOTTA_RED)
					.strength(2.4f, 5.5f).sounds(BlockSoundGroup.STONE).requiresTool()));
	public static final Block FIRE_CLAY_BRICKS = register("fire_clay_bricks",
			new Block(AbstractBlock.Settings.create().mapColor(MapColor.PALE_YELLOW)
					.strength(3.2f, 8.0f).sounds(BlockSoundGroup.STONE).requiresTool()));

	// --- Diegetic Metallurgy Workstations ---
	public static final Block SMELTING_BASIN = register("smelting_basin",
			new SmeltingBasinBlock(AbstractBlock.Settings.create().mapColor(MapColor.DEEPSLATE_GRAY)
					.strength(2.5f, 6.0f).sounds(BlockSoundGroup.STONE).nonOpaque()
					.luminance(s -> s.get(SmeltingBasinBlock.LIT) ? 13 : 0)));
	public static final Block CLAY_CRUCIBLE = register("clay_crucible",
			new ClayCrucibleBlock(AbstractBlock.Settings.create().mapColor(MapColor.TERRACOTTA_BROWN)
					.strength(1.0f, 2.5f).sounds(BlockSoundGroup.STONE).nonOpaque()));
	public static final Block CARVABLE_MOLD = register("carvable_mold",
			new CarvableMoldBlock(AbstractBlock.Settings.create().mapColor(MapColor.TERRACOTTA_LIGHT_GRAY)
					.strength(1.1f, 2.5f).sounds(BlockSoundGroup.STONE).nonOpaque()));
	public static final Block PLACED_METAL = register("placed_metal",
			new PlacedMetalBlock(AbstractBlock.Settings.create().mapColor(MapColor.IRON_GRAY)
					.strength(1.6f, 4.0f).sounds(BlockSoundGroup.METAL).nonOpaque()));

	// --- Block Entities ---
	public static final BlockEntityType<SmeltingBasinBlockEntity> SMELTING_BASIN_ENTITY =
			Registry.register(Registries.BLOCK_ENTITY_TYPE, ProjectRealism.id("smelting_basin"),
					BlockEntityType.Builder.create(SmeltingBasinBlockEntity::new, SMELTING_BASIN).build(null));
	public static final BlockEntityType<ClayCrucibleBlockEntity> CLAY_CRUCIBLE_ENTITY =
			Registry.register(Registries.BLOCK_ENTITY_TYPE, ProjectRealism.id("clay_crucible"),
					BlockEntityType.Builder.create(ClayCrucibleBlockEntity::new, CLAY_CRUCIBLE).build(null));
	public static final BlockEntityType<CarvableMoldBlockEntity> CARVABLE_MOLD_ENTITY =
			Registry.register(Registries.BLOCK_ENTITY_TYPE, ProjectRealism.id("carvable_mold"),
					BlockEntityType.Builder.create(CarvableMoldBlockEntity::new, CARVABLE_MOLD).build(null));
	public static final BlockEntityType<PlacedMetalBlockEntity> PLACED_METAL_ENTITY =
			Registry.register(Registries.BLOCK_ENTITY_TYPE, ProjectRealism.id("placed_metal"),
					BlockEntityType.Builder.create(PlacedMetalBlockEntity::new, PLACED_METAL).build(null));

	private MetallurgyBlocks() {
	}

	private static Block register(String path, Block block) {
		return Registry.register(Registries.BLOCK, ProjectRealism.id(path), block);
	}

	public static void register() {
		ProjectRealism.LOGGER.info("Metallurgy deposits, refractory walls, furnaces, crucibles & molds registered.");
	}
}
