package com.projectrealism.metallurgy;

import com.projectrealism.knapping.HaftData;
import com.projectrealism.knapping.VoxelRockGrid;
import net.minecraft.nbt.NbtCompound;

/**
 * Persistent dynamically manufactured metal object storing continuous elemental {@link AlloyComposition},
 * volumetric sub-voxel {@link MetalVolumeGrid} + 64-zone microstructure, current thermal state,
 * optional physical wooden {@link HaftData}, and lazy geometric measurements.
 *
 * <p>Never stores a tool/armor/weapon category: utility emerges strictly from geometry + mechanics.</p>
 */
public final class DynamicMetalState {
	public AlloyComposition alloy;
	public double voxelScaleCm;
	public MetalVolumeGrid grid;
	public HaftData haft;
	public double temperatureK;
	private VoxelRockGrid.GeometryMetrics cachedMetrics;

	public DynamicMetalState(AlloyComposition alloy, double voxelScaleCm, MetalVolumeGrid grid, double temperatureK) {
		this.alloy = alloy != null ? alloy : AlloyComposition.pureCopper();
		this.voxelScaleCm = Math.max(0.45, Math.min(1.6, voxelScaleCm));
		this.grid = grid != null ? grid : new MetalVolumeGrid();
		this.haft = new HaftData();
		this.temperatureK = Math.max(250.0, temperatureK);
		this.cachedMetrics = null;
	}

	public static DynamicMetalState createNativeNugget(long seed, double sizeScale, double purity) {
		double scaleCm = 0.85 + (Math.abs(seed) % 25) / 100.0;
		return new DynamicMetalState(
				AlloyComposition.nativeCopper(purity),
				scaleCm,
				MetalVolumeGrid.createNativeNugget(seed, sizeScale),
				293.15
		);
	}

	public void invalidateGeometry() {
		cachedMetrics = null;
		if (haft.hafted) {
			VoxelRockGrid.GeometryMetrics m = metrics();
			haft.contactAreaCm2 = Math.min(haft.contactAreaCm2, Math.max(1.5, m.flatSurfaceAreaCm2() + 2.0));
			haft.recomputeSlipResistance();
		}
	}

	public VoxelRockGrid.GeometryMetrics metrics() {
		if (cachedMetrics == null) {
			cachedMetrics = grid.measure(alloy, voxelScaleCm);
		}
		return cachedMetrics;
	}

	public double totalMassKg() {
		double metalKg = metrics().massKg();
		return haft.hafted ? metalKg + haft.handleMassKg : metalKg;
	}

	public double effectiveHardness() {
		return grid.micro.effectiveHardness(alloy, temperatureK);
	}

	public double effectiveToughness() {
		return grid.micro.effectiveToughness(alloy, temperatureK);
	}

	/** Cools metal slightly toward ambient air via Stefan-Boltzmann + convective cooling, stepping microstructure. */
	public void stepThermal(double envTempK, double dtSeconds, boolean reducingAtmosphere) {
		if (dtSeconds <= 0.0) return;
		grid.micro.stepThermalEvolution(alloy, temperatureK, dtSeconds, reducingAtmosphere);
		double m = Math.max(0.05, metrics().massKg());
		double cp = alloy.specificHeat();
		double dT = envTempK - temperatureK;
		// Newton + radiant cooling rate scaled inversely by thermal capacitance m * cp
		double hRad = (temperatureK > 650.0) ? (5.67e-8 * 0.45 * Math.pow(temperatureK, 3)) : 0.0;
		double hConv = 12.0 + hRad;
		double area = Math.max(0.008, metrics().flatSurfaceAreaCm2() * 2.5e-4);
		double rate = (hConv * area) / (m * cp);
		temperatureK += dT * Math.min(0.85, rate * dtSeconds);
		if (temperatureK < envTempK && dT > 0) temperatureK = envTempK;
	}

	public DynamicMetalState copy() {
		DynamicMetalState s = new DynamicMetalState(alloy.copy(), voxelScaleCm, grid.copy(), temperatureK);
		s.haft = haft.copy();
		return s;
	}

	public NbtCompound toNbt() {
		NbtCompound t = new NbtCompound();
		t.put("Alloy", alloy.toNbt());
		t.putDouble("ScaleCm", voxelScaleCm);
		t.putDouble("TempK", temperatureK);
		t.put("Grid", grid.toNbt());
		t.put("Haft", haft.toNbt());
		return t;
	}

	public static DynamicMetalState fromNbt(NbtCompound t) {
		AlloyComposition a = t.contains("Alloy") ? AlloyComposition.fromNbt(t.getCompound("Alloy")) : AlloyComposition.pureCopper();
		double scale = t.contains("ScaleCm") ? t.getDouble("ScaleCm") : 0.95;
		double tempK = t.contains("TempK") ? t.getDouble("TempK") : 293.15;
		MetalVolumeGrid g = t.contains("Grid") ? MetalVolumeGrid.fromNbt(t.getCompound("Grid")) : MetalVolumeGrid.createNativeNugget(11L, 0.85);
		DynamicMetalState s = new DynamicMetalState(a, scale, g, tempK);
		if (t.contains("Haft")) {
			s.haft = HaftData.fromNbt(t.getCompound("Haft"));
		}
		return s;
	}
}
