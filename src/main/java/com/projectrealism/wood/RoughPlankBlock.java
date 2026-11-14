package com.projectrealism.wood;

import com.projectrealism.ProjectRealism;
import com.projectrealism.primitive.ToolCondition;
import com.projectrealism.thermo.ThermalWorldState;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.DirectionProperty;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;
import org.jetbrains.annotations.Nullable;

/**
 * Hewn rough planks. Fresh rough planks contain moisture and must dry before being planed into
 * finished vanilla planks.
 */
public class RoughPlankBlock extends Block {
	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
	public static final IntProperty MOISTURE = DryingWoodHelper.MOISTURE;

	private static final VoxelShape SHAPE = Block.createCuboidShape(0, 0, 0, 16, 4, 16);

	private final WoodSpecies species;

	public RoughPlankBlock(Settings settings, WoodSpecies species) {
		super(settings);
		this.species = species;
		setDefaultState(getDefaultState().with(FACING, Direction.NORTH).with(MOISTURE, 3));
	}

	public WoodSpecies species() {
		return species;
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(FACING, MOISTURE);
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPE;
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		boolean dry = ToolCondition.isDry(ctx.getStack());
		return getDefaultState()
				.with(FACING, ctx.getHorizontalPlayerFacing().getOpposite())
				.with(MOISTURE, dry ? 0 : 3);
	}

	@Override
	public void onPlaced(World world, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
		if (world instanceof ServerWorld server) {
			if (ProjectRealism.config().thermodynamicsSimulation) {
				var node = ThermalWorldState.get(server).getOrCreateNode(server, pos);
				double sm = ToolCondition.getSurfaceMoisture(stack, 0.50);
				double cm = ToolCondition.getCoreMoisture(stack, 0.55);
				node.body.surfaceMoistureKg = Math.max(0.0, node.body.initialDryMassKg * 0.30 * sm);
				node.body.coreMoistureKg = Math.max(0.0, node.body.initialDryMassKg * 0.70 * cm);
			} else if (state.get(MOISTURE) > 0) {
				world.scheduleBlockTick(pos, this,
						DryingWoodHelper.computeStepDelayTicks(world, pos, species, 1.6f));
			}
		}
	}

	@Override
	protected void onBlockAdded(BlockState state, World world, BlockPos pos, BlockState oldState, boolean notify) {
		if (world instanceof ServerWorld server && state.get(MOISTURE) > 0) {
			if (ProjectRealism.config().thermodynamicsSimulation) {
				ThermalWorldState.get(server).getOrCreateNode(server, pos);
			} else {
				world.scheduleBlockTick(pos, this,
						DryingWoodHelper.computeStepDelayTicks(world, pos, species, 1.6f));
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
					DryingWoodHelper.computeStepDelayTicks(world, pos, species, 1.6f));
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
			return;
		}
		if (DryingWoodHelper.isRainingOn(world, pos)) {
			world.scheduleBlockTick(pos, this,
					DryingWoodHelper.computeStepDelayTicks(world, pos, species, 1.6f));
			return;
		}

		DryingWoodHelper.spawnDryingSteam(world, pos);
		int next = moisture - 1;
		world.setBlockState(pos, state.with(MOISTURE, next), Block.NOTIFY_LISTENERS);
		if (next > 0) {
			world.scheduleBlockTick(pos, this,
					DryingWoodHelper.computeStepDelayTicks(world, pos, species, 1.6f));
		}
	}

	@Override
	public void afterBreak(World world, PlayerEntity player, BlockPos pos, BlockState state,
						   @Nullable BlockEntity blockEntity, ItemStack tool) {
		super.afterBreak(world, player, pos, state, blockEntity, tool);
		if (world instanceof ServerWorld server && !player.isCreative()) {
			ItemStack drop = new ItemStack(species.roughPlankItem());
			var node = ThermalWorldState.get(server).getNodeReadOnly(pos);
			if (node != null) {
				ToolCondition.setMoisture(drop, node.body.surfaceMoistureRatio(), node.body.coreMoistureRatio());
			} else {
				ToolCondition.setDry(drop, state.get(MOISTURE) == 0);
			}
			Block.dropStack(world, pos, drop);
		}
	}

	@Override
	public ItemStack getPickStack(WorldView world, BlockPos pos, BlockState state) {
		ItemStack stack = new ItemStack(species.roughPlankItem());
		ToolCondition.setDry(stack, state.get(MOISTURE) == 0);
		return stack;
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (!world.isClient) {
			player.sendMessage(DryingWoodHelper.moistureStatusText(world, pos, state.get(MOISTURE), species), true);
		}
		return ActionResult.SUCCESS;
	}
}
