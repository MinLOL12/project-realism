package com.projectrealism.primitive;

import com.projectrealism.ProjectRealism;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.PickaxeItem;
import net.minecraft.item.ToolMaterial;
import net.minecraft.recipe.Ingredient;
import net.minecraft.registry.tag.BlockTags;

/**
 * A stone pickaxe that is genuinely stone tier: it mines stone, and it is still refused by iron.
 */
public final class StonePickaxe extends PickaxeItem {
	public StonePickaxe(Item.Settings settings) {
		super(Material.INSTANCE, settings.maxDamage(Material.INSTANCE.getDurability()));
	}

	@Override
	public boolean isEnchantable(ItemStack stack) {
		return false;
	}

	private static final class Material implements ToolMaterial {
		static final Material INSTANCE = new Material();

		@Override
		public int getDurability() {
			return 96;
		}

		@Override
		public float getMiningSpeedMultiplier() {
			return 3.0f;
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
		public Ingredient getRepairIngredient() {
			return Ingredient.ofItems(PrimitiveItems.GRANITE_ROCK, PrimitiveItems.LIMESTONE_ROCK,
					PrimitiveItems.FLINT_ROCK);
		}

		@Override
		public net.minecraft.registry.tag.TagKey<net.minecraft.block.Block> getInverseTag() {
			// Stone tier: correct for stone and everything a wooden pickaxe can mine.
			return BlockTags.INCORRECT_FOR_STONE_TOOL;
		}
	}

	static {
		ProjectRealism.LOGGER.debug("Stone pickaxe registered.");
	}
}
