package com.projectrealism.weight;

import java.util.List;

/**
 * A snapshot of what a player is hauling and what it costs them. Deliberately free of Minecraft
 * types so the encumbrance maths can be unit tested without bootstrapping a game.
 *
 * @param totalKg         total mass carried
 * @param capacityKg      mass carried for free
 * @param speedMultiplier applied to movement speed, 1.0 = unburdened
 * @param contributions   kg per item type, sorted heaviest first
 */
public record WeightReport(
		double totalKg,
		double capacityKg,
		double speedMultiplier,
		List<Entry> contributions
) {
	public record Entry(String itemId, String displayName, double totalKg, int count) {
	}

	public boolean overloaded() {
		return totalKg > capacityKg;
	}

	public double overloadKg() {
		return Math.max(0.0, totalKg - capacityKg);
	}

	public double speedPercent() {
		return speedMultiplier * 100.0;
	}

	public Encumbrance.Severity severity() {
		return Encumbrance.severity(totalKg, capacityKg);
	}

	public static WeightReport empty(double capacityKg) {
		return new WeightReport(0.0, capacityKg, 1.0, List.of());
	}
}
