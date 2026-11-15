package com.projectrealism.metallurgy;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Physical molten metal mold filling and solidification solver.
 *
 * <p>Simulates gravity + hydrostatic fill from the highest carved pour sprue/opening, thermal freeze-off
 * along narrow channels vs fluidity, short-pour volume shortages, gas entrapment in unvented or wet
 * green-clay molds, solidification shrinkage, and cooling-rate-dependent cast grain structure.</p>
 */
public final class CastingEngine {
	public record CastResult(
			boolean success,
			DynamicMetalState casting,
			double fillFraction,
			double usedMetalKg,
			double porosity,
			boolean shortPour,
			boolean coldShut,
			boolean gasDefect
	) {
	}

	private CastingEngine() {
	}

	/**
	 * Pours molten metal from {@code crucible} into {@code mold} cavity at {@code voxelScaleCm}.
	 * Deducts exact poured metal mass from crucible and returns the physical {@link DynamicMetalState}.
	 */
	public static CastResult pourAndSolidify(CrucibleState crucible, MoldGrid mold, double voxelScaleCm) {
		int cavityCount = mold.countCavityVoxels();
		if (cavityCount <= 0 || !crucible.isPartiallyMolten() || crucible.metalMassKg < 0.02) {
			return new CastResult(false, null, 0.0, 0.0, 0.0, true, true, false);
		}

		double cellSideM = voxelScaleCm / 100.0;
		double cellVolumeM3 = cellSideM * cellSideM * cellSideM;
		double density = crucible.alloy.densityKgM3();
		double cellMassKg = cellVolumeM3 * density;

		double availableVoxels = crucible.metalMassKg / Math.max(1e-6, cellMassKg);
		double fluidity = crucible.fluidity();

		// Find highest carved cavity entries (sprue / top cavity opening) as pour entry points
		List<int[]> entries = new ArrayList<>();
		int maxY = -1;
		for (int y = MoldGrid.SIZE - 1; y >= 1; y--) {
			for (int z = 1; z < MoldGrid.SIZE - 1; z++) {
				for (int x = 1; x < MoldGrid.SIZE - 1; x++) {
					if (mold.isCavity(x, y, z)) {
						if (y >= maxY) {
							maxY = y;
							entries.add(new int[]{x, y, z});
						}
					}
				}
			}
			if (!entries.isEmpty()) break;
		}
		if (entries.isEmpty()) {
			return new CastResult(false, null, 0.0, 0.0, 0.0, true, false, false);
		}

		// Collect all reachable cavity cells via flood-fill from entry, penalized by chill/narrow necks
		boolean[] reachable = new boolean[MoldGrid.TOTAL_VOXELS];
		int[] chillDist = new int[MoldGrid.TOTAL_VOXELS];
		ArrayDeque<Integer> bfs = new ArrayDeque<>();
		for (int[] e : entries) {
			int idx = MoldGrid.index(e[0], e[1], e[2]);
			reachable[idx] = true;
			chillDist[idx] = 0;
			bfs.add(idx);
		}

		// Thermal freeze distance: cold molds & low superheat freeze off narrow distant runners!
		double preheatFactor = mold.temperatureK > 450.0 ? 1.45 : 1.0;
		double maxRunLength = Math.max(3.5, fluidity * 26.0 * preheatFactor / Math.max(0.75, mold.material.chillConductivity * 0.7));
		boolean coldShut = false;

		while (!bfs.isEmpty()) {
			int cur = bfs.poll();
			int cx = cur & 15, cz = (cur >>> 4) & 15, cy = (cur >>> 8) & 15;
			int dCur = chillDist[cur];
			for (int[] off : new int[][]{{0,-1,0}, {1,0,0}, {-1,0,0}, {0,0,1}, {0,0,-1}, {0,1,0}}) {
				int nx = cx + off[0], ny = cy + off[1], nz = cz + off[2];
				if (!MoldGrid.inBounds(nx, ny, nz) || !mold.isCavity(nx, ny, nz)) continue;
				int nIdx = MoldGrid.index(nx, ny, nz);
				if (reachable[nIdx]) continue;
				// Upward flow inside closed cavity stalls unless sufficient fluidity/pressure exists
				int stepCost = off[1] > 0 ? 3 : (off[1] < 0 ? 1 : 2);
				int nextD = dCur + stepCost;
				if (nextD <= maxRunLength * 2.0) {
					reachable[nIdx] = true;
					chillDist[nIdx] = nextD;
					bfs.add(nIdx);
				} else {
					coldShut = true;
				}
			}
		}

		// Gravity sorting: fill bottom reachable cells upward up to available metal volume!
		MetalVolumeGrid metalGrid = new MetalVolumeGrid();
		double remainingUnits = availableVoxels * MetalVolumeGrid.FULL_CELL;
		long filledUnits = 0;
		int filledCells = 0;

		for (int y = 1; y < MoldGrid.SIZE; y++) {
			// Count reachable cells on layer y
			List<Integer> layer = new ArrayList<>();
			for (int z = 1; z < MoldGrid.SIZE - 1; z++) {
				for (int x = 1; x < MoldGrid.SIZE - 1; x++) {
					int idx = MoldGrid.index(x, y, z);
					if (reachable[idx]) layer.add(idx);
				}
			}
			if (layer.isEmpty() || remainingUnits <= 50.0) continue;

			// Account for solidification shrinkage (~3.5% volumetric pipe on thick central cores!) & trapped air
			double ventPenalty = (!mold.vented && y >= maxY - 2) ? 0.65 : 1.0;
			double perCellTarget = MetalVolumeGrid.FULL_CELL * (0.965 * ventPenalty);

			for (int idx : layer) {
				if (remainingUnits <= 20.0) break;
				int take = (int) Math.min(perCellTarget, remainingUnits);
				if (take >= MetalVolumeGrid.SOLID_THRESHOLD) {
					metalGrid.setFillIdx(idx, take);
					filledUnits += take;
					filledCells++;
				}
				remainingUnits -= take;
			}
		}

		metalGrid.settleOnBase();
		double usedKg = Math.min(crucible.metalMassKg, (filledUnits / (double) MetalVolumeGrid.FULL_CELL) * cellMassKg);
		crucible.metalMassKg = Math.max(0.0, crucible.metalMassKg - usedKg);

		// Compute resulting casting porosity & cast grain size from cooling rate, dissolved gas, and mold venting
		boolean gasDefect = mold.material.moistureGasFactor > 0.25 || (!mold.vented && mold.closedCavity) || crucible.dissolvedGas > 0.22;
		double porosity = Math.min(0.42,
				crucible.dissolvedGas * 0.45
						+ mold.material.moistureGasFactor * 0.45
						+ (!mold.vented && mold.closedCavity ? 0.16 : 0.01)
						+ (crucible.slagMassKg > 0.02 ? 0.06 : 0.0));

		// Fast-chilled stone mold yields finer grains & slight cooling residual stress; hot clay mold yields coarser grains
		double chill = mold.material.chillConductivity / Math.max(0.4, preheatFactor);
		double castGrainUm = Math.max(32.0, Math.min(210.0, 145.0 / Math.sqrt(chill)));
		double castStress = Math.min(0.45, chill * 0.12);

		AlloyComposition castAlloy = crucible.alloy.copy();
		if (crucible.slagMassKg > 0.01) {
			// Unskimmed slag carries traces into casting!
			castAlloy.slag = Math.min(0.25, castAlloy.slag + 0.05);
			castAlloy.normalize();
		}

		for (int i = 0; i < MicrostructureState.TOTAL_CELLS; i++) {
			MicrostructureState.MicroCell c = metalGrid.micro.cell(i);
			c.grainSizeUm = castGrainUm;
			c.porosity = porosity;
			c.slagInclusion = castAlloy.slag;
			c.residualStress = castStress;
			c.localCarbon = castAlloy.c;
		}
		metalGrid.syncMicroWeights();

		DynamicMetalState out = new DynamicMetalState(
				castAlloy, voxelScaleCm, metalGrid,
				Math.min(crucible.temperatureK, castAlloy.solidusTempK() - 35.0));

		double fillFrac = cavityCount > 0 ? Math.min(1.0, filledCells / (double) cavityCount) : 0.0;
		boolean shortPour = fillFrac < 0.85;
		return new CastResult(filledCells > 0, out, fillFrac, usedKg, porosity, shortPour, coldShut, gasDefect);
	}
}
