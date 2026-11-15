package com.projectrealism.hydration;

import com.projectrealism.ProjectRealism;
import com.projectrealism.thermo.AirGrid;
import com.projectrealism.thermo.MaterialProfile;
import com.projectrealism.thermo.ThermalConstants;
import com.projectrealism.thermo.ThermalWorldState;
import com.projectrealism.weight.WeightManager;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.potion.Potions;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.PersistentState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Authoritative per-world physiological hydration, fluid-compartment, sweat-thermoregulation,
 * electrolyte, and renal simulation manager.
 */
public final class HydrationManager extends PersistentState {
	private static final String STATE_KEY = "project_realism_hydration";
	private static final Type<HydrationManager> TYPE = new Type<>(
			HydrationManager::new,
			HydrationManager::fromNbt,
			null
	);

	private static final class ActiveUseTracker {
		ItemStack stack = ItemStack.EMPTY;
		Hand hand = Hand.MAIN_HAND;
		int lastRemaining = 0;
		Vec3d lastPos = Vec3d.ZERO;
		float lastExhaustionProxy = 0f;
	}

	private final Map<UUID, BodyFluidMath.BodyFluidState> states = new HashMap<>();
	private final Map<UUID, ActiveUseTracker> trackers = new HashMap<>();
	private int tickCounter = 0;

	public HydrationManager() {
	}

	public static HydrationManager get(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(TYPE, STATE_KEY);
	}

	public BodyFluidMath.BodyFluidState getOrCreateState(UUID uuid) {
		return states.computeIfAbsent(uuid, k -> new BodyFluidMath.BodyFluidState());
	}

	public void onJoin(ServerPlayerEntity player) {
		getOrCreateState(player.getUuid());
	}

	public void onDisconnect(UUID uuid) {
		trackers.remove(uuid);
	}

	public void resetPlayer(UUID uuid) {
		states.put(uuid, new BodyFluidMath.BodyFluidState());
		markDirty();
	}

	public void drink(ServerPlayerEntity player, double liters, WaterQuality quality) {
		if (!ProjectRealism.config().hydrationSimulation || player.isSpectator()) {
			return;
		}
		BodyFluidMath.BodyFluidState s = getOrCreateState(player.getUuid());
		WaterQuality q = quality != null ? quality.clamped() : WaterQuality.RIVER_WATER;
		if (q.tempK() >= 338.15) {
			player.damage(player.getServerWorld().getDamageSources().inFire(), 1.0f);
			player.sendMessage(Text.translatable("project_realism.hydration.scalding")
					.formatted(Formatting.RED), true);
		}
		BodyFluidMath.ingestWater(s, liters, q);
		if (s.stomachLiters > 1.28) {
			player.sendMessage(Text.translatable("project_realism.hydration.overfull")
					.formatted(Formatting.YELLOW), true);
		} else if (q.salinityGPerL() > 12.0) {
			player.sendMessage(Text.translatable("project_realism.hydration.salty_drink")
					.formatted(Formatting.GOLD), true);
		}
		markDirty();
	}

	public void eatFood(ServerPlayerEntity player, ItemStack foodStack) {
		if (!ProjectRealism.config().hydrationSimulation || foodStack.isEmpty()) {
			return;
		}
		BodyFluidMath.BodyFluidState s = getOrCreateState(player.getUuid());
		MaterialProfile.FoodProperties fp = MaterialProfile.forFood(foodStack);
		String path = Registries.ITEM.getId(foodStack.getItem()).getPath();

		double waterL = fp.massKg() * fp.waterFraction() * 0.55;
		double naMmol = 3.0;
		double kMmol = 6.5;
		double clMmol = 3.0;

		if (path.contains("kelp") || path.contains("cod") || path.contains("salmon")) {
			naMmol = 22.0;
			clMmol = 21.0;
			kMmol = 11.0;
		} else if (path.contains("melon") || path.contains("apple") || path.contains("berry") || path.contains("carrot")) {
			kMmol = 15.0;
			naMmol = 0.8;
			clMmol = 1.0;
		} else if (path.contains("potato")) {
			kMmol = 22.0;
			naMmol = 1.5;
			clMmol = 2.0;
		} else if (path.contains("beef") || path.contains("pork") || path.contains("mutton") || path.contains("chicken")) {
			naMmol = 11.0;
			kMmol = 14.0;
			clMmol = 10.0;
		} else if (path.contains("bread") || path.contains("cookie")) {
			naMmol = 16.0;
			clMmol = 15.0;
			kMmol = 4.0;
			waterL *= 0.25;
		}

		BodyFluidMath.ingestFood(s, waterL, naMmol, kMmol, clMmol);
		markDirty();
	}

