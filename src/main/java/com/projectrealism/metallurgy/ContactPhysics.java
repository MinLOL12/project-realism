package com.projectrealism.metallurgy;

import com.projectrealism.knapping.DynamicStoneState;
import com.projectrealism.knapping.HaftData;
import com.projectrealism.knapping.StoneInteractionPhysics;
import com.projectrealism.knapping.VoxelRockGrid;

import java.util.Random;

/**
 * Generalized category-free material contact physics evaluator unifying dynamically knapped stone
 * and dynamically cast/forged metal objects under identical geometric and mechanical principles.
 *
 * <p>No tool categories or equipment tiers exist: chopping, cutting, mining, scraping, and piercing
 * emerge continuously from working geometry, edge angle/radius, contact length, mass momentum,
 * hardness, toughness, plastic deformation vs brittle fracture, and current temperature.</p>
 */
public final class ContactPhysics {
	private ContactPhysics() {
	}

	public static StoneInteractionPhysics.ContactResponse evaluateStone(
			DynamicStoneState stone,
			StoneInteractionPhysics.TargetMaterial target,
			double appliedForceN) {
		return evaluateBody(
				stone.metrics(), stone.haft, stone.material.mohsHardness, stone.material.toughness,
				false, 293.15, target, appliedForceN);
	}

	public static StoneInteractionPhysics.ContactResponse evaluateMetal(
			DynamicMetalState metal,
			StoneInteractionPhysics.TargetMaterial target,
			double appliedForceN) {
		return evaluateBody(
				metal.metrics(), metal.haft, metal.effectiveHardness(), metal.effectiveToughness(),
				true, metal.temperatureK, target, appliedForceN);
	}

