package com.projectrealism.hydration;

import com.projectrealism.thermo.ThermalConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * Physical, chemical, and microbiological characterization of any water sample or water source.
 *
 * <p>Boiling inactivates microbes without removing dissolved salts or chemical contaminants;
 * charcoal/fiber filtration reduces turbidity and chemicals without desalinating seawater.</p>
 */
public record WaterQuality(
		double tempK,
		double salinityGPerL,
		double turbidity,
		double mineralMgPerL,
		double microbialLoad,
		double chemicalLoad
) {
	private static final String KEY_PRESENT = "WaterQualPresent";
	private static final String KEY_TEMP = "WQTempK";
	private static final String KEY_SALINITY = "WQSalinity";
	private static final String KEY_TURBIDITY = "WQTurbidity";
	private static final String KEY_MINERALS = "WQMinerals";
	private static final String KEY_MICROBES = "WQMicrobes";
	private static final String KEY_CHEMICALS = "WQChemicals";
	private static final String KEY_VOLUME_ML = "WaterVolMl";

	public static final WaterQuality FRESH_SPRING = new WaterQuality(286.15, 0.04, 0.03, 180.0, 0.03, 0.0);
	public static final WaterQuality RAINWATER = new WaterQuality(289.15, 0.005, 0.01, 12.0, 0.01, 0.0);
	public static final WaterQuality RIVER_WATER = new WaterQuality(288.15, 0.08, 0.28, 95.0, 0.34, 0.02);
	public static final WaterQuality SWAMP_WATER = new WaterQuality(297.15, 1.4, 0.72, 140.0, 0.82, 0.16);
	public static final WaterQuality OCEAN_SEAWATER = new WaterQuality(288.15, 35.0, 0.14, 1300.0, 0.25, 0.04);
	public static final WaterQuality POLLUTED_WATER = new WaterQuality(301.15, 2.5, 0.68, 420.0, 0.60, 0.78);

	public WaterQuality clamped() {
		return new WaterQuality(
				Math.max(273.15, Math.min(373.15, tempK)),
				Math.max(0.0, Math.min(80.0, salinityGPerL)),
				Math.max(0.0, Math.min(1.0, turbidity)),
				Math.max(0.0, Math.min(2500.0, mineralMgPerL)),
				Math.max(0.0, Math.min(1.0, microbialLoad)),
				Math.max(0.0, Math.min(1.0, chemicalLoad)));
	}

	/** Sodium concentration in mmol/L derived from dissolved NaCl salinity (g/L). */
	public double sodiumMm() {
		return (salinityGPerL / PhysiologicalConstants.NACL_MOLAR_MASS_G) * 1000.0;
	}

	/** Chloride concentration in mmol/L derived from salinity. */
	public double chlorideMm() {
		return sodiumMm();
	}

	/** Approximate dissolved potassium contribution in mmol/L derived from mineral content. */
	public double potassiumMm() {
		return (mineralMgPerL * 0.08) / 39.1;
	}

	/** Approximate total solute osmolality (mOsm/L). */
	public double osmolalityMOsm() {
		return sodiumMm() * 2.0 + potassiumMm() + (mineralMgPerL / 100.0);
	}

	/**
	 * Heats water for {@code dtSeconds} toward {@code sourceTempK}. Once water exceeds ~368 K (95 C),
	 * microbes are rapidly inactivated while salinity and most chemical toxins persist.
	 */
	public WaterQuality stepHeating(double sourceTempK, double ambientTempK, double dtSeconds) {
		double dt = Math.max(0.05, Math.min(5.0, dtSeconds));
		// Exponential thermal lag for ~0.5 L vessel heated over hearth/coals
		double rate = sourceTempK > tempK ? 0.045 : 0.008;
		double target = sourceTempK > tempK ? Math.min(ThermalConstants.BOILING_K, sourceTempK) : ambientTempK;
		double nextT = Math.max(273.15, Math.min(ThermalConstants.BOILING_K,
				tempK + (target - tempK) * Math.min(0.65, rate * dt)));

		double nextMicrobes = microbialLoad;
		if (nextT >= 343.15) {
			// Thermal log-inactivation accelerates dramatically near boiling (95-100 C)
			double killRate = nextT >= 368.15 ? 0.65 : 0.05 * Math.exp((nextT - 343.15) / 10.0);
			nextMicrobes = Math.max(0.0, microbialLoad * Math.exp(-killRate * dt));
		}
		// Slight evaporative concentration if actively boiling hard
		double nextSal = nextT >= 371.0 ? Math.min(80.0, salinityGPerL * (1.0 + 0.002 * dt)) : salinityGPerL;
		return new WaterQuality(nextT, nextSal, turbidity, mineralMgPerL, nextMicrobes, chemicalLoad).clamped();
	}

	/** Charcoal + fiber mechanical filtration: clarifies sediment & adsorbs chemical toxins; leaves salt intact! */
	public WaterQuality filterWithCharcoal() {
		return new WaterQuality(
				tempK,
				salinityGPerL, // Filtration does NOT remove dissolved salts!
				turbidity * 0.08,
				mineralMgPerL * 0.85,
				microbialLoad * 0.70, // Physical mesh stops some cysts/protozoa, but bacteria require boiling!
				chemicalLoad * 0.14
		).clamped();
	}

	/** Resolves realistic environmental water quality at a world position. */
	public static WaterQuality fromWorld(ServerWorld world, BlockPos pos) {
		var biome = world.getBiome(pos);
		float biomeTemp = biome.value().getTemperature();
		double waterTempK = Math.max(275.5, Math.min(305.0, ThermalConstants.FREEZING_K + 4.0 + biomeTemp * 18.0));

		double sal = 0.06;
		double turb = 0.22;
		double mins = 90.0;
		double mic = 0.28;
		double chem = 0.01;

		if (biome.isIn(BiomeTags.IS_OCEAN) || biome.isIn(BiomeTags.IS_BEACH)) {
			sal = 35.0;
			turb = 0.14;
			mins = 1280.0;
			mic = 0.24;
			chem = 0.03;
		} else if (biome.isIn(BiomeTags.SWAMP_HUT_HAS_STRUCTURE)
				|| world.getBiome(pos).getIdAsString().contains("swamp")
				|| world.getBiome(pos).getIdAsString().contains("mangrove")) {
			sal = world.getBiome(pos).getIdAsString().contains("mangrove") ? 11.5 : 1.1;
			turb = 0.74;
			mins = 160.0;
			mic = 0.84;
			chem = 0.14;
		} else if (biome.isIn(BiomeTags.IS_MOUNTAIN) || biome.isIn(BiomeTags.IS_HILL) || pos.getY() >= 96) {
			sal = 0.02;
			turb = 0.04;
			mins = 135.0;
			mic = 0.06;
			chem = 0.0;
		} else if (biome.isIn(BiomeTags.IS_JUNGLE)) {
			sal = 0.08;
			turb = 0.45;
			mins = 110.0;
			mic = 0.62;
			chem = 0.05;
		} else if (pos.getY() < 45) {
			// Underground spring / aquifer: cold, clear, mineral-rich
			waterTempK = Math.min(waterTempK, 285.15);
			sal = 0.15;
			turb = 0.06;
			mins = 310.0;
			mic = 0.08;
		}

		// Local surroundings: lava/sulfurous deep pools pollute water; mud raises turbidity/microbes
		for (int dx = -3; dx <= 3; dx++) {
			for (int dy = -2; dy <= 2; dy++) {
				for (int dz = -3; dz <= 3; dz++) {
					BlockPos p = pos.add(dx, dy, dz);
					if (!world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) {
						continue;
					}
					BlockState s = world.getBlockState(p);
					if (s.isOf(Blocks.LAVA) || s.isOf(Blocks.MAGMA_BLOCK)) {
						waterTempK = Math.min(358.0, waterTempK + 22.0);
						chem = Math.min(0.95, chem + 0.35);
						mins = Math.min(2000.0, mins + 260.0);
					} else if (s.isOf(Blocks.MUD) || s.isOf(Blocks.MUDDY_MANGROVE_ROOTS)) {
						turb = Math.min(0.96, turb + 0.18);
						mic = Math.min(0.96, mic + 0.12);
					} else if (s.isOf(Blocks.POINTED_DRIPSTONE) || s.isOf(Blocks.DRIPSTONE_BLOCK)) {
						mins = Math.min(1200.0, mins + 80.0);
					}
				}
			}
		}

		return new WaterQuality(waterTempK, sal, turb, mins, mic, chem).clamped();
	}

	public static WaterQuality fromRain(ServerWorld world, BlockPos pos) {
		float biomeTemp = world.getBiome(pos).value().getTemperature();
		double rainTemp = Math.max(276.0, Math.min(298.0, ThermalConstants.FREEZING_K + 2.0 + biomeTemp * 14.0));
		return new WaterQuality(rainTemp, 0.005, 0.01, 10.0, 0.01, 0.0).clamped();
	}

	public static boolean hasQuality(ItemStack stack) {
		NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
		return comp != null && comp.copyNbt().getBoolean(KEY_PRESENT);
	}

	public static WaterQuality read(ItemStack stack, WaterQuality fallback) {
		NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (comp == null) {
			return fallback;
		}
		NbtCompound nbt = comp.copyNbt();
		if (!nbt.getBoolean(KEY_PRESENT)) {
			return fallback;
		}
		return new WaterQuality(
				nbt.getDouble(KEY_TEMP),
				nbt.getDouble(KEY_SALINITY),
				nbt.getDouble(KEY_TURBIDITY),
				nbt.getDouble(KEY_MINERALS),
				nbt.getDouble(KEY_MICROBES),
				nbt.getDouble(KEY_CHEMICALS)
		).clamped();
	}

	public static void write(ItemStack stack, WaterQuality q) {
		WaterQuality cq = q.clamped();
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, nbt -> {
			nbt.putBoolean(KEY_PRESENT, true);
			nbt.putDouble(KEY_TEMP, cq.tempK);
			nbt.putDouble(KEY_SALINITY, cq.salinityGPerL);
			nbt.putDouble(KEY_TURBIDITY, cq.turbidity);
			nbt.putDouble(KEY_MINERALS, cq.mineralMgPerL);
			nbt.putDouble(KEY_MICROBES, cq.microbialLoad);
			nbt.putDouble(KEY_CHEMICALS, cq.chemicalLoad);
		});
	}

	public static int readVolumeMl(ItemStack stack, int fallbackMl) {
		NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (comp == null || !comp.copyNbt().contains(KEY_VOLUME_ML)) {
			return fallbackMl;
		}
		return Math.max(0, comp.copyNbt().getInt(KEY_VOLUME_ML));
	}

	public static void writeVolumeMl(ItemStack stack, int volumeMl) {
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack,
				nbt -> nbt.putInt(KEY_VOLUME_ML, Math.max(0, volumeMl)));
	}

	public static void appendTooltip(ItemStack stack, List<Text> tooltip) {
		if (!hasQuality(stack)) {
			return;
		}
		WaterQuality q = read(stack, RIVER_WATER);
		int tempC = (int) Math.round(ThermalConstants.toCelsius(q.tempK));
		tooltip.add(Text.translatable("project_realism.tooltip.water_temp_sal",
						tempC, String.format("%.1f", q.salinityGPerL))
				.formatted(q.salinityGPerL > 5.0 ? Formatting.RED : (tempC >= 90 ? Formatting.GOLD : Formatting.AQUA)));

		String clarityKey = q.turbidity < 0.12 ? "project_realism.water.clear"
				: (q.turbidity < 0.45 ? "project_realism.water.cloudy" : "project_realism.water.muddy");
		tooltip.add(Text.translatable("project_realism.tooltip.water_minerals_clarity",
						Math.round(q.mineralMgPerL), Text.translatable(clarityKey))
				.formatted(Formatting.GRAY));

		if (q.microbialLoad >= 0.15) {
			tooltip.add(Text.translatable("project_realism.tooltip.water_microbes",
							Math.round(q.microbialLoad * 100.0))
					.formatted(q.microbialLoad >= 0.50 ? Formatting.RED : Formatting.YELLOW));
		} else {
			tooltip.add(Text.translatable("project_realism.tooltip.water_microbe_safe").formatted(Formatting.GREEN));
		}

		if (q.chemicalLoad >= 0.12) {
			tooltip.add(Text.translatable("project_realism.tooltip.water_chemical",
							Math.round(q.chemicalLoad * 100.0))
					.formatted(Formatting.DARK_PURPLE));
		}
	}
}
