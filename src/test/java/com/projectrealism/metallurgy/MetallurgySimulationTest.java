package com.projectrealism.metallurgy;

import com.projectrealism.knapping.FractureEngine;
import com.projectrealism.knapping.HaftData;
import com.projectrealism.knapping.StoneInteractionPhysics;
import com.projectrealism.knapping.VoxelRockGrid;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.Vec3d;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

public class MetallurgySimulationTest {

	@Test
	void oreCrushingAndWaterWashingConcentrateMineralGrade() {
		OreComposition raw = OreComposition.malachiteOre(1.2, 0.55);
		double initialGangue = raw.gangueFraction();
		double initialCu = raw.recoverableElementFraction(MineralSpecies.TargetElement.CU);

		Random rng = new Random(42L);
		raw.crushAndSort(0.85, rng);
		raw.washConcentrate(0.85);

		assertTrue(raw.gangueFraction() < initialGangue * 0.5,
				"Crushing + density washing must flush light silica gangue");
		assertTrue(raw.recoverableElementFraction(MineralSpecies.TargetElement.CU) > initialCu * 1.4,
				"Concentrated copper fraction must rise substantially");
		assertTrue(raw.massKg < 1.2 && raw.massKg > 0.25);
	}

	@Test
	void freeformFurnaceStructureDrivesTemperatureReducingGasAndSmelting() {
		// Compare open fire-pit vs enclosed shaft furnace with tuyeres & chimney
		FurnaceStructure.BlockProbe openProbe = new FurnaceStructure.BlockProbe() {
			@Override
			public FurnaceStructure.WallMaterial probe(int dx, int dy, int dz) {
				return dy == -1 ? FurnaceStructure.WallMaterial.GRANITE_STONE : FurnaceStructure.WallMaterial.AIR;
			}

			@Override
			public boolean isOpenToSky(int dx, int dy, int dz) {
				return true;
			}
		};
		FurnaceStructure.BlockProbe shaftProbe = new FurnaceStructure.BlockProbe() {
			@Override
			public FurnaceStructure.WallMaterial probe(int dx, int dy, int dz) {
				if (dy == -1) return FurnaceStructure.WallMaterial.REFRACTORY_CLAY;
				if (dy == 0 && (Math.abs(dx) == 1 && dz == 0)) return FurnaceStructure.WallMaterial.AIR; // tuyere air inlets
				if (dy >= 0 && dy <= 3 && Math.max(Math.abs(dx), Math.abs(dz)) == 1 && (dx != 0 || dz != 0)) {
					return FurnaceStructure.WallMaterial.REFRACTORY_CLAY;
				}
				return FurnaceStructure.WallMaterial.AIR;
			}

			@Override
			public boolean isOpenToSky(int dx, int dy, int dz) {
				return dx == 0 && dz == 0 && dy >= 4;
			}
		};

		var openGeom = FurnaceStructure.analyze(openProbe);
		var shaftGeom = FurnaceStructure.analyze(shaftProbe);
		assertTrue(shaftGeom.thermalEfficiency() > openGeom.thermalEfficiency() + 0.35);
		assertTrue(shaftGeom.chimneyHeightBlocks() >= 3);
		assertTrue(shaftGeom.tuyereOpenings() >= 1);

		SmeltingChemistry.ChamberState openCh = new SmeltingChemistry.ChamberState();
		SmeltingChemistry.ChamberState shaftCh = new SmeltingChemistry.ChamberState();
		openCh.charcoalMassKg = 4.0;
		openCh.temperatureK = 700.0;
		shaftCh.charcoalMassKg = 4.0;
		shaftCh.temperatureK = 700.0;
		shaftCh.fluxCalciteKg = 0.8;
		shaftCh.activeOre = OreComposition.malachiteOre(1.0, 0.20);
		shaftCh.activeOre.crushedDegree = 0.85;

		for (int i = 0; i < 160; i++) {
			if (i % 4 == 0) SmeltingChemistry.pumpBellows(shaftCh, 0.06);
			SmeltingChemistry.step(openCh, openGeom, 293.15, 0.5);
			SmeltingChemistry.step(shaftCh, shaftGeom, 293.15, 0.5);
		}

		assertTrue(shaftCh.temperatureK > openCh.temperatureK + 300.0,
				"Enclosed shaft furnace with bellows must burn dramatically hotter than open hearth");
		assertTrue(shaftCh.temperatureK > 1200.0, "Shaft furnace must reach smelting range");
		assertTrue(shaftCh.coConcentration > 0.45, "Enclosed charcoal bed must establish reducing CO atmosphere");
		assertTrue(shaftCh.pooledMetalMassKg > 0.30, "Malachite must reduce into molten copper pool");
		assertEquals(AlloyComposition.pureCopper().colorR(), shaftCh.pooledAlloy.colorR(), 25);
	}

