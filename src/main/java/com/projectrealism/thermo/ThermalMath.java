package com.projectrealism.thermo;

/**
 * Pure thermodynamic, heat-transfer, moisture-diffusion, combustion-chemistry, thermal-stress,
 * and cooking physics math. Completely independent of Minecraft registries so it can be verified
 * directly by unit tests as well as run live on the server.
 */
public final class ThermalMath {
	private ThermalMath() {
	}

	/**
	 * Mutable two-zone (outer shell + interior core) thermodynamic state of a physical material body.
	 */
	public static final class BodyState {
		public double surfaceTempK;
		public double coreTempK;
		public double surfaceMoistureKg;
		public double coreMoistureKg;
		public double dryWoodMassKg;
		public double charMassKg;
		public double ashMassKg;
		public double initialDryMassKg;
		public double maxCoreTempK;
		public double stressDamage; // 0..1+ (thermal shock / cracking / softening)
		public boolean flaming;
		public double lastVolatileRateKgS;
		public double lastCharRateKgS;
		public double lastHeatReleasedW;
		public double lastO2ConsumedKgS;
		public double lastCoProducedKgS;
		public double lastCo2ProducedKgS;
		public double lastSmokeProduced;
		public double lastSteamProducedKgS;

		public BodyState(double ambientK, double dryMassKg, double surfaceMoistRatio, double coreMoistRatio) {
			this.surfaceTempK = ambientK;
			this.coreTempK = ambientK;
			this.initialDryMassKg = Math.max(0.05, dryMassKg);
			this.dryWoodMassKg = this.initialDryMassKg;
			// Shell represents ~30% of solid volume, core ~70%
			this.surfaceMoistureKg = Math.max(0.0, this.initialDryMassKg * 0.30 * surfaceMoistRatio);
			this.coreMoistureKg = Math.max(0.0, this.initialDryMassKg * 0.70 * coreMoistRatio);
			this.maxCoreTempK = ambientK;
		}

		public double totalMoistureRatio() {
			double base = Math.max(0.05, dryWoodMassKg + charMassKg * 0.25);
			return (surfaceMoistureKg + coreMoistureKg) / base;
		}

		public double surfaceMoistureRatio() {
			double shellDry = Math.max(0.02, (dryWoodMassKg + charMassKg) * 0.30);
			return surfaceMoistureKg / shellDry;
		}

		public double coreMoistureRatio() {
			double coreDry = Math.max(0.03, (dryWoodMassKg + charMassKg) * 0.70);
			return coreMoistureKg / coreDry;
		}

		public double remainingSolidMassKg() {
			return dryWoodMassKg + charMassKg + ashMassKg;
		}

		public double structuralIntegrity() {
			double solidRatio = Math.min(1.0, (dryWoodMassKg + charMassKg * 0.35) / Math.max(0.05, initialDryMassKg));
			return Math.max(0.0, solidRatio * (1.0 - Math.min(0.95, stressDamage)));
		}
	}

	/**
	 * Effective heat capacity (J/K) of the surface shell or interior core:
	 * {@code C = m_solid * c_p_solid + m_water * c_p_water}.
	 */
	public static double heatCapacityJPerK(double solidMassKg, double specificHeat, double waterMassKg) {
		return Math.max(25.0, solidMassKg * specificHeat + waterMassKg * ThermalConstants.WATER_SPECIFIC_HEAT);
	}

	/**
	 * Energy required to change temperature by {@code deltaT}: {@code Q = m * c_p * dT}.
	 */
	public static double energyForTempChange(double massKg, double specificHeat, double deltaT) {
		return massKg * specificHeat * deltaT;
	}

	/**
	 * Stefan-Boltzmann radiative power emitted (W) from a hot surface to environment:
	 * {@code P = epsilon * sigma * A * (T^4 - T_env^4)}.
	 */
	public static double radiativePowerWatts(double emissivity, double areaM2, double tempK, double envTempK) {
		double t1 = Math.max(1.0, tempK);
		double t0 = Math.max(1.0, envTempK);
		double t1_4 = t1 * t1 * t1 * t1;
		double t0_4 = t0 * t0 * t0 * t0;
		return emissivity * ThermalConstants.STEFAN_BOLTZMANN * areaM2 * (t1_4 - t0_4);
	}

