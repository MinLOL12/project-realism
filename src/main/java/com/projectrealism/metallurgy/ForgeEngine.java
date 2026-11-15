package com.projectrealism.metallurgy;

import com.projectrealism.knapping.FractureEngine;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * Mass-conserving plastic forging and abrasive honing simulator operating on {@link DynamicMetalState}.
 *
 * <p>Hammer impacts deform metal by squeezing sub-voxel volume out of the contact zone and flowing it
 * laterally along least-resistance gradients (drawing edges thin, flattening thick plates, curving ribs,
 * consolidating bloomery iron pores/slag) rather than deleting voxels. Cold hammering work-hardens
 * internal microstructure until ductility limits fracture unless annealed.</p>
 */
public final class ForgeEngine {
	public enum ForgeOutcomeKind {
		SMOOTH_DEFORM("forged"),
		EDGE_DRAWN("edge_drawn"),
		SLAG_EXPELLED("consolidated"),
		WORK_CRACKED("cracked"),
		NO_DEFORM("glanced");

		public final String id;

		ForgeOutcomeKind(String id) {
			this.id = id;
		}
	}

	public record ForgeOutcome(
			ForgeOutcomeKind kind,
			long displacedMillivoxels,
			double slagExpelledFrac,
			double avgDislocationAfter,
			boolean cracked
	) {
	}

	private ForgeEngine() {
	}

	/** Exact raycast onto occupied metal voxels in [0..1]^3 block coordinates. */
	public static FractureEngine.VoxelHit raycastMetal(MetalVolumeGrid grid, Vec3d localStart, Vec3d dir) {
		Vec3d uDir = dir.normalize();
		double gx = localStart.x * MetalVolumeGrid.SIZE;
		double gy = localStart.y * MetalVolumeGrid.SIZE;
		double gz = localStart.z * MetalVolumeGrid.SIZE;
		double t = 0.0;
		for (int step = 0; step < 144 && t < 36.0; step++) {
			double px = gx + uDir.x * t;
			double py = gy + uDir.y * t;
			double pz = gz + uDir.z * t;
			int vx = (int) Math.floor(px);
			int vy = (int) Math.floor(py);
			int vz = (int) Math.floor(pz);
			if (grid.isSolid(vx, vy, vz)) {
				double fx = (px - vx) - 0.5;
				double fy = (py - vy) - 0.5;
				double fz = (pz - vz) - 0.5;
				int nx = 0, ny = 0, nz = 0;
				if (Math.abs(fx) >= Math.abs(fy) && Math.abs(fx) >= Math.abs(fz)) nx = fx > 0 ? 1 : -1;
				else if (Math.abs(fy) >= Math.abs(fz)) ny = fy > 0 ? 1 : -1;
				else nz = fz > 0 ? 1 : -1;
				return new FractureEngine.VoxelHit(vx, vy, vz, nx, ny, nz);
			}
			t += 0.25001;
		}
		return null;
	}

