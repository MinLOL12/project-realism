package com.projectrealism.hydration;

import com.projectrealism.ProjectRealism;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * Registers survival hydration gear: leather waterskin, charcoal water filter, and electrolyte
 * mineral salts.
 */
public final class HydrationItems {
	public static final Item WATERSKIN = register("waterskin",
			new WaterskinItem(new Item.Settings().maxCount(1)));

	public static final Item WATER_FILTER = register("water_filter",
			new Item(new Item.Settings().maxDamage(16)) {
				@Override
				public void appendTooltip(ItemStack stack, TooltipContext ctx, List<Text> tooltip, TooltipType type) {
					super.appendTooltip(stack, ctx, tooltip, type);
					tooltip.add(Text.translatable("item.project_realism.water_filter.tooltip").formatted(Formatting.GRAY));
				}
			});

	public static final Item ELECTROLYTE_SALTS = register("electrolyte_salts",
			new Item(new Item.Settings().maxCount(16)) {
				@Override
				public void appendTooltip(ItemStack stack, TooltipContext ctx, List<Text> tooltip, TooltipType type) {
					super.appendTooltip(stack, ctx, tooltip, type);
					tooltip.add(Text.translatable("item.project_realism.electrolyte_salts.tooltip").formatted(Formatting.GOLD));
				}
			});

	public static final ItemGroup HYDRATION_GROUP = FabricItemGroup.builder()
			.displayName(Text.translatable("itemGroup.project_realism.hydration"))
			.icon(() -> new ItemStack(WATERSKIN))
			.entries((ctx, entries) -> {
				entries.add(WATERSKIN);
				entries.add(WATER_FILTER);
				entries.add(ELECTROLYTE_SALTS);
			})
			.build();

	private HydrationItems() {
	}

	private static Item register(String path, Item item) {
		return Registry.register(Registries.ITEM, ProjectRealism.id(path), item);
	}

	public static void register() {
		Registry.register(Registries.ITEM_GROUP,
				RegistryKey.of(RegistryKeys.ITEM_GROUP, ProjectRealism.id("hydration")),
				HYDRATION_GROUP);
		ProjectRealism.LOGGER.info("Physiological hydration items registered.");
	}
}
