package com.projectrealism.hydration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BodyFluidMathTest {

	@Test
	@DisplayName("baseline body water compartments equal ~60% of 70 kg adult in osmotic equilibrium")
	void baselineCompartmentsAndEquilibrium() {
		BodyFluidMath.BodyFluidState s = new BodyFluidMath.BodyFluidState();

		assertEquals(28.0, s.icfLiters, 1.0e-6);
		assertEquals(11.0, s.isfLiters, 1.0e-6);
		assertEquals(3.0, s.plasmaLiters, 1.0e-6);
		assertEquals(42.0, s.totalBodyWaterLiters(), 1.0e-6);
		assertEquals(0.60, s.bodyWaterFraction(70.0), 1.0e-6);
		assertEquals(290.0, s.ecfOsmolalityMOsm(), 1.0e-4);
		assertEquals(290.0, s.icfOsmolalityMOsm(), 1.0e-4);

		// Resting at 20 C holds core near 37 C cleanly
		for (int i = 0; i < 120; i++) {
			BodyFluidMath.step(s, BodyFluidMath.EnvironmentInput.COMFORTABLE_REST, 5.0);
		}
		assertTrue(s.bodyCoreTempK > 309.5 && s.bodyCoreTempK < 310.7);
	}

	@Test
	@DisplayName("drinking enters stomach first and hydrates plasma gradually rather than instantaneously")
	void delayedStomachIntestineAbsorption() {
		BodyFluidMath.BodyFluidState s = new BodyFluidMath.BodyFluidState();
		s.plasmaLiters = 2.65;
		s.isfLiters = 9.75;
		double plasmaBefore = s.plasmaLiters;

		BodyFluidMath.ingestWater(s, 1.0, WaterQuality.FRESH_SPRING);
		assertEquals(1.0, s.stomachLiters, 1.0e-6);
		assertEquals(plasmaBefore, s.plasmaLiters, 1.0e-6); // zero instant hydration!

		// After 10 seconds, water is still primarily in stomach/intestines, not all dumped into plasma
		for (int i = 0; i < 5; i++) {
			BodyFluidMath.step(s, BodyFluidMath.EnvironmentInput.COMFORTABLE_REST, 2.0);
		}
		assertTrue(s.digestiveWaterLiters() > 0.95);
		assertTrue(s.plasmaLiters - plasmaBefore < 0.02);

		// Over ~45 minutes, gut empties into circulation and restores plasma/ISF
		for (int i = 0; i < 540; i++) {
			BodyFluidMath.step(s, BodyFluidMath.EnvironmentInput.COMFORTABLE_REST, 5.0);
		}
		assertTrue(s.digestiveWaterLiters() < 0.15);
		assertTrue(s.plasmaLiters > plasmaBefore + 0.12);
	}

	@Test
	@DisplayName("hypertonic ocean water pulls water into gut and worsens hypernatremia compared to fresh water")
	void seawaterOsmoticDehydration() {
		BodyFluidMath.BodyFluidState sea = new BodyFluidMath.BodyFluidState();
		BodyFluidMath.BodyFluidState fresh = new BodyFluidMath.BodyFluidState();

		BodyFluidMath.ingestWater(sea, 0.75, WaterQuality.OCEAN_SEAWATER);
		BodyFluidMath.ingestWater(fresh, 0.75, WaterQuality.FRESH_SPRING);

		for (int i = 0; i < 360; i++) {
			BodyFluidMath.step(sea, BodyFluidMath.EnvironmentInput.COMFORTABLE_REST, 5.0);
			BodyFluidMath.step(fresh, BodyFluidMath.EnvironmentInput.COMFORTABLE_REST, 5.0);
		}

		assertTrue(sea.plasmaNaMm() > fresh.plasmaNaMm() + 5.0);
		assertTrue(sea.ecfOsmolalityMOsm() > fresh.ecfOsmolalityMOsm() + 9.0);
		assertTrue(sea.totalBodyWaterLiters() < 42.0); // net body dehydration despite drinking!
		assertTrue(sea.totalBodyWaterLiters() < fresh.totalBodyWaterLiters() - 0.30);
	}

	@Test
	@DisplayName("evaporative sweat cools via Q = m_evap * L_v while humid dead air causes dripping with poor cooling")
	void sweatEvaporationVsHumidDripping() {
		BodyFluidMath.BodyFluidState dryRunner = new BodyFluidMath.BodyFluidState();
		BodyFluidMath.BodyFluidState jungleRunner = new BodyFluidMath.BodyFluidState();

		BodyFluidMath.EnvironmentInput dryBreezyHeat = new BodyFluidMath.EnvironmentInput(
				309.15, 0.15, 2.5, 160.0, 4.2, 20.0, 0.55, false, 293.15);
		BodyFluidMath.EnvironmentInput humidStillHeat = new BodyFluidMath.EnvironmentInput(
				309.15, 0.96, 0.08, 160.0, 4.2, 20.0, 0.55, false, 293.15);

		for (int i = 0; i < 240; i++) {
			BodyFluidMath.step(dryRunner, dryBreezyHeat, 5.0);
			BodyFluidMath.step(jungleRunner, humidStillHeat, 5.0);
		}

		// Q_evap = m_evap * L_v holds directly
		assertEquals(dryRunner.lastSweatEvapRateKgS * PhysiologicalConstants.SWEAT_LATENT_VAPORIZATION,
				dryRunner.lastEvapCoolingW, 1.0e-3);

		// In humid still air, most sweat drips off uselessly and core overheats compared to dry breeze
		assertTrue(jungleRunner.lastSweatDripRateKgS > jungleRunner.lastSweatEvapRateKgS);
		assertTrue(dryRunner.lastEvapCoolingW > jungleRunner.lastEvapCoolingW * 2.0);
		assertTrue(jungleRunner.bodyCoreTempK > dryRunner.bodyCoreTempK + 0.5);
	}

	@Test
	@DisplayName("kidneys raise ADH and concentrate urine when dehydrated vs dilute water diuresis when overloaded")
	void renalAdhAndAldosteroneRegulation() {
		BodyFluidMath.BodyFluidState dehydrated = new BodyFluidMath.BodyFluidState();
		dehydrated.plasmaLiters = 2.55;
		dehydrated.isfLiters = 9.35;
		dehydrated.ecfNaMmol += 95.0; // hypertonic dehydration

		BodyFluidMath.BodyFluidState waterLoaded = new BodyFluidMath.BodyFluidState();
		waterLoaded.plasmaLiters = 3.22;
		waterLoaded.isfLiters = 12.10;
		waterLoaded.ecfNaMmol -= 120.0; // dilute hypotonic state

		BodyFluidMath.step(dehydrated, BodyFluidMath.EnvironmentInput.COMFORTABLE_REST, 5.0);
		BodyFluidMath.step(waterLoaded, BodyFluidMath.EnvironmentInput.COMFORTABLE_REST, 5.0);

		assertTrue(dehydrated.adhLevel > waterLoaded.adhLevel * 4.0);
		assertTrue(dehydrated.urineOsmolalityMOsm > 800.0);
		assertTrue(waterLoaded.urineOsmolalityMOsm < 220.0);
		assertTrue(waterLoaded.lastUrineRateLS > dehydrated.lastUrineRateLS * 4.0);
	}

	@Test
	@DisplayName("perceived thirst triggers from osmotic shift before severe dehydration and reacts to oral satiety")
	void perceivedThirstVsBodyWater() {
		BodyFluidMath.BodyFluidState s = new BodyFluidMath.BodyFluidState();
		// Mild salt load spikes plasma osmolality while TBW stays ~60%
		s.ecfNaMmol += 80.0;
		s.ecfClMmol += 80.0;
		BodyFluidMath.step(s, BodyFluidMath.EnvironmentInput.COMFORTABLE_REST, 5.0);

		double tbw = s.bodyWaterFraction(PhysiologicalConstants.REFERENCE_BODY_MASS_KG);
		assertTrue(tbw > 0.58);
		assertTrue(s.perceivedThirst > 0.35);

		double thirstBeforeSip = s.perceivedThirst;
		BodyFluidMath.ingestWater(s, 0.25, WaterQuality.FRESH_SPRING);
		BodyFluidMath.step(s, BodyFluidMath.EnvironmentInput.COMFORTABLE_REST, 2.0);
		assertTrue(s.perceivedThirst < thirstBeforeSip);
	}

	@Test
	@DisplayName("boiling inactivates microbes without removing salt or chemicals; charcoal filters sediment and toxins")
	void waterQualityBoilingAndFiltration() {
		WaterQuality foulBrine = new WaterQuality(290.0, 28.0, 0.80, 350.0, 0.90, 0.70);

		// Boil at 100 C hearth for 20 seconds
		WaterQuality boiled = foulBrine;
		for (int i = 0; i < 40; i++) {
			boiled = boiled.stepHeating(700.0, 293.15, 2.0);
		}
		assertTrue(boiled.tempK() >= 368.0);
		assertTrue(boiled.microbialLoad() < 0.02);
		assertTrue(boiled.salinityGPerL() >= 28.0); // boiling does NOT remove dissolved salts!
		assertEquals(0.70, boiled.chemicalLoad(), 1.0e-6); // chemical toxins remain!

		WaterQuality filtered = boiled.filterWithCharcoal();
		assertTrue(filtered.turbidity() < 0.10);
		assertTrue(filtered.chemicalLoad() < 0.15);
		assertTrue(filtered.salinityGPerL() >= 28.0); // charcoal does NOT desalinate!
	}
}
