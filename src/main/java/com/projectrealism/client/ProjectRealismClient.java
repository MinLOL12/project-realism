package com.projectrealism.client;

import com.projectrealism.ProjectRealism;
import com.projectrealism.hydration.HydrationManager;
import com.projectrealism.hydration.HydrationPayload;
import com.projectrealism.hydration.WaterQuality;
import com.projectrealism.material.MaterialItems;
import com.projectrealism.primitive.PrimitiveItems;
import com.projectrealism.primitive.ToolCondition;
import com.projectrealism.thermo.CookingPhysics;
import com.projectrealism.weight.WeightPayload;
import com.projectrealism.wood.WoodItems;
import com.projectrealism.wood.WoodSpecies;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.item.ModelPredicateProviderRegistry;
import net.minecraft.item.Item;
import net.minecraft.util.Identifier;

@Environment(EnvType.CLIENT)
public class ProjectRealismClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(WeightPayload.ID, (payload, context) ->
				context.client().execute(() -> WeightHud.update(payload)));
		ClientPlayNetworking.registerGlobalReceiver(HydrationPayload.ID, (payload, context) ->
				context.client().execute(() -> HydrationHud.update(payload)));

		HudRenderCallback.EVENT.register(new WeightHud());
		HudRenderCallback.EVENT.register(new HydrationHud());
		MaterialBlockRenderers.register();
		AnatomyBlockRenderers.register();
		KnappingBlockRenderers.register();
		MetallurgyBlockRenderers.register();

		Identifier dullId = ProjectRealism.id("dull");
		for (Item tool : new Item[]{
				PrimitiveItems.STONE_KNIFE, PrimitiveItems.STONE_AXE, PrimitiveItems.STONE_PICKAXE}) {
			ModelPredicateProviderRegistry.register(tool, dullId,
					(stack, world, entity, seed) -> ToolCondition.isDull(stack) ? 1.0f : 0.0f);
		}
		ModelPredicateProviderRegistry.register(MaterialItems.STONE_SCRAPER, dullId,
				(stack, world, entity, seed) -> MaterialItems.StoneScraperItem.getSharpness(stack) < 35 ? 1.0f : 0.0f);

		Identifier dryId = ProjectRealism.id("dry");
		for (WoodSpecies sp : WoodSpecies.values()) {
			ModelPredicateProviderRegistry.register(WoodItems.splitWood(sp), dryId,
					(stack, world, entity, seed) -> ToolCondition.isDry(stack) ? 1.0f : 0.0f);
			ModelPredicateProviderRegistry.register(WoodItems.roughPlank(sp), dryId,
					(stack, world, entity, seed) -> ToolCondition.isDry(stack) ? 1.0f : 0.0f);
		}

		ItemTooltipCallback.EVENT.register((stack, tooltipContext, tooltipType, lines) -> {
			CookingPhysics.appendFoodTooltip(stack, lines);
			if (HydrationManager.isWaterBottle(stack)) {
				WaterQuality.appendTooltip(stack, lines);
			}
		});
	}
}