	public static void tick(MinecraftServer server) {
		if (!ProjectRealism.config().hydrationSimulation) {
			return;
		}
		for (ServerWorld world : server.getWorlds()) {
			get(world).tickWorld(world);
		}
	}

	private void tickWorld(ServerWorld world) {
		tickCounter++;
		for (ServerPlayerEntity player : world.getPlayers()) {
			trackVanillaConsumption(player);
		}
		if (tickCounter % 10 != 0) {
			return; // 2 Hz physiology update
		}

		double realDt = 0.5;
		double physDt = realDt * ProjectRealism.config().hydrationTimeScale;
		ThermalWorldState thermo = ThermalWorldState.get(world);

		for (ServerPlayerEntity player : world.getPlayers()) {
			if (player.isCreative() || player.isSpectator()) {
				sendPayload(player, getOrCreateState(player.getUuid()), false);
				continue;
			}
			BodyFluidMath.BodyFluidState s = getOrCreateState(player.getUuid());
			ActiveUseTracker tr = trackers.computeIfAbsent(player.getUuid(), k -> new ActiveUseTracker());

			// Measure exertion from displacement, sprinting, swimming, swinging, and hauled load
			Vec3d pos = player.getPos();
			double dist = tr.lastPos == Vec3d.ZERO ? 0.0 : pos.distanceTo(tr.lastPos);
			tr.lastPos = pos;
			double speedMS = dist / realDt;

			double exertion = 1.0;
			if (player.isSwimming() || player.isTouchingWater() && speedMS > 0.5) {
				exertion = 4.4;
			} else if (player.isSprinting()) {
				exertion = 4.2;
			} else if (speedMS > 0.35) {
				exertion = 2.0 + Math.min(1.5, speedMS * 0.25);
			}
			if (player.handSwinging) {
				exertion += 0.9;
			}

			BlockPos bpos = player.getBlockPos();
			double ambK = thermo.ambientTempAt(world, bpos);
			double rh = thermo.relativeHumidityAt(world, bpos);
			AirGrid.AirCell air = thermo.airGrid().getReadOnly(AirGrid.CellPos.fromBlock(bpos.up()), ambK);
			double airTempK = Math.max(ambK, air.temperatureK);
			double windMS = air.speedMS() + (world.isSkyVisible(bpos) ? 0.55 : 0.15) + Math.min(1.2, speedMS * 0.35);

			// Solar load when standing outdoors in direct sunlight + nearby hearth/fire radiation
			double radiantW = 0.0;
			if (world.isDay() && !world.isRaining() && world.isSkyVisible(bpos.up())) {
				float biomeTemp = world.getBiome(bpos).value().getTemperature();
				radiantW += Math.max(20.0, 95.0 + biomeTemp * 85.0);
			}
			radiantW += estimateFireRadiantHeatOnPlayer(world, thermo, bpos);

			double carriedKg = WeightManager.get().totalKg(player);
			// Armor pieces increase thermal insulation (making heavy armor hot to sprint in!)
			double clo = 0.72 + player.getArmor() * 0.025;
			if (world.isRaining() && world.isSkyVisible(bpos.up())) {
				clo *= 0.65;
			}

			boolean inWater = player.isTouchingWater();
			double waterT = inWater ? WaterQuality.fromWorld(world, bpos).tempK() : ambK;

			BodyFluidMath.EnvironmentInput env = new BodyFluidMath.EnvironmentInput(
					airTempK,
					ProjectRealism.config().sweatThermoregulation ? rh : 0.45,
					windMS,
					ProjectRealism.config().sweatThermoregulation ? radiantW : 0.0,
					exertion,
					carriedKg,
					clo,
					inWater,
					waterT
			);

			// Step in <= 6s substeps for crisp numerical stability even at high hydrationTimeScale
			double rem = physDt;
			while (rem > 0.001) {
				double sub = Math.min(4.0, rem);
				BodyFluidMath.step(s, env, sub);
				rem -= sub;
			}

			applyPhysiologicalEffects(world, player, s);
			sendPayload(player, s, ProjectRealism.config().hydrationHud);
		}
		markDirty();
	}

