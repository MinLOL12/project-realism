package com.projectrealism.thermo;

import com.projectrealism.ProjectRealism;
import com.projectrealism.primitive.ToolCondition;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.registry.FuelRegistry;
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
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * Registers firemaking gear, hearth & ash items, and the Combustion & Thermal item group.
 */
public final class ThermoItems {
	public static final Item HEARTH = register("hearth",
			new BlockItem(ThermoBlocks.HEARTH, new Item.Settings()));

	public static final Item WOOD_ASH = register("wood_ash",
			new BlockItem(ThermoBlocks.WOOD_ASH_PILE, new Item.Settings()) {
				@Override
				public void appendTooltip(ItemStack stack, TooltipContext ctx, List<Text> tooltip, TooltipType type) {
					super.appendTooltip(stack, ctx, tooltip, type);
					tooltip.add(Text.translatable("item.project_realism.wood_ash.tooltip").formatted(Formatting.GRAY));
				}
			});

	public static final Item TINDER_BUNDLE = register("tinder_bundle",
			new Item(new Item.Settings()) {
				@Override
				public void appendTooltip(ItemStack stack, TooltipContext ctx, List<Text> tooltip, TooltipType type) {
					super.appendTooltip(stack, ctx, tooltip, type);
					double m = ToolCondition.getSurfaceMoisture(stack, 0.10);
					int pct = (int) Math.round(m * 100.0);
					tooltip.add(Text.translatable("project_realism.tooltip.tinder_moisture", pct)
							.formatted(m <= 0.18 ? Formatting.GOLD : Formatting.AQUA));
				}
			});

	public static final Item FIRE_DRILL = register("fire_drill",
			new Item(new Item.Settings().maxDamage(48)) {
				@Override
				public void appendTooltip(ItemStack stack, TooltipContext ctx, List<Text> tooltip, TooltipType type) {
					super.appendTooltip(stack, ctx, tooltip, type);
					tooltip.add(Text.translatable("item.project_realism.fire_drill.tooltip").formatted(Formatting.GRAY));
				}
			});

	public static final Item BOW_DRILL = register("bow_drill",
			new Item(new Item.Settings().maxDamage(120)) {
				@Override
				public void appendTooltip(ItemStack stack, TooltipContext ctx, List<Text> tooltip, TooltipType type) {
					super.appendTooltip(stack, ctx, tooltip, type);
					tooltip.add(Text.translatable("item.project_realism.bow_drill.tooltip").formatted(Formatting.GOLD));
				}
			});

	public static final ItemGroup THERMO_GROUP = FabricItemGroup.builder()
			.displayName(Text.translatable("itemGroup.project_realism.thermodynamics"))
			.icon(() -> new ItemStack(HEARTH))
			.entries((ctx, entries) -> {
				entries.add(HEARTH);
				entries.add(TINDER_BUNDLE);
				entries.add(FIRE_DRILL);
				entries.add(BOW_DRILL);
				entries.add(WOOD_ASH);
			})
			.build();

	static {
		FuelRegistry.INSTANCE.add(TINDER_BUNDLE, 120);
	}

	private ThermoItems() {
	}

	private static Item register(String path, Item item) {
		return Registry.register(Registries.ITEM, ProjectRealism.id(path), item);
	}

	public static void register() {
		Registry.register(Registries.ITEM_GROUP,
				RegistryKey.of(RegistryKeys.ITEM_GROUP, ProjectRealism.id("thermodynamics")),
				THERMO_GROUP);
		ProjectRealism.LOGGER.info("Thermodynamic & firemaking items registered.");
	}
}