	/**
	 * Fourier conductive heat flow rate (W) across two contacting materials separated by {@code distanceM}.
	 */
	public static double conductivePowerWatts(double k1, double k2, double contactAreaM2,
											  double temp1K, double temp2K, double distanceM) {
		double kEff = (2.0 * k1 * k2) / Math.max(1.0e-4, k1 + k2);
		return kEff * contactAreaM2 * (temp1K - temp2K) / Math.max(0.02, distanceM);
	}

	/**
	 * Convective heat transfer power (W) between moving air/combustion gas and a solid surface.
	 */
	public static double convectivePowerWatts(double airSpeedMS, double areaM2, double gasTempK, double surfTempK) {
		// Natural convection baseline h ~ 6 W/(m2*K), forced draft scales with v^0.65
		double h = 6.0 + 18.0 * Math.pow(Math.max(0.0, airSpeedMS), 0.65);
		return h * areaM2 * (gasTempK - surfTempK);
	}

	/**
	 * Equilibrium moisture content (kg water / kg dry wood) as a function of relative humidity (0..1)
	 * and temperature (K).
	 */
	public static double equilibriumMoistureContent(double relativeHumidity, double tempK) {
		double rh = Math.max(0.02, Math.min(0.99, relativeHumidity));
		double tBonus = Math.max(0.0, (tempK - 293.15) * 0.0012);
		double emc = 0.03 + 0.25 * Math.pow(rh, 1.35) - tBonus;
		return Math.max(0.015, Math.min(0.32, emc));
	}

