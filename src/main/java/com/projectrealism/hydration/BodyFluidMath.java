package com.projectrealism.hydration;

import static com.projectrealism.hydration.PhysiologicalConstants.*;

/**
 * Pure physiological fluid-compartment, electrolyte, thermoregulatory sweating, renal regulation,
 * gastrointestinal absorption, and thirst perception kernel. Zero Minecraft dependencies so all
 * physiological laws are directly testable via JUnit 5.
 */
public final class BodyFluidMath {
	private BodyFluidMath() {
	}

	public static final class BodyFluidState {
		// Fluid compartments (Liters; 1 L ~ 1 kg water)
		public double icfLiters = BASE_ICF_LITERS;
		public double isfLiters = BASE_ISF_LITERS;
		public double plasmaLiters = BASE_PLASMA_LITERS;
		public double stomachLiters = 0.0;
		public double intestineLiters = 0.0;
		public double bladderLiters = 0.08;

		// Extracellular electrolytes & solutes (mmol shared across ISF + Plasma)
		public double ecfNaMmol = BASE_PLASMA_NA_MM * BASE_ECF_LITERS;
		public double ecfKMmol = BASE_PLASMA_K_MM * BASE_ECF_LITERS;
		public double ecfClMmol = BASE_PLASMA_CL_MM * BASE_ECF_LITERS;
		public double ecfOtherOsmMmol = BASE_ECF_OTHER_OSM_MM * BASE_ECF_LITERS;

		// Intracellular solutes (mmol)
		public double icfKMmol = BASE_ICF_K_MM * BASE_ICF_LITERS;
		public double icfOtherOsmMmol = BASE_ICF_OTHER_OSM_MM * BASE_ICF_LITERS;

		// Gastrointestinal tract solutes & quality carryover
		public double stomachNaMmol = 0.0;
		public double stomachKMmol = 0.0;
		public double stomachClMmol = 0.0;
		public double stomachOtherOsmMmol = 0.0;
		public double stomachTempK = NORMAL_CORE_TEMP_K;
		public double stomachMicrobes = 0.0;
		public double stomachChemicals = 0.0;

		public double intestineNaMmol = 0.0;
		public double intestineKMmol = 0.0;
		public double intestineClMmol = 0.0;
		public double intestineOtherOsmMmol = 0.0;
		public double intestineMicrobes = 0.0;
		public double intestineChemicals = 0.0;

		// Thermoregulation
		public double bodyCoreTempK = NORMAL_CORE_TEMP_K;
		public double skinTempK = NORMAL_SKIN_TEMP_K;
		public double lastSweatRateKgS = 0.0;
		public double lastSweatEvapRateKgS = 0.0;
		public double lastSweatDripRateKgS = 0.0;
		public double lastEvapCoolingW = 0.0;

		// Endocrine & renal diagnostics
		public double adhLevel = 0.35;
		public double aldosteroneLevel = 0.35;
		public double lastUrineRateLS = 0.000018;
		public double urineOsmolalityMOsm = 450.0;

		// Perceived thirst & satiety signals
		public double oralSatiety = 0.0;
		public double perceivedThirst = 0.0;

		// Cumulative pathogen & chemical absorption counters (for status effects / future disease model)
		public double pendingMicrobeLoad = 0.0;
		public double pendingChemicalLoad = 0.0;
		public double gastricDiscomfort = 0.0;

		public double ecfLiters() {
			return Math.max(2.0, isfLiters + plasmaLiters);
		}

		public double totalBodyWaterLiters() {
			return icfLiters + isfLiters + plasmaLiters;
		}

		public double bodyWaterFraction(double bodyMassKg) {
			double dryMass = Math.max(15.0, bodyMassKg - REFERENCE_TBW_LITERS);
			double totalMass = dryMass + totalBodyWaterLiters();
			return totalBodyWaterLiters() / Math.max(30.0, totalMass);
		}

		public double plasmaNaMm() {
			return ecfNaMmol / ecfLiters();
		}

		public double plasmaKMm() {
			return ecfKMmol / ecfLiters();
		}

		public double plasmaClMm() {
			return ecfClMmol / ecfLiters();
		}

		public double ecfOsmolalityMOsm() {
			return (ecfNaMmol + ecfKMmol + ecfClMmol + ecfOtherOsmMmol) / ecfLiters();
		}

