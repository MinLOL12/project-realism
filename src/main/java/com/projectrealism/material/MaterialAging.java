package com.projectrealism.material;

/**
 * Environmental material aging equations driven by actual moisture, humidity, heat exposure,
 * cyclic wetting/drying, and mechanical stress rather than artificial timers.
 */
public final class MaterialAging {
	private MaterialAging() {
	}

	/**
	 * Steps environmental aging of a leather waterskin in world/inventory air.
	 * Wet-to-dry cycling wears folds/seams; prolonged damp humid storage promotes mildew & softening;
	 * excessive heat stiffens, discolors, and embrittles seams.
	 */
	public static void stepLeatherContainer(WaterskinConstruction c, boolean hasWaterInside,
											double airTempK, double relHumidity, boolean exposedRain, double dtSec) {
		double prevM = c.leatherMoisture;
		double targetM = exposedRain
				? 0.88
				: Math.max(0.06, Math.min(0.82, 0.08 + relHumidity * 0.25 + (hasWaterInside ? 0.14 : 0.0)));
		double rate = exposedRain ? 0.004 : 0.00045;
		c.leatherMoisture = Math.max(0.04, Math.min(0.95, c.leatherMoisture + (targetM - prevM) * rate * dtSec));

		// Stiffness tracks moisture equilibrium + thermal hardening
		double targetStiff = Math.max(0.08, Math.min(0.95, 0.78 - c.leatherMoisture * 0.92 + c.discoloration * 0.35));
		c.leatherStiffness += (targetStiff - c.leatherStiffness) * 0.0012 * dtSec;

		// Cyclic wetting/drying wears leather around folds and stitched seams
		double moistureSwing = Math.abs(c.leatherMoisture - prevM);
		if (moistureSwing > 1.0e-5) {
			for (WaterskinConstruction.SeamSection s : c.seams) {
				s.seamWear = Math.min(0.95, s.seamWear + moistureSwing * 0.045);
			}
		}

		// Damp unventilated mildew/rot when soaking wet in humid air
		if (c.leatherMoisture > 0.70 && relHumidity > 0.80 && airTempK > 286.0) {
			c.discoloration = Math.min(1.0, c.discoloration + 0.00018 * dtSec);
			for (WaterskinConstruction.SeamSection s : c.seams) {
				s.seamWear = Math.min(0.95, s.seamWear + 0.00009 * dtSec);
			}
		}

		// Extreme heat dries and cracks leather
		if (airTempK > 335.0) {
			double heat = (airTempK - 335.0) / 40.0;
			c.discoloration = Math.min(1.0, c.discoloration + 0.0015 * heat * dtSec);
			c.leatherStiffness = Math.min(1.0, c.leatherStiffness + 0.002 * heat * dtSec);
			for (WaterskinConstruction.SeamSection s : c.seams) {
				s.seamWear = Math.min(0.95, s.seamWear + 0.0008 * heat * dtSec);
			}
		}
	}

	/**
	 * Plant fiber / cordage state: absorbs ambient/rain moisture, gains wet mass, and loses tensile
	 * strength under prolonged wet microbial rot or repeated high-tension abrasion.
	 */
	public static final class FiberState {
		public double moisture = 0.12;
		public double strength = 1.0; // 0 = frayed weak, 1 = crisp strong
		public double fray = 0.0;

		public void stepEnvironment(double relHumidity, boolean exposedRain, boolean elevatedRack, double dtSec) {
			double target = exposedRain ? 0.92 : (0.05 + relHumidity * (elevatedRack ? 0.22 : 0.42));
			moisture = Math.max(0.03, Math.min(0.95, moisture + (target - moisture) * 0.002 * dtSec));
			if (moisture > 0.65 && !elevatedRack) {
				fray = Math.min(1.0, fray + 0.00025 * dtSec);
				strength = Math.max(0.15, strength - 0.00022 * dtSec);
			}
		}

