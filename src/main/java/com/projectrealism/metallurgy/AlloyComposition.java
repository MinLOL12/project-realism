package com.projectrealism.metallurgy;

import net.minecraft.nbt.NbtCompound;

/**
 * Continuous chemical composition of any physical metallic mass (native nugget, smelted prill,
 * bloomery iron, Cu-Sn bronze, arsenical copper, carburized steel, or complex multi-element alloy).
 *
 * <p>No discrete alloy tiers exist: melting point, density, hardness, ductility, corrosion, fluidity,
 * and visual metallic color emerge continuously from elemental fractions.</p>
 */
public final class AlloyComposition {
	/** Elemental mass fractions (always normalized so sum == 1.0). */
	public double cu;
	public double sn;
	public double fe;
	public double as;
	/** Dissolved/carburized carbon (0.0..0.045; ~0.004-0.015 = steel, >0.02 = brittle cast iron). */
	public double c;
	/** Sulfur impurity fraction (causes hot-shortness and brittle grain boundaries). */
	public double s;
	/** Entrapped silicate/fayalite slag & oxide fraction inside solid/bloomery metal. */
	public double slag;

	public AlloyComposition() {
		this.cu = 1.0;
	}

	public AlloyComposition(double cu, double sn, double fe, double as, double c, double s, double slag) {
		this.cu = Math.max(0.0, cu);
		this.sn = Math.max(0.0, sn);
		this.fe = Math.max(0.0, fe);
		this.as = Math.max(0.0, as);
		this.c = Math.max(0.0, c);
		this.s = Math.max(0.0, s);
		this.slag = Math.max(0.0, slag);
		normalize();
	}

	public static AlloyComposition pureCopper() {
		return new AlloyComposition(1.0, 0, 0, 0, 0, 0, 0);
	}

	public static AlloyComposition nativeCopper(double purity) {
		double p = Math.max(0.85, Math.min(0.995, purity));
		return new AlloyComposition(p, 0, (1.0 - p) * 0.4, (1.0 - p) * 0.3, 0, 0.001, (1.0 - p) * 0.3);
	}

	public static AlloyComposition tinBronze(double snFrac) {
		double sn = Math.max(0.02, Math.min(0.28, snFrac));
		return new AlloyComposition(1.0 - sn, sn, 0, 0, 0, 0, 0);
	}

	public static AlloyComposition wroughtIron() {
		return new AlloyComposition(0, 0, 0.985, 0, 0.001, 0, 0.014);
	}

	public void normalize() {
		double total = cu + sn + fe + as + c + s + slag;
		if (total <= 1e-9) {
			cu = 1.0;
			sn = fe = as = c = s = slag = 0.0;
			return;
		}
		cu /= total;
		sn /= total;
		fe /= total;
		as /= total;
		c /= total;
		s /= total;
		slag /= total;
	}

	/** Mass-weighted chemical mixing of two metal melts or charges. */
	public static AlloyComposition mix(AlloyComposition a, double massAKg,
									   AlloyComposition b, double massBKg) {
		double ma = Math.max(0.0, massAKg);
		double mb = Math.max(0.0, massBKg);
		double m = ma + mb;
		if (m <= 1e-9) return a.copy();
		return new AlloyComposition(
				(a.cu * ma + b.cu * mb) / m,
				(a.sn * ma + b.sn * mb) / m,
				(a.fe * ma + b.fe * mb) / m,
				(a.as * ma + b.as * mb) / m,
				(a.c * ma + b.c * mb) / m,
				(a.s * ma + b.s * mb) / m,
				(a.slag * ma + b.slag * mb) / m
		);
	}

	public double solidusTempK() {
		if (fe >= cu) {
			// Fe-C eutectic depression: pure Fe 1811 K -> 2% C cast iron ~1420 K
			double cDrop = Math.min(395.0, c * 18500.0);
			return Math.max(1390.0, 1811.0 - cDrop - s * 420.0);
		}
		// Cu-Sn / Cu-As solidus depression (~1358 K pure Cu down to ~1070 K at high Sn)
		double snDrop = Math.min(290.0, sn * 2400.0);
		double asDrop = Math.min(180.0, as * 1900.0);
		return Math.max(980.0, 1358.0 - snDrop - asDrop - s * 220.0);
	}

	public double liquidusTempK() {
		if (fe >= cu) {
			double cDrop = Math.min(340.0, c * 15500.0);
			return Math.max(1430.0, 1811.0 - cDrop + slag * 120.0);
		}
		double snDrop = Math.min(220.0, sn * 1650.0);
		double asDrop = Math.min(130.0, as * 1350.0);
		return Math.max(1060.0, 1358.0 - snDrop - asDrop + fe * 380.0);
	}

	/** Anneal / recrystallization temperature (~0.45 * T_melt). */
	public double recrystallizationTempK() {
		return solidusTempK() * 0.48;
	}

	public double densityKgM3() {
		return cu * 8960.0 + sn * 7310.0 + fe * 7870.0 + as * 5730.0 + c * 2250.0 + s * 2070.0 + slag * 2900.0;
	}

	public double thermalConductivity() {
		// Pure Cu (~120) -> Bronze (~55) -> Fe (~52) -> Steel (~40); slag/impurities suppress conductivity
		double base = cu * 120.0 + sn * 35.0 + fe * 52.0 + as * 24.0;
		return Math.max(14.0, base * (1.0 - Math.min(0.6, sn * 2.4 + c * 8.0 + slag * 1.4)));
	}

	public double specificHeat() {
		return Math.max(280.0, cu * 385.0 + sn * 228.0 + fe * 450.0 + slag * 820.0);
	}

