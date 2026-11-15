package com.projectrealism.metallurgy;

import net.minecraft.nbt.NbtCompound;

/**
 * Coarse 4 x 4 x 4 (= 64 zones) internal crystal/microstructural simulation mapped over the 16x16x16
 * volumetric metal grid. Tracks grain size, dislocation work hardening, residual stress, porosity,
 * slag inclusions, phase transformations (martensite, austenite/pearlite, alpha-bronze), and carbon diffusion.
 */
public final class MicrostructureState {
	public static final int SUB_SIZE = 4;
	public static final int TOTAL_CELLS = SUB_SIZE * SUB_SIZE * SUB_SIZE; // 64 micro-zones

	public static final class MicroCell {
		/** Mean crystal grain diameter in micrometers (5 um fine forged -> 240 um coarse cast). */
		public double grainSizeUm = 65.0;
		/** Work-hardening dislocation density normalized 0..1. */
		public double dislocationDensity = 0.0;
		/** Residual locked thermal/mechanical stress 0..1. */
		public double residualStress = 0.0;
		/** Volumetric casting/bloom porosity 0..0.85. */
		public double porosity = 0.02;
		/** Local entrapped slag/oxide inclusion fraction 0..1. */
		public double slagInclusion = 0.0;
		/** Local carbon mass fraction (supports surface case carburization vs core!). */
		public double localCarbon = 0.0;
		/** Hardened martensite phase fraction (0..1, for quenched carburized steel). */
		public double martensiteFrac = 0.0;
		/** Crack/micro-tear damage level (0..1). */
		public double fractureDamage = 0.0;

		public MicroCell copy() {
			MicroCell c = new MicroCell();
			c.grainSizeUm = grainSizeUm;
			c.dislocationDensity = dislocationDensity;
			c.residualStress = residualStress;
			c.porosity = porosity;
			c.slagInclusion = slagInclusion;
			c.localCarbon = localCarbon;
			c.martensiteFrac = martensiteFrac;
			c.fractureDamage = fractureDamage;
			return c;
		}

		public NbtCompound toNbt() {
			NbtCompound t = new NbtCompound();
			t.putFloat("G", (float) grainSizeUm);
			t.putFloat("D", (float) dislocationDensity);
			t.putFloat("R", (float) residualStress);
			t.putFloat("P", (float) porosity);
			t.putFloat("Sl", (float) slagInclusion);
			t.putFloat("C", (float) localCarbon);
			t.putFloat("M", (float) martensiteFrac);
			t.putFloat("F", (float) fractureDamage);
			return t;
		}

		public static MicroCell fromNbt(NbtCompound t) {
			MicroCell c = new MicroCell();
			if (t.contains("G")) c.grainSizeUm = t.getFloat("G");
			c.dislocationDensity = t.getFloat("D");
			c.residualStress = t.getFloat("R");
			c.porosity = t.getFloat("P");
			c.slagInclusion = t.getFloat("Sl");
			c.localCarbon = t.getFloat("C");
			c.martensiteFrac = t.getFloat("M");
			c.fractureDamage = t.getFloat("F");
			return c;
		}
	}

	private final MicroCell[] cells = new MicroCell[TOTAL_CELLS];

	public MicrostructureState() {
		for (int i = 0; i < TOTAL_CELLS; i++) {
			cells[i] = new MicroCell();
		}
	}

	public static int cellIndexForVoxel(int vx, int vy, int vz) {
		int cx = Math.max(0, Math.min(3, vx >> 2));
		int cy = Math.max(0, Math.min(3, vy >> 2));
		int cz = Math.max(0, Math.min(3, vz >> 2));
		return (cy << 4) | (cz << 2) | cx;
	}

	public MicroCell cellAtVoxel(int vx, int vy, int vz) {
		return cells[cellIndexForVoxel(vx, vy, vz)];
	}

	public MicroCell cell(int idx) {
		return cells[idx];
	}

