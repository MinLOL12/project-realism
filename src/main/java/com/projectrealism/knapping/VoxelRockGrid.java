package com.projectrealism.knapping;

import net.minecraft.nbt.NbtCompound;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/**
 * 16 x 16 x 16 volumetric rock cell grid (4,096 voxels) storing solid geometry, internal micro-cracks,
 * coarse mineral inclusions, and derived surface/edge geometry with zero predefined tool categories.
 */
public final class VoxelRockGrid {
	public static final int SIZE = 16;
	public static final int TOTAL_VOXELS = SIZE * SIZE * SIZE; // 4,096 cells

	private static final int FLAG_INCLUSION = 0x40;
	private static final int CRACK_MASK = 0x3F; // 0..63 internal damage level

	/** Solid occupancy mask (64 longs = 4,096 bits). */
	private final long[] solidBits = new long[TOTAL_VOXELS / 64];
	/** Per-voxel micro-crack stress (bits 0..5) and mineral inclusion flaw (bit 6). */
	private final byte[] cellMeta = new byte[TOTAL_VOXELS];

	public VoxelRockGrid() {
	}

	public static int index(int x, int y, int z) {
		return (y << 8) | (z << 4) | x;
	}

	public static boolean inBounds(int x, int y, int z) {
		return x >= 0 && x < SIZE && y >= 0 && y < SIZE && z >= 0 && z < SIZE;
	}

	public boolean isSolid(int x, int y, int z) {
		if (!inBounds(x, y, z)) return false;
		int idx = index(x, y, z);
		return ((solidBits[idx >>> 6] >>> (idx & 63)) & 1L) != 0L;
	}

	public boolean isSolidIdx(int idx) {
		return ((solidBits[idx >>> 6] >>> (idx & 63)) & 1L) != 0L;
	}

	public void setSolid(int x, int y, int z, boolean value) {
		if (!inBounds(x, y, z)) return;
		setSolidIdx(index(x, y, z), value);
	}

	public void setSolidIdx(int idx, boolean value) {
		if (value) {
			solidBits[idx >>> 6] |= (1L << (idx & 63));
		} else {
			solidBits[idx >>> 6] &= ~(1L << (idx & 63));
			cellMeta[idx] = 0;
		}
	}

	public int getCrackLevel(int x, int y, int z) {
		if (!inBounds(x, y, z)) return 0;
		return cellMeta[index(x, y, z)] & CRACK_MASK;
	}

	public void addCrack(int x, int y, int z, int delta) {
		if (!inBounds(x, y, z) || !isSolid(x, y, z)) return;
		int idx = index(x, y, z);
		int cur = cellMeta[idx] & CRACK_MASK;
		int next = Math.max(0, Math.min(CRACK_MASK, cur + delta));
		cellMeta[idx] = (byte) ((cellMeta[idx] & FLAG_INCLUSION) | next);
	}

	public boolean hasInclusion(int x, int y, int z) {
		if (!inBounds(x, y, z)) return false;
		return (cellMeta[index(x, y, z)] & FLAG_INCLUSION) != 0;
	}

	public void setInclusion(int x, int y, int z, boolean inc) {
		if (!inBounds(x, y, z)) return;
		int idx = index(x, y, z);
		if (inc) cellMeta[idx] |= FLAG_INCLUSION;
		else cellMeta[idx] &= ~FLAG_INCLUSION;
	}

	public int countSolid() {
		int sum = 0;
		for (long word : solidBits) {
			sum += Long.bitCount(word);
		}
		return sum;
	}

	public VoxelRockGrid copy() {
		VoxelRockGrid g = new VoxelRockGrid();
		System.arraycopy(this.solidBits, 0, g.solidBits, 0, this.solidBits.length);
		System.arraycopy(this.cellMeta, 0, g.cellMeta, 0, this.cellMeta.length);
		return g;
	}

