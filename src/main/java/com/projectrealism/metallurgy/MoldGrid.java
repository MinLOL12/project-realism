package com.projectrealism.metallurgy;

import net.minecraft.nbt.NbtCompound;

/**
 * 16 x 16 x 16 player-carvable casting mold cavity grid. Never stores predefined casting templates:
 * whatever negative space the player carves into the clay, stone, or rammed-sand mold determines
 * the exact 3D shape poured into metal. Supports open molds and 2-part capped vented molds.
 */
public final class MoldGrid {
	public enum MoldMaterial {
		GREEN_CLAY("green_clay", 1.10, 0.55),
		FIRED_CLAY("fired_clay", 1.25, 0.04),
		CARVED_STONE("carved_stone", 2.40, 0.02),
		SAND_RAMMED("sand_rammed", 0.90, 0.08);

		public final String id;
		public final double chillConductivity;
		/** Steam/moisture blowhole porosity contribution when poured molten metal hits mold wall. */
		public final double moistureGasFactor;

		MoldMaterial(String id, double chillConductivity, double moistureGasFactor) {
			this.id = id;
			this.chillConductivity = chillConductivity;
			this.moistureGasFactor = moistureGasFactor;
		}

		public static MoldMaterial fromId(String id) {
			for (MoldMaterial m : values()) if (m.id.equals(id)) return m;
			return GREEN_CLAY;
		}
	}

	public static final int SIZE = 16;
	public static final int TOTAL_VOXELS = SIZE * SIZE * SIZE; // 4,096

	/** True = carved empty cavity ready to receive molten metal; false = solid mold body. */
	private final long[] cavityBits = new long[TOTAL_VOXELS / 64];
	public MoldMaterial material = MoldMaterial.GREEN_CLAY;
	public double temperatureK = 293.15;
	/** Optional second top slab clamping the mold into a closed 2-part casting cavity. */
	public boolean closedCavity = false;
	/** Whether an air vent channel connects high pockets to atmosphere when closed. */
	public boolean vented = false;

	public MoldGrid() {
	}

	public static MoldGrid createBlank(MoldMaterial material) {
		MoldGrid m = new MoldGrid();
		m.material = material;
		return m;
	}

	public static int index(int x, int y, int z) {
		return (y << 8) | (z << 4) | x;
	}

	public static boolean inBounds(int x, int y, int z) {
		return x >= 0 && x < SIZE && y >= 0 && y < SIZE && z >= 0 && z < SIZE;
	}

	public boolean isCavity(int x, int y, int z) {
		if (!inBounds(x, y, z)) return false;
		int idx = index(x, y, z);
		return ((cavityBits[idx >>> 6] >>> (idx & 63)) & 1L) != 0L;
	}

	public void setCavity(int x, int y, int z, boolean carved) {
		if (!inBounds(x, y, z)) return;
		// Keep outer bottom (y=0) and outer x/z border intact so molten metal doesn't spill straight through floor!
		if (y == 0 || x == 0 || x == SIZE - 1 || z == 0 || z == SIZE - 1) return;
		int idx = index(x, y, z);
		if (carved) cavityBits[idx >>> 6] |= (1L << (idx & 63));
		else cavityBits[idx >>> 6] &= ~(1L << (idx & 63));
	}

	/** Carves a small brush/pocket centered at (cx, cy, cz) without touching protective outer mold shell. */
	public int carveBrush(int cx, int cy, int cz, int radius) {
		int carved = 0;
		for (int dy = -radius; dy <= radius; dy++) {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					if (dx * dx + dy * dy + dz * dz <= radius * radius) {
						int vx = cx + dx, vy = cy + dy, vz = cz + dz;
						if (inBounds(vx, vy, vz) && vx > 0 && vx < SIZE - 1 && vz > 0 && vz < SIZE - 1 && vy > 0) {
							if (!isCavity(vx, vy, vz)) {
								setCavity(vx, vy, vz, true);
								carved++;
							}
						}
					}
				}
			}
		}
		// Automatically mark vented if at least 2 disjoint top-level cavities reach y == 15!
		checkVenting();
		return carved;
	}

	public void checkVenting() {
		if (!closedCavity) {
			vented = true;
			return;
		}
		int topOpenings = 0;
		for (int z = 1; z < SIZE - 1; z++) {
			for (int x = 1; x < SIZE - 1; x++) {
				if (isCavity(x, SIZE - 1, z)) topOpenings++;
			}
		}
		// Separate pour sprue + vent channel (at least 2 cells open at top margin)
		vented = topOpenings >= 2;
	}

	public int countCavityVoxels() {
		int sum = 0;
		for (long w : cavityBits) sum += Long.bitCount(w);
		return sum;
	}

	public NbtCompound toNbt() {
		NbtCompound t = new NbtCompound();
		t.putLongArray("Cav", cavityBits);
		t.putString("Mat", material.id);
		t.putDouble("TempK", temperatureK);
		t.putBoolean("Closed", closedCavity);
		t.putBoolean("Vented", vented);
		return t;
	}

	public static MoldGrid fromNbt(NbtCompound t) {
		MoldGrid m = new MoldGrid();
		long[] arr = t.getLongArray("Cav");
		if (arr.length == m.cavityBits.length) {
			System.arraycopy(arr, 0, m.cavityBits, 0, arr.length);
		}
		if (t.contains("Mat")) m.material = MoldMaterial.fromId(t.getString("Mat"));
		if (t.contains("TempK")) m.temperatureK = t.getDouble("TempK");
		m.closedCavity = t.getBoolean("Closed");
		m.vented = t.getBoolean("Vented");
		return m;
	}
}
