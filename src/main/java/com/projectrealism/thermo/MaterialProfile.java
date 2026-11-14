package com.projectrealism.thermo;

import com.projectrealism.primitive.PrimitiveItems;
import com.projectrealism.weight.WeightManager;
import com.projectrealism.wood.FallenTrunkBlock;
import com.projectrealism.wood.FreshLogBlock;
import com.projectrealism.wood.NotchedLogBlock;
import com.projectrealism.wood.RoughPlankBlock;
import com.projectrealism.wood.SplitWoodBlock;
import com.projectrealism.wood.WoodSpecies;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;

/**
 * Physical, thermal, and chemical composition profile for blocks and fuel/food items.
 *
 * <p>Reuses item mass data from {@link WeightManager} so heavy dense logs genuinely require
 * proportionally more thermal energy ({@code Q = m * c_p * dT}) than light twigs or birch.</p>
 */
public final class MaterialProfile {

	public enum MaterialCategory {
		WOOD,
		CHARCOAL,
		PLANT_FIBER,
		STONE,
		CLAY,
		METAL,
		SOIL,
		WATER,
		AIR
	}

	public record Properties(
			MaterialCategory category,
			double dryMassKg,
			double densityKgM3,
			double specificHeat,
			double thermalConductivity,
			double porosity,
			double emissivity,
			double surfaceAreaM2,
			double volatileFraction,
			double fixedCarbonFraction,
			double ashFraction,
			double pyrolysisStartK,
			double ignitionTempK,
			double defaultMoistureRatio,
			double moistureDiffusivity
	) {
		public boolean isCombustible() {
			return volatileFraction > 0.01 || fixedCarbonFraction > 0.01;
		}
	}

	private MaterialProfile() {
	}

	// Canonical presets (pure constants safe for both runtime and unit tests without Bootstrap)
	public static final Properties OAK_WOOD = woodPreset(12.0, 750.0, 1750.0, 0.16, 0.22, 1.8, 0.68, 0.29, 0.03, 545.0, 585.0, 0.18, 0.65);
	public static final Properties BIRCH_WOOD = woodPreset(6.5, 650.0, 1700.0, 0.14, 0.26, 2.0, 0.72, 0.255, 0.025, 525.0, 565.0, 0.16, 0.95);
	public static final Properties SPRUCE_WOOD = woodPreset(5.5, 450.0, 1650.0, 0.12, 0.30, 2.2, 0.76, 0.22, 0.02, 510.0, 550.0, 0.16, 1.15);
	public static final Properties DARK_OAK_WOOD = woodPreset(16.0, 880.0, 1850.0, 0.19, 0.16, 1.7, 0.62, 0.345, 0.035, 565.0, 610.0, 0.20, 0.50);

	public static final Properties CHARCOAL = new Properties(
			MaterialCategory.CHARCOAL, 1.5, 350.0, 1050.0, 0.08, 0.68, 0.95, 2.8,
			0.06, 0.89, 0.05, 580.0, 620.0, 0.03, 1.2);

	public static final Properties TINDER_FIBER = new Properties(
			MaterialCategory.PLANT_FIBER, 0.2, 140.0, 1450.0, 0.05, 0.85, 0.86, 4.5,
			0.80, 0.15, 0.05, 465.0, 505.0, 0.10, 2.5);

	public static final Properties STICK_KINDLING = new Properties(
			MaterialCategory.WOOD, 0.35, 520.0, 1650.0, 0.13, 0.55, 0.88, 3.2,
			0.75, 0.22, 0.03, 495.0, 535.0, 0.12, 1.8);

	public static final Properties GRANITE_STONE = new Properties(
			MaterialCategory.STONE, 25.0, 2700.0, 790.0, 2.80, 0.015, 0.92, 1.4,
			0.0, 0.0, 1.0, 9999.0, 9999.0, 0.005, 0.1);

