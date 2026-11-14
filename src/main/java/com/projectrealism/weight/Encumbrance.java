package com.projectrealism.weight;

/**
 * The entire encumbrance model, kept free of Minecraft types so it can be unit tested and mirrored
 * by the tuning simulator in {@code simulator/}.
 *
 * <p>The curve is deliberately smooth at the capacity boundary: carrying exactly 30 kg costs nothing,
 * carrying 30.1 kg costs almost nothing, and the penalty then deepens continuously. There is no cliff
 * where a player suddenly loses half their speed for picking up one more dirt block.</p>
 */
public final class Encumbrance {
	private Encumbrance() {
	}

	/**
	 * @param weightKg      total mass carried
	 * @param capacityKg    mass carried without penalty
	 * @param halfLifeKg    excess mass that halves speed
	 * @param exponent      curve shape
	 * @param minMultiplier floor, so heavy players can still limp home
	 * @return multiplier applied to the player's movement speed
	 */
	public static double speedMultiplier(double weightKg, double capacityKg, double halfLifeKg,
										  double exponent, double minMultiplier) {
		double overload = weightKg - capacityKg;
		if (overload <= 0.0) {
			return 1.0;
		}
		double scaled = overload / Math.max(0.0001, halfLifeKg);
		double multiplier = 1.0 / (1.0 + Math.pow(scaled, Math.max(0.1, exponent)));
		return Math.max(minMultiplier, Math.min(1.0, multiplier));
	}

	/** Vanilla walking speed is 4.317 m/s; this is what the player actually moves at. */
	public static double walkingSpeedMetersPerSecond(double multiplier) {
		return 4.317 * multiplier;
	}

	/** How overloaded the player is, 0.0 = free and easy, 1.0 = twice capacity, etc. */
	public static double overloadRatio(double weightKg, double capacityKg) {
		if (capacityKg <= 0.0) {
			return 0.0;
		}
		return Math.max(0.0, weightKg / capacityKg - 1.0);
	}

	/**
	 * Extra exhaustion per tick, i.e. hunger drain attributable to hauling too much.
	 *
	 * @param moving whether the player actually moved this tick; standing still under a load is
	 *                 exhausting but nowhere near as bad as walking with it
	 */
	public static float exhaustionPerTick(double weightKg, double capacityKg, double perKgPerSecond, boolean moving) {
		double excess = Math.max(0.0, weightKg - capacityKg);
		if (excess <= 0.0 || perKgPerSecond <= 0.0) {
			return 0.0f;
		}
		double perSecond = excess * perKgPerSecond * (moving ? 1.0 : 0.25);
		return (float) (perSecond / 20.0);
	}

	/** Severity bands used for the HUD colour and for one-off chat warnings. */
	public enum Severity {
		COMFORTABLE,
		LOADED,
		STRAINED,
		CRUSHED
	}

	public static Severity severity(double weightKg, double capacityKg) {
		double ratio = capacityKg <= 0.0 ? 0.0 : weightKg / capacityKg;
		if (ratio <= 1.0) {
			return Severity.COMFORTABLE;
		}
		if (ratio <= 1.25) {
			return Severity.LOADED;
		}
		if (ratio <= 1.75) {
			return Severity.STRAINED;
		}
		return Severity.CRUSHED;
	}
}
