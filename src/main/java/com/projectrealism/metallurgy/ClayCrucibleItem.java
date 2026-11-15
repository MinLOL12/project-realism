package com.projectrealism.metallurgy;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;

/**
 * Handheld/placeable refractory crucible holding live molten or solidified alloy charges.
 * Clicking directly on a placed {@link CarvableMoldBlock} pours molten metal straight into the carved cavity.
 */
public class ClayCrucibleItem extends BlockItem {
	public ClayCrucibleItem(Block block, Settings settings) {
		super(block, settings.maxCount(1));
	}

	public static CrucibleState readState(ItemStack stack) {
		NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (comp != null) {
			NbtCompound root = comp.copyNbt();
			if (root.contains("Crucible")) {
				return CrucibleState.fromNbt(root.getCompound("Crucible"));
			}
		}
		return new CrucibleState();
	}

	public static void writeState(ItemStack stack, CrucibleState state) {
		if (stack.isEmpty() || state == null) return;
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, root -> root.put("Crucible", state.toNbt()));
	}

	public static double massKg(ItemStack stack) {
		CrucibleState s = readState(stack);
		return 1.25 + s.metalMassKg + s.slagMassKg + s.enclosedCarbonKg;
	}

	@Override
	public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
		if (world.isClient || !(entity instanceof PlayerEntity player)) return;
		if ((world.getTime() & 15) != 0) return;
		CrucibleState s = readState(stack);
		if (s.temperatureK > 300.0) {
			s.stepThermal(293.15, 0.8);
			writeState(stack, s);
			if (selected && s.temperatureK > 450.0 && !DynamicMetalItem.hasHeatProtection(player)) {
				player.damage(world.getDamageSources().hotFloor(), 1.5f);
			}
		}
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext ctx) {
		World world = ctx.getWorld();
		BlockPos pos = ctx.getBlockPos();
		if (world.getBlockEntity(pos) instanceof CarvableMoldBlockEntity moldBe) {
			if (!world.isClient) {
				CrucibleState cs = readState(ctx.getStack());
				moldBe.pourFromCrucible(cs, ctx.getPlayer(), world, pos);
				writeState(ctx.getStack(), cs);
			}
			return ActionResult.SUCCESS;
		}
		if (world.getBlockEntity(pos) instanceof SmeltingBasinBlockEntity basinBe) {
			if (!world.isClient) {
				CrucibleState cs = readState(ctx.getStack());
				basinBe.seatCrucible(cs, ctx.getPlayer(), world, pos, ctx.getStack(), ctx.getHand());
			}
			return ActionResult.SUCCESS;
		}
		return super.useOnBlock(ctx);
	}

	@Override
	public Text getName(ItemStack stack) {
		CrucibleState s = readState(stack);
		if (s.isEmpty()) return Text.literal("Clay Crucible (Empty)");
		String stateLabel = s.isMolten() ? "Molten" : (s.isPartiallyMolten() ? "Pasty" : "Frozen");
		return Text.literal("Crucible [" + stateLabel + " " + s.alloy.describeComposition() + "]");
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		CrucibleState s = readState(stack);
		tooltip.add(Text.literal(String.format("Metal: %.2f kg | Slag: %.2f kg | Temp: %.0f°C",
				s.metalMassKg, s.slagMassKg, s.temperatureK - 273.15)).formatted(Formatting.GOLD));
		if (s.metalMassKg > 0.01) {
			tooltip.add(Text.literal(String.format("Liquidus: %.0f°C | Fluidity: %.2f",
					s.alloy.liquidusTempK() - 273.15, s.fluidity())).formatted(Formatting.AQUA));
		}
		if (s.enclosedCarbonKg > 0.001) {
			tooltip.add(Text.literal(String.format("Sealed carbon charge: %.3f kg", s.enclosedCarbonKg))
					.formatted(Formatting.GRAY));
		}
	}
}
