package com.projectrealism.knapping;

/**
 * Physical geological rock materials with distinct mineral density, toughness, conchoidal
 * cleavage behavior, hardness, inclusion probability, edge acuity potential, and visual color.
 */
public enum StoneMaterial {
	FLINT("flint", 2600.0, 0.58, 0.90, 7.0, 0.92, 0.06, 56, 62, 68),
	CHERT("chert", 2650.0, 0.68, 0.82, 6.8, 0.84, 0.10, 128, 120, 108),
	OBSIDIAN("obsidian", 2400.0, 0.28, 0.98, 5.5, 0.99, 0.02, 28, 24, 38),
	QUARTZITE("quartzite", 2700.0, 0.88, 0.45, 7.2, 0.66, 0.14, 212, 206, 198),
	BASALT("basalt", 2950.0, 0.92, 0.36, 6.5, 0.58, 0.16, 48, 50, 54),
	GRANITE("granite", 2750.0, 0.78, 0.22, 6.2, 0.46, 0.24, 152, 136, 130),
	LIMESTONE("limestone", 2450.0, 0.42, 0.18, 3.5, 0.38, 0.18, 194, 188, 170);

	public final String id;
	public final double densityKgM3;
	/** Resistance to fracture propagation / shock failure (0..1). */
	public final double toughness;
	/** How cleanly impacts propagate thin conchoidal flake sheets vs local crumbling (0..1). */
	public final double conchoidal;
	/** Mohs mineral scratch/abrasion hardness (~3.5 limestone .. ~7.2 quartzite). */
	public final double mohsHardness;
	/** Minimum achievable edge acuity factor (higher -> finer micro-edge radius). */
	public final double acuityPotential;
	/** Fraction of internal volume seeded with heterogeneities / coarse crystal inclusions. */
	public final double inclusionRate;
	public final int colorR;
	public final int colorG;
	public final int colorB;

	StoneMaterial(String id, double densityKgM3, double toughness, double conchoidal,
				  double mohsHardness, double acuityPotential, double inclusionRate,
				  int colorR, int colorG, int colorB) {
		this.id = id;
		this.densityKgM3 = densityKgM3;
		this.toughness = toughness;
		this.conchoidal = conchoidal;
		this.mohsHardness = mohsHardness;
		this.acuityPotential = acuityPotential;
		this.inclusionRate = inclusionRate;
		this.colorR = colorR;
		this.colorG = colorG;
		this.colorB = colorB;
	}

	public static StoneMaterial fromId(String id) {
		if (id == null) return FLINT;
		for (StoneMaterial m : values()) {
			if (m.id.equals(id)) return m;
		}
		return FLINT;
	}
}
