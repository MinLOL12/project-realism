package com.projectrealism.anatomy;

import net.minecraft.nbt.NbtCompound;

/**
 * Physical anatomical bone record tracking dimensional size, cross-section diameter, cortical
 * mineral density, moisture content, thermal brittleness, cut notches, and abrasive shaping.
 * Size and density directly constrain what tools can be fashioned and how fragile they turn out.
 */
public final class BoneData {
	public enum BoneSize {
		TINY("tiny", 1),
		SMALL("small", 2),
		MEDIUM("medium", 3),
		LARGE("large", 4);

		public final String id;
		public final int rank;

		BoneSize(String id, int rank) {
			this.id = id;
			this.rank = rank;
		}

		public static BoneSize fromId(String id) {
			for (BoneSize b : values()) {
				if (b.id.equalsIgnoreCase(id)) return b;
			}
			return MEDIUM;
		}
	}

	public enum BoneType {
		LONG_SHAFT("long_shaft"),
		RIB("rib"),
		SCAPULA("scapula");

		public final String id;

		BoneType(String id) {
			this.id = id;
		}

		public static BoneType fromId(String id) {
			for (BoneType b : values()) {
				if (b.id.equalsIgnoreCase(id)) return b;
			}
			return LONG_SHAFT;
		}
	}

	public enum ToolTarget {
		NONE("none", BoneSize.TINY),
		NEEDLE("bone_needle", BoneSize.TINY),
		AWL("bone_awl", BoneSize.SMALL),
		BLADE("bone_blade", BoneSize.MEDIUM),
		CHISEL("bone_chisel", BoneSize.LARGE);

		public final String id;
		public final BoneSize minimumSize;

		ToolTarget(String id, BoneSize minimumSize) {
			this.id = id;
			this.minimumSize = minimumSize;
		}

		public static ToolTarget fromId(String id) {
			for (ToolTarget t : values()) {
				if (t.id.equalsIgnoreCase(id)) return t;
			}
			return NONE;
		}
	}

	public String speciesId = "cow";
	public BoneSize size = BoneSize.MEDIUM;
	public BoneType boneType = BoneType.LONG_SHAFT;
	public double lengthCm = 20.0;
	public double diameterMm = 18.0;
	public double density = 0.85;       // 0.30 (porous juvenile/bird) .. 1.0 (dense adult cow femur)
	public double moisture = 0.42;      // green wet bone vs seasoned dry bone
	public double brittleness = 0.10;   // spikes if over-heated (>345 K) or forced dry
	public double cutProgress = 0.0;    // 0..1 shaping by Stone Knife
	public double abrasionSmooth = 0.0; // 0..1 grinding/polishing by Stone Abrasive
	public double workDamage = 0.0;     // micro-fractures from dull/clumsy working
	public ToolTarget target = ToolTarget.NONE;

	public BoneData() {
	}

	public BoneData(AnimalSpecies species, BoneType boneType, double ageMaturity) {
		this.speciesId = species.id;
		this.boneType = boneType;
		double scale = 0.55 + 0.45 * Math.max(0.25, Math.min(1.0, ageMaturity));
		this.lengthCm = species.boneLengthCm * (boneType == BoneType.RIB ? 0.72 : 1.0) * scale;
		this.diameterMm = species.boneDiameterMm * (boneType == BoneType.RIB ? 0.58 : 1.0) * scale;
		this.density = Math.max(0.32, Math.min(1.0, species.boneDensity * (0.75 + 0.25 * ageMaturity)));
		this.size = classifySize(this.lengthCm, this.diameterMm);
		this.moisture = 0.46;
	}

	private static BoneSize classifySize(double lenCm, double diaMm) {
		if (lenCm >= 24.0 && diaMm >= 22.0) return BoneSize.LARGE;
		if (lenCm >= 12.0 && diaMm >= 11.0) return BoneSize.MEDIUM;
		if (lenCm >= 6.5 && diaMm >= 4.5) return BoneSize.SMALL;
		return BoneSize.TINY;
	}

	public double massKg() {
		double volFactor = (lengthCm / 20.0) * Math.pow(diameterMm / 18.0, 2);
		double dry = Math.max(0.02, 0.26 * volFactor * (0.6 + 0.4 * density));
		return dry * (1.0 + moisture * 0.35);
	}

	public boolean canShape(ToolTarget tool) {
		return size.rank >= tool.minimumSize.rank;
	}

