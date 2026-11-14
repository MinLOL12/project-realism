package com.projectrealism.wood;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;

/**
 * A standing tree trunk with a carved V-notch facing the direction the tree will fall.
 */
public class NotchedLogBlock extends Block {
	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
	public static final IntProperty STAGE = IntProperty.of("stage", 1, 3);

	private final WoodSpecies species;

	public NotchedLogBlock(Settings settings, WoodSpecies species) {
		super(settings);
		this.species = species;
		setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(STAGE, 1));
	}

	public WoodSpecies species() {
		return species;
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING, STAGE);
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
	}

	@Override
	public ItemStack getPickStack(net.minecraft.world.WorldView world, net.minecraft.util.math.BlockPos pos,
									BlockState state) {
		return new ItemStack(species.vanillaLog());
	}
}
