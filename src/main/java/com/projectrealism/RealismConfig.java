package com.projectrealism;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * User-tunable numbers. Everything that decides how miserable the player is lives here, so server
 * owners can dial the misery without a recompile.
 */
public final class RealismConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "project-realism.json";

	/** Mass a player can carry before anything starts to hurt, in kilograms. */
	public double capacityKg = 30.0;

	/** Extra mass, in kg, beyond capacity that halves the player's speed. */
	public double halfLifeKg = 20.0;

	/**
	 * Shape of the slowdown curve. Larger values keep light overloads forgiving and then fall off a
	 * cliff; smaller values slow you from the very first extra kilogram.
	 */
	public double curveExponent = 1.25;

	/** Hard floor on the speed multiplier so a hoarder is never literally frozen in place. */
	public double minSpeedMultiplier = 0.12;

	/** Show the weight readout above the hotbar. */
	public boolean weightHud = true;

	/** How often (in ticks) the readout refreshes. 10 = twice a second. */
	public int hudIntervalTicks = 10;

	/** Heavy loads make you burn through food faster. */
	public boolean overloadExhaustion = true;

	/** Exhaustion added per second for every kilogram carried above capacity. */
	public double exhaustionPerKgPerSecond = 0.01;

	/** Whether creative-mode players are exempt from physics. */
	public boolean affectCreative = false;

	/** Whether creative-mode players see the readout. */
	public boolean hudInCreative = true;

	// ------------------------------------------------------------ early game ----

	/** Whether logs, planks and stone demand a crafted tool before they will yield. */
	public boolean requireTools = true;

	/** Whether rocks generate on the ground as a gatherable resource. */
	public boolean groundRocks = true;

	/** Rock placement attempts per newly generated chunk. */
	public int rocksPerChunk = 8;

	/** Chance that each attempt actually places a rock. */
	public double rockSpawnChance = 0.55;

	/** Seconds between regrowth passes over loaded chunks. 0 disables regrowth. */
	public int rockRegrowSeconds = 90;

	/** Whether sticks spawn on the ground near trees. */
	public boolean groundSticks = true;

	/** Chance per chunk that a fallen stick is placed near a tree. */
	public double stickSpawnChance = 0.5;

	/** Whether tall grass and foliage yield plant fiber. */
	public boolean plantFibers = true;

	/** Whether knapping (striking two rocks) replaces the vanilla stone tool recipes. */
	public boolean knapping = true;

	/**
	 * Whether rocks and fallen sticks are written into newly generated chunks.
	 *
	 * <p>Turn this off if world generation is ever suspected of misbehaving; rocks and sticks stop
	 * being generated in new terrain and nothing during generation touches the world.</p>
	 */
	public boolean generateRocksInNewChunks = true;

	// ---------------------------------------------------- trees & woodcraft ----

	/** Whether chopping trees creates notches and fells physical trees with impact damage. */
	public boolean physicalTreeChopping = true;

	/** Whether logs must be de-branched, cut, split, dried and planed before making planks. */
	public boolean logProcessing = true;

	/** Speed multiplier for moisture drying (higher = dries faster). */
	public double woodDrySpeedMultiplier = 1.0;

	/** Whether primitive tools dull, loosen bindings, and fracture with heavy use. */
	public boolean toolDegradation = true;

	// -------------------------------------------------- thermodynamics & fire ----

	/** Whether physical combustion, heat transfer (conduction/convection/radiation), and moisture physics run. */
	public boolean thermodynamicsSimulation = true;

	/** Whether coarse-grid airflow, buoyancy plumes, smoke pooling, and O2/CO shelter effects are simulated. */
	public boolean dynamicAirflow = true;

	/** Whether thermal expansion, shock cracking, clay firing, metal softening, and char collapse occur. */
	public boolean thermalMaterialDamage = true;

	/** Whether firemaking requires friction energy or sparks conditioned on moisture and oxygen. */
	public boolean realisticFiremaking = true;

	/** Whether cooking simulates thermal surface-to-core penetration across cooking methods. */
	public boolean thermalCooking = true;

	/** Baseline temperate ambient temperature in Kelvin (293.15 K = 20 C). */
	public double ambientTempKelvin = 293.15;

	// -------------------------------------------------- hydration & physiology ---

	/** Whether multi-compartment body water, GI absorption, electrolytes, and kidneys run. */
	public boolean hydrationSimulation = true;

	/** Speed multiplier mapping real seconds to physiological seconds (default 12x). */
	public double hydrationTimeScale = 12.0;

	/** Whether thermoregulatory sweating couples with body temperature, RH, and airflow. */
	public boolean sweatThermoregulation = true;

	/** Whether water sources carry temperature, salinity, sediment, mineral, microbe, and chemical profiles. */
	public boolean waterQualitySimulation = true;

	/** Whether the perceived thirst and body temp HUD is rendered. */
	public boolean hydrationHud = true;

	// ----------------------------------------- diegetic material processing ---

	/** Whether anatomical hides, physical workstations, stitching, leaks, and material aging run. */
	public boolean materialProcessing = true;

	public static RealismConfig load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
		RealismConfig config = new RealismConfig();

		if (Files.exists(path)) {
			try {
				String json = Files.readString(path);
				RealismConfig loaded = GSON.fromJson(json, RealismConfig.class);
				if (loaded != null) {
					config = loaded;
				}
			} catch (IOException | JsonSyntaxException e) {
				ProjectRealism.LOGGER.error("Could not read config, falling back to defaults.", e);
			}
		}

		config.clamp();
		config.save(path);
		return config;
	}

	public void save(Path path) {
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(this));
		} catch (IOException e) {
			ProjectRealism.LOGGER.error("Could not write config.", e);
		}
	}

	public void clamp() {
		if (capacityKg < 1.0) {
			capacityKg = 1.0;
		}
		if (halfLifeKg < 1.0) {
			halfLifeKg = 1.0;
		}
		if (curveExponent < 0.1) {
			curveExponent = 0.1;
		}
		if (minSpeedMultiplier < 0.0 || minSpeedMultiplier > 1.0) {
			minSpeedMultiplier = 0.12;
		}
		if (hudIntervalTicks < 1) {
			hudIntervalTicks = 10;
		}
		if (exhaustionPerKgPerSecond < 0.0) {
			exhaustionPerKgPerSecond = 0.0;
		}
		if (rocksPerChunk < 0 || rocksPerChunk > 64) {
			rocksPerChunk = 8;
		}
		if (rockSpawnChance < 0.0 || rockSpawnChance > 1.0) {
			rockSpawnChance = 0.55;
		}
		if (rockRegrowSeconds < 0) {
			rockRegrowSeconds = 90;
		}
		if (stickSpawnChance < 0.0 || stickSpawnChance > 1.0) {
			stickSpawnChance = 0.5;
		}
		if (woodDrySpeedMultiplier < 0.1) {
			woodDrySpeedMultiplier = 0.1;
		} else if (woodDrySpeedMultiplier > 20.0) {
			woodDrySpeedMultiplier = 20.0;
		}
		if (ambientTempKelvin < 230.0) {
			ambientTempKelvin = 230.0;
		} else if (ambientTempKelvin > 325.0) {
			ambientTempKelvin = 325.0;
		}
		if (hydrationTimeScale < 0.5) {
			hydrationTimeScale = 0.5;
		} else if (hydrationTimeScale > 60.0) {
			hydrationTimeScale = 60.0;
		}
	}
}
