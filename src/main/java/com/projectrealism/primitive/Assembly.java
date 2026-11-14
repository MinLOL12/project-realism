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
 * Hold the part and right-click with the other part in the off hand. Binding requires cordage or plant
 * fiber, because a stone head on a stick is not an axe, it is two objects touching.</p>
 */
public final class Assembly {
	private Assembly() {
	}

	public static TypedActionResult<ItemStack> tryAssemble(PlayerEntity player, World world, Hand hand) {
		ItemStack held = player.getStackInHand(hand);
		ItemStack other = player.getStackInHand(hand == Hand.MAIN_HAND ? Hand.OFF_HAND : Hand.MAIN_HAND);

		if (held.isEmpty() || other.isEmpty()) {
			return TypedActionResult.pass(held);
		}

		if (isBinding(held) || isBinding(other)) {
			return bind(player, world, held, other);
		}

		// Either hand may hold the part; players do not read tooltips about ordering.
		if ((held.isOf(PrimitiveItems.SHARP_STONE) && other.isOf(Items.STICK))
				|| (held.isOf(Items.STICK) && other.isOf(PrimitiveItems.SHARP_STONE))) {
			return makeKnife(player, world, held, other);
		}

		if ((held.isOf(PrimitiveItems.STONE_AXE_HEAD) && other.isOf(Items.STICK))
				|| (held.isOf(Items.STICK) && other.isOf(PrimitiveItems.STONE_AXE_HEAD))) {
			return makeStoneAxe(player, world, held, other);
		}

		return TypedActionResult.pass(held);
	}

	private static boolean isBinding(ItemStack stack) {
		return stack.isOf(PrimitiveItems.CORDAGE) || stack.isOf(PrimitiveItems.PLANT_FIBER);
	}

	/**
	 * Cordage (or fiber) plus a finished tool: the binding turns the tool from a loose assembly into
	 * something that will not fall apart.
	 */
	private static TypedActionResult<ItemStack> bind(PlayerEntity player, World world,
													 ItemStack held, ItemStack other) {
		ItemStack binding = isBinding(held) ? held : other;
		ItemStack tool = isBinding(held) ? other : held;

		if (!(tool.isOf(PrimitiveItems.STONE_KNIFE) || tool.isOf(PrimitiveItems.STONE_AXE)
				|| tool.isOf(PrimitiveItems.STONE_PICKAXE))) {
			return TypedActionResult.pass(held);
		}

		if (world instanceof ServerWorld server) {
			// It is already bound; the gesture exists so the mechanic is discoverable rather than silent.
			player.sendMessage(Text.translatable("project_realism.assemble.already_bound")
					.formatted(Formatting.GRAY), true);
			server.playSound(null, player.getBlockPos(), SoundEvents.ITEM_BUNDLE_INSERT, SoundCategory.PLAYERS,
					0.5f, 1.2f);
		}
		return TypedActionResult.pass(held);
	}

	private static TypedActionResult<ItemStack> makeKnife(PlayerEntity player, World world,
														  ItemStack held, ItemStack other) {
		ItemStack sharp = held.isOf(PrimitiveItems.SHARP_STONE) ? held : other;
		ItemStack stick = sharp == held ? other : held;

		if (world instanceof ServerWorld server) {
			if (!hasFiber(player)) {
				player.sendMessage(Text.translatable("project_realism.assemble.needs_fiber")
						.formatted(Formatting.RED), true);
				return TypedActionResult.success(held);
			}
			consumeFiber(player);
			sharp.decrement(1);
			stick.decrement(1);
			give(player, new ItemStack(PrimitiveItems.STONE_KNIFE));
			bindEffects(server, player);
			player.sendMessage(Text.translatable("project_realism.assemble.knife")
					.formatted(Formatting.YELLOW), true);
		}
		return TypedActionResult.success(held);
	}

	private static TypedActionResult<ItemStack> makeStoneAxe(PlayerEntity player, World world,
															 ItemStack held, ItemStack other) {
		ItemStack head = held.isOf(PrimitiveItems.STONE_AXE_HEAD) ? held : other;
		ItemStack stick = head == held ? other : held;

		if (world instanceof ServerWorld server) {
			if (fiberCount(player) < 2) {
				player.sendMessage(Text.translatable("project_realism.assemble.needs_two_fibers")
						.formatted(Formatting.RED), true);
				return TypedActionResult.success(held);
			}
			consumeFiber(player);
			consumeFiber(player);
			head.decrement(1);
			stick.decrement(1);
			give(player, new ItemStack(PrimitiveItems.STONE_AXE));
			bindEffects(server, player);
			player.sendMessage(Text.translatable("project_realism.assemble.axe")
					.formatted(Formatting.GOLD), true);
		}
		return TypedActionResult.success(held);
	}

	private static int fiberCount(PlayerEntity player) {
		int count = 0;
		for (int slot = 0; slot < player.getInventory().size(); slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (stack.isOf(PrimitiveItems.PLANT_FIBER)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	private static boolean hasFiber(PlayerEntity player) {
		return fiberCount(player) > 0;
	}

	private static void consumeFiber(PlayerEntity player) {
		for (int slot = 0; slot < player.getInventory().size(); slot++) {
			ItemStack stack = player.getInventory().getStack(slot);
			if (stack.isOf(PrimitiveItems.PLANT_FIBER)) {
				stack.decrement(1);
				if (stack.isEmpty()) {
					player.getInventory().setStack(slot, ItemStack.EMPTY);
				}
				return;
			}
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
