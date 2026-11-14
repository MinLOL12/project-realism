package com.projectrealism.primitive;

import net.minecraft.block.BlockState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;

/**
 * A knapped blade: the first cutting tool, used for fiber, log shaping, and planing rough planks.
 */
public class StoneKnife extends Item {
	public StoneKnife(Settings settings) {
		super(settings.maxDamage(64));
	}

	@Override
	public boolean postMine(ItemStack stack, World world, BlockState state, BlockPos pos, LivingEntity miner) {
		if (world instanceof ServerWorld server && miner instanceof PlayerEntity player) {
			stack.damage(1, player, EquipmentSlot.MAINHAND);
			ToolCondition.applyWear(player, Hand.MAIN_HAND, stack, 0.7f, server, false);
		}
		return true;
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		tooltip.add(Text.translatable("item.project_realism.stone_knife.tooltip").formatted(Formatting.GRAY));
		ToolCondition.appendToolTooltip(stack, tooltip);
	}

	@Override
	public boolean isEnchantable(ItemStack stack) {
		return false;
	}
}
