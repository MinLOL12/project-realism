package com.projectrealism.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.projectrealism.ProjectRealism;
import com.projectrealism.hydration.BodyFluidMath;
import com.projectrealism.hydration.HydrationManager;
import com.projectrealism.hydration.PhysiologicalConstants;
import com.projectrealism.thermo.AirGrid;
import com.projectrealism.thermo.ThermalConstants;
import com.projectrealism.thermo.ThermalWorldState;
import com.projectrealism.weight.WeightManager;
import com.projectrealism.weight.WeightReport;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.List;

/**
 * {@code /realism} — the debugging and inspection surface for the weight system.
 */
public final class WeightCommand {
	private WeightCommand() {
	}

	public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
								CommandRegistryAccess registryAccess) {
		LiteralArgumentBuilder<ServerCommandSource> root = CommandManager.literal("realism")
				.requires(source -> source.hasPermissionLevel(0));

		root.then(CommandManager.literal("weight")
				.executes(context -> showSelf(context))
				.then(CommandManager.argument("target", EntityArgumentType.player())
						.requires(source -> source.hasPermissionLevel(2))
						.executes(context -> showOther(context, EntityArgumentType.getPlayer(context, "target")))));

		root.then(CommandManager.literal("item")
				.requires(source -> source.hasPermissionLevel(0))
				.then(CommandManager.argument("item", ItemStackArgumentType.itemStack(registryAccess))
						.executes(WeightCommand::showItem)));

		root.then(CommandManager.literal("capacity")
				.requires(source -> source.hasPermissionLevel(4))
				.then(CommandManager.argument("kilograms", DoubleArgumentType.doubleArg(1.0, 500.0))
						.executes(WeightCommand::setCapacity)));

		root.then(CommandManager.literal("reload")
				.requires(source -> source.hasPermissionLevel(4))
				.executes(WeightCommand::reload));

		root.then(CommandManager.literal("thermo")
				.requires(source -> source.hasPermissionLevel(0))
				.executes(WeightCommand::showThermo));

		root.then(CommandManager.literal("hydration")
				.requires(source -> source.hasPermissionLevel(0))
				.executes(WeightCommand::showHydration));

		root.then(CommandManager.literal("void")
				.requires(source -> source.hasPermissionLevel(0))
				.executes(WeightCommand::voidBladder));

		dispatcher.register(root);
	}

	private static int showSelf(CommandContext<ServerCommandSource> context) {
		return show(context.getSource(), context.getSource().getPlayer());
	}

	private static int showOther(CommandContext<ServerCommandSource> context, ServerPlayerEntity target) {
		return show(context.getSource(), target);
	}

	private static int show(ServerCommandSource source, ServerPlayerEntity player) {
		if (player == null) {
			source.sendError(Text.literal("Only a player can be weighed. Pass a target from the console."));
			return 0;
		}

		WeightReport report = WeightManager.get().reportFor(player);
		source.sendFeedback(() -> Text.literal("— " + player.getName().getString() + " —").formatted(Formatting.GOLD), false);
		source.sendFeedback(() -> Text.literal(String.format("Carrying %.1f kg of %.0f kg free capacity.",
				report.totalKg(), report.capacityKg())).formatted(Formatting.WHITE), false);

		if (report.overloaded()) {
			source.sendFeedback(() -> Text.literal(String.format(
					"Overloaded by %.1f kg — moving at %.0f%% of normal speed.",
					report.overloadKg(), report.speedPercent())).formatted(Formatting.RED), false);
		} else {
			Text text = Text.literal("Within capacity — no speed penalty.")
					.formatted(Formatting.GREEN);
			source.sendFeedback(() -> text, false);
		}

		List<WeightReport.Entry> contributions = report.contributions();
		if (contributions.isEmpty()) {
			source.sendFeedback(() -> Text.literal("  (carrying nothing)").formatted(Formatting.DARK_GRAY), false);
			return 1;
		}

		for (WeightReport.Entry entry : contributions.subList(0, Math.min(8, contributions.size()))) {
			Text line = Text.literal(String.format("  %s x%d — %.1f kg",
					entry.displayName(), entry.count(), entry.totalKg())).formatted(Formatting.GRAY);
			source.sendFeedback(() -> line, false);
		}
		if (contributions.size() > 8) {
			int more = contributions.size() - 8;
			source.sendFeedback(() -> Text.literal("  …and " + more + " more item types.")
					.formatted(Formatting.DARK_GRAY), false);
		}
		return 1;
	}

	private static int showItem(CommandContext<ServerCommandSource> context) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
		ItemStack stack = ItemStackArgumentType.getItemStackArgument(context, "item").createStack(1, false);
		double kg = WeightManager.get().table().weightOf(stack.getItem());
		Identifier id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem());
		int stackSize = stack.getMaxCount();
		double stackKg = kg * stackSize;

		Text text = Text.literal(String.format("%s weighs %.2f kg  (full stack of %d: %.1f kg)",
				id, kg, stackSize, stackKg)).formatted(Formatting.AQUA);
		context.getSource().sendFeedback(() -> text, false);
		return 1;
	}

	private static int setCapacity(CommandContext<ServerCommandSource> context) {
		double kg = DoubleArgumentType.getDouble(context, "kilograms");
		WeightManager.get().setCapacity(kg);
		context.getSource().sendFeedback(() -> Text.literal(String.format(
				"Comfortable carrying capacity is now %.0f kg. (Session only — edit config to persist.)", kg))
				.formatted(Formatting.YELLOW), true);
		return 1;
	}

	/**
	 * Re-reads weights from the datapacks already loaded on this server. Use vanilla {@code /reload}
	 * instead when a pack itself changed — that path also refreshes the resource manager.
	 */
	private static int reload(CommandContext<ServerCommandSource> context) {
		var server = context.getSource().getServer();
		WeightManager.get().reload(server.getResourceManager());
		ProjectRealism.LOGGER.info("Weight data reload requested by {}.", context.getSource().getName());
		context.getSource().sendFeedback(() -> Text.literal(
				"Reloaded " + WeightManager.get().knownWeightCount() + " item weights.").formatted(Formatting.GRAY), true);
		return 1;
	}

	private static int showThermo(CommandContext<ServerCommandSource> context) {
		ServerPlayerEntity player = context.getSource().getPlayer();
		if (player == null) {
			context.getSource().sendError(Text.literal("Run /realism thermo as a player."));
			return 0;
		}
		ServerWorld world = player.getServerWorld();
		ThermalWorldState thermo = ThermalWorldState.get(world);
		BlockPos p = player.getBlockPos();
		double ambK = thermo.ambientTempAt(world, p);
		double rh = thermo.relativeHumidityAt(world, p);
		AirGrid.AirCell air = thermo.airGrid().getReadOnly(AirGrid.CellPos.fromBlock(p.up()), ambK);

		context.getSource().sendFeedback(() -> Text.literal(String.format(
				"Environment: %.1f°C | RH %.0f%% | Active Thermal Nodes: %d | Air Cells: %d",
				ThermalConstants.toCelsius(ambK), rh * 100.0,
				thermo.nodes().size(), thermo.airGrid().cells().size())).formatted(Formatting.GOLD), false);
		context.getSource().sendFeedback(() -> Text.literal(String.format(
				"Local Air: %.1f°C | Wind %.2f m/s | O2 %.1f%% | CO2 %.2f%% | CO %.0f ppm | Smoke %.2f",
				ThermalConstants.toCelsius(air.temperatureK), air.speedMS(),
				air.o2 * 100.0, air.co2 * 100.0, air.co * 1_000_000.0, air.smoke)).formatted(Formatting.AQUA), false);

		var below = thermo.getNodeReadOnly(p.down());
		if (below != null) {
			context.getSource().sendFeedback(() -> Text.literal(String.format(
					"Underfoot Block: Surf %.1f°C | Core %.1f°C | Moist %.0f%% (S %.0f%% / C %.0f%%) | Stress %.0f%%",
					ThermalConstants.toCelsius(below.body.surfaceTempK),
					ThermalConstants.toCelsius(below.body.coreTempK),
					below.body.totalMoistureRatio() * 100.0,
					below.body.surfaceMoistureRatio() * 100.0,
					below.body.coreMoistureRatio() * 100.0,
					below.body.stressDamage * 100.0)).formatted(Formatting.YELLOW), false);
		}
		return 1;
	}

	private static int showHydration(CommandContext<ServerCommandSource> context) {
		ServerPlayerEntity player = context.getSource().getPlayer();
		if (player == null) {
			context.getSource().sendError(Text.literal("Run /realism hydration as a player."));
			return 0;
		}
		BodyFluidMath.BodyFluidState s = HydrationManager.get(player.getServerWorld()).getOrCreateState(player.getUuid());
		double tbwPct = s.bodyWaterFraction(PhysiologicalConstants.REFERENCE_BODY_MASS_KG) * 100.0;
		context.getSource().sendFeedback(() -> Text.literal(String.format(
				"Compartments: ICF %.2f L | ISF %.2f L | Plasma %.2f L | TBW %.2f L (%.1f%%)",
				s.icfLiters, s.isfLiters, s.plasmaLiters, s.totalBodyWaterLiters(), tbwPct))
				.formatted(Formatting.AQUA), false);
		context.getSource().sendFeedback(() -> Text.literal(String.format(
				"GI & Renal: Stomach %.0f mL | Intestine %.0f mL | Bladder %.0f mL (Urine %.0f mOsm/L @ %.2f mL/s)",
				s.stomachLiters * 1000.0, s.intestineLiters * 1000.0, s.bladderLiters * 1000.0,
				s.urineOsmolalityMOsm, s.lastUrineRateLS * 1000.0))
				.formatted(Formatting.GRAY), false);
		context.getSource().sendFeedback(() -> Text.literal(String.format(
				"Electrolytes: Na+ %.1f mM | K+ %.2f mM | Cl- %.1f mM | Osm ECF %.1f / ICF %.1f mOsm/L",
				s.plasmaNaMm(), s.plasmaKMm(), s.plasmaClMm(),
				s.ecfOsmolalityMOsm(), s.icfOsmolalityMOsm()))
				.formatted(Formatting.GOLD), false);
		context.getSource().sendFeedback(() -> Text.literal(String.format(
				"Thermo & Signals: Core %.2f°C | Skin %.2f°C | Sweat %.1f g/min (Evap %.0f W, Drip %.0f%%) | ADH %.2f | Aldo %.2f | Thirst %.0f%%",
				ThermalConstants.toCelsius(s.bodyCoreTempK),
				ThermalConstants.toCelsius(s.skinTempK),
				s.lastSweatRateKgS * 60000.0,
				s.lastEvapCoolingW,
				s.lastSweatRateKgS > 1e-7 ? (s.lastSweatDripRateKgS / s.lastSweatRateKgS) * 100.0 : 0.0,
				s.adhLevel, s.aldosteroneLevel, s.perceivedThirst * 100.0))
				.formatted(Formatting.YELLOW), false);
		return 1;
	}

	private static int voidBladder(CommandContext<ServerCommandSource> context) {
		ServerPlayerEntity player = context.getSource().getPlayer();
		if (player == null) {
			return 0;
		}
		HydrationManager mgr = HydrationManager.get(player.getServerWorld());
		BodyFluidMath.BodyFluidState s = mgr.getOrCreateState(player.getUuid());
		int ml = (int) Math.round(s.bladderLiters * 1000.0);
		double osm = s.urineOsmolalityMOsm;
		s.bladderLiters = 0.02;
		mgr.markDirty();
		context.getSource().sendFeedback(() -> Text.literal(String.format(
				"Voided %d mL urine (%.0f mOsm/L — %s).",
				ml, osm, osm > 800.0 ? "concentrated dark amber" : (osm < 200.0 ? "very dilute clear" : "pale straw")))
				.formatted(osm > 800.0 ? Formatting.GOLD : Formatting.AQUA), false);
		return 1;
	}
}
