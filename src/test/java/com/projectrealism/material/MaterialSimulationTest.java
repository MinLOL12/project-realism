package com.projectrealism.material;

import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class MaterialSimulationTest {

	@Test
	void anatomicalHideThicknessTraumaAndNbtRoundTrip() {
		HideSurface cow = new HideSurface(AnimalHideType.COW);
		HideSurface rabbit = new HideSurface(AnimalHideType.RABBIT);

		// Spine along central columns is thicker than flank/corner; cow outweighs rabbit substantially
		assertTrue(cow.thickness[HideSurface.idx(3, 3)] > cow.thickness[HideSurface.idx(0, 7)]);
		assertTrue(cow.currentMassKg() > rabbit.currentMassKg() * 4.0);

		cow.applyKillTrauma(new Random(42L), 0.35, true);
		NbtCompound nbt = cow.toNbt();
		HideSurface restored = HideSurface.fromNbt(nbt);

		assertEquals(cow.animal, restored.animal);
		assertEquals(cow.intactCount(), restored.intactCount());
		assertEquals(cow.currentMassKg(), restored.currentMassKg(), 1e-6);
		for (int i = 0; i < HideSurface.CELLS; i++) {
			assertEquals(cow.fleshClean[i], restored.fleshClean[i], 1e-3);
			assertEquals(cow.moisture[i], restored.moisture[i], 1e-3);
			assertEquals(cow.scorch[i], restored.scorch[i], 1e-3);
		}
	}

	@Test
	void spatialScrapingDependsOnSharpnessAnglePressureAndRegion() {
		HideSurface cleanTest = new HideSurface(AnimalHideType.GOAT);
		double initialMass = cleanTest.currentMassKg();

		// Sharp scraper at optimal ~26 deg & moderate pressure cleans target quadrant without tearing
		HideSurface.ScrapeOutcome good = cleanTest.scrapeAt(0.25, 0.25, 0.08, 26.0, 0.95, 1.0);
		assertTrue(good.removedFlesh > 0.30);
		assertFalse(good.toreHole);
		assertEquals(0.0, cleanTest.fleshClean[HideSurface.idx(7, 7)], 1e-6, "Opposite corner stays unscraped");
		assertTrue(cleanTest.currentMassKg() < initialMass, "Scraping removes fat/flesh waste mass");
		assertTrue(cleanTest.scrapedFatWasteKg > 0.0);

		// Dull scraper forced hard at steep gouging angle on thin flank damages leather
		HideSurface roughTest = new HideSurface(AnimalHideType.RABBIT);
		double accDamage = 0.0;
		for (int i = 0; i < 4; i++) {
			HideSurface.ScrapeOutcome bad = roughTest.scrapeAt(0.15, 0.85, 0.02, 85.0, 0.10, 1.45);
			accDamage += bad.inflictedDamage;
		}
		assertTrue(accDamage > 0.18, "Dull high-pressure gouging damages thin hide");
	}

	@Test
	void thermodynamicDryingContractionTensionWarpAndScorch() {
		HideSurface slack = new HideSurface(AnimalHideType.COW);
		HideSurface tensioned = new HideSurface(AnimalHideType.COW);
		HideSurface scorched = new HideSurface(AnimalHideType.COW);

		for (int i = 0; i < 4; i++) {
			slack.cornerTension[i] = (i == 0) ? 0.95 : 0.08;
			tensioned.cornerTension[i] = 0.82;
			scorched.cornerTension[i] = 0.75;
		}

		for (int step = 0; step < 30; step++) {
			slack.stepDrying(315.0, 0.22, 2.5, 0.0, 0.0, false, 15.0);
			tensioned.stepDrying(315.0, 0.22, 2.5, 0.0, 0.0, false, 15.0);
			scorched.stepDrying(455.0, 0.12, 1.2, 180.0, 0.0, false, 15.0);
		}

		assertTrue(tensioned.averageMoisture() < 0.25, "Warm dry airflow dries stretched hide");
		assertTrue(slack.contractionFrac > tensioned.contractionFrac, "Untensioned hide shrinks more");
		assertTrue(slack.unevenDeformation > tensioned.unevenDeformation + 0.05,
				"Uneven lashing causes permanent warp/buckling");

		double avgScorch = 0.0;
		for (double sc : scorched.scorch) avgScorch += sc;
		avgScorch /= HideSurface.CELLS;
		assertTrue(avgScorch > 0.20, "Excessive thermal exposure scorches leather");
	}

	@Test
	void knifeCuttingProducesUsablePanelAndLeavesScraps() {
		HideSurface dry = new HideSurface(AnimalHideType.COW);
		for (int i = 0; i < HideSurface.CELLS; i++) {
			dry.fleshClean[i] = 0.95;
			dry.moisture[i] = 0.14;
		}
		int newCuts = dry.cutSegment(0.25, 0.25, 0.65, 0.65, 0.92);
		assertTrue(newCuts >= 4);

		int before = dry.intactCount();
		HideSurface.CutPieceProperties panel = dry.extractCutPiece(0.92);
		assertNotNull(panel);
		assertTrue(panel.areaCm2 > 500.0);
		assertTrue(panel.edgeRegularity > 0.75);
		assertTrue(dry.intactCount() < before && dry.hasRemainingUsableCells(),
				"Cutting a panel preserves remaining usable hide/scraps");

		// Dull blade snagging on thick spine degrades edge regularity
		HideSurface thick = new HideSurface(AnimalHideType.COW);
		thick.cutSegment(0.35, 0.25, 0.55, 0.75, 0.15);
		assertTrue(thick.lastCutRegularity < dry.lastCutRegularity);
	}

	@Test
	void perSeamConstructionAlignmentLocalizedLeakRepairAndSloshMass() {
		WaterskinConstruction c = WaterskinConstruction.fromPieces(0.75, 0.92, 0.90, 0.98,
				AnimalHideType.COW, 2400.0, 0.24, 0.14);

		for (WaterskinConstruction.SeamZone z : WaterskinConstruction.SeamZone.values()) {
			WaterskinConstruction.SeamSection s = c.seams[z.index];
			s.punchedHoles = WaterskinConstruction.HOLES_PER_SEAM;
			s.stitchedHoles = WaterskinConstruction.HOLES_PER_SEAM;
			s.meanTension = 0.78;
			s.sealLevel = 0.95;
		}
		// Sabotage ONLY left flank seam -> localized leak while right flank stays watertight
		c.seams[WaterskinConstruction.SeamZone.LEFT_FLANK.index].meanTension = 0.18;
		c.seams[WaterskinConstruction.SeamZone.LEFT_FLANK.index].sealLevel = 0.0;

		double[] rates = new double[WaterskinConstruction.SEAM_COUNT];
		double total = c.totalLeakMlPerSec(900, 293.15, rates);
		assertTrue(rates[WaterskinConstruction.SeamZone.LEFT_FLANK.index] > 0.40,
				"Loose unsealed seam leaks strongly");
		assertEquals(0.0, rates[WaterskinConstruction.SeamZone.RIGHT_FLANK.index], 1e-6,
				"Well-sewn & sealed opposing seam stays watertight");
		assertEquals(WaterskinConstruction.SeamZone.LEFT_FLANK.index, c.worstLeakingSeamIndex(900, 293.15));

		// Sloshing amplifies leak & shifts bulk fluid head
		double preSlosh = c.sloshMagnitude();
		c.stepFluidBody(900, 6.0, 0.0, 0.5);
		assertTrue(c.sloshMagnitude() > preSlosh);
		assertTrue(c.totalLeakMlPerSec(900, 293.15, null) >= total);

		// Container mass = empty (+ moisture) + water contents
		assertTrue(c.totalMassKg(900) >= c.emptyMassKg + 0.90);

		// Localized repair fixes left flank leak
		c.seams[WaterskinConstruction.SeamZone.LEFT_FLANK.index].meanTension = 0.78;
		c.seams[WaterskinConstruction.SeamZone.LEFT_FLANK.index].sealLevel = 0.95;
		assertEquals(0.0, c.totalLeakMlPerSec(900, 293.15, null), 1e-6);
	}

	@Test
	void environmentalExposureAgingAcrossLeatherFiberWoodAndGourd() {
		// Leather wet/dry cyclic wear & rain mildew
		WaterskinConstruction weathered = WaterskinConstruction.createCraftedBaseline();
		for (int i = 0; i < 40; i++) {
			MaterialAging.stepLeatherContainer(weathered, i % 2 == 0, 310.0, 0.95, true, 60.0);
		}
		assertTrue(weathered.discoloration > 0.01);
		assertTrue(weathered.seams[0].seamWear > 0.01);

		// Plant fiber fraying under damp unventilated exposure & tension pulls
		MaterialAging.FiberState fiber = new MaterialAging.FiberState();
		for (int i = 0; i < 20; i++) {
			fiber.stepEnvironment(0.95, true, false, 120.0);
		}
		fiber.applyTensionPull(0.90);
		assertTrue(fiber.fray > 0.05 && fiber.strength < 0.95);

		// Wood rapid uneven-drying warp & moisture-gradient check crack
		MaterialAging.WoodCondition wood = new MaterialAging.WoodCondition();
		wood.surfaceMoisture = 0.58;
		wood.coreMoisture = 0.62;
		for (int i = 0; i < 25; i++) {
			wood.stepDrying(345.0, 0.08, 3.0, false, 60.0);
		}
		assertTrue(wood.warpSeverity > 0.0 && wood.crackSeverity > 0.0);

		// Rigid gourd gentle rack curing vs harsh shock drying crack
		MaterialAging.GourdState gentle = new MaterialAging.GourdState();
		for (int i = 0; i < 60; i++) {
			gentle.stepDrying(302.0, 0.25, 1.5, false, 120.0);
		}
		assertTrue(gentle.isCuredForCarving() && gentle.crackSeverity < 0.05);

		MaterialAging.GourdState harsh = new MaterialAging.GourdState();
		harsh.stepDrying(360.0, 0.05, 2.0, false, 300.0);
		assertTrue(harsh.crackSeverity > 0.08);
		assertTrue(harsh.leakMlPerSec(600, 750) > 0.10);
	}
}
