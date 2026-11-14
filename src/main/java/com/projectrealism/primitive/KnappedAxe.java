package com.projectrealism.primitive;

import com.projectrealism.ProjectRealism;
import com.projectrealism.wood.WoodSpecies;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ToolMaterial;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;

/**
 * A hand-lashed stone axe. Loses sharpness as it cuts, slows down when dull, and wears its binding.
 */
public final class KnappedAxe extends AxeItem {
	public KnappedAxe(Item.Settings settings) {
		super(KnappedMaterial.INSTANCE, settings.maxDamage(KnappedMaterial.INSTANCE.getDurability()));
	}

	@Override
	public float getMiningSpeed(ItemStack stack, BlockState state) {
		float base = super.getMiningSpeed(stack, state);
		float woodFactor = WoodSpecies.fromBlock(state.getBlock())
				.map(sp -> 1.0f / sp.hardness())
				.orElse(1.0f);
		return base * ToolCondition.speedMultiplier(stack) * Math.max(0.55f, Math.min(1.35f, woodFactor));
	}

	@Override
	public boolean postMine(ItemStack stack, World world, BlockState state, BlockPos pos, LivingEntity miner) {
		boolean mined = super.postMine(stack, world, state, pos, miner);
		if (world instanceof ServerWorld server && miner instanceof PlayerEntity player) {
			float hardness = WoodSpecies.fromBlock(state.getBlock()).map(WoodSpecies::hardness).orElse(1.0f);
			ToolCondition.applyWear(player, Hand.MAIN_HAND, stack, hardness, server, false);
		}
		return mined;
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		super.appendTooltip(stack, context, tooltip, type);
		ToolCondition.appendToolTooltip(stack, tooltip);
	}

	@Override
	public boolean isEnchantable(ItemStack stack) {
		return false;
	}

	@Override
	public String getTranslationKey() {
		return "item.project_realism.stone_axe";
	}

	/** The material is its own type so it can be referenced without an enum. */
	private static final class KnappedMaterial implements ToolMaterial {
		static final KnappedMaterial INSTANCE = new KnappedMaterial();

		@Override
		public int getDurability() {
			return 64;
		}

		@Override
		public float getMiningSpeedMultiplier() {
			return 2.6f;
		}

		@Override
		public float getAttackDamage() {
			return 1.0f;
		}

		@Override
		public int getEnchantability() {
			return 2;
		}

		@Override
		public net.minecraft.recipe.Ingredient getRepairIngredient() {
			return net.minecraft.recipe.Ingredient.ofItems(PrimitiveItems.STONE_AXE_HEAD);
		}

		@Override
		public net.minecraft.registry.tag.TagKey<net.minecraft.block.Block> getInverseTag() {
			return net.minecraft.registry.tag.BlockTags.INCORRECT_FOR_WOODEN_TOOL;
		}
	}

	static {
		ProjectRealism.LOGGER.debug("Knapped axe registered.");
	}
}
