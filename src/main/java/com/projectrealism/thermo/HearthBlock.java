package com.projectrealism.thermo;

import com.mojang.serialization.MapCodec;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
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
 * Physical stone-lined hearth / firepit holding actual fuels undergoing heating, drying,
 * pyrolysis, gas flaming, char oxidation, and ash/charcoal residue accumulation, plus cooking.
 */
public class HearthBlock extends BlockWithEntity {
	public static final MapCodec<HearthBlock> CODEC = createCodec(HearthBlock::new);
	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
	public static final BooleanProperty LIT = Properties.LIT;
	public static final BooleanProperty HAS_EMBERS = BooleanProperty.of("has_embers");

	private static final VoxelShape SHAPE = Block.createCuboidShape(1, 0, 1, 15, 4, 15);

	public HearthBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState()
				.with(FACING, Direction.NORTH)
				.with(LIT, false)
				.with(HAS_EMBERS, false));
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
		builder.add(FACING, LIT, HAS_EMBERS);
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
		return new HearthBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state,
																  BlockEntityType<T> type) {
		return world.isClient ? null : validateTicker(type, ThermoBlocks.HEARTH_ENTITY, HearthBlockEntity::tick);
	}

	@Override
	public void onSteppedOn(World world, BlockPos pos, BlockState state, Entity entity) {
		if (!world.isClient && entity instanceof LivingEntity living && !entity.bypassesSteppingEffects()) {
			if (world.getBlockEntity(pos) instanceof HearthBlockEntity be && be.temperatureK() > 350.0) {
				float dmg = (float) Math.min(4.0, (be.temperatureK() - 340.0) / 140.0);
				living.damage(world.getDamageSources().inFire(), dmg);
			}
		}
		super.onSteppedOn(world, pos, state, entity);
	}

	@Override
	public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
		if (!state.isOf(newState.getBlock())) {
			if (!world.isClient && world.getBlockEntity(pos) instanceof HearthBlockEntity be) {
				be.dropContents(world, pos);
			}
			super.onStateReplaced(state, world, pos, newState, moved);
		}
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (!world.isClient && world.getBlockEntity(pos) instanceof HearthBlockEntity be) {
			if (player.isSneaking()) {
				be.takeTopItem(player, world, pos);
			} else {
				player.sendMessage(be.statusText(), true);
			}
		}
		return ActionResult.SUCCESS;
	}
}