	/**
	 * Advances a {@link BodyState} over {@code dtSeconds} using true energy conservation, overlapping
	 * Heating -> Drying -> Pyrolysis -> Gas Combustion -> Char Oxidation -> Ash stages, internal
	 * core-to-surface heat & moisture diffusion, and thermal shock/stress accumulation.
	 *
	 * @param externalHeatWatts Net external radiant + convective + conductive heat entering the surface (W)
	 * @param airTempK Local air temperature (K)
	 * @param o2Fraction Local oxygen volume fraction (0..0.21)
	 * @param airSpeedMS Local air velocity past fuel (m/s)
	 * @param relativeHumidity Local relative humidity (0..1)
	 * @param rainWettingKgS External wetting rate from rain/immersion (kg/s)
	 */
	public static void stepBody(BodyState s, MaterialProfile.Properties p,
								double dtSeconds,
								double externalHeatWatts,
								double airTempK,
								double o2Fraction,
								double airSpeedMS,
								double relativeHumidity,
								double rainWettingKgS) {
		double dt = Math.max(0.01, Math.min(2.0, dtSeconds));
		s.lastVolatileRateKgS = 0.0;
		s.lastCharRateKgS = 0.0;
		s.lastHeatReleasedW = 0.0;
		s.lastO2ConsumedKgS = 0.0;
		s.lastCoProducedKgS = 0.0;
		s.lastCo2ProducedKgS = 0.0;
		s.lastSmokeProduced = 0.0;
		s.lastSteamProducedKgS = 0.0;

		double prevSurfT = s.surfaceTempK;

		// 0. External wetting (rain / immersion / wet ground)
		if (rainWettingKgS > 0.0) {
			double maxWater = Math.max(0.1, (s.dryWoodMassKg + s.charMassKg) * 1.25);
			s.surfaceMoistureKg = Math.min(maxWater * 0.45, s.surfaceMoistureKg + rainWettingKgS * dt);
		}

		// 1. Internal moisture diffusion from wet core to drier shell
		double diffCoeff = p.moistureDiffusivity() * 1.6e-4 * (1.0 + 0.008 * Math.max(0.0, s.coreTempK - 290.0));
		double moistureGrad = s.coreMoistureRatio() - s.surfaceMoistureRatio();
		double diffusedWater = Math.max(-s.surfaceMoistureKg * 0.25,
				Math.min(s.coreMoistureKg * 0.25, moistureGrad * diffCoeff * s.initialDryMassKg * dt));
		s.coreMoistureKg = Math.max(0.0, s.coreMoistureKg - diffusedWater);
		s.surfaceMoistureKg = Math.max(0.0, s.surfaceMoistureKg + diffusedWater);

		// Geometric surface-area similarity when fuel piece mass differs from profile reference mass
		double massRatio = Math.max(0.04, Math.min(4.0, s.initialDryMassKg / Math.max(0.1, p.dryMassKg())));
		double effArea = p.surfaceAreaM2() * Math.pow(massRatio, 0.6667);

		// 2. Internal heat conduction between outer shell and interior core
		double shellMass = Math.max(0.03, (s.dryWoodMassKg + s.charMassKg + s.ashMassKg) * 0.30);
		double coreMass = Math.max(0.05, (s.dryWoodMassKg + s.charMassKg + s.ashMassKg) * 0.70);
		double charFrac = s.charMassKg / Math.max(0.05, s.dryWoodMassKg + s.charMassKg);
		double kEff = p.thermalConductivity() * (1.0 - 0.5 * charFrac) + 0.25 * s.coreMoistureRatio();
		double thicknessM = Math.max(0.04, Math.cbrt(s.initialDryMassKg / Math.max(100.0, p.densityKgM3())) * 0.5);
		double qInternalW = conductivePowerWatts(kEff, kEff, effArea * 0.6,
				s.surfaceTempK, s.coreTempK, thicknessM);

		// 3. Surface radiative + convective heat loss to surrounding air
		double qRadOutW = radiativePowerWatts(p.emissivity(), effArea * 0.55, s.surfaceTempK, airTempK);
		double qConvToAirW = convectivePowerWatts(airSpeedMS, effArea * 0.55, s.surfaceTempK, airTempK);

		double netShellHeatJ = (externalHeatWatts - qInternalW - qRadOutW - qConvToAirW) * dt;
		double netCoreHeatJ = qInternalW * dt;

		// 4. Moisture evaporation (boiling plateau near 373 K consumes latent heat first!)
		double emc = equilibriumMoistureContent(relativeHumidity, Math.max(airTempK, s.surfaceTempK));
		double dryShellSolid = Math.max(0.02, (s.dryWoodMassKg + s.charMassKg) * 0.30);
		double eqShellWater = dryShellSolid * emc;
		double excessSurfaceWater = Math.max(0.0, s.surfaceMoistureKg - eqShellWater);

		double shellCap = heatCapacityJPerK(shellMass, p.specificHeat(), s.surfaceMoistureKg);
		double coreCap = heatCapacityJPerK(coreMass, p.specificHeat(), s.coreMoistureKg);

		// Ambient air drying (even below boiling, warm dry wind evaporates excess moisture)
		if (excessSurfaceWater > 0.0 && rainWettingKgS <= 0.0) {
			double vaporDrive = Math.max(0.0, (1.0 - relativeHumidity) * (0.5 + airSpeedMS)
					* (0.3 + Math.max(0.0, s.surfaceTempK - 275.0) * 0.03));
			double ambientEvap = Math.min(excessSurfaceWater,
					effArea * 1.2e-4 * vaporDrive * dt);
			s.surfaceMoistureKg -= ambientEvap;
			netShellHeatJ -= ambientEvap * ThermalConstants.WATER_LATENT_VAPORIZATION * 0.35;
		}

		// Thermal boiling evaporation when surface temperature approaches / exceeds 365-373 K
		double tentativeShellTemp = s.surfaceTempK + netShellHeatJ / shellCap;
		if (s.surfaceMoistureKg > 0.0 && tentativeShellTemp > 358.0 && netShellHeatJ > 0.0) {
			double boilFraction = Math.min(1.0, (tentativeShellTemp - 358.0) / 20.0);
			double boilEnergy = netShellHeatJ * boilFraction;
			double evapKg = Math.min(s.surfaceMoistureKg, boilEnergy / ThermalConstants.WATER_LATENT_VAPORIZATION);
			s.surfaceMoistureKg -= evapKg;
			netShellHeatJ -= evapKg * ThermalConstants.WATER_LATENT_VAPORIZATION;
			s.lastSteamProducedKgS += evapKg / dt;
		}

		// Apply remaining sensible heat to shell temperature
		s.surfaceTempK = Math.max(230.0, s.surfaceTempK + netShellHeatJ / shellCap);

		// Core boiling if core reaches 373 K
		double tentativeCoreTemp = s.coreTempK + netCoreHeatJ / coreCap;
		if (s.coreMoistureKg > 0.0 && tentativeCoreTemp > ThermalConstants.BOILING_K && netCoreHeatJ > 0.0) {
			double excessJ = (tentativeCoreTemp - ThermalConstants.BOILING_K) * coreCap;
			double coreEvap = Math.min(s.coreMoistureKg, excessJ / ThermalConstants.WATER_LATENT_VAPORIZATION);
			s.coreMoistureKg -= coreEvap;
			netCoreHeatJ -= coreEvap * ThermalConstants.WATER_LATENT_VAPORIZATION;
			s.lastSteamProducedKgS += coreEvap / dt;
		}
		s.coreTempK = Math.max(230.0, s.coreTempK + netCoreHeatJ / coreCap);
		if (s.coreTempK > s.maxCoreTempK) {
			s.maxCoreTempK = s.coreTempK;
		}

		// 5. Overlapping Combustion Chemistry (Pyrolysis -> Gas Combustion -> Char Oxidation -> Ash)
		if (p.isCombustible()) {
			double surfMoist = s.surfaceMoistureRatio();
			// Moisture strongly suppresses pyrolysis and ignition temperature
			double moistPenaltyK = Math.min(140.0, surfMoist * 220.0);
			double effPyroK = p.pyrolysisStartK() + moistPenaltyK;
			double effIgnK = p.ignitionTempK() + moistPenaltyK;

			// 5a. Pyrolysis: wood -> volatile gases + char + ash
			double pyroRateKgS = 0.0;
			if (s.dryWoodMassKg > 1.0e-4 && s.surfaceTempK >= effPyroK) {
				double thermalDrive = Math.min(3.5, (s.surfaceTempK - effPyroK) / 140.0 + 0.12);
				// Porosity + surface area + low moisture accelerate pyrolysis
				double moistDamp = 1.0 / (1.0 + 4.5 * surfMoist);
				double baseRate = 0.0045 * effArea * (0.45 + p.porosity());
				pyroRateKgS = Math.min(s.dryWoodMassKg / dt, baseRate * thermalDrive * moistDamp);
			}

			if (pyroRateKgS > 0.0) {
				double pyroMass = pyroRateKgS * dt;
				s.dryWoodMassKg = Math.max(0.0, s.dryWoodMassKg - pyroMass);
				double volatileMass = pyroMass * p.volatileFraction();
				double charYield = pyroMass * p.fixedCarbonFraction();
				double ashYield = pyroMass * p.ashFraction();

				s.charMassKg += charYield;
				s.ashMassKg += ashYield;
				s.lastVolatileRateKgS = volatileMass / dt;

				// Pyrolysis absorbs endothermic decomposition heat from shell
				s.surfaceTempK = Math.max(effPyroK - 10.0,
						s.surfaceTempK - (pyroMass * ThermalConstants.HEAT_PYROLYSIS_ENDOTHERM) / shellCap);

				// 5b. Gas combustion of released volatiles, limited by oxygen availability!
				boolean canFlame = o2Fraction >= ThermalConstants.MIN_O2_FLAME
						&& (s.flaming || s.surfaceTempK >= effIgnK);
				if (canFlame) {
					s.flaming = true;
					// Stoichiometric O2 demand ~ 1.35 kg O2 per kg wood volatiles; airflow feeds oxygen
					double o2SupplyFactor = Math.min(1.0,
							((o2Fraction - ThermalConstants.MIN_O2_FLAME) / 0.085) * (0.55 + 0.65 * Math.min(2.0, airSpeedMS + 0.5)));
					double burnedVolatiles = volatileMass * Math.max(0.15, o2SupplyFactor);
					double unburnedVolatiles = volatileMass - burnedVolatiles;

					// Incomplete combustion under low O2 produces CO + heavy smoke
					double incomplete = o2Fraction < ThermalConstants.INCOMPLETE_COMBUSTION_O2
							? Math.min(0.65, (ThermalConstants.INCOMPLETE_COMBUSTION_O2 - o2Fraction) * 14.0)
							: 0.04;
					double qGasJ = burnedVolatiles * ThermalConstants.HEAT_VOLATILE_COMBUSTION * (1.0 - 0.55 * incomplete);

					// Feedback fraction (~28%) heats the wood surface to sustain fire; rest heats surrounding air!
					double feedbackFrac = 0.28 / (1.0 + 1.8 * surfMoist);
					s.surfaceTempK += (qGasJ * feedbackFrac) / shellCap;
					s.lastHeatReleasedW += qGasJ / dt;
					s.lastO2ConsumedKgS += (burnedVolatiles * 1.35) / dt;
					s.lastCo2ProducedKgS += (burnedVolatiles * 1.80 * (1.0 - incomplete)) / dt;
					s.lastCoProducedKgS += (burnedVolatiles * 1.15 * incomplete + unburnedVolatiles * 0.25) / dt;
					s.lastSmokeProduced += (unburnedVolatiles * 4.5 + burnedVolatiles * (0.2 + 2.5 * incomplete)
							+ s.lastSteamProducedKgS * dt * 0.8) / dt;
				} else {
					// Smoldering unignited pyrolysis releases thick raw smoke & CO!
					s.flaming = false;
					s.lastSmokeProduced += (volatileMass * 5.0) / dt;
					s.lastCoProducedKgS += (volatileMass * 0.35) / dt;
				}
			} else if (s.surfaceTempK < effIgnK - 35.0) {
				s.flaming = false;
			}

			// 5c. Char oxidation (glowing embers: C + O2 -> CO2/CO -> Ash)
			if (s.charMassKg > 1.0e-4 && o2Fraction >= ThermalConstants.MIN_O2_EMBER
					&& (s.surfaceTempK >= ThermalConstants.CHAR_OXIDATION_K || s.flaming)) {
				double o2Drive = Math.min(1.3, (o2Fraction - ThermalConstants.MIN_O2_EMBER) / 0.15)
						* (0.45 + 0.75 * Math.min(2.5, airSpeedMS + 0.35));
				double tDrive = Math.min(2.5, (s.surfaceTempK - ThermalConstants.CHAR_OXIDATION_K + 80.0) / 300.0);
				double charRate = 0.0012 * effArea * (0.5 + p.porosity()) * o2Drive * Math.max(0.2, tDrive);
				double burnedChar = Math.min(s.charMassKg, charRate * dt);
				s.charMassKg = Math.max(0.0, s.charMassKg - burnedChar);
				// Small inorganic ash fraction remains from char oxidation
				double residualAsh = burnedChar * 0.05;
				s.ashMassKg += residualAsh;
				double carbonOxidized = burnedChar - residualAsh;

				double incomplete = o2Fraction < 0.14 ? Math.min(0.6, (0.14 - o2Fraction) * 10.0) : 0.03;
				double qCharJ = carbonOxidized * ThermalConstants.HEAT_CHAR_COMBUSTION * (1.0 - 0.65 * incomplete);

				// Embers retain ~48% of energy right in the solid bed (intense glowing radiant heat!)
				s.surfaceTempK = Math.min(1480.0, s.surfaceTempK + (qCharJ * 0.48) / shellCap);
				s.lastHeatReleasedW += qCharJ / dt;
				s.lastO2ConsumedKgS += (carbonOxidized * 2.67) / dt;
				s.lastCo2ProducedKgS += (carbonOxidized * 3.66 * (1.0 - incomplete)) / dt;
				s.lastCoProducedKgS += (carbonOxidized * 2.33 * incomplete) / dt;
				s.lastCharRateKgS = burnedChar / dt;
			}
		}

		// Clamp unphysical runaway temperature
		s.surfaceTempK = Math.min(1600.0, s.surfaceTempK);

		// 6. Thermal expansion, gradient stress, and material damage
		double gradK = Math.abs(s.surfaceTempK - s.coreTempK);
		double dTdt = Math.abs(s.surfaceTempK - prevSurfT) / dt;

		switch (p.category()) {
			case STONE -> {
				// Rapid heating/cooling or trapped moisture in porous limestone triggers thermal shock
				double shockThreshold = p.porosity() > 0.05 ? 95.0 : 190.0;
				if (gradK > shockThreshold || (dTdt > 35.0 && s.surfaceTempK > 450.0)) {
					double steamBoost = 1.0 + 6.0 * s.surfaceMoistureRatio();
					s.stressDamage = Math.min(1.0, s.stressDamage
							+ ((gradK - shockThreshold * 0.6) / 6000.0) * steamBoost * dt);
				}
			}
			case CLAY -> {
				// Rapidly heating wet clay causes steam cracking; slow firing > 850K sinters cleanly
				double moist = s.totalMoistureRatio();
				if (moist > 0.08 && dTdt > 12.0 && s.surfaceTempK > 365.0) {
					s.stressDamage = Math.min(1.0, s.stressDamage + 0.045 * moist * (dTdt / 12.0) * dt);
				} else if (gradK > 150.0) {
					s.stressDamage = Math.min(1.0, s.stressDamage + 0.008 * dt);
				}
			}
			case METAL -> {
				// Metals soften above 700K (iron) or 520K (copper)
				double softenStart = p.thermalConductivity() > 90.0 ? 520.0 : 720.0;
				double softenSpan = p.thermalConductivity() > 90.0 ? 450.0 : 550.0;
				double hotDamage = Math.max(0.0, (s.coreTempK - softenStart) / softenSpan);
				s.stressDamage = Math.max(s.stressDamage * 0.995, Math.min(0.90, hotDamage));
			}
			case WOOD, PLANT_FIBER -> {
				double dryFrac = 1.0 - Math.min(1.0, s.dryWoodMassKg / Math.max(0.05, s.initialDryMassKg));
				s.stressDamage = Math.min(1.0, Math.max(s.stressDamage, dryFrac * 0.85));
			}
			default -> {
			}
		}
	}

