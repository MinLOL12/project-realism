package com.projectrealism.metallurgy;

/**
 * Geological mineral species found inside ore veins, river gravels, and bog deposits.
 * Each mineral carries real metal stoichiometry, gangue affinity, roasting requirement,
 * reduction threshold, and visual tint.
 */
public enum MineralSpecies {
	NATIVE_COPPER("native_copper", TargetElement.CU, 0.96, false, 300.0, 1358.0, 184, 114, 82),
	MALACHITE("malachite", TargetElement.CU, 0.57, false, 960.0, 1358.0, 46, 152, 92),
	AZURITE("azurite", TargetElement.CU, 0.55, false, 950.0, 1358.0, 42, 96, 178),
	CHALCOPYRITE("chalcopyrite", TargetElement.CU, 0.34, true, 1080.0, 1358.0, 178, 154, 62),
	CASSITERITE("cassiterite", TargetElement.SN, 0.78, false, 1140.0, 505.0, 82, 68, 58),
	ARSENOPYRITE("arsenopyrite", TargetElement.AS, 0.46, true, 920.0, 1090.0, 168, 172, 162),
	HEMATITE("hematite", TargetElement.FE, 0.70, false, 1280.0, 1811.0, 122, 56, 48),
	MAGNETITE("magnetite", TargetElement.FE, 0.72, false, 1320.0, 1811.0, 54, 58, 66),
	LIMONITE("limonite", TargetElement.FE, 0.58, false, 1200.0, 1811.0, 156, 104, 44),
	SILICA_GANGUE("silica", TargetElement.NONE, 0.0, false, 9999.0, 1986.0, 188, 184, 176),
	CALCITE_FLUX("calcite", TargetElement.NONE, 0.0, false, 9999.0, 1612.0, 224, 218, 206);

	public enum TargetElement {
		CU, SN, FE, AS, NONE
	}

	public final String id;
	public final TargetElement element;
	/** Stoichiometric pure-metal mass yield of the pure mineral crystal (0..1). */
	public final double pureMetalFraction;
	/** Whether sulfide roasting in O2 is required prior to clean reduction. */
	public final boolean sulfide;
	/** Minimum reducing temperature (K) at which CO/char reduction proceeds briskly. */
	public final double reductionTempK;
	public final double productMeltTempK;
	public final int colorR;
	public final int colorG;
	public final int colorB;

	MineralSpecies(String id, TargetElement element, double pureMetalFraction, boolean sulfide,
				   double reductionTempK, double productMeltTempK,
				   int colorR, int colorG, int colorB) {
		this.id = id;
		this.element = element;
		this.pureMetalFraction = pureMetalFraction;
		this.sulfide = sulfide;
		this.reductionTempK = reductionTempK;
		this.productMeltTempK = productMeltTempK;
		this.colorR = colorR;
		this.colorG = colorG;
		this.colorB = colorB;
	}

	public static MineralSpecies fromId(String id) {
		if (id == null) return MALACHITE;
		for (MineralSpecies m : values()) {
			if (m.id.equals(id)) return m;
		}
		return MALACHITE;
	}
}
