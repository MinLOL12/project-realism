package com.projectrealism.knapping;

import net.minecraft.nbt.NbtCompound;

/**
 * Complete physical stone object holding material properties, 16x16x16 volumetric grid, voxel size,
 * lazy geometric measurements (mass, CoM, edge angles/radii/continuity, surfaces), and optional
 * physical hafting geometry. Never stores a tool category.
 */
public final class DynamicStoneState {
	public StoneMaterial material;
	/** Edge length of one voxel in real centimeters (typical cobble ~0.95..1.25 cm -> ~15..20 cm rock). */
	public double voxelScaleCm;
	public VoxelRockGrid grid;
	public HaftData haft;
	private VoxelRockGrid.GeometryMetrics cachedMetrics;

	public DynamicStoneState(StoneMaterial material, double voxelScaleCm, VoxelRockGrid grid) {
		this.material = material != null ? material : StoneMaterial.FLINT;
		this.voxelScaleCm = Math.max(0.45, Math.min(1.6, voxelScaleCm));
		this.grid = grid != null ? grid : new VoxelRockGrid();
		this.haft = new HaftData();
		this.cachedMetrics = null;
	}

	public static DynamicStoneState createCobble(StoneMaterial material, long seed, double sizeScale) {
		double scaleCm = 0.90 + (Math.abs(seed) % 35) / 100.0;
		return new DynamicStoneState(material, scaleCm, VoxelRockGrid.generateNodule(material, seed, sizeScale));
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
			cachedMetrics = grid.measure(material, voxelScaleCm);
		}
		return cachedMetrics;
	}

	public double totalMassKg() {
		double stoneKg = metrics().massKg();
		return haft.hafted ? stoneKg + haft.handleMassKg : stoneKg;
	}

	public DynamicStoneState copy() {
		DynamicStoneState s = new DynamicStoneState(material, voxelScaleCm, grid.copy());
		s.haft = haft.copy();
		return s;
	}

	public NbtCompound toNbt() {
		NbtCompound t = new NbtCompound();
		t.putString("Mat", material.id);
		t.putDouble("ScaleCm", voxelScaleCm);
		t.put("Grid", grid.toNbt());
		t.put("Haft", haft.toNbt());
		return t;
	}

	public static DynamicStoneState fromNbt(NbtCompound t) {
		StoneMaterial mat = StoneMaterial.fromId(t.getString("Mat"));
		double scale = t.contains("ScaleCm") ? t.getDouble("ScaleCm") : 1.05;
		VoxelRockGrid grid = t.contains("Grid")
				? VoxelRockGrid.fromNbt(t.getCompound("Grid"))
				: VoxelRockGrid.generateNodule(mat, 17L, 0.85);
		DynamicStoneState s = new DynamicStoneState(mat, scale, grid);
		if (t.contains("Haft")) {
			s.haft = HaftData.fromNbt(t.getCompound("Haft"));
		}
		return s;
	}
}