	private void trackVanillaConsumption(ServerPlayerEntity player) {
		ActiveUseTracker tr = trackers.computeIfAbsent(player.getUuid(), k -> new ActiveUseTracker());
		if (player.isUsingItem()) {
			ItemStack active = player.getActiveItem();
			if (!active.isEmpty() && (isWaterBottle(active) || active.contains(DataComponentTypes.FOOD))) {
				tr.stack = active.copy();
				tr.hand = player.getActiveHand();
				tr.lastRemaining = player.getItemUseTimeLeft();
			}
			return;
		}
		if (!tr.stack.isEmpty() && tr.lastRemaining <= 2) {
			ItemStack finished = tr.stack;
			ItemStack current = player.getStackInHand(tr.hand);
			// Confirm consumption actually completed (item turned to glass bottle or decremented)
			boolean consumed = !ItemStack.areItemsAndComponentsEqual(finished, current)
					|| current.getCount() < finished.getCount();
			if (consumed) {
				if (isWaterBottle(finished)) {
					WaterQuality q = WaterQuality.read(finished, WaterQuality.FRESH_SPRING);
					drink(player, 0.250, q);
				} else if (finished.contains(DataComponentTypes.FOOD)) {
					eatFood(player, finished);
				}
			}
		}
		tr.stack = ItemStack.EMPTY;
		tr.lastRemaining = 0;
	}

	public static boolean isWaterBottle(ItemStack stack) {
		if (!stack.isOf(Items.POTION)) {
			return false;
		}
		PotionContentsComponent pc = stack.get(DataComponentTypes.POTION_CONTENTS);
		return pc != null && pc.matches(Potions.WATER);
	}

	private static double estimateFireRadiantHeatOnPlayer(ServerWorld world, ThermalWorldState thermo, BlockPos p) {
		double watts = 0.0;
		for (int dx = -4; dx <= 4; dx++) {
			for (int dy = -2; dy <= 2; dy++) {
				for (int dz = -4; dz <= 4; dz++) {
					double d2 = dx * dx + dy * dy + dz * dz;
					if (d2 < 0.5 || d2 > 20.0) continue;
					BlockPos src = p.add(dx, dy, dz);
					double t = thermo.blockSurfaceTempK(world, src);
					if (t < 420.0) continue;
					if (!thermo.hasLineOfSight(world, src, p.up())) continue;
					double rad = 0.9 * ThermalConstants.STEFAN_BOLTZMANN * 1.1 * (Math.pow(t, 4) - Math.pow(306.15, 4));
					watts += rad / (4.0 * Math.PI * Math.max(0.8, d2));
				}
			}
		}
		return Math.min(1800.0, watts);
	}

