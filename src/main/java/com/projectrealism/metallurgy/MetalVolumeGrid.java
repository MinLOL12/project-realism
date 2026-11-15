package com.projectrealism.metallurgy;

import com.projectrealism.knapping.VoxelRockGrid;
import net.minecraft.nbt.NbtCompound;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 16 x 16 x 16 volumetric metal representation storing continuous sub-voxel metal mass
 * ({@code 0..1000} millivoxels per cell so plastic deformation redistributes volume cleanly without
 * deleting matter) coupled to a 64-zone {@link MicrostructureState}.
 */
public final class MetalVolumeGrid {
	public static final int SIZE = 16;
	public static final int TOTAL_VOXELS = SIZE * SIZE * SIZE; // 4,096
	public static final int FULL_CELL = 1000;
	/** Minimum fill considered visually & physically solid (>= 220 millivoxels). */
	public static final int SOLID_THRESHOLD = 220;

	private final short[] fill = new short[TOTAL_VOXELS];
	public final MicrostructureState micro;

	public MetalVolumeGrid() {
		this.micro = new MicrostructureState();
	}

	public MetalVolumeGrid(MicrostructureState micro) {
		this.micro = micro != null ? micro : new MicrostructureState();
	}

	public static int index(int x, int y, int z) {
		return (y << 8) | (z << 4) | x;
	}

	public static boolean inBounds(int x, int y, int z) {
		return x >= 0 && x < SIZE && y >= 0 && y < SIZE && z >= 0 && z < SIZE;
	}

	public int getFill(int x, int y, int z) {
		if (!inBounds(x, y, z)) return 0;
		return fill[index(x, y, z)];
	}

	public void setFill(int x, int y, int z, int amount) {
		if (!inBounds(x, y, z)) return;
		fill[index(x, y, z)] = (short) Math.max(0, Math.min(FULL_CELL, amount));
	}

	public int getFillIdx(int idx) {
		return fill[idx];
	}

	public void setFillIdx(int idx, int amount) {
		fill[idx] = (short) Math.max(0, Math.min(FULL_CELL, amount));
	}

	public boolean isSolid(int x, int y, int z) {
		return inBounds(x, y, z) && fill[index(x, y, z)] >= SOLID_THRESHOLD;
	}

	public long totalVolumeUnits() {
		long sum = 0;
		for (short v : fill) sum += v;
		return sum;
	}

	public int countSolidCells() {
		int c = 0;
		for (short v : fill) {
			if (v >= SOLID_THRESHOLD) c++;
		}
		return c;
	}

	/** Syncs mass-occupancy weights on the 64-zone MicrostructureState so empty air voxels never dilute averages. */
	public void syncMicroWeights() {
		double[] weights = new double[MicrostructureState.TOTAL_CELLS];
		for (int y = 0; y < SIZE; y++) {
			for (int z = 0; z < SIZE; z++) {
				for (int x = 0; x < SIZE; x++) {
					int f = fill[index(x, y, z)];
					if (f > 0) {
						weights[MicrostructureState.cellIndexForVoxel(x, y, z)] += f / (double) FULL_CELL;
					}
				}
			}
		}
		micro.setCellMassWeights(weights);
	}

	/** Creates an organic unshaped native copper nugget or smelted prill lump. */
	public static MetalVolumeGrid createNativeNugget(long seed, double sizeScale) {
		MetalVolumeGrid g = new MetalVolumeGrid();
		Random rng = new Random(seed);
		double s = Math.max(0.42, Math.min(1.0, sizeScale));
		double rx = (3.0 + rng.nextDouble() * 1.4) * s;
		double ry = (2.2 + rng.nextDouble() * 1.0) * s;
		double rz = (3.2 + rng.nextDouble() * 1.5) * s;
		for (int y = 0; y < SIZE; y++) {
			for (int z = 0; z < SIZE; z++) {
				for (int x = 0; x < SIZE; x++) {
					double dx = (x - 7.5) / rx;
					double dy = (y - 2.0) / ry;
					double dz = (z - 7.5) / rz;
					double wobble = 0.16 * Math.sin(x * 0.9 + seed) * Math.cos(z * 0.8);
					double dist = dx * dx + dy * dy + dz * dz;
					if (dist <= 1.0 + wobble) {
						int amt = dist < 0.72 ? FULL_CELL : (int) Math.round(FULL_CELL * Math.max(0.35, 1.15 - dist));
						g.setFill(x, y, z, amt);
					}
				}
			}
		}
		g.settleOnBase();
		g.syncMicroWeights();
		return g;
	}