		public void applyTensionPull(double tensionForce) {
			double wear = Math.max(0.0, tensionForce - 0.55) * 0.08 + 0.012;
			fray = Math.min(1.0, fray + wear);
			strength = Math.max(0.15, strength - wear * 0.6);
		}
	}

	/**
	 * Wood moisture-gradient dimensional shrinkage, warping, and checking crack physics.
	 */
	public static final class WoodCondition {
		public double surfaceMoisture = 0.55;
		public double coreMoisture = 0.60;
		public double warpSeverity = 0.0;
		public double crackSeverity = 0.0;

		public void stepDrying(double airTempK, double relHumidity, double airSpeedMS, boolean elevatedRack, double dtSec) {
			double eq = 0.08 + relHumidity * (elevatedRack ? 0.16 : 0.28);
			double envSpeed = Math.max(0.2, (airTempK - 265.0) / 30.0) * (0.4 + airSpeedMS * 0.55);
			surfaceMoisture = Math.max(0.04, Math.min(0.95,
					surfaceMoisture - (surfaceMoisture - eq) * 0.0014 * envSpeed * dtSec));
			// Internal diffusion from core to surface
			double grad = coreMoisture - surfaceMoisture;
			double diff = grad * 0.0005 * dtSec;
			coreMoisture = Math.max(0.05, Math.min(0.95, coreMoisture - diff));

			// Severe moisture gradient drives warping and splitting checks!
			double absGrad = Math.abs(grad);
			if (absGrad > 0.14) {
				double stress = (absGrad - 0.14);
				warpSeverity = Math.min(1.0, warpSeverity + stress * 0.0028 * dtSec);
				if (absGrad > 0.25 && airTempK > 325.0) {
					crackSeverity = Math.min(1.0, crackSeverity + (absGrad - 0.25) * 0.0045 * dtSec);
				}
			}
		}
	}

	/**
	 * Gourd drying & shell hardening: slowly loses moisture into a tough hollow shell, or cracks
	 * under rapid radiant heat desiccation or physical shock impacts.
	 */
	public static final class GourdState {
		public double moisture = 0.78;
		public double shellHardness = 0.20;
		public double crackSeverity = 0.0;
		public double sealLevel = 0.0; // pitch/wax patching over cracks

		public void stepDrying(double airTempK, double relHumidity, double airSpeedMS, boolean exposedRain, double dtSec) {
			if (exposedRain) {
				moisture = Math.min(0.90, moisture + 0.002 * dtSec);
				return;
			}
			double eq = 0.06 + relHumidity * 0.15;
			double rate = 0.00045 * (0.4 + airSpeedMS * 0.45) * Math.max(0.25, (airTempK - 268.0) / 28.0);
			moisture = Math.max(0.06, moisture - (moisture - eq) * rate * dtSec);
			// Shell hardens cleanly as gourd seasons slowly below 25% moisture
			if (moisture < 0.35) {
				shellHardness = Math.min(1.0, shellHardness + (0.35 - moisture) * 0.0012 * dtSec);
			}
			// Rapid scorching (> 338 K) before seasoned cracks the rind!
			if (airTempK > 338.0 && moisture > 0.18) {
				crackSeverity = Math.min(1.0, crackSeverity + ((airTempK - 338.0) / 40.0) * 0.004 * dtSec);
			}
		}

		public boolean isCuredForCarving() {
			return moisture <= 0.18 && shellHardness >= 0.65 && crackSeverity < 0.75;
		}

		public double leakMlPerSec(int volumeMl, int capacityMl) {
			if (volumeMl <= 0 || crackSeverity <= 0.05) return 0.0;
			double effectiveCrack = Math.max(0.0, crackSeverity * (1.0 - sealLevel * 0.90));
			if (effectiveCrack <= 0.05) return 0.0;
			return effectiveCrack * (volumeMl / (double) Math.max(100, capacityMl)) * 12.0;
		}
	}
}
