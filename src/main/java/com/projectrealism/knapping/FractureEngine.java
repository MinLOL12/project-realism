package com.projectrealism.knapping;

import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Seven-variable physical fracture simulator operating on a 16x16x16 {@link VoxelRockGrid}:
 * impact location, impact direction, impact energy, rock material properties, existing cracks &
 * inclusions, local surface geometry/platform angle, and underlying support conditions.
 */
public final class FractureEngine {
	public enum SupportCondition {
		HARD_ANVIL("anvil", 1.22, 0.35),
		FIRM_WOOD("firm", 1.0, 0.22),
		SOFT_EARTH("cushion", 0.72, 0.10),
		OVERHANG("overhang", 1.15, 0.75);

		public final String id;
		public final double reboundMultiplier;
		public final double snapRisk;

		SupportCondition(String id, double reboundMultiplier, double snapRisk) {
			this.id = id;
			this.reboundMultiplier = reboundMultiplier;
			this.snapRisk = snapRisk;
		}
	}

	public enum OutcomeKind {
		CLEAN_FLAKE("clean_flake"),
		THIN_BLADE_SCAR("blade_scar"),
		HINGED_CHIP("hinged_chip"),
		CRUSHED_POWDER("crushed"),
		SPLIT_FAILURE("split_failure"),
		NO_EFFECT("glanced");

		public final String id;

		OutcomeKind(String id) {
			this.id = id;
		}
	}

	public record VoxelHit(int x, int y, int z, int nx, int ny, int nz) {
	}

	public record KnapOutcome(
			OutcomeKind kind,
			int removedVoxels,
			List<DynamicStoneState> detachedFlakes,
			double localPlatformAngleDeg,
			boolean hitCrackedZone
	) {
	}

	private FractureEngine() {
	}

	/**
	 * Exact 3D DDA traversal from {@code localStart} along unit vector {@code dir} (in block [0..1]
	 * coordinates) returning the exact occupied voxel and struck face normal.
	 */
	public static VoxelHit raycastVoxel(VoxelRockGrid grid, Vec3d localStart, Vec3d dir) {
		double eps = 1e-5;
		Vec3d uDir = dir.normalize();
		// Scale to 16x16x16 voxel space (placed block occupies central 14x14x14 or full 16^3 cell grid)
		double gx = localStart.x * VoxelRockGrid.SIZE;
		double gy = localStart.y * VoxelRockGrid.SIZE;
		double gz = localStart.z * VoxelRockGrid.SIZE;

		// Advance slightly to enter bounding box [0, 16]
		double t = 0.0;
		for (int step = 0; step < 144 && t < 36.0; step++) {
			double px = gx + uDir.x * t;
			double py = gy + uDir.y * t;
			double pz = gz + uDir.z * t;
			int vx = (int) Math.floor(px);
			int vy = (int) Math.floor(py);
			int vz = (int) Math.floor(pz);
			if (VoxelRockGrid.inBounds(vx, vy, vz) && grid.isSolid(vx, vy, vz)) {
				// Determine entry normal from voxel local fractional offset
				double fx = (px - vx) - 0.5;
				double fy = (py - vy) - 0.5;
				double fz = (pz - vz) - 0.5;
				int nx = 0, ny = 0, nz = 0;
				if (Math.abs(fx) >= Math.abs(fy) && Math.abs(fx) >= Math.abs(fz)) {
					nx = fx > 0 ? 1 : -1;
				} else if (Math.abs(fy) >= Math.abs(fz)) {
					ny = fy > 0 ? 1 : -1;
				} else {
					nz = fz > 0 ? 1 : -1;
				}
				return new VoxelHit(vx, vy, vz, nx, ny, nz);
			}
			t += 0.25 + eps;
		}
		return null;
	}

	/**
	 * Determines local support under voxel column (hx, hy, hz): detects cantilevers/overhangs
	 * cleanly vs solid underside support.
	 */
	public static SupportCondition resolveSupport(VoxelRockGrid grid, int hx, int hy, int hz,
													SupportCondition baseGroundSupport) {
		boolean airDirectlyBelow = false;
		for (int y = hy - 1; y >= 0; y--) {
			if (!grid.isSolid(hx, y, hz)) {
				airDirectlyBelow = true;
				break;
			}
		}
		if (airDirectlyBelow && hy >= 2) {
			return SupportCondition.OVERHANG;
		}
		return baseGroundSupport;
	}

