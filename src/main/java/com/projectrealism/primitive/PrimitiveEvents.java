package com.projectrealism.primitive;

import com.projectrealism.ProjectRealism;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * All the event wiring for the primitive early game.
 *
 * <p>Nothing here needs a mixin: block breaking is gated at the moment of the attack (which stops the
 * progress bar before it ever starts), the same rule is enforced server-side so a hacked client cannot
 * skip it, gathering is plain block breaking, and knapping is a right-click.</p>
 */
public final class PrimitiveEvents {
	private static int regrowCountdown = 0;

	private PrimitiveEvents() {
	}

	public static void register() {
		registerBreakGate();
		registerGathering();
		registerKnapping();
		registerWorldgen();
		ProjectRealism.LOGGER.info("Primitive resource gathering enabled.");
	}

	// ------------------------------------------------------------------ gating ----

	private static void registerBreakGate() {
		// The attack event fires before the client begins any mining progress, so cancelling here means
		// the block never takes damage at all: punching a log is a noise, not a slow harvest.
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (player.isSpectator() || player.isCreative()) {
				return ActionResult.PASS;
			}
			if (!ProjectRealism.config().requireTools) {
				return ActionResult.PASS;
			}

			BlockState state = world.getBlockState(pos);
			ToolGate.Requirement requirement = ToolGate.requirementFor(state);
			if (requirement == ToolGate.Requirement.HAND || ToolGate.satisfied(requirement, player, pos)) {
				return ActionResult.PASS;
			}

			if (world.isClient) {
				// The dull thud of a hand on wood is the entire harvest you get.
				world.playSound(player, pos, requirement == ToolGate.Requirement.AXE
						? SoundEvents.BLOCK_WOOD_HIT : SoundEvents.BLOCK_STONE_HIT,
						SoundCategory.BLOCKS, 0.35f, 0.75f);
				world.addParticle(ParticleTypes.CRIT,
						pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5, 0.0, 0.05, 0.0);
				player.sendMessage(ToolGate.hintFor(requirement).copy().formatted(Formatting.RED), true);
			}

			return ActionResult.FAIL;
		});

		// Server-side backstop: covers blocks broken by other means (explosions aside) and any client
		// that ignores the attack cancellation.
		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) -> {
			if (!ProjectRealism.config().requireTools || player.isCreative() || player.isSpectator()) {
				return true;
			}

			ToolGate.Requirement requirement = ToolGate.requirementFor(state);
			if (requirement == ToolGate.Requirement.HAND || ToolGate.satisfied(requirement, player, pos)) {
				return true;
			}

			player.sendMessage(ToolGate.hintFor(requirement).copy().formatted(Formatting.RED), true);
			return false;
		});
	}

	// --------------------------------------------------------------- gathering ----

	private static void registerGathering() {
		// Tall grass -> plant fiber, leaves -> the occasional stick. Hand harvesting only, which is why
		// this hangs off the player break event rather than a loot table.
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, entity) -> {
			if (!(world instanceof ServerWorld server) || player.isCreative()) {
				return;
			}

			var random = server.getRandom();

			if (ProjectRealism.config().plantFibers && isFiberPlant(state)) {
				int fibers = 1 + (random.nextFloat() < 0.4f ? 1 : 0);
				if (state.isOf(Blocks.TALL_GRASS) || state.isOf(Blocks.LARGE_FERN)) {
					fibers++;
				}
				dropAt(server, pos, new ItemStack(PrimitiveItems.PLANT_FIBER, fibers));
			}

			if (state.isIn(BlockTags.LEAVES) && random.nextFloat() < 0.12f) {
				dropAt(server, pos, new ItemStack(Items.STICK, 1));
			}
		});
	}

	private static boolean isFiberPlant(BlockState state) {
		return state.isOf(Blocks.SHORT_GRASS) || state.isOf(Blocks.TALL_GRASS)
				|| state.isOf(Blocks.FERN) || state.isOf(Blocks.LARGE_FERN)
				|| state.isOf(Blocks.SEAGRASS) || state.isOf(Blocks.TALL_SEAGRASS)
				|| state.isOf(Blocks.VINE) || state.isOf(Blocks.HANGING_ROOTS);
	}

	private static void dropAt(ServerWorld world, BlockPos pos, ItemStack stack) {
		net.minecraft.block.Block.dropStack(world, pos, stack);
	}

	// ---------------------------------------------------------------- knapping ----

	private static void registerKnapping() {
		UseItemCallback.EVENT.register((player, world, hand) -> {
			if (!ProjectRealism.config().knapping || player.isSpectator()) {
				return TypedActionResult.pass(player.getStackInHand(hand));
			}

			ItemStack held = player.getStackInHand(hand);

			// Only the main hand drives the interaction; the off hand supplies the second component.
			if (hand == Hand.OFF_HAND) {
				return TypedActionResult.pass(held);
			}

			if (!PrimitiveItems.isKnappable(held.getItem())
					&& !held.isOf(PrimitiveItems.SHARP_STONE)
					&& !held.isOf(PrimitiveItems.STONE_AXE_HEAD)) {
				TypedActionResult<ItemStack> assembled = Assembly.tryAssemble(player, world, hand);
				if (assembled.getResult().isAccepted()) {
					return assembled;
				}
				return TypedActionResult.pass(held);
			}

			TypedActionResult<ItemStack> result = Knapping.tryStrike(player, world, hand);

			// Client prediction: a real swing, so the strike reads as deliberate.
			if (world.isClient && result.getResult().isAccepted()) {
				player.swingHand(hand);
			}

			return result;
		});
	}

	// ----------------------------------------------------------------- worldgen ----

	private static void registerWorldgen() {
		// Both hooks are strictly chunk-local. Touching the world's chunk source from inside generation
		// deadlocks it; see GroundRockSpawner for the long version.
		ServerChunkEvents.CHUNK_GENERATE.register((world, chunk) -> {
			try {
				GroundRockSpawner.populateNewChunk(world, chunk);
				GroundSticks.populateNewChunk(chunk);
			} catch (RuntimeException exception) {
				// A world must never fail to load because decoration went wrong.
				ProjectRealism.LOGGER.error("Rock/stick placement failed for chunk {}; skipping it.",
						chunk.getPos(), exception);
			}
		});

		// Slow regrowth: a rolling pass over chunks that actually have players in them, so rocks come
		// back in the places people return to rather than in untouched terrain nobody will ever see.
		ServerTickEvents.END_SERVER_TICK.register(PrimitiveEvents::tickRegrowth);
	}

	private static void tickRegrowth(MinecraftServer server) {
		int seconds = ProjectRealism.config().rockRegrowSeconds;
		if (seconds <= 0 || !ProjectRealism.config().groundRocks) {
			return;
		}

		if (++regrowCountdown < seconds * 20) {
			return;
		}
		regrowCountdown = 0;

		List<ServerWorld> worlds = new ArrayList<>();
		for (ServerWorld world : server.getWorlds()) {
			worlds.add(world);
		}

		for (ServerWorld world : worlds) {
			for (var player : world.getPlayers()) {
				// getChunk (not getWorldChunk) is deliberate: the player is standing here, so the chunk
				// is already loaded, and the call cannot trigger a synchronous load during a tick.
				int chunkX = player.getBlockX() >> 4;
				int chunkZ = player.getBlockZ() >> 4;
				WorldChunk chunk = world.getChunk(chunkX, chunkZ);
				GroundRockSpawner.tryGrowRock(world, chunk);
			}
		}
	}

	/** Exposed for command output and tests: the rule a block is subject to. */
	public static Text describeRequirement(ToolGate.Requirement requirement) {
		return ToolGate.hintFor(requirement);
	}
}
