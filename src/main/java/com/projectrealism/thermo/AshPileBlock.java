package com.projectrealism.thermo;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;

/**
 * Inorganic wood ash residue left after complete combustion. Can be scooped up with any tool or
 * hand and used for soil fertilization, potash lye, or damping embers.
 */
public class AshPileBlock extends Block {
	public static final IntProperty LAYERS = IntProperty.of("layers", 1, 4);

	private static final VoxelShape[] SHAPES = new VoxelShape[]{
			Block.createCuboidShape(0, 0, 0, 16, 2, 16),
			Block.createCuboidShape(0, 0, 0, 16, 4, 16),
			Block.createCuboidShape(0, 0, 0, 16, 6, 16),
			Block.createCuboidShape(0, 0, 0, 16, 8, 16)
	};

	public AshPileBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(LAYERS, 1));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(LAYERS);
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPES[state.get(LAYERS) - 1];
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		BlockState existing = ctx.getWorld().getBlockState(ctx.getBlockPos());
		if (existing.isOf(this)) {
			int cur = existing.get(LAYERS);
			return existing.with(LAYERS, Math.min(4, cur + 1));
		}
		return getDefaultState();
	}

	@Override
	protected boolean canReplace(BlockState state, ItemPlacementContext context) {
		return context.getStack().isOf(this.asItem()) && state.get(LAYERS) < 4;
	}
}
