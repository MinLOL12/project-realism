package com.projectrealism.client;

import com.projectrealism.weight.WeightPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;

@Environment(EnvType.CLIENT)
public class ProjectRealismClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		ClientPlayNetworking.registerGlobalReceiver(WeightPayload.ID, (payload, context) ->
				context.client().execute(() -> WeightHud.update(payload)));

		HudRenderCallback.EVENT.register(new WeightHud());
	}
}
