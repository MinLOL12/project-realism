package com.projectrealism.hydration;

import com.projectrealism.ProjectRealism;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsage;
import net.minecraft.item.Items;
import net.minecraft.potion.Potions;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wires water-bottle quality tagging, bare-handed water source drinking, rain catching,
 * charcoal/fiber filtration, and electrolyte salt mixing.
 */
public final class HydrationEvents {
	private static final Map<UUID, Long> LAST_SIP_MS = new ConcurrentHashMap<>();

	private HydrationEvents() {
	}

	public static void register() {
		UseItemCallback.EVENT.register((player, world, hand) -> {
			if (!ProjectRealism.config().hydrationSimulation || player.isSpectator()) {
				return TypedActionResult.pass(player.getStackInHand(hand));
			}
			ItemStack main = player.getStackInHand(hand);
			ItemStack other = player.getStackInHand(hand == Hand.MAIN_HAND ? Hand.OFF_HAND : Hand.MAIN_HAND);

			// 1. Charcoal Water Filter in off-hand + dirty Water Bottle / Waterskin in main hand
			if (hand == Hand.MAIN_HAND && other.isOf(HydrationItems.WATER_FILTER) && isWaterContainerWithWater(main)) {
				if (world instanceof ServerWorld sw) {
					WaterQuality cur = WaterQuality.read(main, WaterQuality.RIVER_WATER);
					WaterQuality filtered = cur.filterWithCharcoal();
					WaterQuality.write(main, filtered);
					if (!player.isCreative()) {
						other.damage(1, player, EquipmentSlot.OFFHAND);
					}
					sw.playSound(null, player.getBlockPos(), SoundEvents.ITEM_BOTTLE_EMPTY,
							SoundCategory.PLAYERS, 0.6f, 1.35f);
					player.sendMessage(Text.translatable("project_realism.hydration.filtered",
							String.format("%.1f", filtered.salinityGPerL())).formatted(Formatting.AQUA), true);
				}
				return TypedActionResult.success(main, world.isClient());
			}

			// 2. Electrolyte Salts in off-hand + Water Bottle / Waterskin in main hand
			if (hand == Hand.MAIN_HAND && other.isOf(HydrationItems.ELECTROLYTE_SALTS) && isWaterContainerWithWater(main)) {
				if (world instanceof ServerWorld sw) {
					WaterQuality cur = WaterQuality.read(main, WaterQuality.FRESH_SPRING);
					WaterQuality enriched = new WaterQuality(
							cur.tempK(),
							Math.min(10.0, cur.salinityGPerL() + 2.6), // ~45 mM NaCl isotonic ORS
							cur.turbidity(),
							Math.min(2000.0, cur.mineralMgPerL() + 750.0), // adds bioavailable K+ minerals
							cur.microbialLoad(),
							cur.chemicalLoad()
					).clamped();
					WaterQuality.write(main, enriched);
					other.decrementUnlessCreative(1, player);
					sw.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_BREWING_STAND_BREW,
							SoundCategory.PLAYERS, 0.5f, 1.3f);
					player.sendMessage(Text.translatable("project_realism.hydration.salts_mixed")
							.formatted(Formatting.GOLD), true);
				}
				return TypedActionResult.success(main, world.isClient());
			}

			// 3. Direct ingestion of dry Electrolyte Salts without container
			if (hand == Hand.MAIN_HAND && main.isOf(HydrationItems.ELECTROLYTE_SALTS)
					&& !isWaterContainerWithWater(other)) {
				if (player instanceof ServerPlayerEntity sp) {
					BodyFluidMath.BodyFluidState st = HydrationManager.get(sp.getServerWorld()).getOrCreateState(sp.getUuid());
					BodyFluidMath.ingestFood(st, 0.0, 42.0, 18.0, 38.0);
					main.decrementUnlessCreative(1, player);
					sp.sendMessage(Text.translatable("project_realism.hydration.salts_eaten")
							.formatted(Formatting.YELLOW), true);
				}
				return TypedActionResult.success(main, world.isClient());
			}

			// 4. Glass Bottle filled from real water source OR rain -> carries exact WaterQuality!
			if (main.isOf(Items.GLASS_BOTTLE)) {
				BlockHitResult hit = raycastWater(world, player);
				if (hit.getType() == HitResult.Type.BLOCK) {
					BlockPos waterPos = hit.getBlockPos();
					FluidState fs = world.getFluidState(waterPos);
					if (fs.isIn(FluidTags.WATER)) {
						if (world instanceof ServerWorld sw) {
							ItemStack bottle = PotionContentsComponent.createStack(Items.POTION, Potions.WATER);
							WaterQuality.write(bottle, WaterQuality.fromWorld(sw, waterPos));
							sw.playSound(null, player.getBlockPos(), SoundEvents.ITEM_BOTTLE_FILL,
									SoundCategory.PLAYERS, 0.8f, 1.0f);
							return TypedActionResult.success(ItemUsage.exchangeStack(main, player, bottle), false);
						}
						return TypedActionResult.success(main, true);
					}
				}
				if (player.isSneaking() && player.getPitch() < -35.0f && world.isRaining() && world.isSkyVisible(player.getBlockPos())) {
					if (world instanceof ServerWorld sw) {
						ItemStack bottle = PotionContentsComponent.createStack(Items.POTION, Potions.WATER);
						WaterQuality.write(bottle, WaterQuality.fromRain(sw, player.getBlockPos()));
						sw.playSound(null, player.getBlockPos(), SoundEvents.ITEM_BOTTLE_FILL,
								SoundCategory.PLAYERS, 0.6f, 1.4f);
						return TypedActionResult.success(ItemUsage.exchangeStack(main, player, bottle), false);
					}
					return TypedActionResult.success(main, true);
				}
			}

			// 5. Sneak + empty main hand looking at water source -> cup hands and drink 180 mL directly
			if (hand == Hand.MAIN_HAND && main.isEmpty() && player.isSneaking()) {
				BlockHitResult hit = raycastWater(world, player);
				if (hit.getType() == HitResult.Type.BLOCK) {
					BlockPos waterPos = hit.getBlockPos();
					if (world.getFluidState(waterPos).isIn(FluidTags.WATER)) {
						long now = System.currentTimeMillis();
						Long prev = LAST_SIP_MS.get(player.getUuid());
						if (prev != null && now - prev < 650L) {
							return TypedActionResult.success(main, world.isClient());
						}
						LAST_SIP_MS.put(player.getUuid(), now);
						if (player instanceof ServerPlayerEntity sp) {
							ServerWorld sw = sp.getServerWorld();
							WaterQuality q = WaterQuality.fromWorld(sw, waterPos);
							HydrationManager.get(sw).drink(sp, 0.180, q);
							sw.playSound(null, sp.getBlockPos(), SoundEvents.ENTITY_GENERIC_DRINK,
									SoundCategory.PLAYERS, 0.6f, 1.05f);
						}
						return TypedActionResult.success(main, world.isClient());
					}
				}
			}

			return TypedActionResult.pass(main);
		});
	}

	private static boolean isWaterContainerWithWater(ItemStack stack) {
		if (stack.isOf(HydrationItems.WATERSKIN)) {
			return WaterskinItem.getVolumeMl(stack) > 0;
		}
		if (stack.isOf(com.projectrealism.material.MaterialItems.GOURD_FLASK)) {
			return com.projectrealism.material.GourdFlaskItem.getVolumeMl(stack) > 0;
		}
		if (stack.isOf(com.projectrealism.anatomy.AnatomyItems.HORN_FLASK)) {
			return com.projectrealism.anatomy.HornFlaskItem.getVolumeMl(stack) > 0;
		}
		return HydrationManager.isWaterBottle(stack);
	}

	private static BlockHitResult raycastWater(World world, PlayerEntity player) {
		Vec3d eye = player.getEyePos();
		Vec3d end = eye.add(player.getRotationVec(1.0f).multiply(4.5));
		return world.raycast(new RaycastContext(
				eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.SOURCE_ONLY, player));
	}

	public static void onDisconnect(PlayerEntity player) {
		LAST_SIP_MS.remove(player.getUuid());
	}
}
