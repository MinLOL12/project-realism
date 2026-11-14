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

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Striking rocks together and twisting plant fiber into cordage.
 *
 * <p>Hold a rock in one hand and another rock in the other hand, then right-click: the stones knock
 * a {@link PrimitiveItems#SHARP_STONE} off each other. Hold a sharp stone and a fresh rock (in either
 * hand) and right-click to knap a {@link PrimitiveItems#STONE_AXE_HEAD}. Hold plant fiber in both
 * hands and right-click to twist it into {@link PrimitiveItems#CORDAGE}.</p>
 */
public final class Knapping {
	/** Client pacing between strikes, in milliseconds. */
	public static final long CLIENT_COOLDOWN_MS = 550L;

	/**
	 * Server rate limit, slightly shorter than the client pacing so network jitter never drops a
	 * legitimate strike, while still blocking packet spam.
	 */
	public static final long SERVER_COOLDOWN_MS = 450L;

	/** Separate maps ensure singleplayer client & server threads never collide on the same UUID. */
	private static final Map<UUID, Long> CLIENT_LAST_STRIKE = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> SERVER_LAST_STRIKE = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> LAST_HINT = new ConcurrentHashMap<>();

	private Knapping() {
	}

	public static TypedActionResult<ItemStack> tryStrike(PlayerEntity player, World world, Hand hand) {
		Hand offHand = hand == Hand.MAIN_HAND ? Hand.OFF_HAND : Hand.MAIN_HAND;
		ItemStack held = player.getStackInHand(hand);
		ItemStack other = player.getStackInHand(offHand);

		if (held.isEmpty()) {
			return TypedActionResult.pass(held);
		}

		boolean heldIsRock = PrimitiveItems.isKnappable(held.getItem());
		boolean otherIsRock = PrimitiveItems.isKnappable(other.getItem());
		boolean heldIsSharp = held.isOf(PrimitiveItems.SHARP_STONE);
		boolean otherIsSharp = other.isOf(PrimitiveItems.SHARP_STONE);
		boolean heldIsFiber = held.isOf(PrimitiveItems.PLANT_FIBER);
		boolean otherIsFiber = other.isOf(PrimitiveItems.PLANT_FIBER);
		boolean heldIsTool = ToolCondition.isPrimitiveTool(held);
		boolean otherIsTool = ToolCondition.isPrimitiveTool(other);

		// A dull stone tool struck with a rock (either hand order) re-knaps its cutting edge.
		if ((heldIsTool && otherIsRock) || (otherIsTool && heldIsRock)) {
			return sharpenTool(player, world, hand, offHand, held, other);
		}

		// A sharp edge struck against a fresh rock (either hand order) knaps an axe head.
		if ((heldIsSharp && otherIsRock) || (otherIsSharp && heldIsRock)) {
			return strikeAxeHead(player, world, hand, offHand, held, other);
		}

		// Two rocks: knap both of them into sharp stones.
		if (heldIsRock && otherIsRock) {
			return strikeSharp(player, world, hand, offHand, held, other);
		}

		// Two plant fibers: twist into cordage.
		if (heldIsFiber && otherIsFiber) {
			return twistCordage(player, world, hand, offHand, held, other);
		}

		// A rock and nothing else: tell the player what the interaction wants, while letting the
		// right-click pass through so holding a rock never blocks doors or containers.
		if (heldIsRock && other.isEmpty() && !world.isClient
				&& tryConsumeCooldown(LAST_HINT, player.getUuid(), System.currentTimeMillis(), 1500L)) {
			player.sendMessage(Text.translatable("project_realism.knapping.needs_two")
					.formatted(Formatting.GRAY), true);
		}

		return TypedActionResult.pass(held);
	}

	private static TypedActionResult<ItemStack> strikeSharp(PlayerEntity player, World world,
															Hand hand, Hand offHand,
															ItemStack held, ItemStack other) {
		if (!canStrike(player, world)) {
			return TypedActionResult.consume(held);
		}

		if (world instanceof ServerWorld server) {
			held.decrementUnlessCreative(1, player);
			other.decrementUnlessCreative(1, player);
			clearIfEmpty(player, hand, held);
			clearIfEmpty(player, offHand, other);

			give(player, new ItemStack(PrimitiveItems.SHARP_STONE, 1));
			// The second rock often shatters, which is why one rock is roughly one sharp edge.
			if (server.random.nextFloat() > 0.35f) {
				give(player, new ItemStack(PrimitiveItems.SHARP_STONE, 1));
			} else {
				give(player, new ItemStack(Items.GRAVEL, 1));
			}

			effects(server, player, 0.55f);
			player.sendMessage(Text.translatable("project_realism.knapping.sharp")
					.formatted(Formatting.YELLOW), true);
		}

		return TypedActionResult.success(player.getStackInHand(hand));
	}

	private static TypedActionResult<ItemStack> strikeAxeHead(PlayerEntity player, World world,
															  Hand hand, Hand offHand,
															  ItemStack held, ItemStack other) {
		if (!canStrike(player, world)) {
			return TypedActionResult.consume(held);
		}

		if (world instanceof ServerWorld server) {
			// The sharp stone keeps its edge; the fresh rock is consumed.
			if (held.isOf(PrimitiveItems.SHARP_STONE)) {
				other.decrementUnlessCreative(1, player);
				clearIfEmpty(player, offHand, other);
			} else {
				held.decrementUnlessCreative(1, player);
				clearIfEmpty(player, hand, held);
			}
			give(player, new ItemStack(PrimitiveItems.STONE_AXE_HEAD, 1));
			effects(server, player, 0.8f);
			player.sendMessage(Text.translatable("project_realism.knapping.axe_head")
					.formatted(Formatting.GOLD), true);
		}

		return TypedActionResult.success(player.getStackInHand(hand));
	}

	private static TypedActionResult<ItemStack> twistCordage(PlayerEntity player, World world,
															 Hand hand, Hand offHand,
															 ItemStack held, ItemStack other) {
		if (!canStrike(player, world)) {
			return TypedActionResult.consume(held);
		}

		if (world instanceof ServerWorld server) {
			held.decrementUnlessCreative(1, player);
			other.decrementUnlessCreative(1, player);
			clearIfEmpty(player, hand, held);
			clearIfEmpty(player, offHand, other);

			give(player, new ItemStack(PrimitiveItems.CORDAGE, 1));
			server.playSound(null, player.getBlockPos(), SoundEvents.ITEM_BUNDLE_INSERT, SoundCategory.PLAYERS,
					0.65f, 1.15f);
			server.spawnParticles(ParticleTypes.WAX_ON,
					player.getX(), player.getY() + 1.0, player.getZ(),
					4, 0.2, 0.2, 0.2, 0.02);
			player.sendMessage(Text.translatable("project_realism.knapping.cordage")
					.formatted(Formatting.GREEN), true);
		}

		return TypedActionResult.success(player.getStackInHand(hand));
	}

	private static TypedActionResult<ItemStack> sharpenTool(PlayerEntity player, World world,
															Hand hand, Hand offHand,
															ItemStack held, ItemStack other) {
		boolean toolInMain = ToolCondition.isPrimitiveTool(held);
		ItemStack tool = toolInMain ? held : other;
		ItemStack rock = toolInMain ? other : held;
		Hand toolHand = toolInMain ? hand : offHand;
		Hand rockHand = toolInMain ? offHand : hand;

		if (ToolCondition.getSharpness(tool) >= 98) {
			if (!world.isClient) {
				player.sendMessage(Text.translatable("project_realism.tool.already_sharp")
						.formatted(Formatting.GRAY), true);
			}
			return TypedActionResult.consume(held);
		}

		if (!canStrike(player, world)) {
			return TypedActionResult.consume(held);
		}

		if (world instanceof ServerWorld server) {
			ToolCondition.SharpenResult result = ToolCondition.sharpen(tool, server.random);
			if (server.random.nextFloat() < 0.45f) {
				rock.decrementUnlessCreative(1, player);
				clearIfEmpty(player, rockHand, rock);
			}

			if (result == ToolCondition.SharpenResult.SHATTERED) {
				player.setStackInHand(toolHand, new ItemStack(Items.STICK));
				server.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_STONE_BREAK,
						SoundCategory.PLAYERS, 0.9f, 0.6f);
				server.spawnParticles(ParticleTypes.CRIT,
						player.getX(), player.getY() + 1.0, player.getZ(),
						10, 0.2, 0.2, 0.2, 0.08);
				player.sendMessage(Text.translatable("project_realism.tool.head_shattered")
						.formatted(Formatting.RED), true);
			} else {
				effects(server, player, 1.15f);
				player.sendMessage(Text.translatable("project_realism.tool.sharpened",
						ToolCondition.getSharpness(tool)).formatted(Formatting.AQUA), true);
			}
		}

		return TypedActionResult.success(player.getStackInHand(hand));
	}

	private static boolean canStrike(PlayerEntity player, World world) {
		long now = System.currentTimeMillis();
		if (world.isClient) {
			return tryConsumeCooldown(CLIENT_LAST_STRIKE, player.getUuid(), now, CLIENT_COOLDOWN_MS);
		}
		return tryConsumeCooldown(SERVER_LAST_STRIKE, player.getUuid(), now, SERVER_COOLDOWN_MS);
	}

	/** Pure cooldown helper exposed for unit testing without bootstrapping Minecraft registries. */
	public static boolean tryConsumeCooldown(Map<UUID, Long> map, UUID uuid, long nowMs, long cooldownMs) {
		Long last = map.get(uuid);
		if (last != null && nowMs - last < cooldownMs) {
			return false;
		}
		map.put(uuid, nowMs);
		return true;
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

	private static void effects(ServerWorld world, PlayerEntity player, float pitch) {
		world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_STONE_HIT, SoundCategory.PLAYERS,
				0.7f, pitch + world.random.nextFloat() * 0.2f);
		world.spawnParticles(ParticleTypes.CRIT,
				player.getX(), player.getY() + 1.0, player.getZ(),
				6, 0.2, 0.2, 0.2, 0.05);
	}

	/** Called on disconnect so maps do not leak player ids. */
	public static void forget(UUID uuid) {
		CLIENT_LAST_STRIKE.remove(uuid);
		SERVER_LAST_STRIKE.remove(uuid);
		LAST_HINT.remove(uuid);
	}

	static {
		ProjectRealism.LOGGER.debug("Knapping ready: {} ms client / {} ms server cooldown.",
				CLIENT_COOLDOWN_MS, SERVER_COOLDOWN_MS);
	}
}
