package com.projectrealism.metallurgy;

import com.mojang.serialization.MapCodec;
import com.projectrealism.anatomy.AnatomyItems;
import com.projectrealism.knapping.DynamicKnappingItems;
import com.projectrealism.knapping.KnappingStoneBlock;
import com.projectrealism.primitive.GroundRockBlock;
import com.projectrealism.primitive.PrimitiveItems;
import com.projectrealism.primitive.RockDropBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;
import org.jetbrains.annotations.Nullable;

/**
 * In-world 16x16x16 volumetric metal body placed on a flat stone/anvil surface for dynamic
 * plastic hammering, rolling/flipping, annealing checks, and wooden handle hafting.
 */
public class PlacedMetalBlock extends BlockWithEntity {
	public static final MapCodec<PlacedMetalBlock> CODEC = createCodec(PlacedMetalBlock::new);
	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
	private static final VoxelShape SHAPE = Block.createCuboidShape(1, 0, 1, 15, 13, 15);

	public PlacedMetalBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(FACING, Direction.NORTH));
	}

	@Override
	protected MapCodec<? extends BlockWithEntity> getCodec() {
		return CODEC;
	}

	@Override
	protected BlockRenderType getRenderType(BlockState state) {
		return BlockRenderType.ENTITYBLOCK_ANIMATED;
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPE;
	}

	@Override
	protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
		BlockPos down = pos.down();
		BlockState below = world.getBlockState(down);
		if (below.getBlock() instanceof PlacedMetalBlock
				|| below.getBlock() instanceof KnappingStoneBlock
				|| below.getBlock() instanceof GroundRockBlock
				|| below.getBlock() instanceof RockDropBlock
				|| below.hasBlockEntity()) {
			return false;
		}
		return below.isSideSolidFullSquare(world, down, Direction.UP);
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
	}

	@Nullable
	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new PlacedMetalBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		return world.isClient ? null : validateTicker(type, MetallurgyBlocks.PLACED_METAL_ENTITY,
				(w, p, s, be) -> be.serverTick(w, p));
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock())) {
			if (!world.isClient && world.getBlockEntity(pos) instanceof PlacedMetalBlockEntity be) {
				be.dropOnBreak(world, pos);
			}
			super.onStateReplaced(state, world, pos, newState, moved);
		}
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (world.isClient) return ActionResult.SUCCESS;
		if (!(world.getBlockEntity(pos) instanceof PlacedMetalBlockEntity be)) return ActionResult.PASS;

		ItemStack held = player.getMainHandStack();
		Direction facing = state.get(FACING);

		// 1. Bare hand or tongs: inspect / rotate / pick up
		if (held.isEmpty() || held.isOf(MetallurgyItems.IRON_TONGS)) {
			if (player.isSneaking()) {
				if (hit.getSide() == Direction.UP || hit.getSide() == Direction.DOWN) {
					be.pickUp(player, world, pos);
				} else {
					be.rotateFace(hit.getSide(), player, world);
				}
			} else {
				be.inspect(player);
			}
			return ActionResult.SUCCESS;
		}

		// 2. Striking with stone or heavy metal hammer -> plastic forging deformation!
		if (DynamicKnappingItems.isStriker(held) || held.isOf(MetallurgyItems.DYNAMIC_METAL)) {
			be.hammerStrike(facing, player, held, hit.getPos(), world, pos);
			return ActionResult.SUCCESS;
		}

		// 3. Attaching a wooden handle
		if (held.isOf(Items.STICK)) {
			be.attachStick(player, held, world, pos);
			return ActionResult.SUCCESS;
		}

		// 4. Wrapping fiber/cordage/sinew binding around metal-stick junction
		if (held.isOf(PrimitiveItems.PLANT_FIBER) || held.isOf(PrimitiveItems.CORDAGE)
				|| held.isOf(AnatomyItems.SINEW) || held.isOf(AnatomyItems.TENDON)) {
			be.bindHandle(player, held, world, pos);
			return ActionResult.SUCCESS;
		}

		// 5. Honing with flint/chert/quartzite directly on the anvil
		if (held.isOf(DynamicKnappingItems.QUARTZITE_ROCK) || held.isOf(DynamicKnappingItems.CHERT_ROCK)) {
			be.honeEdge(player, world, pos);
			return ActionResult.SUCCESS;
		}

		return ActionResult.PASS;
	}
}