	/** Shifts non-zero fill downward so lowest occupied layer rests on y = 0. */
	public void settleOnBase() {
		int minY = SIZE;
		for (int y = 0; y < SIZE; y++) {
			for (int z = 0; z < SIZE; z++) {
				for (int x = 0; x < SIZE; x++) {
					if (getFill(x, y, z) > 0) {
						minY = Math.min(minY, y);
					}
				}
			}
		}
		if (minY <= 0 || minY >= SIZE) return;
		for (int y = 0; y < SIZE - minY; y++) {
			for (int z = 0; z < SIZE; z++) {
				for (int x = 0; x < SIZE; x++) {
					setFill(x, y, z, getFill(x, y + minY, z));
				}
			}
		}
		for (int y = SIZE - minY; y < SIZE; y++) {
			for (int z = 0; z < SIZE; z++) {
				for (int x = 0; x < SIZE; x++) {
					setFill(x, y, z, 0);
				}
			}
		}
	}

	/** Rotates metal body 90 degrees around axis (0=X, 1=Y, 2=Z) preserving exact volume. */
	public void rotate90(int axis) {
		short[] next = new short[TOTAL_VOXELS];
		for (int y = 0; y < SIZE; y++) {
			for (int z = 0; z < SIZE; z++) {
				for (int x = 0; x < SIZE; x++) {
					short v = fill[index(x, y, z)];
					if (v <= 0) continue;
					int nx, ny, nz;
					if (axis == 0) {
						nx = x; ny = z; nz = SIZE - 1 - y;
					} else if (axis == 1) {
						nx = SIZE - 1 - z; ny = y; nz = x;
					} else {
						nx = y; ny = SIZE - 1 - x; nz = z;
					}
					next[index(nx, ny, nz)] = v;
				}
			}
		}
		System.arraycopy(next, 0, fill, 0, TOTAL_VOXELS);
		settleOnBase();
	}

