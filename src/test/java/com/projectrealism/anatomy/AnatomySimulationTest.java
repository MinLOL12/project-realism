package com.projectrealism.anatomy;

import com.projectrealism.material.AnimalHideType;
import com.projectrealism.material.HideSurface;
import net.minecraft.nbt.NbtCompound;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

class AnatomySimulationTest {

	@Test
	void speciesAnatomyDivergenceRabbitVsCowVsSheep() {
		AnimalPhysiologyState cowPhys = new AnimalPhysiologyState(AnimalSpecies.COW, 0.90, 0.85);
		AnimalPhysiologyState rabbitPhys = new AnimalPhysiologyState(AnimalSpecies.RABBIT, 0.90, 0.85);
		AnimalPhysiologyState sheepPhys = new AnimalPhysiologyState(AnimalSpecies.SHEEP, 0.90, 0.85);

		CarcassState cow = CarcassState.fromLivingAnimal(cowPhys, false, 0.12);
		CarcassState rabbit = CarcassState.fromLivingAnimal(rabbitPhys, false, 0.12);
		CarcassState sheep = CarcassState.fromLivingAnimal(sheepPhys, false, 0.12);

		// A rabbit is fundamentally distinct from a cow (no horns, tiny bones, low fat fraction, fast cooling)
		assertEquals(0, rabbit.hornsRemaining);
		assertEquals(2, cow.hornsRemaining);
		assertEquals(0.0, cow.woolRemainingKg, 1e-6);
		assertTrue(sheep.woolRemainingKg > 1.5, "Sheep carries actual wool fleece");
		assertEquals(BoneData.BoneSize.SMALL, rabbit.bonesRemaining.get(0).size);
		assertEquals(BoneData.BoneSize.LARGE, cow.bonesRemaining.get(0).size);
		assertTrue(cow.fatRemainingKg > rabbit.fatRemainingKg * 50.0);

		// NBT round-trip preserves full carcass state & mass
		NbtCompound nbt = cow.toNbt();
		CarcassState restored = CarcassState.fromNbt(nbt);
		assertEquals(cow.species, restored.species);
		assertEquals(cow.bonesRemaining.size(), restored.bonesRemaining.size());
		assertEquals(cow.currentTotalMassKg(), restored.currentTotalMassKg(), 1e-3);
	}

	@Test
	void liveAnimalPhysiologyRespondsToForageHydrationColdExertionAndInjury() {
		AnimalPhysiologyState fed = new AnimalPhysiologyState(AnimalSpecies.COW, 0.80, 0.55);
		AnimalPhysiologyState stressed = new AnimalPhysiologyState(AnimalSpecies.RABBIT, 0.80, 0.55);

		for (int i = 0; i < 120; i++) {
			fed.stepLivePhysiology(292.0, 0.55, 0.5, true, true, 15.0);
			stressed.stepLivePhysiology(262.0, 0.25, 4.5, false, false, 15.0);
		}

		assertTrue(fed.fatReserveFrac > stressed.fatReserveFrac + 0.15, "Grazing builds fat reserves");
		assertTrue(fed.hydrationFrac > stressed.hydrationFrac + 0.15, "Water access maintains hydration");
		assertTrue(stressed.fatigueFrac > 0.45, "Sustained fleeing causes muscular fatigue");

		stressed.applyHitTrauma(0.60, 0.20); // leg injury
		assertTrue(stressed.movementSpeedFactor() < 0.70, "Leg injury + fatigue slow movement");
	}

	@Test
	void carcassThermodynamicCoolingBleedVisceraAndDecomposition() {
		AnimalPhysiologyState p = new AnimalPhysiologyState(AnimalSpecies.COW, 0.90, 0.80);
		CarcassState openCold = CarcassState.fromLivingAnimal(p, false, 0.1);
		openCold.stickNeckToBleed();
		openCold.processViscera(0.95, new Random(1L)); // eviscerate cleanly
		openCold.hidePresent = false;                  // skinned cools even faster

		CarcassState closedWarm = CarcassState.fromLivingAnimal(p, false, 0.1);

		for (int i = 0; i < 120; i++) {
			openCold.stepEnvironment(269.0, 0.40, 1.8, 0.0, false, true, 45.0);   // sub-zero breeze -> freezes!
			closedWarm.stepEnvironment(306.0, 0.88, 0.2, 0.0, false, false, 45.0); // hot humid still air + viscera + blood!
		}

		assertTrue(openCold.bodyTempK < closedWarm.bodyTempK - 18.0, "Eviscerated & skinned carcass cools faster");
		assertTrue(openCold.bodyTempK <= 273.15, "Cold environment freezes carcass");
		assertTrue(closedWarm.spoilageFrac > openCold.spoilageFrac + 0.15, "Warm unbled un-eviscerated carcass spoils rapidly");
		assertTrue(closedWarm.insectActivity > openCold.insectActivity, "Insects proliferate in warm open humid air");
	}

