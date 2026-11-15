package com.projectrealism.primitive;

import com.projectrealism.wood.FallenTrunkBlock;
import com.projectrealism.wood.FreshLogBlock;
import com.projectrealism.wood.NotchedLogBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.item.PickaxeItem;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * Which tool, if any, a block demands before it will yield.
 *
 * <p>Vanilla really only gates blocks that have a `needs_x_tool` tag; everything else breaks bare-handed
 * and merely breaks faster with the right tool. Early-game Project Realism inverts that: logs, planks and
 * stone are all genuinely locked behind a crafted tool, so the first hour is spent knapping rather than
 * punching.</p>
 */
public final class ToolGate {
	private ToolGate() {
	}

	public enum Requirement {
		/** Anything works, including a bare hand. */
		HAND,
		/** A stone axe or better. */
		AXE,
		/** A pickaxe or better. */
		PICKAXE,
		/** An iron-tier pickaxe or better: stone will not touch this. */
		STRONG_PICKAXE
	}

	/** The minimum tool demanded by this block state. */
	public static Requirement requirementFor(BlockState state) {
		// Deep sulfide & iron bedrock veins require hardened alloy or steel; shallow weathered copper/tin need a stone pick; clay/bog is diggable by hand.
		if (state.getBlock() instanceof com.projectrealism.metallurgy.MineralDepositBlock md) {
			return switch (md.depositKind()) {
				case FIRE_CLAY, LIMONITE_BOG -> Requirement.HAND;
				case NATIVE_COPPER, MALACHITE_AZURITE, CASSITERITE -> Requirement.PICKAXE;
				case CHALCOPYRITE, ARSENOPYRITE, HEMATITE_MAGNETITE -> Requirement.STRONG_PICKAXE;
			};
		}
		// Iron and the metals above it need a real pickaxe or sufficiently hard shaped metal.
		if (state.isIn(BlockTags.NEEDS_IRON_TOOL)) {
			return Requirement.STRONG_PICKAXE;
		}

		// The pebble blocks are themselves a resource, so they must never be gated behind the tool that
		// they are the raw material for.
		if (state.getBlock() instanceof GroundRockBlock) {
			return Requirement.HAND;
		}

		if (state.getBlock() instanceof NotchedLogBlock
				|| state.getBlock() instanceof FallenTrunkBlock
				|| state.getBlock() instanceof FreshLogBlock
				|| state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS)
				|| state.isIn(BlockTags.WOODEN_SLABS) || state.isIn(BlockTags.WOODEN_STAIRS)
				|| state.isIn(BlockTags.WOODEN_FENCES) || state.isIn(BlockTags.WOODEN_DOORS)
				|| state.isIn(BlockTags.WOODEN_TRAPDOORS) || state.isIn(BlockTags.WOODEN_BUTTONS)
				|| state.isIn(BlockTags.WOODEN_PRESSURE_PLATES)
				|| state.isOf(Blocks.CRAFTING_TABLE) || state.isOf(Blocks.CHEST)
				|| state.isOf(Blocks.BARREL) || state.isOf(Blocks.LECTERN)
				|| state.isOf(Blocks.BOOKSHELF) || state.isOf(Blocks.CHISELED_BOOKSHELF)) {
			return Requirement.AXE;
		}

		// Stone and everything that behaves like stone, including ores.
		if (state.isIn(BlockTags.PICKAXE_MINEABLE) || state.isIn(BlockTags.STONE_BRICKS)
				|| state.isOf(Blocks.STONE) || state.isOf(Blocks.COBBLESTONE)
				|| state.isOf(Blocks.GRANITE) || state.isOf(Blocks.DIORITE)
				|| state.isOf(Blocks.ANDESITE) || state.isOf(Blocks.DEEPSLATE)
				|| state.isOf(Blocks.COBBLED_DEEPSLATE) || state.isOf(Blocks.TUFF)
				|| state.isOf(Blocks.CALCITE) || state.isOf(Blocks.FURNACE)
				|| state.isOf(Blocks.BLAST_FURNACE) || state.isOf(Blocks.SMOKER)
				|| state.isOf(Blocks.ENCHANTING_TABLE) || state.isOf(Blocks.ANVIL)
				|| state.isOf(Blocks.OBSIDIAN) || state.isOf(Blocks.NETHERITE_BLOCK)) {
			return Requirement.PICKAXE;
		}

