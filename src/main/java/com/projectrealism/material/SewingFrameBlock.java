package com.projectrealism.material;

import com.mojang.serialization.MapCodec;
import com.projectrealism.hydration.HydrationItems;
import com.projectrealism.hydration.WaterskinItem;
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
 * Vertical wooden leather stitching clamp holding two cut pieces (or an existing leaking
 * waterskin for individual seam repair) for physical alignment, awl punching, needle stitching,
 * cordage tensioning, and tallow/resin seam sealing.
 */
public class SewingFrameBlock extends BlockWithEntity {
	public static final MapCodec<SewingFrameBlock> CODEC = createCodec(SewingFrameBlock::new);
	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
	private static final VoxelShape SHAPE = Block.createCuboidShape(2, 0, 2, 14, 15, 14);

	public SewingFrameBlock(Settings settings) {
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
		return new SewingFrameBlockEntity(pos, state);
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock())) {
			if (!world.isClient && world.getBlockEntity(pos) instanceof SewingFrameBlockEntity be) {
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
		if (!(world.getBlockEntity(pos) instanceof SewingFrameBlockEntity be)) {
			return ActionResult.PASS;
		}
		ItemStack held = player.getMainHandStack();
		Vec3d uv = ScrapingFrameBlock.localUV(state.get(FACING), pos, hit.getPos());
		int seamIdx = seamFromUV(uv.x, uv.z);

		// Sneak + empty hand: remove assembled waterskin or clamped pieces
		if (player.isSneaking() && held.isEmpty()) {
			be.takeResult(player, world, pos);
			return ActionResult.SUCCESS;
		}

		// Clamp cut leather pieces OR mount an existing empty waterskin to repair specific seams!
		if (held.isOf(MaterialItems.LEATHER_PIECE) && !be.hasConstruction()) {
			be.addLeatherPiece(held, player, world, pos);
			return ActionResult.SUCCESS;
		}
		if (held.isOf(HydrationItems.WATERSKIN) && WaterskinItem.getVolumeMl(held) <= 0
				&& !be.hasPendingPiece() && !be.hasConstruction()) {
			be.mountWaterskinForRepair(held, player);
			return ActionResult.SUCCESS;
		}

		if (be.hasConstruction()) {
			// Empty hand adjusts alignment OR inspects specific seam
			if (held.isEmpty()) {
				be.nudgeAlignment(uv.x < 0.5 ? -0.14 : 0.14, seamIdx, player, world, pos);
				return ActionResult.SUCCESS;
			}
			// Bone Awl punches holes along targeted seam
			if (held.isOf(MaterialItems.BONE_AWL)) {
				be.punchHole(seamIdx, player, Hand.MAIN_HAND, held, world, pos);
				return ActionResult.SUCCESS;
			}
			// Bone Needle threads & tensions stitches along targeted seam
			if (held.isOf(MaterialItems.BONE_NEEDLE)) {
				be.stitchOrTighten(seamIdx, player, Hand.MAIN_HAND, held, world, pos);
				return ActionResult.SUCCESS;
			}
			// Sealants (Tallow Wax, Pine Resin, Honeycomb) seal finished seam
			if (held.isOf(MaterialItems.TALLOW_WAX) || held.isOf(MaterialItems.PINE_RESIN)
					|| held.isOf(Items.HONEYCOMB)) {
				be.sealSeam(seamIdx, player, held, world, pos);
				return ActionResult.SUCCESS;
			}
			// Patch torn seam holes with Hide Scrap
			if (held.isOf(MaterialItems.HIDE_SCRAP)) {
				be.patchSeamWithScrap(seamIdx, player, held, world, pos);
				return ActionResult.SUCCESS;
			}
		}
		return ActionResult.PASS;
	}

	private static int seamFromUV(double u, double v) {
		// v: 0 = top of frame, 1 = bottom
		if (v < 0.30) return WaterskinConstruction.SeamZone.TOP_NECK.index;
		if (v > 0.72) return WaterskinConstruction.SeamZone.BOTTOM_GUSSET.index;
		return u < 0.5 ? WaterskinConstruction.SeamZone.LEFT_FLANK.index : WaterskinConstruction.SeamZone.RIGHT_FLANK.index;
	}
}
