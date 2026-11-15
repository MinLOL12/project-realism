package com.projectrealism.thermo;

import com.projectrealism.ProjectRealism;
import com.projectrealism.primitive.PrimitiveItems;
import com.projectrealism.primitive.ToolCondition;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Friction-based and spark-based realistic firemaking.
 *
 * <p>Success depends on physical energy accumulation, ambient wind/rain cooling, oxygen
 * availability from {@link AirGrid}, and continuous fuel/tinder moisture rather than a flat
 * percentage roll.</p>
 */
public final class Firemaking {
	public enum IgnitionOutcome {
		IGNITED,
		WARMING,
		TOO_WET,
		LOW_OXYGEN,
		NO_FUEL
	}

	private static final Map<UUID, Long> LAST_STRIKE_MS = new ConcurrentHashMap<>();

	private Firemaking() {
	}

	public static boolean isFireStarter(ItemStack stack) {
		return !stack.isEmpty() && (stack.isOf(ThermoItems.FIRE_DRILL)
				|| stack.isOf(ThermoItems.BOW_DRILL)
				|| stack.isOf(Items.FLINT_AND_STEEL)
				|| stack.isOf(PrimitiveItems.FLINT_ROCK));
	}

	public static ActionResult tryStartFire(PlayerEntity player, ServerWorld world, Hand hand,
											ItemStack starter, BlockHitResult hit) {
		if (!ProjectRealism.config().realisticFiremaking || !isFireStarter(starter)) {
			return ActionResult.PASS;
		}
		long now = System.currentTimeMillis();
		Long prev = LAST_STRIKE_MS.get(player.getUuid());
		if (prev != null && now - prev < 220L) {
			return ActionResult.SUCCESS;
		}
		LAST_STRIKE_MS.put(player.getUuid(), now);

		boolean isSpark = starter.isOf(Items.FLINT_AND_STEEL) || starter.isOf(PrimitiveItems.FLINT_ROCK);
		double energyJ = starter.isOf(ThermoItems.BOW_DRILL) ? 1450.0
				: (starter.isOf(ThermoItems.FIRE_DRILL) ? 620.0
				: (starter.isOf(Items.FLINT_AND_STEEL) ? 2600.0 : 1200.0));

		// Check if flint rock is being struck against steel/another flint vs bare stone
		if (starter.isOf(PrimitiveItems.FLINT_ROCK)) {
			ItemStack other = player.getStackInHand(hand == Hand.MAIN_HAND ? Hand.OFF_HAND : Hand.MAIN_HAND);
			if (!other.isOf(PrimitiveItems.FLINT_ROCK) && !other.isOf(Items.IRON_INGOT)) {
				return ActionResult.PASS; // allow normal knapping/placement unless striking against flint/iron
			}
			if (other.isOf(Items.IRON_INGOT)) {
				energyJ = 2300.0;
			}
		}

		BlockPos targetPos = hit.getBlockPos();
		BlockState targetState = world.getBlockState(targetPos);

		// 1. Lighting a physical Hearth
		if (targetState.isOf(ThermoBlocks.HEARTH) && world.getBlockEntity(targetPos) instanceof HearthBlockEntity be) {
			damageStarter(player, hand, starter);
			playActionSound(world, targetPos, isSpark);
			IgnitionOutcome outcome = be.applyIgnitionEnergy(world, energyJ, isSpark);
			notifyOutcome(player, outcome, (int) Math.round(ThermalConstants.toCelsius(be.temperatureK())));
			return ActionResult.SUCCESS;
		}

		// 2. Lighting an unlit vanilla campfire with realistic condition checks
		if ((targetState.isOf(Blocks.CAMPFIRE) || targetState.isOf(Blocks.SOUL_CAMPFIRE))
				&& targetState.contains(Properties.LIT) && !targetState.get(Properties.LIT)) {
			ThermalWorldState thermo = ThermalWorldState.get(world);
			ThermalWorldState.ThermalNode node = thermo.getOrCreateNode(world, targetPos);
			AirGrid.AirCell air = thermo.airGrid().getReadOnly(
					AirGrid.CellPos.fromBlock(targetPos), thermo.ambientTempAt(world, targetPos));
			damageStarter(player, hand, starter);
			playActionSound(world, targetPos, isSpark);
			if (air.o2 < ThermalConstants.MIN_O2_FLAME) {
				notifyOutcome(player, IgnitionOutcome.LOW_OXYGEN, 20);
				return ActionResult.SUCCESS;
			}
			double envPenalty = (world.isRaining() && world.isSkyVisible(targetPos.up())) ? 0.20 : 1.0;
			node.pendingExternalHeatW += (energyJ * 2.0) * envPenalty;
			if (node.body.surfaceTempK > 470.0 || (isSpark && envPenalty > 0.5 && node.body.surfaceMoistureRatio() < 0.18)) {
				world.setBlockState(targetPos, targetState.with(Properties.LIT, true), Block.NOTIFY_ALL);
				notifyOutcome(player, IgnitionOutcome.IGNITED, 320);
			} else {
				world.spawnParticles(ParticleTypes.SMOKE,
						targetPos.getX() + 0.5, targetPos.getY() + 0.45, targetPos.getZ() + 0.5,
						3, 0.12, 0.08, 0.12, 0.01);
				notifyOutcome(player, node.body.surfaceMoistureRatio() > 0.22
						? IgnitionOutcome.TOO_WET : IgnitionOutcome.WARMING,
						(int) Math.round(ThermalConstants.toCelsius(node.body.surfaceTempK)));
			}
			return ActionResult.SUCCESS;
		}

		// 3. Using an off-hand Tinder Bundle to start a world fire on target face
		Hand otherHand = hand == Hand.MAIN_HAND ? Hand.OFF_HAND : Hand.MAIN_HAND;
		ItemStack other = player.getStackInHand(otherHand);
		if (other.isOf(ThermoItems.TINDER_BUNDLE)) {
			BlockPos placePos = targetPos.offset(hit.getSide());
			ThermalWorldState thermo = ThermalWorldState.get(world);
			AirGrid.AirCell air = thermo.airGrid().getReadOnly(
					AirGrid.CellPos.fromBlock(placePos), thermo.ambientTempAt(world, placePos));
			damageStarter(player, hand, starter);
			playActionSound(world, placePos, isSpark);

			if (air.o2 < ThermalConstants.MIN_O2_FLAME) {
				notifyOutcome(player, IgnitionOutcome.LOW_OXYGEN, 20);
				return ActionResult.SUCCESS;
			}

			double surfM = ToolCondition.getSurfaceMoisture(other, 0.10);
			if (world.isRaining() && world.isSkyVisible(placePos)) {
				surfM = Math.min(1.0, surfM + 0.15);
			}
			if (surfM > 0.22) {
				// Friction heat dries the tinder slightly but fails to catch while wet!
				double dried = Math.max(0.05, surfM - (energyJ / 18000.0));
				ToolCondition.setMoisture(other, dried, dried);
				world.spawnParticles(ParticleTypes.CLOUD,
						placePos.getX() + 0.5, placePos.getY() + 0.3, placePos.getZ() + 0.5,
						2, 0.1, 0.08, 0.1, 0.01);
				notifyOutcome(player, IgnitionOutcome.TOO_WET, 85);
				return ActionResult.SUCCESS;
			}

			if (world.isAir(placePos)) {
				other.decrementUnlessCreative(1, player);
				world.setBlockState(placePos, Blocks.FIRE.getDefaultState(), Block.NOTIFY_ALL);
				thermo.radiateHeatSource(world, placePos, 780.0, 0.6, 0.5);
				notifyOutcome(player, IgnitionOutcome.IGNITED, 500);
				return ActionResult.SUCCESS;
			}
		}

		// 4. Direct friction / spark on any world combustible block (logs, planks, leaves, split wood)
		MaterialProfile.Properties props = MaterialProfile.forBlock(targetState);
		if (props.isCombustible()) {
			ThermalWorldState thermo = ThermalWorldState.get(world);
			ThermalWorldState.ThermalNode node = thermo.getOrCreateNode(world, targetPos);
			AirGrid.AirCell air = thermo.airGrid().getReadOnly(
					AirGrid.CellPos.fromBlock(targetPos), thermo.ambientTempAt(world, targetPos));
			damageStarter(player, hand, starter);
			playActionSound(world, targetPos, isSpark);

			if (air.o2 < ThermalConstants.MIN_O2_FLAME) {
				notifyOutcome(player, IgnitionOutcome.LOW_OXYGEN, 20);
				return ActionResult.SUCCESS;
			}

			double shellCap = ThermalMath.heatCapacityJPerK(
					Math.max(0.05, node.body.dryWoodMassKg * 0.30),
					props.specificHeat(), node.body.surfaceMoistureKg);
			if (node.body.surfaceMoistureKg > 0.01) {
				double evap = Math.min(node.body.surfaceMoistureKg, (energyJ * 0.7) / ThermalConstants.WATER_LATENT_VAPORIZATION);
				node.body.surfaceMoistureKg -= evap;
				energyJ -= evap * ThermalConstants.WATER_LATENT_VAPORIZATION;
			}
			node.body.surfaceTempK = Math.min(1150.0, node.body.surfaceTempK + Math.max(0.0, energyJ * 1.4) / shellCap);
			double effIgn = props.ignitionTempK() + node.body.surfaceMoistureRatio() * 220.0;

			if (node.body.surfaceTempK >= effIgn) {
				node.body.flaming = true;
				BlockPos firePos = targetPos.offset(hit.getSide());
				if (world.isAir(firePos)) {
					world.setBlockState(firePos, Blocks.FIRE.getDefaultState(), Block.NOTIFY_ALL);
				}
				notifyOutcome(player, IgnitionOutcome.IGNITED, (int) Math.round(ThermalConstants.toCelsius(node.body.surfaceTempK)));
			} else {
				world.spawnParticles(node.body.surfaceMoistureRatio() > 0.22 ? ParticleTypes.CLOUD : ParticleTypes.SMOKE,
						targetPos.getX() + 0.5, targetPos.getY() + 0.85, targetPos.getZ() + 0.5,
						2, 0.15, 0.1, 0.15, 0.01);
				notifyOutcome(player, node.body.surfaceMoistureRatio() > 0.22
						? IgnitionOutcome.TOO_WET : IgnitionOutcome.WARMING,
						(int) Math.round(ThermalConstants.toCelsius(node.body.surfaceTempK)));
			}
			return ActionResult.SUCCESS;
		}

		return ActionResult.PASS;
	}