	public static final Properties LIMESTONE_STONE = new Properties(
			MaterialCategory.STONE, 22.0, 2500.0, 840.0, 1.90, 0.09, 0.91, 1.5,
			0.0, 0.0, 1.0, 9999.0, 9999.0, 0.045, 0.2);

	public static final Properties CLAY_BODY = new Properties(
			MaterialCategory.CLAY, 16.0, 1900.0, 920.0, 1.10, 0.22, 0.90, 1.4,
			0.0, 0.0, 1.0, 9999.0, 9999.0, 0.24, 0.4);

	public static final Properties FIRED_CERAMIC = new Properties(
			MaterialCategory.CLAY, 15.0, 2050.0, 860.0, 1.35, 0.08, 0.91, 1.4,
			0.0, 0.0, 1.0, 9999.0, 9999.0, 0.005, 0.15);

	public static final Properties IRON_METAL = new Properties(
			MaterialCategory.METAL, 55.0, 7870.0, 450.0, 52.0, 0.001, 0.45, 1.6,
			0.0, 0.0, 1.0, 9999.0, 9999.0, 0.0, 0.0);

	public static final Properties COPPER_METAL = new Properties(
			MaterialCategory.METAL, 45.0, 8960.0, 385.0, 120.0, 0.001, 0.35, 1.6,
			0.0, 0.0, 1.0, 9999.0, 9999.0, 0.0, 0.0);

	public static final Properties MINERAL_SOIL = new Properties(
			MaterialCategory.SOIL, 12.0, 1450.0, 1040.0, 0.55, 0.35, 0.92, 1.2,
			0.0, 0.0, 1.0, 9999.0, 9999.0, 0.18, 0.4);

	private static Properties woodPreset(double mass, double density, double cp, double k,
										 double porosity, double area, double vol, double fc,
										 double ash, double pyroK, double ignK,
										 double moisture, double diff) {
		return new Properties(MaterialCategory.WOOD, mass, density, cp, k, porosity, 0.90,
				area, vol, fc, ash, pyroK, ignK, moisture, diff);
	}

