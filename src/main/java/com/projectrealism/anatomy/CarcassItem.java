package com.projectrealism.anatomy;

import com.projectrealism.knapping.KnappingStoneBlock;
import com.projectrealism.metallurgy.PlacedMetalBlock;
import com.projectrealism.primitive.GroundRockBlock;
import com.projectrealism.primitive.RockDropBlock;
import com.projectrealism.thermo.ThermalConstants;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import java.util.List;

/**
 * Portable animal carcass carrying its full anatomical state, body temperature, blood, moisture,
 * decomposition, and remaining harvestable tissues. Right-click a surface to place it back in-world.
 */
public class CarcassItem extends Item {
	public CarcassItem(Settings settings) {
		super(settings.maxCount(1));
	}

	public static CarcassState readCarcass(ItemStack stack) {
		NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (comp != null) {
			NbtCompound root = comp.copyNbt();
			if (root.contains("Carcass")) {
				return CarcassState.fromNbt(root.getCompound("Carcass"));
			}
		}
		return CarcassState.fromLivingAnimal(new AnimalPhysiologyState(AnimalSpecies.RABBIT, 0.9, 0.7), false, 0.1);
	}

	public static void writeCarcass(ItemStack stack, CarcassState state) {
		if (stack.isEmpty() || state == null) return;
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, root -> root.put("Carcass", state.toNbt()));
	}

	public static ItemStack create(CarcassState state) {
		ItemStack st = new ItemStack(AnatomyItems.CARCASS_ITEM);
		writeCarcass(st, state);
		return st;
	}

	public static double massKg(ItemStack stack) {
		return readCarcass(stack).currentTotalMassKg();
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		World world = context.getWorld();
		PlayerEntity player = context.getPlayer();
		BlockPos clickedPos = context.getBlockPos();
		BlockState clicked = world.getBlockState(clickedPos);
		if (clicked.getBlock() instanceof KnappingStoneBlock
				|| clicked.getBlock() instanceof PlacedMetalBlock
				|| clicked.getBlock() instanceof CarcassBlock
				|| clicked.getBlock() instanceof GroundRockBlock
				|| clicked.getBlock() instanceof RockDropBlock
				|| clicked.hasBlockEntity()) {
			return ActionResult.FAIL;
		}
		if (context.getSide() != Direction.UP || !clicked.isSideSolidFullSquare(world, clickedPos, Direction.UP)) {
			return ActionResult.PASS;
		}
		BlockPos placePos = clickedPos.up();
		BlockState target = world.getBlockState(placePos);
		if (!target.isAir() && !target.isReplaceable()) {
			return ActionResult.PASS;
		}
		if (!world.isClient) {
			CarcassState state = readCarcass(context.getStack());
			BlockState placed = AnatomyBlocks.CARCASS.getDefaultState()
					.with(CarcassBlock.FACING, context.getHorizontalPlayerFacing().getOpposite());
			world.setBlockState(placePos, placed);
			if (world.getBlockEntity(placePos) instanceof CarcassBlockEntity be) {
				be.setCarcass(state);
			}
			if (player == null || !player.isCreative()) {
				context.getStack().decrement(1);
			}
			world.playSound(null, placePos, SoundEvents.BLOCK_WOOL_PLACE, SoundCategory.BLOCKS, 0.7f, 0.85f);
		}
		return ActionResult.SUCCESS;
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		super.appendTooltip(stack, context, tooltip, type);
		CarcassState c = readCarcass(stack);
		tooltip.add(Text.translatable("project_realism.tooltip.carcass_summary",
				c.species.id,
				String.format("%.2f", c.currentTotalMassKg()),
				(int) Math.round(ThermalConstants.toCelsius(c.bodyTempK)),
				(int) Math.round(c.remainingBloodFrac * 100.0))
				.formatted(Formatting.GOLD));
		tooltip.add(Text.translatable("project_realism.tooltip.carcass_spoil",
				(int) Math.round(c.spoilageFrac * 100.0),
				(int) Math.round(c.rigorMortisFrac * 100.0))
				.formatted(c.spoilageFrac > 0.35 ? Formatting.RED : Formatting.GRAY));
	}
}
