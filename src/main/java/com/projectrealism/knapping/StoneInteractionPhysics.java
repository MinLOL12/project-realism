package com.projectrealism.knapping;

import java.util.Random;

/**
 * Category-free contact physics evaluator: calculates how effectively any dynamically shaped stone
 * slices, chops, scrapes, pierces, or crushes a target material strictly from its real voxel
 * geometry, mass, center-of-mass leverage, edge angle/radius/continuity, and hafting stability.
 */
public final class StoneInteractionPhysics {
	public enum TargetMaterial {
		WOOD_CHOP(1.6),
		WOOD_SHAVE(0.85),
		STONE_MINE(2.2),
		ORE_HARD(3.5),
		FLESH_SLICE(0.28),
		HIDE_SCRAPE(0.35),
		HIDE_CUT(0.45),
		BONE_CARVE(1.15),
		BONE_CHISEL(1.55),
		PLANT_CUT(0.18),
		PIERCE_BORE(0.95);

		public final double resistance;

		TargetMaterial(double resistance) {
			this.resistance = resistance;
		}
	}

	public record ContactResponse(
			double effectiveness,
			double speedMultiplier,
			double gougeRisk,
			double edgeStress
	) {
	}

	private StoneInteractionPhysics() {
	}

	/**
	 * Evaluates physical interaction performance (never checks any tool category!).
	 * Cutting follows P_cut ~ F_normal * L_effective / (r_edge + eps) moderated by wedge angle,
	 * backing spine thickness, broadness/flatness, mass momentum, and haft leverage.
	 */
	public static ContactResponse evaluate(DynamicStoneState stone, TargetMaterial target, double appliedForceN) {
		VoxelRockGrid.GeometryMetrics m = stone.metrics();
		if (m.voxelCount() <= 2) {
			return new ContactResponse(0.05, 0.25, 0.0, 1.0);
		}

		double rEdge = Math.max(0.12, m.primaryEdgeRadiusMm());
		double lEff = Math.max(0.4, m.continuousEdgeCm());
		double angle = m.primaryEdgeAngleDeg();
		double effMass = stone.haft.effectiveMassKg(m.massKg());
		double slipMod = stone.haft.hafted ? (0.45 + 0.55 * stone.haft.slipResistance) : 1.0;

		return switch (target) {
			case FLESH_SLICE, HIDE_CUT, PLANT_CUT -> {
				// Slicing concentrates normal force across long acute tiny-radius cutting edges
				double wedge = angle <= 26.0 ? 1.15 : Math.max(0.18, 1.15 - (angle - 26.0) / 48.0);
				double raw = (appliedForceN * Math.min(8.5, lEff)) / ((rEdge + 0.35) * 28.0);
				double eff = Math.max(0.08, Math.min(1.45, raw * wedge * (stone.haft.hafted ? 1.08 : 1.0) * slipMod));
				double stress = (target.resistance * 0.45) / Math.max(0.25, stone.material.toughness * (0.4 + 0.2 * m.edgeSpineCm()));
				yield new ContactResponse(eff, 0.4 + eff * 1.1, angle > 42.0 ? 0.35 : 0.04, stress);
			}
			case WOOD_SHAVE, BONE_CARVE -> {
				double wedge = angle <= 38.0 ? 1.05 : Math.max(0.15, 1.05 - (angle - 38.0) / 44.0);
				double raw = (appliedForceN * Math.min(7.5, lEff)) / ((rEdge + 0.45) * 32.0);
				double eff = Math.max(0.06, Math.min(1.30, raw * wedge * slipMod));
				double stress = target.resistance / Math.max(0.30, stone.material.toughness * (0.5 + 0.22 * m.edgeSpineCm()));
				yield new ContactResponse(eff, 0.35 + eff, angle > 52.0 ? 0.30 : 0.05, stress);
			}
			case WOOD_CHOP -> {
				// Chopping needs swing momentum (mass * haft leverage) + sturdy medium wedge (~28..56 deg)
				double momentum = Math.min(2.2, effMass / 1.45);
				double oriFit = stone.haft.hafted
						? stone.haft.orientation.swingAlignment * stone.haft.edgeClearance * slipMod
						: 0.35; // bare-hand rock chops poorly compared to levered handle!
				double wedgeFit;
				if (angle < 18.0) {
					wedgeFit = 0.65; // ultra-thin razor edge bites briefly then risks severe chipping!
				} else if (angle <= 55.0) {
					wedgeFit = 1.0 - Math.abs(angle - 36.0) / 65.0;
				} else {
					wedgeFit = Math.max(0.08, 0.7 - (angle - 55.0) / 35.0);
				}
				double spineSupport = Math.min(1.25, m.edgeSpineCm() / 2.2);
				double contactLen = Math.min(1.15, lEff / 4.2);
				double eff = Math.max(0.05, Math.min(1.50, momentum * oriFit * wedgeFit * spineSupport * contactLen));
				double speed = 0.35 + eff * 2.25;
				// Thin acute brittle stones suffer huge edge stress under wood impact!
				double thinRisk = angle < 25.0 ? (28.0 - angle) * 0.045 : 0.0;
				double stress = (target.resistance + thinRisk) / Math.max(0.22, stone.material.toughness * spineSupport);
				yield new ContactResponse(eff, speed, 0.0, stress);
			}
			case STONE_MINE, ORE_HARD -> {
				// Breaking mineral rock demands high Mohs hardness, concentrated impact mass & sturdy shoulder
				double hardGap = stone.material.mohsHardness - (target == TargetMaterial.ORE_HARD ? 7.8 : 4.2);
				if (hardGap < 0.0) {
					yield new ContactResponse(0.04, 0.20, 0.0, 2.2);
				}
				double oriFit = stone.haft.hafted ? (0.45 + 0.55 * stone.haft.orientation.swingAlignment) * slipMod : 0.30;
				double shoulder = Math.min(1.2, (m.pointScore() * 0.65 + (1.0 - Math.min(0.6, Math.abs(angle - 48.0) / 55.0)) * 0.45));
				double momentum = Math.min(2.0, effMass / 1.65);
				double eff = Math.max(0.05, Math.min(1.35, (hardGap / 2.8) * oriFit * shoulder * momentum));
				double stress = target.resistance / Math.max(0.25, stone.material.toughness * (0.4 + 0.25 * m.edgeSpineCm()));
				yield new ContactResponse(eff, 0.35 + eff * 2.4, 0.0, stress);
			}
			case HIDE_SCRAPE -> {
				// Scraping wants broad flat contact & steeper 42..80 deg edge; razor acute edges gouge!
				double broad = Math.min(1.15, m.flatSurfaceAreaCm2() / 26.0);
				double scrapeWedge = angle >= 40.0 ? Math.min(1.15, 0.75 + (angle - 40.0) / 70.0) : Math.max(0.25, angle / 55.0);
				double gouge = angle < 28.0 ? Math.min(0.95, (30.0 - angle) / 22.0) : 0.05;
				double eff = Math.max(0.10, Math.min(1.30, (0.55 * broad + 0.45 * scrapeWedge) * (1.0 - m.fractureDensity() * 0.4)));
				yield new ContactResponse(eff, 0.5 + eff * 0.9, gouge, 0.22);
			}
			case PIERCE_BORE, BONE_CHISEL -> {
				double tip = Math.max(0.08, m.pointScore() * 0.8 + (m.edgeSpineCm() >= 2.0 && angle <= 50.0 ? 0.25 : 0.0));
				double oriBoost = stone.haft.hafted && stone.haft.orientation == HaftData.Orientation.INLINE ? 1.15 * slipMod : 1.0;
				double eff = Math.max(0.06, Math.min(1.35, tip * oriBoost * (stone.material.mohsHardness / 6.0)));
				double stress = target.resistance / Math.max(0.28, stone.material.toughness * (0.5 + m.edgeSpineCm() * 0.25));
				yield new ContactResponse(eff, 0.4 + eff * 1.1, 0.0, stress);
			}
		};
	}

