package com.projectrealism.primitive;

import com.projectrealism.ProjectRealism;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

/**
 * Items that only exist because the early game no longer hands you a tree.
 *
 * <p>Everything here is deliberately fragile: the stone knife and axe are the first tools a player can
 * build, and they are worse than iron in every respect, including how long they last.</p>
 */
public final class PrimitiveItems {
	// --- raw materials -------------------------------------------------------
	public static final Item GRANITE_ROCK = register("granite_rock", new Item(new Item.Settings()));
	public static final Item LIMESTONE_ROCK = register("limestone_rock", new Item(new Item.Settings()));
	public static final Item FLINT_ROCK = register("flint_rock", new Item(new Item.Settings()));

	public static final Item SHARP_STONE = register("sharp_stone", new Item(new Item.Settings()));
	public static final Item STONE_AXE_HEAD = register("stone_axe_head", new Item(new Item.Settings()));

	public static final Item PLANT_FIBER = register("plant_fiber", new Item(new Item.Settings()));
	public static final Item CORDAGE = register("cordage", new Item(new Item.Settings()));

	// --- tools ---------------------------------------------------------------
	public static final Item STONE_KNIFE = register("stone_knife",
			new StoneKnife(new Item.Settings()));
	public static final Item STONE_AXE = register("stone_axe",
			new KnappedAxe(new Item.Settings()));
	public static final Item STONE_PICKAXE = register("stone_pickaxe",
			new StonePickaxe(new Item.Settings()));

	private PrimitiveItems() {
	}

	private static Item register(String path, Item item) {
		Identifier id = ProjectRealism.id(path);
		return Registry.register(Registries.ITEM, id, item);
	}

	public static final net.minecraft.item.ItemGroup GROUP = net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup.builder()
			.displayName(net.minecraft.text.Text.translatable("itemGroup.project_realism.primitive"))
			.icon(() -> new ItemStack(SHARP_STONE))
			.entries((context, entries) -> {
				entries.add(GRANITE_ROCK);
				entries.add(LIMESTONE_ROCK);
				entries.add(FLINT_ROCK);
				entries.add(SHARP_STONE);
				entries.add(STONE_AXE_HEAD);
				entries.add(PLANT_FIBER);
				entries.add(CORDAGE);
				entries.add(STONE_KNIFE);
				entries.add(STONE_AXE);
				entries.add(STONE_PICKAXE);
			})
			.build();

	/** Called from the initializer purely to force class loading of the static fields. */
	public static void register() {
		Registry.register(Registries.ITEM_GROUP,
				net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.ITEM_GROUP,
						ProjectRealism.id("primitive")),
				GROUP);
		ProjectRealism.LOGGER.info("Primitive items registered.");
	}

	public static boolean isKnappable(Item item) {
		return item == GRANITE_ROCK || item == LIMESTONE_ROCK || item == FLINT_ROCK;
	}
}