	/**
	 * Applies thermal recovery, recrystallization, tempering, and grain growth during heating/annealing.
	 * Hot forging followed by annealing refines coarse grains into ultra-tough fine equiaxed crystals!
	 */
	public void stepThermalEvolution(AlloyComposition alloy, double tempK, double dtSeconds, boolean inReducingCo) {
		double recrystK = alloy.recrystallizationTempK();
		double solidusK = alloy.solidusTempK();
		if (tempK <= 420.0 || dtSeconds <= 0.0) return;

		for (MicroCell cell : cells) {
			// 1. Low-temperature tempering of brittle quenched martensite (440..780 K)
			if (cell.martensiteFrac > 0.01 && tempK >= 440.0) {
				double temperRate = Math.min(0.6, dtSeconds * 0.015 * ((tempK - 420.0) / 300.0));
				cell.residualStress = Math.max(0.0, cell.residualStress * (1.0 - temperRate * 1.4));
				if (tempK >= 720.0) {
					// Over-tempering softens martensite back toward pearlite/ferrite
					cell.martensiteFrac = Math.max(0.0, cell.martensiteFrac * (1.0 - temperRate));
				}
			}

			// 2. Annealing / Recrystallization above ~0.48 * T_melt
			if (tempK >= recrystK) {
				double overRatio = (tempK - recrystK) / Math.max(100.0, solidusK - recrystK);
				double rate = Math.min(0.85, dtSeconds * (0.04 + 0.16 * overRatio));
				double prevDisl = cell.dislocationDensity;
				cell.dislocationDensity = Math.max(0.0, cell.dislocationDensity * (1.0 - rate));
				cell.residualStress = Math.max(0.0, cell.residualStress * (1.0 - rate * 1.2));
				// Cold work + anneal nucleates fine new grains!
				if (prevDisl > 0.15) {
					cell.grainSizeUm = Math.max(14.0, cell.grainSizeUm * (1.0 - 0.35 * prevDisl * rate));
				} else if (tempK > solidusK * 0.78) {
					// Prolonged overheating coarsens grains slightly
					cell.grainSizeUm = Math.min(220.0, cell.grainSizeUm + dtSeconds * 0.6 * overRatio);
				}
			}

			// 3. Solid-state carbon absorption (case carburization / crucible steel) in glowing CO bed >= 1120 K
			if (inReducingCo && alloy.fe >= alloy.cu && tempK >= 1120.0) {
				double diffRate = 1.6e-4 * Math.exp((tempK - 1120.0) / 260.0) * dtSeconds;
				cell.localCarbon = Math.min(0.018, cell.localCarbon + diffRate * (0.018 - cell.localCarbon));
			}
		}
	}

	public void applyQuench(AlloyComposition alloy, double fromTempK, double coolantTempK, double coolantRate) {
		double delta = Math.max(0.0, fromTempK - coolantTempK);
		double sev = Math.max(0.1, Math.min(1.0, (delta / 800.0) * (coolantRate / 400.0)));
		applyQuench(alloy, fromTempK, sev);
	}

	/**
	 * Rapid cooling / water quenching physics:
	 * - Steel/high-C iron quenched from austenite (>= 1020 K) locks into hard martensite + stress.
	 * - Copper/Bronze quenched cleanly locks annealed/solutionized soft ductility!
	 */
	public void applyQuench(AlloyComposition alloy, double fromTempK, double quenchSeverity) {
		double sev = Math.max(0.1, Math.min(1.0, quenchSeverity));
		if (alloy.fe >= alloy.cu) {
			if (fromTempK >= 1020.0) {
				for (MicroCell cell : cells) {
					double c = Math.max(cell.localCarbon, alloy.c);
					if (c >= 0.0022) {
						double transform = Math.min(1.0, (c / 0.008) * sev);
						cell.martensiteFrac = Math.max(cell.martensiteFrac, transform);
						cell.residualStress = Math.min(1.0, cell.residualStress + 0.38 * transform);
						if (c > 0.012 && sev > 0.85) {
							cell.fractureDamage = Math.min(1.0, cell.fractureDamage + 0.08);
						}
					}
				}
			}
		} else if (fromTempK >= alloy.recrystallizationTempK()) {
			// Water quench on hot copper/bronze anneals out work hardening
			for (MicroCell cell : cells) {
				cell.dislocationDensity *= 0.20;
				cell.residualStress *= 0.35;
			}
		}
	}

	public double averageDislocation() {
		double sum = 0;
		for (MicroCell c : cells) sum += c.dislocationDensity;
		return sum / TOTAL_CELLS;
	}

	public double averageGrainSizeUm() {
		double sum = 0;
		for (MicroCell c : cells) sum += c.grainSizeUm;
		return sum / TOTAL_CELLS;
	}

	public double averagePorosity() {
		double sum = 0;
		for (MicroCell c : cells) sum += c.porosity;
		return sum / TOTAL_CELLS;
	}

	public double averageMartensite() {
		double sum = 0;
		for (MicroCell c : cells) sum += c.martensiteFrac;
		return sum / TOTAL_CELLS;
	}

	public double averageFracture() {
		double sum = 0;
		for (MicroCell c : cells) sum += c.fractureDamage;
		return sum / TOTAL_CELLS;
	}

	public double averageResidualStress() {
		double sum = 0;
		for (MicroCell c : cells) sum += c.residualStress;
		return sum / TOTAL_CELLS;
	}

	public double averageCarbon(AlloyComposition alloy) {
		double sum = 0;
		for (MicroCell c : cells) sum += Math.max(c.localCarbon, alloy.c);
		return sum / TOTAL_CELLS;
	}

