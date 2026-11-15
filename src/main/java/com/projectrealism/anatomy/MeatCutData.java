package com.projectrealism.anatomy;

import net.minecraft.nbt.NbtCompound;

/**
 * Physical properties of an individual anatomical muscle cut or organ, tracking actual mass,
 * fat marbling, connective-tissue toughness, moisture, temperature, thermal cooking, spoilage,
 * and microbial load hook.
 */
public final class MeatCutData {
	public enum CutKind {
		PRIME_LOIN("prime_cut", 0.35, 0.25),
		FATTY_RIB("fatty_cut", 0.65, 0.50),
		TOUGH_HAUNCH("tough_cut", 0.25, 0.85),
		HEART("organ_heart", 0.15, 0.55),
		LIVER("organ_liver", 0.12, 0.15),
		GUT_CASING("organ_gut", 0.18, 0.35);

		public final String id;
		public final double defaultMarbling;
		public final double defaultToughness;

		CutKind(String id, double defaultMarbling, double defaultToughness) {
			this.id = id;
			this.defaultMarbling = defaultMarbling;
			this.defaultToughness = defaultToughness;
		}

		public static CutKind fromId(String id) {
			for (CutKind k : values()) {
				if (k.id.equalsIgnoreCase(id)) return k;
			}
			return PRIME_LOIN;
		}
	}

	public String speciesId = "cow";
	public CutKind kind = CutKind.PRIME_LOIN;
	public double massKg = 0.45;
	public double marblingFat = 0.35;
	public double toughness = 0.35;
	public double moisture = 0.72;
	public double tempK = 295.0;
	public double spoilage = 0.0;        // 0 = fresh, 1 = rotten/inedible
	public double gutContamination = 0.0;
	public double microbialLoad = 0.05;  // future microbiology & foodborne infection hook
	public double cookedFrac = 0.0;
	public double charredFrac = 0.0;

	public MeatCutData() {
	}

	public MeatCutData(AnimalSpecies species, CutKind kind, double massKg, double bodyCondition,
					   double fatigue, double initialTempK, double carcassSpoilage,
					   double gutContam, double microbialLoad) {
		this.speciesId = species.id;
		this.kind = kind;
		this.massKg = Math.max(0.05, massKg);
		this.marblingFat = Math.max(0.05, Math.min(1.0, kind.defaultMarbling * (0.4 + 0.8 * bodyCondition)));
		// Exhausted muscles & older animals toughen meat!
		this.toughness = Math.max(0.10, Math.min(1.0, kind.defaultToughness + fatigue * 0.22));
		this.tempK = initialTempK;
		this.spoilage = Math.max(0.0, Math.min(1.0, carcassSpoilage));
		this.gutContamination = Math.max(0.0, Math.min(1.0, gutContam));
		this.microbialLoad = Math.max(0.0, Math.min(1.0, microbialLoad));
	}

	/** Steps spoilage and cooling/warming for exposed meat cuts using thermodynamic Q10 kinetics. */
	public void stepEnvironment(double airTempK, double relHumidity, double airSpeedMS, boolean smoked, double dtSec) {
		double eqK = airTempK;
		double coolRate = (0.008 + airSpeedMS * 0.004) / Math.max(0.2, massKg);
		tempK += (eqK - tempK) * Math.min(0.4, coolRate * dtSec);

		// Cooking pasteurizes microbes and slows spoilage dramatically
		if (tempK >= 345.0) {
			double cookGain = ((tempK - 345.0) / 40.0) * 0.015 * dtSec;
			cookedFrac = Math.min(1.0, cookedFrac + cookGain);
			microbialLoad = Math.max(0.0, microbialLoad - cookGain * 2.0);
			if (tempK >= 420.0) {
				charredFrac = Math.min(1.0, charredFrac + ((tempK - 420.0) / 60.0) * 0.01 * dtSec);
			}
		}

		// Frozen meat preserves indefinitely; warm moist unpasteurized cuts spoil rapidly
		if (tempK <= 273.15) return;

		double tempFactor = Math.pow(2.0, (Math.min(315.0, tempK) - 277.0) / 10.0); // Q10 = 2.0
		double moistureGate = Math.max(0.15, moisture);
		double organBoost = (kind == CutKind.LIVER || kind == CutKind.GUT_CASING) ? 1.8 : 1.0;
		double cookedShield = 1.0 - cookedFrac * 0.68;
		double smokeShield = smoked ? 0.25 : 1.0;
		double contamBoost = 1.0 + gutContamination * 2.2 + microbialLoad * 1.5;

		double spoilDelta = 0.000018 * tempFactor * moistureGate * organBoost * cookedShield * smokeShield * contamBoost * dtSec;
		spoilage = Math.min(1.0, spoilage + spoilDelta);
		if (tempK >= 285.0 && tempK <= 325.0) {
			microbialLoad = Math.min(1.0, microbialLoad + spoilDelta * 1.4);
		}
	}

	public boolean isSafeToEat() {
		return spoilage < 0.45 && gutContamination < 0.35;
	}

	public NbtCompound toNbt() {
		NbtCompound t = new NbtCompound();
		t.putString("Species", speciesId);
		t.putString("Kind", kind.id);
		t.putDouble("Mass", massKg);
		t.putDouble("Fat", marblingFat);
		t.putDouble("Tough", toughness);
		t.putDouble("Moist", moisture);
		t.putDouble("TempK", tempK);
		t.putDouble("Spoil", spoilage);
		t.putDouble("Contam", gutContamination);
		t.putDouble("Microb", microbialLoad);
		t.putDouble("Cook", cookedFrac);
		t.putDouble("Char", charredFrac);
		return t;
	}

	public static MeatCutData fromNbt(NbtCompound t) {
		MeatCutData m = new MeatCutData();
		m.speciesId = t.contains("Species") ? t.getString("Species") : "cow";
		m.kind = CutKind.fromId(t.getString("Kind"));
		if (t.contains("Mass")) m.massKg = t.getDouble("Mass");
		if (t.contains("Fat")) m.marblingFat = t.getDouble("Fat");
		if (t.contains("Tough")) m.toughness = t.getDouble("Tough");
		if (t.contains("Moist")) m.moisture = t.getDouble("Moist");
		if (t.contains("TempK")) m.tempK = t.getDouble("TempK");
		m.spoilage = t.getDouble("Spoil");
		m.gutContamination = t.getDouble("Contam");
		m.microbialLoad = t.getDouble("Microb");
		m.cookedFrac = t.getDouble("Cook");
		m.charredFrac = t.getDouble("Char");
		return m;
	}
}
