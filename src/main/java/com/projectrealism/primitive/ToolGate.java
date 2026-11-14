package com.projectrealism.primitive;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
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
		PICKAXE
	}

	/** The minimum tool demanded by this block state. */
	public static Requirement requirementFor(BlockState state) {
		// The pebble blocks are themselves a resource, so they must never be gated behind the tool that
		// they are the raw material for.
		if (state.getBlock() instanceof GroundRockBlock) {
			return Requirement.HAND;
		}

		if (state.isIn(BlockTags.LOGS) || state.isIn(BlockTags.PLANKS)
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
		};
	}

	private static boolean isAxe(ItemStack stack) {
		if (stack.isEmpty()) {
			return false;
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
		if (stack.isIn(ItemTags.PICKAXES)) {
			return true;
		}
		return stack.getItem() instanceof net.minecraft.item.PickaxeItem;
	}

	/** Human-readable "you need X" line for the action bar. */
	public static Text hintFor(Requirement requirement) {
		return switch (requirement) {
			case HAND -> Text.empty();
			case AXE -> Text.translatable("project_realism.gate.axe");
			case PICKAXE -> Text.translatable("project_realism.gate.pickaxe");
		};
	}
}