	public void stepDrying(double airTempK, double relHumidity, double airSpeedMS, boolean exposedRain, double dtSec) {
		if (exposedRain) {
			moisture = Math.min(0.65, moisture + 0.0015 * dtSec);
			return;
		}
		double eq = 0.05 + relHumidity * 0.14;
		double rate = 0.0006 * (0.4 + airSpeedMS * 0.4) * Math.max(0.25, (airTempK - 268.0) / 30.0);
		moisture = Math.max(0.05, moisture - (moisture - eq) * rate * dtSec);

		// Excessive kiln/hearth baking cracks & embrittles bone!
		if (airTempK > 345.0) {
			double heat = (airTempK - 345.0) / 40.0;
			brittleness = Math.min(1.0, brittleness + 0.0045 * heat * dtSec);
		}
	}

	/** Scores and shapes bone geometry using a knife; too-dry/brittle bone or dull blades chip! */
	public void carveWithKnife(double knifeSharpness, ToolTarget desired) {
		if (desired != ToolTarget.NONE) {
			target = desired;
		}
		// Damp/partially-seasoned bone carves cleanly; bone-dry brittle bone micro-fractures!
		double drynessRisk = moisture < 0.12 ? (0.12 - moisture) * 2.5 : 0.0;
		double dullRisk = Math.max(0.0, 0.65 - knifeSharpness) * 0.45;
		double gain = (0.22 + 0.20 * knifeSharpness) * (moisture > 0.25 ? 1.15 : 0.85);
		cutProgress = Math.min(1.0, cutProgress + gain);
		lengthCm = Math.max(4.0, lengthCm - 0.6);
		workDamage = Math.min(1.0, workDamage + drynessRisk * 0.18 + dullRisk * 0.14 + brittleness * 0.12);
	}

	/** Abrades shaft against rough stone to form smooth tip/edge without shattering. */
	public void abradeWithStone(double stoneGrit) {
		abrasionSmooth = Math.min(1.0, abrasionSmooth + 0.25 * (0.6 + 0.4 * stoneGrit));
		diameterMm = Math.max(2.5, diameterMm - 0.4);
		if (brittleness > 0.55) {
			workDamage = Math.min(1.0, workDamage + 0.15 * brittleness);
		}
	}

	public boolean isReadyToFinish() {
		return cutProgress >= 0.85 && abrasionSmooth >= 0.75;
	}

	/** Computes resulting crafted tool structural integrity (10..100%) from density & workmanship. */
	public int resultingToolIntegrityPct() {
		double q = density * 0.50 + abrasionSmooth * 0.25 + cutProgress * 0.25;
		double penalty = workDamage * 0.60 + brittleness * 0.40;
		return Math.max(12, Math.min(100, (int) Math.round((q - penalty) * 100.0)));
	}

	public NbtCompound toNbt() {
		NbtCompound t = new NbtCompound();
		t.putString("Species", speciesId);
		t.putString("Size", size.id);
		t.putString("Type", boneType.id);
		t.putDouble("Len", lengthCm);
		t.putDouble("Dia", diameterMm);
		t.putDouble("Dens", density);
		t.putDouble("Moist", moisture);
		t.putDouble("Brit", brittleness);
		t.putDouble("Cut", cutProgress);
		t.putDouble("Abr", abrasionSmooth);
		t.putDouble("Dmg", workDamage);
		t.putString("Target", target.id);
		return t;
	}

	public static BoneData fromNbt(NbtCompound t) {
		BoneData b = new BoneData();
		b.speciesId = t.contains("Species") ? t.getString("Species") : "cow";
		b.size = BoneSize.fromId(t.getString("Size"));
		b.boneType = BoneType.fromId(t.getString("Type"));
		if (t.contains("Len")) b.lengthCm = t.getDouble("Len");
		if (t.contains("Dia")) b.diameterMm = t.getDouble("Dia");
		if (t.contains("Dens")) b.density = t.getDouble("Dens");
		if (t.contains("Moist")) b.moisture = t.getDouble("Moist");
		b.brittleness = t.getDouble("Brit");
		b.cutProgress = t.getDouble("Cut");
		b.abrasionSmooth = t.getDouble("Abr");
		b.workDamage = t.getDouble("Dmg");
		b.target = ToolTarget.fromId(t.getString("Target"));
		return b;
	}
}
