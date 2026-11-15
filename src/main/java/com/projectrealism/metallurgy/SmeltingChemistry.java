package com.projectrealism.metallurgy;

import com.projectrealism.thermo.ThermalConstants;

/**
 * Physical combustion and carbothermic ore smelting chemistry engine for player-built furnaces.
 *
 * <p>Simulates fuel oxidation, bellows forced-air surges, CO/CO2 reducing gas ratio, sulfide ore
 * roasting, copper/tin/arsenic/iron oxide reduction, silicate/calcite slag formation, and wall
 * thermal damage. Never uses fixed furnace recipes.</p>
 */
public final class SmeltingChemistry {
	public static final class ChamberState {
		public double temperatureK = ThermalConstants.AMBIENT_DEFAULT_K;
		public double charcoalMassKg = 0.0;
		public double woodMassKg = 0.0;
		public double fluxCalciteKg = 0.0;
		public double bellowsAirSurgeKgS = 0.0;
		public double coConcentration = 0.0; // 0..1 reducing atmosphere strength
		public double o2Concentration = ThermalConstants.AMBIENT_O2;

		/** Active mixed ore charge currently smelting. */
		public OreComposition activeOre = null;
		public double unmeltedMetalMassKg = 0.0;
		public double pooledMetalMassKg = 0.0;
		public AlloyComposition pooledAlloy = AlloyComposition.pureCopper();
		public double slagMassKg = 0.0;
		/** Unreduced or metallic prills trapped in slag when temperature/flux was insufficient. */
		public double trappedMetalInSlagKg = 0.0;
		public double wallThermalStress = 0.0;
	}

	private SmeltingChemistry() {
	}

	public static void pumpBellows(ChamberState chamber, double strokeAirKg) {
		chamber.bellowsAirSurgeKgS = Math.min(0.18, chamber.bellowsAirSurgeKgS + Math.max(0.01, strokeAirKg));
	}

