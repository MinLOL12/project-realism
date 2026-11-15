package com.projectrealism.material;

import com.mojang.serialization.MapCodec;
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
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Angled wooden hide-scraping A-frame holding a hide stretched taut across its beam so players
 * can target specific regions with a Stone Scraper along sweeping strokes.
 */
public class ScrapingFrameBlock extends BlockWithEntity {
	public static final MapCodec<ScrapingFrameBlock> CODEC = createCodec(ScrapingFrameBlock::new);
	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
	private static final VoxelShape SHAPE = Block.createCuboidShape(1, 0, 1, 15, 14, 15);

	public ScrapingFrameBlock(Settings settings) {
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
		return new ScrapingFrameBlockEntity(pos, state);
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock())) {
			if (!world.isClient && world.getBlockEntity(pos) instanceof ScrapingFrameBlockEntity be) {
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
		if (!(world.getBlockEntity(pos) instanceof ScrapingFrameBlockEntity be)) {
			return ActionResult.PASS;
		}
		ItemStack held = player.getMainHandStack();
		Vec3d local = localUV(state.get(FACING), pos, hit.getPos());

		// Sneak + empty hand: remove mounted hide
		if (player.isSneaking() && held.isEmpty()) {
			be.takeHide(player, world, pos);
			return ActionResult.SUCCESS;
		}
		// Place RawHide onto frame
		if (!be.hasHide() && held.isOf(MaterialItems.RAW_HIDE)) {
			be.mountHide(held, player);
			return ActionResult.SUCCESS;
		}
		// Scrape exact targeted region with Stone Scraper or dynamically shaped flat stone
		if (be.hasHide() && (held.isOf(MaterialItems.STONE_SCRAPER)
				|| held.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE))) {
			be.scrapeAt(player, Hand.MAIN_HAND, held, local.x, local.z, world, pos);
			return ActionResult.SUCCESS;
		}
		// Inspect mounted hide visually/descriptively without percentage bars
		if (be.hasHide()) {
			player.sendMessage(be.statusText(), true);
			return ActionResult.SUCCESS;
		}
		return ActionResult.PASS;
	}

	public static Vec3d localUV(Direction facing, BlockPos pos, Vec3d hitPos) {
		double lx = hitPos.x - pos.getX();
		double ly = hitPos.y - pos.getY();
		double lz = hitPos.z - pos.getZ();
		double u;
		double v = 1.0 - Math.max(0.0, Math.min(1.0, ly));
		switch (facing) {
			case SOUTH -> u = 1.0 - lx;
			case EAST -> u = lz;
			case WEST -> u = 1.0 - lz;
			default -> u = lx;
		}
		return new Vec3d(Math.max(0.02, Math.min(0.98, u)), ly, Math.max(0.02, Math.min(0.98, v)));
	}
}
