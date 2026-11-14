package com.projectrealism.wood;

import com.projectrealism.ProjectRealism;
import com.projectrealism.primitive.ToolCondition;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Progressive tree notching and physical felling.
 *
 * <p>Chopping a standing tree trunk carves a visible notch on the struck face across multiple strikes.
 * Larger and harder trees require significantly more chops, dull axes take longer, and when the
 * final hinge gives way the tree falls physically in the direction of the notch.</p>
 */
public final class TreeChopper {
	private static final Map<UUID, Direction> LAST_HIT_FACE = new ConcurrentHashMap<>();
	private static final Map<RegistryKey<World>, Map<BlockPos, Integer>> CHOP_PROGRESS = new ConcurrentHashMap<>();

	private TreeChopper() {
	}

	public static void register() {
		AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
			if (!world.isClient && direction.getAxis().isHorizontal()) {
				LAST_HIT_FACE.put(player.getUuid(), direction);
			}
			return ActionResult.PASS;
		});

		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) -> {
			if (!(world instanceof ServerWorld server) || player.isCreative() || player.isSpectator()) {
				return true;
			}
			if (!ProjectRealism.config().physicalTreeChopping) {
				return true;
			}

			if (!(state.isIn(BlockTags.LOGS) || state.getBlock() instanceof NotchedLogBlock)) {
				return true;
			}

			ItemStack tool = player.getMainHandStack();
			if (!isAxe(tool)) {
				return true;
			}

			Optional<TreeAnalyzer.TreeData> maybeTree = TreeAnalyzer.analyze(server, pos);
			if (maybeTree.isEmpty()) {
				return true;
			}

			TreeAnalyzer.TreeData tree = maybeTree.get();
			int required = TreeAnalyzer.requiredChops(tree, tool);

			Direction notchDir = resolveNotchDirection(state, player.getUuid(), player.getHorizontalFacing());
			Map<BlockPos, Integer> worldProgress = CHOP_PROGRESS.computeIfAbsent(
					server.getRegistryKey(), k -> new ConcurrentHashMap<>());
			int newChops = worldProgress.getOrDefault(pos, 0) + 1;

			ToolCondition.applyWear(player, Hand.MAIN_HAND, tool, tree.species().hardness(), server, true);

			if (newChops < required) {
				worldProgress.put(pos, newChops);
				float ratio = newChops / (float) required;
				int stage = ratio < 0.38f ? 1 : (ratio < 0.72f ? 2 : 3);

				BlockState notched = tree.species().notchedLogBlock().getDefaultState()
						.with(NotchedLogBlock.FACING, notchDir)
						.with(NotchedLogBlock.STAGE, stage);
				server.setBlockState(pos, notched, Block.NOTIFY_ALL);

				server.playSound(null, pos, SoundEvents.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 0.9f, 0.85f);
				server.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, state),
						pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
						8, 0.25, 0.25, 0.25, 0.05);

				player.sendMessage(Text.translatable("project_realism.tree.notching",
								tree.species().displayName(), newChops, required, notchDir.asString())
						.formatted(Formatting.YELLOW), true);
				return false;
			}

			worldProgress.remove(pos);
			FallingTreeManager.spawnFallingTree(server, pos, notchDir, tree.species(), tree);
			player.sendMessage(Text.translatable("project_realism.tree.timber",
							tree.species().displayName(), notchDir.asString())
					.formatted(Formatting.GOLD), true);
			return false;
		});
	}

	private static Direction resolveNotchDirection(BlockState state, UUID playerId, Direction fallbackFacing) {
		Direction struck = LAST_HIT_FACE.get(playerId);
		if (struck != null && struck.getAxis().isHorizontal()) {
			return struck;
		}
		if (state.getBlock() instanceof NotchedLogBlock) {
			return state.get(NotchedLogBlock.FACING);
		}
		return fallbackFacing;
	}

	private static boolean isAxe(ItemStack stack) {
		return !stack.isEmpty() && (stack.isIn(ItemTags.AXES) || stack.getItem() instanceof AxeItem);
	}

	public static void forget(UUID uuid) {
		LAST_HIT_FACE.remove(uuid);
	}
}
