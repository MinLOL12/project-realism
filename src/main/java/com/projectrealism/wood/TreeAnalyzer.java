package com.projectrealism.wood;

import com.projectrealism.primitive.ToolCondition;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Scans connected logs and canopy leaves above a chop position to classify a standing tree,
 * distinguish natural trees from player-built log structures, and compute chop effort.
 */
public final class TreeAnalyzer {
	private static final int MAX_LOGS = 96;
	private static final int MAX_LEAVES_SCAN = 160;

	public record TreeData(
			WoodSpecies species,
			List<BlockPos> logs,
			List<BlockPos> leaves,
			int trunkHeight,
			boolean thickTrunk
	) {
		public int totalLogCount() {
			return logs.size();
		}
	}

	private TreeAnalyzer() {
	}

	public static Optional<TreeData> analyze(World world, BlockPos cutPos) {
		BlockState cutState = world.getBlockState(cutPos);
		Optional<WoodSpecies> maybeSpecies = WoodSpecies.fromBlock(cutState.getBlock());
		if (maybeSpecies.isEmpty()) {
			return Optional.empty();
		}

		boolean alreadyNotched = cutState.getBlock() instanceof NotchedLogBlock;
		if (!alreadyNotched && !cutState.isIn(BlockTags.LOGS)) {
			return Optional.empty();
		}

		WoodSpecies species = maybeSpecies.get();
		Set<BlockPos> logSet = new HashSet<>();
		List<BlockPos> logs = new ArrayList<>();
		Deque<BlockPos> queue = new ArrayDeque<>();

		logSet.add(cutPos);
		logs.add(cutPos);
		queue.add(cutPos);

		int minY = cutPos.getY();
		int maxY = cutPos.getY();

		while (!queue.isEmpty() && logs.size() < MAX_LOGS) {
			BlockPos cur = queue.poll();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = 0; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx == 0 && dy == 0 && dz == 0) {
							continue;
						}
						BlockPos next = cur.add(dx, dy, dz);
						if (next.getY() < minY || next.getY() > cutPos.getY() + 28) {
							continue;
						}
						if (Math.abs(next.getX() - cutPos.getX()) > 7 || Math.abs(next.getZ() - cutPos.getZ()) > 7) {
							continue;
						}
						if (logSet.contains(next)) {
							continue;
						}
						BlockState ns = world.getBlockState(next);
						if (isTreeLog(ns.getBlock(), species)) {
							logSet.add(next);
							logs.add(next);
							queue.add(next);
							if (next.getY() > maxY) {
								maxY = next.getY();
							}
						}
					}
				}
			}
		}

		// Collect nearby canopy leaves attached to the scanned logs
		Set<BlockPos> leafSet = new HashSet<>();
		List<BlockPos> leaves = new ArrayList<>();
		Deque<BlockPos> leafQueue = new ArrayDeque<>();
		for (BlockPos lp : logs) {
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						BlockPos np = lp.add(dx, dy, dz);
						if (!leafSet.contains(np) && world.getBlockState(np).isIn(BlockTags.LEAVES)) {
							leafSet.add(np);
							leaves.add(np);
							leafQueue.add(np);
						}
					}
				}
			}
		}
		while (!leafQueue.isEmpty() && leaves.size() < MAX_LEAVES_SCAN) {
			BlockPos cur = leafQueue.poll();
			for (int dx = -1; dx <= 1; dx++) {
				for (int dy = -1; dy <= 1; dy++) {
					for (int dz = -1; dz <= 1; dz++) {
						if (dx == 0 && dy == 0 && dz == 0) {
							continue;
						}
						BlockPos np = cur.add(dx, dy, dz);
						if (leafSet.contains(np)) {
							continue;
						}
						if (Math.abs(np.getX() - cutPos.getX()) > 7 || Math.abs(np.getZ() - cutPos.getZ()) > 7
								|| np.getY() < cutPos.getY()) {
							continue;
						}
						if (world.getBlockState(np).isIn(BlockTags.LEAVES)) {
							leafSet.add(np);
							leaves.add(np);
							leafQueue.add(np);
						}
					}
				}
			}
		}

		// Require either a canopy or an already-notched trunk so player log cabins don't topple
		if (!alreadyNotched && (logs.size() < 3 || leaves.size() < 4)) {
			return Optional.empty();
		}

		int trunkHeight = Math.max(2, (maxY - minY) + 1);
		boolean thickTrunk = hasAdjacentTrunkLog(logSet, cutPos);
		return Optional.of(new TreeData(species, logs, leaves, trunkHeight, thickTrunk));
	}

	private static boolean isTreeLog(Block block, WoodSpecies species) {
		return (block instanceof NotchedLogBlock notched && notched.species() == species)
				|| (WoodSpecies.fromBlock(block).map(s -> s == species).orElse(false)
						&& !(block instanceof FallenTrunkBlock)
						&& !(block instanceof FreshLogBlock)
						&& !(block instanceof SplitWoodBlock)
						&& !(block instanceof RoughPlankBlock));
	}

	private static boolean hasAdjacentTrunkLog(Set<BlockPos> logSet, BlockPos cutPos) {
		int adjacent = 0;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if ((dx != 0 || dz != 0) && logSet.contains(cutPos.add(dx, 0, dz))) {
					adjacent++;
				}
			}
		}
		return adjacent >= 2;
	}

	public static int requiredChops(TreeData data, ItemStack tool) {
		double speedMult = ToolCondition.speedMultiplier(tool);
		if (tool.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE)) {
			var s = com.projectrealism.knapping.DynamicStoneItem.readState(tool);
			speedMult = com.projectrealism.knapping.StoneInteractionPhysics.evaluate(
					s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.WOOD_CHOP, 140.0)
					.speedMultiplier();
		}
		double base = 2.0 + (data.totalLogCount() / 3.0) + (data.thickTrunk() ? 4.0 : 0.0);
		double scaled = base * data.species().processingFactor() / Math.max(0.25, speedMult);
		return Math.max(2, Math.min(24, (int) Math.ceil(scaled)));
	}
}