	/**
	 * Applies true geometric voxel chipping to the most exposed working edge whenever contact
	 * stress exceeds local mineral resistance. Physically shortens/blunts damaged regions!
	 */
	public static int applyContactWear(DynamicStoneState stone, TargetMaterial target,
									   double forceN, Random rng) {
		ContactResponse resp = evaluate(stone, target, forceN);
		// Shock also strains haft binding
		if (stone.haft.hafted) {
			stone.haft.absorbImpactShock(resp.edgeStress * 0.65);
		}

		double chipProb = Math.min(0.92, resp.edgeStress * 0.22 * (0.65 + 0.7 * stone.metrics().fractureDensity()));
		if (rng.nextDouble() > chipProb) {
			return 0;
		}

		VoxelRockGrid grid = stone.grid;
		if (grid.countSolid() <= 8) return 0;

		// Find most exposed acute edge voxels (highest neighbor air count + pre-existing cracks)
		int bestX = -1, bestY = -1, bestZ = -1;
		double bestScore = -1.0;
		for (int y = 0; y < VoxelRockGrid.SIZE; y++) {
			for (int z = 0; z < VoxelRockGrid.SIZE; z++) {
				for (int x = 0; x < VoxelRockGrid.SIZE; x++) {
					if (!grid.isSolid(x, y, z)) continue;
					int air = 0;
					if (!grid.isSolid(x + 1, y, z)) air++;
					if (!grid.isSolid(x - 1, y, z)) air++;
					if (!grid.isSolid(x, y + 1, z)) air++;
					if (!grid.isSolid(x, y - 1, z)) air++;
					if (!grid.isSolid(x, y, z + 1)) air++;
					if (!grid.isSolid(x, y, z - 1)) air++;
					if (air < 3) continue;
					double score = air + grid.getCrackLevel(x, y, z) * 0.12 + rng.nextDouble() * 0.8;
					if (score > bestScore) {
						bestScore = score;
						bestX = x;
						bestY = y;
						bestZ = z;
					}
				}
			}
		}

		if (bestX < 0) return 0;

		// Chip 1..3 cells on high stress (brittle obsidian/cracked edges chip wider!)
		int targetChips = resp.edgeStress > 1.35 ? (1 + rng.nextInt(3)) : 1;
		int chipped = 0;
		for (int dy = -1; dy <= 1 && chipped < targetChips; dy++) {
			for (int dz = -1; dz <= 1 && chipped < targetChips; dz++) {
				for (int dx = -1; dx <= 1 && chipped < targetChips; dx++) {
					int vx = bestX + dx, vy = bestY + dy, vz = bestZ + dz;
					if (grid.isSolid(vx, vy, vz)) {
						grid.setSolid(vx, vy, vz, false);
						chipped++;
					}
				}
			}
		}
		for (int dy = -1; dy <= 1; dy++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					grid.addCrack(bestX + dx, bestY + dy, bestZ + dz, 4);
				}
			}
		}
		grid.extractDetachedComponents();
		stone.invalidateGeometry();
		return chipped;
	}
}
