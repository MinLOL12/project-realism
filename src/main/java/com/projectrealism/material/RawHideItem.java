package com.projectrealism.material;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * Physical animal hide carrying a persistent 8x8 anatomical surface grid (thickness, cleanliness,
 * damage holes, moisture, stiffness, contraction, tension deformation, and fat waste).
 */
public class RawHideItem extends Item {
	public RawHideItem(Settings settings) {
		super(settings.maxCount(1));
	}

	public static HideSurface readSurface(ItemStack stack) {
		NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (comp != null) {
			NbtCompound nbt = comp.copyNbt();
			if (nbt.contains("HideSurf")) {
				return HideSurface.fromNbt(nbt.getCompound("HideSurf"));
			}
		}
		return new HideSurface(AnimalHideType.COW);
	}

	public static void writeSurface(ItemStack stack, HideSurface surface) {
		if (stack.isEmpty() || surface == null) return;
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, nbt -> nbt.put("HideSurf", surface.toNbt()));
	}

	public static ItemStack create(HideSurface surface) {
		ItemStack stack = new ItemStack(MaterialItems.RAW_HIDE);
		writeSurface(stack, surface);
		return stack;
	}

	@Override
	public boolean isItemBarVisible(ItemStack stack) {
		return true;
	}

	@Override
	public int getItemBarStep(ItemStack stack) {
		HideSurface s = readSurface(stack);
		double usable = Math.max(0.0, (s.intactCount() / (double) HideSurface.CELLS) * (1.0 - s.averageDamage() * 0.6));
		return Math.max(1, Math.min(13, (int) Math.round(usable * 13.0)));
	}

	@Override
	public int getItemBarColor(ItemStack stack) {
		HideSurface s = readSurface(stack);
		double clean = s.averageCleanliness();
		double moist = s.averageMoisture();
		if (s.averageDamage() > 0.45) return 0xCC4433;
		if (clean < 0.70) return 0xA84C3C;      // raw bloody flesh
		if (moist > 0.35) return 0x6B4829;      // dark wet hide
		return 0xC99862;                        // cured clean tan hide
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		super.appendTooltip(stack, context, tooltip, type);
		HideSurface s = readSurface(stack);
		tooltip.add(Text.translatable("project_realism.tooltip.hide_animal",
				s.animal.id, (int) Math.round(s.usableAreaCm2()), String.format("%.2f", s.currentMassKg()))
				.formatted(Formatting.GOLD));
		tooltip.add(Text.translatable("project_realism.tooltip.hide_state",
				(int) Math.round(s.averageCleanliness() * 100.0),
				(int) Math.round(s.averageMoisture() * 100.0),
				(int) Math.round(s.averageDamage() * 100.0))
				.formatted(s.averageCleanliness() >= 0.80 && s.averageMoisture() < 0.25 ? Formatting.GREEN : Formatting.GRAY));
		if (s.unevenDeformation > 0.15) {
			tooltip.add(Text.translatable("project_realism.tooltip.hide_warped",
					(int) Math.round(s.unevenDeformation * 100.0)).formatted(Formatting.YELLOW));
		}
	}
}
