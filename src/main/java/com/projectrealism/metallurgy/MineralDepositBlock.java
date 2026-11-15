package com.projectrealism.metallurgy;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.Random;

/**
 * Geological mineral deposit block generating in regional ore veins and alluvial beds.
 * Dropping physical {@link OreComposition} chunks or native copper nuggets scaled by regional
 * grade and host depth rather than static fixed ingot drops.
 */
public class MineralDepositBlock extends Block {
	public enum DepositKind {
		NATIVE_COPPER,
		MALACHITE_AZURITE,
		CHALCOPYRITE,
		CASSITERITE,
		ARSENOPYRITE,
		HEMATITE_MAGNETITE,
		LIMONITE_BOG,
		FIRE_CLAY
	}

	private final DepositKind kind;

	public MineralDepositBlock(Settings settings, DepositKind kind) {
		super(settings);
		this.kind = kind;
	}

	public DepositKind depositKind() {
		return kind;
	}

	@Override
	public BlockState onBreak(World world, BlockPos pos, BlockState state, PlayerEntity player) {
		if (world instanceof ServerWorld sw && !player.isCreative()) {
			dropMineralYield(sw, pos);
		}
		return super.onBreak(world, pos, state, player);
	}

	public void dropMineralYield(ServerWorld sw, BlockPos pos) {
		long seed = pos.asLong() ^ sw.getSeed();
		Random rng = new Random(seed);
		double gradeVar = 0.72 + rng.nextDouble() * 0.56;

		switch (kind) {
			case NATIVE_COPPER -> {
				if (rng.nextDouble() < 0.65) {
					DynamicMetalState nugget = DynamicMetalState.createNativeNugget(seed, 0.58 + rng.nextDouble() * 0.34,
							0.92 + rng.nextDouble() * 0.06);
					dropStack(sw, pos, DynamicMetalItem.create(nugget));
				}
				OreComposition ore = new OreComposition(0.45 * gradeVar);
				ore.addMineral(MineralSpecies.NATIVE_COPPER, 0.52);
				ore.addMineral(MineralSpecies.MALACHITE, 0.14);
				ore.addMineral(MineralSpecies.SILICA_GANGUE, 0.34);
				dropStack(sw, pos, OreChunkItem.create(ore));
			}
			case MALACHITE_AZURITE -> {
				int count = 1 + (rng.nextDouble() < 0.45 ? 1 : 0);
				for (int i = 0; i < count; i++) {
					OreComposition ore = OreComposition.malachiteOre(0.50 * gradeVar, 0.24 + rng.nextDouble() * 0.28);
					if (rng.nextDouble() < 0.40) ore.addMineral(MineralSpecies.AZURITE, 0.22);
					dropStack(sw, pos, OreChunkItem.create(ore));
				}
			}
			case CHALCOPYRITE -> {
				OreComposition ore = OreComposition.chalcopyriteOre(0.55 * gradeVar, 0.22 + rng.nextDouble() * 0.26);
				dropStack(sw, pos, OreChunkItem.create(ore));
			}
			case CASSITERITE -> {
				OreComposition ore = OreComposition.cassiteriteOre(0.42 * gradeVar, 0.26 + rng.nextDouble() * 0.28);
				dropStack(sw, pos, OreChunkItem.create(ore));
			}
			case ARSENOPYRITE -> {
				OreComposition ore = new OreComposition(0.45 * gradeVar);
				ore.addMineral(MineralSpecies.ARSENOPYRITE, 0.48);
				ore.addMineral(MineralSpecies.CHALCOPYRITE, 0.18);
				ore.addMineral(MineralSpecies.SILICA_GANGUE, 0.34);
				ore.sulfurContent = 0.18;
				dropStack(sw, pos, OreChunkItem.create(ore));
			}
			case HEMATITE_MAGNETITE -> {
				int count = 1 + (rng.nextDouble() < 0.5 ? 1 : 0);
				for (int i = 0; i < count; i++) {
					OreComposition ore = OreComposition.ironOre(0.60 * gradeVar,
							pos.getY() < 16 ? MineralSpecies.MAGNETITE : MineralSpecies.HEMATITE,
							0.20 + rng.nextDouble() * 0.24);
					dropStack(sw, pos, OreChunkItem.create(ore));
				}
			}
			case LIMONITE_BOG -> {
				OreComposition ore = OreComposition.ironOre(0.48 * gradeVar, MineralSpecies.LIMONITE, 0.34);
				dropStack(sw, pos, OreChunkItem.create(ore));
			}
			case FIRE_CLAY -> dropStack(sw, pos, new ItemStack(MetallurgyItems.FIRE_CLAY_BALL, 2 + rng.nextInt(3)));
		}
	}
}
