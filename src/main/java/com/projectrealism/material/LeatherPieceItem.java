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
 * Cut panel of prepared leather extracted from a hide along a real blade cut path, carrying
 * averaged anatomical thickness, edge cut regularity, cleanliness, damage, moisture, and stiffness.
 */
public class LeatherPieceItem extends Item {
	public LeatherPieceItem(Settings settings) {
		super(settings.maxCount(16));
	}

	public static void writeProperties(ItemStack stack, HideSurface.CutPieceProperties p) {
		if (stack.isEmpty() || p == null) return;
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, nbt -> {
			NbtCompound t = new NbtCompound();
			t.putString("Animal", p.animal.id);
			t.putDouble("Area", p.areaCm2);
			t.putDouble("Thick", p.thickness);
			t.putDouble("Clean", p.cleanliness);
			t.putDouble("Edge", p.edgeRegularity);
			t.putDouble("Dmg", p.damage);
			t.putDouble("Moist", p.moisture);
			t.putDouble("Stiff", p.stiffness);
			t.putDouble("Scorch", p.scorch);
			nbt.put("Piece", t);
		});
	}

	public static HideSurface.CutPieceProperties readProperties(ItemStack stack) {
		NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (comp != null) {
			NbtCompound root = comp.copyNbt();
			if (root.contains("Piece")) {
				NbtCompound t = root.getCompound("Piece");
				return new HideSurface.CutPieceProperties(
						AnimalHideType.fromId(t.getString("Animal")),
						t.contains("Area") ? t.getDouble("Area") : 1400.0,
						t.contains("Thick") ? t.getDouble("Thick") : 0.72,
						t.contains("Clean") ? t.getDouble("Clean") : 0.9,
						t.contains("Edge") ? t.getDouble("Edge") : 0.85,
						t.getDouble("Dmg"),
						t.contains("Moist") ? t.getDouble("Moist") : 0.14,
						t.contains("Stiff") ? t.getDouble("Stiff") : 0.25,
						t.getDouble("Scorch")
				);
			}
		}
		return new HideSurface.CutPieceProperties(AnimalHideType.COW, 1400.0, 0.75, 0.9, 0.85, 0.0, 0.14, 0.25, 0.0);
	}

	public static ItemStack create(HideSurface.CutPieceProperties p) {
		ItemStack stack = new ItemStack(MaterialItems.LEATHER_PIECE);
		writeProperties(stack, p);
		return stack;
	}

	public static double massKg(ItemStack stack) {
		HideSurface.CutPieceProperties p = readProperties(stack);
		double dry = Math.max(0.08, (p.areaCm2 / 15000.0) * p.animal.dryMassKg * (0.6 + 0.4 * p.thickness));
		return dry * (1.0 + p.moisture * 0.95);
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		super.appendTooltip(stack, context, tooltip, type);
		HideSurface.CutPieceProperties p = readProperties(stack);
		tooltip.add(Text.translatable("project_realism.tooltip.piece_dims",
				p.animal.id, (int) Math.round(p.areaCm2), String.format("%.2f", p.thickness))
				.formatted(Formatting.GOLD));
		tooltip.add(Text.translatable("project_realism.tooltip.piece_quality",
				(int) Math.round(p.edgeRegularity * 100.0),
				(int) Math.round(p.cleanliness * 100.0),
				(int) Math.round(p.damage * 100.0))
				.formatted(p.edgeRegularity >= 0.75 ? Formatting.GREEN : Formatting.YELLOW));
	}
}