	/** Resolves physical properties for a world block, deriving mass from {@link WeightManager}. */
	public static Properties forBlock(BlockState state) {
		Block b = state.getBlock();
		if (state.isAir()) {
			return new Properties(MaterialCategory.AIR, 1.2, 1.225, 1005.0, 0.026, 1.0,
					0.0, 1.0, 0.0, 0.0, 0.0, 9999.0, 9999.0, 0.01, 1.0);
		}
		if (b == Blocks.WATER) {
			return new Properties(MaterialCategory.WATER, 1000.0, 1000.0, 4184.0, 0.60, 0.0,
					0.96, 1.0, 0.0, 0.0, 0.0, 9999.0, 9999.0, 1.0, 1.0);
		}

		// Wood species blocks
		var maybeSpecies = WoodSpecies.fromBlock(b);
		if (maybeSpecies.isPresent()) {
			WoodSpecies sp = maybeSpecies.get();
			Properties base = forSpecies(sp);
			double itemMass = lookupBlockMass(b, base.dryMassKg());

			if (b instanceof SplitWoodBlock) {
				// Split firewood exposes 2.2x surface area and much higher porosity for airflow
				return withGeometry(base, itemMass, 0.52, base.surfaceAreaM2() * 1.9,
						0.60, base.moistureDiffusivity() * 2.2);
			}
			if (b instanceof RoughPlankBlock) {
				return withGeometry(base, itemMass, 0.38, base.surfaceAreaM2() * 2.1,
						0.55, base.moistureDiffusivity() * 1.8);
			}
			if (b instanceof FreshLogBlock || b instanceof FallenTrunkBlock || b instanceof NotchedLogBlock) {
				return withGeometry(base, itemMass, base.porosity(), base.surfaceAreaM2(),
						0.72, base.moistureDiffusivity());
			}
			return withGeometry(base, itemMass, base.porosity(), base.surfaceAreaM2(),
					0.16, base.moistureDiffusivity());
		}

		if (state.isIn(BlockTags.PLANKS) || state.isIn(BlockTags.WOODEN_FENCES)
				|| state.isIn(BlockTags.WOODEN_DOORS) || state.isIn(BlockTags.WOODEN_STAIRS)
				|| state.isIn(BlockTags.WOODEN_SLABS) || state.isIn(BlockTags.LOGS)) {
			double m = lookupBlockMass(b, 3.0);
			return withGeometry(OAK_WOOD, m, 0.36, 2.4, 0.14, 1.2);
		}

		if (state.isIn(BlockTags.LEAVES) || b == Blocks.HAY_BLOCK || b == Blocks.SHORT_GRASS
				|| b == Blocks.TALL_GRASS || b == Blocks.FERN || b == Blocks.MOSS_BLOCK) {
			double m = lookupBlockMass(b, 0.6);
			return withGeometry(TINDER_FIBER, m, 0.85, 5.2, 0.45, 2.4);
		}

		if (state.isIn(BlockTags.WOOL) || b == Blocks.COAL_BLOCK) {
			if (b == Blocks.COAL_BLOCK) {
				return withGeometry(CHARCOAL, lookupBlockMass(b, 30.0), 0.25, 1.6, 0.02, 0.5);
			}
			return withGeometry(TINDER_FIBER, lookupBlockMass(b, 1.5), 0.72, 3.5, 0.08, 1.6);
		}

		if (b == Blocks.CLAY) {
			return CLAY_BODY;
		}
		if (b == Blocks.TERRACOTTA || b == Blocks.BRICKS || b == Blocks.MUD_BRICKS) {
			return FIRED_CERAMIC;
		}
		if (b == Blocks.IRON_BLOCK || b == Blocks.ANVIL || b == Blocks.CHIPPED_ANVIL
				|| b == Blocks.DAMAGED_ANVIL || b == Blocks.IRON_BARS || b == Blocks.IRON_DOOR) {
			return withGeometry(IRON_METAL, lookupBlockMass(b, 35.0), 0.001, 1.6, 0.0, 0.0);
		}
		if (b == Blocks.COPPER_BLOCK || b == Blocks.CUT_COPPER || b == Blocks.GOLD_BLOCK) {
			return withGeometry(COPPER_METAL, lookupBlockMass(b, 35.0), 0.001, 1.6, 0.0, 0.0);
		}
		if (b == Blocks.COBBLESTONE || b == Blocks.MOSSY_COBBLESTONE || b == Blocks.SANDSTONE
				|| b == Blocks.TUFF || b == Blocks.CALCITE || b == Blocks.DRIPSTONE_BLOCK) {
			// Higher porosity stones carry trapped moisture that spalls under thermal shock
			return withGeometry(LIMESTONE_STONE, lookupBlockMass(b, 5.0) * 4.0, 0.09, 1.5, 0.05, 0.2);
		}
		if (state.isIn(BlockTags.BASE_STONE_OVERWORLD) || b == Blocks.STONE_BRICKS
				|| b == Blocks.DEEPSLATE || b == Blocks.BASALT || b == Blocks.ANDESITE
				|| b == Blocks.GRANITE || b == Blocks.DIORITE) {
			return withGeometry(GRANITE_STONE, lookupBlockMass(b, 5.2) * 4.2, 0.018, 1.4, 0.01, 0.1);
		}
		return MINERAL_SOIL;
	}

	public static Properties forSpecies(WoodSpecies species) {
		return switch (species) {
			case OAK -> OAK_WOOD;
			case BIRCH -> BIRCH_WOOD;
			case SPRUCE -> SPRUCE_WOOD;
			case DARK_OAK -> DARK_OAK_WOOD;
		};
	}

