package com.projectrealism.material;

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
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Open tensioned wooden drying rack exposing hides, raw gourds, or fiber bundles to circulating
 * airflow, ambient humidity, solar radiation, rain, and nearby hearths. Players adjust individual
 * corner cordage tension directly at each corner [NW, NE, SE, SW].
 */
public class DryingRackBlock extends BlockWithEntity {
	public static final MapCodec<DryingRackBlock> CODEC = createCodec(DryingRackBlock::new);
	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
	private static final VoxelShape SHAPE = Block.createCuboidShape(1, 0, 1, 15, 16, 15);

	public DryingRackBlock(Settings settings) {
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
		return new DryingRackBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		return world.isClient ? null : validateTicker(type, MaterialBlocks.DRYING_RACK_ENTITY, DryingRackBlockEntity::tick);
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock())) {
			if (!world.isClient && world.getBlockEntity(pos) instanceof DryingRackBlockEntity be) {
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
		if (!(world.getBlockEntity(pos) instanceof DryingRackBlockEntity be)) {
			return ActionResult.PASS;
		}
		ItemStack held = player.getMainHandStack();
		Vec3d uv = ScrapingFrameBlock.localUV(state.get(FACING), pos, hit.getPos());

		if (player.isSneaking() && held.isEmpty() && be.hasItem()) {
			be.takeItem(player, world, pos);
			return ActionResult.SUCCESS;
		}
		if (!be.hasItem() && (held.isOf(MaterialItems.RAW_HIDE) || held.isOf(MaterialItems.RAW_GOURD)
				|| held.isOf(com.projectrealism.anatomy.AnatomyItems.RAW_BONE))) {
			be.mountItem(held, player);
			return ActionResult.SUCCESS;
		}
		if (be.hasHide()) {
			// Targeted corner tension lacing [0=NW, 1=NE, 2=SE, 3=SW]
			int corner = cornerFromUV(uv.x, uv.z);
			if (held.isOf(com.projectrealism.primitive.PrimitiveItems.CORDAGE) || held.isEmpty()) {
				double delta = player.isSneaking() ? -0.18 : 0.18;
				be.adjustCornerTension(corner, delta, player, world, pos);
				return ActionResult.SUCCESS;
			}
		}
		if (be.hasItem()) {
			player.sendMessage(be.statusText(), true);
			return ActionResult.SUCCESS;
		}
		return ActionResult.PASS;
	}

	private static int cornerFromUV(double u, double v) {
		if (u < 0.5 && v < 0.5) return 0; // NW
		if (u >= 0.5 && v < 0.5) return 1; // NE
		if (u >= 0.5 && v >= 0.5) return 2; // SE
		return 3; // SW
	}
}
