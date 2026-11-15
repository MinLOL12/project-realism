package com.projectrealism.anatomy;

import com.mojang.serialization.MapCodec;
import com.projectrealism.material.MaterialItems;
import com.projectrealism.primitive.PrimitiveItems;
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
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Persistent in-world animal carcass supporting anatomical region inspection, bleeding out,
 * fleece shearing, skinning, fat flensing, sinew/tendon extraction, evisceration, muscle cutting,
 * horn detachment, and skeletal disarticulation with zero progress bars.
 */
public class CarcassBlock extends BlockWithEntity {
	public static final MapCodec<CarcassBlock> CODEC = createCodec(CarcassBlock::new);
	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
	private static final VoxelShape SHAPE = Block.createCuboidShape(1, 0, 1, 15, 10, 15);

	public CarcassBlock(Settings settings) {
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
		return new CarcassBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		return world.isClient ? null : validateTicker(type, AnatomyBlocks.CARCASS_ENTITY, CarcassBlockEntity::tick);
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock())) {
			if (!world.isClient && world.getBlockEntity(pos) instanceof CarcassBlockEntity be) {
				be.dropOnBreak(world, pos);
			}
			super.onStateReplaced(state, world, pos, newState, moved);
		}
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (world.isClient) return ActionResult.SUCCESS;
		if (!(world.getBlockEntity(pos) instanceof CarcassBlockEntity be)) return ActionResult.PASS;

		Vec3d uv = localBedUV(state.get(FACING), pos, hit.getPos());
		CarcassState.Region region = regionFromUV(uv.x, uv.z);
		ItemStack held = player.getMainHandStack();

		// Sneak + empty hand: pick up whole persistent carcass as portable CarcassItem
		if (player.isSneaking() && held.isEmpty()) {
			be.pickUpWhole(player, world, pos);
			return ActionResult.SUCCESS;
		}

		// Bare hand inspect targeted anatomical region without opening a GUI
		if (held.isEmpty()) {
			player.sendMessage(be.inspectText(region), true);
			return ActionResult.SUCCESS;
		}

		// Shears or knife on wool-bearing coat removes raw wool fleece cleanly
		if (held.isOf(Items.SHEARS)) {
			be.shearCoat(player, Hand.MAIN_HAND, held, world, pos);
			return ActionResult.SUCCESS;
		}

		// Sharp stone / bone cutting blade, hide scraper, chisel, or axe process targeted anatomical zone
		if (held.isOf(PrimitiveItems.STONE_KNIFE) || held.isOf(AnatomyItems.BONE_BLADE)
				|| held.isOf(MaterialItems.STONE_SCRAPER)
				|| held.isOf(PrimitiveItems.STONE_AXE) || held.isOf(AnatomyItems.BONE_CHISEL)) {
			be.processRegion(region, player, Hand.MAIN_HAND, held, world, pos);
			return ActionResult.SUCCESS;
		}

		return ActionResult.PASS;
	}

	/**
	 * Maps a block-hit position onto normalized carcass bed coordinates aligned with {@link AnatomyBlockRenderers}:
	 * {@code u} (Vec3d.x) spans across the body flanks (0 = left, 1 = right), and {@code v} (Vec3d.z) runs
	 * front-to-back along the body axis (0 = head/neck end, 1 = hindquarters/limbs).
	 */
	public static Vec3d localBedUV(Direction facing, BlockPos pos, Vec3d hitPos) {
		double lx = Math.max(0.02, Math.min(0.98, hitPos.x - pos.getX()));
		double lz = Math.max(0.02, Math.min(0.98, hitPos.z - pos.getZ()));
		double u;
		double v;
		switch (facing) {
			case SOUTH -> { u = 1.0 - lx; v = 1.0 - lz; }
			case EAST  -> { u = lz;       v = 1.0 - lx; }
			case WEST  -> { u = 1.0 - lz; v = lx;       }
			default    -> { u = lx;       v = lz;       } // NORTH: head renders at -Z (low lz)
		}
		return new Vec3d(u, hitPos.y - pos.getY(), v);
	}

	public static CarcassState.Region regionFromUV(double u, double v) {
		// Front head/neck (v < 0.28), hindquarters/limbs (v > 0.72), left belly/viscera, right spine/loin, center torso
		if (v < 0.28) return CarcassState.Region.HEAD_NECK;
		if (v > 0.72) return CarcassState.Region.HAUNCH_LIMB;
		if (u < 0.36) return CarcassState.Region.VISCERA_CAVITY;
		if (u > 0.68) return CarcassState.Region.LOIN_RIBS;
		return CarcassState.Region.COAT_TORSO;
	}
}
