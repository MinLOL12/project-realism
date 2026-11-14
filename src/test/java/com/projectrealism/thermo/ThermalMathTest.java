package com.projectrealism.thermo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThermalMathTest {

	@Test
	@DisplayName("Q = m * cp * dT: massive oak takes far more energy to heat than light kindling")
	void energyConservationMassScaling() {
		double dT = 200.0;
		double qDarkOak = ThermalMath.energyForTempChange(
				MaterialProfile.DARK_OAK_WOOD.dryMassKg(),
				MaterialProfile.DARK_OAK_WOOD.specificHeat(), dT);
		double qBirch = ThermalMath.energyForTempChange(
				MaterialProfile.BIRCH_WOOD.dryMassKg(),
				MaterialProfile.BIRCH_WOOD.specificHeat(), dT);
		double qStick = ThermalMath.energyForTempChange(
				MaterialProfile.STICK_KINDLING.dryMassKg(),
				MaterialProfile.STICK_KINDLING.specificHeat(), dT);

		assertTrue(qDarkOak > qBirch * 2.0);
		assertTrue(qBirch > qStick * 10.0);

		ThermalMath.BodyState oak = new ThermalMath.BodyState(293.15, 16.0, 0.05, 0.05);
		ThermalMath.BodyState stick = new ThermalMath.BodyState(293.15, 0.35, 0.05, 0.05);
		for (int i = 0; i < 6; i++) {
			ThermalMath.stepBody(oak, MaterialProfile.DARK_OAK_WOOD, 0.5, 4500.0, 293.15, 0.2095, 0.5, 0.4, 0.0);
			ThermalMath.stepBody(stick, MaterialProfile.STICK_KINDLING, 0.5, 4500.0, 293.15, 0.2095, 0.5, 0.4, 0.0);
		}
		assertTrue(stick.surfaceTempK > oak.surfaceTempK + 120.0);
	}

	@Test
	@DisplayName("wet wood evaporates surface moisture first while core stays moist and temperature stalls")
	void wetWoodBoilingPlateauAndCoreLag() {
		ThermalMath.BodyState dryBirch = new ThermalMath.BodyState(293.15, 1.0, 0.06, 0.06);
		ThermalMath.BodyState wetBirch = new ThermalMath.BodyState(293.15, 1.0, 0.75, 0.75);

		for (int i = 0; i < 60; i++) {
			ThermalMath.stepBody(dryBirch, MaterialProfile.BIRCH_WOOD, 0.5, 10000.0, 320.0, 0.2095, 0.8, 0.45, 0.0);
			ThermalMath.stepBody(wetBirch, MaterialProfile.BIRCH_WOOD, 0.5, 10000.0, 320.0, 0.2095, 0.8, 0.45, 0.0);
		}

		assertTrue(dryBirch.surfaceTempK > wetBirch.surfaceTempK + 80.0);
		assertTrue(wetBirch.surfaceMoistureRatio() < wetBirch.coreMoistureRatio());
		assertTrue(dryBirch.flaming);
		assertFalse(wetBirch.flaming);
	}

	@Test
	@DisplayName("Stefan-Boltzmann radiation scales strongly with T^4")
	void stefanBoltzmannFourthPower() {
		double p600 = ThermalMath.radiativePowerWatts(0.9, 1.0, 600.0, 300.0);
		double p1200 = ThermalMath.radiativePowerWatts(0.9, 1.0, 1200.0, 300.0);
		double expectedRatio = (Math.pow(1200.0, 4) - Math.pow(300.0, 4))
				/ (Math.pow(600.0, 4) - Math.pow(300.0, 4));
		assertEquals(expectedRatio, p1200 / p600, 1.0e-6);
		assertTrue(p1200 > p600 * 16.0);
	}

	@Test
	@DisplayName("full wood combustion progresses through pyrolysis, flaming, char oxidation, and ash")
	void sixStageCombustionAndOxygenDependence() {
		ThermalMath.BodyState openAir = new ThermalMath.BodyState(293.15, 1.0, 0.05, 0.05);
		ThermalMath.BodyState chokedO2 = new ThermalMath.BodyState(293.15, 1.0, 0.05, 0.05);

		// Initial ignition heat pulse heats dry spruce above pyrolysis & autoignition;
		// kiln has oxygen starved below char-oxidation threshold (< 0.06) to yield charcoal.
		for (int i = 0; i < 450; i++) {
			double pulse = i < 35 ? 14000.0 : 350.0;
			ThermalMath.stepBody(openAir, MaterialProfile.SPRUCE_WOOD, 0.5, pulse, 340.0, 0.2095, 1.2, 0.35, 0.0);
			ThermalMath.stepBody(chokedO2, MaterialProfile.SPRUCE_WOOD, 0.5, 4500.0, 480.0, 0.04, 0.15, 0.35, 0.0);
		}

		// Open-air fire burns cleanly leaving ash and consuming dry wood + char
		assertTrue(openAir.dryWoodMassKg < 0.20);
		assertTrue(openAir.ashMassKg > 0.01);

		// Oxygen-starved pyrolysis cannot flame or oxidize char, yielding solid charcoal!
		assertFalse(chokedO2.flaming);
		assertTrue(chokedO2.charMassKg > openAir.charMassKg + 0.05);
	}

	@Test
	@DisplayName("rapid heating of wet clay causes steam cracking stress while slow firing stays intact")
	void thermalShockMaterialDamage() {
		ThermalMath.BodyState shockedClay = new ThermalMath.BodyState(293.15, 16.0, 0.28, 0.28);
		ThermalMath.BodyState gentleClay = new ThermalMath.BodyState(293.15, 16.0, 0.28, 0.28);

		for (int i = 0; i < 60; i++) {
			ThermalMath.stepBody(shockedClay, MaterialProfile.CLAY_BODY, 0.5, 140000.0, 800.0, 0.2095, 1.0, 0.3, 0.0);
			ThermalMath.stepBody(gentleClay, MaterialProfile.CLAY_BODY, 0.5, 2200.0, 350.0, 0.2095, 0.5, 0.3, 0.0);
		}
		assertTrue(shockedClay.stressDamage > gentleClay.stressDamage + 0.05);
	}

	@Test
	@DisplayName("boiling caps food below char temperature while roasting creates surface-core lag and sears")
	void thermalCookingMethods() {
		MaterialProfile.FoodProperties steak = new MaterialProfile.FoodProperties(
				0.55, 0.72, 0.45, 3450.0, 0.036, 344.0, 470.0);

		ThermalMath.FoodThermalState roast = new ThermalMath.FoodThermalState(293.15, steak.waterFraction());
		ThermalMath.FoodThermalState boil = new ThermalMath.FoodThermalState(293.15, steak.waterFraction());

		for (int i = 0; i < 120; i++) {
			ThermalMath.stepFood(roast, steak, ThermalMath.CookingMethod.ROASTING, 920.0, 0.9, 0.5);
			ThermalMath.stepFood(boil, steak, ThermalMath.CookingMethod.BOILING, 920.0, 0.9, 0.5);
		}

		assertTrue(roast.surfaceTempK > roast.coreTempK + 30.0);
		assertTrue(boil.surfaceTempK <= ThermalConstants.BOILING_K + 2.5);
		assertEquals(0.0, boil.charredProgress, 1.0e-9);
		assertTrue(roast.charredProgress > 0.0 || roast.cookedProgress > 0.5);
	}
}
