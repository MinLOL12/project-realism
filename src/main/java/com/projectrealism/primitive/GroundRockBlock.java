package com.projectrealism.primitive;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.item.Items;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

/**
 * A small rock resting on the ground.
 *
 * <p>Three size stages, stage 1 being a plausible pebble and stage 3 being an implausible pile. Being a
 * real block rather than a hovering item entity means they stay where they are put, obey gravity rules
 * of their own (they only sit on suitable terrain), and can be broken, mapped and saved normally.
 * Picking one up with a bare hand is the intended interaction; destroying one with a tool also works,
 * because players will try that first.</p>
 */
public class GroundRockBlock extends Block {
	public static final IntProperty STAGE = IntProperty.of("stage", 1, 3);

	private static final VoxelShape SHAPE_1 = Block.createCuboidShape(5, 0, 4, 11, 3, 12);
	private static final VoxelShape SHAPE_2 = Block.createCuboidShape(4, 0, 3, 12, 4, 13);
	private static final VoxelShape SHAPE_3 = Block.createCuboidShape(3, 0, 2, 13, 5, 14);

	private final Item drop;
	private final int dropCount;

	public GroundRockBlock(Settings settings, Item drop, int dropCount) {
		super(settings);
		this.drop = drop;
		this.dropCount = dropCount;
		setDefaultState(getDefaultState().with(STAGE, 1));
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(STAGE);
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, net.minecraft.world.BlockView world, BlockPos pos,
										ShapeContext context) {
		return switch (state.get(STAGE)) {
			case 1 -> SHAPE_1;
			case 2 -> SHAPE_2;
			default -> SHAPE_3;
		};
	}

	@Override
	protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
		return GroundRockSpawner.canRestOn(world.getBlockState(pos.down()));
	}

	/** Right-click with a bare hand to pick the rock up. */
	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player,
							  net.minecraft.util.hit.BlockHitResult hit) {
		if (world.isClient) {
			return ActionResult.SUCCESS;
		}
		harvest(world, pos, state, player);
		return ActionResult.CONSUME;
	}

	@Override
	protected void onBlockBreakStart(BlockState state, World world, BlockPos pos, PlayerEntity player) {
		// Punching a rock should also collect it rather than shattering it into nothing.
		if (!world.isClient) {
			harvest(world, pos, state, player);
		}
	}

	private void harvest(World world, BlockPos pos, BlockState state, PlayerEntity player) {
		if (!(world instanceof ServerWorld server) || player.isSpectator()) {
			return;
		}

		int count = dropCount + (state.get(STAGE) - 1);
		if (!player.isCreative()) {
			ItemStack collected = new ItemStack(drop, count);
			if (!player.getInventory().insertStack(collected)) {
				Block.dropStack(world, pos, collected);
			}
		}

		// Items first, then the block: if the drop ever fails, the rock is still standing rather than
		// silently deleted.
		world.playSound(null, pos, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.BLOCKS,
				0.45f, 1.35f + world.random.nextFloat() * 0.15f);
		world.removeBlock(pos, false);
		server.spawnParticles(ParticleTypes.CRIT,
				pos.getX() + 0.5, pos.getY() + 0.15, pos.getZ() + 0.5,
				6, 0.18, 0.08, 0.18, 0.02);
	}

	/** Slow regrowth: rocks only come back after a long, random wait. */
	@Override
	protected boolean hasRandomTicks(BlockState state) {
		return state.get(STAGE) < 3;
	}

	@Override
	protected void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		if (state.get(STAGE) >= 3) {
			return;
		}
		if (!canPlaceAt(state, world, pos)) {
			return;
		}
		// Roughly one in 40 random ticks (~ 1 in 8 minutes) the pile grows by one stage.
		if (random.nextInt(40) == 0) {
			world.setBlockState(pos, state.with(STAGE, state.get(STAGE) + 1),
					Block.NOTIFY_LISTENERS);
		}
	}

	@Override
	public Item asItem() {
		return drop == null ? Items.AIR : drop;
	}

	/** Convenience so the spawner and the block agree on what "a rock" is. */
	public static boolean isRock(Block block) {
		return block instanceof GroundRockBlock;
	}

	@Override
	public String toString() {
		return "GroundRockBlock[" + Registries.BLOCK.getId(this) + "]";
	}
}