	/**
	 * Advances furnace thermal and chemical reactions by {@code dtSeconds}.
	 */
	public static void step(ChamberState ch, FurnaceStructure.GeometrySummary geom,
							double ambientK, double dtSeconds) {
		if (dtSeconds <= 0.0) return;

		// 1. Air & oxygen supply = natural chimney draft + forced bellows surge
		double airSupplyKgS = geom.draftAirFlowKgS() + ch.bellowsAirSurgeKgS;
		ch.bellowsAirSurgeKgS = Math.max(0.0, ch.bellowsAirSurgeKgS * Math.exp(-dtSeconds * 0.42));
		double o2MassFlowKgS = airSupplyKgS * 0.232; // 23.2% O2 by mass in air

		// 2. Fuel combustion & reducing CO atmosphere
		double heatPowerW = 0.0;
		boolean hasFuel = (ch.charcoalMassKg > 0.005 || ch.woodMassKg > 0.005);
		if (hasFuel && ch.temperatureK >= 520.0) {
			// Charcoal burns hotter & produces strong CO reducing bed when enclosed
			if (ch.charcoalMassKg > 0.005) {
				double maxRate = Math.min(ch.charcoalMassKg / dtSeconds,
						Math.max(1e-4, o2MassFlowKgS * 0.72 * (0.6 + 0.4 * geom.enclosureFraction())));
				double burnKg = maxRate * dtSeconds;
				ch.charcoalMassKg = Math.max(0.0, ch.charcoalMassKg - burnKg);
				// CO builds up when enclosure + charcoal bed depth reduce excess free O2
				double reducingRatio = Math.min(0.92, 0.28 + 0.52 * geom.enclosureFraction()
						+ Math.min(0.25, ch.charcoalMassKg * 0.06)
						- Math.min(0.25, ch.bellowsAirSurgeKgS * 1.4));
				ch.coConcentration = Math.max(0.05, Math.min(0.92, reducingRatio));
				ch.o2Concentration = Math.max(0.02, ThermalConstants.AMBIENT_O2 * (1.0 - ch.coConcentration * 0.78));
				// Effective heat release (partial CO combustion + full char oxidation)
				double effHeatPerKg = ThermalConstants.HEAT_CHAR_COMBUSTION * (0.48 + 0.44 * (1.0 - ch.coConcentration * 0.35));
				heatPowerW += (burnKg / dtSeconds) * effHeatPerKg;
			}
			if (ch.woodMassKg > 0.005) {
				double woodRate = Math.min(ch.woodMassKg / dtSeconds, o2MassFlowKgS * 0.85);
				double burnW = woodRate * dtSeconds;
				ch.woodMassKg = Math.max(0.0, ch.woodMassKg - burnW);
				// Wood converts partly to fresh charcoal inside enclosed reducing zone!
				if (geom.enclosureFraction() >= 0.55) {
					ch.charcoalMassKg += burnW * 0.22;
				}
				heatPowerW += (burnW / dtSeconds) * ThermalConstants.HEAT_VOLATILE_COMBUSTION * 0.58;
				ch.coConcentration = Math.max(ch.coConcentration, 0.18 * geom.enclosureFraction());
			}
		} else {
			ch.coConcentration = Math.max(0.0, ch.coConcentration * Math.exp(-dtSeconds * 0.5));
			ch.o2Concentration = ThermalConstants.AMBIENT_O2;
		}

		// 3. Energy balance: Q_in - wall conduction/radiation losses - exhaust convective loss
		double lossW = geom.wallHeatLossFactorWPerK() * (ch.temperatureK - ambientK)
				+ (geom.draftAirFlowKgS() + ch.bellowsAirSurgeKgS) * 1005.0 * (ch.temperatureK - ambientK) * 0.65;
		double thermalCapJPerK = Math.max(3500.0,
				2800.0 * geom.chamberVolumeCells() + ch.charcoalMassKg * 1050.0 + ch.pooledMetalMassKg * 420.0);
		ch.temperatureK = Math.max(ambientK, Math.min(2050.0,
				ch.temperatureK + ((heatPowerW - lossW) / thermalCapJPerK) * dtSeconds));

		// Wall thermal stress accumulation when chamber exceeds weakest wall safe rating
		if (ch.temperatureK > geom.minWallSafeTempK()) {
			double over = (ch.temperatureK - geom.minWallSafeTempK()) / 350.0;
			ch.wallThermalStress = Math.min(1.0, ch.wallThermalStress + over * 0.03 * dtSeconds);
		}

		// 4. Chemical reactions on active OreComposition
		if (ch.activeOre != null && ch.activeOre.massKg > 0.005) {
			stepOreReactions(ch, dtSeconds);
		}
	}

