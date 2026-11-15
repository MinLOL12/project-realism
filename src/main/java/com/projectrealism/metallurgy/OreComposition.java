package com.projectrealism.metallurgy;

import net.minecraft.nbt.NbtCompound;

import java.util.EnumMap;
import java.util.Map;

/**
 * Physical mineralogical composition of a mined ore rock, crushed concentrate, or washed pan.
 * Stores real mass in kilograms, exact fraction of every constituent mineral + silicate/iron/calcite
 * gangue, mechanical crushing fineness, and oxidative sulfide roast degree.
 */
public final class OreComposition {
	public double massKg;
	/** Mineral weights keyed by species, normalized internally on mutation. */
	private final EnumMap<MineralSpecies, Double> minerals = new EnumMap<>(MineralSpecies.class);
	/** 0 = intact raw cobble, 1 = finely crushed mineral sand ready for washing or smelting. */
	public double crushedDegree;
	/** 0 = unroasted raw sulfide, 1 = fully roasted oxide ready for reduction. */
	public double roastedFraction;
	/** Unwanted sulfur fraction bound inside unroasted sulfides. */
	public double sulfurContent;

	public OreComposition(double massKg) {
		this.massKg = Math.max(0.02, massKg);
	}

	public void setMineral(MineralSpecies species, double weight) {
		if (weight <= 1e-6) {
			minerals.remove(species);
		} else {
			minerals.put(species, weight);
		}
	}

	public void addMineral(MineralSpecies species, double weight) {
		if (weight <= 1e-6) return;
		minerals.merge(species, weight, Double::sum);
	}

	public void setFraction(MineralSpecies species, double weight) {
		setMineral(species, weight);
	}

	public double fraction(MineralSpecies species) {
		return fractionOf(species);
	}

	public void normalize() {
		double total = totalWeight();
		if (total <= 1e-9) return;
		for (var e : new EnumMap<>(minerals).entrySet()) {
			minerals.put(e.getKey(), e.getValue() / total);
		}
	}

	public static OreComposition malachiteOre(double massKg, double grade) {
		return createVeinOre(MineralSpecies.MALACHITE, massKg, Math.max(0.20, Math.min(0.85, grade)), 11L);
	}

	public static OreComposition cassiteriteOre(double massKg, double grade) {
		return createVeinOre(MineralSpecies.CASSITERITE, massKg, Math.max(0.20, Math.min(0.85, grade)), 23L);
	}

	public static OreComposition chalcopyriteOre(double massKg, double grade) {
		return createVeinOre(MineralSpecies.CHALCOPYRITE, massKg, Math.max(0.20, Math.min(0.85, grade)), 37L);
	}

	public static OreComposition ironOre(double massKg, MineralSpecies ironMineral, double grade) {
		return createVeinOre(ironMineral, massKg, Math.max(0.20, Math.min(0.85, grade)), 51L);
	}

	public double fractionOf(MineralSpecies species) {
		double total = totalWeight();
		if (total <= 1e-9) return 0.0;
		return minerals.getOrDefault(species, 0.0) / total;
	}

	public Map<MineralSpecies, Double> normalizedMap() {
		EnumMap<MineralSpecies, Double> out = new EnumMap<>(MineralSpecies.class);
		double total = totalWeight();
		if (total <= 1e-9) {
			out.put(MineralSpecies.SILICA_GANGUE, 1.0);
			return out;
		}
		for (var e : minerals.entrySet()) {
			out.put(e.getKey(), e.getValue() / total);
		}
		return out;
	}

	private double totalWeight() {
		double sum = 0.0;
		for (double v : minerals.values()) sum += v;
		return sum;
	}

	/** Creates a realistic deposit chunk conditioned on primary mineral, vein richness, and depth. */
	public static OreComposition createVeinOre(MineralSpecies primary, double massKg, double grade01, long seed) {
		OreComposition oc = new OreComposition(massKg);
		double g = Math.max(0.08, Math.min(0.95, grade01));
		oc.setMineral(primary, g);
		double gangue = 1.0 - g;
		boolean carbonateHost = (seed & 3L) == 0;
		if (primary == MineralSpecies.MALACHITE && (seed & 7L) == 1) {
			oc.setMineral(MineralSpecies.AZURITE, g * 0.28);
			oc.setMineral(primary, g * 0.72);
		}
		if (primary == MineralSpecies.CHALCOPYRITE) {
			oc.setMineral(MineralSpecies.HEMATITE, gangue * 0.25);
			oc.setMineral(MineralSpecies.SILICA_GANGUE, gangue * 0.75);
			oc.sulfurContent = g * 0.34;
		} else if (primary == MineralSpecies.ARSENOPYRITE) {
			oc.setMineral(MineralSpecies.SILICA_GANGUE, gangue);
			oc.sulfurContent = g * 0.20;
		} else {
			oc.setMineral(carbonateHost ? MineralSpecies.CALCITE_FLUX : MineralSpecies.SILICA_GANGUE, gangue * 0.85);
			if (gangue > 0.10) {
				oc.setMineral(MineralSpecies.HEMATITE, gangue * 0.15);
			}
		}
		if (primary == MineralSpecies.NATIVE_COPPER) {
			oc.roastedFraction = 1.0;
		}
		return oc;
	}

