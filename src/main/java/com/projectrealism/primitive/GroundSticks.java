package com.projectrealism.primitive;

import com.projectrealism.ProjectRealism;
import net.minecraft.block.BlockState;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Fallen sticks: the second half of "there is no wood yet".
 *
 * <p>Sticks lie under trees rather than hanging on them, so a player who has just knapped a knife can
 * walk to the treeline and pick up a handle. They are ordinary item entities with despawning disabled,
 * which is the only way to have loose items that survive a reload.</p>
 */
public final class GroundSticks {
	private GroundSticks() {
	}

	/** Drops a few sticks under any tree in a freshly generated chunk. */
	public static void populateNewChunk(WorldChunk chunk) {
		if (!(chunk.getWorld() instanceof ServerWorld world) || !ProjectRealism.config().groundSticks) {
			return;
		}

		var random = world.getRandom();
		int placed = 0;

		// A handful of probes per chunk is plenty: one tree usually yields one or two sticks.
		for (int attempt = 0; attempt < 6 && placed < 3; attempt++) {
			int x = ChunkSectionPos.getBlockCoord(chunk.getPos().x)
					+ random.nextInt(16);
			int z = ChunkSectionPos.getBlockCoord(chunk.getPos().z)
					+ random.nextInt(16);

			BlockPos top = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
			if (!isTreeCanopy(world, top)) {
				continue;
			}

			if (random.nextDouble() > ProjectRealism.config().stickSpawnChance) {
				continue;
			}

			if (spawnStick(world, top, random)) {
				placed++;
			}
		}
	}

	/** Cheap canopy test: solid ground with leaves a few blocks above it. */
	private static boolean isTreeCanopy(ServerWorld world, BlockPos groundTop) {
		BlockPos below = groundTop.down();
		BlockState belowState = world.getBlockState(below);
		if (!belowState.isSolidBlock(world, below)) {
			return false;
		}

		for (int dy = 2; dy <= 6; dy++) {
			BlockState state = world.getBlockState(groundTop.up(dy));
			if (state.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) {
				return true;
			}
		}
		return false;
	}

	/** Places a single stick slightly scattered off the given position. */
	public static boolean spawnStick(ServerWorld world, BlockPos pos, net.minecraft.util.math.random.Random random) {
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
