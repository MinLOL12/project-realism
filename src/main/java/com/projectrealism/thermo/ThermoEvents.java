package com.projectrealism.thermo;

import com.projectrealism.ProjectRealism;
import com.projectrealism.hydration.HydrationItems;
import com.projectrealism.hydration.HydrationManager;
import com.projectrealism.hydration.WaterskinItem;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.Fertilizable;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

/**
 * Interactive event wiring for hearths, friction/spark firemaking, hot-stone conductive cooking,
 * wood ash soil fertilization, and thermal inspection.
 */
public final class ThermoEvents {
	private ThermoEvents() {
	}

	public static void register() {
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (hand != Hand.MAIN_HAND || player.isSpectator()) {
				return ActionResult.PASS;
			}
			BlockPos pos = hit.getBlockPos();
			BlockState state = world.getBlockState(pos);
			ItemStack held = player.getStackInHand(hand);

			// 1. Firemaking tools (Fire Drill, Bow Drill, Flint & Steel, Flint against iron/flint)
			if (Firemaking.isFireStarter(held)) {
				if (world instanceof ServerWorld server) {
					return Firemaking.tryStartFire(player, server, hand, held, hit);
				}
				if (state.isOf(ThermoBlocks.HEARTH)
						|| MaterialProfile.forBlock(state).isCombustible()
						|| player.getOffHandStack().isOf(ThermoItems.TINDER_BUNDLE)) {
					return ActionResult.SUCCESS;
				}
				return ActionResult.PASS;
			}

			// 2. Hearth fuel / cooking food / water container boiling loading
			if (state.isOf(ThermoBlocks.HEARTH) && world.getBlockEntity(pos) instanceof HearthBlockEntity be) {
				if (!held.isEmpty()) {
					boolean isWaterVessel = HydrationManager.isWaterBottle(held)
							|| (held.isOf(HydrationItems.WATERSKIN) && WaterskinItem.getVolumeMl(held) > 0);
					if (CookingPhysics.isFoodTrackable(held) || isWaterVessel) {
						if (world instanceof ServerWorld && be.addFood(held)) {
							held.decrementUnlessCreative(1, player);
							world.playSound(null, pos, SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.BLOCKS, 0.6f, 0.9f);
							return ActionResult.SUCCESS;
						}
						return ActionResult.SUCCESS;
					}
					if (MaterialProfile.forFuelItem(held).isCombustible()) {
						if (world instanceof ServerWorld && be.addFuel(held, world)) {
							held.decrementUnlessCreative(1, player);
							world.playSound(null, pos, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 0.7f, 1.0f);
							return ActionResult.SUCCESS;
						}
						return ActionResult.SUCCESS;
					}
				}
				return ActionResult.PASS; // lets HearthBlock.onUse display status or retrieve top item
			}

			// 3. Hot Stone conductive cooking: right-click a hot stone/metal block (> 375 K) with food!
			if (ProjectRealism.config().thermalCooking && world instanceof ServerWorld server) {
				ThermalWorldState thermo = ThermalWorldState.get(server);
				if (held.isEmpty() && thermo.hotStoneFoods().containsKey(pos)) {
					ThermalWorldState.HotStoneFood hf = thermo.hotStoneFoods().remove(pos);
					if (hf != null && !hf.stack.isEmpty()) {
						if (!player.getInventory().insertStack(hf.stack)) {
							Block.dropStack(server, pos.up(), hf.stack);
						}
						thermo.markDirty();
						server.playSound(null, pos, SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.BLOCKS, 0.6f, 1.1f);
						return ActionResult.SUCCESS;
					}
				}
				if (CookingPhysics.isFoodTrackable(held) && !thermo.hotStoneFoods().containsKey(pos)) {
					MaterialProfile.Properties props = MaterialProfile.forBlock(state);
					if (props.category() == MaterialProfile.MaterialCategory.STONE
							|| props.category() == MaterialProfile.MaterialCategory.METAL
							|| props.category() == MaterialProfile.MaterialCategory.CLAY) {
						double tempK = thermo.blockSurfaceTempK(server, pos);
						if (tempK >= 375.0) {
							thermo.hotStoneFoods().put(pos.toImmutable(),
									new ThermalWorldState.HotStoneFood(pos, held.copyWithCount(1), server.getTime()));
							held.decrementUnlessCreative(1, player);
							thermo.markDirty();
							server.playSound(null, pos, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.5f, 1.4f);
							player.sendMessage(Text.translatable("project_realism.cook.placed_hot_stone",
									(int) Math.round(ThermalConstants.toCelsius(tempK))).formatted(Formatting.GOLD), true);
							return ActionResult.SUCCESS;
						}
					}
				}

				// 4. Wood Ash agricultural fertilizer on crops/saplings/grass
				if (held.isOf(ThermoItems.WOOD_ASH) && state.getBlock() instanceof Fertilizable fert
						&& fert.isFertilizable(server, pos, state)) {
					if (fert.canGrow(server, server.random, pos, state)) {
						fert.grow(server, server.random, pos, state);
						server.spawnParticles(ParticleTypes.HAPPY_VILLAGER,
								pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
								8, 0.35, 0.25, 0.35, 0.02);
						held.decrementUnlessCreative(1, player);
						return ActionResult.SUCCESS;
					}
				}

				// 5. Sneak + empty hand on any block gives a live thermodynamic inspection readout!
				if (held.isEmpty() && player.isSneaking()
						&& !state.isOf(ThermoBlocks.HEARTH)
						&& !(state.getBlock() instanceof com.projectrealism.wood.FallenTrunkBlock)) {
					double surfT = thermo.blockSurfaceTempK(server, pos);
					ThermalWorldState.ThermalNode node = thermo.getNodeReadOnly(pos);
					AirGrid.AirCell air = thermo.airGrid().getReadOnly(
							AirGrid.CellPos.fromBlock(pos.up()), thermo.ambientTempAt(server, pos));
					if (node != null || surfT > 325.0 || air.o2 < 0.195 || air.smoke > 0.08) {
						int surfC = (int) Math.round(ThermalConstants.toCelsius(surfT));
						int coreC = (int) Math.round(ThermalConstants.toCelsius(node != null ? node.body.coreTempK : surfT));
						int moistPct = node != null ? (int) Math.round(node.body.totalMoistureRatio() * 100.0) : 0;
						int o2Pct = (int) Math.round(air.o2 * 100.0);
						player.sendMessage(Text.translatable("project_realism.thermo.inspect",
								surfC, coreC, moistPct, o2Pct).formatted(Formatting.AQUA), true);
						return ActionResult.SUCCESS;
					}
				}
			}

			return ActionResult.PASS;
		});
	}

	public static void tick(net.minecraft.server.MinecraftServer server) {
		for (ServerWorld world : server.getWorlds()) {
			ThermalWorldState.get(world).tick(world);
		}
	}

	public static void onDisconnect(PlayerEntity player) {
		Firemaking.forget(player.getUuid());
	}
}