	/** Resolves physical fuel profile for an item added into a hearth/fire. */
	public static Properties forFuelItem(ItemStack stack) {
		Item item = stack.getItem();
		double mass = Math.max(0.1, safeWeight(item));
		if (item == Items.CHARCOAL || item == Items.COAL) {
			return withGeometry(CHARCOAL, mass, 0.65, 2.8, 0.02, 1.2);
		}
		if (item == PrimitiveItems.PLANT_FIBER || item == PrimitiveItems.CORDAGE
				|| item == ThermoItems.TINDER_BUNDLE || item == Items.PAPER || item == Items.DRIED_KELP) {
			return withGeometry(TINDER_FIBER, mass, 0.88, 5.0, 0.08, 2.6);
		}
		if (item == Items.STICK || item == ThermoItems.WOOD_ASH) {
			return item == ThermoItems.WOOD_ASH
					? MINERAL_SOIL
					: withGeometry(STICK_KINDLING, mass, 0.62, 3.6, 0.12, 1.9);
		}

		Block b = Block.getBlockFromItem(item);
		if (b != Blocks.AIR) {
			return withGeometry(forBlock(b.getDefaultState()), mass,
					forBlock(b.getDefaultState()).porosity(),
					forBlock(b.getDefaultState()).surfaceAreaM2(),
					forBlock(b.getDefaultState()).defaultMoistureRatio(),
					forBlock(b.getDefaultState()).moistureDiffusivity());
		}

		String path = Registries.ITEM.getId(item).getPath();
		if (path.contains("planks") || path.contains("fence") || path.contains("slab")) {
			return withGeometry(OAK_WOOD, mass, 0.40, 2.4, 0.14, 1.5);
		}
		if (path.contains("log")) {
			return withGeometry(OAK_WOOD, mass, 0.22, 1.6, 0.18, 0.8);
		}
		return MINERAL_SOIL;
	}

	/** Physical cooking profile for any edible food item. */
	public record FoodProperties(
			double massKg,
			double waterFraction,
			double thermalConductivity,
			double specificHeat,
			double characteristicRadiusM,
			double targetCoreTempK,
			double charTempK
	) {
	}

	public static FoodProperties forFood(ItemStack stack) {
		if (!stack.contains(DataComponentTypes.FOOD)) {
			return new FoodProperties(0.25, 0.55, 0.42, 3200.0, 0.025, 345.0, 465.0);
		}
		double mass = Math.max(0.12, safeWeight(stack.getItem()));
		String path = Registries.ITEM.getId(stack.getItem()).getPath();
		if (path.contains("beef") || path.contains("pork") || path.contains("mutton")) {
			return new FoodProperties(Math.max(0.45, mass), 0.72, 0.45, 3450.0, 0.036, 344.0, 470.0);
		}
		if (path.contains("chicken") || path.contains("rabbit")) {
			return new FoodProperties(Math.max(0.35, mass), 0.74, 0.44, 3500.0, 0.030, 347.0, 465.0);
		}
		if (path.contains("cod") || path.contains("salmon")) {
			return new FoodProperties(Math.max(0.25, mass), 0.78, 0.50, 3600.0, 0.020, 336.0, 450.0);
		}
		if (path.contains("potato")) {
			return new FoodProperties(Math.max(0.20, mass), 0.79, 0.56, 3650.0, 0.026, 363.0, 475.0);
		}
		return new FoodProperties(mass, 0.65, 0.46, 3350.0, 0.024, 343.0, 460.0);
	}

	private static double lookupBlockMass(Block block, double fallback) {
		Item item = block.asItem();
		if (item == Items.AIR) {
			return fallback;
		}
		return Math.max(0.25, safeWeight(item));
	}

	private static double safeWeight(Item item) {
		try {
			return WeightManager.get().table().weightOf(item);
		} catch (Throwable ignored) {
			return 1.0;
		}
	}

	private static Properties withGeometry(Properties base, double massKg, double porosity,
											 double areaM2, double defaultMoisture, double diff) {
		return new Properties(
				base.category(), massKg, base.densityKgM3(), base.specificHeat(),
				base.thermalConductivity(), porosity, base.emissivity(), areaM2,
				base.volatileFraction(), base.fixedCarbonFraction(), base.ashFraction(),
				base.pyrolysisStartK(), base.ignitionTempK(), defaultMoisture, diff);
	}
}
