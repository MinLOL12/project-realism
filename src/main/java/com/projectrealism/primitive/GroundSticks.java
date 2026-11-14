package com.projectrealism.primitive;

import com.projectrealism.ProjectRealism;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Fallen sticks: the second half of "there is no wood yet".
 *
 * <p>Sticks lie under trees rather than hanging on them, so a player who has just knapped a knife can
 * walk to the treeline and pick up a handle.</p>
 *
 * <p><strong>No entity spawning here.</strong> This runs during chunk generation, where
 * {@code world.spawnEntity(...)} blocks on the chunk task executor and deadlocks world generation. See
 * {@link GroundRockSpawner} for the full explanation. Sticks are therefore generated into the chunk as
 * {@link RockDropBlock}s, which pop a real item entity the first time a player walks over them — same
 * result, no world access during generation.</p>
 */
public final class GroundSticks {
	private GroundSticks() {
	}

	/** Drops a few sticks under any tree in a freshly generated chunk. */
	public static void populateNewChunk(WorldChunk chunk) {
		if (!ProjectRealism.config().groundSticks || !ProjectRealism.config().generateRocksInNewChunks) {
			return;
		}

		Random random = Random.create(chunk.getPos().toLong() ^ 0x57_1C4CL);
		int placed = 0;

		// A handful of probes per chunk is plenty: one tree usually yields one or two sticks.
		for (int attempt = 0; attempt < 6 && placed < 3; attempt++) {
			int x = chunk.getPos().getStartX() + random.nextInt(16);
			int z = chunk.getPos().getStartZ() + random.nextInt(16);

			Heightmap heightmap = chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES);
			int top = heightmap.get(x & 15, z & 15);
			if (top <= chunk.getBottomY() || top >= chunk.getTopY()) {
				continue;
			}

			BlockPos surface = new BlockPos(x, top, z);
			if (!chunk.getBlockState(surface).isAir()) {
				continue;
			}
			if (!isTreeCanopy(chunk, surface)) {
				continue;
			}

			if (random.nextDouble() > ProjectRealism.config().stickSpawnChance) {
				continue;
			}

			chunk.setBlockState(surface, PrimitiveBlocks.FALLEN_STICK.getDefaultState(), false);
			placed++;
		}
	}

	/** Cheap canopy test: solid ground with leaves a few blocks above it. */
	private static boolean isTreeCanopy(WorldChunk chunk, BlockPos groundTop) {
		BlockPos below = groundTop.down();
		BlockState belowState = chunk.getBlockState(below);
		if (belowState.isAir() || belowState.isIn(BlockTags.LEAVES)) {
			return false;
		}

		for (int dy = 2; dy <= 6; dy++) {
			BlockState state = chunk.getBlockState(groundTop.up(dy));
			if (state.isIn(BlockTags.LEAVES)) {
				return true;
			}
		}
		return false;
	}

	/** Places a single stick item entity. Safe only outside chunk generation. */
	public static boolean spawnStick(ServerWorld world, BlockPos pos, Random random) {
		double x = pos.getX() + 0.5 + (random.nextDouble() - 0.5) * 1.4;
		double y = pos.getY() + 0.1;
		double z = pos.getZ() + 0.5 + (random.nextDouble() - 0.5) * 1.4;

		ItemEntity entity = new ItemEntity(world, x, y, z, new ItemStack(Items.STICK,
				1 + random.nextInt(2)));
		entity.setNeverDespawn();
		entity.setToDefaultPickupDelay();
		entity.setVelocity(0.0, 0.0, 0.0);
		return world.spawnEntity(entity);
	}
}
