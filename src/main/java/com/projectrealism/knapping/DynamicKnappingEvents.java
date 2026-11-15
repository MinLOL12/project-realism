package com.projectrealism.knapping;

import com.projectrealism.ProjectRealism;
import com.projectrealism.anatomy.CarcassBlock;
import com.projectrealism.anatomy.CarcassItem;
import com.projectrealism.metallurgy.DynamicMetalItem;
import com.projectrealism.primitive.GroundRockBlock;
import com.projectrealism.primitive.RockDropBlock;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Mixin-free event hooks placing raw collected geological stones onto solid ground as real
 * 16x16x16 voxel rocks for dynamic knapping, routing sneak precision strikes, blocking rock/block
 * towering via Shift+Right-Click, and yielding nodules from mined geological strata.
 */
public final class DynamicKnappingEvents {
	private DynamicKnappingEvents() {
	}

	private static boolean isPlaceableStackItem(ItemStack held) {
		return held.getItem() instanceof BlockItem
				|| held.getItem() instanceof DynamicStoneItem
				|| DynamicKnappingItems.isKnappableRawRock(held)
				|| held.getItem() instanceof DynamicMetalItem
				|| held.getItem() instanceof CarcassItem;
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

		// Handle knapping precision strikes, anti-towering placement guards, and raw rock ground placement
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (!ProjectRealism.config().dynamicKnapping || player.isSpectator()) {
				return ActionResult.PASS;
			}
			ItemStack held = player.getStackInHand(hand);
			BlockPos clicked = hit.getBlockPos();
			BlockState clickedState = world.getBlockState(clicked);

			// 1. Existing KnappingStoneBlock: route Shift+Right-Click striker blows to precision flaking
			//    and block Shift+Right-Click block/rock stacking on top of rocks.
			if (clickedState.getBlock() instanceof KnappingStoneBlock) {
				if (player.isSneaking()) {
					if (hand == Hand.MAIN_HAND && DynamicKnappingItems.isStriker(held)) {
						if (world instanceof ServerWorld && world.getBlockEntity(clicked) instanceof KnappingStoneBlockEntity be) {
							be.strikeWithRock(clickedState.get(KnappingStoneBlock.FACING), player, hand, held, hit.getPos(), world, clicked);
						}
						return ActionResult.SUCCESS;
					}
					if (!held.isEmpty() && isPlaceableStackItem(held)) {
						return ActionResult.FAIL;
					}
				} else if (held.getItem() instanceof BlockItem) {
					return ActionResult.FAIL;
				}
				return ActionResult.PASS;
			}

			// 2. Ground pebbles, fallen sticks, and non-stackable world objects: forbid stacking blocks/rocks on top
			if (clickedState.getBlock() instanceof GroundRockBlock
					|| clickedState.getBlock() instanceof RockDropBlock
					|| clickedState.getBlock() instanceof CarcassBlock) {
				if (isPlaceableStackItem(held)) {
					return ActionResult.FAIL;
				}
				return ActionResult.PASS;
			}

			if (!DynamicKnappingItems.isKnappableRawRock(held)) {
				return ActionResult.PASS;
			}
			// Raw rocks may only be placed cleanly flat onto full top faces of solid non-entity blocks
			if (clickedState.hasBlockEntity() || hit.getSide() != Direction.UP) {
				return ActionResult.PASS;
			}
			BlockPos placePos = clicked.up();
			BlockState above = world.getBlockState(placePos);
			if (!above.isAir() && !above.isReplaceable()) {
				return ActionResult.PASS;
			}
			if (!clickedState.isSideSolidFullSquare(world, clicked, Direction.UP)) {
				return ActionResult.PASS;
			}
			BlockState placed = DynamicKnappingBlocks.KNAPPING_STONE.getDefaultState()
					.with(KnappingStoneBlock.FACING, player.getHorizontalFacing().getOpposite());
			if (!placed.canPlaceAt(world, placePos)) {
				return ActionResult.PASS;
			}

			if (world instanceof ServerWorld sw) {
				StoneMaterial mat = DynamicKnappingItems.materialOfItem(held);
				if (mat == null) mat = StoneMaterial.FLINT;
				long seed = sw.random.nextLong();
				DynamicStoneState state = DynamicStoneState.createCobble(mat, seed, 0.74 + sw.random.nextDouble() * 0.14);
				sw.setBlockState(placePos, placed);
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