	@Test
	void plasticForgingConservesMassWorkHardensAndAnnealsCleanly() {
		DynamicMetalState copper = DynamicMetalState.createNativeNugget(101L, 0.90, 0.97);
		long initialVol = copper.grid.totalVolumeUnits();
		double initialHard = copper.effectiveHardness();
		Random rng = new Random(77L);

		// Cold hammering compresses and spreads copper laterally without deleting mass
		int successfulBlows = 0;
		for (int i = 0; i < 8; i++) {
			FractureEngine.VoxelHit hit = new FractureEngine.VoxelHit(7 + (i % 2), 4, 7 + (i / 4), 0, 1, 0);
			// Find top solid voxel in column
			for (int y = MetalVolumeGrid.SIZE - 1; y >= 0; y--) {
				if (copper.grid.isSolid(hit.x(), y, hit.z())) {
					hit = new FractureEngine.VoxelHit(hit.x(), y, hit.z(), 0, 1, 0);
					break;
				}
			}
			var out = ForgeEngine.hammerStrike(copper, hit, 1.4, 6.5, 1.9, new Vec3d(1, 0, 0), rng);
			if (out.displacedMillivoxels() > 0) successfulBlows++;
		}

		assertTrue(successfulBlows >= 3, "Hammering native copper must plastically deform mass");
		assertEquals(initialVol, copper.grid.totalVolumeUnits(),
				"Plastic deformation must strictly conserve sub-voxel metal volume");
		assertTrue(copper.grid.micro.averageDislocation() > 0.25,
				"Cold hammering must accumulate dislocation work hardening");
		assertTrue(copper.effectiveHardness() > initialHard,
				"Work-hardened copper must become harder than cast/raw annealed copper");

		// Heating above recrystallization (~620 K+) anneals dislocations back down & restores ductility
		double dislocBeforeAnneal = copper.grid.micro.averageDislocation();
		copper.temperatureK = 840.0;
		copper.stepThermal(840.0, 20.0, true);
		assertTrue(copper.grid.micro.averageDislocation() < dislocBeforeAnneal * 0.45,
				"Annealing hot copper must recrystallize and relieve cold work dislocations");
	}

	@Test
	void hotForgingExpelsBloomerySlagAndSulfurCausesHotShortCracking() {
		// Slaggy bloomery iron forged hot consolidates porosity & squeezes slag out
		AlloyComposition bloomAlloy = new AlloyComposition(0.0, 0.0, 0.84, 0.0, 0.002, 0.0, 0.16);
		MetalVolumeGrid grid = MetalVolumeGrid.createNativeNugget(55L, 0.90);
		for (int i = 0; i < MicrostructureState.TOTAL_CELLS; i++) {
			grid.micro.cell(i).porosity = 0.24;
			grid.micro.cell(i).slagInclusion = 0.16;
		}
		DynamicMetalState bloom = new DynamicMetalState(bloomAlloy, 0.95, grid, 1320.0);

		double initSlag = bloom.alloy.slag;
		double initPoro = bloom.grid.micro.averagePorosity();
		var hit = ForgeEngine.raycastMetal(bloom.grid, new Vec3d(0.5, 0.9, 0.5), new Vec3d(0, -1, 0));
		assertNotNull(hit);
		ForgeEngine.hammerStrike(bloom, hit, 1.5, 6.0, 2.2, new Vec3d(1, 0, 0), new Random(3L));

		assertTrue(bloom.alloy.slag < initSlag, "Hot forging bloomery iron must squeeze out entrapped slag");
		assertTrue(bloom.grid.micro.averagePorosity() < initPoro, "Hot forging must collapse internal bloom porosity");

		// High-sulfur hot copper exhibits hot-short cracking under hammer blows
		AlloyComposition sulfidic = new AlloyComposition(0.95, 0.0, 0.0, 0.0, 0.0, 0.05, 0.0);
		DynamicMetalState badCopper = new DynamicMetalState(sulfidic, 0.95, MetalVolumeGrid.createNativeNugget(19L, 0.85), 1100.0);
		boolean cracked = false;
		for (int i = 0; i < 6; i++) {
			var h2 = ForgeEngine.raycastMetal(badCopper.grid, new Vec3d(0.5, 0.9, 0.5), new Vec3d(0, -1, 0));
			if (h2 != null && ForgeEngine.hammerStrike(badCopper, h2, 1.3, 6.0, 2.0, new Vec3d(1, 0, 0), new Random(i)).cracked()) {
				cracked = true;
				break;
			}
		}
		assertTrue(cracked, "Residual sulfur above hot-short threshold must trigger cracking when forged hot");
	}