	public static StoneInteractionPhysics.ContactResponse evaluateBody(
			VoxelRockGrid.GeometryMetrics m,
			HaftData haft,
			double hardness,
			double toughness,
			boolean ductileMetal,
			double tempK,
			StoneInteractionPhysics.TargetMaterial target,
			double appliedForceN) {
		if (m.voxelCount() <= 2) {
			return new StoneInteractionPhysics.ContactResponse(0.05, 0.25, 0.0, 1.0);
		}

		double rEdge = Math.max(0.05, m.primaryEdgeRadiusMm());
		double lEff = Math.max(0.4, m.continuousEdgeCm());
		double angle = m.primaryEdgeAngleDeg();
		double effMass = haft.effectiveMassKg(m.massKg());
		double slipMod = haft.hafted ? (0.45 + 0.55 * haft.slipResistance) : 1.0;

		// Soft hot or unhardened metal against hard rock/bone suffers plastic edge rolling stress!
		double hardMatch = Math.max(0.25, hardness / Math.max(2.5, target.resistance * 2.2));
		double metalSharpBonus = ductileMetal ? 1.12 : 1.0;

		return switch (target) {
			case FLESH_SLICE, HIDE_CUT, PLANT_CUT -> {
				double wedge = angle <= 26.0 ? 1.15 : Math.max(0.18, 1.15 - (angle - 26.0) / 48.0);
				double raw = (appliedForceN * Math.min(8.5, lEff)) / ((rEdge + 0.32) * 28.0);
				double eff = Math.max(0.08, Math.min(1.65, raw * wedge * metalSharpBonus * (haft.hafted ? 1.08 : 1.0) * slipMod));
				double stress = (target.resistance * 0.45) / Math.max(0.25, toughness * (0.4 + 0.2 * m.edgeSpineCm()));
				yield new StoneInteractionPhysics.ContactResponse(eff, 0.4 + eff * 1.15, angle > 42.0 ? 0.35 : 0.04, stress);
			}
			case WOOD_SHAVE, BONE_CARVE -> {
				double wedge = angle <= 38.0 ? 1.05 : Math.max(0.15, 1.05 - (angle - 38.0) / 44.0);
				double raw = (appliedForceN * Math.min(7.5, lEff)) / ((rEdge + 0.42) * 32.0);
				double eff = Math.max(0.06, Math.min(1.55, raw * wedge * Math.min(1.25, hardMatch) * slipMod));
				double stress = target.resistance / Math.max(0.30, toughness * (0.5 + 0.22 * m.edgeSpineCm()) * Math.min(1.3, hardness / 4.2));
				yield new StoneInteractionPhysics.ContactResponse(eff, 0.35 + eff * 1.1, angle > 52.0 ? 0.30 : 0.05, stress);
			}
			case WOOD_CHOP -> {
				double momentum = Math.min(2.4, effMass / 1.45);
				double oriFit = haft.hafted
						? haft.orientation.swingAlignment * haft.edgeClearance * slipMod
						: 0.35;
				double wedgeFit;
				if (angle < 16.0) {
					wedgeFit = ductileMetal ? 0.78 : 0.65;
				} else if (angle <= 55.0) {
					wedgeFit = 1.0 - Math.abs(angle - 34.0) / 65.0;
				} else {
					wedgeFit = Math.max(0.08, 0.7 - (angle - 55.0) / 35.0);
				}
				double spineSupport = Math.min(1.30, m.edgeSpineCm() / 2.2);
				double contactLen = Math.min(1.18, lEff / 4.2);
				double hardBenefit = ductileMetal ? Math.max(0.65, Math.min(1.32, hardness / 4.0)) : 1.0;
				double eff = Math.max(0.05, Math.min(1.80,
						momentum * oriFit * wedgeFit * spineSupport * contactLen * hardBenefit));
				double speed = 0.35 + eff * 2.35;
				double thinRisk = angle < 24.0 ? (26.0 - angle) * (ductileMetal ? 0.025 : 0.045) : 0.0;
				double stress = (target.resistance + thinRisk) / Math.max(0.22, toughness * spineSupport * Math.max(0.5, hardness / 4.5));
				yield new StoneInteractionPhysics.ContactResponse(eff, speed, 0.0, stress);
			}
			case STONE_MINE, ORE_HARD -> {
				// Pure mineral & rock breakage checks physical hardness gap + toughness + impact mass!
				double threshold = target == StoneInteractionPhysics.TargetMaterial.ORE_HARD ? 4.35 : 3.45;
				if (!ductileMetal) {
					threshold = target == StoneInteractionPhysics.TargetMaterial.ORE_HARD ? 7.8 : 4.2;
				}
				double hardGap = hardness - threshold;
				if (hardGap <= 0.0) {
					yield new StoneInteractionPhysics.ContactResponse(0.04, 0.20, 0.0, 2.2);
				}
				double oriFit = haft.hafted ? (0.45 + 0.55 * haft.orientation.swingAlignment) * slipMod : 0.30;
				double shoulder = Math.min(1.25, (m.pointScore() * 0.65 + (1.0 - Math.min(0.6, Math.abs(angle - 46.0) / 55.0)) * 0.45));
				double momentum = Math.min(2.2, effMass / 1.60);
				double eff = Math.max(0.05, Math.min(1.75, (hardGap / 2.3) * oriFit * shoulder * momentum));
				double stress = target.resistance / Math.max(0.25, toughness * (0.4 + 0.25 * m.edgeSpineCm()) * Math.max(0.45, hardness / 4.8));
				yield new StoneInteractionPhysics.ContactResponse(eff, 0.35 + eff * 2.5, 0.0, stress);
			}
			case HIDE_SCRAPE -> {
				double broad = Math.min(1.15, m.flatSurfaceAreaCm2() / 26.0);
				double scrapeWedge = angle >= 40.0 ? Math.min(1.15, 0.75 + (angle - 40.0) / 70.0) : Math.max(0.25, angle / 55.0);
				double gouge = angle < 28.0 ? Math.min(0.95, (30.0 - angle) / 22.0) : 0.05;
				double eff = Math.max(0.10, Math.min(1.30, (0.55 * broad + 0.45 * scrapeWedge) * (1.0 - m.fractureDensity() * 0.4)));
				yield new StoneInteractionPhysics.ContactResponse(eff, 0.5 + eff * 0.9, gouge, 0.22);
			}
			case PIERCE_BORE, BONE_CHISEL -> {
				double tip = Math.max(0.08, m.pointScore() * 0.8 + (m.edgeSpineCm() >= 1.8 && angle <= 50.0 ? 0.25 : 0.0));
				double oriBoost = haft.hafted && haft.orientation == HaftData.Orientation.INLINE ? 1.15 * slipMod : 1.0;
				double eff = Math.max(0.06, Math.min(1.65, tip * oriBoost * (hardness / 5.5)));
				double stress = target.resistance / Math.max(0.28, toughness * (0.5 + m.edgeSpineCm() * 0.25));
				yield new StoneInteractionPhysics.ContactResponse(eff, 0.4 + eff * 1.1, 0.0, stress);
			}
		};
	}

