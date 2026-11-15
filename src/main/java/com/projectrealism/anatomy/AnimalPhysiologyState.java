package com.projectrealism.anatomy;

import net.minecraft.nbt.NbtCompound;

/**
 * Living physiological state of an animal reacting continuously to forage availability,
 * hydration, environmental thermal stress, age, physical exertion/fatigue, and localized injuries.
 */
public final class AnimalPhysiologyState {
	public AnimalSpecies species = AnimalSpecies.COW;
	public double ageMaturity = 0.85;    // 0.25 (juvenile) .. 1.0 (prime/mature adult)
	public double bodyCondition = 0.72;  // 0.05 (emaciated) .. 1.0 (well-fed prime)
	public double fatReserveFrac = 0.65; // 0.05 .. 1.0 stored fat relative to species capacity
	public double hydrationFrac = 0.88;  // 0.10 .. 1.0 physiological water balance
	public double fatigueFrac = 0.05;    // 0.0 .. 1.0 muscular exertion/glycogen depletion
	public double bodyTempK = 311.5;     // living core body temperature (~38.3 C)
	public double injuryTorso = 0.0;     // 0..1 trauma reducing meat/hide quality in torso
	public double injuryLimb = 0.0;      // 0..1 trauma slowing gait & bruising haunches
	public double injuryHead = 0.0;
	public double diseaseLoad = 0.0;     // future microbiology / infection hook (0..1)
	public boolean woolShorn = false;

	public AnimalPhysiologyState() {
	}

	public AnimalPhysiologyState(AnimalSpecies species, double ageMaturity, double initialCondition) {
		this.species = species;
		this.ageMaturity = Math.max(0.25, Math.min(1.0, ageMaturity));
		this.bodyCondition = Math.max(0.05, Math.min(1.0, initialCondition));
		this.fatReserveFrac = Math.max(0.05, Math.min(1.0, initialCondition * 0.92));
	}

	/**
	 * Steps live biological physiology in response to environment, grazing, drinking, and exertion.
	 */
	public void stepLivePhysiology(double airTempK, double relHumidity, double moveSpeedMS,
								   boolean nearForage, boolean nearWater, double dtSec) {
		// 1. Age creeps gradually upward across days
		ageMaturity = Math.min(1.0, ageMaturity + 0.000012 * dtSec);

		// 2. Thermoregulatory energy demand: cold increases burn buffered by fat + coat insulation
		double thermoNeutralK = 290.0;
		double insulation = species.coatInsulation * (woolShorn ? 0.30 : 1.0) + fatReserveFrac * 0.45;
		double coldStress = Math.max(0.0, thermoNeutralK - airTempK) / Math.max(0.35, insulation + 0.55);
		double heatStress = Math.max(0.0, airTempK - 301.0) * (1.0 + insulation * 0.5);

		// Small mammals (high surface-area-to-volume ratio) lose heat and dehydrate faster!
		double sizeScale = Math.pow(50.0 / Math.max(1.5, species.liveMassKg), 0.25);

		// 3. Activity & muscular fatigue
		if (moveSpeedMS > 1.8) {
			fatigueFrac = Math.min(1.0, fatigueFrac + 0.0065 * (moveSpeedMS / 4.0) * dtSec);
		} else {
			fatigueFrac = Math.max(0.0, fatigueFrac - 0.0025 * (0.5 + hydrationFrac * 0.5) * dtSec);
		}

		// 4. Water balance: evaporative loss in heat & exertion vs drinking near water
		double sweatEvap = (0.00008 + heatStress * 0.000045 * (1.0 - relHumidity * 0.5)
				+ (moveSpeedMS > 1.8 ? 0.00018 : 0.0)) * sizeScale;
		if (nearWater) {
			hydrationFrac = Math.min(1.0, hydrationFrac + 0.015 * dtSec);
		} else {
			hydrationFrac = Math.max(0.10, hydrationFrac - sweatEvap * dtSec);
		}

		// 5. Energy & fat reserves from grazing vs cold/fatigue drain
		double burn = (0.00003 + coldStress * 0.000018 + fatigueFrac * 0.00005) * sizeScale;
		if (nearForage && hydrationFrac > 0.35 && moveSpeedMS < 1.5) {
			double gain = 0.00032 * dtSec;
			fatReserveFrac = Math.min(1.0, fatReserveFrac + gain);
			bodyCondition = Math.min(1.0, bodyCondition + gain * 0.65);
		} else {
			fatReserveFrac = Math.max(0.05, fatReserveFrac - burn * dtSec);
			if (fatReserveFrac < 0.20) {
				bodyCondition = Math.max(0.08, bodyCondition - burn * 1.4 * dtSec);
			}
		}

		// 6. Core body temp & slow natural wound recovery when fed and hydrated
		double targetBodyK = 311.5 + Math.min(1.8, heatStress * 0.06) - Math.min(2.2, coldStress * 0.03);
		bodyTempK += (targetBodyK - bodyTempK) * 0.02 * dtSec;
		if (bodyCondition > 0.45 && hydrationFrac > 0.45) {
			injuryLimb = Math.max(0.0, injuryLimb - 0.00025 * dtSec);
			injuryTorso = Math.max(0.0, injuryTorso - 0.00025 * dtSec);
			injuryHead = Math.max(0.0, injuryHead - 0.00025 * dtSec);
		}
	}