	@Test
	void bronzeAlloyMeltingFluidityAndDynamicCarvedMoldCasting() {
		AlloyComposition bronze = AlloyComposition.tinBronze(0.11); // ~11% Sn bronze
		assertTrue(bronze.liquidusTempK() < AlloyComposition.pureCopper().liquidusTempK() - 60.0,
				"Tin bronze must melt significantly cooler than pure copper");
		assertTrue(bronze.intrinsicHardness() > AlloyComposition.pureCopper().intrinsicHardness() + 1.4,
				"11% Sn bronze must be substantially harder than pure copper");

		CrucibleState crucible = new CrucibleState();
		crucible.addMetalCharge(bronze, 2.5, 1340.0);
		assertTrue(crucible.isMolten());

		// Carve an arbitrary elongated wedged blade cavity inside a dry fired mold
		MoldGrid goodMold = MoldGrid.createBlank(MoldGrid.MoldMaterial.FIRED_CLAY);
		for (int z = 3; z <= 12; z++) {
			for (int x = 6; x <= 9; x++) {
				for (int y = 2; y <= (x <= 7 ? 4 : 3); y++) {
					goodMold.setCavity(x, y, z, true);
				}
			}
		}
		// Compare against damp green clay unvented closed mold
		MoldGrid badMold = MoldGrid.createBlank(MoldGrid.MoldMaterial.GREEN_CLAY);
		for (int z = 3; z <= 12; z++) {
			for (int x = 6; x <= 9; x++) {
				for (int y = 2; y <= 4; y++) {
					badMold.setCavity(x, y, z, true);
				}
			}
		}
		badMold.closedCavity = true;
		badMold.vented = false;

		double startMass = crucible.metalMassKg;
		var goodCast = CastingEngine.pourAndSolidify(crucible, goodMold, 0.95);
		assertTrue(goodCast.success() && goodCast.casting() != null);
		assertFalse(goodCast.gasDefect());
		assertEquals(startMass - goodCast.usedMetalKg(), crucible.metalMassKg, 1e-6,
				"Crucible pour must conserve exact alloy mass");

		crucible.addMetalCharge(bronze, 2.0, 1340.0);
		var badCast = CastingEngine.pourAndSolidify(crucible, badMold, 0.95);
		assertTrue(badCast.gasDefect(), "Wet unvented closed mold must exhibit steam/gas defect");
		assertTrue(badCast.porosity() > goodCast.porosity() + 0.15,
				"Damp unvented mold casting must contain much higher blowhole porosity");
	}

	@Test
	void wootzCrucibleCarburizationQuenchAndTemperingDriveMicrostructure() {
		// Crucible wootz carburization: pure iron + organic charcoal held hot (>1480 K)
		CrucibleState crucible = new CrucibleState();
		crucible.addMetalCharge(AlloyComposition.wroughtIron(), 1.2, 1520.0);
		crucible.enclosedCarbonKg = 0.08;
		for (int s = 0; s < 120; s++) {
			crucible.stepThermal(1560.0, 1.0);
		}
		assertTrue(crucible.alloy.c > 0.006, "Crucible steel must absorb carbon from sealed charcoal charge");

		// Quench high-carbon blade from austenite (1120 K) vs slow anneal vs warm temper (540 K)
		DynamicMetalState steel = new DynamicMetalState(
				new AlloyComposition(0.0, 0.0, 0.99, 0.0, 0.009, 0.0, 0.002),
				0.95, MetalVolumeGrid.createNativeNugget(9L, 0.85), 1120.0);

		double preHard = steel.effectiveHardness();
		steel.grid.micro.applyQuench(steel.alloy, 1120.0, 293.15, 420.0);
		steel.temperatureK = 295.15;

		double quenchedHard = steel.effectiveHardness();
		double quenchedTough = steel.effectiveToughness();
		assertTrue(quenchedHard > preHard + 1.5, "Quenching austenitized high-C steel must form hard martensite");
		assertTrue(steel.grid.micro.averageMartensite() > 0.45);

		// Temper at 545 K to relieve brittle quench stress & regain toughness
		steel.temperatureK = 545.0;
		steel.stepThermal(545.0, 25.0, false);
		assertTrue(steel.effectiveToughness() > quenchedTough + 0.05,
				"Tempering martensitic steel must relieve residual stress and raise toughness");
	}

