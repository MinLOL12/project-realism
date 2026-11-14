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
 * Assembling tool parts by hand.
 *
 * <p>This is the replacement for the vanilla crafting grid: stone tools are never a shaped recipe.
 * Hold the part and right-click with the other part in the off hand (in either order). Binding consumes
 * plant fiber (1 unit) or cordage (2 units), because a stone head on a stick is not an axe — it is
 * two objects touching.</p>
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

		// Either hand may hold either part; players should never have to guess hand ordering.
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

		if (isBinding(held) || isBinding(other)) {
			return bind(player, world, held, other);
		}

		return TypedActionResult.pass(held);
	}

	private static boolean isBinding(ItemStack stack) {
		return stack.isOf(PrimitiveItems.CORDAGE) || stack.isOf(PrimitiveItems.PLANT_FIBER);
	}

	/**
	 * Cordage (or fiber) plus a finished tool: the gesture exists so the mechanic is discoverable.
	 */
	private static TypedActionResult<ItemStack> bind(PlayerEntity player, World world,
													 ItemStack held, ItemStack other) {
		ItemStack tool = isBinding(held) ? other : held;

		if (!(tool.isOf(PrimitiveItems.STONE_KNIFE) || tool.isOf(PrimitiveItems.STONE_AXE)
				|| tool.isOf(PrimitiveItems.STONE_PICKAXE))) {
			return TypedActionResult.pass(held);
		}

		if (world instanceof ServerWorld server) {
			player.sendMessage(Text.translatable("project_realism.assemble.already_bound")
					.formatted(Formatting.GRAY), true);
			server.playSound(null, player.getBlockPos(), SoundEvents.ITEM_BUNDLE_INSERT, SoundCategory.PLAYERS,
					0.5f, 1.2f);
		}
		return TypedActionResult.pass(held);
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
			if (!player.isCreative()) {
				consumeBinding(player, 1);
			}
			held.decrementUnlessCreative(1, player);
			other.decrementUnlessCreative(1, player);
			clearIfEmpty(player, hand, held);
			clearIfEmpty(player, offHand, other);
			give(player, new ItemStack(PrimitiveItems.STONE_KNIFE));
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
			if (!player.isCreative()) {
				consumeBinding(player, 2);
			}
			held.decrementUnlessCreative(1, player);
			other.decrementUnlessCreative(1, player);
			clearIfEmpty(player, hand, held);
			clearIfEmpty(player, offHand, other);
			give(player, new ItemStack(PrimitiveItems.STONE_AXE));
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
			if (!player.isCreative()) {
				consumeBinding(player, 1);
			}
			held.decrementUnlessCreative(1, player);
			other.decrementUnlessCreative(1, player);
			clearIfEmpty(player, hand, held);
			clearIfEmpty(player, offHand, other);
			give(player, new ItemStack(PrimitiveItems.STONE_PICKAXE));
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
			} else if (stack.isOf(PrimitiveItems.CORDAGE)) {
				units += stack.getCount() * 2;
			}
		}
		return units;
	}

	private static void consumeBinding(PlayerEntity player, int required) {
		int remaining = required;

		// For two-unit bindings, prefer consuming twisted cordage first; for one-unit bindings,
		// prefer loose plant fiber so cordage isn't wasted unless it's the only binding available.
		if (remaining >= 2) {
			remaining = consumeItem(player, PrimitiveItems.CORDAGE, 2, remaining);
			remaining = consumeItem(player, PrimitiveItems.PLANT_FIBER, 1, remaining);
		} else {
			remaining = consumeItem(player, PrimitiveItems.PLANT_FIBER, 1, remaining);
			if (remaining > 0) {
				consumeItem(player, PrimitiveItems.CORDAGE, 2, remaining);
			}
		}
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