	/**
	 * Intrinsic base hardness (Vickers-like normalized scale 1.0..10.0 comparable against stone Mohs).
	 * Annealed Cu ~3.0, 10% Sn bronze ~4.8, high-Sn bronze ~6.2, wrought Fe ~4.6, carburized steel ~6.5+.
	 */
	public double intrinsicHardness() {
		double base;
		if (fe >= cu) {
			base = 4.6 + Math.min(2.6, c * 185.0); // iron -> steel solid-solution/carbide boost
		} else {
			double snBoost = Math.min(3.3, sn * 26.0);
			double asBoost = Math.min(2.0, as * 24.0);
			base = 3.0 + snBoost + asBoost + fe * 1.5;
		}
		return Math.max(2.2, base - slag * 1.4);
	}

	/**
	 * Intrinsic cold ductility / workability before fracture (0.05..1.0).
	 * Pure copper is super ductile (~0.94); >14% Sn bronze grows brittle (delta intermetallic);
	 * sulfur & slag severely degrade malleability.
	 */
	public double intrinsicDuctility() {
		double base;
		if (fe >= cu) {
			base = 0.80 - Math.min(0.68, c * 32.0); // low-C wrought iron bends smoothly; cast iron snaps!
		} else {
			double snPenalty = sn <= 0.10 ? sn * 2.2 : 0.22 + (sn - 0.10) * 5.2;
			double asPenalty = as <= 0.06 ? as * 2.0 : 0.12 + (as - 0.06) * 4.5;
			base = 0.94 - snPenalty - asPenalty;
		}
		base -= s * 8.5 + slag * 1.8;
		return Math.max(0.05, Math.min(0.96, base));
	}

	/** Molten fluidity boost above Cu baseline (tin & moderate arsenic dramatically help cast filling!). */
	public double fluidityFactor() {
		double bonus = 1.0 + Math.min(0.55, sn * 4.5) + Math.min(0.35, as * 4.0) + Math.min(0.35, c * 15.0);
		return Math.max(0.35, bonus - slag * 1.8 - fe * (cu > 0.2 ? 0.35 : 0.0));
	}

	/** Environmental corrosion susceptibility (high Fe corrodes quickly unless alloyed/patinated). */
	public double corrosionRate() {
		if (fe >= cu) return 0.65 + s * 3.0;
		return Math.max(0.05, 0.28 - sn * 1.2 + s * 2.5 + slag * 0.5);
	}

	/** Dynamic RGB surface tint from continuous elemental chemistry. */
	public int[] rgbColor() {
		if (fe >= cu) {
			double steelDark = Math.min(1.0, c * 75.0);
			int r = (int) Math.round(146 - steelDark * 30 - slag * 42);
			int g = (int) Math.round(148 - steelDark * 28 - slag * 46);
			int b = (int) Math.round(154 - steelDark * 22 - slag * 50);
			return new int[]{clamp(r), clamp(g), clamp(b)};
		}
		// Pure Cu (198,118,76) -> 8% Sn golden bronze (206,170,84) -> >18% Sn pale speculum (210,204,184) -> As silvery-pink
		double tinShift = Math.min(1.0, sn / 0.12);
		double highTin = Math.max(0.0, Math.min(1.0, (sn - 0.12) / 0.14));
		double asShift = Math.min(1.0, as / 0.08);
		int r = (int) Math.round(198 + tinShift * 8 + highTin * 4 - asShift * 14 - slag * 60);
		int g = (int) Math.round(118 + tinShift * 52 + highTin * 34 + asShift * 26 - slag * 50);
		int b = (int) Math.round(76 + tinShift * 8 + highTin * 100 + asShift * 54 - slag * 40);
		return new int[]{clamp(r), clamp(g), clamp(b)};
	}

	public int colorR() {
		return rgbColor()[0];
	}

	public int colorG() {
		return rgbColor()[1];
	}

	public int colorB() {
		return rgbColor()[2];
	}

	public String describeComposition() {
		return descriptiveName();
	}

	/** Descriptive physical summary string (never a fixed tool/equipment tier!). */
	public String descriptiveName() {
		String base;
		if (fe >= cu) {
			if (c >= 0.020) base = "High-Carbon Cast Iron";
			else if (c >= 0.004) base = String.format("Steel (%.1f%% C)", c * 100.0);
			else if (slag > 0.06) base = "Iron Bloom";
			else base = "Wrought Iron";
		} else if (sn >= 0.02) {
			base = String.format("Cu-Sn Bronze (%.0f%% Sn)", sn * 100.0);
		} else if (as >= 0.015) {
			base = String.format("Arsenical Copper (%.1f%% As)", as * 100.0);
		} else {
			base = "Copper";
		}
		if (s > 0.015) base += " [Sulfurous]";
		if (slag > 0.12 && !(fe >= cu && c < 0.004)) base += " [Slaggy]";
		return base;
	}

	private static int clamp(int v) {
		return Math.max(15, Math.min(245, v));
	}

	public AlloyComposition copy() {
		return new AlloyComposition(cu, sn, fe, as, c, s, slag);
	}

	public NbtCompound toNbt() {
		NbtCompound t = new NbtCompound();
		t.putDouble("Cu", cu);
		t.putDouble("Sn", sn);
		t.putDouble("Fe", fe);
		t.putDouble("As", as);
		t.putDouble("C", c);
		t.putDouble("S", s);
		t.putDouble("Slag", slag);
		return t;
	}

	public static AlloyComposition fromNbt(NbtCompound t) {
		return new AlloyComposition(
				t.getDouble("Cu"), t.getDouble("Sn"), t.getDouble("Fe"),
				t.getDouble("As"), t.getDouble("C"), t.getDouble("S"), t.getDouble("Slag")
		);
	}
}
