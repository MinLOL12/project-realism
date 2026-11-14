package com.projectrealism.recipe;

import com.projectrealism.ProjectRealism;
import net.minecraft.recipe.RecipeSerializer;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

/**
 * Registers custom crafting recipe serializers for Project Realism.
 */
public final class ModRecipes {
	public static final RecipeSerializer<ToolCraftingRecipe> TOOL_CRAFTING_SERIALIZER = Registry.register(
			Registries.RECIPE_SERIALIZER,
			ProjectRealism.id("tool_crafting"),
			new ToolCraftingRecipe.Serializer()
	);

	private ModRecipes() {
	}

	public static void register() {
		ProjectRealism.LOGGER.info("Custom recipe serializers registered.");
	}
}
