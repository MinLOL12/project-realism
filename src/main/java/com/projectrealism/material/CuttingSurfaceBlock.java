package com.projectrealism.material;

import com.mojang.serialization.MapCodec;
import com.projectrealism.primitive.PrimitiveItems;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
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
import org.jetbrains.annotations.Nullable;

/**
 * Flat timber cutting slab supporting physical hides for positioning, rotation, and direct
 * knife-path cutting into shaped {@link LeatherPieceItem} panels and {@link MaterialItems#HIDE_SCRAP}.
 */
public class CuttingSurfaceBlock extends BlockWithEntity {
	public static final MapCodec<CuttingSurfaceBlock> CODEC = createCodec(CuttingSurfaceBlock::new);
	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
	private static final VoxelShape SHAPE = Block.createCuboidShape(0, 0, 0, 16, 12, 16);

	public CuttingSurfaceBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(FACING, Direction.NORTH));
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
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING);
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPE;
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
	}

	@Nullable
	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new CuttingSurfaceBlockEntity(pos, state);
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock())) {
			if (!world.isClient && world.getBlockEntity(pos) instanceof CuttingSurfaceBlockEntity be) {
				be.dropContents(world, pos);
			}
			super.onStateReplaced(state, world, pos, newState, moved);
		}
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (world.isClient) {
			return ActionResult.SUCCESS;
		}
		if (!(world.getBlockEntity(pos) instanceof CuttingSurfaceBlockEntity be)) {
			return ActionResult.PASS;
		}
		ItemStack held = player.getMainHandStack();
		double rawU = Math.max(0.02, Math.min(0.98, hit.getPos().x - pos.getX()));
		double rawV = Math.max(0.02, Math.min(0.98, hit.getPos().z - pos.getZ()));

		if (!be.hasHide() && held.isOf(MaterialItems.RAW_HIDE)) {
			be.placeHide(held, player);
			return ActionResult.SUCCESS;
		}
		if (be.hasHide() && player.isSneaking() && held.isEmpty()) {
			be.takeHide(player, world, pos);
			return ActionResult.SUCCESS;
		}
		if (be.hasHide() && !player.isSneaking() && held.isEmpty()) {
			be.rotateAndInspect(player, world, pos);
			return ActionResult.SUCCESS;
		}
		if (be.hasHide() && (held.isOf(PrimitiveItems.STONE_KNIFE) || held.isOf(com.projectrealism.anatomy.AnatomyItems.BONE_BLADE))) {
			if (player.isSneaking()) {
				be.separateCutPiece(player, Hand.MAIN_HAND, held, world, pos);
			} else {
				be.cutTo(player, Hand.MAIN_HAND, held, rawU, rawV, world, pos);
			}
			return ActionResult.SUCCESS;
		}
		return ActionResult.PASS;
	}
}
