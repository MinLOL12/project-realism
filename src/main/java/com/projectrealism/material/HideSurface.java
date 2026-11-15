package com.projectrealism.material;

import com.projectrealism.thermo.ThermalMath;
import net.minecraft.nbt.NbtCompound;

import java.util.Arrays;
import java.util.Random;

/**
 * Spatial 8x8 physical simulation grid representing an actual animal hide, leather sheet, or scrap
 * undergoing scraping, tensioned drying, environmental exposure, and blade cutting.
 */
public final class HideSurface {
	public static final int GRID = 8;
	public static final int CELLS = GRID * GRID;

	public AnimalHideType animal;
	public final boolean[] intact = new boolean[CELLS];
	public final double[] thickness = new double[CELLS];
	public final double[] fleshClean = new double[CELLS];   // 0 = raw fatty flesh, 1 = clean dermis
	public final double[] damage = new double[CELLS];       // 0 = flawless, >= 0.92 = torn hole
	public final double[] moisture = new double[CELLS];     // 0 = bone dry, 1 = dripping soaked
	public final double[] stiffness = new double[CELLS];    // 0 = limp, 1 = brittle board-stiff
	public final double[] scorch = new double[CELLS];       // heat-charring & discoloration
	public final boolean[] cutMark = new boolean[CELLS];    // knife path traced across surface

	/** Corner tie-down tensions [NW, NE, SE, SW] on a drying frame (0..1). */
	public final double[] cornerTension = new double[]{0.5, 0.5, 0.5, 0.5};

	public double unevenDeformation = 0.0; // warping/buckling from poor tension distribution
	public double contractionFrac = 0.0;   // physical shrinkage as moisture drops
	public double lastCutRegularity = 1.0; // quality of knife cuts (sharp steady vs dull snag)
	public double scrapedFatWasteKg = 0.0; // accumulated scrapings available to drop/render
	public double ageDays = 0.0;
	public double mildewDiscoloration = 0.0;

	public HideSurface(AnimalHideType animal) {
		this.animal = animal;
		for (int y = 0; y < GRID; y++) {
			for (int x = 0; x < GRID; x++) {
				int idx = idx(x, y);
				intact[idx] = animal.defaultIntactAt(x, y);
				thickness[idx] = animal.regionalThicknessAt(x, y);
				fleshClean[idx] = 0.0;
				damage[idx] = 0.0;
				moisture[idx] = 0.68;
				stiffness[idx] = 0.22;
				scorch[idx] = 0.0;
				cutMark[idx] = false;
			}
		}
	}

	public static int idx(int x, int y) {
		return clampIdx(y) * GRID + clampIdx(x);
	}

	private static int clampIdx(int v) {
		return Math.max(0, Math.min(GRID - 1, v));
	}

	/** Apply anatomical battle wounds from how the animal was killed (arrows, fire, heavy axe). */
	public void applyKillTrauma(Random rng, double severity, boolean fireDamage) {
		int holes = (int) Math.round(severity * 10.0);
		for (int h = 0; h < holes; h++) {
			int cx = 1 + rng.nextInt(GRID - 2);
			int cy = 1 + rng.nextInt(GRID - 2);
			int id = idx(cx, cy);
			damage[id] = Math.min(1.0, damage[id] + 0.45 + rng.nextDouble() * 0.55);
			if (damage[id] >= 0.92) {
				intact[id] = false;
			}
			if (fireDamage) {
				scorch[id] = Math.min(1.0, scorch[id] + 0.55);
				moisture[id] = Math.max(0.05, moisture[id] - 0.35);
			}
		}
	}

	public static final class ScrapeOutcome {
		public final int targetedCell;
		public final double removedFlesh;
		public final double inflictedDamage;
		public final double scrapedFatKg;
		public final boolean toreHole;
		public final boolean wetHide;

		public ScrapeOutcome(int targetedCell, double removedFlesh, double inflictedDamage,
							 double scrapedFatKg, boolean toreHole, boolean wetHide) {
			this.targetedCell = targetedCell;
			this.removedFlesh = removedFlesh;
			this.inflictedDamage = inflictedDamage;
			this.scrapedFatKg = scrapedFatKg;
			this.toreHole = toreHole;
			this.wetHide = wetHide;
		}
	}

