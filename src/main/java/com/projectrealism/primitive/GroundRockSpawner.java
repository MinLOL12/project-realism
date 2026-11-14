package com.projectrealism.primitive;

import com.projectrealism.ProjectRealism;
import com.projectrealism.RealismConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Populates rocks around the world.
 *
 * <p>Runs when a chunk first becomes a full chunk — the only reliable "chunk is ready" hook available
 * without a chunk-load mixin — and again on a slow timer for chunks players are standing in, which is
 * what makes rocks regrow. Rocks are placed strictly on the surface and only where the block below is a
 * plausible stone source, so they read as geology rather than loot.</p>
 */
public final class GroundRockSpawner {
	private GroundRockSpawner() {
	}

	/** Called for every newly generated chunk exactly once. */
	public static void populateNewChunk(ServerWorld world, WorldChunk chunk) {
		RealismConfig config = ProjectRealism.config();
		if (!config.groundRocks) {
			return;
		}

		var random = world.getRandom();
		for (int i = 0; i < config.rocksPerChunk; i++) {
			if (random.nextDouble() > config.rockSpawnChance) {
				continue;
			}
			placeRock(world, chunk, random);
		}
	}

	/**
	 * Tries to grow a rock in this chunk at a random surface position. Used by both initial population
	 * and the slow regrowth pass.
	 */
	public static boolean tryGrowRock(ServerWorld world, WorldChunk chunk) {
		return placeRock(world, chunk, world.getRandom());
	}

	private static boolean placeRock(ServerWorld world, WorldChunk chunk,
									 Random random) {
		int x = chunk.getPos().getStartX() + random.nextInt(16);
		int z = chunk.getPos().getStartZ() + random.nextInt(16);

		BlockPos surface = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));

		// Chunk edges get filled in by their own generation pass; writing outside the current chunk while
		// it is still generating is how you corrupt a world.
		if ((surface.getX() >> 4) != chunk.getPos().x || (surface.getZ() >> 4) != chunk.getPos().z) {
			return false;
		}

		BlockState groundState = chunk.getBlockState(surface.down());
		if (!PrimitiveBlocks.isRockSurface(groundState.getBlock())) {
			return false;
		}
		if (!chunk.getBlockState(surface).isAir()) {
			return false;
		}

		Block rock = pickRock(groundState.getBlock(), random);
		BlockState state = rock.getDefaultState().with(GroundRockBlock.STAGE, 1 + random.nextInt(2));

		world.setBlockState(surface, state, Block.NOTIFY_LISTENERS);
		return true;
	}

	private static Block pickRock(Block surface, net.minecraft.util.math.random.Random random) {
		int roll = random.nextInt(100);

		// Granite ground yields granite; limestone is the common soft stone; flint hides in gravel.
		if (surface == Blocks.GRANITE || surface == Blocks.DEEPSLATE || surface == Blocks.TUFF) {
			return roll < 75 ? PrimitiveBlocks.GRANITE_ROCK : PrimitiveBlocks.FLINT_ROCK;
		}
		if (surface == Blocks.GRAVEL || surface == Blocks.SAND || surface == Blocks.RED_SAND) {
			return roll < 60 ? PrimitiveBlocks.FLINT_ROCK : PrimitiveBlocks.GRANITE_ROCK;
		}
		if (surface == Blocks.CALCITE) {
			return PrimitiveBlocks.LIMESTONE_ROCK;
		}
		if (surface == Blocks.STONE || surface == Blocks.ANDESITE || surface == Blocks.DIORITE) {
			return roll < 55 ? PrimitiveBlocks.LIMESTONE_ROCK : PrimitiveBlocks.GRANITE_ROCK;
		}
		return roll < 50 ? PrimitiveBlocks.LIMESTONE_ROCK : PrimitiveBlocks.GRANITE_ROCK;
	}

	/** Whether a rock may rest on this block state. Exposed for {@link GroundRockBlock}. */
	public static boolean canRestOn(BlockState below) {
		return PrimitiveBlocks.isRockSurface(below.getBlock());
	}
}