	/** Total mass fraction of recoverable pure metal (Cu + Sn + Fe + As) before smelting losses. */
	public double totalRecoverableMetalFraction() {
		double total = 0.0;
		for (var e : normalizedMap().entrySet()) {
			total += e.getValue() * e.getKey().pureMetalFraction;
		}
		return total;
	}

	public double recoverableElementFraction(MineralSpecies.TargetElement target) {
		double total = 0.0;
		for (var e : normalizedMap().entrySet()) {
			if (e.getKey().element == target) {
				total += e.getValue() * e.getKey().pureMetalFraction;
			}
		}
		return total;
	}

	public double gangueFraction() {
		return fractionOf(MineralSpecies.SILICA_GANGUE) + fractionOf(MineralSpecies.CALCITE_FLUX);
	}

	public MineralSpecies dominantSpecies() {
		MineralSpecies best = MineralSpecies.SILICA_GANGUE;
		double bestVal = -1.0;
		for (var e : normalizedMap().entrySet()) {
			if (e.getKey().element != MineralSpecies.TargetElement.NONE && e.getValue() > bestVal) {
				bestVal = e.getValue();
				best = e.getKey();
			}
		}
		return bestVal > 0.0 ? best : MineralSpecies.SILICA_GANGUE;
	}

	/**
	 * Mechanical ore crushing & hand-cobbing: breaks brittle silica/calcite gangue free, shedding
	 * waste rock and raising ore concentration. Returns waste gangue massKg chipped away.
	 */
	public double crushAndSort(double strikeEnergyJ) {
		double step = Math.min(0.45, 0.18 * strikeEnergyJ);
		double prevCrush = crushedDegree;
		crushedDegree = Math.min(1.0, crushedDegree + step);
		double gangue = gangueFraction();
		if (gangue <= 0.04) return 0.0;

		double rejectFrac = Math.min(gangue * 0.55, (crushedDegree - prevCrush) * gangue * 0.72);
		double rejectedMass = massKg * rejectFrac;
		if (rejectedMass <= 1e-4) return 0.0;

		EnumMap<MineralSpecies, Double> norm = new EnumMap<>(normalizedMap());
		for (MineralSpecies g : new MineralSpecies[]{MineralSpecies.SILICA_GANGUE, MineralSpecies.CALCITE_FLUX}) {
			double cur = norm.getOrDefault(g, 0.0);
			if (cur > 0.0) {
				norm.put(g, Math.max(0.01, cur * (1.0 - rejectFrac / Math.max(0.05, gangue))));
			}
		}
		minerals.clear();
		double sum = 0.0;
		for (double v : norm.values()) sum += v;
		for (var e : norm.entrySet()) minerals.put(e.getKey(), e.getValue() / Math.max(1e-9, sum));
		massKg = Math.max(0.02, massKg - rejectedMass);
		return rejectedMass;
	}

	/**
	 * Water sluicing / panning of crushed ore: density separation flushes away light silica/calcite
	 * (~2650 kg/m3) while dense metal oxides/native copper (~4000..8900 kg/m3) remain!
	 */
	public double washConcentrate() {
		if (crushedDegree < 0.20) return 0.0;
		double gangue = gangueFraction();
		if (gangue <= 0.05) return 0.0;

		double washEff = 0.35 + 0.50 * crushedDegree;
		double removedFrac = gangue * washEff * 0.65;
		double washedMass = massKg * removedFrac;

		EnumMap<MineralSpecies, Double> norm = new EnumMap<>(normalizedMap());
		for (MineralSpecies g : new MineralSpecies[]{MineralSpecies.SILICA_GANGUE, MineralSpecies.CALCITE_FLUX}) {
			double cur = norm.getOrDefault(g, 0.0);
			if (cur > 0.0) {
				norm.put(g, Math.max(0.01, cur * (1.0 - washEff * 0.65)));
			}
		}
		minerals.clear();
		double sum = 0.0;
		for (double v : norm.values()) sum += v;
		for (var e : norm.entrySet()) minerals.put(e.getKey(), e.getValue() / Math.max(1e-9, sum));
		massKg = Math.max(0.02, massKg - washedMass);
		return washedMass;
	}