	/** Generates a natural, asymmetric irregular river cobble / nodule seeded with cracks & inclusions. */
	public static VoxelRockGrid generateNodule(StoneMaterial material, long seed, double sizeScale) {
		VoxelRockGrid grid = new VoxelRockGrid();
		Random rng = new Random(seed);
		double s = Math.max(0.45, Math.min(1.0, sizeScale));
		double rx = (5.4 + rng.nextDouble() * 1.9) * s;
		double ry = (3.4 + rng.nextDouble() * 1.6) * s;
		double rz = (5.6 + rng.nextDouble() * 2.0) * s;

		// Random multi-lobe harmonic asymmetry so no two rocks are identical spheres
		double p1 = rng.nextDouble() * Math.PI * 2.0;
		double p2 = rng.nextDouble() * Math.PI * 2.0;
		double a1 = 0.08 + rng.nextDouble() * 0.14;
		double a2 = 0.05 + rng.nextDouble() * 0.10;
		double flatBottomCut = -ry * (0.68 + rng.nextDouble() * 0.18);

		for (int y = 0; y < SIZE; y++) {
			double dy = y - 6.0;
			if (dy < flatBottomCut) continue;
			for (int z = 0; z < SIZE; z++) {
				double dz = z - 7.5;
				for (int x = 0; x < SIZE; x++) {
					double dx = x - 7.5;
					double angle = Math.atan2(dz, dx);
					double asym = 1.0 + a1 * Math.sin(2.0 * angle + p1) + a2 * Math.cos(3.0 * angle + p2);
					double q = (dx * dx) / (rx * rx * asym) + (dy * dy) / (ry * ry) + (dz * dz) / (rz * rz * asym);
					if (q <= 1.0 + (rng.nextDouble() - 0.5) * 0.12) {
						grid.setSolid(x, y, z, true);
						if (rng.nextDouble() < material.inclusionRate) {
							grid.setInclusion(x, y, z, true);
						}
					}
				}
			}
		}

		// Seed 1..2 subtle natural bedding / incipient fracture planes inside larger nodules
		int planes = rng.nextInt(3);
		for (int p = 0; p < planes; p++) {
			double nx = rng.nextDouble() - 0.5;
			double ny = rng.nextDouble() - 0.5;
			double nz = rng.nextDouble() - 0.5;
			double len = Math.max(0.01, Math.sqrt(nx * nx + ny * ny + nz * nz));
			nx /= len;
			ny /= len;
			nz /= len;
			double off = (rng.nextDouble() - 0.5) * 4.0;
			int severity = 6 + rng.nextInt(14);
			for (int y = 0; y < SIZE; y++) {
				for (int z = 0; z < SIZE; z++) {
					for (int x = 0; x < SIZE; x++) {
						if (!grid.isSolid(x, y, z)) continue;
						double d = Math.abs((x - 7.5) * nx + (y - 6.0) * ny + (z - 7.5) * nz - off);
						if (d < 0.65 && rng.nextDouble() < 0.60) {
							grid.addCrack(x, y, z, severity);
						}
					}
				}
			}
		}
		grid.settleOnBase();
		return grid;
	}

	/** Shifts occupied voxels downward so the lowest voxel rests cleanly at y = 0. */
	public void settleOnBase() {
		int minY = SIZE;
		for (int y = 0; y < SIZE; y++) {
			for (int z = 0; z < SIZE; z++) {
				for (int x = 0; x < SIZE; x++) {
					if (isSolid(x, y, z)) {
						minY = Math.min(minY, y);
					}
				}
			}
		}
		if (minY <= 0 || minY >= SIZE) return;
		for (int y = 0; y < SIZE - minY; y++) {
			for (int z = 0; z < SIZE; z++) {
				for (int x = 0; x < SIZE; x++) {
					int src = index(x, y + minY, z);
					int dst = index(x, y, z);
					boolean occ = isSolidIdx(src);
					byte meta = cellMeta[src];
					setSolidIdx(dst, occ);
					if (occ) cellMeta[dst] = meta;
				}
			}
		}
		for (int y = SIZE - minY; y < SIZE; y++) {
			for (int z = 0; z < SIZE; z++) {
				for (int x = 0; x < SIZE; x++) {
					setSolid(x, y, z, false);
				}
			}
		}
	}

	/**
	 * Rotates the rock 90 degrees around X, Y, or Z and re-centers horizontally & settles onto y=0
	 * so players can flip and access every face naturally during knapping.
	 */
	public void rotate90(int axis) {
		long[] nextSolid = new long[solidBits.length];
		byte[] nextMeta = new byte[TOTAL_VOXELS];
		for (int y = 0; y < SIZE; y++) {
			for (int z = 0; z < SIZE; z++) {
				for (int x = 0; x < SIZE; x++) {
					if (!isSolid(x, y, z)) continue;
					int nx, ny, nz;
					if (axis == 0) { // roll around X
						nx = x;
						ny = z;
						nz = SIZE - 1 - y;
					} else if (axis == 1) { // yaw around Y
						nx = SIZE - 1 - z;
						ny = y;
						nz = x;
					} else { // pitch/roll around Z
						nx = y;
						ny = SIZE - 1 - x;
						nz = z;
					}
					int dst = index(nx, ny, nz);
					nextSolid[dst >>> 6] |= (1L << (dst & 63));
					nextMeta[dst] = cellMeta[index(x, y, z)];
				}
			}
		}
		System.arraycopy(nextSolid, 0, solidBits, 0, solidBits.length);
		System.arraycopy(nextMeta, 0, cellMeta, 0, cellMeta.length);
		settleOnBase();
	}

