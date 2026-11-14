package com.projectrealism;

import com.projectrealism.command.WeightCommand;
import com.projectrealism.weight.WeightAttributes;
import com.projectrealism.weight.WeightManager;
import com.projectrealism.weight.WeightPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Project Realism — an unforgiving survival overhaul.
 *
 * <p>Stage one: block weight. Every item the player carries has a mass in kilograms. Hauling more
 * than the body can comfortably support (30 kg) slows the player down, progressively and without a
 * cliff edge, until at absurd loads they can barely stagger forward.</p>
 */
public class ProjectRealism implements ModInitializer {
	public static final String MOD_ID = "project_realism";
	public static final Logger LOGGER = LoggerFactory.getLogger("Project Realism");

	public static Identifier id(String path) {
		return Identifier.of(MOD_ID, path);
	}

	@Override
	public void onInitialize() {
		WeightAttributes.register();
		WeightManager.get().loadWeights();

		PayloadTypeRegistry.playS2C().register(WeightPayload.ID, WeightPayload.CODEC);

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
				WeightCommand.register(dispatcher, registryAccess));

		// Recompute weight every tick: inventory edits, potion effects and elytra flight all move the
		// needle constantly, so a cached value would lie.
		ServerTickEvents.END_SERVER_TICK.register(server -> WeightManager.get().tick(server));

		// Logging in must never inherit a stale modifier from a previous session.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
				WeightManager.get().onJoin(handler.getPlayer()));
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
				WeightManager.get().onDisconnect(handler.getPlayer().getUuid()));

		// Pack authors can drop JSON weight overrides into a datapack and /reload without a restart.
		ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(
				new SimpleSynchronousResourceReloadListener() {
					@Override
					public Identifier getFabricId() {
						return id("weights");
					}

					@Override
					public void reload(ResourceManager manager) {
						WeightManager.get().reload(manager);
						LOGGER.info("Reloaded weight data: {} item weights active.",
								WeightManager.get().knownWeightCount());
					}
				});

		if (FabricLoader.getInstance().isDevelopmentEnvironment()) {
			LOGGER.info("Project Realism loaded in a dev environment. Bring water.");
		}
	}
}