	public double crushAndSort(double effort, java.util.Random rng) {
		double step = Math.max(0.15, Math.min(0.95, effort));
		double prevCrush = crushedDegree;
		crushedDegree = Math.min(1.0, crushedDegree + step);
		double gangue = gangueFraction();
		if (gangue <= 0.04) return 0.0;
		double rejectFrac = Math.min(gangue * 0.65, (crushedDegree - prevCrush) * gangue * 0.55);
		double rejectedMass = massKg * rejectFrac;
		EnumMap<MineralSpecies, Double> norm = new EnumMap<>(normalizedMap());
		for (MineralSpecies g : new MineralSpecies[]{MineralSpecies.SILICA_GANGUE, MineralSpecies.CALCITE_FLUX}) {
			double cur = norm.getOrDefault(g, 0.0);
			if (cur > 0.0) {
				norm.put(g, Math.max(0.01, cur * (1.0 - rejectFrac / Math.max(0.05, gangue))));
			}
		}
		minerals.clear();
		double sum = 0.0;
		for (double v : norm.values()) sum += v;
		for (var e : norm.entrySet()) minerals.put(e.getKey(), e.getValue() / Math.max(1e-9, sum));
		massKg = Math.max(0.02, massKg - rejectedMass);
		return rejectedMass;
	}

	public double washConcentrate(double efficiency) {
		if (crushedDegree < 0.20) return 0.0;
		double gangue = gangueFraction();
		if (gangue <= 0.03) return 0.0;
		double eff = Math.max(0.25, Math.min(0.95, efficiency * (0.55 + 0.45 * crushedDegree)));
		double washedFrac = gangue * eff * 0.85;
		EnumMap<MineralSpecies, Double> norm = new EnumMap<>(normalizedMap());
		for (MineralSpecies g : new MineralSpecies[]{MineralSpecies.SILICA_GANGUE, MineralSpecies.CALCITE_FLUX}) {
			double cur = norm.getOrDefault(g, 0.0);
			if (cur > 0.0) {
				norm.put(g, Math.max(0.005, cur * (1.0 - eff * 0.85)));
			}
		}
		minerals.clear();
		double sum = 0.0;
		for (double v : norm.values()) sum += v;
		for (var e : norm.entrySet()) minerals.put(e.getKey(), e.getValue() / Math.max(1e-9, sum));
		double washedMass = massKg * washedFrac;
		massKg = Math.max(0.02, massKg - washedMass);
		return washedMass;
	}

	public OreComposition copy() {
		OreComposition c = new OreComposition(massKg);
		c.minerals.putAll(minerals);
		c.crushedDegree = crushedDegree;
		c.roastedFraction = roastedFraction;
		c.sulfurContent = sulfurContent;
		return c;
	}

	public NbtCompound toNbt() {
		NbtCompound t = new NbtCompound();
		t.putDouble("MassKg", massKg);
		t.putDouble("Crush", crushedDegree);
		t.putDouble("Roast", roastedFraction);
		t.putDouble("Sulfur", sulfurContent);
		NbtCompound mTag = new NbtCompound();
		for (var e : minerals.entrySet()) {
			mTag.putDouble(e.getKey().id, e.getValue());
		}
		t.put("Mins", mTag);
		return t;
	}

	public static OreComposition fromNbt(NbtCompound t) {
		double m = t.contains("MassKg") ? t.getDouble("MassKg") : 0.45;
		OreComposition oc = new OreComposition(m);
		oc.crushedDegree = t.getDouble("Crush");
		oc.roastedFraction = t.getDouble("Roast");
		oc.sulfurContent = t.getDouble("Sulfur");
		if (t.contains("Mins")) {
			NbtCompound mTag = t.getCompound("Mins");
			for (String key : mTag.getKeys()) {
				oc.setMineral(MineralSpecies.fromId(key), mTag.getDouble(key));
			}
		}
		if (oc.totalWeight() <= 1e-6) {
			oc.setMineral(MineralSpecies.MALACHITE, 0.45);
			oc.setMineral(MineralSpecies.SILICA_GANGUE, 0.55);
		}
		return oc;
	}
}
