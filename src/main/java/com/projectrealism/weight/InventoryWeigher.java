package com.projectrealism.weight;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns an inventory into kilograms.
 *
 * <p>The carried mass covers the main inventory, the hotbar, worn armour, the off hand, and the
 * contents of shulker boxes and bundles. Nested containers are counted three levels deep, after which
 * the recursion stops — a shulker inside a shulker inside a shulker is already a war crime and
 * measuring it further feels like gilding.</p>
 */
public final class InventoryWeigher {
	private static final int MAX_CONTAINER_DEPTH = 3;

	private InventoryWeigher() {
	}

	public static WeightReport weigh(PlayerEntity player, WeightTable table, RealismNumbers numbers) {
		Map<Identifier, Tally> totals = new HashMap<>();
		double total = 0.0;

		PlayerInventory inventory = player.getInventory();

		// size() covers the main inventory, the hotbar, worn armour and the off hand in one sweep.
		for (int slot = 0; slot < inventory.size(); slot++) {
			total += addStack(inventory.getStack(slot), table, totals, 0);
		}

		List<WeightReport.Entry> contributions = new ArrayList<>(totals.size());
		for (Map.Entry<Identifier, Tally> entry : totals.entrySet()) {
			Tally tally = entry.getValue();
			contributions.add(new WeightReport.Entry(
					entry.getKey().toString(),
					tally.displayName,
					round(tally.kg),
					tally.count));
		}
		contributions.sort(Comparator.comparingDouble(WeightReport.Entry::totalKg).reversed());

		double multiplier = Encumbrance.speedMultiplier(total, numbers.capacityKg, numbers.halfLifeKg,
				numbers.exponent, numbers.minMultiplier);

		return new WeightReport(round(total), numbers.capacityKg, multiplier, contributions);
	}

	/**
	 * Adds one stack's mass, descending into containers. Contained items are tallied under their own
	 * ids so the breakdown stays honest, and the container's own (empty) weight is still charged.
	 */
	private static double addStack(ItemStack stack, WeightTable table,
								   Map<Identifier, Tally> totals, int depth) {
		if (stack.isEmpty()) {
			return 0.0;
		}

		double mass;
		if (stack.isOf(com.projectrealism.hydration.HydrationItems.WATERSKIN)) {
			int vol = com.projectrealism.hydration.WaterskinItem.getVolumeMl(stack);
			mass = com.projectrealism.material.WaterskinConstruction.read(stack).totalMassKg(vol) * stack.getCount();
		} else if (stack.isOf(com.projectrealism.material.MaterialItems.GOURD_FLASK)) {
			mass = com.projectrealism.material.GourdFlaskItem.totalMassKg(stack) * stack.getCount();
		} else if (stack.isOf(com.projectrealism.anatomy.AnatomyItems.HORN_FLASK)) {
			mass = com.projectrealism.anatomy.HornFlaskItem.totalMassKg(stack) * stack.getCount();
		} else if (stack.isOf(com.projectrealism.anatomy.AnatomyItems.CARCASS_ITEM)) {
			mass = com.projectrealism.anatomy.CarcassItem.massKg(stack) * stack.getCount();
		} else if (stack.isOf(com.projectrealism.anatomy.AnatomyItems.RAW_BONE)) {
			mass = com.projectrealism.anatomy.RawBoneItem.massKg(stack) * stack.getCount();
		} else if (stack.getItem() instanceof com.projectrealism.anatomy.AnatomyMeatItem) {
			mass = com.projectrealism.anatomy.AnatomyMeatItem.massKg(stack) * stack.getCount();
		} else if (stack.isOf(com.projectrealism.material.MaterialItems.RAW_HIDE)) {
			mass = com.projectrealism.material.RawHideItem.readSurface(stack).currentMassKg() * stack.getCount();
		} else if (stack.isOf(com.projectrealism.material.MaterialItems.LEATHER_PIECE)) {
			mass = com.projectrealism.material.LeatherPieceItem.massKg(stack) * stack.getCount();
		} else {
			mass = table.weightOf(stack.getItem()) * stack.getCount();
		}

		Identifier id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem());
		Tally tally = totals.computeIfAbsent(id, key -> new Tally(stack.getName().getString()));
		tally.kg += mass;
		tally.count += stack.getCount();

		if (depth >= MAX_CONTAINER_DEPTH) {
			return mass;
		}

		ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
		if (container != null) {
			for (ItemStack inner : container.iterateNonEmpty()) {
				mass += addStack(inner, table, totals, depth + 1);
			}
		}

		BundleContentsComponent bundle = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
		if (bundle != null) {
			for (ItemStack inner : bundle.iterate()) {
				mass += addStack(inner, table, totals, depth + 1);
			}
		}

		return mass;
	}

	private static double round(double value) {
		return Math.round(value * 10.0) / 10.0;
	}

	private static final class Tally {
		private String displayName;
		private double kg;
		private int count;

		private Tally(String displayName) {
			this.displayName = displayName;
		}
	}

	/**
	 * The handful of numbers the weigher needs, copied out of the config so the weigher itself has no
	 * dependency on file IO or on Fabric's loader being initialised.
	 */
	public record RealismNumbers(double capacityKg, double halfLifeKg, double exponent, double minMultiplier) {
	}
}
