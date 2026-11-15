package com.projectrealism.knapping;

import com.projectrealism.ProjectRealism;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Mixin-free event hooks placing raw collected geological stones onto solid ground as real
 * 16x16x16 voxel rocks for dynamic knapping, and yielding nodules from mined geological strata.
 */
public final class DynamicKnappingEvents {
	private DynamicKnappingEvents() {
	}

	public static void register() {
		// Mining obsidian, basalt, deepslate, or gravel occasionally frees intact knappable nodules
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, entity) -> {
			if (!(world instanceof ServerWorld sw) || player.isCreative()) return;
			if (!ProjectRealism.config().dynamicKnapping) return;
			if (state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.CRYING_OBSIDIAN)) {
				Block.dropStack(sw, pos, new ItemStack(DynamicKnappingItems.OBSIDIAN_NODULE, 1 + sw.random.nextInt(2)));
			} else if ((state.isOf(Blocks.BASALT) || state.isOf(Blocks.SMOOTH_BASALT)) && sw.random.nextFloat() < 0.35f) {
				Block.dropStack(sw, pos, new ItemStack(DynamicKnappingItems.BASALT_ROCK, 1));
			} else if (state.isOf(Blocks.DEEPSLATE) && sw.random.nextFloat() < 0.14f) {
				Block.dropStack(sw, pos, new ItemStack(DynamicKnappingItems.CHERT_ROCK, 1));
			} else if (state.isOf(Blocks.DIORITE) && sw.random.nextFloat() < 0.18f) {
				Block.dropStack(sw, pos, new ItemStack(DynamicKnappingItems.QUARTZITE_ROCK, 1));
			}
		});

		// Right-clicking any solid surface with a raw rock pickup places it down as a KnappingStoneBlock!
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (!ProjectRealism.config().dynamicKnapping || player.isSpectator()) {
				return ActionResult.PASS;
			}
			ItemStack held = player.getStackInHand(hand);
			if (!DynamicKnappingItems.isKnappableRawRock(held)) {
				return ActionResult.PASS;
			}
			BlockPos clicked = hit.getBlockPos();
			BlockState clickedState = world.getBlockState(clicked);
			// Never intercept clicking an existing KnappingStoneBlock (used to strike!) or workstation
			if (clickedState.getBlock() instanceof KnappingStoneBlock || clickedState.hasBlockEntity()) {
				return ActionResult.PASS;
			}
			if (hit.getSide() != Direction.UP) {
				return ActionResult.PASS;
			}
			BlockPos placePos = clicked.up();
			BlockState above = world.getBlockState(placePos);
			if (!above.isAir() && !above.isReplaceable()) {
				return ActionResult.PASS;
			}
			if (!clickedState.isSolidBlock(world, clicked) && !clickedState.isSideSolidFullSquare(world, clicked, Direction.UP)) {
				return ActionResult.PASS;
			}

			if (world instanceof ServerWorld sw) {
				StoneMaterial mat = DynamicKnappingItems.materialOfItem(held);
				if (mat == null) mat = StoneMaterial.FLINT;
				long seed = sw.random.nextLong();
				DynamicStoneState state = DynamicStoneState.createCobble(mat, seed, 0.85 + sw.random.nextDouble() * 0.14);
				sw.setBlockState(placePos, DynamicKnappingBlocks.KNAPPING_STONE.getDefaultState()
						.with(KnappingStoneBlock.FACING, player.getHorizontalFacing().getOpposite()));
				if (sw.getBlockEntity(placePos) instanceof KnappingStoneBlockEntity be) {
					be.setStone(state);
				}
				sw.playSound(null, placePos, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 0.65f, 1.0f);
				if (!player.isCreative()) {
					held.decrement(1);
				}
			}
			return ActionResult.SUCCESS;
		});
	}
}
