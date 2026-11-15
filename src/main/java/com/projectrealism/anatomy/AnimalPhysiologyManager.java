package com.projectrealism.anatomy;

import com.projectrealism.ProjectRealism;
import com.projectrealism.thermo.ThermalWorldState;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Tracks live animal physiology across loaded entities without mixins: forage grazing, drinking,
 * environmental cold/heat metabolism, activity fatigue, injury slowness attributes, and capture
 * into persistent carcasses upon death.
 */
public final class AnimalPhysiologyManager {
	private static final Identifier PHYSIO_SPEED_ID = ProjectRealism.id("animal_physio_speed");
	private static final Map<UUID, AnimalPhysiologyState> STATES = new HashMap<>();
	private static int tickCounter = 0;

	private AnimalPhysiologyManager() {
	}

	public static AnimalPhysiologyState getOrCreate(LivingEntity entity) {
		return STATES.computeIfAbsent(entity.getUuid(), id -> {
			AnimalSpecies sp = AnimalSpecies.fromEntity(entity);
			boolean baby = entity instanceof AnimalEntity a && a.isBaby();
			double age = baby ? 0.30 : (0.72 + (Math.abs(id.getLeastSignificantBits()) % 28) / 100.0);
			double cond = 0.60 + (Math.abs(id.getMostSignificantBits()) % 32) / 100.0;
			AnimalPhysiologyState st = new AnimalPhysiologyState(sp, age, cond);
			if (entity instanceof SheepEntity sheep && sheep.isSheared()) {
				st.woolShorn = true;
			}
			return st;
		});
	}

	public static AnimalPhysiologyState popOnDeath(LivingEntity entity) {
		AnimalPhysiologyState st = getOrCreate(entity);
		STATES.remove(entity.getUuid());
		return st;
	}

	public static void recordDamage(LivingEntity entity, float damageAmount) {
		if (!AnimalSpecies.isSimulatedAnimal(entity.getType())) return;
		AnimalPhysiologyState st = getOrCreate(entity);
		float maxHp = Math.max(4f, entity.getMaxHealth());
		st.applyHitTrauma(damageAmount / maxHp, 0.50);
	}

	public static void tick(MinecraftServer server) {
		if (!ProjectRealism.config().animalPhysiology) return;
		tickCounter++;
		if (tickCounter % 20 != 0) return; // 1 Hz animal physiology update

		double dt = 1.0;
		for (ServerWorld world : server.getWorlds()) {
			ThermalWorldState thermo = ThermalWorldState.get(world);
			for (var entity : world.iterateEntities()) {
				if (!(entity instanceof LivingEntity living) || !living.isAlive()) continue;
				if (!AnimalSpecies.isSimulatedAnimal(living.getType())) continue;

				AnimalPhysiologyState st = getOrCreate(living);
				if (living instanceof SheepEntity sheep) {
					st.woolShorn = sheep.isSheared();
				}
				BlockPos pos = living.getBlockPos();
				double ambK = thermo.ambientTempAt(world, pos);
				double rh = thermo.relativeHumidityAt(world, pos);
				double speed = living.getVelocity().horizontalLength() * 20.0;

				boolean forage = isForageNear(world, pos);
				boolean water = isWaterNear(world, pos);
				st.stepLivePhysiology(ambK, rh, speed, forage, water, dt);

				// Apply injury / fatigue / dehydration movement speed modifier cleanly via EntityAttributeModifier
				EntityAttributeInstance attr = living.getAttributeInstance(EntityAttributes.GENERIC_MOVEMENT_SPEED);
				if (attr != null) {
					attr.removeModifier(PHYSIO_SPEED_ID);
					double factor = st.movementSpeedFactor();
					if (factor < 0.96) {
						attr.addTemporaryModifier(new EntityAttributeModifier(
								PHYSIO_SPEED_ID, factor - 1.0, EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
					}
				}
			}
		}

		// Periodically prune very stale entries if map grows large
		if (STATES.size() > 2500) {
			Iterator<UUID> it = STATES.keySet().iterator();
			int drop = STATES.size() - 1800;
			while (it.hasNext() && drop-- > 0) {
				it.next();
				it.remove();
			}
		}
	}

	private static boolean isForageNear(ServerWorld world, BlockPos pos) {
		BlockState at = world.getBlockState(pos);
		BlockState below = world.getBlockState(pos.down());
		return at.isOf(Blocks.SHORT_GRASS) || at.isOf(Blocks.TALL_GRASS) || at.isOf(Blocks.FERN)
				|| below.isOf(Blocks.GRASS_BLOCK) || below.isOf(Blocks.HAY_BLOCK) || at.isIn(BlockTags.CROPS);
	}

	private static boolean isWaterNear(ServerWorld world, BlockPos pos) {
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = -1; dy <= 0; dy++) {
					if (world.getFluidState(pos.add(dx, dy, dz)).isIn(FluidTags.WATER)) {
						return true;
					}
				}
			}
		}
		return world.isRaining() && world.isSkyVisible(pos.up());
	}
}
