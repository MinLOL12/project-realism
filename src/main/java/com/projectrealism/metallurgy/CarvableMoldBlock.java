package com.projectrealism.metallurgy;

import com.mojang.serialization.MapCodec;
import com.projectrealism.knapping.DynamicKnappingItems;
import com.projectrealism.material.MaterialItems;
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
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * In-world 16x16x16 carvable casting mold block. Players physically carve arbitrary 3D cavity shapes
 * (never preset mold recipes!), optionally clamp/vent a 2-part cap, and pour molten alloy from a crucible.
 */
public class CarvableMoldBlock extends BlockWithEntity {
	public static final MapCodec<CarvableMoldBlock> CODEC = createCodec(CarvableMoldBlock::new);
	public static final DirectionProperty FACING = Properties.HORIZONTAL_FACING;
	private static final VoxelShape SHAPE = Block.createCuboidShape(0, 0, 0, 16, 12, 16);

	public CarvableMoldBlock(Settings settings) {
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
		return new CarvableMoldBlockEntity(pos, state);
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (world.isClient) return ActionResult.SUCCESS;
		if (!(world.getBlockEntity(pos) instanceof CarvableMoldBlockEntity be)) return ActionResult.PASS;

		ItemStack held = player.getMainHandStack();
		Direction facing = state.get(FACING);

		if (held.isEmpty()) {
			if (player.isSneaking() && be.hasSolidifiedCasting()) {
				be.extractCasting(player, world, pos);
			} else {
				be.inspect(player);
			}
			return ActionResult.SUCCESS;
		}

		// Pour molten metal from held ClayCrucibleItem
		if (held.isOf(MetallurgyItems.CLAY_CRUCIBLE_ITEM)) {
			CrucibleState cs = ClayCrucibleItem.readState(held);
			be.pourFromCrucible(cs, player, world, pos);
			ClayCrucibleItem.writeState(held, cs);
			return ActionResult.SUCCESS;
		}

		// Toggle 2-part capped mold & check venting with clay/stone cap
		if (held.isOf(Items.CLAY_BALL) || held.isOf(MetallurgyItems.FIRE_CLAY_BALL)) {
			be.toggleCap(player, world, pos);
			return ActionResult.SUCCESS;
		}

		// Carve cavity voxel-by-voxel with sharp stone, metal, bone awl, or knife!
		if (held.isOf(DynamicKnappingItems.DYNAMIC_STONE) || held.isOf(MetallurgyItems.DYNAMIC_METAL)
				|| held.isOf(PrimitiveItems.STONE_KNIFE) || held.isOf(MaterialItems.BONE_AWL)
				|| held.isOf(Items.FLINT)) {
			be.carveAt(facing, player, hit.getPos(), pos, world);
			return ActionResult.SUCCESS;
		}

		return ActionResult.PASS;
	}
}
