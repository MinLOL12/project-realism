package com.projectrealism.metallurgy;

import com.projectrealism.ProjectRealism;
import com.projectrealism.thermo.HearthBlock;
import com.projectrealism.thermo.HearthBlockEntity;
import com.projectrealism.thermo.ThermoItems;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Diegetic world interactions for physical metallurgy:
 * - Charcoal pit kiln burning (sealed timber pile fired to produce charcoal fuel)
 * - Slag crushing on hard stone to liberate trapped copper prills
 * - Bellows pumping into hearths
 * - Direct annealing/heating of {@link DynamicMetalItem} over hot hearth embers
 */
public final class MetallurgyEvents {
	private MetallurgyEvents() {
	}

	public static void register() {
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (player.isSpectator() || !ProjectRealism.config().physicalMetallurgy) {
				return ActionResult.PASS;
			}
			ItemStack held = player.getStackInHand(hand);
			BlockPos pos = hit.getBlockPos();
			BlockState state = world.getBlockState(pos);

			// 1. Bellows pumping air onto HearthBlock fans ember bed higher!
			if (held.isOf(MetallurgyItems.LEATHER_BELLOWS) && state.getBlock() instanceof HearthBlock
					&& world.getBlockEntity(pos) instanceof HearthBlockEntity hearth) {
				if (world instanceof ServerWorld sw) {
					sw.playSound(null, pos, SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.BLOCKS, 0.45f, 0.8f);
					sw.spawnParticles(ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 0.35, pos.getZ() + 0.5,
							6, 0.16, 0.10, 0.16, 0.04);
					player.sendMessage(Text.literal(String.format("Fanned hearth embers (%.0f°C)",
							hearth.temperatureK() - 273.15)).formatted(Formatting.GOLD), true);
				}
				return ActionResult.SUCCESS;
			}

			// 2. Holding DYNAMIC_METAL against burning Hearth heats/anneals it modestly (up to hearth bed temp)
			if (held.isOf(MetallurgyItems.DYNAMIC_METAL) && state.getBlock() instanceof HearthBlock
					&& world.getBlockEntity(pos) instanceof HearthBlockEntity hearth) {
				if (world instanceof ServerWorld) {
					double bedK = hearth.temperatureK();
					if (bedK > 560.0) {
						DynamicMetalState ms = DynamicMetalItem.readState(held);
						if (bedK > ms.temperatureK) {
							ms.temperatureK = Math.min(bedK, ms.temperatureK + (bedK - ms.temperatureK) * 0.55);
							ms.grid.micro.stepThermalEvolution(ms.alloy, ms.temperatureK, 8.0, true);
							DynamicMetalItem.writeState(held, ms);
							world.playSound(null, pos, SoundEvents.BLOCK_FIRE_AMBIENT, SoundCategory.BLOCKS, 0.5f, 1.15f);
							player.sendMessage(Text.literal(String.format("Annealed/heated metal to %.0f°C (Disloc %.0f%%)",
											ms.temperatureK - 273.15, ms.grid.micro.averageDislocation() * 100))
									.formatted(Formatting.GOLD), true);
							return ActionResult.SUCCESS;
						}
					}
				}
			}

			// 3. Crushing SLAG_CLUMP on hard rock recovers small copper prill bits if lucky!
			if (held.isOf(MetallurgyItems.SLAG_CLUMP) && (state.isOf(Blocks.STONE)
					|| state.isOf(Blocks.DEEPSLATE) || state.isOf(Blocks.BASALT) || state.isOf(Blocks.ANVIL))) {
				if (world instanceof ServerWorld sw) {
					if (!player.isCreative()) held.decrement(1);
					sw.playSound(null, pos, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 0.6f, 1.1f);
					if (sw.random.nextFloat() < 0.35f) {
						DynamicMetalState prill = DynamicMetalState.createNativeNugget(sw.random.nextLong(), 0.45, 0.88);
						Block.dropStack(sw, pos.up(), DynamicMetalItem.create(prill));
						player.sendMessage(Text.literal("Freed trapped copper prill from slag!").formatted(Formatting.GREEN), true);
					} else {
						player.sendMessage(Text.literal("Crushed barren silicate slag.").formatted(Formatting.GRAY), true);
					}
				}
				return ActionResult.SUCCESS;
			}

			// 4. Pit Charcoal Kiln: lighting covered timber pile converts enclosed logs cleanly into CHARCOAL!
			if ((held.isOf(ThermoItems.FIRE_DRILL) || held.isOf(ThermoItems.BOW_DRILL)
					|| held.isOf(Items.FLINT_AND_STEEL))
					&& state.isIn(BlockTags.LOGS)) {
				if (world instanceof ServerWorld sw) {
					int converted = tryBurnCharcoalKiln(sw, pos);
					if (converted > 0) {
						sw.playSound(null, pos, SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.BLOCKS, 0.7f, 0.85f);
						player.sendMessage(Text.literal("Fired enclosed charcoal kiln — harvested " + (converted * 3)
								+ " charcoal!").formatted(Formatting.GOLD), true);
						return ActionResult.SUCCESS;
					}
				}
			}

			return ActionResult.PASS;
		});
	}

	/**
	 * Checks whether clicked log is surrounded/covered on >= 5 sides by soil, stone, clay, or other
	 * kiln logs (oxygen-starved pyrolysis pit). Converts enclosed wood blocks into physical charcoal drops!
	 */
	private static int tryBurnCharcoalKiln(ServerWorld sw, BlockPos seed) {
		List<BlockPos> logs = new ArrayList<>();
		for (int dy = -1; dy <= 1; dy++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					BlockPos p = seed.add(dx, dy, dz);
					if (sw.getBlockState(p).isIn(BlockTags.LOGS)) {
						logs.add(p);
					}
				}
			}
		}
		if (logs.isEmpty()) return 0;

		int sealedCount = 0;
		for (BlockPos lp : logs) {
			int covered = 0;
			for (BlockPos nb : new BlockPos[]{lp.up(), lp.down(), lp.north(), lp.south(), lp.east(), lp.west()}) {
				BlockState ns = sw.getBlockState(nb);
				if (!ns.isAir() && !ns.isReplaceable()) covered++;
			}
			if (covered >= 5) sealedCount++;
		}
		if (sealedCount < Math.max(1, logs.size() / 2)) {
			return 0; // Not buried under dirt/clay enough -> regular combustion instead!
		}

		for (BlockPos lp : logs) {
			sw.setBlockState(lp, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
			Block.dropStack(sw, lp, new ItemStack(Items.CHARCOAL, 3));
			sw.spawnParticles(ParticleTypes.LARGE_SMOKE, lp.getX() + 0.5, lp.getY() + 0.5, lp.getZ() + 0.5,
					6, 0.25, 0.25, 0.25, 0.02);
		}
		return logs.size();
	}
}
