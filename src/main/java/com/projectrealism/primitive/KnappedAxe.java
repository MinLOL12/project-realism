package com.projectrealism.primitive;

import com.projectrealism.ProjectRealism;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ToolMaterial;

/**
 * The two crafted tools. Both extend the vanilla tool classes so that stripping logs, mining tags and
 * enchantability all behave exactly as they do for real axes and pickaxes.
 */
public final class KnappedAxe extends AxeItem {
	public KnappedAxe(Item.Settings settings) {
		super(KnappedMaterial.INSTANCE, settings.maxDamage(KnappedMaterial.INSTANCE.getDurability()));
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
			return 2.5f;
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
