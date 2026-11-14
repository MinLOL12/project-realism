package com.projectrealism.wood;

import com.projectrealism.ProjectRealism;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Shared drying schedule & environmental check for fresh logs, split wood, and rough planks.
 */
public final class DryingWoodHelper {
	public static final IntProperty MOISTURE = IntProperty.of("moisture", 0, 3);

	private DryingWoodHelper() {
	}

	public static int computeStepDelayTicks(World world, BlockPos pos, WoodSpecies species, float speedBonus) {
		double configMult = Math.max(0.1, ProjectRealism.config().woodDrySpeedMultiplier);
		double envMult = 1.0;
		if (hasHeatNearby(world, pos)) {
			envMult = 4.0;
		} else if (world.isDay() && world.isSkyVisible(pos.up())) {
			envMult = 1.5;
		}
		int ticks = (int) Math.round((species.dryStepTicks() / (configMult * envMult * speedBonus)));
		return Math.max(40, ticks);
	}

	public static boolean isRainingOn(World world, BlockPos pos) {
		return world.isRaining() && world.isSkyVisible(pos.up()) && !hasHeatNearby(world, pos);
	}

	public static boolean hasHeatNearby(World world, BlockPos pos) {
		for (int dx = -3; dx <= 3; dx++) {
			for (int dy = -2; dy <= 2; dy++) {
				for (int dz = -3; dz <= 3; dz++) {
					BlockState s = world.getBlockState(pos.add(dx, dy, dz));
					Block b = s.getBlock();
					if (b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE
							|| b == Blocks.FIRE || b == Blocks.SOUL_FIRE) {
						if (!s.contains(Properties.LIT) || s.get(Properties.LIT)) {
							return true;
						}
					} else if ((b == Blocks.FURNACE || b == Blocks.SMOKER || b == Blocks.BLAST_FURNACE)
							&& s.contains(Properties.LIT) && s.get(Properties.LIT)) {
						return true;
					}
				}
			}
		}
		return false;
	}

	public static void spawnDryingSteam(ServerWorld world, BlockPos pos) {
		if (hasHeatNearby(world, pos)) {
			world.spawnParticles(ParticleTypes.CLOUD,
					pos.getX() + 0.5, pos.getY() + 0.65, pos.getZ() + 0.5,
					2, 0.18, 0.12, 0.18, 0.01);
		}
	}

	public static Text moistureStatusText(World world, BlockPos pos, int moisture, WoodSpecies species) {
		if (moisture <= 0) {
			return Text.translatable("project_realism.wood.status_dry", species.displayName())
					.formatted(Formatting.GOLD);
		}
		if (isRainingOn(world, pos)) {
			return Text.translatable("project_realism.wood.status_rain", species.displayName(), moisture * 33)
					.formatted(Formatting.BLUE);
		}
		String key = hasHeatNearby(world, pos)
				? "project_realism.wood.status_drying_fast"
				: "project_realism.wood.status_drying";
		return Text.translatable(key, species.displayName(), moisture * 33).formatted(Formatting.GREEN);
	}
}
