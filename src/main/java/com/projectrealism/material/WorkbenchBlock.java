package com.projectrealism.material;

import com.mojang.serialization.MapCodec;
import com.projectrealism.anatomy.AnatomyItems;
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
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Spatial wooden workbench with 4 persistent surface quadrants so tools, hides, cordage, and
 * vessels remain exactly where placed across work sessions, plus direct gourd vessel carving and sealing.
 */
public class WorkbenchBlock extends BlockWithEntity {
	public static final MapCodec<WorkbenchBlock> CODEC = createCodec(WorkbenchBlock::new);
	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
	private static final VoxelShape SHAPE = Block.createCuboidShape(0, 0, 0, 16, 15, 16);

	public WorkbenchBlock(Settings settings) {
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
		return new WorkbenchBlockEntity(pos, state);
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock())) {
			if (!world.isClient && world.getBlockEntity(pos) instanceof WorkbenchBlockEntity be) {
				be.dropContents(world, pos);
			}
			super.onStateReplaced(state, world, pos, newState, moved);
		}
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (world.isClient) return ActionResult.SUCCESS;
		if (!(world.getBlockEntity(pos) instanceof WorkbenchBlockEntity be)) return ActionResult.PASS;

		double u = hit.getPos().x - pos.getX();
		double v = hit.getPos().z - pos.getZ();
		int slot = (v >= 0.5 ? 2 : 0) + (u >= 0.5 ? 1 : 0);

		ItemStack held = player.getMainHandStack();
		ItemStack slotItem = be.getSlot(slot);

		// Interact with raw bone (carving & abrasive shaping), animal horn, or cured gourd placed on that quadrant!
		if (slotItem.isOf(AnatomyItems.RAW_BONE)) {
			if (held.isOf(PrimitiveItems.STONE_KNIFE) || held.isOf(AnatomyItems.BONE_BLADE)) {
				be.carveBone(slot, player, held, world, pos);
				return ActionResult.SUCCESS;
			}
			if (held.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE)) {
				var s = com.projectrealism.knapping.DynamicStoneItem.readState(held);
				double slice = com.projectrealism.knapping.StoneInteractionPhysics.evaluate(
						s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.BONE_CARVE, 95.0).effectiveness();
				double flat = s.metrics().flatSurfaceAreaCm2();
				if (slice >= 0.45 && s.metrics().primaryEdgeAngleDeg() <= 45.0) {
					be.carveBone(slot, player, held, world, pos);
				} else if (flat >= 12.0 || slice < 0.45) {
					be.abradeBone(slot, player, held, world, pos);
				} else {
					be.carveBone(slot, player, held, world, pos);
				}
				return ActionResult.SUCCESS;
			}
			if (PrimitiveItems.isKnappable(held.getItem()) || held.isOf(PrimitiveItems.SHARP_STONE)
					|| com.projectrealism.knapping.DynamicKnappingItems.isKnappableRawRock(held)) {
				be.abradeBone(slot, player, held, world, pos);
				return ActionResult.SUCCESS;
			}
		}
		if (slotItem.isOf(AnatomyItems.ANIMAL_HORN)
				&& (held.isOf(MaterialItems.BONE_AWL) || held.isOf(PrimitiveItems.STONE_KNIFE)
				|| held.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE))) {
			be.carveHornFlask(slot, player, held, world, pos);
			return ActionResult.SUCCESS;
		}
		if (slotItem.isOf(MaterialItems.RAW_GOURD)
				&& (held.isOf(MaterialItems.BONE_AWL) || held.isOf(PrimitiveItems.STONE_KNIFE)
				|| held.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE))) {
			be.carveGourd(slot, player, held, world, pos);
			return ActionResult.SUCCESS;
		}
		if (slotItem.isOf(MaterialItems.GOURD_FLASK)
				&& (held.isOf(MaterialItems.PINE_RESIN) || held.isOf(MaterialItems.TALLOW_WAX))) {
			be.sealGourdCrack(slot, player, held, world, pos);
			return ActionResult.SUCCESS;
		}

		if (slotItem.isEmpty() && !held.isEmpty()) {
			be.placeItem(slot, held, player, world, pos);
			return ActionResult.SUCCESS;
		}
		if (!slotItem.isEmpty() && (held.isEmpty() || player.isSneaking())) {
			be.takeItem(slot, player, world, pos);
			return ActionResult.SUCCESS;
		}
		return ActionResult.PASS;
	}
}