	/**
	 * Physically scrapes the hide around surface coordinate (u, v) in [0,1]^2.
	 *
	 * @param u            Normalized horizontal frame coordinate (0..1)
	 * @param v            Normalized vertical frame coordinate (0..1)
	 * @param sweepDist    Lateral drag distance across the hide surface since last stroke (0..1)
	 * @param angleDeg     Tool angle relative to surface tangent (10..35 deg optimal; &gt;55 deg gouges!)
	 * @param sharpness    Scraper edge sharpness (0.05..1.0)
	 * @param pressure     Applied force (0.3..1.8; excessive pressure + dull tool damages fibers)
	 */
	public ScrapeOutcome scrapeAt(double u, double v, double sweepDist, double angleDeg,
								  double sharpness, double pressure) {
		int gx = clampIdx((int) Math.floor(u * GRID));
		int gy = clampIdx((int) Math.floor(v * GRID));
		int center = idx(gx, gy);

		double meanM = averageMoisture();
		boolean isWet = meanM >= 0.32;

		// Angle efficiency peaks around 18-32 degrees; steep angles catch and dig into leather!
		double anglePenalty = Math.max(0.0, (angleDeg - 45.0) / 45.0);
		double angleEfficiency = Math.max(0.18, 1.0 - Math.abs(angleDeg - 26.0) / 60.0);
		double moistureSlip = isWet ? (0.85 + 0.35 * moisture[center]) : 0.35;
		double sweepBonus = 0.55 + Math.min(1.0, sweepDist * 8.0);
		double toolEff = (0.25 + 0.75 * sharpness) * angleEfficiency * moistureSlip * sweepBonus * Math.min(1.4, pressure);

		double totalRemoved = 0.0;
		double totalDmg = 0.0;
		boolean tore = false;

		for (int dy = -1; dy <= 1; dy++) {
			for (int dx = -1; dx <= 1; dx++) {
				int nx = gx + dx;
				int ny = gy + dy;
				if (nx < 0 || nx >= GRID || ny < 0 || ny >= GRID) continue;
				int ci = idx(nx, ny);
				if (!intact[ci]) continue;

				double weight = (dx == 0 && dy == 0) ? 1.0 : ((Math.abs(dx) + Math.abs(dy) == 1) ? 0.38 : 0.14);
				double alreadyClean = fleshClean[ci];

				// Scraping flesh/fat cleanly off
				double cleanDelta = Math.min(1.0 - alreadyClean, 0.34 * toolEff * weight);
				fleshClean[ci] += cleanDelta;
				totalRemoved += cleanDelta;

				// Over-scraping an already clean thin spot, gouging steeply, or rubbing dry/dull damages hide!
				double thinVulnerability = 1.0 / Math.max(0.28, thickness[ci]);
				double overScrapeFactor = alreadyClean > 0.85 ? (alreadyClean - 0.70) * 1.45 : 0.0;
				double dullRoughness = Math.max(0.0, 0.65 - sharpness) * 0.45;
				double dryDrag = !isWet ? 0.12 : 0.0;
				double excessForce = Math.max(0.0, pressure - 1.15) * 0.25;

				double rawDmg = (overScrapeFactor + anglePenalty * 0.35 + dullRoughness + dryDrag + excessForce)
						* thinVulnerability * 0.16 * weight * pressure;

				if (rawDmg > 0.002) {
					damage[ci] = Math.min(1.0, damage[ci] + rawDmg);
					totalDmg += rawDmg;
					if (damage[ci] >= 0.92) {
						intact[ci] = false;
						tore = true;
					}
				}
			}
		}

		double fatGain = (totalRemoved / CELLS) * animal.initialFatKg * 1.45;
		scrapedFatWasteKg += fatGain;
		return new ScrapeOutcome(center, totalRemoved, totalDmg, fatGain, tore, isWet);
	}

