package com.projectrealism.wood;

import com.projectrealism.ProjectRealism;
import com.projectrealism.primitive.ToolCondition;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.registry.FuelRegistry;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Registers block-items for fresh logs, split wood firewood, and rough planks across all wood species.
 */
public final class WoodItems {
	private static final Map<WoodSpecies, Item> FRESH_LOGS = new EnumMap<>(WoodSpecies.class);
	private static final Map<WoodSpecies, Item> SPLIT_WOOD = new EnumMap<>(WoodSpecies.class);
	private static final Map<WoodSpecies, Item> ROUGH_PLANKS = new EnumMap<>(WoodSpecies.class);

	static {
		for (WoodSpecies sp : WoodSpecies.values()) {
			Item fresh = register("fresh_" + sp.id() + "_log",
					new WoodStageItem(WoodBlocks.freshLog(sp), new Item.Settings(), true));
			Item split = register(sp.id() + "_split_wood",
					new WoodStageItem(WoodBlocks.splitWood(sp), new Item.Settings(), false));
			Item rough = register("rough_" + sp.id() + "_plank",
					new WoodStageItem(WoodBlocks.roughPlank(sp), new Item.Settings(), false));

			FRESH_LOGS.put(sp, fresh);
			SPLIT_WOOD.put(sp, split);
			ROUGH_PLANKS.put(sp, rough);

			FuelRegistry.INSTANCE.add(split, 240);
			FuelRegistry.INSTANCE.add(fresh, 200);
			FuelRegistry.INSTANCE.add(rough, 200);
		}
	}

	public static final ItemGroup WOOD_GROUP = FabricItemGroup.builder()
			.displayName(Text.translatable("itemGroup.project_realism.woodcraft"))
			.icon(() -> new ItemStack(ROUGH_PLANKS.get(WoodSpecies.OAK)))
			.entries((context, entries) -> {
				for (WoodSpecies sp : WoodSpecies.values()) {
					entries.add(FRESH_LOGS.get(sp));
					entries.add(SPLIT_WOOD.get(sp));
					ItemStack drySplit = new ItemStack(SPLIT_WOOD.get(sp));
					ToolCondition.setDry(drySplit, true);
					entries.add(drySplit);
					entries.add(ROUGH_PLANKS.get(sp));
					ItemStack dryRough = new ItemStack(ROUGH_PLANKS.get(sp));
					ToolCondition.setDry(dryRough, true);
					entries.add(dryRough);
				}
			})
			.build();

	private WoodItems() {
	}

	private static Item register(String path, Item item) {
		return Registry.register(Registries.ITEM, ProjectRealism.id(path), item);
	}

	public static Item freshLog(WoodSpecies sp) {
		return FRESH_LOGS.get(sp);
	}

	public static Item splitWood(WoodSpecies sp) {
		return SPLIT_WOOD.get(sp);
	}

	public static Item roughPlank(WoodSpecies sp) {
		return ROUGH_PLANKS.get(sp);
	}

	public static void register() {
		Registry.register(Registries.ITEM_GROUP,
				RegistryKey.of(RegistryKeys.ITEM_GROUP, ProjectRealism.id("woodcraft")),
				WOOD_GROUP);
		ProjectRealism.LOGGER.info("Woodcraft items registered.");
	}

	private static final class WoodStageItem extends BlockItem {
		private final boolean alwaysWet;

		private WoodStageItem(Block block, Settings settings, boolean alwaysWet) {
			super(block, settings);
			this.alwaysWet = alwaysWet;
		}

		@Override
		public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
			super.appendTooltip(stack, context, tooltip, type);
			ToolCondition.appendWoodTooltip(stack, tooltip, alwaysWet);
		}
	}
}
