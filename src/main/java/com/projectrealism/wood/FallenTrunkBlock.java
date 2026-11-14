package com.projectrealism.wood;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

/**
 * A felled trunk lying horizontally on the ground. Players must remove branches with an axe before
 * cutting the trunk into manageable log sections.
 */
public class FallenTrunkBlock extends Block {
	public static final EnumProperty<Direction.Axis> AXIS = Properties.AXIS;
	public static final BooleanProperty BRANCHED = BooleanProperty.of("branched");

	private static final VoxelShape SHAPE_X = Block.createCuboidShape(0, 0, 2, 16, 12, 14);
	private static final VoxelShape SHAPE_Z = Block.createCuboidShape(2, 0, 0, 14, 12, 16);
	private static final VoxelShape SHAPE_Y = Block.createCuboidShape(2, 0, 2, 14, 16, 14);

	private final WoodSpecies species;

	public FallenTrunkBlock(Settings settings, WoodSpecies species) {
		super(settings);
		this.species = species;
		setDefaultState(getDefaultState().with(AXIS, Direction.Axis.Z).with(BRANCHED, true));
	}

	public WoodSpecies species() {
		return species;
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(AXIS, BRANCHED);
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return switch (state.get(AXIS)) {
			case X -> SHAPE_X;
			case Y -> SHAPE_Y;
			case Z -> SHAPE_Z;
		};
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		return getDefaultState().with(AXIS, ctx.getSide().getAxis());
	}

	@Override
	public ItemStack getPickStack(WorldView world, BlockPos pos, BlockState state) {
		return new ItemStack(species.freshLogItem());
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (!world.isClient) {
			if (state.get(BRANCHED)) {
				player.sendMessage(Text.translatable("project_realism.wood.needs_debranch", species.displayName())
						.formatted(Formatting.GRAY), true);
			} else {
				player.sendMessage(Text.translatable("project_realism.wood.needs_bucking", species.displayName())
						.formatted(Formatting.GRAY), true);
			}
		}
		return ActionResult.SUCCESS;
	}
}