	/**
	 * Runs 6-connected component analysis after voxel removal. Keeps the largest connected body in
	 * place and returns any detached secondary chunks as independent {@link VoxelRockGrid}s.
	 */
	public List<VoxelRockGrid> extractDetachedComponents() {
		boolean[] visited = new boolean[TOTAL_VOXELS];
		List<int[]> components = new ArrayList<>();
		int[] queue = new int[TOTAL_VOXELS];

		for (int idx = 0; idx < TOTAL_VOXELS; idx++) {
			if (!isSolidIdx(idx) || visited[idx]) continue;
			int head = 0, tail = 0;
			queue[tail++] = idx;
			visited[idx] = true;
			while (head < tail) {
				int cur = queue[head++];
				int x = cur & 15;
				int z = (cur >>> 4) & 15;
				int y = (cur >>> 8) & 15;
				if (x > 0) { int n = cur - 1; if (isSolidIdx(n) && !visited[n]) { visited[n] = true; queue[tail++] = n; } }
				if (x < 15) { int n = cur + 1; if (isSolidIdx(n) && !visited[n]) { visited[n] = true; queue[tail++] = n; } }
				if (z > 0) { int n = cur - 16; if (isSolidIdx(n) && !visited[n]) { visited[n] = true; queue[tail++] = n; } }
				if (z < 15) { int n = cur + 16; if (isSolidIdx(n) && !visited[n]) { visited[n] = true; queue[tail++] = n; } }
				if (y > 0) { int n = cur - 256; if (isSolidIdx(n) && !visited[n]) { visited[n] = true; queue[tail++] = n; } }
				if (y < 15) { int n = cur + 256; if (isSolidIdx(n) && !visited[n]) { visited[n] = true; queue[tail++] = n; } }
			}
			components.add(Arrays.copyOf(queue, tail));
		}

		if (components.size() <= 1) return List.of();

		// Find largest component to remain as primary rock
		int largestIdx = 0;
		for (int i = 1; i < components.size(); i++) {
			if (components.get(i).length > components.get(largestIdx).length) {
				largestIdx = i;
			}
		}

		List<VoxelRockGrid> detached = new ArrayList<>();
		for (int i = 0; i < components.size(); i++) {
			if (i == largestIdx) continue;
			int[] comp = components.get(i);
			VoxelRockGrid sub = new VoxelRockGrid();
			for (int cIdx : comp) {
				sub.setSolidIdx(cIdx, true);
				sub.cellMeta[cIdx] = this.cellMeta[cIdx];
				setSolidIdx(cIdx, false);
			}
			sub.settleOnBase();
			detached.add(sub);
		}
		return detached;
	}