	/**
	 * Steps regional drying, contraction, tension warping, thermal scorching, and rain re-wetting
	 * coupled directly to {@link ThermalMath} environmental temperature, humidity, airflow, and heat.
	 */
	public void stepDrying(double airTempK, double relHumidity, double airSpeedMS,
						   double radiantHeatW, double radiantBiasX, boolean exposedRain, double dtSec) {
		// 1. Tension balance across the 4 corners [NW, NE, SE, SW]
		double meanTension = (cornerTension[0] + cornerTension[1] + cornerTension[2] + cornerTension[3]) * 0.25;
		double maxDiff = 0.0;
		for (int i = 0; i < 4; i++) {
			for (int j = i + 1; j < 4; j++) {
				maxDiff = Math.max(maxDiff, Math.abs(cornerTension[i] - cornerTension[j]));
			}
		}

		double totalShrink = 0.0;
		int count = 0;

		for (int y = 0; y < GRID; y++) {
			for (int x = 0; x < GRID; x++) {
				int ci = idx(x, y);
				if (!intact[ci]) continue;
				count++;

				// Corner tension interpolation at cell (x, y)
				double fx = x / (double) (GRID - 1);
				double fy = y / (double) (GRID - 1);
				double localT = (1 - fx) * (1 - fy) * cornerTension[0]
						+ fx * (1 - fy) * cornerTension[1]
						+ fx * fy * cornerTension[2]
						+ (1 - fx) * fy * cornerTension[3];

				// Regional radiant heat bias (a fire on the +X side dries the right edge faster!)
				double sideBoost = 1.0 + radiantBiasX * (fx - 0.5) * 1.1;
				double localRad = Math.max(0.0, radiantHeatW * sideBoost);
				double effectiveTempK = airTempK + localRad / 48.0;

				// Vapor gradient driving drying via ThermalMath equilibrium
				double cellM = moisture[ci];
				double eqMoist = 0.06 + 0.16 * relHumidity;
				double airFactor = 0.35 + 0.65 * Math.min(2.8, 0.35 + airSpeedMS * 0.65);
				double edgeFactor = (x == 0 || x == 7 || y == 0 || y == 7) ? 1.38 : 0.88;
				double thinFactor = 1.0 / Math.max(0.30, thickness[ci]);

				if (exposedRain) {
					moisture[ci] = Math.min(0.95, cellM + 0.0065 * dtSec);
					stiffness[ci] = Math.max(0.10, stiffness[ci] - 0.002 * dtSec);
				} else {
					double dryRate = (cellM - eqMoist) * 0.0018 * airFactor * edgeFactor * thinFactor
							* Math.max(0.25, (effectiveTempK - 265.0) / 28.0);
					moisture[ci] = Math.max(0.04, Math.min(0.95, cellM - dryRate * dtSec));
				}

				// Thermal damage & brittle stiffening from nearby open flame / intense heat (> 340 K)
				if (effectiveTempK > 340.0) {
					double heatExcess = (effectiveTempK - 340.0) / 45.0;
					scorch[ci] = Math.min(1.0, scorch[ci] + 0.004 * heatExcess * dtSec);
					damage[ci] = Math.min(1.0, damage[ci] + (0.0025 + 0.003 * localT) * heatExcess * dtSec);
					stiffness[ci] = Math.min(1.0, stiffness[ci] + 0.005 * heatExcess * dtSec);
					if (damage[ci] >= 0.92) {
						intact[ci] = false;
					}
				}

				// Contraction & tension resistance: low tension lets hide shrink & wrinkle; excessive tension stretches
				double dryness = Math.max(0.0, 0.68 - moisture[ci]);
				totalShrink += dryness * (0.28 - 0.16 * localT);
			}
		}

		// Poorly distributed or too-slack tension during drying generates permanent deformation/warp!
		double prevContraction = contractionFrac;
		contractionFrac = count > 0 ? Math.max(0.0, Math.min(0.32, totalShrink / count)) : 0.0;
		double dryProgressDelta = Math.max(0.0, contractionFrac - prevContraction);
		double badTension = maxDiff * 0.9 + Math.max(0.0, 0.35 - meanTension) * 0.65;
		unevenDeformation = Math.min(1.0, unevenDeformation + dryProgressDelta * badTension * 4.5);

		// Excessive drying rate increases stiffness if stretched unevenly
		if (badTension > 0.45 && averageMoisture() < 0.28) {
			for (int i = 0; i < CELLS; i++) {
				if (intact[i]) {
					stiffness[i] = Math.min(1.0, stiffness[i] + 0.0008 * badTension * dtSec);
				}
			}
		}
	}

