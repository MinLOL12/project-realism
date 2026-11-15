package com.projectrealism.metallurgy;

import com.projectrealism.ProjectRealism;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Random;

/**
 * Chunk-local geological ore vein and alluvial placer generator.
 *
 * <p>Strictly reads/writes only inside the handed {@link WorldChunk} during
 * {@code ServerChunkEvents.CHUNK_GENERATE} to preserve deadlock-free chunk generation.</p>
 */
public final class OreVeinSpawner {
	private OreVeinSpawner() {
	}

	public static void register() {
		ServerChunkEvents.CHUNK_GENERATE.register(OreVeinSpawner::populateChunk);
	}

	public static void populateChunk(ServerWorld world, WorldChunk chunk) {
		if (!ProjectRealism.config().geologicalOreVeins) return;

		long chunkSeed = world.getSeed() ^ ((long) chunk.getPos().x * 341873128712L)
				^ ((long) chunk.getPos().z * 132897987541L);
		Random rng = new Random(chunkSeed);

		// Regional geological province noise (broad multi-chunk belts of copper, tin, or iron richness)
		double rx = chunk.getPos().x * 0.14;
		double rz = chunk.getPos().z * 0.14;
		double province = Math.sin(rx + 0.7 * Math.cos(rz)) + 0.5 * Math.cos(rx * 0.6 - rz * 0.8);

		int startX = chunk.getPos().getStartX();
		int startZ = chunk.getPos().getStartZ();
		Heightmap hm = chunk.getHeightmap(Heightmap.Type.OCEAN_FLOOR_WG);

		// 1. Alluvial river/clay-bed placers: native copper, cassiterite tin gravel, limonite bog iron, fire clay
		for (int attempt = 0; attempt < 3; attempt++) {
			int lx = 2 + rng.nextInt(12);
			int lz = 2 + rng.nextInt(12);
			int top = hm.get(lx, lz);
			if (top <= chunk.getBottomY() + 2 || top >= chunk.getTopY() - 2) continue;

			BlockPos p = new BlockPos(startX + lx, top - 1, startZ + lz);
			BlockState bed = chunk.getBlockState(p);
			if (bed.isOf(Blocks.GRAVEL) || bed.isOf(Blocks.SAND) || bed.isOf(Blocks.CLAY)) {
				double roll = rng.nextDouble();
				Block target = roll < 0.42 ? MetallurgyBlocks.FIRE_CLAY_DEPOSIT
						: (roll < 0.72 ? MetallurgyBlocks.NATIVE_COPPER_DEPOSIT
						: (roll < 0.90 ? MetallurgyBlocks.CASSITERITE_DEPOSIT
						: MetallurgyBlocks.LIMONITE_BOG_DEPOSIT));
				placeClusterInChunk(chunk, startX + lx, top - 1, startZ + lz, 3 + rng.nextInt(4), target, rng, false);
			}
		}

		// 2. Subsurface bedrock veins (oxidized gossans near surface -> sulfides & iron deep underground)
		int veinCount = 1 + (rng.nextDouble() < 0.55 ? 1 : 0);
		for (int v = 0; v < veinCount; v++) {
			int lx = 2 + rng.nextInt(12);
			int lz = 2 + rng.nextInt(12);
			int top = hm.get(lx, lz);
			int minY = Math.max(chunk.getBottomY() + 6, -36);
			int maxY = Math.max(minY + 8, top - 4);
			int vy = minY + rng.nextInt(Math.max(1, maxY - minY));

			Block veinBlock = pickSubsurfaceVein(vy, province, rng);
			int veinSize = 4 + rng.nextInt(7);
			placeClusterInChunk(chunk, startX + lx, vy, startZ + lz, veinSize, veinBlock, rng, true);

			// Shallow oxidized veins sometimes break surface as weathered green/blue malachite gossan indicator!
			if (vy >= top - 14 && (veinBlock == MetallurgyBlocks.MALACHITE_AZURITE_DEPOSIT
					|| veinBlock == MetallurgyBlocks.NATIVE_COPPER_DEPOSIT)
					&& rng.nextDouble() < 0.35) {
				BlockPos surf = new BlockPos(startX + lx, top - 1, startZ + lz);
				BlockState surfState = chunk.getBlockState(surf);
				if (surfState.isOf(Blocks.STONE) || surfState.isOf(Blocks.GRASS_BLOCK) || surfState.isOf(Blocks.DIRT)) {
					chunk.setBlockState(surf, MetallurgyBlocks.MALACHITE_AZURITE_DEPOSIT.getDefaultState(), false);
				}
			}
		}
	}

	private static Block pickSubsurfaceVein(int y, double province, Random rng) {
		double r = rng.nextDouble();
		if (y >= 26) {
			if (province > 0.35 && r < 0.45) return MetallurgyBlocks.MALACHITE_AZURITE_DEPOSIT;
			if (r < 0.25) return MetallurgyBlocks.NATIVE_COPPER_DEPOSIT;
			if (r < 0.55) return MetallurgyBlocks.MALACHITE_AZURITE_DEPOSIT;
			if (r < 0.78) return MetallurgyBlocks.HEMATITE_MAGNETITE_DEPOSIT;
			return MetallurgyBlocks.CASSITERITE_DEPOSIT;
		} else {
			if (province < -0.25 && r < 0.32) return MetallurgyBlocks.CASSITERITE_DEPOSIT;
			if (r < 0.34) return MetallurgyBlocks.CHALCOPYRITE_DEPOSIT;
			if (r < 0.62) return MetallurgyBlocks.HEMATITE_MAGNETITE_DEPOSIT;
			if (r < 0.80) return MetallurgyBlocks.ARSENOPYRITE_DEPOSIT;
			return MetallurgyBlocks.CASSITERITE_DEPOSIT;
		}
	}

	private static void placeClusterInChunk(WorldChunk chunk, int cx, int cy, int cz, int size,
											  Block depositBlock, Random rng, boolean stoneOnly) {
		int startX = chunk.getPos().getStartX();
		int startZ = chunk.getPos().getStartZ();
		int x = cx, y = cy, z = cz;
		for (int i = 0; i < size; i++) {
			if (x >= startX && x < startX + 16 && z >= startZ && z < startZ + 16
					&& y > chunk.getBottomY() + 1 && y < chunk.getTopY() - 1) {
				BlockPos pos = new BlockPos(x, y, z);
				BlockState existing = chunk.getBlockState(pos);
				boolean valid = stoneOnly
						? (existing.isOf(Blocks.STONE) || existing.isOf(Blocks.DEEPSLATE)
						|| existing.isOf(Blocks.GRANITE) || existing.isOf(Blocks.DIORITE)
						|| existing.isOf(Blocks.ANDESITE) || existing.isOf(Blocks.TUFF)
						|| existing.isOf(Blocks.BASALT))
						: (existing.isOf(Blocks.GRAVEL) || existing.isOf(Blocks.SAND)
						|| existing.isOf(Blocks.CLAY) || existing.isOf(Blocks.DIRT));
				if (valid) {
					chunk.setBlockState(pos, depositBlock.getDefaultState(), false);
				}
			}
			x += rng.nextInt(3) - 1;
			y += rng.nextInt(3) - 1;
			z += rng.nextInt(3) - 1;
		}
	}
}