	/**
	 * Pure 3D voxel geometry inspection: derives mass, CoM, dimensions, wedge angles, edge radii,
	 * continuous cutting edge length, point sharpness, flat scraping area, and fracture exposure.
	 */
	public GeometryMetrics measure(StoneMaterial material, double voxelScaleCm) {
		int count = 0;
		double sumX = 0, sumY = 0, sumZ = 0;
		int minX = SIZE, minY = SIZE, minZ = SIZE;
		int maxX = -1, maxY = -1, maxZ = -1;
		int crackSum = 0;
		int crackedVoxels = 0;

		for (int y = 0; y < SIZE; y++) {
			for (int z = 0; z < SIZE; z++) {
				for (int x = 0; x < SIZE; x++) {
					if (!isSolid(x, y, z)) continue;
					count++;
					sumX += x + 0.5;
					sumY += y + 0.5;
					sumZ += z + 0.5;
					if (x < minX) minX = x;
					if (x > maxX) maxX = x;
					if (y < minY) minY = y;
					if (y > maxY) maxY = y;
					if (z < minZ) minZ = z;
					if (z > maxZ) maxZ = z;
					int cr = getCrackLevel(x, y, z);
					if (cr > 4) {
						crackSum += cr;
						crackedVoxels++;
					}
				}
			}
		}

		if (count == 0) {
			return new GeometryMetrics(0, 0.0, 8, 8, 8, 0, 0, 0, 90.0, 4.0, 0.0, 4.0, 0.0, 0.0, 0.0);
		}

		double cx = sumX / count;
		double cy = sumY / count;
		double cz = sumZ / count;
		double lengthCm = (Math.max(maxX - minX, maxZ - minZ) + 1) * voxelScaleCm;
		double widthCm = (Math.min(maxX - minX, maxZ - minZ) + 1) * voxelScaleCm;
		double thicknessCm = (maxY - minY + 1) * voxelScaleCm;

		double cellSideM = voxelScaleCm / 100.0;
		double massKg = count * (cellSideM * cellSideM * cellSideM) * material.densityKgM3;

		// Identify edge voxels, local wedge angles, edge radii, flat patch areas, and point convergence
		boolean[] acuteEdge = new boolean[TOTAL_VOXELS];
		double[] localAngles = new double[TOTAL_VOXELS];
		double[] localRadii = new double[TOTAL_VOXELS];
		double[] localSpine = new double[TOTAL_VOXELS];
		Arrays.fill(localAngles, 90.0);

		int[] planeCounts = new int[6]; // +Y, -Y, +X, -X, +Z, -Z flat face exposure counts
		int flatCellTotal = 0;
		double sharpestPointScore = 0.0;

		for (int y = 0; y < SIZE; y++) {
			for (int z = 0; z < SIZE; z++) {
				for (int x = 0; x < SIZE; x++) {
					if (!isSolid(x, y, z)) continue;
					int airAdj = 0;
					boolean[] air = new boolean[]{
							!isSolid(x, y + 1, z), !isSolid(x, y - 1, z),
							!isSolid(x + 1, y, z), !isSolid(x - 1, y, z),
							!isSolid(x, y, z + 1), !isSolid(x, y, z - 1)
					};
					for (int d = 0; d < 6; d++) {
						if (air[d]) {
							airAdj++;
							planeCounts[d]++;
						}
					}
					if (airAdj == 0) continue;
					if (airAdj == 1) flatCellTotal++;

					// Estimate local wedge angle across whichever horizontal or vertical direction enters interior
					int inX = (air[2] ? -1 : 0) + (air[3] ? 1 : 0);
					int inY = (air[0] ? -1 : 0) + (air[1] ? 1 : 0);
					int inZ = (air[4] ? -1 : 0) + (air[5] ? 1 : 0);

					if (airAdj >= 2 && (inX != 0 || inY != 0 || inZ != 0)) {
						// Measure thickness 1 voxel and 2 voxels behind the edge along inward normal
						int t1 = measureThicknessPerp(x + inX, y + inY, z + inZ, inX, inY, inZ);
						int t2 = measureThicknessPerp(x + inX * 2, y + inY * 2, z + inZ * 2, inX, inY, inZ);
						int t0 = measureThicknessPerp(x, y, z, inX, inY, inZ);
						double taper = Math.max(0.11, 0.11 * Math.max(0, t2 - t0)
								+ 0.07 * Math.max(0, t1 - t0) + 0.11 + Math.max(0, t0 - 1) * 0.14);
						double angleDeg = Math.max(12.0, Math.min(86.0, Math.toDegrees(2.0 * Math.atan(taper))));
						// Material acuity + chipped crack roughness determine micro-tip radius (mm)
						int cr = getCrackLevel(x, y, z);
						double radiusMm = (1.18 - material.acuityPotential * 0.92) * voxelScaleCm * (t0 > 1 ? 2.1 : 0.75)
								+ (cr / 63.0) * 1.4;

						int idx = index(x, y, z);
						localAngles[idx] = angleDeg;
						localRadii[idx] = Math.max(0.10, Math.min(5.0, radiusMm));
						localSpine[idx] = t2;

						if (angleDeg <= 58.0 && t0 <= 2) {
							acuteEdge[idx] = true;
						}
						// Reinforced point: exposed on 3+ faces AND backed by solid interior stem
						if (airAdj >= 3 && t2 >= 2) {
							double pScore = Math.min(1.0, (airAdj / 4.2) * (1.0 - Math.min(0.8, radiusMm / 3.0))
									* (t2 >= 3 ? 1.0 : 0.72));
							if (pScore > sharpestPointScore) sharpestPointScore = pScore;
						}
					}
				}
			}
		}

		// Trace 26-connected continuous acute edge chains to find L_effective & edge averages
		boolean[] visited = new boolean[TOTAL_VOXELS];
		int bestChainCount = 0;
		double bestChainAngleSum = 0.0;
		double bestChainRadiusSum = 0.0;
		double bestChainSpineSum = 0.0;

		ArrayDeque<Integer> bfs = new ArrayDeque<>();
		for (int idx = 0; idx < TOTAL_VOXELS; idx++) {
			if (!acuteEdge[idx] || visited[idx]) continue;
			bfs.clear();
			bfs.add(idx);
			visited[idx] = true;
			int len = 0;
			double aSum = 0, rSum = 0, spSum = 0;
			while (!bfs.isEmpty()) {
				int cur = bfs.poll();
				len++;
				aSum += localAngles[cur];
				rSum += localRadii[cur];
				spSum += localSpine[cur];
				int x = cur & 15;
				int z = (cur >>> 4) & 15;
				int y = (cur >>> 8) & 15;
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						for (int dx = -1; dx <= 1; dx++) {
							if (dx == 0 && dy == 0 && dz == 0) continue;
							int nx = x + dx, ny = y + dy, nz = z + dz;
							if (!inBounds(nx, ny, nz)) continue;
							int nIdx = index(nx, ny, nz);
							if (acuteEdge[nIdx] && !visited[nIdx]) {
								visited[nIdx] = true;
								bfs.add(nIdx);
							}
						}
					}
				}
			}
			if (len > bestChainCount) {
				bestChainCount = len;
				bestChainAngleSum = aSum;
				bestChainRadiusSum = rSum;
				bestChainSpineSum = spSum;
			}
		}

		double continuousEdgeCm = bestChainCount > 0 ? Math.min(lengthCm * 1.6, bestChainCount * voxelScaleCm * 0.78) : 0.0;
		double primaryAngle = bestChainCount > 0 ? bestChainAngleSum / bestChainCount : 75.0;
		double primaryRadius = bestChainCount > 0 ? bestChainRadiusSum / bestChainCount : 3.2;
		double spineThickness = bestChainCount > 0 ? (bestChainSpineSum / bestChainCount) * voxelScaleCm : thicknessCm * 0.5;

		int maxPlane = 0;
		for (int pc : planeCounts) maxPlane = Math.max(maxPlane, pc);
		double flatAreaCm2 = Math.max(flatCellTotal * 0.4, maxPlane) * voxelScaleCm * voxelScaleCm;
		double fractureDensity = count > 0 ? Math.min(1.0, (crackSum / 63.0 + crackedVoxels * 0.25) / Math.max(10, count * 0.35)) : 0.0;

		return new GeometryMetrics(count, massKg, cx, cy, cz, lengthCm, widthCm, thicknessCm,
				primaryAngle, primaryRadius, continuousEdgeCm, spineThickness,
				sharpestPointScore, flatAreaCm2, fractureDensity);
	}

	private int measureThicknessPerp(int bx, int by, int bz, int inX, int inY, int inZ) {
		if (!inBounds(bx, by, bz)) return 0;
		// Measure transverse thickness orthogonal to inward normal
		int span = 0;
		if (Math.abs(inY) >= Math.abs(inX) && Math.abs(inY) >= Math.abs(inZ)) {
			for (int d = -2; d <= 2; d++) if (isSolid(bx + d, by, bz)) span++;
		} else {
			for (int d = -2; d <= 2; d++) if (isSolid(bx, by + d, bz)) span++;
		}
		return span;
	}

	public NbtCompound toNbt() {
		NbtCompound t = new NbtCompound();
		t.putLongArray("Solid", solidBits);
		// Sparse pack only non-zero crack/inclusion cells for compact ItemStack NBT
		List<Integer> sparse = new ArrayList<>();
		for (int i = 0; i < TOTAL_VOXELS; i++) {
			if (cellMeta[i] != 0) {
				sparse.add((i << 8) | (cellMeta[i] & 0xFF));
			}
		}
		int[] arr = new int[sparse.size()];
		for (int i = 0; i < sparse.size(); i++) arr[i] = sparse.get(i);
		t.putIntArray("Meta", arr);
		return t;
	}

	public static VoxelRockGrid fromNbt(NbtCompound t) {
		VoxelRockGrid g = new VoxelRockGrid();
		long[] s = t.getLongArray("Solid");
		if (s.length == g.solidBits.length) {
			System.arraycopy(s, 0, g.solidBits, 0, s.length);
		}
		int[] meta = t.getIntArray("Meta");
		for (int packed : meta) {
			int idx = (packed >>> 8) & 0xFFF;
			g.cellMeta[idx] = (byte) (packed & 0xFF);
		}
		return g;
	}

	public record GeometryMetrics(
			int voxelCount,
			double massKg,
			double centerX,
			double centerY,
			double centerZ,
			double lengthCm,
			double widthCm,
			double thicknessCm,
			double primaryEdgeAngleDeg,
			double primaryEdgeRadiusMm,
			double continuousEdgeCm,
			double edgeSpineCm,
			double pointScore,
			double flatSurfaceAreaCm2,
			double fractureDensity
	) {
	}
}