	/**
	 * Cooking physics state for a single piece of food undergoing thermal penetration.
	 */
	public enum CookingMethod {
		ROASTING(0.75, 0.70, 0.10),
		BOILING(0.10, 0.85, 0.80),
		BAKING(0.65, 0.80, 0.35),
		HOT_STONE(0.25, 0.15, 1.30),
		STEAMING(0.15, 1.15, 0.25);

		public final double radiationWeight;
		public final double convectionWeight;
		public final double conductionWeight;

		CookingMethod(double rad, double conv, double cond) {
			this.radiationWeight = rad;
			this.convectionWeight = conv;
			this.conductionWeight = cond;
		}
	}

	public static final class FoodThermalState {
		public double surfaceTempK;
		public double coreTempK;
		public double waterRemainingFrac;
		public double cookedProgress; // 0..1 (1.0 = safely cooked through)
		public double charredProgress; // 0..1 (1.0 = burnt)
		public double pasteurizationDose; // accumulated time*exp((T_core - 333)/10) for future microbiology

		public FoodThermalState(double initialTempK, double waterFrac) {
			this.surfaceTempK = initialTempK;
			this.coreTempK = initialTempK;
			this.waterRemainingFrac = waterFrac;
		}
	}

	/**
	 * Advances food heating by {@code dtSeconds} using surface-to-core thermal conduction and
	 * method-specific radiation/convection/conduction heat transfer.
	 */
	public static void stepFood(FoodThermalState fs, MaterialProfile.FoodProperties fp,
								CookingMethod method, double sourceTempK,
								double airSpeedMS, double dtSeconds) {
		double dt = Math.max(0.01, Math.min(2.0, dtSeconds));
		double surfMass = fp.massKg() * 0.30;
		double coreMass = fp.massKg() * 0.70;
		double area = 0.048 * Math.pow(fp.massKg() / 0.3, 0.66);

		double qRad = method.radiationWeight * radiativePowerWatts(0.88, area, sourceTempK, fs.surfaceTempK);
		double qConv = method.convectionWeight * convectivePowerWatts(airSpeedMS + 0.4, area, sourceTempK, fs.surfaceTempK);
		double qCond = method.conductionWeight * conductivePowerWatts(1.8, fp.thermalConductivity(), area * 0.5,
				sourceTempK, fs.surfaceTempK, 0.012);

		// Boiling and steaming physically cap the effective medium temperature near 373.15 K
		double capTemp = (method == CookingMethod.BOILING || method == CookingMethod.STEAMING)
				? Math.min(sourceTempK, ThermalConstants.BOILING_K + 2.0) : 1300.0;

		double qInner = conductivePowerWatts(fp.thermalConductivity(), fp.thermalConductivity(), area * 1.4,
				fs.surfaceTempK, fs.coreTempK, fp.characteristicRadiusM()) * 3.5;

		double surfCap = Math.max(80.0, surfMass * fp.specificHeat());
		double coreCap = Math.max(150.0, coreMass * fp.specificHeat());

		double netSurfJ = (qRad + qConv + qCond - qInner) * dt;
		// Evaporation of surface water buffers food surface temperature near 373 K while moist
		if (fs.waterRemainingFrac > 0.08 && fs.surfaceTempK > 368.0 && netSurfJ > 0.0) {
			double evapFraction = (method == CookingMethod.BOILING || method == CookingMethod.STEAMING) ? 0.15 : 0.45;
			double evapEnergy = netSurfJ * evapFraction;
			double waterLossKg = Math.min(surfMass * fs.waterRemainingFrac,
					evapEnergy / ThermalConstants.WATER_LATENT_VAPORIZATION);
			fs.waterRemainingFrac = Math.max(0.0, fs.waterRemainingFrac - waterLossKg / Math.max(0.05, fp.massKg()));
			netSurfJ -= waterLossKg * ThermalConstants.WATER_LATENT_VAPORIZATION;
		}

		fs.surfaceTempK = Math.min(capTemp, Math.max(250.0, fs.surfaceTempK + netSurfJ / surfCap));
		fs.coreTempK = Math.min(capTemp, Math.max(250.0, fs.coreTempK + (qInner * dt) / coreCap));

		// Cooking progress driven by internal core temperature reaching protein/starch denaturation range
		if (fs.coreTempK >= fp.targetCoreTempK() - 12.0) {
			double rate = 0.045 * Math.min(3.0, (fs.coreTempK - (fp.targetCoreTempK() - 15.0)) / 18.0);
			fs.cookedProgress = Math.min(1.0, fs.cookedProgress + rate * dt);
		}
		// Charring happens on dry surface exceeding charTempK (cannot burn while boiling/steaming!)
		if (method != CookingMethod.BOILING && method != CookingMethod.STEAMING
				&& fs.surfaceTempK >= fp.charTempK()) {
			double charRate = 0.025 * Math.min(4.0, (fs.surfaceTempK - fp.charTempK()) / 40.0 + 0.25)
					* (1.0 - 0.6 * fs.waterRemainingFrac);
			fs.charredProgress = Math.min(1.0, fs.charredProgress + charRate * dt);
		}
		// Microbial thermal lethality log-dose above 60 C (333.15 K)
		if (fs.coreTempK > 333.15) {
			double dRate = Math.exp((fs.coreTempK - 333.15) / 10.0) * 0.05;
			fs.pasteurizationDose = Math.min(1000.0, fs.pasteurizationDose + dRate * dt);
		}
	}
}
