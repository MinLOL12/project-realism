package com.projectrealism.primitive;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/**
 * Assembling tool parts by hand and re-tightening loose bindings.
 */
public final class Assembly {
	private Assembly() {
	}

	public static TypedActionResult<ItemStack> tryAssemble(PlayerEntity player, World world, Hand hand) {
		Hand offHand = hand == Hand.MAIN_HAND ? Hand.OFF_HAND : Hand.MAIN_HAND;
		ItemStack held = player.getStackInHand(hand);
		ItemStack other = player.getStackInHand(offHand);

		if (held.isEmpty() || other.isEmpty()) {
			return TypedActionResult.pass(held);
		}

		// Dynamic stone or metal + Stick -> physical handle hafting (no predefined recipe!)
		boolean heldIsDyn = held.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE);
		boolean otherIsDyn = other.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE);
		boolean heldIsMetal = held.isOf(com.projectrealism.metallurgy.MetallurgyItems.DYNAMIC_METAL);
		boolean otherIsMetal = other.isOf(com.projectrealism.metallurgy.MetallurgyItems.DYNAMIC_METAL);
		if ((heldIsDyn && other.isOf(Items.STICK)) || (otherIsDyn && held.isOf(Items.STICK))) {
			return haftDynamicStone(player, world, hand, offHand, held, other);
		}
		if ((heldIsMetal && other.isOf(Items.STICK)) || (otherIsMetal && held.isOf(Items.STICK))) {
			return haftDynamicMetal(player, world, hand, offHand, held, other);
		}

		if (isBinding(held) || isBinding(other)) {
			if (heldIsDyn || otherIsDyn) {
				return bindDynamicStone(player, world, hand, offHand, held, other);
			}
			if (heldIsMetal || otherIsMetal) {
				return bindDynamicMetal(player, world, hand, offHand, held, other);
			}
			return bind(player, world, hand, offHand, held, other);
		}

		if (com.projectrealism.ProjectRealism.config().dynamicKnapping) {
			return TypedActionResult.pass(held);
		}

		if ((held.isOf(PrimitiveItems.SHARP_STONE) && other.isOf(Items.STICK))
				|| (held.isOf(Items.STICK) && other.isOf(PrimitiveItems.SHARP_STONE))) {
			return makeKnife(player, world, hand, offHand, held, other);
		}

		if ((held.isOf(PrimitiveItems.STONE_AXE_HEAD) && other.isOf(Items.STICK))
				|| (held.isOf(Items.STICK) && other.isOf(PrimitiveItems.STONE_AXE_HEAD))) {
			return makeStoneAxe(player, world, hand, offHand, held, other);
		}

		if ((held.isOf(PrimitiveItems.STONE_AXE) && other.isOf(PrimitiveItems.SHARP_STONE))
				|| (held.isOf(PrimitiveItems.SHARP_STONE) && other.isOf(PrimitiveItems.STONE_AXE))) {
			return makeStonePickaxe(player, world, hand, offHand, held, other);
		}

		return TypedActionResult.pass(held);
	}

	private static TypedActionResult<ItemStack> haftDynamicStone(PlayerEntity player, World world,
																 Hand hand, Hand offHand,
																 ItemStack held, ItemStack other) {
		boolean stoneInMain = held.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE);
		ItemStack stoneStack = stoneInMain ? held : other;
		ItemStack stickStack = stoneInMain ? other : held;
		Hand stickHand = stoneInMain ? offHand : hand;

		if (world instanceof ServerWorld server) {
			var state = com.projectrealism.knapping.DynamicStoneItem.readState(stoneStack);
			var ori = player.isSneaking()
					? com.projectrealism.knapping.HaftData.Orientation.PERPENDICULAR_CHOP
					: com.projectrealism.knapping.HaftData.Orientation.INLINE;
			if (!state.haft.hafted) {
				stickStack.decrementUnlessCreative(1, player);
				clearIfEmpty(player, stickHand, stickStack);
			} else {
				var all = com.projectrealism.knapping.HaftData.Orientation.values();
				ori = all[(state.haft.orientation.ordinal() + 1) % all.length];
			}
			state.haft.attachHandle(ori, ori == com.projectrealism.knapping.HaftData.Orientation.INLINE ? 30.0 : 62.0,
					ori == com.projectrealism.knapping.HaftData.Orientation.INLINE ? 0.18 : 0.42, state.metrics());
			if (!player.isCreative() && bindingUnits(player) >= 1) {
				boolean strong = consumeBinding(player, 1);
				state.haft.wrapBinding(strong ? "cordage" : "plant_fiber", 0.85);
			}
			com.projectrealism.knapping.DynamicStoneItem.writeState(stoneStack, state);
			bindEffects(server, player);
			player.sendMessage(Text.translatable("project_realism.knapping.hafted",
					ori.id,
					(int) Math.round(state.haft.socketFit * 100.0),
					(int) Math.round(state.haft.edgeClearance * 100.0)).formatted(Formatting.GOLD), true);
		}
		return TypedActionResult.success(player.getStackInHand(hand));
	}

	private static TypedActionResult<ItemStack> bindDynamicStone(PlayerEntity player, World world,
																 Hand hand, Hand offHand,
																 ItemStack held, ItemStack other) {
		boolean bindInMain = isBinding(held);
		ItemStack binding = bindInMain ? held : other;
		ItemStack stoneStack = bindInMain ? other : held;
		Hand bindingHand = bindInMain ? hand : offHand;

		var state = com.projectrealism.knapping.DynamicStoneItem.readState(stoneStack);
		if (!state.haft.hafted) {
			if (!world.isClient) {
				player.sendMessage(Text.translatable("project_realism.knapping.needs_stick_first")
						.formatted(Formatting.YELLOW), true);
			}
			return TypedActionResult.pass(held);
		}
		if (world instanceof ServerWorld server) {
			String mat = binding.isOf(com.projectrealism.anatomy.AnatomyItems.SINEW) ? "sinew"
					: (binding.isOf(com.projectrealism.anatomy.AnatomyItems.TENDON) ? "tendon"
					: (binding.isOf(PrimitiveItems.CORDAGE) ? "cordage" : "plant_fiber"));
			state.haft.wrapBinding(mat, player.isSneaking() ? 0.96 : 0.82);
			com.projectrealism.knapping.DynamicStoneItem.writeState(stoneStack, state);
			binding.decrementUnlessCreative(1, player);
			clearIfEmpty(player, bindingHand, binding);
			bindEffects(server, player);
			player.sendMessage(Text.translatable("project_realism.knapping.bound",
					mat,
					(int) Math.round(state.haft.bindingTension * 100.0),
					(int) Math.round(state.haft.slipResistance * 100.0)).formatted(Formatting.GREEN), true);
		}
		return TypedActionResult.success(player.getStackInHand(hand));
	}

	private static TypedActionResult<ItemStack> haftDynamicMetal(PlayerEntity player, World world,
																 Hand hand, Hand offHand,
																 ItemStack held, ItemStack other) {
		boolean metalInMain = held.isOf(com.projectrealism.metallurgy.MetallurgyItems.DYNAMIC_METAL);
		ItemStack metalStack = metalInMain ? held : other;
		ItemStack stickStack = metalInMain ? other : held;
		Hand stickHand = metalInMain ? offHand : hand;

		if (world instanceof ServerWorld server) {
			var state = com.projectrealism.metallurgy.DynamicMetalItem.readState(metalStack);
			if (state.temperatureK > 450.0) {
				player.sendMessage(Text.literal("Cool hot metal before attaching wooden handle!")
						.formatted(Formatting.RED), true);
				return TypedActionResult.fail(player.getStackInHand(hand));
			}
			var ori = player.isSneaking()
					? com.projectrealism.knapping.HaftData.Orientation.PERPENDICULAR_CHOP
					: com.projectrealism.knapping.HaftData.Orientation.INLINE;
			if (!state.haft.hafted) {
				stickStack.decrementUnlessCreative(1, player);
				clearIfEmpty(player, stickHand, stickStack);
			} else {
				var all = com.projectrealism.knapping.HaftData.Orientation.values();
				ori = all[(state.haft.orientation.ordinal() + 1) % all.length];
			}
			state.haft.attachHandle(ori, ori == com.projectrealism.knapping.HaftData.Orientation.INLINE ? 30.0 : 62.0,
					ori == com.projectrealism.knapping.HaftData.Orientation.INLINE ? 0.18 : 0.42, state.metrics());
			if (!player.isCreative() && bindingUnits(player) >= 1) {
				boolean strong = consumeBinding(player, 1);
				state.haft.wrapBinding(strong ? "cordage" : "plant_fiber", 0.85);
			}
			com.projectrealism.metallurgy.DynamicMetalItem.writeState(metalStack, state);
			bindEffects(server, player);
			player.sendMessage(Text.translatable("project_realism.knapping.hafted",
					ori.id,
					(int) Math.round(state.haft.socketFit * 100.0),
					(int) Math.round(state.haft.edgeClearance * 100.0)).formatted(Formatting.GOLD), true);
		}
		return TypedActionResult.success(player.getStackInHand(hand));
	}

	private static TypedActionResult<ItemStack> bindDynamicMetal(PlayerEntity player, World world,
																 Hand hand, Hand offHand,
																 ItemStack held, ItemStack other) {
		boolean bindInMain = isBinding(held);
		ItemStack binding = bindInMain ? held : other;
		ItemStack metalStack = bindInMain ? other : held;
		Hand bindingHand = bindInMain ? hand : offHand;

		var state = com.projectrealism.metallurgy.DynamicMetalItem.readState(metalStack);
		if (!state.haft.hafted) {
			if (!world.isClient) {
				player.sendMessage(Text.translatable("project_realism.knapping.needs_stick_first")
						.formatted(Formatting.YELLOW), true);
			}
			return TypedActionResult.pass(held);
		}
		if (world instanceof ServerWorld server) {
			String mat = binding.isOf(com.projectrealism.anatomy.AnatomyItems.SINEW) ? "sinew"
					: (binding.isOf(com.projectrealism.anatomy.AnatomyItems.TENDON) ? "tendon"
					: (binding.isOf(PrimitiveItems.CORDAGE) ? "cordage" : "plant_fiber"));
			state.haft.wrapBinding(mat, player.isSneaking() ? 0.96 : 0.82);
			com.projectrealism.metallurgy.DynamicMetalItem.writeState(metalStack, state);
			binding.decrementUnlessCreative(1, player);
			clearIfEmpty(player, bindingHand, binding);
			bindEffects(server, player);
			player.sendMessage(Text.translatable("project_realism.knapping.bound",
					mat,
					(int) Math.round(state.haft.bindingTension * 100.0),
					(int) Math.round(state.haft.slipResistance * 100.0)).formatted(Formatting.GREEN), true);
		}
		return TypedActionResult.success(player.getStackInHand(hand));
	}

	private static boolean isBinding(ItemStack stack) {
		return stack.isOf(PrimitiveItems.CORDAGE) || stack.isOf(PrimitiveItems.PLANT_FIBER)
				|| stack.isOf(com.projectrealism.anatomy.AnatomyItems.SINEW)
				|| stack.isOf(com.projectrealism.anatomy.AnatomyItems.TENDON);
	}

	/**
	 * Cordage, sinew, tendon, or fiber plus a finished tool: tightens or upgrades loose bindings.
	 */
	private static TypedActionResult<ItemStack> bind(PlayerEntity player, World world,
													 Hand hand, Hand offHand,
													 ItemStack held, ItemStack other) {
		boolean bindingInMain = isBinding(held);
		ItemStack binding = bindingInMain ? held : other;
		ItemStack tool = bindingInMain ? other : held;
		Hand bindingHand = bindingInMain ? hand : offHand;

		if (!ToolCondition.isPrimitiveTool(tool)) {
			return TypedActionResult.pass(held);
		}

		boolean isStrong = binding.isOf(PrimitiveItems.CORDAGE)
				|| binding.isOf(com.projectrealism.anatomy.AnatomyItems.SINEW)
				|| binding.isOf(com.projectrealism.anatomy.AnatomyItems.TENDON);
		int currentBind = ToolCondition.getBinding(tool);
		if (currentBind >= 98 && (ToolCondition.isCordageBound(tool) || !isStrong)) {
			if (!world.isClient) {
				player.sendMessage(Text.translatable("project_realism.assemble.already_bound")
						.formatted(Formatting.GRAY), true);
			}
			return TypedActionResult.pass(held);
		}

		if (world instanceof ServerWorld server) {
			ToolCondition.tightenBinding(tool, isStrong);
			binding.decrementUnlessCreative(1, player);
			clearIfEmpty(player, bindingHand, binding);
			bindEffects(server, player);
			player.sendMessage(Text.translatable("project_realism.tool.rebound",
					ToolCondition.getBinding(tool)).formatted(Formatting.GREEN), true);
		}
		return TypedActionResult.success(player.getStackInHand(hand));
	}

	private static TypedActionResult<ItemStack> makeKnife(PlayerEntity player, World world,
														  Hand hand, Hand offHand,
														  ItemStack held, ItemStack other) {
		if (!player.isCreative() && bindingUnits(player) < 1) {
			if (!world.isClient) {
				player.sendMessage(Text.translatable("project_realism.assemble.needs_one_binding")
						.formatted(Formatting.RED), true);
			}
			return TypedActionResult.consume(held);
		}

		if (world instanceof ServerWorld server) {
			boolean usedCordage = player.isCreative() || consumeBinding(player, 1);
			held.decrementUnlessCreative(1, player);
			other.decrementUnlessCreative(1, player);
			clearIfEmpty(player, hand, held);
			clearIfEmpty(player, offHand, other);
			ItemStack knife = new ItemStack(PrimitiveItems.STONE_KNIFE);
			ToolCondition.initTool(knife, usedCordage);
			give(player, knife);
			bindEffects(server, player);
			player.sendMessage(Text.translatable("project_realism.assemble.knife")
					.formatted(Formatting.YELLOW), true);
		}
		return TypedActionResult.success(player.getStackInHand(hand));
	}

	private static TypedActionResult<ItemStack> makeStoneAxe(PlayerEntity player, World world,
															 Hand hand, Hand offHand,
															 ItemStack held, ItemStack other) {
		if (!player.isCreative() && bindingUnits(player) < 2) {
			if (!world.isClient) {
				player.sendMessage(Text.translatable("project_realism.assemble.needs_two_binding")
						.formatted(Formatting.RED), true);
			}
			return TypedActionResult.consume(held);
		}

		if (world instanceof ServerWorld server) {
			boolean usedCordage = player.isCreative() || consumeBinding(player, 2);
			held.decrementUnlessCreative(1, player);
			other.decrementUnlessCreative(1, player);
			clearIfEmpty(player, hand, held);
			clearIfEmpty(player, offHand, other);
			ItemStack axe = new ItemStack(PrimitiveItems.STONE_AXE);
			ToolCondition.initTool(axe, usedCordage);
			give(player, axe);
			bindEffects(server, player);
			player.sendMessage(Text.translatable("project_realism.assemble.axe")
					.formatted(Formatting.GOLD), true);
		}
		return TypedActionResult.success(player.getStackInHand(hand));
	}

	private static TypedActionResult<ItemStack> makeStonePickaxe(PlayerEntity player, World world,
																 Hand hand, Hand offHand,
																 ItemStack held, ItemStack other) {
		if (!player.isCreative() && bindingUnits(player) < 1) {
			if (!world.isClient) {
				player.sendMessage(Text.translatable("project_realism.assemble.needs_one_binding")
						.formatted(Formatting.RED), true);
			}
			return TypedActionResult.consume(held);
		}

		if (world instanceof ServerWorld server) {
			boolean usedCordage = player.isCreative() || consumeBinding(player, 1);
			held.decrementUnlessCreative(1, player);
			other.decrementUnlessCreative(1, player);
			clearIfEmpty(player, hand, held);
			clearIfEmpty(player, offHand, other);
			ItemStack pick = new ItemStack(PrimitiveItems.STONE_PICKAXE);
			ToolCondition.initTool(pick, usedCordage);
			give(player, pick);
			bindEffects(server, player);
			player.sendMessage(Text.translatable("project_realism.assemble.pickaxe")
					.formatted(Formatting.GOLD), true);
		}
		return TypedActionResult.success(player.getStackInHand(hand));
	}

	private static int bindingUnits(PlayerEntity player) {
		int units = 0;
		for (int slot = 0; slot < player.getInventory().size(); slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (stack.isOf(PrimitiveItems.PLANT_FIBER)) {
				units += stack.getCount();
			} else if (stack.isOf(PrimitiveItems.CORDAGE)
					|| stack.isOf(com.projectrealism.anatomy.AnatomyItems.SINEW)
					|| stack.isOf(com.projectrealism.anatomy.AnatomyItems.TENDON)) {
				units += stack.getCount() * 2;
			}
		}
		return units;
	}

	/** Returns true if cordage/sinew/tendon (strong binding) was used, false if only raw plant fiber was used. */
	private static boolean consumeBinding(PlayerEntity player, int required) {
		int remaining = required;
		boolean usedStrong = false;

		if (remaining >= 2) {
			int before = remaining;
			remaining = consumeItem(player, PrimitiveItems.CORDAGE, 2, remaining);
			remaining = consumeItem(player, com.projectrealism.anatomy.AnatomyItems.SINEW, 2, remaining);
			remaining = consumeItem(player, com.projectrealism.anatomy.AnatomyItems.TENDON, 2, remaining);
			usedStrong = remaining < before;
			remaining = consumeItem(player, PrimitiveItems.PLANT_FIBER, 1, remaining);
		} else {
			remaining = consumeItem(player, PrimitiveItems.PLANT_FIBER, 1, remaining);
			if (remaining > 0) {
				int before = remaining;
				remaining = consumeItem(player, PrimitiveItems.CORDAGE, 2, remaining);
				remaining = consumeItem(player, com.projectrealism.anatomy.AnatomyItems.SINEW, 2, remaining);
				remaining = consumeItem(player, com.projectrealism.anatomy.AnatomyItems.TENDON, 2, remaining);
				usedStrong = remaining < before;
			}
		}
		return usedStrong;
	}

	private static int consumeItem(PlayerEntity player, net.minecraft.item.Item item, int unitValue, int remaining) {
		for (int slot = 0; slot < player.getInventory().size() && remaining > 0; slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (!stack.isOf(item)) {
				continue;
			}
			while (!stack.isEmpty() && remaining > 0) {
				stack.decrement(1);
				remaining -= unitValue;
			}
			if (stack.isEmpty()) {
				player.getInventory().setStack(slot, ItemStack.EMPTY);
			}
		}
		return Math.max(0, remaining);
	}

	private static void clearIfEmpty(PlayerEntity player, Hand hand, ItemStack stack) {
		if (stack.isEmpty()) {
			player.setStackInHand(hand, ItemStack.EMPTY);
		}
	}

	private static void give(PlayerEntity player, ItemStack stack) {
		if (!player.getInventory().insertStack(stack)) {
			player.dropItem(stack, false);
		}
	}

	private static void bindEffects(ServerWorld world, PlayerEntity player) {
		world.playSound(null, player.getBlockPos(), SoundEvents.ITEM_BUNDLE_INSERT, SoundCategory.PLAYERS,
				0.8f, 0.9f);
		world.spawnParticles(ParticleTypes.WAX_ON,
				player.getX(), player.getY() + 1.0, player.getZ(), 4, 0.2, 0.2, 0.2, 0.02);
	}
}