	/**
	 * Traces an actual physical blade cut from (u0, v0) to (u1, v1) across the hide grid using a knife.
	 * Returns how many new cells were sliced and updates cut regularity based on blade sharpness and hide thickness.
	 */
	public int cutSegment(double u0, double v0, double u1, double v1, double knifeSharpness) {
		int steps = 16;
		int newCuts = 0;
		double maxThickEncountered = 0.0;

		for (int s = 0; s <= steps; s++) {
			double t = s / (double) steps;
			int gx = clampIdx((int) Math.floor((u0 + (u1 - u0) * t) * GRID));
			int gy = clampIdx((int) Math.floor((v0 + (v1 - v0) * t) * GRID));
			int ci = idx(gx, gy);
			if (!intact[ci]) continue;
			maxThickEncountered = Math.max(maxThickEncountered, thickness[ci]);
			if (!cutMark[ci]) {
				cutMark[ci] = true;
				newCuts++;
			}
		}

		// Dull blade snagging on thick leather reduces edge regularity!
		double snagPenalty = Math.max(0.0, maxThickEncountered - 0.55) * Math.max(0.0, 0.85 - knifeSharpness) * 0.65;
		double segRegularity = Math.max(0.20, Math.min(1.0, (0.35 + 0.65 * knifeSharpness) - snagPenalty));
		lastCutRegularity = 0.6 * lastCutRegularity + 0.4 * segRegularity;
		return newCuts;
	}

	public int intactCount() {
		int c = 0;
		for (boolean b : intact) if (b) c++;
		return c;
	}

	public int cutMarkCount() {
		int c = 0;
		for (int i = 0; i < CELLS; i++) if (intact[i] && cutMark[i]) c++;
		return c;
	}

	public double averageCleanliness() {
		double sum = 0;
		int c = 0;
		for (int i = 0; i < CELLS; i++) {
			if (intact[i]) {
				sum += fleshClean[i];
				c++;
			}
		}
		return c == 0 ? 0.0 : sum / c;
	}

	public double averageMoisture() {
		double sum = 0;
		int c = 0;
		for (int i = 0; i < CELLS; i++) {
			if (intact[i]) {
				sum += moisture[i];
				c++;
			}
		}
		return c == 0 ? 0.0 : sum / c;
	}

	public double averageThickness() {
		double sum = 0;
		int c = 0;
		for (int i = 0; i < CELLS; i++) {
			if (intact[i]) {
				sum += thickness[i];
				c++;
			}
		}
		return c == 0 ? 0.5 : sum / c;
	}

	public double averageDamage() {
		double sum = 0;
		int c = 0;
		for (int i = 0; i < CELLS; i++) {
			if (intact[i]) {
				sum += damage[i];
				c++;
			}
		}
		return c == 0 ? 1.0 : sum / c;
	}

	public double averageStiffness() {
		double sum = 0;
		int c = 0;
		for (int i = 0; i < CELLS; i++) {
			if (intact[i]) {
				sum += stiffness[i];
				c++;
			}
		}
		return c == 0 ? 0.5 : sum / c;
	}

	/** Current physical mass in kg (dry collagen + bound/free water + unscraped fat residue). */
	public double currentMassKg() {
		double intactFrac = intactCount() / (double) CELLS;
		double dryKg = animal.dryMassKg * intactFrac;
		double waterKg = dryKg * (averageMoisture() * 1.15);
		double fatRemKg = animal.initialFatKg * intactFrac * (1.0 - averageCleanliness());
		return dryKg + waterKg + fatRemKg;
	}