	/** Movement speed penalty factor (1.0 = healthy prime, down to 0.45 = exhausted/injured/dehydrated). */
	public double movementSpeedFactor() {
		double penalty = injuryLimb * 0.38 + Math.max(0.0, fatigueFrac - 0.5) * 0.28
				+ Math.max(0.0, 0.45 - hydrationFrac) * 0.35 + diseaseLoad * 0.30;
		return Math.max(0.42, Math.min(1.0, 1.0 - penalty));
	}

	public void applyHitTrauma(double fracDamage, double hitHeightFrac) {
		double add = Math.max(0.05, Math.min(0.65, fracDamage));
		if (hitHeightFrac > 0.72) {
			injuryHead = Math.min(1.0, injuryHead + add);
		} else if (hitHeightFrac < 0.35) {
			injuryLimb = Math.min(1.0, injuryLimb + add);
		} else {
			injuryTorso = Math.min(1.0, injuryTorso + add);
		}
	}

	public NbtCompound toNbt() {
		NbtCompound t = new NbtCompound();
		t.putString("Species", species.id);
		t.putDouble("Age", ageMaturity);
		t.putDouble("Cond", bodyCondition);
		t.putDouble("Fat", fatReserveFrac);
		t.putDouble("Hyd", hydrationFrac);
		t.putDouble("Fatig", fatigueFrac);
		t.putDouble("TempK", bodyTempK);
		t.putDouble("InjT", injuryTorso);
		t.putDouble("InjL", injuryLimb);
		t.putDouble("InjH", injuryHead);
		t.putDouble("Dis", diseaseLoad);
		t.putBoolean("Shorn", woolShorn);
		return t;
	}

	public static AnimalPhysiologyState fromNbt(NbtCompound t) {
		AnimalPhysiologyState s = new AnimalPhysiologyState();
		s.species = AnimalSpecies.fromId(t.getString("Species"));
		if (t.contains("Age")) s.ageMaturity = t.getDouble("Age");
		if (t.contains("Cond")) s.bodyCondition = t.getDouble("Cond");
		if (t.contains("Fat")) s.fatReserveFrac = t.getDouble("Fat");
		if (t.contains("Hyd")) s.hydrationFrac = t.getDouble("Hyd");
		s.fatigueFrac = t.getDouble("Fatig");
		if (t.contains("TempK")) s.bodyTempK = t.getDouble("TempK");
		s.injuryTorso = t.getDouble("InjT");
		s.injuryLimb = t.getDouble("InjL");
		s.injuryHead = t.getDouble("InjH");
		s.diseaseLoad = t.getDouble("Dis");
		s.woolShorn = t.getBoolean("Shorn");
		return s;
	}
}