		return Requirement.HAND;
	}

	public static boolean satisfied(Requirement requirement, PlayerEntity player, BlockPos pos) {
		if (player.isCreative() || player.isSpectator()) {
			return true;
		}

		ItemStack held = player.getMainHandStack();

		return switch (requirement) {
			case HAND -> true;
			case AXE -> isAxe(held);
			case PICKAXE -> isPickaxe(held);
			// Stone pickaxes and dynamic stone tools cannot break hard iron/sulfide ores; shaped bronze/steel metal succeeds if hard enough.
			case STRONG_PICKAXE -> isStrongMetal(held) || (isPickaxe(held) && !held.isOf(PrimitiveItems.STONE_PICKAXE)
					&& !held.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE)
					&& !held.isOf(com.projectrealism.metallurgy.MetallurgyItems.DYNAMIC_METAL));
		};
	}

	private static boolean isStrongMetal(ItemStack stack) {
		if (!stack.isOf(com.projectrealism.metallurgy.MetallurgyItems.DYNAMIC_METAL)) return false;
		var s = com.projectrealism.metallurgy.DynamicMetalItem.readState(stack);
		return com.projectrealism.metallurgy.ContactPhysics.evaluateMetal(
				s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.ORE_HARD, 165.0)
				.effectiveness() >= 0.36;
	}

	private static boolean isAxe(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		if (stack.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE)) {
			var s = com.projectrealism.knapping.DynamicStoneItem.readState(stack);
			return com.projectrealism.knapping.StoneInteractionPhysics.evaluate(
					s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.WOOD_CHOP, 140.0)
					.effectiveness() >= 0.36;
		}
		if (stack.isOf(com.projectrealism.metallurgy.MetallurgyItems.DYNAMIC_METAL)) {
			var s = com.projectrealism.metallurgy.DynamicMetalItem.readState(stack);
			return com.projectrealism.metallurgy.ContactPhysics.evaluateMetal(
					s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.WOOD_CHOP, 150.0)
					.effectiveness() >= 0.36;
		}
		if (stack.isIn(ItemTags.AXES)) {
			return true;
		}
		Item item = stack.getItem();
		return item instanceof net.minecraft.item.AxeItem;
	}

	private static boolean isPickaxe(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		if (stack.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE)) {
			var s = com.projectrealism.knapping.DynamicStoneItem.readState(stack);
			return com.projectrealism.knapping.StoneInteractionPhysics.evaluate(
					s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.STONE_MINE, 160.0)
					.effectiveness() >= 0.36;
		}
		if (stack.isOf(com.projectrealism.metallurgy.MetallurgyItems.DYNAMIC_METAL)) {
			var s = com.projectrealism.metallurgy.DynamicMetalItem.readState(stack);
			return com.projectrealism.metallurgy.ContactPhysics.evaluateMetal(
					s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.STONE_MINE, 165.0)
					.effectiveness() >= 0.36;
		}
		if (stack.isIn(ItemTags.PICKAXES)) {
			return true;
		}
		return stack.getItem() instanceof PickaxeItem;
	}

	/** Human-readable "you need X" line for the action bar. */
	public static Text hintFor(Requirement requirement) {
		return switch (requirement) {
			case HAND -> Text.empty();
			case AXE -> Text.translatable("project_realism.gate.axe");
			case PICKAXE -> Text.translatable("project_realism.gate.pickaxe");
			case STRONG_PICKAXE -> Text.translatable("project_realism.gate.strong_pickaxe");
		};
	}
}