	/** Usable area in cm^2 accounting for holes and contraction. */
	public double usableAreaCm2() {
		double intactFrac = intactCount() / (double) CELLS;
		double area = animal.widthCm * animal.heightCm * intactFrac * (1.0 - contractionFrac * 0.6);
		return Math.max(0.0, area * (1.0 - 0.5 * unevenDeformation));
	}

	/** Extracts a cut leather panel or scrap inheriting regional quality, edge regularity, and condition. */
	public static final class CutPieceProperties {
		public final AnimalHideType animal;
		public final double areaCm2;
		public final double thickness;
		public final double cleanliness;
		public final double edgeRegularity;
		public final double damage;
		public final double moisture;
		public final double stiffness;
		public final double scorch;

		public CutPieceProperties(AnimalHideType animal, double areaCm2, double thickness, double cleanliness,
								  double edgeRegularity, double damage, double moisture, double stiffness, double scorch) {
			this.animal = animal;
			this.areaCm2 = areaCm2;
			this.thickness = thickness;
			this.cleanliness = cleanliness;
			this.edgeRegularity = edgeRegularity;
			this.damage = damage;
			this.moisture = moisture;
			this.stiffness = stiffness;
			this.scorch = scorch;
		}
	}

	/** Harvests cut regions from the hide, returning panel properties and consuming cut cells (leaving scraps!). */
	public CutPieceProperties extractCutPiece(double knifeSharpness) {
		int cutCells = cutMarkCount();
		double sumThick = 0, sumClean = 0, sumDmg = 0, sumMoist = 0, sumStiff = 0, sumScorch = 0;
		int harvested = 0;

		if (cutCells >= 6) {
			// Extract the cells inside or along the traced cut region
			int minX = GRID, maxX = -1, minY = GRID, maxY = -1;
			for (int y = 0; y < GRID; y++) {
				for (int x = 0; x < GRID; x++) {
					int ci = idx(x, y);
					if (intact[ci] && cutMark[ci]) {
						minX = Math.min(minX, x);
						maxX = Math.max(maxX, x);
						minY = Math.min(minY, y);
						maxY = Math.max(maxY, y);
					}
				}
			}
			for (int y = minY; y <= maxY; y++) {
				for (int x = minX; x <= maxX; x++) {
					int ci = idx(x, y);
					if (!intact[ci]) continue;
					sumThick += thickness[ci];
					sumClean += fleshClean[ci];
					sumDmg += damage[ci];
					sumMoist += moisture[ci];
					sumStiff += stiffness[ci];
					sumScorch += scorch[ci];
					harvested++;
					intact[ci] = false;
					cutMark[ci] = false;
				}
			}
		} else {
			// Fallback partial recovery of remaining usable block
			for (int i = 0; i < CELLS; i++) {
				if (!intact[i]) continue;
				sumThick += thickness[i];
				sumClean += fleshClean[i];
				sumDmg += damage[i];
				sumMoist += moisture[i];
				sumStiff += stiffness[i];
				sumScorch += scorch[i];
				harvested++;
				intact[i] = false;
				cutMark[i] = false;
				if (harvested >= 18) break;
			}
		}

		if (harvested <= 0) {
			return null;
		}
		double cellAreaCm2 = (animal.widthCm * animal.heightCm / CELLS) * (1.0 - contractionFrac * 0.5);
		double warpPenalty = 1.0 - unevenDeformation * 0.35;
		return new CutPieceProperties(
				animal,
				harvested * cellAreaCm2,
				sumThick / harvested,
				sumClean / harvested,
				Math.max(0.2, Math.min(1.0, lastCutRegularity * warpPenalty * (0.4 + 0.6 * knifeSharpness))),
				sumDmg / harvested,
				sumMoist / harvested,
				sumStiff / harvested,
				sumScorch / harvested
		);
	}