	@Test
	void carcassMaterialRecoveryConservesMassAndYieldsSpeciesHide() {
		AnimalPhysiologyState p = new AnimalPhysiologyState(AnimalSpecies.SHEEP, 0.92, 0.85);
		CarcassState c = CarcassState.fromLivingAnimal(p, false, 0.20);
		double initialMass = c.currentTotalMassKg();

		double wool = c.shearWoolKg();
		assertTrue(wool > 0.40);
		assertTrue(c.currentTotalMassKg() < initialMass);

		HideSurface hide = null;
		Random rng = new Random(7L);
		for (int i = 0; i < 4 && hide == null; i++) {
			hide = c.skinStep(0.90, rng);
		}
		assertNotNull(hide, "Repeated knife passes detach complete hide");
		assertEquals(AnimalHideType.SHEEP, hide.animal);

		double fat = c.flenseFatKg(0.90);
		double sinew = c.extractSinewKg();
		double tendon = c.extractTendonKg();
		MeatCutData loin = c.butcherMuscle(MeatCutData.CutKind.PRIME_LOIN, 0.90);
		BoneData bone = c.disarticulateOneBone();

		assertTrue(fat > 0.20 && sinew > 0.05 && tendon > 0.05);
		assertNotNull(loin);
		assertNotNull(bone);
		assertTrue(loin.massKg > 0.20);
	}

	@Test
	void boneSizeDensityHeatBrittlenessAndToolWorkmanship() {
		BoneData rabbitBone = new BoneData(AnimalSpecies.RABBIT, BoneData.BoneType.LONG_SHAFT, 0.85);
		BoneData cowBone = new BoneData(AnimalSpecies.COW, BoneData.BoneType.LONG_SHAFT, 0.95);

		// Rabbit bone can form delicate needles/awls, NEVER heavy blades or chisels
		assertTrue(rabbitBone.canShape(BoneData.ToolTarget.NEEDLE));
		assertFalse(rabbitBone.canShape(BoneData.ToolTarget.BLADE));
		assertFalse(rabbitBone.canShape(BoneData.ToolTarget.CHISEL));

		// Cow femur can shape everything including heavy bone chisels & blades
		assertTrue(cowBone.canShape(BoneData.ToolTarget.CHISEL));

		// Careful shaping with sharp knife & grit stone yields high-integrity tool
		BoneData crafted = new BoneData(AnimalSpecies.COW, BoneData.BoneType.LONG_SHAFT, 0.95);
		for (int i = 0; i < 4; i++) {
			crafted.carveWithKnife(0.92, BoneData.ToolTarget.BLADE);
			crafted.abradeWithStone(0.88);
		}
		assertTrue(crafted.isReadyToFinish());
		int goodQuality = crafted.resultingToolIntegrityPct();

		// Scorched/brittle bone carved clumsily with dull knife yields fragile tool!
		BoneData abused = new BoneData(AnimalSpecies.COW, BoneData.BoneType.LONG_SHAFT, 0.95);
		abused.stepDrying(390.0, 0.05, 1.0, false, 180.0); // severe heat embrittlement!
		for (int i = 0; i < 4; i++) {
			abused.carveWithKnife(0.20, BoneData.ToolTarget.BLADE);
			abused.abradeWithStone(0.40);
		}
		assertTrue(abused.brittleness > 0.45);
		assertTrue(abused.resultingToolIntegrityPct() < goodQuality - 20,
				"Poor workmanship & heat-brittle bone produce markedly more fragile tools");
	}

	@Test
	void carcassHorizontalRegionMappingReachesHeadAndAllZones() {
		net.minecraft.util.math.BlockPos pos = net.minecraft.util.math.BlockPos.ORIGIN;
		// Facing NORTH: head is at low Z (0.10), hind at high Z (0.85), flanks/torso in middle Z (0.50)
		var headUv = CarcassState.localBedUV(net.minecraft.util.math.Direction.NORTH, pos,
				new net.minecraft.util.math.Vec3d(0.50, 0.625, 0.10));
		var limbUv = CarcassState.localBedUV(net.minecraft.util.math.Direction.NORTH, pos,
				new net.minecraft.util.math.Vec3d(0.50, 0.625, 0.85));
		var gutUv = CarcassState.localBedUV(net.minecraft.util.math.Direction.NORTH, pos,
				new net.minecraft.util.math.Vec3d(0.20, 0.625, 0.50));
		var loinUv = CarcassState.localBedUV(net.minecraft.util.math.Direction.NORTH, pos,
				new net.minecraft.util.math.Vec3d(0.80, 0.625, 0.50));
		var torsoUv = CarcassState.localBedUV(net.minecraft.util.math.Direction.NORTH, pos,
				new net.minecraft.util.math.Vec3d(0.50, 0.625, 0.50));

		assertEquals(CarcassState.Region.HEAD_NECK, CarcassState.regionFromUV(headUv.x, headUv.z));
		assertEquals(CarcassState.Region.HAUNCH_LIMB, CarcassState.regionFromUV(limbUv.x, limbUv.z));
		assertEquals(CarcassState.Region.VISCERA_CAVITY, CarcassState.regionFromUV(gutUv.x, gutUv.z));
		assertEquals(CarcassState.Region.LOIN_RIBS, CarcassState.regionFromUV(loinUv.x, loinUv.z));
		assertEquals(CarcassState.Region.COAT_TORSO, CarcassState.regionFromUV(torsoUv.x, torsoUv.z));
	}
}
