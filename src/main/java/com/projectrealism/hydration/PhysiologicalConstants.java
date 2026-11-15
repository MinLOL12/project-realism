package com.projectrealism.hydration;

/**
 * Physical and physiological reference constants for human fluid compartments, electrolytes,
 * thermoregulatory sweating, renal regulation, and water chemistry.
 */
public final class PhysiologicalConstants {
	private PhysiologicalConstants() {
	}

	/** Reference adult body mass in kg. */
	public static final double REFERENCE_BODY_MASS_KG = 70.0;

	/** Reference total body water (~60% of 70 kg = 42.0 L). */
	public static final double REFERENCE_TBW_LITERS = 42.0;

	/** Baseline compartment volumes (L). */
	public static final double BASE_ICF_LITERS = 28.0;
	public static final double BASE_ISF_LITERS = 11.0;
	public static final double BASE_PLASMA_LITERS = 3.0;
	public static final double BASE_ECF_LITERS = BASE_ISF_LITERS + BASE_PLASMA_LITERS; // 14.0 L

	/** Target fraction of extracellular fluid residing in intravascular blood plasma. */
	public static final double TARGET_PLASMA_ECF_FRACTION = BASE_PLASMA_LITERS / BASE_ECF_LITERS;

	/** Baseline electrolyte concentrations in extracellular fluid / blood plasma (mmol/L = mM). */
	public static final double BASE_PLASMA_NA_MM = 140.0;
	public static final double BASE_PLASMA_K_MM = 4.2;
	public static final double BASE_PLASMA_CL_MM = 104.0;
	public static final double BASE_ECF_OTHER_OSM_MM = 41.8; // HCO3-, glucose, urea, etc.

	/** Baseline total extracellular osmolality (140 + 4.2 + 104 + 41.8 = 290.0 mOsm/L). */
	public static final double BASE_OSMOLALITY_MOSM = 290.0;

	/** Baseline intracellular potassium and impermanent solute concentrations (mmol/L). */
	public static final double BASE_ICF_K_MM = 145.0;
	public static final double BASE_ICF_OTHER_OSM_MM = 145.0; // Sums to 290.0 mOsm/L

	/** Sweat electrolyte concentrations (mmol/L = mM, hypotonic relative to plasma). */
	public static final double SWEAT_NA_MM = 45.0;
	public static final double SWEAT_K_MM = 5.0;
	public static final double SWEAT_CL_MM = 40.0;

	/** Latent heat of vaporization of sweat on skin at ~33 C (J/kg). */
	public static final double SWEAT_LATENT_VAPORIZATION = 2.41e6;

	/** Specific heat capacity of human body tissues (J / (kg * K)). */
	public static final double BODY_SPECIFIC_HEAT = 3470.0;

	/** Reference normal core temperature (37.0 C) and skin temperature (33.0 C). */
	public static final double NORMAL_CORE_TEMP_K = 310.15;
	public static final double NORMAL_SKIN_TEMP_K = 306.15;

	/** Standard adult skin surface area (m^2). */
	public static final double SKIN_AREA_M2 = 1.85;

	/** Resting basal metabolic heat production (Watts). */
	public static final double BASAL_METABOLIC_W = 85.0;

	/** Molar mass of NaCl (g/mol) for salinity g/L -> mM conversion. */
	public static final double NACL_MOLAR_MASS_G = 58.44;
}