	private static void stepOreReactions(ChamberState ch, double dtSeconds) {
		OreComposition ore = ch.activeOre;
		double t = ch.temperatureK;
		if (t < 680.0) return;

		// Stage A: Roasting sulfides (Chalcopyrite / Arsenopyrite) requires free O2 and 750..1080 K
		if (ore.sulfurContent > 0.004 && ore.roastedFraction < 1.0 && t >= 740.0) {
			double oxDrive = Math.min(1.0, ch.o2Concentration / 0.12);
			double rate = 0.025 * oxDrive * ((t - 700.0) / 350.0) * dtSeconds;
			double dRoast = Math.min(1.0 - ore.roastedFraction, rate);
			ore.roastedFraction += dRoast;
			ore.sulfurContent = Math.max(0.0, ore.sulfurContent * (1.0 - dRoast * 0.88));
		}

		// Stage B: Carbothermic / CO reduction across all constituent minerals
		MineralSpecies dom = ore.dominantSpecies();
		double minRedK = dom == MineralSpecies.SILICA_GANGUE ? 960.0 : dom.reductionTempK;
		if (t < minRedK) return;

		// Crushed ore reacts dramatically faster due to exposed surface area!
		double surfaceMult = 0.55 + 1.15 * ore.crushedDegree;
		double redDrive = Math.max(0.08, ch.coConcentration * 1.25);
		double tempDrive = Math.min(2.2, 0.35 + (t - minRedK) / 260.0);
		double fracReacted = Math.min(1.0, 0.038 * surfaceMult * redDrive * tempDrive * dtSeconds);

		double chunkReactedKg = ore.massKg * fracReacted;
		if (chunkReactedKg <= 1e-5) return;

		double cuKg = chunkReactedKg * ore.recoverableElementFraction(MineralSpecies.TargetElement.CU);
		double snKg = chunkReactedKg * ore.recoverableElementFraction(MineralSpecies.TargetElement.SN);
		double feKg = chunkReactedKg * ore.recoverableElementFraction(MineralSpecies.TargetElement.FE);
		double asKg = chunkReactedKg * ore.recoverableElementFraction(MineralSpecies.TargetElement.AS);

		// Unroasted sulfur fouls copper/iron product & retains iron in matte; flux helps bind silica gangue cleanly!
		double gangueKg = chunkReactedKg * ore.gangueFraction();
		double fluxAvail = Math.min(ch.fluxCalciteKg, gangueKg * 0.45);
		ch.fluxCalciteKg = Math.max(0.0, ch.fluxCalciteKg - fluxAvail);

		// Silicate slag fluidity: without enough temperature/flux, up to 35% of metal droplets stay trapped in viscous slag!
		double slagFluidity = Math.min(1.0, (t >= 1180.0 ? 0.65 : 0.25)
				+ (fluxAvail > 1e-4 ? 0.28 : 0.0)
				+ (ore.crushedDegree * 0.15));

		// Fe only reduces cleanly into bloomery iron above ~1220 K; below that, Fe stays oxidized inside fayalite slag!
		double feReduced = (t >= 1220.0 && ch.coConcentration >= 0.35) ? feKg * Math.min(0.95, 0.45 + ch.coConcentration * 0.5) : 0.0;
		double feSlag = feKg - feReduced;

		double rawMetalTotal = cuKg + snKg + asKg + feReduced;
		if (rawMetalTotal > 1e-6) {
			double recovered = rawMetalTotal * (0.68 + 0.30 * slagFluidity);
			double trapped = rawMetalTotal - recovered;

			// Bloomery iron retains entrapped silicate slag (~8..22%) until hot-hammered out!
			double entrappedSlag = (feReduced >= cuKg) ? recovered * (0.24 - 0.14 * slagFluidity) : recovered * 0.025 * (1.0 - slagFluidity);
			double residualSulfur = ore.sulfurContent * chunkReactedKg * (1.0 - ore.roastedFraction * 0.85) * 0.28;
			double bloomC = (feReduced >= cuKg && ch.coConcentration > 0.62 && t >= 1340.0)
					? recovered * (0.002 + 0.004 * (ch.coConcentration - 0.5))
					: 0.0;

			AlloyComposition batch = new AlloyComposition(
					cuKg / Math.max(1e-6, rawMetalTotal) * (recovered - entrappedSlag),
					snKg / Math.max(1e-6, rawMetalTotal) * (recovered - entrappedSlag),
					feReduced / Math.max(1e-6, rawMetalTotal) * (recovered - entrappedSlag),
					asKg / Math.max(1e-6, rawMetalTotal) * (recovered - entrappedSlag),
					bloomC,
					residualSulfur,
					entrappedSlag
			);

			ch.pooledAlloy = AlloyComposition.mix(ch.pooledAlloy, ch.pooledMetalMassKg, batch, recovered);
			ch.pooledMetalMassKg += recovered;
			ch.trappedMetalInSlagKg += trapped;
		}

		ch.slagMassKg += gangueKg + fluxAvail + feSlag;
		ore.massKg = Math.max(0.0, ore.massKg - chunkReactedKg);
		if (ore.massKg < 0.005) {
			ch.activeOre = null;
		}
	}
}
