package com.projectrealism.material;

import com.projectrealism.ProjectRealism;
import com.projectrealism.hydration.HydrationItems;
import com.projectrealism.hydration.WaterQuality;
import com.projectrealism.hydration.WaterskinItem;
import com.projectrealism.primitive.PrimitiveItems;
import com.projectrealism.thermo.ThermalConstants;
import com.projectrealism.thermo.ThermalWorldState;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * World event wiring for anatomical animal hide drops, pine resin bark scoring, wild gourd
 * foraging, scraper resharpening, active waterskin slosh/seam-leak physics, rigid gourd impact
 * cracking, and environmental container aging.
 */
public final class MaterialEvents {
	private static final Map<UUID, Vec3d> PREV_VEL = new HashMap<>();
	private static final Map<UUID, Float> PREV_FALL = new HashMap<>();
	private static final Map<UUID, Long> LAST_LEAK_MSG_MS = new HashMap<>();
	private static int tickCounter = 0;

	private MaterialEvents() {
	}

	public static void register() {
		// 1. Anatomically informed raw hide drops ONLY when carcass system is disabled
		ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
			if (!ProjectRealism.config().materialProcessing || ProjectRealism.config().carcassSystem) return;
			if (!(entity.getWorld() instanceof ServerWorld sw)) return;
			if (!AnimalHideType.isHideBearing(entity.getType())) return;

			AnimalHideType type = AnimalHideType.fromEntity(entity);
			HideSurface surface = new HideSurface(type);
			boolean fire = source.isIn(DamageTypeTags.IS_FIRE);
			double trauma = computeTrauma(entity, source);
			surface.applyKillTrauma(new Random(sw.random.nextLong()), trauma, fire);
			Block.dropStack(sw, entity.getBlockPos(), RawHideItem.create(surface));
		});

		// 2. Wild raw gourd recovery from pumpkins/melons/jungle foliage
		PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, entity) -> {
			if (!(world instanceof ServerWorld sw) || player.isCreative()) return;
			if (!ProjectRealism.config().materialProcessing) return;
			if (state.isOf(Blocks.PUMPKIN) || state.isOf(Blocks.MELON)
					|| (state.isOf(Blocks.VINE) && sw.random.nextFloat() < 0.14f)
					|| (state.isOf(Blocks.LARGE_FERN) && sw.random.nextFloat() < 0.12f)) {
				ItemStack gourd = new ItemStack(MaterialItems.RAW_GOURD);
				MaterialItems.RawGourdItem.writeGourdState(gourd, new MaterialAging.GourdState());
				Block.dropStack(sw, pos, gourd);
			}
		});

		// 3. Pine resin bark scoring on spruce logs with Stone Knife or Stone Scraper
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (!ProjectRealism.config().materialProcessing || player.isSpectator()) return ActionResult.PASS;
			ItemStack held = player.getStackInHand(hand);
			if (!held.isOf(PrimitiveItems.STONE_KNIFE) && !held.isOf(MaterialItems.STONE_SCRAPER)
					&& !held.isOf(com.projectrealism.anatomy.AnatomyItems.BONE_BLADE)
					&& !held.isOf(com.projectrealism.anatomy.AnatomyItems.BONE_CHISEL)
					&& !held.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE)
					&& !held.isOf(com.projectrealism.metallurgy.MetallurgyItems.DYNAMIC_METAL)) {
				return ActionResult.PASS;
			}
			BlockPos pos = hit.getBlockPos();
			BlockState bs = world.getBlockState(pos);
			if (!player.isSneaking()) return ActionResult.PASS;
			if (!bs.isOf(Blocks.SPRUCE_LOG) && !bs.isOf(Blocks.SPRUCE_WOOD)) return ActionResult.PASS;

			if (world instanceof ServerWorld sw) {
				if (sw.random.nextFloat() < 0.65f) {
					Block.dropStack(sw, pos.offset(hit.getSide()), new ItemStack(MaterialItems.PINE_RESIN, 1));
				}
				sw.playSound(null, pos, SoundEvents.ITEM_AXE_STRIP, SoundCategory.BLOCKS, 0.55f, 1.3f);
				if (!player.isCreative()) {
					held.damage(1, player, hand == Hand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
				}
			}
			return ActionResult.SUCCESS;
		});

		// 4. Sharpen Stone Scraper against rock in off-hand
		UseItemCallback.EVENT.register((player, world, hand) -> {
			ItemStack main = player.getStackInHand(hand);
			ItemStack off = player.getStackInHand(hand == Hand.MAIN_HAND ? Hand.OFF_HAND : Hand.MAIN_HAND);
			if (hand == Hand.MAIN_HAND && main.isOf(MaterialItems.STONE_SCRAPER) && PrimitiveItems.isKnappable(off.getItem())) {
				if (world instanceof ServerWorld sw) {
					int cur = MaterialItems.StoneScraperItem.getSharpness(main);
					MaterialItems.StoneScraperItem.setSharpness(main, Math.min(100, cur + 35));
					sw.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_GRINDSTONE_USE,
							SoundCategory.PLAYERS, 0.6f, 1.2f);
				}
				return TypedActionResult.success(main, world.isClient());
			}
			return TypedActionResult.pass(main);
		});
	}

	private static double computeTrauma(LivingEntity entity, DamageSource source) {
		double t = 0.15;
		if (source.isIn(DamageTypeTags.IS_PROJECTILE)) t += 0.20;
		if (source.isIn(DamageTypeTags.IS_EXPLOSION)) t += 0.55;
		if (source.isIn(DamageTypeTags.IS_FIRE)) t += 0.25;
		return Math.min(0.85, t);
	}

	public static void tick(MinecraftServer server) {
		if (!ProjectRealism.config().materialProcessing) return;
		tickCounter++;
		if (tickCounter % 10 != 0) return; // 2 Hz container fluid & aging physics

		double dt = 0.5;
		for (ServerWorld world : server.getWorlds()) {
			ThermalWorldState thermo = ThermalWorldState.get(world);
			for (ServerPlayerEntity player : world.getPlayers()) {
				stepPlayerContainers(world, thermo, player, dt);
			}
		}
	}

	private static void stepPlayerContainers(ServerWorld world, ThermalWorldState thermo,
											 ServerPlayerEntity player, double dt) {
		UUID id = player.getUuid();
		Vec3d vel = player.getVelocity();
		Vec3d prev = PREV_VEL.getOrDefault(id, vel);
		PREV_VEL.put(id, vel);
		double accelX = (vel.x - prev.x) / dt;
		double accelZ = (vel.z - prev.z) / dt;

		float prevFall = PREV_FALL.getOrDefault(id, 0f);
		float curFall = player.fallDistance;
		boolean hardLanding = player.isOnGround() && prevFall > 4.2f;
		PREV_FALL.put(id, curFall);

		BlockPos pos = player.getBlockPos();
		double ambK = thermo.ambientTempAt(world, pos);
		double rh = thermo.relativeHumidityAt(world, pos);
		boolean rain = world.isRaining() && world.isSkyVisible(pos.up());

		var inv = player.getInventory();
		for (int slot = 0; slot < inv.size(); slot++) {
			ItemStack stack = inv.getStack(slot);
			if (stack.isEmpty()) continue;

			if (stack.isOf(HydrationItems.WATERSKIN)) {
				WaterskinConstruction c = WaterskinConstruction.read(stack);
				int vol = WaterskinItem.getVolumeMl(stack);
				WaterQuality wq = WaterQuality.read(stack, WaterQuality.RIVER_WATER);

				// Fluid slosh & container expansion
				c.stepFluidBody(vol, accelX, accelZ, dt);
				MaterialAging.stepLeatherContainer(c, vol > 0, ambK, rh, rain, dt);

				// Per-seam hydrostatic + slosh leaking
				if (vol > 0) {
					double[] seamRates = new double[WaterskinConstruction.SEAM_COUNT];
					double leakPerSec = c.totalLeakMlPerSec(vol, wq.tempK(), seamRates);
					if (leakPerSec > 0.01) {
						c.leakResidualMl += leakPerSec * dt;
						int lost = (int) Math.floor(c.leakResidualMl);
						if (lost > 0) {
							c.leakResidualMl -= lost;
							vol = Math.max(0, vol - lost);
							WaterskinItem.setContents(stack, vol, wq);
						}
						// Visual leak cue from player side + periodic diagnostic message naming exact leaking seam!
						int worst = c.worstLeakingSeamIndex(vol, wq.tempK());
						if (worst >= 0 && world.random.nextFloat() < 0.6f) {
							double sideOff = worst == 1 ? -0.35 : (worst == 2 ? 0.35 : 0.0);
							double hOff = worst == 0 ? 1.15 : (worst == 3 ? 0.55 : 0.85);
							world.spawnParticles(ParticleTypes.DRIPPING_WATER,
									player.getX() + sideOff, player.getY() + hOff, player.getZ(),
									2, 0.08, 0.05, 0.08, 0.01);
						}
						long now = System.currentTimeMillis();
						Long last = LAST_LEAK_MSG_MS.get(id);
						if (worst >= 0 && (last == null || now - last > 5500L)) {
							LAST_LEAK_MSG_MS.put(id, now);
							String seamKey = WaterskinConstruction.SeamZone.values()[worst].key;
							player.sendMessage(Text.translatable("project_realism.waterskin.leaking",
									seamKey, String.format("%.1f", leakPerSec)).formatted(Formatting.AQUA), true);
						}
					}
				}
				WaterskinConstruction.write(stack, c);
			} else if (stack.isOf(MaterialItems.GOURD_FLASK)) {
				MaterialAging.GourdState gs = GourdFlaskItem.readGourdState(stack);
				if (hardLanding) {
					double shock = Math.min(0.65, (prevFall - 3.5f) * 0.12);
					gs.crackSeverity = Math.min(1.0, gs.crackSeverity + shock);
					world.playSound(null, pos, SoundEvents.ENTITY_TURTLE_EGG_CRACK, SoundCategory.PLAYERS, 0.7f, 0.9f);
					player.sendMessage(Text.translatable("project_realism.gourd.cracked").formatted(Formatting.RED), true);
				}
				int vol = GourdFlaskItem.getVolumeMl(stack);
				double leakRate = gs.leakMlPerSec(vol, GourdFlaskItem.CAPACITY_ML);
				if (vol > 0 && leakRate > 0.05) {
					int lost = Math.max(1, (int) Math.round(leakRate * dt));
					WaterQuality wq = WaterQuality.read(stack, WaterQuality.RIVER_WATER);
					GourdFlaskItem.setContents(stack, Math.max(0, vol - lost), wq);
					world.spawnParticles(ParticleTypes.DRIPPING_WATER,
							player.getX(), player.getY() + 0.85, player.getZ(), 1, 0.1, 0.1, 0.1, 0.01);
				}
				GourdFlaskItem.writeGourdState(stack, gs);
			}
		}
	}

	public static void onDisconnect(UUID uuid) {
		PREV_VEL.remove(uuid);
		PREV_FALL.remove(uuid);
		LAST_LEAK_MSG_MS.remove(uuid);
	}
}
