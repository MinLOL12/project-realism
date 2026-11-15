package com.projectrealism.anatomy;

import com.projectrealism.ProjectRealism;
import com.projectrealism.primitive.PrimitiveItems;
import com.projectrealism.thermo.ThermalWorldState;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.tag.DamageTypeTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.stat.Stats;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * Mixin-free event hooks turning dying animals into persistent anatomical carcasses (replacing
 * instant meat/leather/wool/bone drops), ticking live animal physiology and inventory spoilage,
 * and handling hand bone-shaping abrasion.
 */
public final class AnatomyEvents {
	private static int tickCount = 0;

	private AnatomyEvents() {
	}

	public static void register() {
		// Record directional combat injuries into live physiology
		ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDmg, dmgTaken, blocked) -> {
			if (!entity.getWorld().isClient() && ProjectRealism.config().animalPhysiology) {
				AnimalPhysiologyManager.recordDamage(entity, dmgTaken);
			}
		});

		// Intercept death of simulated animals -> spawn persistent anatomical CarcassBlock with ZERO vanilla drops!
		ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> {
			if (entity.getWorld().isClient() || !ProjectRealism.config().carcassSystem) {
				return true;
			}
			if (!AnimalSpecies.isSimulatedAnimal(entity.getType())) {
				return true;
			}
			ServerWorld sw = (ServerWorld) entity.getWorld();
			AnimalPhysiologyState phys = AnimalPhysiologyManager.popOnDeath(entity);
			boolean fire = source.isIn(DamageTypeTags.IS_FIRE);
			double trauma = 0.12 + (source.isIn(DamageTypeTags.IS_PROJECTILE) ? 0.18 : 0.0)
					+ (source.isIn(DamageTypeTags.IS_EXPLOSION) ? 0.45 : 0.0);

			CarcassState carcass = CarcassState.fromLivingAnimal(phys, fire, trauma);

			BlockPos spawnPos = findCarcassPlacement(sw, entity.getBlockPos());
			if (spawnPos != null) {
				Direction facing = Direction.fromRotation(entity.getYaw());
				sw.setBlockState(spawnPos, AnatomyBlocks.CARCASS.getDefaultState().with(CarcassBlock.FACING, facing));
				if (sw.getBlockEntity(spawnPos) instanceof CarcassBlockEntity be) {
					be.setCarcass(carcass);
				}
			} else {
				Block.dropStack(sw, entity.getBlockPos(), CarcassItem.create(carcass));
			}

			// Credit player kill stat cleanly before discarding entity
			Entity attacker = source.getAttacker();
			if (attacker instanceof PlayerEntity player) {
				player.incrementStat(Stats.KILLED.getOrCreateStat(entity.getType()));
			}
			sw.playSound(null, entity.getBlockPos(), SoundEvents.BLOCK_WOOL_PLACE, SoundCategory.NEUTRAL, 0.65f, 0.75f);
			entity.discard();
			return false;
		});

		// Hand abrasion: holding RawBone in main hand + rough rock in off-hand shapes/smooths bone
		UseItemCallback.EVENT.register((player, world, hand) -> {
			ItemStack main = player.getStackInHand(hand);
			ItemStack off = player.getStackInHand(hand == Hand.MAIN_HAND ? Hand.OFF_HAND : Hand.MAIN_HAND);
			if (hand == Hand.MAIN_HAND && main.isOf(AnatomyItems.RAW_BONE) && PrimitiveItems.isKnappable(off.getItem())) {
				if (world instanceof ServerWorld sw) {
					BoneData b = RawBoneItem.readBone(main);
					b.abradeWithStone(off.isOf(PrimitiveItems.FLINT_ROCK) ? 0.90 : 0.72);
					RawBoneItem.writeBone(main, b);
					sw.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_GRINDSTONE_USE,
							SoundCategory.PLAYERS, 0.5f, 1.3f);
				}
				return TypedActionResult.success(main, world.isClient());
			}
			return TypedActionResult.pass(main);
		});
	}

	private static BlockPos findCarcassPlacement(ServerWorld world, BlockPos center) {
		BlockPos[] candidates = new BlockPos[]{
				center, center.up(), center.down(),
				center.north(), center.south(), center.east(), center.west()
		};
		for (BlockPos p : candidates) {
			BlockState s = world.getBlockState(p);
			if ((s.isAir() || s.isReplaceable()) && world.getFluidState(p).isEmpty()) {
				return p;
			}
		}
		return null;
	}

	public static void tick(MinecraftServer server) {
		AnimalPhysiologyManager.tick(server);
		if (!ProjectRealism.config().carcassSystem) return;

		tickCount++;
		if (tickCount % 40 != 0) return; // 0.5 Hz inventory meat & portable carcass spoilage

		double dt = 2.0;
		for (ServerWorld world : server.getWorlds()) {
			ThermalWorldState thermo = ThermalWorldState.get(world);
			for (ServerPlayerEntity player : world.getPlayers()) {
				BlockPos pos = player.getBlockPos();
				double ambK = thermo.ambientTempAt(world, pos);
				double rh = thermo.relativeHumidityAt(world, pos);
				boolean rain = world.isRaining() && world.isSkyVisible(pos.up());
				var inv = player.getInventory();

				for (int i = 0; i < inv.size(); i++) {
					ItemStack st = inv.getStack(i);
					if (st.isEmpty()) continue;
					if (st.getItem() instanceof AnatomyMeatItem ami) {
						MeatCutData m = AnatomyMeatItem.readMeat(st, ami.defaultKind());
						m.stepEnvironment(ambK, rh, 0.25, false, dt);
						AnatomyMeatItem.writeMeat(st, m);
					} else if (st.isOf(AnatomyItems.CARCASS_ITEM)) {
						CarcassState cs = CarcassItem.readCarcass(st);
						cs.stepEnvironment(ambK, rh, 0.25, 0.0, rain, true, dt);
						CarcassItem.writeCarcass(st, cs);
					}
				}
			}
		}
	}
}