	private void applyPhysiologicalEffects(ServerWorld world, ServerPlayerEntity player, BodyFluidMath.BodyFluidState s) {
		double coreC = ThermalConstants.toCelsius(s.bodyCoreTempK);
		double na = s.plasmaNaMm();
		double k = s.plasmaKMm();

		// Periodic natural voiding when bladder fills
		if (s.bladderLiters >= 0.72) {
			s.bladderLiters = 0.05;
			String toneKey = s.urineOsmolalityMOsm > 800.0
					? "project_realism.hydration.void_dark"
					: (s.urineOsmolalityMOsm < 200.0
					? "project_realism.hydration.void_clear"
					: "project_realism.hydration.void_normal");
			player.sendMessage(Text.translatable(toneKey).formatted(
					s.urineOsmolalityMOsm > 800.0 ? Formatting.GOLD : Formatting.GRAY), true);
		}

		// Hyperthermia / heat exhaustion
		if (coreC >= 39.2) {
			player.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 60, 0, true, false));
			player.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 60, 0, true, false));
			if (coreC >= 40.6 && world.random.nextFloat() < 0.35f) {
				player.damage(world.getDamageSources().inFire(), 1.0f);
			}
		} else if (coreC <= 34.8) {
			// Hypothermia
			player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 0, true, false));
			player.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 60, 0, true, false));
			if (coreC <= 33.5 && world.random.nextFloat() < 0.35f) {
				player.damage(world.getDamageSources().freeze(), 1.0f);
			}
		}

		// Electrolyte disturbances
		if (na < 131.0 || na > 151.0) {
			player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 70, 0, true, false));
			if (na < 126.0 || na > 156.0) {
				player.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 80, 0, true, false));
				if (world.random.nextFloat() < 0.25f) {
					player.damage(world.getDamageSources().generic(), 1.0f);
				}
			}
		}
		if (k < 3.3 || k > 5.8) {
			player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 0, true, false));
		}

		// Hypovolemia / severe dehydration
		if (s.plasmaLiters < 2.28) {
			player.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 60, 0, true, false));
			player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 0, true, false));
			if (s.plasmaLiters < 1.95 && world.random.nextFloat() < 0.3f) {
				player.damage(world.getDamageSources().starve(), 1.0f);
			}
		}

		// Gastric overload or contaminated water illness
		if (s.gastricDiscomfort > 0.45 || s.pendingMicrobeLoad > 0.35) {
			player.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 90, 0, true, false));
			if (s.pendingMicrobeLoad > 0.65 && world.random.nextFloat() < 0.2f) {
				// Waterborne GI fluid loss
				s.plasmaLiters = Math.max(1.5, s.plasmaLiters - 0.03);
				s.ecfNaMmol = Math.max(100.0, s.ecfNaMmol - 3.5);
				s.ecfKMmol = Math.max(5.0, s.ecfKMmol - 0.8);
			}
		}
		if (s.pendingChemicalLoad > 0.22) {
			player.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 80, 0, true, false));
			s.pendingChemicalLoad = Math.max(0.0, s.pendingChemicalLoad - 0.05);
		}
	}

	private static void sendPayload(ServerPlayerEntity player, BodyFluidMath.BodyFluidState s, boolean visible) {
		double na = s.plasmaNaMm();
		double k = s.plasmaKMm();
		byte flags = 0;
		if (na < 133.0) flags |= 1;
		if (na > 149.0) flags |= 2;
		if (k < 3.4 || k > 5.6) flags |= 4;
		if (s.gastricDiscomfort > 0.3 || s.pendingMicrobeLoad > 0.35 || s.pendingChemicalLoad > 0.2) flags |= 8;

		float dripFrac = s.lastSweatRateKgS > 1.0e-7
				? (float) (s.lastSweatDripRateKgS / s.lastSweatRateKgS)
				: 0.0f;

		HydrationPayload payload = new HydrationPayload(
				(float) s.perceivedThirst,
				(float) ThermalConstants.toCelsius(s.bodyCoreTempK),
				(float) (s.lastSweatRateKgS * 60000.0), // g/min
				dripFrac,
				(float) Math.min(1.0, s.stomachLiters / 1.25),
				(float) s.urineOsmolalityMOsm,
				flags,
				visible
		);
		ServerPlayNetworking.send(player, payload);
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		NbtList list = new NbtList();
		for (Map.Entry<UUID, BodyFluidMath.BodyFluidState> e : states.entrySet()) {
			BodyFluidMath.BodyFluidState s = e.getValue();
			NbtCompound tag = new NbtCompound();
			tag.putUuid("UUID", e.getKey());
			tag.putDouble("ICF", s.icfLiters);
			tag.putDouble("ISF", s.isfLiters);
			tag.putDouble("PL", s.plasmaLiters);
			tag.putDouble("ST", s.stomachLiters);
			tag.putDouble("IN", s.intestineLiters);
			tag.putDouble("BL", s.bladderLiters);
			tag.putDouble("ENa", s.ecfNaMmol);
			tag.putDouble("EK", s.ecfKMmol);
			tag.putDouble("ECl", s.ecfClMmol);
			tag.putDouble("EOth", s.ecfOtherOsmMmol);
			tag.putDouble("IK", s.icfKMmol);
			tag.putDouble("IOth", s.icfOtherOsmMmol);
			tag.putDouble("SNa", s.stomachNaMmol);
			tag.putDouble("SK", s.stomachKMmol);
			tag.putDouble("SCl", s.stomachClMmol);
			tag.putDouble("SOth", s.stomachOtherOsmMmol);
			tag.putDouble("STemp", s.stomachTempK);
			tag.putDouble("SMic", s.stomachMicrobes);
			tag.putDouble("SChem", s.stomachChemicals);
			tag.putDouble("INa", s.intestineNaMmol);
			tag.putDouble("IKm", s.intestineKMmol);
			tag.putDouble("ICl", s.intestineClMmol);
			tag.putDouble("IOthm", s.intestineOtherOsmMmol);
			tag.putDouble("IMic", s.intestineMicrobes);
			tag.putDouble("IChem", s.intestineChemicals);
			tag.putDouble("CoreT", s.bodyCoreTempK);
			tag.putDouble("SkinT", s.skinTempK);
			tag.putDouble("Oral", s.oralSatiety);
			tag.putDouble("Thirst", s.perceivedThirst);
			tag.putDouble("Adh", s.adhLevel);
			tag.putDouble("Aldo", s.aldosteroneLevel);
			tag.putDouble("UOsm", s.urineOsmolalityMOsm);
			tag.putDouble("PMic", s.pendingMicrobeLoad);
			tag.putDouble("PChem", s.pendingChemicalLoad);
			list.add(tag);
		}
		nbt.put("Players", list);
		return nbt;
	}

	public static HydrationManager fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		HydrationManager mgr = new HydrationManager();
		NbtList list = nbt.getList("Players", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < list.size(); i++) {
			NbtCompound tag = list.getCompound(i);
			if (!tag.containsUuid("UUID")) continue;
			BodyFluidMath.BodyFluidState s = new BodyFluidMath.BodyFluidState();
			s.icfLiters = tag.getDouble("ICF");
			s.isfLiters = tag.getDouble("ISF");
			s.plasmaLiters = tag.getDouble("PL");
			s.stomachLiters = tag.getDouble("ST");
			s.intestineLiters = tag.getDouble("IN");
			s.bladderLiters = tag.getDouble("BL");
			s.ecfNaMmol = tag.getDouble("ENa");
			s.ecfKMmol = tag.getDouble("EK");
			s.ecfClMmol = tag.getDouble("ECl");
			s.ecfOtherOsmMmol = tag.getDouble("EOth");
			s.icfKMmol = tag.getDouble("IK");
			s.icfOtherOsmMmol = tag.getDouble("IOth");
			s.stomachNaMmol = tag.getDouble("SNa");
			s.stomachKMmol = tag.getDouble("SK");
			s.stomachClMmol = tag.getDouble("SCl");
			s.stomachOtherOsmMmol = tag.getDouble("SOth");
			s.stomachTempK = tag.getDouble("STemp");
			s.stomachMicrobes = tag.getDouble("SMic");
			s.stomachChemicals = tag.getDouble("SChem");
			s.intestineNaMmol = tag.getDouble("INa");
			s.intestineKMmol = tag.getDouble("IKm");
			s.intestineClMmol = tag.getDouble("ICl");
			s.intestineOtherOsmMmol = tag.getDouble("IOthm");
			s.intestineMicrobes = tag.getDouble("IMic");
			s.intestineChemicals = tag.getDouble("IChem");
			s.bodyCoreTempK = tag.getDouble("CoreT");
			s.skinTempK = tag.getDouble("SkinT");
			s.oralSatiety = tag.getDouble("Oral");
			s.perceivedThirst = tag.getDouble("Thirst");
			s.adhLevel = tag.getDouble("Adh");
			s.aldosteroneLevel = tag.getDouble("Aldo");
			s.urineOsmolalityMOsm = tag.getDouble("UOsm");
			s.pendingMicrobeLoad = tag.getDouble("PMic");
			s.pendingChemicalLoad = tag.getDouble("PChem");
			mgr.states.put(tag.getUuid("UUID"), s);
		}
		return mgr;
	}
}
