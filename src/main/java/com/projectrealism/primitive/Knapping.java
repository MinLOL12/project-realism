package com.projectrealism.primitive;

import com.projectrealism.ProjectRealism;
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

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Striking rocks together.
 *
 * <p>Hold a rock, right-click with another rock in the other hand. The first strike knocks an edge onto
 * both stones; the second strike turns that edge into a proper blade or an axe head. Strikes are spaced
 * out, so the interaction reads as deliberate work rather than click spam.</p>
 */
public final class Knapping {
	/** Minimum milliseconds between strikes, per player. */
	private static final long STRIKE_COOLDOWN_MS = 600L;

	private static final Map<UUID, Long> LAST_STRIKE = new HashMap<>();

	private Knapping() {
	}

	public static TypedActionResult<ItemStack> tryStrike(PlayerEntity player, World world, Hand hand) {
		ItemStack held = player.getStackInHand(hand);

		if (!PrimitiveItems.isKnappable(held.getItem())) {
			return TypedActionResult.pass(held);
		}

		ItemStack other = player.getStackInHand(hand == Hand.MAIN_HAND ? Hand.OFF_HAND : Hand.MAIN_HAND);
		boolean heldIsSharp = held.isOf(PrimitiveItems.SHARP_STONE);
		boolean otherIsSharp = other.isOf(PrimitiveItems.SHARP_STONE);
		boolean heldIsRock = PrimitiveItems.isKnappable(held.getItem());
		boolean otherIsRock = PrimitiveItems.isKnappable(other.getItem());

		// A sharp edge struck against a fresh rock knocks off flakes until it has a proper axe profile.
		if ((heldIsSharp && otherIsRock) || (otherIsSharp && heldIsRock)) {
			return strikeAxeHead(player, world, held, other);
		}

		// Two rocks: knap both of them into sharp stones.
		if (heldIsRock && otherIsRock) {
			return strikeSharp(player, world, held, other);
		}

		// A rock and nothing else: tell the player what the interaction actually wants, but let the
		// right-click fall through so holding a rock never blocks opening a door or a chest.
		if (!world.isClient && cooledDown(player)) {
			player.sendMessage(Text.translatable("project_realism.knapping.needs_two")
					.formatted(Formatting.GRAY), true);
		}
		return TypedActionResult.pass(held);
	}

	private static TypedActionResult<ItemStack> strikeSharp(PlayerEntity player, World world,
															ItemStack held, ItemStack other) {
		if (!canStrike(player)) {
			return TypedActionResult.success(held);
		}

		if (world instanceof ServerWorld server) {
			held.decrementUnlessCreative(1, player);
			other.decrementUnlessCreative(1, player);

			give(player, new ItemStack(PrimitiveItems.SHARP_STONE, 1));
			// The second rock often shatters, which is why one rock is roughly one sharp edge.
			if (server.random.nextFloat() > 0.35f) {
				give(player, new ItemStack(PrimitiveItems.SHARP_STONE, 1));
			} else {
				give(player, new ItemStack(Items.GRAVEL, 1));
			}

			effects(server, player, 0.55f);
			player.sendMessage(Text.translatable("project_realism.knapping.sharp").formatted(Formatting.YELLOW), true);
		}

		return TypedActionResult.success(held);
	}

	private static TypedActionResult<ItemStack> strikeAxeHead(PlayerEntity player, World world,
															  ItemStack held, ItemStack other) {
		if (!canStrike(player)) {
			return TypedActionResult.success(held);
		}

		if (world instanceof ServerWorld server) {
			// The sharp stone keeps its edge; the rock does not survive.
			if (held.isOf(PrimitiveItems.SHARP_STONE)) {
				other.decrementUnlessCreative(1, player);
			} else {
				held.decrementUnlessCreative(1, player);
			}
			give(player, new ItemStack(PrimitiveItems.STONE_AXE_HEAD, 1));
			effects(server, player, 0.8f);
			player.sendMessage(Text.translatable("project_realism.knapping.axe_head").formatted(Formatting.GOLD), true);
		}

		return TypedActionResult.success(held);
	}

	private static boolean cooledDown(PlayerEntity player) {
		Long last = LAST_STRIKE.get(player.getUuid());
		return last == null || System.currentTimeMillis() - last >= STRIKE_COOLDOWN_MS;
	}

	private static boolean canStrike(PlayerEntity player) {
		if (!cooledDown(player)) {
			return false;
		}
		LAST_STRIKE.put(player.getUuid(), System.currentTimeMillis());
		return true;
	}

	private static void give(PlayerEntity player, ItemStack stack) {
		if (!player.getInventory().insertStack(stack)) {
			player.dropItem(stack, false);
		}
	}

	private static void effects(ServerWorld world, PlayerEntity player, float pitch) {
		world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_STONE_HIT, SoundCategory.PLAYERS,
				0.7f, pitch + world.random.nextFloat() * 0.2f);
		world.spawnParticles(ParticleTypes.CRIT,
				player.getX(), player.getY() + 1.0, player.getZ(),
				6, 0.2, 0.2, 0.2, 0.05);
	}

	/** Called on disconnect so the map does not leak player ids. */
	public static void forget(UUID uuid) {
		LAST_STRIKE.remove(uuid);
	}

	static {
		ProjectRealism.LOGGER.debug("Knapping ready: {} ms between strikes.", STRIKE_COOLDOWN_MS);
	}
}
