package com.projectrealism.wood;

import com.projectrealism.ProjectRealism;
import com.projectrealism.thermo.ThermalWorldState;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.PillarBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.EnumProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

/**
 * A freshly cut green log containing moisture. Dries over time into a seasoned vanilla log.
 */
public class FreshLogBlock extends Block {
	public static final EnumProperty<Direction.Axis> AXIS = Properties.AXIS;
	public static final IntProperty MOISTURE = DryingWoodHelper.MOISTURE;

	private final WoodSpecies species;

	public FreshLogBlock(Settings settings, WoodSpecies species) {
		super(settings);
		this.species = species;
		setDefaultState(getDefaultState().with(AXIS, Direction.Axis.Y).with(MOISTURE, 3));
	}

	public WoodSpecies species() {
		return species;
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(AXIS, MOISTURE);
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		return getDefaultState().with(AXIS, ctx.getSide().getAxis()).with(MOISTURE, 3);
	}

	@Override
	protected void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
		if (world instanceof ServerWorld server && state.get(MOISTURE) > 0) {
			if (ProjectRealism.config().thermodynamicsSimulation) {
				ThermalWorldState.get(server).getOrCreateNode(server, pos);
			} else {
				world.scheduleBlockTick(pos, this,
						DryingWoodHelper.computeStepDelayTicks(world, pos, species, 1.0f));
			}
		}
	}

	@Override
	protected boolean hasRandomTicks(BlockState state) {
		return state.get(MOISTURE) > 0;
	}

	@Override
	protected void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		if (ProjectRealism.config().thermodynamicsSimulation) {
			ThermalWorldState.get(world).getOrCreateNode(world, pos);
			return;
		}
		if (state.get(MOISTURE) > 0 && !world.getBlockTickScheduler().isQueued(pos, this)) {
			world.scheduleBlockTick(pos, this,
					DryingWoodHelper.computeStepDelayTicks(world, pos, species, 1.0f));
		}
	}

	@Override
	protected void scheduledTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		if (ProjectRealism.config().thermodynamicsSimulation) {
			ThermalWorldState.get(world).getOrCreateNode(world, pos);
			return;
		}
		int moisture = state.get(MOISTURE);
		if (moisture <= 0) {
			world.setBlockState(pos, species.vanillaLog().getDefaultState()
					.with(PillarBlock.AXIS, state.get(AXIS)), Block.NOTIFY_ALL);
			return;
		}
		if (DryingWoodHelper.isRainingOn(world, pos)) {
			world.scheduleBlockTick(pos, this,
					DryingWoodHelper.computeStepDelayTicks(world, pos, species, 1.0f));
			return;
		}

		DryingWoodHelper.spawnDryingSteam(world, pos);
		int next = moisture - 1;
		if (next <= 0) {
			world.setBlockState(pos, species.vanillaLog().getDefaultState()
					.with(PillarBlock.AXIS, state.get(AXIS)), Block.NOTIFY_ALL);
		} else {
			world.setBlockState(pos, state.with(MOISTURE, next), Block.NOTIFY_LISTENERS);
			world.scheduleBlockTick(pos, this,
					DryingWoodHelper.computeStepDelayTicks(world, pos, species, 1.0f));
		}
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (!world.isClient) {
			player.sendMessage(DryingWoodHelper.moistureStatusText(world, pos, state.get(MOISTURE), species), true);
		}
		return ActionResult.SUCCESS;
	}
}