	public NbtCompound toNbt() {
		NbtCompound tag = new NbtCompound();
		tag.putString("Animal", animal.id);
		byte[] intactB = new byte[CELLS];
		int[] cleanI = new int[CELLS];
		int[] dmgI = new int[CELLS];
		int[] moistI = new int[CELLS];
		int[] stiffI = new int[CELLS];
		int[] scorchI = new int[CELLS];
		byte[] cutB = new byte[CELLS];
		for (int i = 0; i < CELLS; i++) {
			intactB[i] = (byte) (intact[i] ? 1 : 0);
			cleanI[i] = Math.round((float) (fleshClean[i] * 1000.0));
			dmgI[i] = Math.round((float) (damage[i] * 1000.0));
			moistI[i] = Math.round((float) (moisture[i] * 1000.0));
			stiffI[i] = Math.round((float) (stiffness[i] * 1000.0));
			scorchI[i] = Math.round((float) (scorch[i] * 1000.0));
			cutB[i] = (byte) (cutMark[i] ? 1 : 0);
		}
		tag.putByteArray("Intact", intactB);
		tag.putIntArray("Clean", cleanI);
		tag.putIntArray("Dmg", dmgI);
		tag.putIntArray("Moist", moistI);
		tag.putIntArray("Stiff", stiffI);
		tag.putIntArray("Scorch", scorchI);
		tag.putByteArray("Cut", cutB);
		tag.putDouble("T0", cornerTension[0]);
		tag.putDouble("T1", cornerTension[1]);
		tag.putDouble("T2", cornerTension[2]);
		tag.putDouble("T3", cornerTension[3]);
		tag.putDouble("Warp", unevenDeformation);
		tag.putDouble("Shrink", contractionFrac);
		tag.putDouble("CutReg", lastCutRegularity);
		tag.putDouble("FatWaste", scrapedFatWasteKg);
		tag.putDouble("Age", ageDays);
		tag.putDouble("Mildew", mildewDiscoloration);
		return tag;
	}

	public static HideSurface fromNbt(NbtCompound tag) {
		AnimalHideType type = AnimalHideType.fromId(tag.getString("Animal"));
		HideSurface s = new HideSurface(type);
		byte[] intactB = tag.getByteArray("Intact");
		int[] cleanI = tag.getIntArray("Clean");
		int[] dmgI = tag.getIntArray("Dmg");
		int[] moistI = tag.getIntArray("Moist");
		int[] stiffI = tag.getIntArray("Stiff");
		int[] scorchI = tag.getIntArray("Scorch");
		byte[] cutB = tag.getByteArray("Cut");
		for (int i = 0; i < CELLS; i++) {
			if (intactB.length == CELLS) s.intact[i] = intactB[i] != 0;
			if (cleanI.length == CELLS) s.fleshClean[i] = cleanI[i] / 1000.0;
			if (dmgI.length == CELLS) s.damage[i] = dmgI[i] / 1000.0;
			if (moistI.length == CELLS) s.moisture[i] = moistI[i] / 1000.0;
			if (stiffI.length == CELLS) s.stiffness[i] = stiffI[i] / 1000.0;
			if (scorchI.length == CELLS) s.scorch[i] = scorchI[i] / 1000.0;
			if (cutB.length == CELLS) s.cutMark[i] = cutB[i] != 0;
		}
		if (tag.contains("T0")) {
			s.cornerTension[0] = tag.getDouble("T0");
			s.cornerTension[1] = tag.getDouble("T1");
			s.cornerTension[2] = tag.getDouble("T2");
			s.cornerTension[3] = tag.getDouble("T3");
		}
		s.unevenDeformation = tag.getDouble("Warp");
		s.contractionFrac = tag.getDouble("Shrink");
		if (tag.contains("CutReg")) s.lastCutRegularity = tag.getDouble("CutReg");
		s.scrapedFatWasteKg = tag.getDouble("FatWaste");
		s.ageDays = tag.getDouble("Age");
		s.mildewDiscoloration = tag.getDouble("Mildew");
		return s;
	}

	public void adjustCornerTension(int cornerIdx, double delta) {
		if (cornerIdx < 0 || cornerIdx > 3) return;
		cornerTension[cornerIdx] = Math.max(0.05, Math.min(1.0, cornerTension[cornerIdx] + delta));
	}

	public boolean hasRemainingUsableCells() {
		return intactCount() >= 4;
	}

	public void clearCutMarks() {
		Arrays.fill(cutMark, false);
	}
}
