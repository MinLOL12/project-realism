package com.projectrealism.thermo;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * Thermal cooking state stored on edible food items via {@link DataComponentTypes#CUSTOM_DATA}.
 *
 * <p>Heat penetrates from food surface to core over time based on mass, geometry, water content,
 * conductivity, and heat transfer method (roasting, boiling, baking, hot stone, steaming). Tracks
 * pasteurization thermal dose for future microbiology integration.</p>
 */
public final class CookingPhysics {
	private static final String KEY_SURF_T = "FoodSurfK";
	private static final String KEY_CORE_T = "FoodCoreK";
	private static final String KEY_WATER = "FoodWater";
	private static final String KEY_COOKED = "FoodCooked";
	private static final String KEY_CHARRED = "FoodCharred";
	private static final String KEY_PAST_DOSE = "FoodPastDose";
	private static final String KEY_METHOD = "FoodMethod";

	private CookingPhysics() {
	}

	public static boolean isCookableRaw(Item item) {
		return cookedVariantFor(item) != null;
	}

	public static Item cookedVariantFor(Item raw) {
		if (raw == Items.BEEF) return Items.COOKED_BEEF;
		if (raw == Items.PORKCHOP) return Items.COOKED_PORKCHOP;
		if (raw == Items.MUTTON) return Items.COOKED_MUTTON;
		if (raw == Items.CHICKEN) return Items.COOKED_CHICKEN;
		if (raw == Items.RABBIT) return Items.COOKED_RABBIT;
		if (raw == Items.COD) return Items.COOKED_COD;
		if (raw == Items.SALMON) return Items.COOKED_SALMON;
		if (raw == Items.POTATO) return Items.BAKED_POTATO;
		if (raw == Items.KELP) return Items.DRIED_KELP;
		return null;
	}

	public static boolean isFoodTrackable(ItemStack stack) {
		return !stack.isEmpty() && stack.contains(DataComponentTypes.FOOD);
	}

	public static ThermalMath.FoodThermalState readState(ItemStack stack) {
		MaterialProfile.FoodProperties fp = MaterialProfile.forFood(stack);
		NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (comp == null) {
			boolean alreadyCooked = isAlreadyCookedVanilla(stack.getItem());
			ThermalMath.FoodThermalState st = new ThermalMath.FoodThermalState(
					ThermalConstants.AMBIENT_DEFAULT_K, fp.waterFraction());
			if (alreadyCooked) {
				st.cookedProgress = 1.0;
				st.pasteurizationDose = 12.0;
			}
			return st;
		}
		NbtCompound nbt = comp.copyNbt();
		if (!nbt.contains(KEY_CORE_T)) {
			ThermalMath.FoodThermalState st = new ThermalMath.FoodThermalState(
					ThermalConstants.AMBIENT_DEFAULT_K, fp.waterFraction());
			if (isAlreadyCookedVanilla(stack.getItem())) {
				st.cookedProgress = 1.0;
				st.pasteurizationDose = 12.0;
			}
			return st;
		}
		ThermalMath.FoodThermalState st = new ThermalMath.FoodThermalState(
				nbt.getDouble(KEY_SURF_T), nbt.getDouble(KEY_WATER));
		st.coreTempK = nbt.getDouble(KEY_CORE_T);
		st.cookedProgress = nbt.getDouble(KEY_COOKED);
		st.charredProgress = nbt.getDouble(KEY_CHARRED);
		st.pasteurizationDose = nbt.getDouble(KEY_PAST_DOSE);
		return st;
	}

	public static void writeState(ItemStack stack, ThermalMath.FoodThermalState st,
								  ThermalMath.CookingMethod method) {
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, nbt -> {
			nbt.putDouble(KEY_SURF_T, st.surfaceTempK);
			nbt.putDouble(KEY_CORE_T, st.coreTempK);
			nbt.putDouble(KEY_WATER, st.waterRemainingFrac);
			nbt.putDouble(KEY_COOKED, st.cookedProgress);
			nbt.putDouble(KEY_CHARRED, st.charredProgress);
			nbt.putDouble(KEY_PAST_DOSE, st.pasteurizationDose);
			if (method != null) {
				nbt.putString(KEY_METHOD, method.name());
			}
		});
	}

	/**
	 * Advances food heating by {@code dtSeconds} and returns the updated (possibly transformed to
	 * cooked or charred) {@link ItemStack}.
	 */
	public static ItemStack stepCooking(ItemStack stack, ThermalMath.CookingMethod method,
										double sourceTempK, double airSpeedMS, double dtSeconds) {
		if (stack.isEmpty() || !isFoodTrackable(stack)) {
			return stack;
		}
		MaterialProfile.FoodProperties fp = MaterialProfile.forFood(stack);
		ThermalMath.FoodThermalState st = readState(stack);
		ThermalMath.stepFood(st, fp, method, sourceTempK, airSpeedMS, dtSeconds);

		if (st.charredProgress >= 0.90) {
			return new ItemStack(Items.CHARCOAL, 1);
		}

		ItemStack result = stack;
		Item cookedItem = cookedVariantFor(stack.getItem());
		if (cookedItem != null && st.cookedProgress >= 1.0) {
			ItemStack cooked = new ItemStack(cookedItem, stack.getCount());
			writeState(cooked, st, method);
			result = cooked;
		} else {
			writeState(result, st, method);
		}
		return result;
	}

	public static void appendFoodTooltip(ItemStack stack, List<Text> tooltip) {
		if (!isFoodTrackable(stack)) {
			return;
		}
		NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (comp == null || !comp.copyNbt().contains(KEY_CORE_T)) {
			return;
		}
		NbtCompound nbt = comp.copyNbt();
		int surfC = (int) Math.round(ThermalConstants.toCelsius(nbt.getDouble(KEY_SURF_T)));
		int coreC = (int) Math.round(ThermalConstants.toCelsius(nbt.getDouble(KEY_CORE_T)));
		int cookedPct = (int) Math.round(nbt.getDouble(KEY_COOKED) * 100.0);
		int charPct = (int) Math.round(nbt.getDouble(KEY_CHARRED) * 100.0);
		double past = nbt.getDouble(KEY_PAST_DOSE);

		Formatting coreColor = coreC >= 68 ? Formatting.GOLD : (coreC >= 40 ? Formatting.YELLOW : Formatting.AQUA);
		tooltip.add(Text.translatable("project_realism.tooltip.food_temps", surfC, coreC).formatted(coreColor));
		tooltip.add(Text.translatable("project_realism.tooltip.food_progress", cookedPct, charPct)
				.formatted(charPct > 45 ? Formatting.RED : (cookedPct >= 100 ? Formatting.GREEN : Formatting.GRAY)));
		if (past >= 5.0) {
			tooltip.add(Text.translatable("project_realism.tooltip.food_pasteurized").formatted(Formatting.GREEN));
		} else if (cookedPct > 0) {
			tooltip.add(Text.translatable("project_realism.tooltip.food_undercooked").formatted(Formatting.YELLOW));
		}
		if (nbt.contains(KEY_METHOD)) {
			tooltip.add(Text.translatable("project_realism.tooltip.food_method", nbt.getString(KEY_METHOD))
					.formatted(Formatting.DARK_GRAY));
		}
	}

	private static boolean isAlreadyCookedVanilla(Item item) {
		return item == Items.COOKED_BEEF || item == Items.COOKED_PORKCHOP
				|| item == Items.COOKED_MUTTON || item == Items.COOKED_CHICKEN
				|| item == Items.COOKED_RABBIT || item == Items.COOKED_COD
				|| item == Items.COOKED_SALMON || item == Items.BAKED_POTATO
				|| item == Items.DRIED_KELP || item == Items.BREAD;
	}
}
