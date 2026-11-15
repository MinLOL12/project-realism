package com.projectrealism.anatomy;

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
 * Physical raw or partially shaped bone retaining species anatomy, length, diameter, mineral
 * density, moisture, thermal brittleness, and carving/abrasion workmanship.
 */
public class RawBoneItem extends Item {
	public RawBoneItem(Settings settings) {
		super(settings.maxCount(1));
	}

	public static BoneData readBone(ItemStack stack) {
		NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (comp != null) {
			NbtCompound root = comp.copyNbt();
			if (root.contains("Bone")) {
				return BoneData.fromNbt(root.getCompound("Bone"));
			}
		}
		return new BoneData(AnimalSpecies.COW, BoneData.BoneType.LONG_SHAFT, 0.85);
	}

	public static void writeBone(ItemStack stack, BoneData b) {
		if (stack.isEmpty() || b == null) return;
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, root -> root.put("Bone", b.toNbt()));
	}

	public static ItemStack create(BoneData b) {
		ItemStack st = new ItemStack(AnatomyItems.RAW_BONE);
		writeBone(st, b);
		return st;
	}

	public static double massKg(ItemStack stack) {
		return readBone(stack).massKg();
	}

	@Override
	public boolean isItemBarVisible(ItemStack stack) {
		BoneData b = readBone(stack);
		return b.cutProgress > 0.01 || b.abrasionSmooth > 0.01;
	}

	@Override
	public int getItemBarStep(ItemStack stack) {
		BoneData b = readBone(stack);
		double p = 0.5 * b.cutProgress + 0.5 * b.abrasionSmooth;
		return Math.max(1, Math.min(13, (int) Math.round(p * 13.0)));
	}

	@Override
	public int getItemBarColor(ItemStack stack) {
		BoneData b = readBone(stack);
		if (b.workDamage > 0.35 || b.brittleness > 0.50) return 0xD45B3E;
		return 0xEAE4D3;
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		super.appendTooltip(stack, context, tooltip, type);
		BoneData b = readBone(stack);
		tooltip.add(Text.translatable("project_realism.tooltip.bone_dims",
				b.speciesId, b.size.id,
				String.format("%.1f", b.lengthCm),
				String.format("%.1f", b.diameterMm),
				String.format("%.2f", b.massKg()))
				.formatted(Formatting.GOLD));
		tooltip.add(Text.translatable("project_realism.tooltip.bone_props",
				(int) Math.round(b.density * 100.0),
				(int) Math.round(b.moisture * 100.0),
				(int) Math.round(b.brittleness * 100.0))
				.formatted(b.brittleness > 0.45 ? Formatting.RED : Formatting.GRAY));
		if (b.cutProgress > 0.01 || b.abrasionSmooth > 0.01) {
			tooltip.add(Text.translatable("project_realism.tooltip.bone_work",
					b.target.id,
					(int) Math.round(b.cutProgress * 100.0),
					(int) Math.round(b.abrasionSmooth * 100.0),
					b.resultingToolIntegrityPct())
					.formatted(Formatting.AQUA));
		} else {
			tooltip.add(Text.translatable("project_realism.tooltip.bone_hint").formatted(Formatting.DARK_GRAY));
		}
	}
}
