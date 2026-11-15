package com.projectrealism.metallurgy;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * In-world ceramic melting crucible supporting loading mixed metal/ore/carbon charges, slag skimming,
 * thermal equilibration, and lifting into {@link ClayCrucibleItem} preserving full state.
 */
public class ClayCrucibleBlock extends BlockWithEntity {
	public static final MapCodec<ClayCrucibleBlock> CODEC = createCodec(ClayCrucibleBlock::new);
	private static final VoxelShape SHAPE = Block.createCuboidShape(4, 0, 4, 12, 10, 12);

	public ClayCrucibleBlock(Settings settings) {
		super(settings);
	}

	@Override
	protected MapCodec<? extends BlockWithEntity> getCodec() {
		return CODEC;
	}

	@Override
	protected BlockRenderType getRenderType(BlockState state) {
		return BlockRenderType.MODEL;
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPE;
	}

	@Nullable
	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new ClayCrucibleBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		return world.isClient ? null : validateTicker(type, MetallurgyBlocks.CLAY_CRUCIBLE_ENTITY,
				(w, p, s, be) -> be.serverTick(w, p));
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock())) {
			if (!world.isClient && world.getBlockEntity(pos) instanceof ClayCrucibleBlockEntity be) {
				be.dropOnBreak(world, pos);
			}
			super.onStateReplaced(state, world, pos, newState, moved);
		}
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (world.isClient) return ActionResult.SUCCESS;
		if (!(world.getBlockEntity(pos) instanceof ClayCrucibleBlockEntity be)) return ActionResult.PASS;

		ItemStack held = player.getMainHandStack();
		if (held.isEmpty() || held.isOf(MetallurgyItems.IRON_TONGS)) {
			if (player.isSneaking()) {
				be.pickUp(player, world, pos);
			} else {
				be.inspect(player);
			}
			return ActionResult.SUCCESS;
		}
		if (held.isOf(MetallurgyItems.DYNAMIC_METAL)) {
			be.addMetal(player, held, world, pos);
			return ActionResult.SUCCESS;
		}
		if (held.isOf(Items.CHARCOAL) || held.isOf(Items.STICK)) {
			be.interactWithStickOrCarbon(player, held, world, pos);
			return ActionResult.SUCCESS;
		}
		return ActionResult.PASS;
	}
}
