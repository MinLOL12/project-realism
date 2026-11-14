package com.projectrealism.primitive;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * A knapped blade: the first cutting tool, and the thing that makes cordage possible.
 */
public class StoneKnife extends Item {
	public StoneKnife(Settings settings) {
		super(settings.maxDamage(64));
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		tooltip.add(Text.translatable("item.project_realism.stone_knife.tooltip").formatted(Formatting.GRAY));
	}

	@Override
	public boolean isEnchantable(ItemStack stack) {
		return false;
	}
}
