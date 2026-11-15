package com.projectrealism.metallurgy;

/**
 * Freeform 3D furnace geometry & wall-material analyzer. Never checks a fixed multiblock pattern:
 * scans a local volume around a combustion/smelting bed and derives chamber dimensions, wall
 * thermal resistance, natural chimney draft pressure, tuyere air supply, and structural heat limits.
 */
public final class FurnaceStructure {
	public enum WallMaterial {
		AIR(0.0, 0.0, 9999.0, 0.0),
		REFRACTORY_CLAY(0.85, 1950.0, 1880.0, 1.0),
		FIRED_CLAY(1.15, 1900.0, 1720.0, 0.90),
		BASALT_DEEPSLATE(1.75, 2400.0, 1580.0, 0.85),
		GRANITE_STONE(2.60, 2300.0, 1380.0, 0.65),
		LIMESTONE(1.90, 2100.0, 1120.0, 0.45),
		RAW_CLAY(1.10, 1650.0, 1280.0, 0.55),
		DIRT_OR_WOOD(0.55, 1200.0, 620.0, 0.10);

		public final double conductivityWmk;
		public final double thermalMassFactor;
		public final double maxSafeTempK;
		public final double integrityWeight;

		WallMaterial(double conductivityWmk, double thermalMassFactor, double maxSafeTempK, double integrityWeight) {
			this.conductivityWmk = conductivityWmk;
			this.thermalMassFactor = thermalMassFactor;
			this.maxSafeTempK = maxSafeTempK;
			this.integrityWeight = integrityWeight;
		}
	}

	/** Pure interface so FurnaceStructure runs both in-world and inside headless JUnit tests. */
	public interface BlockProbe {
		WallMaterial probe(int dx, int dy, int dz);
		boolean isOpenToSky(int dx, int dy, int dz);
	}

	public record GeometrySummary(
			int chamberVolumeCells,
			int chimneyHeightBlocks,
			int tuyereOpenings,
			int topFlueOpenings,
			double enclosureFraction,
			double avgWallConductivity,
			double minWallSafeTempK,
			double draftAirFlowKgS,
			double wallHeatLossFactorWPerK
	) {
		/** Effective insulation & draft quality (0 = open campfire, ~0.4 = pit hearth, ~0.85+ = chimney shaft furnace). */
		public double thermalEfficiency() {
			return Math.max(0.05, Math.min(1.0,
					enclosureFraction * 0.62
							+ Math.min(0.28, chimneyHeightBlocks * 0.055)
							+ Math.min(0.18, tuyereOpenings * 0.06)));
		}
	}

	private FurnaceStructure() {
	}

	/**
	 * Scans [-3..+3] horizontally and [-1..+5] vertically around the basin center (0,0,0) to evaluate
	 * enclosed chamber volume, shaft height, lower air inlets, top chimney flue, and wall heat loss.
	 */
	public static GeometrySummary analyze(BlockProbe probe) {
		int chamberVol = 0;
		int wallContacts = 0;
		int totalBoundaryChecks = 0;
		int tuyeres = 0;
		int topFlues = 0;
		double kSum = 0.0;
		double minSafeTemp = 1900.0;

		// Measure lower ring (y=0..1) enclosure & tuyere air ports
		for (int dy = 0; dy <= 1; dy++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dx = -2; dx <= 2; dx++) {
					if (dx == 0 && dy == 0 && dz == 0) continue;
					WallMaterial wm = probe.probe(dx, dy, dz);
					boolean inner = Math.abs(dx) <= 1 && Math.abs(dz) <= 1;
					if (inner && wm == WallMaterial.AIR) {
						chamberVol++;
					}
					boolean ring = (Math.abs(dx) == 1 && dz == 0) || (dx == 0 && Math.abs(dz) == 1)
							|| (Math.abs(dx) == 2 && Math.abs(dz) <= 1) || (Math.abs(dz) == 2 && Math.abs(dx) <= 1);
					if (ring && dy <= 1) {
						totalBoundaryChecks++;
						if (wm != WallMaterial.AIR) {
							wallContacts++;
							kSum += wm.conductivityWmk;
							if (wm.maxSafeTempK < minSafeTemp) minSafeTemp = wm.maxSafeTempK;
						} else if (dy == 0 && (Math.abs(dx) == 1 && dz == 0 || dx == 0 && Math.abs(dz) == 1)) {
							// Check if outer side beyond this air block also opens outward -> tuyere port!
							if (probe.probe(dx * 2, 0, dz * 2) == WallMaterial.AIR) {
								tuyeres++;
							}
						}
					}
				}
			}
		}

		// Measure chimney/shaft height upward along center & inner shaft
		int shaftHeight = 0;
		for (int dy = 1; dy <= 5; dy++) {
			WallMaterial center = probe.probe(0, dy, 0);
			if (center != WallMaterial.AIR) {
				// Solid cap blocking top completely chokes flue unless side flue exists!
				break;
			}
			int ringWalls = 0;
			for (int[] off : new int[][]{{1,0}, {-1,0}, {0,1}, {0,-1}}) {
				WallMaterial w = probe.probe(off[0], dy, off[1]);
				if (w != WallMaterial.AIR) {
					ringWalls++;
					kSum += w.conductivityWmk;
					wallContacts++;
					if (w.maxSafeTempK < minSafeTemp) minSafeTemp = w.maxSafeTempK;
				}
			}
			if (ringWalls >= 3) {
				shaftHeight++;
				chamberVol++;
			} else {
				if (probe.isOpenToSky(0, dy, 0) || ringWalls == 0) {
					topFlues++;
				}
				break;
			}
		}
		if (shaftHeight > 0 && probe.probe(0, shaftHeight + 1, 0) == WallMaterial.AIR) {
			topFlues = Math.max(1, topFlues);
		}

		// Foundation block underneath (y = -1)
		WallMaterial floor = probe.probe(0, -1, 0);
		if (floor != WallMaterial.AIR) {
			wallContacts++;
			kSum += floor.conductivityWmk;
			if (floor.maxSafeTempK < minSafeTemp) minSafeTemp = floor.maxSafeTempK;
		} else {
			minSafeTemp = Math.min(minSafeTemp, 620.0);
		}

		double enclosure = totalBoundaryChecks > 0 ? Math.min(1.0, wallContacts / (double) totalBoundaryChecks) : 0.0;
		double avgK = wallContacts > 0 ? kSum / wallContacts : 1.5;

		// Natural chimney stack draft scales with sqrt(H_chimney) and requires tuyeres + top flue (choked if sealed!)
		double intake = Math.max(0.12, Math.min(1.8, tuyeres * 0.55 + (enclosure < 0.7 ? 0.65 : 0.0)));
		double flueGate = topFlues > 0 ? 1.0 : (enclosure < 0.85 ? 0.65 : 0.08);
		double naturalDraft = (0.012 + 0.014 * Math.sqrt(Math.max(0.0, shaftHeight))) * intake * flueGate;

		// Heat loss coefficient (W/K) drops when surrounded by thicker lower-k refractory walls
		double wallLoss = (1.0 - enclosure * 0.72) * 32.0 + avgK * 4.5 + topFlues * 3.8;

		return new GeometrySummary(
				Math.max(1, chamberVol),
				shaftHeight,
				tuyeres,
				topFlues,
				enclosure,
				avgK,
				minSafeTemp,
				naturalDraft,
				Math.max(3.5, wallLoss)
		);
	}
}
