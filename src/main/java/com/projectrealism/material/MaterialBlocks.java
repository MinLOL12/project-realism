package com.projectrealism.material;

import com.projectrealism.ProjectRealism;
import net.minecraft.block.AbstractBlock;
import net.minecraft.block.Block;
import net.minecraft.block.MapColor;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.BlockSoundGroup;

/**
 * Registers all six diegetic material-processing workstations and their BlockEntity types.
 */
public final class MaterialBlocks {
	public static final Block SCRAPING_FRAME = registerBlock("scraping_frame",
			new ScrapingFrameBlock(AbstractBlock.Settings.create()
					.mapColor(MapColor.OAK_TAN).strength(1.8f).sounds(BlockSoundGroup.WOOD).nonOpaque()));
	public static final Block DRYING_RACK = registerBlock("drying_rack",
			new DryingRackBlock(AbstractBlock.Settings.create()
					.mapColor(MapColor.OAK_TAN).strength(1.6f).sounds(BlockSoundGroup.WOOD).nonOpaque()));
	public static final Block CUTTING_SURFACE = registerBlock("cutting_surface",
			new CuttingSurfaceBlock(AbstractBlock.Settings.create()
					.mapColor(MapColor.OAK_TAN).strength(2.0f).sounds(BlockSoundGroup.WOOD).nonOpaque()));
	public static final Block SEWING_FRAME = registerBlock("sewing_frame",
			new SewingFrameBlock(AbstractBlock.Settings.create()
					.mapColor(MapColor.OAK_TAN).strength(1.8f).sounds(BlockSoundGroup.WOOD).nonOpaque()));
	public static final Block WOODEN_WORKBENCH = registerBlock("wooden_workbench",
			new WorkbenchBlock(AbstractBlock.Settings.create()
					.mapColor(MapColor.OAK_TAN).strength(2.2f).sounds(BlockSoundGroup.WOOD).nonOpaque()));
	public static final Block STORAGE_RACK = registerBlock("storage_rack",
			new StorageRackBlock(AbstractBlock.Settings.create()
					.mapColor(MapColor.OAK_TAN).strength(1.8f).sounds(BlockSoundGroup.WOOD).nonOpaque()));

	public static final BlockEntityType<ScrapingFrameBlockEntity> SCRAPING_FRAME_ENTITY =
			Registry.register(Registries.BLOCK_ENTITY_TYPE, ProjectRealism.id("scraping_frame"),
					BlockEntityType.Builder.create(ScrapingFrameBlockEntity::new, SCRAPING_FRAME).build(null));
	public static final BlockEntityType<DryingRackBlockEntity> DRYING_RACK_ENTITY =
			Registry.register(Registries.BLOCK_ENTITY_TYPE, ProjectRealism.id("drying_rack"),
					BlockEntityType.Builder.create(DryingRackBlockEntity::new, DRYING_RACK).build(null));
	public static final BlockEntityType<CuttingSurfaceBlockEntity> CUTTING_SURFACE_ENTITY =
			Registry.register(Registries.BLOCK_ENTITY_TYPE, ProjectRealism.id("cutting_surface"),
					BlockEntityType.Builder.create(CuttingSurfaceBlockEntity::new, CUTTING_SURFACE).build(null));
	public static final BlockEntityType<SewingFrameBlockEntity> SEWING_FRAME_ENTITY =
			Registry.register(Registries.BLOCK_ENTITY_TYPE, ProjectRealism.id("sewing_frame"),
					BlockEntityType.Builder.create(SewingFrameBlockEntity::new, SEWING_FRAME).build(null));
	public static final BlockEntityType<WorkbenchBlockEntity> WOODEN_WORKBENCH_ENTITY =
			Registry.register(Registries.BLOCK_ENTITY_TYPE, ProjectRealism.id("wooden_workbench"),
					BlockEntityType.Builder.create(WorkbenchBlockEntity::new, WOODEN_WORKBENCH).build(null));
	public static final BlockEntityType<StorageRackBlockEntity> STORAGE_RACK_ENTITY =
			Registry.register(Registries.BLOCK_ENTITY_TYPE, ProjectRealism.id("storage_rack"),
					BlockEntityType.Builder.create(StorageRackBlockEntity::new, STORAGE_RACK).build(null));

	private MaterialBlocks() {
	}

	private static Block registerBlock(String path, Block block) {
		return Registry.register(Registries.BLOCK, ProjectRealism.id(path), block);
	}

	public static void register() {
		ProjectRealism.LOGGER.info("Registered 6 diegetic material workstations.");
	}
}