	/**
	 * Executes one physical hammer strike on {@code metal} at {@code hit}.
	 *
	 * @param hammerContactRadiusVox footprint radius of hammer face (narrow peen ~0.95 -> flat hammer ~2.1)
	 * @param hammerHardness         hardness of striking rock/metal face (Mohs equivalent)
	 * @param impactEnergyJ          strike energy modulated by mass & swing posture
	 * @param biasDir                preferred directional draw vector (e.g. player look horizontal projection)
	 */
	public static ForgeOutcome hammerStrike(DynamicMetalState metal,
											FractureEngine.VoxelHit hit,
											double hammerContactRadiusVox,
											double hammerHardness,
											double impactEnergyJ,
											Vec3d biasDir,
											Random rng) {
		if (hit == null || !metal.grid.isSolid(hit.x(), hit.y(), hit.z())) {
			return new ForgeOutcome(ForgeOutcomeKind.NO_DEFORM, 0, 0.0,
					metal.grid.micro.averageDislocation(), false);
		}

		MetalVolumeGrid grid = metal.grid;
		AlloyComposition alloy = metal.alloy;
		double tempK = metal.temperatureK;
		double solidusK = alloy.solidusTempK();
		double recrystK = alloy.recrystallizationTempK();

		// Flow resistance drops exponentially once heated near/above recrystallization temperature
		double thermalSoft = tempK >= recrystK
				? Math.max(0.22, 1.0 - 0.72 * Math.min(1.0, (tempK - recrystK) / Math.max(120.0, solidusK - recrystK)))
				: 1.0 + Math.max(0.0, (420.0 - tempK) / 600.0);
		double effHardness = metal.effectiveHardness() * (1.0 / thermalSoft > 1.0 ? 0.55 : 1.0);

		// Minimum energy threshold: hard cold steel struck lightly by soft limestone just bounces!
		double yieldBar = Math.max(0.25, 0.22 * effHardness * thermalSoft / Math.max(0.35, hammerHardness / 5.5));
		if (impactEnergyJ < yieldBar * 0.55) {
			return new ForgeOutcome(ForgeOutcomeKind.NO_DEFORM, 0, 0.0,
					grid.micro.averageDislocation(), false);
		}

		MicrostructureState.MicroCell localCell = grid.micro.cellAtVoxel(hit.x(), hit.y(), hit.z());
		boolean isHot = tempK >= recrystK;

		// Check if excessive cold work / sulfur hot-shortness / low ductility triggers cracking
		double ductilityMargin = alloy.intrinsicDuctility()
				- localCell.dislocationDensity * (isHot ? 0.15 : 0.72)
				- localCell.residualStress * 0.35
				- localCell.porosity * 0.45;
		// Sulfur impurity causes severe hot-short cracking when hammering hot copper/iron!
		if (isHot && alloy.s > 0.012) {
			ductilityMargin -= alloy.s * 18.0;
		}
		if (ductilityMargin < 0.08 && rng.nextDouble() < Math.min(0.88, 0.25 + (0.15 - ductilityMargin) * 2.8)) {
			localCell.fractureDamage = Math.min(1.0, localCell.fractureDamage + 0.22 + impactEnergyJ * 0.05);
			localCell.residualStress = Math.min(1.0, localCell.residualStress + 0.15);
			metal.invalidateGeometry();
			return new ForgeOutcome(ForgeOutcomeKind.WORK_CRACKED, 0, 0.0,
					grid.micro.averageDislocation(), true);
		}

		// Plastic compression fraction per struck cell
		double radius = Math.max(0.85, Math.min(2.4, hammerContactRadiusVox));
		int rInt = (int) Math.ceil(radius);
		double driveStrength = Math.min(0.62, 0.16 * (impactEnergyJ / Math.max(0.25, yieldBar))
				* (isHot ? 1.55 : 0.78));

		// Preferred lateral flow vector orthogonal to hit face normal (draws blades along biasDir!)
		Vec3d faceN = new Vec3d(hit.nx(), hit.ny(), hit.nz());
		Vec3d b = biasDir != null && biasDir.lengthSquared() > 1e-4 ? biasDir.normalize() : new Vec3d(1, 0, 0);
		Vec3d tangent = b.subtract(faceN.multiply(b.dotProduct(faceN)));
		if (tangent.lengthSquared() < 1e-4) {
			tangent = (Math.abs(faceN.y) > 0.5) ? new Vec3d(1, 0, 0) : new Vec3d(0, 0, 1);
		} else {
			tangent = tangent.normalize();
		}

		long totalDisplaced = 0;
		boolean drewEdgeThin = false;

		for (int dy = -rInt; dy <= rInt; dy++) {
			for (int dz = -rInt; dz <= rInt; dz++) {
				for (int dx = -rInt; dx <= rInt; dx++) {
					double dSq = dx * dx + dy * dy + dz * dz;
					if (dSq > radius * radius) continue;
					int sx = hit.x() + dx, sy = hit.y() + dy, sz = hit.z() + dz;
					if (!MetalVolumeGrid.inBounds(sx, sy, sz)) continue;
					int curFill = grid.getFill(sx, sy, sz);
					if (curFill <= 140) continue;

					// Do not delete final wafer on y=0 unless pushing laterally outward into edge margin
					int minFloor = (sy == 0) ? 260 : 80;
					int removable = curFill - minFloor;
					if (removable <= 0) continue;

					double falloff = 1.0 - (dSq / (radius * radius + 0.35));
					int squeeze = (int) Math.round(removable * driveStrength * (0.45 + 0.55 * falloff));
					if (squeeze < 35) continue;

					// Find best lateral recipient cells (outward along tangent & radial XY/XZ ring)
					int moved = distributeDisplacedMass(grid, sx, sy, sz, squeeze, faceN, tangent);
					if (moved > 0) {
						grid.setFill(sx, sy, sz, curFill - moved);
						totalDisplaced += moved;
						if (curFill - moved < 520) drewEdgeThin = true;
					}
				}
			}
		}

		// Ensure lowest voxel stays seated on anvil
		grid.settleOnBase();

		// Update internal microstructure across impacted 4x4x4 zone & adjacent neighbors
		double strain = Math.min(0.28, (totalDisplaced / 4200.0) * (isHot ? 0.25 : 1.0));
		double slagExpelled = 0.0;
		for (int dy = -1; dy <= 1; dy++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					int vx = Math.max(0, Math.min(15, hit.x() + dx * 2));
					int vy = Math.max(0, Math.min(15, hit.y() + dy * 2));
					int vz = Math.max(0, Math.min(15, hit.z() + dz * 2));
					MicrostructureState.MicroCell c = grid.micro.cellAtVoxel(vx, vy, vz);
					if (isHot) {
						// Hot forging welds pores & refines cast dendrites dynamically!
						c.porosity = Math.max(0.005, c.porosity * 0.72);
						c.grainSizeUm = Math.max(12.0, c.grainSizeUm * 0.86);
						c.dislocationDensity = Math.min(0.25, c.dislocationDensity + strain * 0.25);
						if (alloy.slag > 0.008) {
							double drop = Math.min(alloy.slag * 0.22, 0.028);
							c.slagInclusion = Math.max(0.0, c.slagInclusion - drop);
							slagExpelled = Math.max(slagExpelled, drop);
						}
					} else {
						// Cold work hardening sharply elevates dislocation density & residual stress!
						c.dislocationDensity = Math.min(1.0, c.dislocationDensity + 0.08 + strain * 0.65);
						c.residualStress = Math.min(1.0, c.residualStress + 0.05 + strain * 0.45);
						c.porosity = Math.max(0.01, c.porosity * 0.90);
					}
				}
			}
		}

		if (slagExpelled > 0.0) {
			alloy.slag = Math.max(0.0, alloy.slag - slagExpelled);
			alloy.normalize();
		}

		metal.invalidateGeometry();
		ForgeOutcomeKind kind = slagExpelled > 0.005 ? ForgeOutcomeKind.SLAG_EXPELLED
				: (drewEdgeThin ? ForgeOutcomeKind.EDGE_DRAWN : ForgeOutcomeKind.SMOOTH_DEFORM);
		return new ForgeOutcome(kind, totalDisplaced, slagExpelled, grid.micro.averageDislocation(), false);
	}

	/**
	 * Spreads squeezed millivoxels outward along perpendicular & biased draw directions while strictly
	 * conserving total mass.
	 */
	private static int distributeDisplacedMass(MetalVolumeGrid grid, int sx, int sy, int sz, int amount,
											   Vec3d faceN, Vec3d tangent) {
		// Candidate target offsets ordered by draw preference: along tangent first, then symmetric sides
		int tx = (int) Math.round(tangent.x);
		int ty = (int) Math.round(tangent.y);
		int tz = (int) Math.round(tangent.z);
		int[][] cand = new int[][]{
				{tx, ty, tz},
				{tx != 0 ? tx : 1, 0, tz != 0 ? tz : 0},
				{-tx, -ty, -tz},
				{1, 0, 0}, {-1, 0, 0}, {0, 0, 1}, {0, 0, -1},
				{1, 0, 1}, {-1, 0, 1}, {1, 0, -1}, {-1, 0, -1},
				{2 * (tx != 0 ? tx : 1), 0, 2 * tz},
				{0, 1, 0} // slight upset ridge if completely trapped laterally
		};

		int remaining = amount;
		for (int[] off : cand) {
			if (remaining <= 0) break;
			if (off[0] == 0 && off[1] == 0 && off[2] == 0) continue;
			// Prefer spreading perpendicular to downward anvil axis unless spreading out
			int nx = sx + off[0], ny = sy + off[1], nz = sz + off[2];
			if (!MetalVolumeGrid.inBounds(nx, ny, nz)) continue;
			if (off[1] == 1 && Math.abs(faceN.y) > 0.6 && remaining == amount) continue;
			int cur = grid.getFill(nx, ny, nz);
			int cap = MetalVolumeGrid.FULL_CELL - cur;
			if (cap <= 40) continue;
			int push = Math.min(cap, Math.max(remaining / 2, Math.min(remaining, 360)));
			grid.setFill(nx, ny, nz, cur + push);
			remaining -= push;
		}
		return amount - remaining;
	}

	/**
	 * Abrasive stone honing / grinding along exposed acute edge cells: gently shapes bevel faces down
	 * and removes chipped edge micro-burrs at cost of minor metal filing dust.
	 */
	public static double grindEdge(DynamicMetalState metal, double abrasiveMohs) {
		if (abrasiveMohs < metal.effectiveHardness() * 0.72) {
			return 0.0;
		}
		int honed = 0;
		for (int i = 0; i < MicrostructureState.TOTAL_CELLS; i++) {
			MicrostructureState.MicroCell c = metal.grid.micro.cell(i);
			if (c.fractureDamage > 0.01) {
				c.fractureDamage = Math.max(0.0, c.fractureDamage - 0.14);
				honed++;
			}
		}
		// Subtle trim on protruding over-thick edge tips so edge radius refines cleanly
		for (int y = 0; y < MetalVolumeGrid.SIZE; y++) {
			for (int z = 0; z < MetalVolumeGrid.SIZE; z++) {
				for (int x = 0; x < MetalVolumeGrid.SIZE; x++) {
					int f = metal.grid.getFill(x, y, z);
					if (f >= 480 && f <= 920) {
						int air = (!metal.grid.isSolid(x+1,y,z)?1:0) + (!metal.grid.isSolid(x-1,y,z)?1:0)
								+ (!metal.grid.isSolid(x,y+1,z)?1:0) + (!metal.grid.isSolid(x,y-1,z)?1:0);
						if (air >= 2) {
							metal.grid.setFill(x, y, z, Math.max(320, f - 45));
							honed++;
						}
					}
				}
			}
		}
		metal.invalidateGeometry();
		return honed;
	}
}
