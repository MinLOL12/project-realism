package com.projectrealism.primitive;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;

/**
 * A small object resting on the ground that turns into a real item the first time it is touched.
 *
 * <p>This exists for one reason: {@code world.spawnEntity(...)} during chunk generation deadlocks the
 * chunk task executor, so loose items cannot be created at generation time. A block can be written
 * straight into the generating chunk, and it becomes an ordinary item entity on first contact, which
 * is exactly how a fallen object should behave anyway.</p>
 *
 * <p>Used for fallen sticks and for spare rocks.</p>
 */
public class RockDropBlock extends Block {
	public static final IntProperty COUNT = IntProperty.of("count", 1, 3);

	private static final VoxelShape SHAPE = Block.createCuboidShape(6, 0, 6, 10, 1, 10);

	private final ItemStack drop;

	public RockDropBlock(Settings settings, ItemStack drop) {
		super(settings);
		this.drop = drop.copy();
		setDefaultState(getDefaultState().with(COUNT, 1));
	}

	public ItemStack getDropStack() {
		return drop.copy();
	}

	@Override
	protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
		builder.add(COUNT);
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, net.minecraft.world.BlockView world, BlockPos pos,
										ShapeContext context) {
		return SHAPE;
	}

	@Override
	protected boolean canPlaceAt(BlockState state, WorldView world, BlockPos pos) {
		return GroundRockSpawner.canRestOn(world.getBlockState(pos.down()));
	}

	@Override
	public BlockState getPlacementState(ItemPlacementContext ctx) {
		return getDefaultState();
	}

	/** Walking over it picks it up. */
	@Override
	protected void onEntityCollision(BlockState state, World world, BlockPos pos, Entity entity) {
		if (world.isClient || !(entity instanceof PlayerEntity player)) {
			return;
		}
		pop(world, pos, state, player);
	}

	/** Breaking it also works, because players will try. */
	@Override
	protected void onBlockBreakStart(BlockState state, World world, BlockPos pos, PlayerEntity player) {
		if (!world.isClient) {
			pop(world, pos, state, player);
		}
	}

	@Override
	public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
		if (!world.isClient) {
			pop(world, pos, state, player);
		}
		return super.onBreak(world, pos, state, player);
	}

	private void pop(World world, BlockPos pos, BlockState state, PlayerEntity player) {
		if (!(world instanceof ServerWorld server) || player.isSpectator()) {
			return;
		}

		if (!player.isCreative()) {
			int count = state.get(COUNT);
			for (int i = 0; i < count; i++) {
				// Thrown slightly away from the player so it is visible when it lands.
				Direction away = player.getHorizontalFacing().getOpposite();
				ItemEntity item = new ItemEntity(server,
						pos.getX() + 0.5 + away.getOffsetX() * 0.35,
						pos.getY() + 0.25,
						pos.getZ() + 0.5 + away.getOffsetZ() * 0.35,
						drop.copy());
				item.setPickupDelay(0);
				item.setNeverDespawn();
				server.spawnEntity(item);
			}
		}

		server.playSound(null, pos, SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.BLOCKS,
				0.35f, 1.4f);
		world.removeBlock(pos, false);
	}

	@Override
	protected boolean hasRandomTicks(BlockState state) {
		// If a player never comes back, don't leave one more block in the world forever.
		return true;
	}

	@Override
	protected void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
		if (!canPlaceAt(state, world, pos)) {
			world.removeBlock(pos, false);
		}
	}

	@Override
	public String toString() {
		return "RockDropBlock[" + Registries.BLOCK.getId(this) + "]";
	}
}
