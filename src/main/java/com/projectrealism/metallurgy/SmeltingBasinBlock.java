package com.projectrealism.metallurgy;

import com.mojang.serialization.MapCodec;
import com.projectrealism.primitive.PrimitiveItems;
import com.projectrealism.thermo.ThermoItems;
import com.projectrealism.wood.SplitWoodBlock;
import com.projectrealism.wood.WoodItems;
import net.minecraft.block.Block;
import net.minecraft.block.BlockRenderType;
import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityTicker;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.ActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Physical smelting hearth & furnace floor block. Forms a working furnace simply by building
 * enclosing clay, stone, or refractory walls around and above it — no fixed multiblock recipes.
 */
public class SmeltingBasinBlock extends BlockWithEntity {
	public static final MapCodec<SmeltingBasinBlock> CODEC = createCodec(SmeltingBasinBlock::new);
	public static final BooleanProperty LIT = BooleanProperty.of("lit");
	private static final VoxelShape SHAPE = Block.createCuboidShape(0, 0, 0, 16, 10, 16);

	public SmeltingBasinBlock(Settings settings) {
		super(settings);
		setDefaultState(getDefaultState().with(LIT, false));
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
		builder.add(LIT);
	}

	@Override
	protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
		return SHAPE;
	}

	@Nullable
	@Override
	public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
		return new SmeltingBasinBlockEntity(pos, state);
	}

	@Nullable
	@Override
	public <T extends BlockEntity> BlockEntityTicker<T> getTicker(World world, BlockState state, BlockEntityType<T> type) {
		return world.isClient ? null : validateTicker(type, MetallurgyBlocks.SMELTING_BASIN_ENTITY,
				(w, p, s, be) -> be.serverTick(w, p));
	}

	@Override
	public ActionResult onUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit) {
		if (world.isClient) return ActionResult.SUCCESS;
		if (!(world.getBlockEntity(pos) instanceof SmeltingBasinBlockEntity be)) return ActionResult.PASS;

		ItemStack held = player.getMainHandStack();
		if (held.isEmpty() || held.isOf(MetallurgyItems.IRON_TONGS)) {
			if (player.isSneaking()) {
				be.extractProduct(player, world, pos);
			} else {
				be.inspect(player, world, pos);
			}
			return ActionResult.SUCCESS;
		}
		if (held.isOf(MetallurgyItems.LEATHER_BELLOWS)) {
			be.pumpBellows(player, world, pos);
			return ActionResult.SUCCESS;
		}
		if (held.isOf(Items.CHARCOAL) || held.isOf(Items.COAL) || held.isOf(Items.STICK)
				|| Block.getBlockFromItem(held.getItem()) instanceof SplitWoodBlock) {
			be.addFuel(player, held, world, pos);
			return ActionResult.SUCCESS;
		}
		if (held.isOf(MetallurgyItems.ORE_CHUNK)) {
			be.addOre(player, held, world, pos);
			return ActionResult.SUCCESS;
		}
		if (held.isOf(PrimitiveItems.LIMESTONE_ROCK) || held.isOf(ThermoItems.WOOD_ASH)) {
			be.addFlux(player, held, world, pos);
			return ActionResult.SUCCESS;
		}
		if (held.isOf(ThermoItems.FIRE_DRILL) || held.isOf(ThermoItems.BOW_DRILL)
				|| held.isOf(Items.FLINT_AND_STEEL)) {
			be.ignite(player, world, pos);
			return ActionResult.SUCCESS;
		}
		if (held.isOf(MetallurgyItems.DYNAMIC_METAL)) {
			be.heatMetalObject(player, held, world, pos);
			return ActionResult.SUCCESS;
		}
		return ActionResult.PASS;
	}
}