	/** Effective mechanical hardness boost/modifier combining Hall-Petch grain refinement, work hardening, martensite & porosity. */
	public double effectiveHardness(AlloyComposition alloy, double tempK) {
		double base = alloy.intrinsicHardness();
		// Hall-Petch refinement (smaller grains -> stronger) + work hardening + martensite transformation
		double hallPetch = Math.max(-0.4, Math.min(1.2, (60.0 - averageGrainSizeUm()) / 55.0));
		double workBoost = averageDislocation() * (alloy.fe >= alloy.cu ? 1.8 : 2.1);
		double martBoost = averageMartensite() * 3.1;
		double porousLoss = averagePorosity() * 2.2;
		double thermalSoft = tempK > 500.0 ? Math.min(0.65, (tempK - 500.0) / Math.max(400.0, alloy.solidusTempK())) : 0.0;
		return Math.max(1.2, (base + hallPetch + workBoost + martBoost - porousLoss) * (1.0 - thermalSoft));
	}

	/** Effective fracture toughness / resistance to brittle snapping (0.08..1.25). */
	public double effectiveToughness(AlloyComposition alloy, double tempK) {
		double duct = alloy.intrinsicDuctility();
		double grainFactor = Math.max(0.55, Math.min(1.25, 1.15 - (averageGrainSizeUm() - 25.0) / 260.0));
		double workBrittleness = averageDislocation() * 0.42 + averageResidualStress() * 0.35 + averageMartensite() * 0.48;
		double defects = averagePorosity() * 0.65 + averageFracture() * 0.85 + alloy.slag * 0.8;
		double hotDuctile = tempK >= alloy.recrystallizationTempK() ? 0.30 : 0.0;
		return Math.max(0.08, Math.min(1.30, (duct * grainFactor + hotDuctile) - workBrittleness - defects));
	}

	public MicrostructureState copy() {
		MicrostructureState m = new MicrostructureState();
		for (int i = 0; i < TOTAL_CELLS; i++) {
			m.cells[i] = cells[i].copy();
		}
		return m;
	}

	public NbtCompound toNbt() {
		NbtCompound t = new NbtCompound();
		byte[] grain = new byte[TOTAL_CELLS];
		byte[] disl = new byte[TOTAL_CELLS];
		byte[] stress = new byte[TOTAL_CELLS];
		byte[] poro = new byte[TOTAL_CELLS];
		byte[] slag = new byte[TOTAL_CELLS];
		byte[] carb = new byte[TOTAL_CELLS];
		byte[] mart = new byte[TOTAL_CELLS];
		byte[] frac = new byte[TOTAL_CELLS];
		for (int i = 0; i < TOTAL_CELLS; i++) {
			MicroCell c = cells[i];
			grain[i] = (byte) clampU8(c.grainSizeUm);
			disl[i] = (byte) clampU8(c.dislocationDensity * 255.0);
			stress[i] = (byte) clampU8(c.residualStress * 255.0);
			poro[i] = (byte) clampU8(c.porosity * 255.0);
			slag[i] = (byte) clampU8(c.slagInclusion * 255.0);
			carb[i] = (byte) clampU8(c.localCarbon * 5000.0);
			mart[i] = (byte) clampU8(c.martensiteFrac * 255.0);
			frac[i] = (byte) clampU8(c.fractureDamage * 255.0);
		}
		t.putByteArray("G", grain);
		t.putByteArray("D", disl);
		t.putByteArray("R", stress);
		t.putByteArray("P", poro);
		t.putByteArray("S", slag);
		t.putByteArray("C", carb);
		t.putByteArray("M", mart);
		t.putByteArray("F", frac);
		return t;
	}

	private static int clampU8(double v) {
		return Math.max(0, Math.min(255, (int) Math.round(v)));
	}

	public static MicrostructureState fromNbt(NbtCompound t) {
		MicrostructureState m = new MicrostructureState();
		byte[] grain = t.getByteArray("G");
		byte[] disl = t.getByteArray("D");
		byte[] stress = t.getByteArray("R");
		byte[] poro = t.getByteArray("P");
		byte[] slag = t.getByteArray("S");
		byte[] carb = t.getByteArray("C");
		byte[] mart = t.getByteArray("M");
		byte[] frac = t.getByteArray("F");
		if (grain.length == TOTAL_CELLS) {
			for (int i = 0; i < TOTAL_CELLS; i++) {
				MicroCell c = m.cells[i];
				c.grainSizeUm = grain[i] & 0xFF;
				if (disl.length == TOTAL_CELLS) c.dislocationDensity = (disl[i] & 0xFF) / 255.0;
				if (stress.length == TOTAL_CELLS) c.residualStress = (stress[i] & 0xFF) / 255.0;
				if (poro.length == TOTAL_CELLS) c.porosity = (poro[i] & 0xFF) / 255.0;
				if (slag.length == TOTAL_CELLS) c.slagInclusion = (slag[i] & 0xFF) / 255.0;
				if (carb.length == TOTAL_CELLS) c.localCarbon = (carb[i] & 0xFF) / 5000.0;
				if (mart.length == TOTAL_CELLS) c.martensiteFrac = (mart[i] & 0xFF) / 255.0;
				if (frac.length == TOTAL_CELLS) c.fractureDamage = (frac[i] & 0xFF) / 255.0;
			}
		}
		return m;
	}
}
