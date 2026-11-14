package com.projectrealism.primitive;

import com.projectrealism.ProjectRealism;
import com.projectrealism.RealismConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Populates rocks around the world.
 *
 * <p><strong>Everything here must stay inside the chunk it is handed.</strong> This runs from
 * {@code ServerChunkEvents.CHUNK_GENERATE}, i.e. while the server's chunk task executor is busy
 * generating. Any call that asks the world to resolve a chunk — {@code world.getTopPosition(...)},
 * {@code world.getBlockState(...)}, {@code world.setBlockState(...)}, or spawning an entity — queues
 * work on that same executor and blocks the generation thread waiting for it. That is a deadlock: the
 * world sits on "Generating world" at a couple of percent, forever, with nothing in the log. So reads
 * go through the chunk's own heightmap and its own state cache, and writes go through the chunk only.</p>
 */
public final class GroundRockSpawner {
	private GroundRockSpawner() {
	}

	/** Called for every newly generated chunk exactly once. */
	public static void populateNewChunk(ServerWorld world, WorldChunk chunk) {
		RealismConfig config = ProjectRealism.config();
		if (!config.groundRocks || !config.generateRocksInNewChunks) {
			return;
		}

		Random random = world.getRandom();
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

	private static boolean placeRock(ServerWorld world, WorldChunk chunk, Random random) {
		int x = chunk.getPos().getStartX() + random.nextInt(16);
		int z = chunk.getPos().getStartZ() + random.nextInt(16);

		// The heightmap stored on the chunk itself. Deliberately not world.getTopPosition(...): that
		// asks the world for a chunk, which deadlocks while generation holds the executor.
		Heightmap heightmap = chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES);
		int top = heightmap.get(x & 15, z & 15);

		if (top <= chunk.getBottomY() || top >= chunk.getTopY()) {
			return false;
		}

		BlockPos surface = new BlockPos(x, top, z);

		BlockState groundState = chunk.getBlockState(surface.down());
		if (!PrimitiveBlocks.isRockSurface(groundState.getBlock())) {
			return false;
		}
		if (!chunk.getBlockState(surface).isAir()) {
			return false;
		}

		Block rock = pickRock(groundState.getBlock(), random);
		BlockState state = rock.getDefaultState().with(GroundRockBlock.STAGE, 1 + random.nextInt(2));

		// setBlockState on the chunk keeps the heightmap and lighting bookkeeping consistent, and does
		// not touch the world's chunk source.
		chunk.setBlockState(surface, state, Block.NOTIFY_LISTENERS);
		return true;
	}

	private static Block pickRock(Block surface, Random random) {
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