	private static void damageStarter(PlayerEntity player, Hand hand, ItemStack stack) {
		if (!player.isCreative() && stack.isDamageable()) {
			stack.damage(1, player, hand == Hand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
		}
	}

	private static void playActionSound(ServerWorld world, BlockPos pos, boolean spark) {
		world.playSound(null, pos,
				spark ? SoundEvents.ITEM_FLINTANDSTEEL_USE : SoundEvents.BLOCK_WOOD_STEP,
				SoundCategory.BLOCKS, 0.8f, spark ? 1.0f : 1.55f);
	}

	private static void notifyOutcome(PlayerEntity player, IgnitionOutcome outcome, int tempC) {
		switch (outcome) {
			case IGNITED -> player.sendMessage(Text.translatable("project_realism.fire.ignited")
					.formatted(Formatting.GOLD), true);
			case WARMING -> player.sendMessage(Text.translatable("project_realism.fire.warming", tempC)
					.formatted(Formatting.YELLOW), true);
			case TOO_WET -> player.sendMessage(Text.translatable("project_realism.fire.too_wet", tempC)
					.formatted(Formatting.AQUA), true);
			case LOW_OXYGEN -> player.sendMessage(Text.translatable("project_realism.fire.low_o2")
					.formatted(Formatting.RED), true);
			case NO_FUEL -> player.sendMessage(Text.translatable("project_realism.fire.no_fuel")
					.formatted(Formatting.GRAY), true);
		}
	}

	public static void forget(UUID uuid) {
		LAST_STRIKE_MS.remove(uuid);
	}
}
