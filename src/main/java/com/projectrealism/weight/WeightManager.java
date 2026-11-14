package com.projectrealism.weight;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.projectrealism.ProjectRealism;
import com.projectrealism.RealismConfig;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns the weight table and decides, every tick, how fast each player is allowed to move.
 */
public final class WeightManager {
	private static final WeightManager INSTANCE = new WeightManager();

	private final WeightTable table = new WeightTable();
	private final Map<UUID, PlayerState> states = new HashMap<>();
	private RealismConfig config = new RealismConfig();
	private boolean ready = false;

	private WeightManager() {
	}

	public static WeightManager get() {
		return INSTANCE;
	}

	public void loadWeights() {
		config = RealismConfig.load();
		table.loadBuiltIn();
		ready = true;
		ProjectRealism.LOGGER.info("Weighed {} items. Comfortable carrying capacity: {} kg.",
				table.size(), config.capacityKg);
	}

	/** Applied on every datapack reload so pack makers can retune weights without a restart. */
	public void reload(ResourceManager manager) {
		table.loadBuiltIn();

		for (String namespace : manager.getAllNamespaces()) {
			Identifier file = Identifier.of(namespace, "project_realism/weights.json");
			Optional<Resource> resource = manager.getResource(file);
			if (resource.isEmpty()) {
				continue;
			}
			try (var reader = new InputStreamReader(resource.get().getInputStream(), StandardCharsets.UTF_8)) {
				JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
				int applied = table.merge(root, file.toString());
				ProjectRealism.LOGGER.info("Applied {} weight overrides from {}.", applied, file);
			} catch (Exception e) {
				ProjectRealism.LOGGER.error("Could not read weight overrides from {}", file, e);
			}
		}

		ready = true;
	}

	public int knownWeightCount() {
		return table.size();
	}

	public WeightTable table() {
		return table;
	}

	public RealismConfig config() {
		return config;
	}

	public void setCapacity(double kg) {
		config.capacityKg = kg;
	}

	public void onJoin(ServerPlayerEntity player) {
		WeightAttributes.clear(player);
		states.put(player.getUuid(), new PlayerState());
	}

	public void onDisconnect(UUID uuid) {
		states.remove(uuid);
	}

	private InventoryWeigher.RealismNumbers numbers() {
		return new InventoryWeigher.RealismNumbers(
				config.capacityKg, config.halfLifeKg, config.curveExponent, config.minSpeedMultiplier);
	}

	/** Runs at the end of every server tick. */
	public void tick(MinecraftServer server) {
		if (!ready) {
			return;
		}

		InventoryWeigher.RealismNumbers numbers = numbers();
		int interval = Math.max(1, config.hudIntervalTicks);

		for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
			PlayerState state = states.computeIfAbsent(player.getUuid(), k -> new PlayerState());
			state.ticks++;

			WeightReport report = InventoryWeigher.weigh(player, table, numbers);
			state.lastReport = report;

			if (player.isCreative() && !config.affectCreative) {
				WeightAttributes.clear(player);
			} else {
				WeightAttributes.apply(player, report.speedMultiplier());
			}

			if (config.overloadExhaustion && report.overloaded() && canGetHungry(player)) {
				float exhaustion = Encumbrance.exhaustionPerTick(
						report.totalKg(),
						config.capacityKg,
						config.exhaustionPerKgPerSecond,
						player.getVelocity().horizontalLengthSquared() > 1.0e-4);
				if (exhaustion > 0.0f) {
					player.addExhaustion(exhaustion);
				}
			}

			announceSeverity(player, state, report);

			// Vanilla clients never registered the channel; sending anyway would spam their logs.
			if (state.ticks % interval == 0 && hasOurClient(player)) {
				boolean showHud = config.weightHud && (!player.isCreative() || config.hudInCreative);
				ServerPlayNetworking.send(player, new WeightPayload(
						(float) report.totalKg(),
						(float) report.capacityKg(),
						(float) report.speedMultiplier(),
						showHud));
			}
		}
	}

	/** True when the client on the other end is running Project Realism (so it has the HUD). */
	private static boolean hasOurClient(ServerPlayerEntity player) {
		try {
			return ServerPlayNetworking.canSend(player, WeightPayload.ID);
		} catch (IllegalArgumentException e) {
			return false;
		}
	}

	private static boolean canGetHungry(ServerPlayerEntity player) {
		return !player.isCreative() && !player.isSpectator();
	}

	/**
	 * Chat feedback is deliberately sparse: one line when a player tips into a worse band. A warning
	 * that repeats every tick is a warning nobody reads.
	 */
	private void announceSeverity(ServerPlayerEntity player, PlayerState state, WeightReport report) {
		Encumbrance.Severity severity = report.severity();
		if (severity.ordinal() <= state.lastAnnouncedSeverity.ordinal()) {
			state.lastAnnouncedSeverity = severity;
			return;
		}

		String key = switch (severity) {
			case COMFORTABLE -> "project_realism.weight.free";
			case LOADED -> "project_realism.weight.loaded";
			case STRAINED -> "project_realism.weight.strained";
			case CRUSHED -> "project_realism.weight.crushed";
		};

		player.sendMessage(Text.translatable(key).formatted(Formatting.GOLD), true);
		state.lastAnnouncedSeverity = severity;
	}

	public WeightReport reportFor(ServerPlayerEntity player) {
		PlayerState state = states.get(player.getUuid());
		if (state != null && state.lastReport != null) {
			return state.lastReport;
		}
		return InventoryWeigher.weigh(player, table, numbers());
	}

	private static final class PlayerState {
		long ticks = 0;
		WeightReport lastReport = null;
		Encumbrance.Severity lastAnnouncedSeverity = Encumbrance.Severity.COMFORTABLE;
	}
}
