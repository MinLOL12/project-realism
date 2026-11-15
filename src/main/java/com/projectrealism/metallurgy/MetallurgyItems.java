package com.projectrealism.metallurgy;

import com.projectrealism.ProjectRealism;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;

/**
 * Registers all geological ore chunks, refractory components, bellows, tongs, crucibles,
 * carvable casting molds, and the category-free universal {@link DynamicMetalItem}.
 */
public final class MetallurgyItems {
	public static final Item ORE_CHUNK = register("ore_chunk", new OreChunkItem(new Item.Settings()));
	public static final Item SLAG_CLUMP = register("slag_clump", new Item(new Item.Settings().maxCount(32)));
	public static final Item FIRE_CLAY_BALL = register("fire_clay_ball", new Item(new Item.Settings().maxCount(32)));
	public static final Item FIRE_CLAY_BRICK = register("fire_clay_brick", new Item(new Item.Settings().maxCount(32)));
	public static final Item LEATHER_BELLOWS = register("leather_bellows", new Item(new Item.Settings().maxCount(1)));
	public static final Item IRON_TONGS = register("iron_tongs", new Item(new Item.Settings().maxCount(1)));

	public static final Item DYNAMIC_METAL = register("dynamic_metal", new DynamicMetalItem(new Item.Settings()));

	// BlockItems
	public static final Item NATIVE_COPPER_DEPOSIT = registerBlockItem("native_copper_deposit", MetallurgyBlocks.NATIVE_COPPER_DEPOSIT);
	public static final Item MALACHITE_AZURITE_DEPOSIT = registerBlockItem("malachite_azurite_deposit", MetallurgyBlocks.MALACHITE_AZURITE_DEPOSIT);
	public static final Item CHALCOPYRITE_DEPOSIT = registerBlockItem("chalcopyrite_deposit", MetallurgyBlocks.CHALCOPYRITE_DEPOSIT);
	public static final Item CASSITERITE_DEPOSIT = registerBlockItem("cassiterite_deposit", MetallurgyBlocks.CASSITERITE_DEPOSIT);
	public static final Item ARSENOPYRITE_DEPOSIT = registerBlockItem("arsenopyrite_deposit", MetallurgyBlocks.ARSENOPYRITE_DEPOSIT);
	public static final Item HEMATITE_MAGNETITE_DEPOSIT = registerBlockItem("hematite_magnetite_deposit", MetallurgyBlocks.HEMATITE_MAGNETITE_DEPOSIT);
	public static final Item LIMONITE_BOG_DEPOSIT = registerBlockItem("limonite_bog_deposit", MetallurgyBlocks.LIMONITE_BOG_DEPOSIT);
	public static final Item FIRE_CLAY_DEPOSIT = registerBlockItem("fire_clay_deposit", MetallurgyBlocks.FIRE_CLAY_DEPOSIT);

	public static final Item RAW_CLAY_BLOCK = registerBlockItem("raw_clay_wall", MetallurgyBlocks.RAW_CLAY_BLOCK);
	public static final Item FIRED_CLAY_WALL = registerBlockItem("fired_clay_wall", MetallurgyBlocks.FIRED_CLAY_WALL);
	public static final Item FIRE_CLAY_BRICKS = registerBlockItem("fire_clay_bricks", MetallurgyBlocks.FIRE_CLAY_BRICKS);

	public static final Item SMELTING_BASIN = registerBlockItem("smelting_basin", MetallurgyBlocks.SMELTING_BASIN);
	public static final Item CLAY_CRUCIBLE_ITEM = register("clay_crucible",
			new ClayCrucibleItem(MetallurgyBlocks.CLAY_CRUCIBLE, new Item.Settings()));
	public static final Item CARVABLE_MOLD_ITEM = registerBlockItem("carvable_mold", MetallurgyBlocks.CARVABLE_MOLD);

	public static final ItemGroup GROUP = FabricItemGroup.builder()
			.displayName(Text.translatable("itemGroup.project_realism.metallurgy"))
			.icon(() -> DynamicMetalItem.create(DynamicMetalState.createNativeNugget(5L, 0.9, 0.96)))
			.entries((context, entries) -> {
				entries.add(SMELTING_BASIN);
				entries.add(CLAY_CRUCIBLE_ITEM);
				entries.add(CARVABLE_MOLD_ITEM);
				entries.add(RAW_CLAY_BLOCK);
				entries.add(FIRED_CLAY_WALL);
				entries.add(FIRE_CLAY_BRICKS);
				entries.add(FIRE_CLAY_BALL);
				entries.add(FIRE_CLAY_BRICK);
				entries.add(LEATHER_BELLOWS);
				entries.add(IRON_TONGS);
				entries.add(SLAG_CLUMP);
				entries.add(OreChunkItem.create(OreComposition.malachiteOre(0.55, 0.25)));
				entries.add(OreChunkItem.create(OreComposition.cassiteriteOre(0.45, 0.25)));
				entries.add(OreChunkItem.create(OreComposition.chalcopyriteOre(0.55, 0.25)));
				entries.add(OreChunkItem.create(OreComposition.ironOre(0.60, MineralSpecies.HEMATITE, 0.20)));
				entries.add(DynamicMetalItem.create(DynamicMetalState.createNativeNugget(11L, 0.92, 0.97)));
				entries.add(NATIVE_COPPER_DEPOSIT);
				entries.add(MALACHITE_AZURITE_DEPOSIT);
				entries.add(CHALCOPYRITE_DEPOSIT);
				entries.add(CASSITERITE_DEPOSIT);
				entries.add(ARSENOPYRITE_DEPOSIT);
				entries.add(HEMATITE_MAGNETITE_DEPOSIT);
				entries.add(LIMONITE_BOG_DEPOSIT);
				entries.add(FIRE_CLAY_DEPOSIT);
			})
			.build();

	private MetallurgyItems() {
	}

	private static Item register(String path, Item item) {
		return Registry.register(Registries.ITEM, ProjectRealism.id(path), item);
	}

	private static Item registerBlockItem(String path, Block block) {
		return register(path, new BlockItem(block, new Item.Settings()));
	}

	public static void register() {
		Registry.register(Registries.ITEM_GROUP,
				RegistryKey.of(RegistryKeys.ITEM_GROUP, ProjectRealism.id("metallurgy")),
				GROUP);
		ProjectRealism.LOGGER.info("Physical metallurgy items registered.");
	}
}
