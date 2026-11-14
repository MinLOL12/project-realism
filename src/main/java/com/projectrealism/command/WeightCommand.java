package com.projectrealism.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.projectrealism.ProjectRealism;
import com.projectrealism.weight.WeightManager;
import com.projectrealism.weight.WeightReport;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

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
}