		public double icfOsmolalityMOsm() {
			return (icfKMmol + icfOtherOsmMmol) / Math.max(5.0, icfLiters);
		}

		public double digestiveWaterLiters() {
			return stomachLiters + intestineLiters;
		}
	}

	/** Environmental and behavioral inputs driving thermoregulation, sweat evaporation, and exertion. */
	public record EnvironmentInput(
			double airTempK,
			double relativeHumidity,
			double airSpeedMS,
			double radiantLoadW,
			double exertionMultiplier, // 1.0 resting, 2.0 walking, 4.0+ sprinting / heavy mining
			double carriedMassKg,
			double clothingInsulation, // 0.25 bare skin .. 0.85 full heavy armor
			boolean inWater,
			double waterTempK
	) {
		public static final EnvironmentInput COMFORTABLE_REST = new EnvironmentInput(
				293.15, 0.50, 0.35, 0.0, 1.0, 0.0, 0.75, false, 288.15);
	}

	/** Introduces ingested water (with its full chemical/microbial/thermal profile) into the stomach. */
	public static void ingestWater(BodyFluidState s, double volumeLiters, WaterQuality quality) {
		if (volumeLiters <= 0.0) {
			return;
		}
		WaterQuality q = quality != null ? quality.clamped() : WaterQuality.RIVER_WATER;
		double prevVol = s.stomachLiters;
		double newVol = prevVol + volumeLiters;
		s.stomachTempK = (s.stomachTempK * prevVol + q.tempK() * volumeLiters) / Math.max(0.001, newVol);
		s.stomachLiters = newVol;
		s.stomachNaMmol += q.sodiumMm() * volumeLiters;
		s.stomachKMmol += q.potassiumMm() * volumeLiters;
		s.stomachClMmol += q.chlorideMm() * volumeLiters;
		s.stomachOtherOsmMmol += Math.max(0.0, (q.mineralMgPerL() / 100.0) * volumeLiters);
		s.stomachMicrobes += q.microbialLoad() * volumeLiters;
		s.stomachChemicals += q.chemicalLoad() * volumeLiters;

		// Drinking activates oropharyngeal moisture receptors right away (before plasma recovers!)
		double satietyGain = Math.min(0.85, volumeLiters * 2.2);
		s.oralSatiety = Math.min(1.0, s.oralSatiety + satietyGain);
		if (s.stomachLiters > 1.25) {
			s.gastricDiscomfort = Math.min(1.0, (s.stomachLiters - 1.20) / 0.8);
		}
	}

	/** Introduces water & electrolytes contained in eaten food into the stomach. */
	public static void ingestFood(BodyFluidState s, double waterLiters,
								  double naMmol, double kMmol, double clMmol) {
		if (waterLiters > 0.0) {
			double prev = s.stomachLiters;
			double next = prev + waterLiters;
			s.stomachTempK = (s.stomachTempK * prev + s.bodyCoreTempK * waterLiters) / Math.max(0.001, next);
			s.stomachLiters = next;
		}
		s.stomachNaMmol += Math.max(0.0, naMmol);
		s.stomachKMmol += Math.max(0.0, kMmol);
		s.stomachClMmol += Math.max(0.0, clMmol);
		s.oralSatiety = Math.min(1.0, s.oralSatiety + Math.min(0.3, waterLiters * 1.2));
	}

	/** Magnus saturation vapor pressure (kPa) at temperature {@code tempK}. */
	public static double saturationVaporPressureKpa(double tempK) {
		double tC = tempK - 273.15;
		return 0.61094 * Math.exp((17.625 * tC) / Math.max(30.0, tC + 243.04));
	}

