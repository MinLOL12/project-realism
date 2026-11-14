package com.projectrealism.wood;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.Item;

import java.util.Optional;

/**
 * Physical and processing characteristics per wood species.
 *
 * <ul>
 *   <li>Oak: High hardness, Heavy, Slow processing</li>
 *   <li>Birch: Medium hardness, Light, Fast processing</li>
 *   <li>Spruce: Low hardness, Light, Fast processing</li>
 *   <li>Dark Oak: Very high hardness, Very heavy, Very slow processing</li>
 * </ul>
 */
public enum WoodSpecies {
	OAK("oak", "Oak", 1.45f, 12.0, 1.45f, 350),
	BIRCH("birch", "Birch", 1.0f, 6.5, 0.85f, 200),
	SPRUCE("spruce", "Spruce", 0.7f, 5.5, 0.75f, 150),
	DARK_OAK("dark_oak", "Dark Oak", 1.9f, 16.0, 2.0f, 500);

	private final String id;
	private final String displayName;
	private final float hardness;
	private final double logWeightKg;
	private final float processingFactor;
	private final int dryStepTicks;

	WoodSpecies(String id, String displayName, float hardness, double logWeightKg,
				float processingFactor, int dryStepTicks) {
		this.id = id;
		this.displayName = displayName;
		this.hardness = hardness;
		this.logWeightKg = logWeightKg;
		this.processingFactor = processingFactor;
		this.dryStepTicks = dryStepTicks;
	}

	public String id() {
		return id;
	}

	public String displayName() {
		return displayName;
	}

	public float hardness() {
		return hardness;
	}

	public double logWeightKg() {
		return logWeightKg;
	}

	public float processingFactor() {
		return processingFactor;
	}

	public int dryStepTicks() {
		return dryStepTicks;
	}

	public Block vanillaLog() {
		return switch (this) {
			case OAK -> Blocks.OAK_LOG;
			case BIRCH -> Blocks.BIRCH_LOG;
			case SPRUCE -> Blocks.SPRUCE_LOG;
			case DARK_OAK -> Blocks.DARK_OAK_LOG;
		};
	}

	public Block vanillaPlanks() {
		return switch (this) {
			case OAK -> Blocks.OAK_PLANKS;
			case BIRCH -> Blocks.BIRCH_PLANKS;
			case SPRUCE -> Blocks.SPRUCE_PLANKS;
			case DARK_OAK -> Blocks.DARK_OAK_PLANKS;
		};
	}

	public Block notchedLogBlock() {
		return WoodBlocks.notchedLog(this);
	}

	public Block fallenTrunkBlock() {
		return WoodBlocks.fallenTrunk(this);
	}

	public Block freshLogBlock() {
		return WoodBlocks.freshLog(this);
	}

	public Block splitWoodBlock() {
		return WoodBlocks.splitWood(this);
	}

	public Block roughPlankBlock() {
		return WoodBlocks.roughPlank(this);
	}

	public Item freshLogItem() {
		return WoodItems.freshLog(this);
	}

	public Item splitWoodItem() {
		return WoodItems.splitWood(this);
	}

	public Item roughPlankItem() {
		return WoodItems.roughPlank(this);
	}

	public static Optional<WoodSpecies> fromBlock(Block block) {
		if (block == Blocks.OAK_LOG || block == Blocks.OAK_WOOD
				|| block == Blocks.STRIPPED_OAK_LOG || block == Blocks.STRIPPED_OAK_WOOD
				|| block == Blocks.ACACIA_LOG || block == Blocks.ACACIA_WOOD
				|| block == Blocks.STRIPPED_ACACIA_LOG || block == Blocks.STRIPPED_ACACIA_WOOD
				|| block == Blocks.JUNGLE_LOG || block == Blocks.JUNGLE_WOOD
				|| block == Blocks.STRIPPED_JUNGLE_LOG || block == Blocks.STRIPPED_JUNGLE_WOOD) {
			return Optional.of(OAK);
		}
		if (block == Blocks.BIRCH_LOG || block == Blocks.BIRCH_WOOD
				|| block == Blocks.STRIPPED_BIRCH_LOG || block == Blocks.STRIPPED_BIRCH_WOOD
				|| block == Blocks.CHERRY_LOG || block == Blocks.CHERRY_WOOD
				|| block == Blocks.STRIPPED_CHERRY_LOG || block == Blocks.STRIPPED_CHERRY_WOOD) {
			return Optional.of(BIRCH);
		}
		if (block == Blocks.SPRUCE_LOG || block == Blocks.SPRUCE_WOOD
				|| block == Blocks.STRIPPED_SPRUCE_LOG || block == Blocks.STRIPPED_SPRUCE_WOOD) {
			return Optional.of(SPRUCE);
		}
		if (block == Blocks.DARK_OAK_LOG || block == Blocks.DARK_OAK_WOOD
				|| block == Blocks.STRIPPED_DARK_OAK_LOG || block == Blocks.STRIPPED_DARK_OAK_WOOD
				|| block == Blocks.MANGROVE_LOG || block == Blocks.MANGROVE_WOOD
				|| block == Blocks.STRIPPED_MANGROVE_LOG || block == Blocks.STRIPPED_MANGROVE_WOOD) {
			return Optional.of(DARK_OAK);
		}
		for (WoodSpecies sp : values()) {
			if (block == sp.notchedLogBlock() || block == sp.fallenTrunkBlock()
					|| block == sp.freshLogBlock() || block == sp.splitWoodBlock()
					|| block == sp.roughPlankBlock()) {
				return Optional.of(sp);
			}
		}
		return Optional.empty();
	}

	public static boolean isDryLog(Block block) {
		return block == Blocks.OAK_LOG || block == Blocks.BIRCH_LOG
				|| block == Blocks.SPRUCE_LOG || block == Blocks.DARK_OAK_LOG
				|| block == Blocks.STRIPPED_OAK_LOG || block == Blocks.STRIPPED_BIRCH_LOG
				|| block == Blocks.STRIPPED_SPRUCE_LOG || block == Blocks.STRIPPED_DARK_OAK_LOG;
	}
}
