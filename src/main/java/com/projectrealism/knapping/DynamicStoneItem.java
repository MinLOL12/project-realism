package com.projectrealism.knapping;

import com.projectrealism.wood.WoodSpecies;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.List;
import java.util.Random;

/**
 * Physical dynamically shaped stone object (raw cobble, flaked blade, thick wedge, scraper cobble,
 * or handle-hafted composite). Never stores a tool category; all mining speed, slicing/chopping
 * efficacy, and voxel chipping derive purely from its actual 3D geometry and material physics.
 */
public class DynamicStoneItem extends Item {
	public DynamicStoneItem(Settings settings) {
		super(settings.maxCount(1));
	}

	public static DynamicStoneState readState(ItemStack stack) {
		NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (comp != null) {
			NbtCompound root = comp.copyNbt();
			if (root.contains("Stone")) {
				return DynamicStoneState.fromNbt(root.getCompound("Stone"));
			}
		}
		return DynamicStoneState.createCobble(StoneMaterial.FLINT, 101L, 0.85);
	}

	public static void writeState(ItemStack stack, DynamicStoneState state) {
		if (stack.isEmpty() || state == null) return;
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, root -> root.put("Stone", state.toNbt()));
	}

	public static ItemStack create(DynamicStoneState state) {
		ItemStack st = new ItemStack(DynamicKnappingItems.DYNAMIC_STONE);
		writeState(st, state);
		return st;
	}

	public static double massKg(ItemStack stack) {
		return readState(stack).totalMassKg();
	}

	@Override
	public Text getName(ItemStack stack) {
		DynamicStoneState s = readState(stack);
		VoxelRockGrid.GeometryMetrics m = s.metrics();
		String matName = Character.toUpperCase(s.material.id.charAt(0)) + s.material.id.substring(1);
		String formDesc;
		if (s.haft.hafted) {
			if (m.continuousEdgeCm() >= 3.5 && m.primaryEdgeAngleDeg() <= 34.0) formDesc = "Hafted Edged Stone";
			else if (m.pointScore() >= 0.55) formDesc = "Hafted Pointed Stone";
			else formDesc = "Hafted Stone";
		} else if (m.continuousEdgeCm() >= 3.0 && m.primaryEdgeAngleDeg() <= 34.0) {
			formDesc = "Flaked " + matName;
		} else if (m.voxelCount() < 160) {
			formDesc = matName + " Flake";
		} else {
			formDesc = matName + " Stone";
		}
		return Text.literal(s.haft.hafted ? matName + " (" + formDesc + ")" : formDesc);
	}

	@Override
	public boolean isItemBarVisible(ItemStack stack) {
		DynamicStoneState s = readState(stack);
		return s.haft.hafted || s.metrics().fractureDensity() > 0.12;
	}

	@Override
	public int getItemBarStep(ItemStack stack) {
		DynamicStoneState s = readState(stack);
		double val = s.haft.hafted ? s.haft.bindingTension : (1.0 - s.metrics().fractureDensity());
		return Math.max(1, Math.min(13, (int) Math.round(val * 13.0)));
	}

	@Override
	public int getItemBarColor(ItemStack stack) {
		DynamicStoneState s = readState(stack);
		if (s.haft.hafted) return s.haft.bindingTension < 0.25 ? 0xD4523C : 0x62C370;
		return s.metrics().fractureDensity() > 0.45 ? 0xCF5C42 : 0x7EB4D9;
	}

	@Override
	public float getMiningSpeed(ItemStack stack, BlockState state) {
		DynamicStoneState s = readState(stack);
		if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS) || state.isIn(BlockTags.WOODEN_SLABS)) {
			double hard = WoodSpecies.fromBlock(state.getBlock()).map(WoodSpecies::hardness).orElse(1.0f);
			StoneInteractionPhysics.ContactResponse resp = StoneInteractionPhysics.evaluate(
					s, StoneInteractionPhysics.TargetMaterial.WOOD_CHOP, 140.0);
			return (float) Math.max(0.45, resp.speedMultiplier() * 1.45 / Math.max(0.6, hard));
		}
		if (state.isIn(BlockTags.PICKAXE_MINEABLE)) {
			StoneInteractionPhysics.TargetMaterial tm = state.isIn(BlockTags.NEEDS_IRON_TOOL)
					? StoneInteractionPhysics.TargetMaterial.ORE_HARD
					: StoneInteractionPhysics.TargetMaterial.STONE_MINE;
			StoneInteractionPhysics.ContactResponse resp = StoneInteractionPhysics.evaluate(s, tm, 160.0);
			return (float) Math.max(0.35, resp.speedMultiplier() * 1.45);
		}
		return super.getMiningSpeed(stack, state);
	}

	@Override
	public boolean postMine(ItemStack stack, World world, BlockState state, BlockPos pos, LivingEntity miner) {
		if (world instanceof ServerWorld sw && miner instanceof PlayerEntity player && !player.isCreative()) {
			DynamicStoneState s = readState(stack);
			StoneInteractionPhysics.TargetMaterial target;
			if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS)) {
				target = StoneInteractionPhysics.TargetMaterial.WOOD_CHOP;
			} else if (state.isIn(BlockTags.NEEDS_IRON_TOOL)) {
				target = StoneInteractionPhysics.TargetMaterial.ORE_HARD;
			} else if (state.isIn(BlockTags.PICKAXE_MINEABLE)) {
				target = StoneInteractionPhysics.TargetMaterial.STONE_MINE;
			} else {
				target = StoneInteractionPhysics.TargetMaterial.PLANT_CUT;
			}
			boolean wasHafted = s.haft.hafted;
			int chipped = StoneInteractionPhysics.applyContactWear(s, target, 145.0, new Random(sw.random.nextLong()));
			if (wasHafted && !s.haft.hafted) {
				sw.playSound(null, player.getBlockPos(), SoundEvents.ITEM_BUNDLE_DROP_CONTENTS,
						SoundCategory.PLAYERS, 0.7f, 0.9f);
				player.sendMessage(Text.translatable("project_realism.knapping.binding_slipped").formatted(Formatting.RED), true);
			} else if (chipped > 0) {
				sw.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_STONE_BREAK,
						SoundCategory.PLAYERS, 0.35f, 1.45f);
			}
			writeState(stack, s);
		}
		return true;
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext context) {
		World world = context.getWorld();
		PlayerEntity player = context.getPlayer();
		ItemStack stack = context.getStack();
		if (player == null) return ActionResult.PASS;

		// Placing stone down on a solid surface turns it into an in-world KnappingStoneBlock for shaping
		ItemPlacementContext pCtx = new ItemPlacementContext(context);
		BlockPos placePos = pCtx.getBlockPos();
		BlockState targetState = world.getBlockState(placePos);
		BlockState belowState = world.getBlockState(placePos.down());
		if (!belowState.isAir() && (targetState.isAir() || targetState.isReplaceable())) {
			if (!world.isClient) {
				DynamicStoneState state = readState(stack);
				state.grid.settleOnBase();
				world.setBlockState(placePos, DynamicKnappingBlocks.KNAPPING_STONE.getDefaultState()
						.with(KnappingStoneBlock.FACING, player.getHorizontalFacing().getOpposite()));
				if (world.getBlockEntity(placePos) instanceof KnappingStoneBlockEntity be) {
					be.setStone(state);
				}
				world.playSound(null, placePos, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 0.65f, 1.0f);
				if (!player.isCreative()) {
					stack.decrement(1);
				}
			}
			return ActionResult.success(world.isClient);
		}
		return ActionResult.PASS;
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		super.appendTooltip(stack, context, tooltip, type);
		DynamicStoneState s = readState(stack);
		VoxelRockGrid.GeometryMetrics m = s.metrics();
		tooltip.add(Text.translatable("project_realism.tooltip.stone_dims",
				s.material.id,
				String.format("%.2f", s.totalMassKg()),
				m.voxelCount(),
				String.format("%.1f", m.lengthCm()),
				String.format("%.1f", m.widthCm()),
				String.format("%.1f", m.thicknessCm())).formatted(Formatting.GOLD));
		tooltip.add(Text.translatable("project_realism.tooltip.stone_edge",
				(int) Math.round(m.primaryEdgeAngleDeg()),
				String.format("%.2f", m.primaryEdgeRadiusMm()),
				String.format("%.1f", m.continuousEdgeCm()),
				String.format("%.1f", m.flatSurfaceAreaCm2())).formatted(Formatting.AQUA));
		tooltip.add(Text.translatable("project_realism.tooltip.stone_struct",
				(int) Math.round(m.pointScore() * 100.0),
				(int) Math.round(m.fractureDensity() * 100.0)).formatted(
				m.fractureDensity() > 0.45 ? Formatting.RED : Formatting.GRAY));
		if (s.haft.hafted) {
			tooltip.add(Text.translatable("project_realism.tooltip.stone_haft",
					s.haft.orientation.id,
					s.haft.bindingMaterial,
					(int) Math.round(s.haft.bindingTension * 100.0),
					(int) Math.round(s.haft.socketFit * 100.0)).formatted(Formatting.GREEN));
		}
	}
}