	@Test
	void contactPhysicsDistinguishesGeometryHardnessAndWearAcrossMetals() {
		// Construct two identical-alloy (11% Sn Bronze) geometries: a keen thin knife vs a heavy hafted axe wedge
		AlloyComposition bronze = AlloyComposition.tinBronze(0.11);
		MetalVolumeGrid bladeGrid = new MetalVolumeGrid();
		for (int z = 3; z <= 12; z++) {
			bladeGrid.setFill(8, 1, z, 950);
			bladeGrid.setFill(7, 1, z, 820);
			bladeGrid.setFill(6, 1, z, 360); // thin acute 1-voxel front edge
		}
		DynamicMetalState bronzeKnife = new DynamicMetalState(bronze, 0.95, bladeGrid, 293.15);

		MetalVolumeGrid wedgeGrid = new MetalVolumeGrid();
		for (int z = 4; z <= 11; z++) {
			for (int y = 0; y <= 2; y++) {
				wedgeGrid.setFill(9, y, z, 1000);
				wedgeGrid.setFill(8, y, z, 1000);
			}
			wedgeGrid.setFill(7, 0, z, 900);
			wedgeGrid.setFill(7, 1, z, 900);
			wedgeGrid.setFill(6, 0, z, 520);
		}
		DynamicMetalState bronzeChopper = new DynamicMetalState(bronze, 1.15, wedgeGrid, 293.15);
		bronzeChopper.haft.attachHandle(HaftData.Orientation.PERPENDICULAR_CHOP, 65.0, 0.45, bronzeChopper.metrics());
		bronzeChopper.haft.wrapBinding("sinew", 0.95);

		var knifeSlice = ContactPhysics.evaluateMetal(bronzeKnife, StoneInteractionPhysics.TargetMaterial.FLESH_SLICE, 85.0);
		var chopperChop = ContactPhysics.evaluateMetal(bronzeChopper, StoneInteractionPhysics.TargetMaterial.WOOD_CHOP, 150.0);
		var knifeChop = ContactPhysics.evaluateMetal(bronzeKnife, StoneInteractionPhysics.TargetMaterial.WOOD_CHOP, 150.0);

		assertTrue(knifeSlice.effectiveness() > 0.45);
		assertTrue(chopperChop.effectiveness() > knifeChop.effectiveness() + 0.15,
				"Hafted sturdy bronze wedge must chop wood significantly better than unhafted thin blade");

		// Compare soft cold unhardened pure copper vs hardened work-hardened bronze against ORE_HARD
		DynamicMetalState softCopperPick = new DynamicMetalState(AlloyComposition.pureCopper(), 1.15, wedgeGrid.copy(), 293.15);
		softCopperPick.haft.attachHandle(HaftData.Orientation.ANGLED_PICK, 65.0, 0.45, softCopperPick.metrics());
		softCopperPick.haft.wrapBinding("cordage", 0.90);

		bronzeChopper.grid.micro.cell(0).dislocationDensity = 0.70;
		for (int i = 0; i < MicrostructureState.TOTAL_CELLS; i++) {
			bronzeChopper.grid.micro.cell(i).dislocationDensity = 0.65;
		}
		var softMine = ContactPhysics.evaluateMetal(softCopperPick, StoneInteractionPhysics.TargetMaterial.ORE_HARD, 165.0);
		var bronzeMine = ContactPhysics.evaluateMetal(bronzeChopper, StoneInteractionPhysics.TargetMaterial.ORE_HARD, 165.0);
		assertTrue(bronzeMine.effectiveness() > softMine.effectiveness() * 2.0,
				"Hardened bronze must pierce hard mineral veins where soft pure copper fails");
	}

	@Test
	void metallurgyNbtRoundTripsPreserveExactState() {
		DynamicMetalState orig = DynamicMetalState.createNativeNugget(333L, 0.88, 0.94);
		orig.alloy = AlloyComposition.tinBronze(0.095);
		orig.temperatureK = 985.0;
		orig.grid.micro.cell(5).dislocationDensity = 0.72;
		orig.grid.micro.cell(12).martensiteFrac = 0.41;
		orig.haft.attachHandle(HaftData.Orientation.PERPENDICULAR_CHOP, 60.0, 0.40, orig.metrics());
		orig.haft.wrapBinding("sinew", 0.90);

		NbtCompound tag = orig.toNbt();
		DynamicMetalState loaded = DynamicMetalState.fromNbt(tag);

		assertEquals(orig.alloy.sn, loaded.alloy.sn, 1e-4);
		assertEquals(orig.temperatureK, loaded.temperatureK, 1e-3);
		assertEquals(orig.grid.totalVolumeUnits(), loaded.grid.totalVolumeUnits());
		assertEquals(0.72, loaded.grid.micro.cell(5).dislocationDensity, 0.02);
		assertEquals(0.41, loaded.grid.micro.cell(12).martensiteFrac, 0.02);
		assertTrue(loaded.haft.hafted);
		assertEquals("sinew", loaded.haft.bindingMaterial);
	}
}
