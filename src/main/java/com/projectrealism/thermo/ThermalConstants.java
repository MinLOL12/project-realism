package com.projectrealism.thermo;

/**
 * Physical and chemical constants for combustion, heat transfer, moisture thermodynamics, and
 * air-quality modeling.
 */
public final class ThermalConstants {
	private ThermalConstants() {
	}

	/** Stefan-Boltzmann constant in W * m^-2 * K^-4. */
	public static final double STEFAN_BOLTZMANN = 5.670374419e-8;

	/** Freezing point of water in Kelvin (0 C). */
	public static final double FREEZING_K = 273.15;

	/** Boiling point of water at 1 atm in Kelvin (100 C). */
	public static final double BOILING_K = 373.15;

	/** Reference comfortable ambient air temperature (20 C). */
	public static final double AMBIENT_DEFAULT_K = 293.15;

	/** Specific heat capacity of liquid water in J / (kg * K). */
	public static final double WATER_SPECIFIC_HEAT = 4184.0;

	/** Latent heat of vaporization of liquid water in J / kg. */
	public static final double WATER_LATENT_VAPORIZATION = 2.26e6;

	/** Specific heat capacity of dry air at constant pressure in J / (kg * K). */
	public static final double AIR_SPECIFIC_HEAT = 1005.0;

	/** Ambient dry air density near sea level in kg / m^3. */
	public static final double AIR_DENSITY = 1.225;

	/** Ambient oxygen volume/mole fraction. */
	public static final double AMBIENT_O2 = 0.2095;

	/** Ambient carbon dioxide volume fraction. */
	public static final double AMBIENT_CO2 = 0.0004;

	/** Minimum O2 fraction required to sustain flaming gas combustion. */
	public static final double MIN_O2_FLAME = 0.125;

	/** Minimum O2 fraction required to sustain smoldering char oxidation. */
	public static final double MIN_O2_EMBER = 0.06;

	/** Temperature above which flaming gas combustion can initiate spontaneously when O2 allows. */
	public static final double AUTOIGNITION_GAS_K = 560.0;

	/** Minimum temperature for glowing char oxidation. */
	public static final double CHAR_OXIDATION_K = 620.0;

	/** Heat of combustion of pyrolyzed wood volatile gases (J / kg volatiles). */
	public static final double HEAT_VOLATILE_COMBUSTION = 1.65e7;

	/** Heat of combustion of fixed carbon / charcoal (J / kg carbon). */
	public static final double HEAT_CHAR_COMBUSTION = 3.25e7;

	/** Endothermic heat absorbed during wood pyrolysis (J / kg dry wood decomposed). */
	public static final double HEAT_PYROLYSIS_ENDOTHERM = 4.5e5;

	/** Molar / mass CO yield rises sharply when O2 falls below this threshold. */
	public static final double INCOMPLETE_COMBUSTION_O2 = 0.16;

	/** Universal gas constant R in J / (mol * K). */
	public static final double GAS_CONSTANT = 8.31446;

	/** Gravitational acceleration in m / s^2. */
	public static final double GRAVITY = 9.81;

	/** Converts Kelvin to Celsius. */
	public static double toCelsius(double kelvin) {
		return kelvin - FREEZING_K;
	}

	/** Converts Celsius to Kelvin. */
	public static double toKelvin(double celsius) {
		return celsius + FREEZING_K;
	}
}