	/** Computes exact physical metrics from conserved sub-voxel volume, alloy density, and microstructure. */
	public VoxelRockGrid.GeometryMetrics measure(AlloyComposition alloy, double voxelScaleCm) {
		syncMicroWeights();
		int solidCount = 0;
		long volUnits = 0;
		double sumX = 0, sumY = 0, sumZ = 0;
		int minX = SIZE, minY = SIZE, minZ = SIZE;
		int maxX = -1, maxY = -1, maxZ = -1;

		for (int y = 0; y < SIZE; y++) {
			for (int z = 0; z < SIZE; z++) {
				for (int x = 0; x < SIZE; x++) {
					int f = getFill(x, y, z);
					if (f < SOLID_THRESHOLD) continue;
					solidCount++;
					volUnits += f;
					double w = f / (double) FULL_CELL;
					sumX += (x + 0.5) * w;
					sumY += (y + 0.5) * w;
					sumZ += (z + 0.5) * w;
					if (x < minX) minX = x;
					if (x > maxX) maxX = x;
					if (y < minY) minY = y;
					if (y > maxY) maxY = y;
					if (z < minZ) minZ = z;
					if (z > maxZ) maxZ = z;
				}
			}
		}

		if (solidCount == 0 || volUnits == 0) {
			return new VoxelRockGrid.GeometryMetrics(0, 0.0, 8, 8, 8, 0, 0, 0, 90.0, 4.0, 0.0, 4.0, 0.0, 0.0, 0.0);
		}

		double equivCells = volUnits / (double) FULL_CELL;
		double cx = sumX / equivCells;
		double cy = sumY / equivCells;
		double cz = sumZ / equivCells;

		double lengthCm = (Math.max(maxX - minX, maxZ - minZ) + 1) * voxelScaleCm;
		double widthCm = (Math.min(maxX - minX, maxZ - minZ) + 1) * voxelScaleCm;
		double thicknessCm = (maxY - minY + 1) * voxelScaleCm;

		double cellSideM = voxelScaleCm / 100.0;
		double poro = micro.averagePorosity();
		double massKg = equivCells * (cellSideM * cellSideM * cellSideM) * alloy.densityKgM3() * (1.0 - poro * 0.75);

		boolean[] acuteEdge = new boolean[TOTAL_VOXELS];
		double[] localAngles = new double[TOTAL_VOXELS];
		double[] localRadii = new double[TOTAL_VOXELS];
		double[] localSpine = new double[TOTAL_VOXELS];
		Arrays.fill(localAngles, 90.0);

		int[] planeCounts = new int[6];
		int flatCellTotal = 0;
		double sharpestPointScore = 0.0;

		for (int y = 0; y < SIZE; y++) {
			for (int z = 0; z < SIZE; z++) {
				for (int x = 0; x < SIZE; x++) {
					if (!isSolid(x, y, z)) continue;
					int f = getFill(x, y, z);
					boolean[] air = new boolean[]{
							!isSolid(x, y + 1, z), !isSolid(x, y - 1, z),
							!isSolid(x + 1, y, z), !isSolid(x - 1, y, z),
							!isSolid(x, y, z + 1), !isSolid(x, y, z - 1)
					};
					int airAdj = 0;
					for (int d = 0; d < 6; d++) {
						if (air[d]) {
							airAdj++;
							planeCounts[d]++;
						}
					}
					if (airAdj == 1) flatCellTotal++;

					int inX = (air[2] ? -1 : 0) + (air[3] ? 1 : 0);
					int inY = (air[0] ? -1 : 0) + (air[1] ? 1 : 0);
					int inZ = (air[4] ? -1 : 0) + (air[5] ? 1 : 0);

					if (airAdj >= 2 && (inX != 0 || inY != 0 || inZ != 0)) {
						double t0 = thicknessUnitsPerp(x, y, z, inX, inY, inZ);
						double t1 = thicknessUnitsPerp(x + inX, y + inY, z + inZ, inX, inY, inZ);
						double t2 = thicknessUnitsPerp(x + inX * 2, y + inY * 2, z + inZ * 2, inX, inY, inZ);
						// Sub-voxel thinness reduces wedge angle & edge radius naturally during drawing/honing!
						double tipFrac = f / (double) FULL_CELL;
						double taper = Math.max(0.08, 0.11 * Math.max(0, t2 - t0)
								+ 0.07 * Math.max(0, t1 - t0) + 0.08 * tipFrac + Math.max(0, t0 - 1.0) * 0.14);
						double angleDeg = Math.max(9.0, Math.min(86.0, Math.toDegrees(2.0 * Math.atan(taper))));
						MicrostructureState.MicroCell mc = micro.cellAtVoxel(x, y, z);
						double radiusMm = (0.06 + 0.34 * tipFrac) * voxelScaleCm + mc.fractureDamage * 1.4 + mc.porosity * 0.6;
						if (t0 > 1.35) radiusMm *= 1.85;

						int idx = index(x, y, z);
						localAngles[idx] = angleDeg;
						localRadii[idx] = Math.max(0.05, Math.min(4.5, radiusMm));
						localSpine[idx] = t2;

						if (angleDeg <= 58.0 && t0 <= 2.1) {
							acuteEdge[idx] = true;
						}
						if (airAdj >= 3 && t2 >= 1.6) {
							double pScore = Math.min(1.0, (airAdj / 4.0) * (1.0 - Math.min(0.85, radiusMm / 2.6)));
							if (pScore > sharpestPointScore) sharpestPointScore = pScore;
						}
					}
				}
			}
		}

		// Trace longest continuous working bevel edge
		boolean[] visited = new boolean[TOTAL_VOXELS];
		int bestChain = 0;
		double angleSum = 0, radiusSum = 0, spineSum = 0;
		int[] queue = new int[TOTAL_VOXELS];
		for (int idx = 0; idx < TOTAL_VOXELS; idx++) {
			if (!acuteEdge[idx] || visited[idx]) continue;
			int head = 0, tail = 0;
			queue[tail++] = idx;
			visited[idx] = true;
			int len = 0;
			double aS = 0, rS = 0, spS = 0;
			while (head < tail) {
				int cur = queue[head++];
				len++;
				aS += localAngles[cur];
				rS += localRadii[cur];
				spS += localSpine[cur];
				int cx0 = cur & 15, cz0 = (cur >>> 4) & 15, cy0 = (cur >>> 8) & 15;
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						for (int dx = -1; dx <= 1; dx++) {
							int nx = cx0 + dx, ny = cy0 + dy, nz = cz0 + dz;
							if (!inBounds(nx, ny, nz)) continue;
							int nIdx = index(nx, ny, nz);
							if (acuteEdge[nIdx] && !visited[nIdx]) {
								visited[nIdx] = true;
								queue[tail++] = nIdx;
							}
						}
					}
				}
			}
			if (len > bestChain) {
				bestChain = len;
				angleSum = aS;
				radiusSum = rS;
				spineSum = spS;
			}
		}

		double continuousEdgeCm = bestChain > 0 ? Math.min(lengthCm * 1.6, bestChain * voxelScaleCm * 0.78) : 0.0;
		double primaryAngle = bestChain > 0 ? angleSum / bestChain : 74.0;
		double primaryRadius = bestChain > 0 ? radiusSum / bestChain : 2.8;
		double spineCm = bestChain > 0 ? (spineSum / bestChain) * voxelScaleCm : thicknessCm * 0.5;

		int maxPlane = 0;
		for (int pc : planeCounts) maxPlane = Math.max(maxPlane, pc);
		double flatAreaCm2 = Math.max(flatCellTotal * 0.4, maxPlane) * voxelScaleCm * voxelScaleCm;
		double defectDensity = Math.min(1.0, micro.averageFracture() * 0.7 + micro.averagePorosity() * 0.55);

		return new VoxelRockGrid.GeometryMetrics(solidCount, massKg, cx, cy, cz, lengthCm, widthCm, thicknessCm,
				primaryAngle, primaryRadius, continuousEdgeCm, spineCm,
				sharpestPointScore, flatAreaCm2, defectDensity);
	}

	private double thicknessUnitsPerp(int bx, int by, int bz, int inX, int inY, int inZ) {
		if (!inBounds(bx, by, bz)) return 0.0;
		double span = 0.0;
		if (Math.abs(inY) >= Math.abs(inX) && Math.abs(inY) >= Math.abs(inZ)) {
			for (int d = -2; d <= 2; d++) span += getFill(bx + d, by, bz) / (double) FULL_CELL;
		} else {
			for (int d = -2; d <= 2; d++) span += getFill(bx, by + d, bz) / (double) FULL_CELL;
		}
		return span;
	}

	public MetalVolumeGrid copy() {
		MetalVolumeGrid g = new MetalVolumeGrid(this.micro.copy());
		System.arraycopy(this.fill, 0, g.fill, 0, TOTAL_VOXELS);
		g.syncMicroWeights();
		return g;
	}

	public NbtCompound toNbt() {
		NbtCompound t = new NbtCompound();
		List<Integer> sparse = new ArrayList<>();
		for (int i = 0; i < TOTAL_VOXELS; i++) {
			if (fill[i] > 0) {
				sparse.add((i << 12) | (fill[i] & 0xFFF));
			}
		}
		int[] arr = new int[sparse.size()];
		for (int i = 0; i < sparse.size(); i++) arr[i] = sparse.get(i);
		t.putIntArray("Fill", arr);
		t.put("Micro", micro.toNbt());
		return t;
	}

	public static MetalVolumeGrid fromNbt(NbtCompound t) {
		MicrostructureState m = t.contains("Micro") ? MicrostructureState.fromNbt(t.getCompound("Micro")) : new MicrostructureState();
		MetalVolumeGrid g = new MetalVolumeGrid(m);
		int[] arr = t.getIntArray("Fill");
		for (int packed : arr) {
			int idx = (packed >>> 12) & 0xFFF;
			int amt = packed & 0xFFF;
			if (idx >= 0 && idx < TOTAL_VOXELS) {
				g.fill[idx] = (short) Math.min(FULL_CELL, amt);
			}
		}
		g.syncMicroWeights();
		return g;
	}
}