	/**
	 * Applies realistic contact wear to a metal object:
	 * - Ductile low-hardness metal rolls/blunts edge cells slightly and work-hardens (can be re-forged!).
	 * - Brittle high-martensite/over-worked/porous/high-S metal develops micro-fracture cracks.
	 */
	public static boolean applyMetalWear(DynamicMetalState metal,
										 StoneInteractionPhysics.TargetMaterial target,
										 double forceN,
										 Random rng) {
		StoneInteractionPhysics.ContactResponse resp = evaluateMetal(metal, target, forceN);
		if (metal.haft.hafted) {
			metal.haft.absorbImpactShock(resp.edgeStress() * 0.50);
		}
		double hardness = metal.effectiveHardness();
		double toughness = metal.effectiveToughness();

		// Plastic blunting probability rises if metal is soft relative to impact stress (e.g. cold unhardened copper on rock!)
		double rollProb = Math.min(0.85, Math.max(0.02, (resp.edgeStress() * 0.28) * (4.2 / Math.max(1.5, hardness))));
		// Brittle micro-cracking probability rises if toughness is very low (untempered martensite / >16% Sn / high S)
		double crackProb = Math.min(0.75, Math.max(0.0, (resp.edgeStress() * 0.22) * (0.48 - toughness) * 2.4));

		boolean changed = false;
		if (rng.nextDouble() < rollProb) {
			// Find exposed thin edge voxel and gently thicken/flatten it inward (mass-conserving edge roll!)
			MetalVolumeGrid g = metal.grid;
			for (int y = 0; y < MetalVolumeGrid.SIZE; y++) {
				for (int z = 0; z < MetalVolumeGrid.SIZE; z++) {
					for (int x = 0; x < MetalVolumeGrid.SIZE; x++) {
						int f = g.getFill(x, y, z);
						if (f < MetalVolumeGrid.SOLID_THRESHOLD || f >= 850) continue;
						int air = (!g.isSolid(x+1,y,z)?1:0) + (!g.isSolid(x-1,y,z)?1:0)
								+ (!g.isSolid(x,y+1,z)?1:0) + (!g.isSolid(x,y-1,z)?1:0);
						if (air >= 3 && rng.nextDouble() < 0.35) {
							// Push 90 millivoxels back toward spine (rolls/blunts thin edge slightly without deleting metal!)
							int nx = g.isSolid(x-1,y,z) ? x-1 : (g.isSolid(x+1,y,z) ? x+1 : x);
							int nz = g.isSolid(x,y,z-1) ? z-1 : (g.isSolid(x,y,z+1) ? z+1 : z);
							int dstFill = g.getFill(nx, y, nz);
							int move = Math.min(90, Math.min(f - 180, MetalVolumeGrid.FULL_CELL - dstFill));
							if (move > 20) {
								g.setFill(x, y, z, f - move);
								g.setFill(nx, y, nz, dstFill + move);
								MicrostructureState.MicroCell mc = g.micro.cellAtVoxel(x, y, z);
								mc.dislocationDensity = Math.min(1.0, mc.dislocationDensity + 0.025);
								changed = true;
								break;
							}
						}
					}
					if (changed) break;
				}
				if (changed) break;
			}
		}

		if (rng.nextDouble() < crackProb) {
			int idx = rng.nextInt(MicrostructureState.TOTAL_CELLS);
			MicrostructureState.MicroCell c = metal.grid.micro.cell(idx);
			c.fractureDamage = Math.min(1.0, c.fractureDamage + 0.06);
			changed = true;
		}
		if (changed) {
			metal.invalidateGeometry();
		}
		return changed;
	}
}
