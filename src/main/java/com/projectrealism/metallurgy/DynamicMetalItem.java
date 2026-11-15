package com.projectrealism.metallurgy;

import com.projectrealism.knapping.DynamicKnappingItems;
import com.projectrealism.knapping.StoneInteractionPhysics;
import com.projectrealism.knapping.VoxelRockGrid;
import com.projectrealism.wood.WoodSpecies;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.List;
import java.util.Random;

/**
 * Universal category-free dynamic metal item storing exact alloy chemistry, 16x16x16 sub-voxel
 * volume geometry, 64-zone microstructure, thermal state, and optional wooden handle attachment.
 */
public class DynamicMetalItem extends Item {
	public DynamicMetalItem(Settings settings) {
		super(settings.maxCount(1));
	}

	public static DynamicMetalState readState(ItemStack stack) {
		NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (comp != null) {
			NbtCompound root = comp.copyNbt();
			if (root.contains("Metal")) {
				return DynamicMetalState.fromNbt(root.getCompound("Metal"));
			}
		}
		return DynamicMetalState.createNativeNugget(19L, 0.85, 0.96);
	}

	public static void writeState(ItemStack stack, DynamicMetalState state) {
		if (stack.isEmpty() || state == null) return;
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, root -> root.put("Metal", state.toNbt()));
	}

	public static ItemStack create(DynamicMetalState state) {
		ItemStack st = new ItemStack(MetallurgyItems.DYNAMIC_METAL);
		writeState(st, state);
		return st;
	}

	public static double massKg(ItemStack stack) {
		return readState(stack).totalMassKg();
	}

	@Override
	public Text getName(ItemStack stack) {
		DynamicMetalState s = readState(stack);
		VoxelRockGrid.GeometryMetrics m = s.metrics();
		String mat = s.alloy.describeComposition();
		String shape;
		if (s.haft.hafted) {
			if (m.continuousEdgeCm() >= 3.5 && m.primaryEdgeAngleDeg() <= 34.0) shape = "Hafted Edged";
			else if (m.pointScore() >= 0.55) shape = "Hafted Pointed";
			else shape = "Hafted";
		} else if (m.continuousEdgeCm() >= 3.0 && m.primaryEdgeAngleDeg() <= 34.0) {
			shape = "Edged";
		} else if (m.voxelCount() < 120) {
			shape = "Prill";
		} else {
			shape = "Mass";
		}
		String hot = s.temperatureK >= 820.0 ? " [Glowing]" : (s.temperatureK >= 420.0 ? " [Hot]" : "");
		return Text.literal(mat + " " + shape + hot);
	}

	@Override
	public void inventoryTick(ItemStack stack, World world, Entity entity, int slot, boolean selected) {
		if (world.isClient || !(entity instanceof PlayerEntity player)) return;
		if ((world.getTime() & 15) != 0) return;
		DynamicMetalState s = readState(stack);
		if (s.temperatureK > 300.0) {
			s.stepThermal(293.15, 0.8, false);
			writeState(stack, s);
			if (selected && s.temperatureK > 395.0 && !hasHeatProtection(player)) {
				player.damage(world.getDamageSources().hotFloor(), 1.0f);
			}
		}
	}

	public static boolean hasHeatProtection(PlayerEntity player) {
		return player.isCreative() || player.getOffHandStack().isOf(MetallurgyItems.IRON_TONGS)
				|| player.getMainHandStack().isOf(MetallurgyItems.IRON_TONGS)
				|| player.getOffHandStack().isOf(com.projectrealism.material.MaterialItems.LEATHER_PIECE);
	}

	@Override
	public boolean isItemBarVisible(ItemStack stack) {
		DynamicMetalState s = readState(stack);
		return s.temperatureK > 360.0 || s.haft.hafted || s.grid.micro.averageDislocation() > 0.25;
	}

	@Override
	public int getItemBarStep(ItemStack stack) {
		DynamicMetalState s = readState(stack);
		if (s.temperatureK > 360.0) {
			return Math.max(1, Math.min(13, (int) Math.round(((s.temperatureK - 293.0) / 1400.0) * 13.0)));
		}
		double val = s.haft.hafted ? s.haft.bindingTension : (1.0 - s.grid.micro.averageFracture());
		return Math.max(1, Math.min(13, (int) Math.round(val * 13.0)));
	}

	@Override
	public int getItemBarColor(ItemStack stack) {
		DynamicMetalState s = readState(stack);
		if (s.temperatureK > 360.0) return s.temperatureK > 900.0 ? 0xFF6B1A : 0xE6A12A;
		if (s.haft.hafted) return s.haft.bindingTension < 0.25 ? 0xD4523C : 0x62C370;
		return 0x7EB4D9;
	}

	@Override
	public float getMiningSpeed(ItemStack stack, BlockState state) {
		DynamicMetalState s = readState(stack);
		if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS) || state.isIn(BlockTags.WOODEN_SLABS)) {
			double hard = WoodSpecies.fromBlock(state.getBlock()).map(WoodSpecies::hardness).orElse(1.0f);
			var resp = ContactPhysics.evaluateMetal(s, StoneInteractionPhysics.TargetMaterial.WOOD_CHOP, 150.0);
			return (float) Math.max(0.45, resp.speedMultiplier() * 1.55 / Math.max(0.6, hard));
		}
		if (state.isIn(BlockTags.PICKAXE_MINEABLE) || state.getBlock() instanceof MineralDepositBlock) {
			boolean hardVein = state.isIn(BlockTags.NEEDS_IRON_TOOL)
					|| (state.getBlock() instanceof MineralDepositBlock md
					&& (md.depositKind() == MineralDepositBlock.DepositKind.CHALCOPYRITE
					|| md.depositKind() == MineralDepositBlock.DepositKind.ARSENOPYRITE
					|| md.depositKind() == MineralDepositBlock.DepositKind.HEMATITE_MAGNETITE));
			var tm = hardVein
					? StoneInteractionPhysics.TargetMaterial.ORE_HARD
					: StoneInteractionPhysics.TargetMaterial.STONE_MINE;
			var resp = ContactPhysics.evaluateMetal(s, tm, 165.0);
			return (float) Math.max(0.35, resp.speedMultiplier() * 1.55);
		}
		return super.getMiningSpeed(stack, state);
	}

	@Override
	public boolean postMine(ItemStack stack, World world, BlockState state, BlockPos pos, LivingEntity miner) {
		if (world instanceof ServerWorld sw && miner instanceof PlayerEntity player && !player.isCreative()) {
			DynamicMetalState s = readState(stack);
			StoneInteractionPhysics.TargetMaterial target;
			boolean hardVein = state.isIn(BlockTags.NEEDS_IRON_TOOL)
					|| (state.getBlock() instanceof MineralDepositBlock md
					&& (md.depositKind() == MineralDepositBlock.DepositKind.CHALCOPYRITE
					|| md.depositKind() == MineralDepositBlock.DepositKind.ARSENOPYRITE
					|| md.depositKind() == MineralDepositBlock.DepositKind.HEMATITE_MAGNETITE));
			if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS)) {
				target = StoneInteractionPhysics.TargetMaterial.WOOD_CHOP;
			} else if (hardVein) {
				target = StoneInteractionPhysics.TargetMaterial.ORE_HARD;
			} else if (state.isIn(BlockTags.PICKAXE_MINEABLE) || state.getBlock() instanceof MineralDepositBlock) {
				target = StoneInteractionPhysics.TargetMaterial.STONE_MINE;
			} else {
				target = StoneInteractionPhysics.TargetMaterial.PLANT_CUT;
			}
			boolean worn = ContactPhysics.applyMetalWear(s, target, 145.0, new Random(sw.random.nextLong()));
			writeState(stack, s);
			if (worn && s.effectiveHardness() < 3.6 && (target == StoneInteractionPhysics.TargetMaterial.ORE_HARD
					|| target == StoneInteractionPhysics.TargetMaterial.STONE_MINE)) {
				player.sendMessage(Text.literal("Soft metal edge rolled against rock — re-forge or harden alloy!")
						.formatted(Formatting.YELLOW), true);
			}
		}
		return true;
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		BlockHitResult fluidHit = raycast(world, user, RaycastContext.FluidHandling.SOURCE_ONLY);
		if (fluidHit.getType() == HitResult.Type.BLOCK && world.getFluidState(fluidHit.getBlockPos()).isIn(FluidTags.WATER)) {
			if (!world.isClient) {
				DynamicMetalState s = readState(stack);
				if (s.temperatureK > 340.0) {
					double beforeT = s.temperatureK;
					s.grid.micro.applyQuench(s.alloy, beforeT, 293.15, 420.0);
					s.temperatureK = 295.15;
					s.invalidateGeometry();
					writeState(stack, s);
					world.playSound(null, fluidHit.getBlockPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH,
							SoundCategory.PLAYERS, 0.7f, 1.3f);
					user.sendMessage(Text.literal(String.format("Quenched from %.0f°C -> hardness %.1f, toughness %.2f",
									beforeT - 273.15, s.effectiveHardness(), s.effectiveToughness()))
							.formatted(Formatting.AQUA), true);
				}
			}
			return TypedActionResult.success(stack, world.isClient);
		}
		return super.use(world, user, hand);
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext ctx) {
		World world = ctx.getWorld();
		PlayerEntity player = ctx.getPlayer();
		BlockPos pos = ctx.getBlockPos();
		BlockState clicked = world.getBlockState(pos);

		if (clicked.hasBlockEntity() || clicked.getBlock() instanceof PlacedMetalBlock) {
			return ActionResult.PASS;
		}
		if (player == null) return ActionResult.PASS;

		// Honing / abrasive grinding on sandstone or quartzite rock surface
		if (player.isSneaking() && (clicked.isOf(Blocks.SANDSTONE) || clicked.isOf(Blocks.SMOOTH_SANDSTONE)
				|| clicked.isOf(Blocks.GRINDSTONE))) {
			if (!world.isClient) {
				DynamicMetalState s = readState(ctx.getStack());
				double honed = ForgeEngine.grindEdge(s, 7.0);
				writeState(ctx.getStack(), s);
				world.playSound(null, pos, SoundEvents.BLOCK_GRINDSTONE_USE, SoundCategory.BLOCKS, 0.55f, 1.15f);
				player.sendMessage(Text.literal(String.format("Honed metal bevel (%.0f cells refined, edge %.2f mm)",
								honed, s.metrics().primaryEdgeRadiusMm()))
						.formatted(Formatting.GREEN), true);
			}
			return ActionResult.SUCCESS;
		}

		// Place onto solid stone/metal anvil surface for hammering & hafting
		if (ctx.getSide() == Direction.UP && (clicked.isSolidBlock(world, pos)
				|| clicked.isSideSolidFullSquare(world, pos, Direction.UP))) {
			BlockPos placePos = pos.up();
			BlockState above = world.getBlockState(placePos);
			if (!above.isAir() && !above.isReplaceable()) return ActionResult.PASS;
			if (!world.isClient) {
				DynamicMetalState s = readState(ctx.getStack());
				world.setBlockState(placePos, MetallurgyBlocks.PLACED_METAL.getDefaultState()
						.with(PlacedMetalBlock.FACING, player.getHorizontalFacing().getOpposite()));
				if (world.getBlockEntity(placePos) instanceof PlacedMetalBlockEntity be) {
					be.setMetal(s);
				}
				world.playSound(null, placePos, SoundEvents.BLOCK_ANVIL_PLACE, SoundCategory.BLOCKS, 0.5f, 1.3f);
				if (!player.isCreative()) ctx.getStack().decrement(1);
			}
			return ActionResult.SUCCESS;
		}
		return ActionResult.PASS;
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		DynamicMetalState s = readState(stack);
		VoxelRockGrid.GeometryMetrics m = s.metrics();
		tooltip.add(Text.literal(String.format("Alloy: %s | Temp: %.0f°C | Mass: %.2f kg",
				s.alloy.describeComposition(), s.temperatureK - 273.15, s.totalMassKg())).formatted(Formatting.GOLD));
		tooltip.add(Text.literal(String.format("Cu:%.1f%% Sn:%.1f%% Fe:%.1f%% As:%.1f%% C:%.2f%% S:%.2f%% Slag:%.1f%%",
						s.alloy.cu * 100, s.alloy.sn * 100, s.alloy.fe * 100, s.alloy.as * 100,
						s.alloy.c * 100, s.alloy.s * 100, s.alloy.slag * 100))
				.formatted(Formatting.DARK_AQUA));
		tooltip.add(Text.literal(String.format("Hardness: %.1f | Toughness: %.2f | Disloc: %.0f%% | Porosity: %.0f%%",
						s.effectiveHardness(), s.effectiveToughness(),
						s.grid.micro.averageDislocation() * 100, s.grid.micro.averagePorosity() * 100))
				.formatted(Formatting.GRAY));
		tooltip.add(Text.literal(String.format("Edge: %.0f° bevel, %.2f mm tip, %.1f cm long",
				m.primaryEdgeAngleDeg(), m.primaryEdgeRadiusMm(), m.continuousEdgeCm())).formatted(Formatting.GREEN));
		if (s.haft.hafted) {
			tooltip.add(Text.literal(String.format("Hafted (%s) | Binding %.0f%% | Slip %.0f%%",
							s.haft.orientation.id, s.haft.bindingTension * 100, s.haft.slipResistance * 100))
					.formatted(Formatting.YELLOW));
		}
	}
}