	/** Advances complete body-fluid, GI, sweat, thermal, electrolyte, renal, and thirst state over {@code dtSeconds}. */
	public static void step(BodyFluidState s, EnvironmentInput env, double dtSeconds) {
		double dt = Math.max(0.05, Math.min(15.0, dtSeconds));

		// Decay oral satiety & discomfort naturally (~45-60 s)
		s.oralSatiety = Math.max(0.0, s.oralSatiety * Math.exp(-dt / 45.0));
		s.gastricDiscomfort = Math.max(0.0, s.gastricDiscomfort * Math.exp(-dt / 90.0));

		// ------------------------------------------------------------------ 1. Thermoregulation & Sweat
		double loadPenalty = 1.0 + Math.max(0.0, env.carriedMassKg()) / 55.0;
		double metabolicW = BASAL_METABOLIC_W * Math.max(0.85, env.exertionMultiplier() * loadPenalty);

		// Vasomotor skin blood flow increases dramatically when core is warm (blunted if plasma volume is critically low)
		double plasmaReserve = clamp((s.plasmaLiters - 1.9) / 1.1, 0.25, 1.15);
		double vasodilate = clamp((s.bodyCoreTempK - 309.5) / 1.8, 0.0, 2.5) * plasmaReserve;
		double skinConductanceWK = 17.5 * (0.55 + 0.45 * plasmaReserve + vasodilate * 1.6);
		double qCoreToSkinW = skinConductanceWK * (s.bodyCoreTempK - s.skinTempK);

		// Thermal exchange with stomach fluid (cold water cools core, scalding water warms it)
		double qStomachW = 0.0;
		if (s.stomachLiters > 0.005) {
			qStomachW = s.stomachLiters * 4184.0 * (s.stomachTempK - s.bodyCoreTempK) * 0.015;
			s.stomachTempK += (-qStomachW * dt) / Math.max(40.0, s.stomachLiters * 4184.0);
		}

		// Skin heat exchange using series clothing resistance R_clo + 1 / (h_conv + h_rad)
		double clo = clamp(env.clothingInsulation(), 0.15, 1.50);
		double vaporPerm = 1.0 - Math.min(0.65, clo * 0.38);

		double qEnvDryW;
		if (env.inWater()) {
			qEnvDryW = 210.0 * SKIN_AREA_M2 * 0.75 * (env.waterTempK() - s.skinTempK);
		} else {
			double hConv = 4.2 + 9.5 * Math.pow(Math.max(0.0, env.airSpeedMS()), 0.65);
			double hRad = 4.9;
			double uDry = 1.0 / (clo * 0.36 + 1.0 / (hConv + hRad));
			double qAir = uDry * SKIN_AREA_M2 * (env.airTempK() - s.skinTempK);
			qEnvDryW = qAir + env.radiantLoadW() * (0.45 + 0.55 * (uDry / (hConv + hRad)));
		}

		// Passive respiratory heat + insensible pure water loss
		double respLossW = 11.0 + Math.max(0.0, (310.15 - env.airTempK()) * 0.22)
				+ (metabolicW - BASAL_METABOLIC_W) * 0.04;
		double insensibleWaterKgS = (5.5e-6 + 1.5e-6 * Math.max(0.0, 1.0 - env.relativeHumidity()))
				* (0.85 + 0.15 * env.exertionMultiplier());

		// Sweat gland secretion driven by core & skin elevation plus exertion
		double sweatDrive = Math.max(0.0, (s.bodyCoreTempK - 310.28) * 1.4)
				+ Math.max(0.0, (s.skinTempK - 307.1) * 0.25)
				+ Math.max(0.0, env.exertionMultiplier() - 1.8) * 0.08;
		double maxSweatKgS = 0.00058 * clamp((s.plasmaLiters - 2.0) / 0.85, 0.10, 1.05);
		double sweatKgS = env.inWater() ? 0.0 : Math.min(maxSweatKgS, sweatDrive * 0.00028);

		// Maximum evaporative capacity governed by vapor pressure deficit & airflow
		double eSkin = saturationVaporPressureKpa(s.skinTempK) * 0.94;
		double eAir = clamp(env.relativeHumidity(), 0.02, 0.99) * saturationVaporPressureKpa(env.airTempK());
		double deltaE = Math.max(0.0, eSkin - eAir);
		double hEvap = (6.0 + 18.0 * Math.pow(Math.max(0.0, env.airSpeedMS()), 0.65)) * 16.5;
		double maxEvapW = hEvap * SKIN_AREA_M2 * vaporPerm * deltaE;
		double maxEvapKgS = maxEvapW / SWEAT_LATENT_VAPORIZATION;

		double evapKgS = Math.min(sweatKgS, maxEvapKgS);
		double dripKgS = Math.max(0.0, sweatKgS - evapKgS);
		double qEvapW = evapKgS * SWEAT_LATENT_VAPORIZATION;
		double qDripCoolingW = dripKgS * SWEAT_LATENT_VAPORIZATION * 0.05; // Dripping sweat removes water/salt with very little cooling!

		s.lastSweatRateKgS = sweatKgS;
		s.lastSweatEvapRateKgS = evapKgS;
		s.lastSweatDripRateKgS = dripKgS;
		s.lastEvapCoolingW = qEvapW;

		// Update Core & Skin temperatures (Q = m * cp * dT)
		double coreCapJK = (REFERENCE_BODY_MASS_KG * 0.85) * BODY_SPECIFIC_HEAT;
		double skinCapJK = (REFERENCE_BODY_MASS_KG * 0.15) * BODY_SPECIFIC_HEAT;
		s.bodyCoreTempK = clamp(s.bodyCoreTempK + ((metabolicW - qCoreToSkinW - respLossW + qStomachW) * dt) / coreCapJK,
				298.0, 318.0);
		s.skinTempK = clamp(s.skinTempK + ((qCoreToSkinW + qEnvDryW - qEvapW - qDripCoolingW) * dt) / skinCapJK,
				285.0, 322.0);

		// Deduct insensible pure water & sweat water/electrolytes from ECF (Plasma/ISF)
		double insensibleLossL = Math.min(s.plasmaLiters * 0.1, insensibleWaterKgS * dt);
		double sweatLossL = Math.min(s.plasmaLiters * 0.15, sweatKgS * dt);
		s.plasmaLiters = Math.max(1.2, s.plasmaLiters - insensibleLossL - sweatLossL);
		s.ecfNaMmol = Math.max(100.0, s.ecfNaMmol - sweatLossL * SWEAT_NA_MM);
		s.ecfKMmol = Math.max(5.0, s.ecfKMmol - sweatLossL * SWEAT_K_MM);
		s.ecfClMmol = Math.max(80.0, s.ecfClMmol - sweatLossL * SWEAT_CL_MM);

		// ------------------------------------------------------------------ 2. Stomach -> Intestine Emptying
		if (s.stomachLiters > 1.0e-5) {
			double stOsm = (s.stomachNaMmol + s.stomachKMmol + s.stomachClMmol + s.stomachOtherOsmMmol)
					/ Math.max(0.01, s.stomachLiters);
			double osmSlow = 1.0 / (1.0 + Math.max(0.0, (stOsm - 300.0) / 900.0));
			// Bounded physiological emptying rate (excess rapid intake cannot instantly enter blood!)
			double emptyRateLS = Math.min(0.00058, (0.00018 + 0.0009 * s.stomachLiters) * osmSlow);
			double movedL = Math.min(s.stomachLiters, emptyRateLS * dt);
			double frac = movedL / Math.max(1.0e-6, s.stomachLiters);

			s.stomachLiters -= movedL;
			s.intestineLiters += movedL;

			double movedNa = s.stomachNaMmol * frac;
			double movedK = s.stomachKMmol * frac;
			double movedCl = s.stomachClMmol * frac;
			double movedOth = s.stomachOtherOsmMmol * frac;
			double movedMic = s.stomachMicrobes * frac;
			double movedChem = s.stomachChemicals * frac;

			s.stomachNaMmol -= movedNa;
			s.stomachKMmol -= movedK;
			s.stomachClMmol -= movedCl;
			s.stomachOtherOsmMmol -= movedOth;
			s.stomachMicrobes -= movedMic;
			s.stomachChemicals -= movedChem;

			s.intestineNaMmol += movedNa;
			s.intestineKMmol += movedK;
			s.intestineClMmol += movedCl;
			s.intestineOtherOsmMmol += movedOth;
			s.intestineMicrobes += movedMic;
			s.intestineChemicals += movedChem;
		}

		// ------------------------------------------------------------------ 3. Intestinal Absorption / Osmotic Shift
		if (s.intestineLiters > 1.0e-5) {
			double ecfOsm = s.ecfOsmolalityMOsm();
			double gutOsm = (s.intestineNaMmol + s.intestineKMmol + s.intestineClMmol + s.intestineOtherOsmMmol)
					/ Math.max(0.01, s.intestineLiters);

			if (gutOsm > ecfOsm + 45.0) {
				// Hypertonic lumen (e.g. ocean seawater!): pulls water OUT of blood plasma into the gut!
				double osmoticGrad = (gutOsm - ecfOsm) / 300.0;
				double secRateLS = Math.min(0.00065, 0.00028 * osmoticGrad * (0.4 + s.intestineLiters));
				double pulledL = Math.min(s.plasmaLiters * 0.08, secRateLS * dt);
				s.plasmaLiters = Math.max(1.2, s.plasmaLiters - pulledL);
				s.intestineLiters += pulledL;

				// Dissolved salt still diffuses across mucosa into blood, worsening hypernatremia
				double diffRate = Math.min(0.35, 0.009 * dt);
				double dNa = s.intestineNaMmol * diffRate;
				double dCl = s.intestineClMmol * diffRate;
				double dK = s.intestineKMmol * diffRate;
				s.intestineNaMmol -= dNa;
				s.intestineClMmol -= dCl;
				s.intestineKMmol -= dK;
				s.ecfNaMmol += dNa;
				s.ecfClMmol += dCl;
				s.ecfKMmol += dK;

				// Clearance of excess hypertonic diarrhea fluid
				if (s.intestineLiters > 0.12) {
					double purgeFrac = Math.min(0.4, 0.0045 * dt);
					s.intestineLiters *= (1.0 - purgeFrac);
					s.intestineNaMmol *= (1.0 - purgeFrac);
					s.intestineKMmol *= (1.0 - purgeFrac);
					s.intestineClMmol *= (1.0 - purgeFrac);
					s.intestineOtherOsmMmol *= (1.0 - purgeFrac);
					s.intestineMicrobes *= (1.0 - purgeFrac);
					s.intestineChemicals *= (1.0 - purgeFrac);
				}
			} else {
				// Normal water & solute absorption into blood plasma (bounded rate prevents instant chugging)
				double absRateLS = Math.min(0.00042, 0.00014 + 0.0007 * s.intestineLiters);
				double absorbedL = Math.min(s.intestineLiters, absRateLS * dt);
				double frac = absorbedL / Math.max(1.0e-6, s.intestineLiters);

				s.intestineLiters -= absorbedL;
				s.plasmaLiters += absorbedL;

				double dNa = s.intestineNaMmol * frac;
				double dK = s.intestineKMmol * frac;
				double dCl = s.intestineClMmol * frac;
				double dOth = s.intestineOtherOsmMmol * frac;
				double dMic = s.intestineMicrobes * frac;
				double dChem = s.intestineChemicals * frac;

				s.intestineNaMmol -= dNa;
				s.intestineKMmol -= dK;
				s.intestineClMmol -= dCl;
				s.intestineOtherOsmMmol -= dOth;
				s.intestineMicrobes -= dMic;
				s.intestineChemicals -= dChem;

				s.ecfNaMmol += dNa;
				s.ecfKMmol += dK;
				s.ecfClMmol += dCl;
				s.ecfOtherOsmMmol += dOth * 0.4;
				s.pendingMicrobeLoad = Math.min(5.0, s.pendingMicrobeLoad + dMic);
				s.pendingChemicalLoad = Math.min(5.0, s.pendingChemicalLoad + dChem);
			}
		}

		// ------------------------------------------------------------------ 4. Inter-Compartment Exchanges
		// 4a. Starling capillary equilibration between Blood Plasma and Interstitial Fluid
		double ecfTot = s.ecfLiters();
		double targetPlasma = ecfTot * TARGET_PLASMA_ECF_FRACTION;
		double plasmaShift = (targetPlasma - s.plasmaLiters) * Math.min(0.65, 0.012 * dt);
		s.plasmaLiters = Math.max(1.0, s.plasmaLiters + plasmaShift);
		s.isfLiters = Math.max(1.0, s.isfLiters - plasmaShift);

		// 4b. Osmotic water shift across cell membranes (ICF <-> ECF)
		double osmECF = s.ecfOsmolalityMOsm();
		double osmICF = s.icfOsmolalityMOsm();
		double osmDiffRatio = (osmECF - osmICF) / BASE_OSMOLALITY_MOSM;
		// Positive = hypertonic ECF pulls water OUT of ICF into ISF/Plasma; negative = cellular swelling!
		double icfToEcfL = clamp(osmDiffRatio * 0.008 * s.icfLiters * dt,
				-s.isfLiters * 0.15, s.icfLiters * 0.15);
		s.icfLiters = Math.max(8.0, s.icfLiters - icfToEcfL);
		s.isfLiters = Math.max(1.0, s.isfLiters + icfToEcfL);

		// 4c. Na+/K+ pump buffering of intracellular vs extracellular K+
		double plasmaK = s.plasmaKMm();
		double kShiftMmol = (plasmaK - BASE_PLASMA_K_MM) * s.ecfLiters() * Math.min(0.35, 0.0025 * dt);
		s.ecfKMmol = Math.max(2.0, s.ecfKMmol - kShiftMmol);
		s.icfKMmol = Math.max(500.0, s.icfKMmol + kShiftMmol);

		// ------------------------------------------------------------------ 5. Kidney Regulation & Urine Production
		double plasmaNa = s.plasmaNaMm();
		plasmaK = s.plasmaKMm();
		double plasmaCl = s.plasmaClMm();
		osmECF = s.ecfOsmolalityMOsm();

		double gfrFactor = clamp((s.plasmaLiters - 1.85) / 1.15, 0.12, 1.45);
		s.adhLevel = clamp(
				0.35 + (osmECF - 287.5) * 0.14 + Math.max(0.0, 2.95 - s.plasmaLiters) * 1.45,
				0.02, 3.0);
		s.aldosteroneLevel = clamp(
				0.35 + Math.max(0.0, 138.0 - plasmaNa) * 0.16
						+ Math.max(0.0, 2.95 - s.plasmaLiters) * 1.35
						+ Math.max(0.0, plasmaK - 4.6) * 0.65
						- Math.max(0.0, plasmaNa - 143.0) * 0.08,
				0.03, 3.0);

		// Urine flow drops to ~0.0000045 L/s when dehydrated (high ADH), rises to ~0.00022 L/s during water diuresis;
		// hypernatremic salt load without volume depletion also drives obligatory osmotic solute diuresis
		double soluteDiuresisLS = Math.max(0.0, (plasmaNa - 142.0) * 0.000018)
				* clamp((s.plasmaLiters - 2.65) / 0.35, 0.0, 1.0);
		double rawUrineLS = 0.000032 * gfrFactor / (0.14 + s.adhLevel * 1.75) + soluteDiuresisLS;
		double urineFlowLS = clamp(rawUrineLS, 0.0000045, 0.00024);
		double urineL = Math.min(s.plasmaLiters * 0.08, urineFlowLS * dt);
		s.lastUrineRateLS = urineFlowLS;

		// Urine concentration responds sharply to ADH
		s.urineOsmolalityMOsm = clamp(65.0 + s.adhLevel * 385.0, 60.0, 1200.0);

		// Renal electrolyte excretion regulated by aldosterone & plasma levels
		double urineNaMm = clamp((85.0 + Math.max(0.0, plasmaNa - 138.0) * 14.0) / (0.35 + s.aldosteroneLevel * 1.8),
				12.0, 240.0);
		double urineKMm = clamp(32.0 * (0.45 + s.aldosteroneLevel * 1.4) + Math.max(0.0, plasmaK - 4.2) * 28.0,
				8.0, 160.0);
		double urineClMm = clamp(urineNaMm * 0.85 + urineKMm * 0.35, 12.0, 250.0);

		s.plasmaLiters = Math.max(1.2, s.plasmaLiters - urineL);
		s.bladderLiters = Math.min(0.95, s.bladderLiters + urineL);
		s.ecfNaMmol = Math.max(100.0, s.ecfNaMmol - urineL * urineNaMm);
		s.ecfKMmol = Math.max(5.0, s.ecfKMmol - urineL * urineKMm);
		s.ecfClMmol = Math.max(80.0, s.ecfClMmol - urineL * urineClMm);

		// Passive decay of pending gut toxin/microbe shock counters
		s.pendingMicrobeLoad = Math.max(0.0, s.pendingMicrobeLoad * Math.exp(-dt / 600.0));
		s.pendingChemicalLoad = Math.max(0.0, s.pendingChemicalLoad * Math.exp(-dt / 450.0));

		// ------------------------------------------------------------------ 6. Perceived Thirst Signal
		double finalOsm = s.ecfOsmolalityMOsm();
		double osmDrive = clamp((finalOsm - 287.5) / 14.0, 0.0, 1.0);
		double volDrive = clamp((2.96 - s.plasmaLiters) / 0.45, 0.0, 1.0);
		double rawThirst = Math.max(osmDrive, 0.65 * osmDrive + 0.55 * volDrive);
		s.perceivedThirst = clamp(rawThirst - s.oralSatiety * 0.45, 0.0, 1.0);
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
