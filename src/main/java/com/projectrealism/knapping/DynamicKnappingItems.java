package com.projectrealism.knapping;

import com.projectrealism.ProjectRealism;
import com.projectrealism.primitive.PrimitiveItems;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;

/**
 * Registers the universal dynamic voxel stone item plus newly exposed geological rocks
 * (chert, quartzite, basalt, obsidian nodules) used for dynamic stone knapping and toolmaking.
 */
public final class DynamicKnappingItems {
	public static final Item CHERT_ROCK = register("chert_rock", new Item(new Item.Settings()));
	public static final Item QUARTZITE_ROCK = register("quartzite_rock", new Item(new Item.Settings()));
	public static final Item BASALT_ROCK = register("basalt_rock", new Item(new Item.Settings()));
	public static final Item OBSIDIAN_NODULE = register("obsidian_nodule", new Item(new Item.Settings()));

	public static final Item DYNAMIC_STONE = register("dynamic_stone",
			new DynamicStoneItem(new Item.Settings()));

	public static final ItemGroup GROUP = FabricItemGroup.builder()
			.displayName(Text.translatable("itemGroup.project_realism.knapping"))
			.icon(() -> DynamicStoneItem.create(DynamicStoneState.createCobble(StoneMaterial.FLINT, 42L, 0.90)))
			.entries((context, entries) -> {
				entries.add(CHERT_ROCK);
				entries.add(QUARTZITE_ROCK);
				entries.add(BASALT_ROCK);
				entries.add(OBSIDIAN_NODULE);
				for (StoneMaterial mat : StoneMaterial.values()) {
					entries.add(DynamicStoneItem.create(DynamicStoneState.createCobble(mat, 100L + mat.ordinal() * 31L, 0.92)));
				}
			})
			.build();

	private DynamicKnappingItems() {
	}

	private static Item register(String path, Item item) {
		return Registry.register(Registries.ITEM, ProjectRealism.id(path), item);
	}

	public static void register() {
		Registry.register(Registries.ITEM_GROUP,
				RegistryKey.of(RegistryKeys.ITEM_GROUP, ProjectRealism.id("knapping")),
				GROUP);
		ProjectRealism.LOGGER.info("Dynamic stone knapping items registered.");
	}

	/** Maps any raw rock pickup or dynamic stone into its geological {@link StoneMaterial}, or null. */
	public static StoneMaterial materialOfItem(ItemStack stack) {
		if (stack.isEmpty()) return null;
		if (stack.isOf(DYNAMIC_STONE)) return DynamicStoneItem.readState(stack).material;
		if (stack.isOf(PrimitiveItems.FLINT_ROCK) || stack.isOf(net.minecraft.item.Items.FLINT)) return StoneMaterial.FLINT;
		if (stack.isOf(CHERT_ROCK)) return StoneMaterial.CHERT;
		if (stack.isOf(OBSIDIAN_NODULE)) return StoneMaterial.OBSIDIAN;
		if (stack.isOf(QUARTZITE_ROCK)) return StoneMaterial.QUARTZITE;
		if (stack.isOf(BASALT_ROCK)) return StoneMaterial.BASALT;
		if (stack.isOf(PrimitiveItems.GRANITE_ROCK)) return StoneMaterial.GRANITE;
		if (stack.isOf(PrimitiveItems.LIMESTONE_ROCK)) return StoneMaterial.LIMESTONE;
		return null;
	}

	public static boolean isKnappableRawRock(ItemStack stack) {
		return stack.isOf(CHERT_ROCK) || stack.isOf(QUARTZITE_ROCK) || stack.isOf(BASALT_ROCK)
				|| stack.isOf(OBSIDIAN_NODULE) || stack.isOf(PrimitiveItems.FLINT_ROCK)
				|| stack.isOf(PrimitiveItems.GRANITE_ROCK) || stack.isOf(PrimitiveItems.LIMESTONE_ROCK)
				|| stack.isOf(net.minecraft.item.Items.FLINT);
	}

	public static boolean isStriker(ItemStack stack) {
		return stack.isOf(DYNAMIC_STONE) || isKnappableRawRock(stack);
	}
}
