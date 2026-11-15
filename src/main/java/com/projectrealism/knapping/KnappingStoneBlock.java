package com.projectrealism.knapping;

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
import net.minecraft.item.Items;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * In-world 16x16x16 voxel rock supporting voxel-accurate raycast knapping, rolling/rotating to
 * reach every face, bare-hand inspection, handle hafting, and binding wrapping.
 */
public class KnappingStoneBlock extends BlockWithEntity {
	public static final MapCodec<KnappingStoneBlock> CODEC = createCodec(KnappingStoneBlock::new);
	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
	private static final VoxelShape SHAPE = Block.createCuboidShape(1, 0, 1, 15, 13, 15);

	public KnappingStoneBlock(Settings settings) {
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
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		return getDefaultState().with(FACING, ctx.getHorizontalPlayerFacing().getOpposite());
	}

	@Nullable
	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new KnappingStoneBlockEntity(pos, state);
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock())) {
			if (!world.isClient && world.getBlockEntity(pos) instanceof KnappingStoneBlockEntity be) {
				be.dropOnBreak(world, pos);
			}
			super.onStateReplaced(state, world, pos, newState, moved);
		}
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (world.isClient) return ActionResult.SUCCESS;
		if (!(world.getBlockEntity(pos) instanceof KnappingStoneBlockEntity be)) return ActionResult.PASS;

		ItemStack held = player.getMainHandStack();
		Direction facing = state.get(FACING);

		// 1. Bare hand: inspect targeted voxel or sneak-roll / sneak-pick-up
		if (held.isEmpty()) {
			if (player.isSneaking()) {
				if (hit.getSide() == Direction.UP || hit.getSide() == Direction.DOWN) {
					be.pickUp(player, world, pos);
				} else {
					be.rotateFace(hit.getSide(), player, world, pos);
				}
			} else {
				be.inspectAt(facing, player, hit.getPos(), pos);
			}
			return ActionResult.SUCCESS;
		}

		// 2. Striking with another rock / hammerstone -> voxel-level physical fracture simulation!
		if (DynamicKnappingItems.isStriker(held)) {
			be.strikeWithRock(facing, player, Hand.MAIN_HAND, held, hit.getPos(), world, pos);
			return ActionResult.SUCCESS;
		}

		// 3. Attaching a wooden handle directly onto the shaped stone
		if (held.isOf(Items.STICK)) {
			be.attachStick(player, Hand.MAIN_HAND, held, world, pos);
			return ActionResult.SUCCESS;
		}

		// 4. Wrapping natural fiber / cordage / sinew / tendon binding onto the stone-wood junction
		if (held.isOf(PrimitiveItems.PLANT_FIBER) || held.isOf(PrimitiveItems.CORDAGE)
				|| held.isOf(AnatomyItems.SINEW) || held.isOf(AnatomyItems.TENDON)) {
			be.wrapBinding(player, Hand.MAIN_HAND, held, world, pos);
			return ActionResult.SUCCESS;
		}

		return ActionResult.PASS;
	}

	/**
	 * Inverse-rotates a world vector relative to block center into voxel [0..1]^3 space matching
	 * {@code KnappingBlockRenderers}.
	 */
	public static Vec3d worldToVoxelPoint(Direction facing, BlockPos pos, Vec3d worldPt) {
		double wx = worldPt.x - pos.getX() - 0.5;
		double wy = worldPt.y - pos.getY();
		double wz = worldPt.z - pos.getZ() - 0.5;
		return switch (facing) {
			case SOUTH -> new Vec3d(-wx + 0.5, wy, -wz + 0.5);
			case WEST  -> new Vec3d(-wz + 0.5, wy, wx + 0.5);
			case EAST  -> new Vec3d(wz + 0.5, wy, -wx + 0.5);
			default    -> new Vec3d(wx + 0.5, wy, wz + 0.5);
		};
	}

	public static Vec3d worldDirToVoxelDir(Direction facing, Vec3d dir) {
		return switch (facing) {
			case SOUTH -> new Vec3d(-dir.x, dir.y, -dir.z);
			case WEST  -> new Vec3d(-dir.z, dir.y, dir.x);
			case EAST  -> new Vec3d(dir.z, dir.y, -dir.x);
			default    -> dir;
		};
	}

	public static Vec3d voxelToWorldPoint(Direction facing, BlockPos pos, double vx, double vy, double vz) {
		double lx = vx - 0.5;
		double lz = vz - 0.5;
		double wx, wz;
		switch (facing) {
			case SOUTH -> { wx = -lx; wz = -lz; }
			case WEST  -> { wx = lz;  wz = -lx; }
			case EAST  -> { wx = -lz; wz = lx;  }
			default    -> { wx = lx;  wz = lz;  }
		}
		return new Vec3d(pos.getX() + 0.5 + wx, pos.getY() + vy, pos.getZ() + 0.5 + wz);
	}
}
