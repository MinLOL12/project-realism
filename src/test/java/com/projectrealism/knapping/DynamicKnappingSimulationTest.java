package com.projectrealism.knapping;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class DynamicKnappingSimulationTest {

	@Test
	void volumeAndDensityDetermineExactMassAndShrinkMonotonically() {
		DynamicStoneState obsidian = DynamicStoneState.createCobble(StoneMaterial.OBSIDIAN, 42L, 0.90);
		DynamicStoneState basalt = DynamicStoneState.createCobble(StoneMaterial.BASALT, 42L, 0.90);

		int vox = obsidian.grid.countSolid();
		assertTrue(vox > 300, "Generated cobble should contain substantial voxels");
		double expectedObsidianKg = vox * Math.pow(obsidian.voxelScaleCm, 3) * 1e-6 * StoneMaterial.OBSIDIAN.densityKgM3;
		assertEquals(expectedObsidianKg, obsidian.metrics().massKg(), 1e-4);
		assertTrue(basalt.metrics().massKg() > obsidian.metrics().massKg(),
				"Basalt is denser than obsidian for identical geometry");

		double initialMass = obsidian.metrics().massKg();
		obsidian.grid.setSolid(8, 2, 8, false);
		obsidian.invalidateGeometry();
		assertTrue(obsidian.metrics().massKg() < initialMass, "Removing voxels must permanently reduce mass");
	}

	@Test
	void nbtRoundTripPreservesVoxelsCracksInclusionsAndHafting() {
		DynamicStoneState orig = DynamicStoneState.createCobble(StoneMaterial.FLINT, 1337L, 0.92);
		orig.grid.addCrack(7, 2, 7, 19);
		orig.grid.setInclusion(6, 2, 6, true);
		orig.haft.attachHandle(HaftData.Orientation.PERPENDICULAR_CHOP, 62.0, 0.42, orig.metrics());
		orig.haft.wrapBinding("sinew", 0.92);

		NbtCompound nbt = orig.toNbt();
		DynamicStoneState loaded = DynamicStoneState.fromNbt(nbt);

		assertEquals(orig.material, loaded.material);
		assertEquals(orig.grid.countSolid(), loaded.grid.countSolid());
		assertEquals(19, loaded.grid.getCrackLevel(7, 2, 7));
		assertTrue(loaded.grid.hasInclusion(6, 2, 6));
		assertTrue(loaded.haft.hafted);
		assertEquals(HaftData.Orientation.PERPENDICULAR_CHOP, loaded.haft.orientation);
		assertEquals("sinew", loaded.haft.bindingMaterial);
		assertEquals(orig.haft.slipResistance, loaded.haft.slipResistance, 1e-5);
	}

	@Test
	void rotationAndConnectedComponentExtractionDetachIsolatedIslands() {
		VoxelRockGrid grid = new VoxelRockGrid();
		// Build main block on left + thin 1-voxel neck + lobe on right
		for (int y = 0; y < 5; y++) {
			for (int z = 4; z < 10; z++) {
				for (int x = 1; x < 6; x++) grid.setSolid(x, y, z, true);
				for (int x = 8; x < 12; x++) grid.setSolid(x, y, z, true);
			}
		}
		grid.setSolid(6, 2, 6, true);
		grid.setSolid(7, 2, 6, true);
		assertTrue(grid.extractDetachedComponents().isEmpty(), "Bridged components remain unified");

		// Sever bridge -> disconnected lobe separates cleanly as an independent flake rock
		grid.setSolid(6, 2, 6, false);
		var flakes = grid.extractDetachedComponents();
		assertEquals(1, flakes.size());
		assertEquals(5 * 6 * 5, grid.countSolid());
		assertEquals(5 * 6 * 4 + 1, flakes.get(0).countSolid());

		int beforeRot = grid.countSolid();
		grid.rotate90(0);
		assertEquals(beforeRot, grid.countSolid(), "90-degree rotation must preserve all voxels");
	}

	@Test
	void sevenKnappingFactorsGovernFractureOutcomeAndSecondaryFlakes() {
		// Deterministic beveled flint block
		DynamicStoneState flint = buildBeveledBlock(StoneMaterial.FLINT);
		DynamicStoneState limestone = buildBeveledBlock(StoneMaterial.LIMESTONE);

		// 1. Weak interior center hit glances vs rim platform strike removes clean flake
		FractureEngine.VoxelHit centerHit = new FractureEngine.VoxelHit(8, 4, 8, 0, 1, 0);
		var glance = FractureEngine.strike(flint.copy(), centerHit, new Vec3d(0, -1, 0),
				0.20, StoneMaterial.LIMESTONE, FractureEngine.SupportCondition.SOFT_EARTH, new Random(1L));
		assertEquals(FractureEngine.OutcomeKind.NO_EFFECT, glance.kind());

		// 2. Crisp beveled rim strike with hard quartzite hammerstone on firm wood removes sharp flake
		FractureEngine.VoxelHit rimHit = new FractureEngine.VoxelHit(11, 2, 8, 0, 1, 0);
		var clean = FractureEngine.strike(flint.copy(), rimHit, new Vec3d(-0.35, -0.94, 0),
				1.30, StoneMaterial.QUARTZITE, FractureEngine.SupportCondition.FIRM_WOOD, new Random(7L));
		assertTrue(clean.removedVoxels() > 0, "Rim strike with hard hammerstone must remove flake voxels");

		// 3. Support condition changes effective transmitted fracture energy
		var softStrike = FractureEngine.strike(flint.copy(), rimHit, new Vec3d(-0.35, -0.94, 0),
				0.92, StoneMaterial.QUARTZITE, FractureEngine.SupportCondition.SOFT_EARTH, new Random(7L));
		var anvilStrike = FractureEngine.strike(flint.copy(), rimHit, new Vec3d(-0.35, -0.94, 0),
				0.92, StoneMaterial.QUARTZITE, FractureEngine.SupportCondition.HARD_ANVIL, new Random(7L));
		assertTrue(anvilStrike.removedVoxels() >= softStrike.removedVoxels(),
				"Anvil rebound transmits more fracture energy than cushioned soft earth");

		// 4. Existing deep cracks increase breakage / catastrophic split probability
		DynamicStoneState cracked = buildBeveledBlock(StoneMaterial.OBSIDIAN);
		for (int y = 0; y < 5; y++) {
			for (int z = 5; z < 11; z++) {
				cracked.grid.addCrack(11, y, z, 28);
				cracked.grid.addCrack(9, y, z, 28);
			}
		}
		var splitRes = FractureEngine.strike(cracked, rimHit, new Vec3d(-0.2, -0.98, 0),
				2.80, StoneMaterial.BASALT, FractureEngine.SupportCondition.HARD_ANVIL, new Random(11L));
		assertTrue(splitRes.hitCrackedZone());
		assertTrue(splitRes.removedVoxels() > 0);
		assertTrue(limestone.material.conchoidal < flint.material.conchoidal);
	}

	@Test
	void cuttingChoppingScrapingAndTipPerformanceEmergePurelyFromGeometry() {
		// A thin, acute, keen long flint blade vs a blunt boxy granite cobble (NO tool categories!)
		DynamicStoneState keenBlade = buildAcuteBlade(StoneMaterial.OBSIDIAN);
		DynamicStoneState bluntCobble = buildBox(StoneMaterial.GRANITE, 5, 11, 0, 5, 5, 11);

		var sliceBlade = StoneInteractionPhysics.evaluate(keenBlade, StoneInteractionPhysics.TargetMaterial.FLESH_SLICE, 85.0);
		var sliceCobble = StoneInteractionPhysics.evaluate(bluntCobble, StoneInteractionPhysics.TargetMaterial.FLESH_SLICE, 85.0);
		assertTrue(sliceBlade.effectiveness() > sliceCobble.effectiveness() * 1.4,
				"Acute continuous glassy edge should vastly outperform blunt cobble at slicing");

		// Heavy thick flint wedge hafted perpendicularly vs tiny acute obsidian razor on hardwood chopping
		DynamicStoneState heavyWedge = buildBox(StoneMaterial.BASALT, 4, 12, 0, 5, 4, 12);
		// Carve realistic ~43 deg chopping bevel tapering 3 -> 2 -> 1 along front x=9..12
		for (int z = 4; z < 12; z++) {
			heavyWedge.grid.setSolid(9, 0, z, false);
			heavyWedge.grid.setSolid(9, 4, z, false);
			heavyWedge.grid.setSolid(10, 0, z, false);
			heavyWedge.grid.setSolid(10, 3, z, false);
			heavyWedge.grid.setSolid(10, 4, z, false);
			heavyWedge.grid.setSolid(11, 0, z, false);
			heavyWedge.grid.setSolid(11, 2, z, false);
			heavyWedge.grid.setSolid(11, 3, z, false);
			heavyWedge.grid.setSolid(11, 4, z, false);
		}
		heavyWedge.invalidateGeometry();
		heavyWedge.haft.attachHandle(HaftData.Orientation.PERPENDICULAR_CHOP, 64.0, 0.45, heavyWedge.metrics());
		heavyWedge.haft.wrapBinding("sinew", 0.95);

		var chopWedge = StoneInteractionPhysics.evaluate(heavyWedge, StoneInteractionPhysics.TargetMaterial.WOOD_CHOP, 140.0);
		var chopThinUnbound = StoneInteractionPhysics.evaluate(keenBlade, StoneInteractionPhysics.TargetMaterial.WOOD_CHOP, 140.0);
		assertTrue(chopWedge.effectiveness() > chopThinUnbound.effectiveness(),
				"Hafted heavy wedge outperforms bare hand razor flake at chopping hardwood");
		assertTrue(chopThinUnbound.edgeStress() > chopWedge.edgeStress(),
				"Thin brittle obsidian experiences much higher edge chipping stress on hardwood");

		// Broad flat blunt cobble scrapes hide without gouging, while ultra-acute blade risks gouging!
		var scrapeCobble = StoneInteractionPhysics.evaluate(bluntCobble, StoneInteractionPhysics.TargetMaterial.HIDE_SCRAPE, 75.0);
		var scrapeBlade = StoneInteractionPhysics.evaluate(keenBlade, StoneInteractionPhysics.TargetMaterial.HIDE_SCRAPE, 75.0);
		assertTrue(scrapeBlade.gougeRisk() >= scrapeCobble.gougeRisk(),
				"Acute razor edge carries higher hide-gouging risk than a broad scraper face");
	}

	@Test
	void contactWearPhysicallyChipsExposedEdgesAndBindingLoosensUnderShock() {
		DynamicStoneState blade = buildAcuteBlade(StoneMaterial.OBSIDIAN);
		blade.haft.attachHandle(HaftData.Orientation.PERPENDICULAR_CHOP, 60.0, 0.40, blade.metrics());
		blade.haft.wrapBinding("plant_fiber", 0.70);
		double initialTension = blade.haft.bindingTension;
		int initialVoxels = blade.grid.countSolid();

		int totalChipped = 0;
		Random rng = new Random(99L);
		for (int i = 0; i < 35; i++) {
			totalChipped += StoneInteractionPhysics.applyContactWear(
					blade, StoneInteractionPhysics.TargetMaterial.ORE_HARD, 180.0, rng);
		}
		assertTrue(totalChipped > 0, "Hard rock contact must physically chip edge voxels");
		assertTrue(blade.grid.countSolid() < initialVoxels, "Voxel count must decrease after chipping");
		assertTrue(!blade.haft.hafted || blade.haft.bindingTension < initialTension,
				"Weak plant fiber binding must loosen or slip under repeated hard impacts");
	}

	private static DynamicStoneState buildBox(StoneMaterial mat, int x0, int x1, int y0, int y1, int z0, int z1) {
		VoxelRockGrid g = new VoxelRockGrid();
		for (int y = y0; y < y1; y++) {
			for (int z = z0; z < z1; z++) {
				for (int x = x0; x < x1; x++) {
					g.setSolid(x, y, z, true);
				}
			}
		}
		return new DynamicStoneState(mat, 1.0, g);
	}

	private static DynamicStoneState buildBeveledBlock(StoneMaterial mat) {
		DynamicStoneState s = buildBox(mat, 4, 12, 0, 5, 4, 12);
		for (int z = 4; z < 12; z++) {
			s.grid.setSolid(11, 4, z, false);
			s.grid.setSolid(11, 3, z, false);
		}
		s.invalidateGeometry();
		return s;
	}

	private static DynamicStoneState buildAcuteBlade(StoneMaterial mat) {
		VoxelRockGrid g = new VoxelRockGrid();
		// Spine x=4..6 (thickness 3), tapering sharply to 1-voxel keen edge along x=10, z=3..12
		for (int z = 3; z < 13; z++) {
			for (int y = 0; y < 3; y++) {
				for (int x = 4; x < 7; x++) g.setSolid(x, y, z, true);
			}
			for (int y = 0; y < 2; y++) {
				g.setSolid(7, y, z, true);
				g.setSolid(8, y, z, true);
			}
			g.setSolid(9, 0, z, true);
			g.setSolid(10, 0, z, true);
		}
		return new DynamicStoneState(mat, 0.90, g);
	}
}