	/**
	 * Executes one physical impact on {@code stone} at {@code hit} along {@code strikeDir}.
	 * Permanently mutates {@code stone.grid}, separating any disconnected regions.
	 */
	public static KnapOutcome strike(DynamicStoneState stone,
									 VoxelHit hit,
									 Vec3d strikeDir,
									 double impactEnergyJ,
									 StoneMaterial strikerMaterial,
									 SupportCondition baseSupport,
									 Random rng) {
		if (hit == null || !stone.grid.isSolid(hit.x, hit.y, hit.z)) {
			return new KnapOutcome(OutcomeKind.NO_EFFECT, 0, List.of(), 90.0, false);
		}
		VoxelRockGrid grid = stone.grid;
		int initialCount = grid.countSolid();
		if (initialCount <= 6) {
			return new KnapOutcome(OutcomeKind.NO_EFFECT, 0, List.of(), 90.0, false);
		}

		// 1. Evaluate local surface geometry / platform angle & free face count
		int airFaces = 0;
		int outX = 0, outY = 0, outZ = 0;
		int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		for (int[] d : dirs) {
			if (!grid.isSolid(hit.x + d[0], hit.y + d[1], hit.z + d[2])) {
				airFaces++;
				outX += d[0];
				outY += d[1];
				outZ += d[2];
			}
		}
		// Check adjacent platform quality (near an exposed margin or ridge)
		int nearAir = 0;
		for (int dz = -1; dz <= 1; dz++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dx = -1; dx <= 1; dx++) {
					if (!grid.isSolid(hit.x + dx, hit.y + dy, hit.z + dz)) nearAir++;
				}
			}
		}
		double edgeExposure = Math.min(1.0, (airFaces * 0.25 + nearAir * 0.032));
		double platformAngleDeg = Math.max(15.0, Math.min(89.0, 92.0 - edgeExposure * 62.0));

		// 2. Strike direction vs inward surface normal & free-edge direction
		Vec3d sDir = strikeDir.normalize();
		Vec3d faceNormal = new Vec3d(hit.nx, hit.ny, hit.nz);
		double directness = Math.max(0.0, -sDir.dotProduct(faceNormal)); // 1 = straight in, 0 = glancing
		double hardnessRatio = Math.max(0.35, Math.min(1.65, strikerMaterial.mohsHardness / Math.max(2.0, stone.material.mohsHardness)));
		SupportCondition support = resolveSupport(grid, hit.x, hit.y, hit.z, baseSupport);

		double effEnergy = impactEnergyJ * hardnessRatio * support.reboundMultiplier * (0.45 + 0.55 * directness);
		int existingCrack = grid.getCrackLevel(hit.x, hit.y, hit.z);
		boolean hitCrack = existingCrack > 8;
		boolean hitInclusion = grid.hasInclusion(hit.x, hit.y, hit.z);

		// Soft cushion + ultra-light strike against tough rock with poor platform fails to flake
		double minEnergyFactor = edgeExposure >= 0.35 ? 0.28 : 0.45;
		if (effEnergy < minEnergyFactor * stone.material.toughness && existingCrack < 12) {
			grid.addCrack(hit.x, hit.y, hit.z, 5);
			return new KnapOutcome(OutcomeKind.NO_EFFECT, 0, List.of(), platformAngleDeg, hitCrack);
		}

		// 3. Determine failure mode: precision pressure-flake vs conchoidal edge flake vs split vs crumble
		boolean precisionTap = impactEnergyJ <= 0.85 && baseSupport != SupportCondition.OVERHANG;
		double effectiveConchoidal = edgeExposure >= 0.40
				? Math.max(stone.material.conchoidal, 0.42 * Math.min(1.0, hardnessRatio))
				: stone.material.conchoidal;
		double cleavageChance = effectiveConchoidal * edgeExposure * (0.55 + 0.45 * (1.0 - Math.abs(directness - 0.58)));
		boolean overStruck = !precisionTap && (effEnergy > (2.1 + stone.material.toughness * 2.2)
				|| (support == SupportCondition.OVERHANG && effEnergy > 1.25 * stone.material.toughness)
				|| (existingCrack > 24 && effEnergy > 1.1));

		List<int[]> toRemove = new ArrayList<>();
		OutcomeKind kind;

		if (precisionTap && (edgeExposure >= 0.15 || existingCrack >= 12)) {
			// Controlled Shift+Right-Click precision pressure-flaking: cleanly removes 1..2 aimed voxels!
			kind = OutcomeKind.CLEAN_FLAKE;
			toRemove.add(new int[]{hit.x, hit.y, hit.z});
			if (edgeExposure >= 0.45 && stone.material.conchoidal >= 0.55) {
				int nx = hit.x + (int) Math.round(sDir.x);
				int ny = hit.y + (int) Math.round(sDir.y);
				int nz = hit.z + (int) Math.round(sDir.z);
				if (grid.isSolid(nx, ny, nz)) {
					toRemove.add(new int[]{nx, ny, nz});
				}
			}
		} else if (overStruck && (support == SupportCondition.OVERHANG || existingCrack > 18 || effEnergy > 3.4)) {
			// Catastrophic split / shear fracture across weakened or unsupported plane
			kind = OutcomeKind.SPLIT_FAILURE;
			Vec3d planeNormal = (existingCrack > 18 && outX + outY + outZ != 0)
					? new Vec3d(outX, outY, outZ).normalize()
					: sDir.crossProduct(new Vec3d(0.3, 1.0, 0.2)).normalize();
			if (planeNormal.lengthSquared() < 0.01) planeNormal = new Vec3d(1, 0, 0);
			double reach = 3.2 + effEnergy * 1.45;
			for (int y = 0; y < VoxelRockGrid.SIZE; y++) {
				for (int z = 0; z < VoxelRockGrid.SIZE; z++) {
					for (int x = 0; x < VoxelRockGrid.SIZE; x++) {
						if (!grid.isSolid(x, y, z)) continue;
						double dx = x - hit.x, dy = y - hit.y, dz = z - hit.z;
						double dSq = dx * dx + dy * dy + dz * dz;
						if (dSq > reach * reach) continue;
						double pDist = Math.abs(dx * planeNormal.x + dy * planeNormal.y + dz * planeNormal.z);
						int cr = grid.getCrackLevel(x, y, z);
						if (pDist < (0.85 + cr * 0.025) && (dx * sDir.x + dy * sDir.y + dz * sDir.z) >= -1.2) {
							toRemove.add(new int[]{x, y, z});
						}
					}
				}
			}
		} else if (cleavageChance > 0.36 && platformAngleDeg < 76.0) {
			// Hertzian / conchoidal flake propagation: removes an oriented thin outer sheet!
			boolean razorScar = stone.material.conchoidal > 0.72 && directness >= 0.30 && directness <= 0.80;
			kind = razorScar ? OutcomeKind.THIN_BLADE_SCAR : OutcomeKind.CLEAN_FLAKE;

			double flakeLen = (1.9 + Math.min(4.2, effEnergy * 1.35)) * (0.65 + 0.55 * stone.material.conchoidal);
			double flakeWidth = 1.6 + Math.min(2.6, effEnergy * 0.75);
			double flakeDepth = razorScar ? 1.35 : 1.85;
			if (hitInclusion) {
				// Inclusion prematurely steps/hinges the flake!
				flakeLen *= 0.62;
				kind = OutcomeKind.HINGED_CHIP;
			}

			// Outward normal biases removal strictly to exterior surface shell (never gutting interior bulk!)
			Vec3d outVec = (outX != 0 || outY != 0 || outZ != 0)
					? new Vec3d(outX, outY, outZ).normalize()
					: faceNormal;
			Vec3d propDir = sDir.multiply(0.68).add(outVec.multiply(-0.22)).normalize();

			for (int y = 0; y < VoxelRockGrid.SIZE; y++) {
				for (int z = 0; z < VoxelRockGrid.SIZE; z++) {
					for (int x = 0; x < VoxelRockGrid.SIZE; x++) {
						if (!grid.isSolid(x, y, z)) continue;
						double rx = x - hit.x, ry = y - hit.y, rz = z - hit.z;
						double along = rx * propDir.x + ry * propDir.y + rz * propDir.z;
						if (along < -0.7 || along > flakeLen) continue;
						double outDepth = rx * outVec.x + ry * outVec.y + rz * outVec.z;
						// Stay close to outer shell along outVec
						if (outDepth < -flakeDepth) continue;
						double perpSq = (rx * rx + ry * ry + rz * rz) - along * along;
						double maxRad = flakeWidth * (0.55 + 0.55 * (1.0 - Math.max(0.0, along) / flakeLen));
						if (perpSq <= maxRad * maxRad) {
							// Coarse inclusion deflects edge slightly
							if (grid.hasInclusion(x, y, z) && rng.nextDouble() < 0.45) continue;
							toRemove.add(new int[]{x, y, z});
						}
					}
				}
			}
		} else {
			// Poor interior platform or granular non-conchoidal stone -> local crushing + stress cracking!
			kind = OutcomeKind.CRUSHED_POWDER;
			double crushR = Math.min(2.4, 0.9 + effEnergy * 0.48 * (1.15 - stone.material.toughness * 0.4));
			for (int dy = -2; dy <= 2; dy++) {
				for (int dz = -2; dz <= 2; dz++) {
					for (int dx = -2; dx <= 2; dx++) {
						int vx = hit.x + dx, vy = hit.y + dy, vz = hit.z + dz;
						if (!grid.isSolid(vx, vy, vz)) continue;
						double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
						if (d <= crushR) {
							toRemove.add(new int[]{vx, vy, vz});
						} else if (d <= crushR + 1.4) {
							grid.addCrack(vx, vy, vz, (int) Math.round(6 + effEnergy * 5.0));
						}
					}
				}
			}
		}

		// Always remove at least the struck voxel if any action occurred (except NO_EFFECT)
		if (toRemove.isEmpty()) {
			toRemove.add(new int[]{hit.x, hit.y, hit.z});
		}

		// Prevent deleting the entire rock in one blow if < 6 cells would remain (unless tiny pebble)
		int maxRemovable = Math.max(1, initialCount - 5);
		int removed = 0;
		for (int[] c : toRemove) {
			if (removed >= maxRemovable) break;
			if (grid.isSolid(c[0], c[1], c[2])) {
				grid.setSolid(c[0], c[1], c[2], false);
				removed++;
			}
		}

		// Seed subtle radial micro-fractures around the fresh scar perimeter
		int rimStress = kind == OutcomeKind.SPLIT_FAILURE ? 14
				: (kind == OutcomeKind.CRUSHED_POWDER ? 10 : (precisionTap ? 1 : 3));
		for (int[] c : toRemove) {
			for (int[] d : dirs) {
				int nx = c[0] + d[0], ny = c[1] + d[1], nz = c[2] + d[2];
				if (grid.isSolid(nx, ny, nz)) {
					grid.addCrack(nx, ny, nz, rimStress + (rng.nextInt(4)));
				}
			}
		}

		// Flood-fill connected components so any cut-off island breaks off cleanly!
		List<VoxelRockGrid> islands = grid.extractDetachedComponents();
		List<DynamicStoneState> droppedFlakes = new ArrayList<>();
		for (VoxelRockGrid island : islands) {
			int islCount = island.countSolid();
			removed += islCount;
			// Substantial spalls/blades (>= 18 voxels) drop as real usable flake stones!
			if (islCount >= 18) {
				DynamicStoneState flake = new DynamicStoneState(stone.material, stone.voxelScaleCm, island);
				droppedFlakes.add(flake);
			}
		}

		stone.invalidateGeometry();
		return new KnapOutcome(kind, removed, droppedFlakes, platformAngleDeg, hitCrack);
	}
}
