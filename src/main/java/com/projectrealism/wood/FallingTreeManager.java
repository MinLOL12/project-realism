package com.projectrealism.wood;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Simulates physical falling trees using smooth-interpolating {@link DisplayEntity.BlockDisplayEntity}
 * segments that rotate toward the notch direction, injure entities and smash fragile structures along
 * the fall arc, and settle on the ground as physical {@link FallenTrunkBlock} logs.
 */
public final class FallingTreeManager {
	private static final int TOTAL_FALL_TICKS = 26;
	private static final int MAX_DISPLAY_LEAVES = 22;

	private static final List<FallingTree> ACTIVE = new ArrayList<>();

	private FallingTreeManager() {
	}

	public static void spawnFallingTree(ServerWorld world, BlockPos pivot, Direction fallDir,
										WoodSpecies species, TreeAnalyzer.TreeData data) {
		List<BlockEntry> entries = new ArrayList<>();
		for (BlockPos p : data.logs()) {
			BlockState state = world.getBlockState(p);
			if (state.getBlock() instanceof NotchedLogBlock) {
				state = species.vanillaLog().getDefaultState();
			}
			entries.add(new BlockEntry(p.subtract(pivot), state, true));
		}

		int leafStep = Math.max(1, data.leaves().size() / MAX_DISPLAY_LEAVES);
		for (int i = 0; i < data.leaves().size(); i++) {
			BlockPos lp = data.leaves().get(i);
			BlockState ls = world.getBlockState(lp);
			if (i % leafStep == 0 && entries.size() < 46) {
				entries.add(new BlockEntry(lp.subtract(pivot), ls, false));
			}
			// Remove original leaf blocks immediately so the physical crown moves cleanly
			world.setBlockState(lp, Blocks.AIR.getDefaultState(), Block.NOTIFY_LISTENERS);
		}
		for (BlockPos lp : data.logs()) {
			world.setBlockState(lp, Blocks.AIR.getDefaultState(), Block.NOTIFY_ALL);
		}

		List<UUID> displayIds = new ArrayList<>();
		for (BlockEntry entry : entries) {
			DisplayEntity.BlockDisplayEntity display = EntityType.BLOCK_DISPLAY.create(world);
			if (display == null) {
				continue;
			}
			display.refreshPositionAndAngles(
					pivot.getX() + 0.5, pivot.getY(), pivot.getZ() + 0.5, 0.0f, 0.0f);
			writeDisplayTransform(display, entry.state, entry.offset, 0.0f, fallDir, 0, 1);
			if (world.spawnEntity(display)) {
				displayIds.add(display.getUuid());
			}
		}

		world.playSound(null, pivot, SoundEvents.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 1.1f, 0.55f);
		ACTIVE.add(new FallingTree(world, pivot, fallDir, species, data.trunkHeight(), data.totalLogCount(),
				entries, displayIds));
	}

	public static void tick(MinecraftServer server) {
		if (ACTIVE.isEmpty()) {
			return;
		}
		Iterator<FallingTree> it = ACTIVE.iterator();
		while (it.hasNext()) {
			FallingTree tree = it.next();
			if (tree.world.getServer() != server) {
				continue;
			}
			if (!tree.step()) {
				it.remove();
			}
		}
	}

	private static void writeDisplayTransform(DisplayEntity.BlockDisplayEntity display,
											  BlockState state, BlockPos relOffset,
											  float angleDeg, Direction fallDir,
											  int startInterp, int interpDuration) {
		NbtCompound nbt = new NbtCompound();
		display.writeNbt(nbt);
		nbt.put("block_state", NbtHelper.fromBlockState(state));
		nbt.putInt("start_interpolation", startInterp);
		nbt.putInt("interpolation_duration", interpDuration);

		float rad = (float) Math.toRadians(angleDeg);
		float half = rad * 0.5f;
		float sinH = (float) Math.sin(half);
		float cosH = (float) Math.cos(half);

		// Rotate around the base hinge axis perpendicular to fallDir
		float ax = 0f, az = 0f;
		switch (fallDir) {
			case NORTH -> ax = -1.0f;
			case SOUTH -> ax = 1.0f;
			case EAST -> az = -1.0f;
			case WEST -> az = 1.0f;
			default -> ax = 1.0f;
		}
		float qx = ax * sinH;
		float qy = 0.0f;
		float qz = az * sinH;
		float qw = cosH;

		// Local block corner offset relative to pivot (pivot is at x+0.5, y, z+0.5)
		float ox = relOffset.getX() - 0.5f;
		float oy = relOffset.getY();
		float oz = relOffset.getZ() - 0.5f;

		// Rotate local offset by quaternion q so block rotates rigidly around pivot
		float[] rot = rotateVec(ox, oy, oz, qx, qy, qz, qw);

		NbtCompound trans = new NbtCompound();
		trans.put("translation", floatList(rot[0], rot[1], rot[2]));
		trans.put("left_rotation", floatList(qx, qy, qz, qw));
		trans.put("scale", floatList(1.0f, 1.0f, 1.0f));
		trans.put("right_rotation", floatList(0.0f, 0.0f, 0.0f, 1.0f));

		nbt.put("transformation", trans);
		display.readNbt(nbt);
	}

	private static float[] rotateVec(float vx, float vy, float vz, float qx, float qy, float qz, float qw) {
		float tx = 2.0f * (qy * vz - qz * vy);
		float ty = 2.0f * (qz * vx - qx * vz);
		float tz = 2.0f * (qx * vy - qy * vx);
		return new float[]{
				vx + qw * tx + (qy * tz - qz * ty),
				vy + qw * ty + (qz * tx - qx * tz),
				vz + qw * tz + (qx * ty - qy * tx)
		};
	}

	private static NbtList floatList(float... vals) {
		NbtList list = new NbtList();
		for (float v : vals) {
			list.add(NbtFloat.of(v));
		}
		return list;
	}

	private record BlockEntry(BlockPos offset, BlockState state, boolean isLog) {
	}

	private static final class FallingTree {
		final ServerWorld world;
		final BlockPos pivot;
		final Direction fallDir;
		final WoodSpecies species;
		final int trunkHeight;
		final int totalLogs;
		final List<BlockEntry> entries;
		final List<UUID> displayIds;
		final Set<UUID> hitEntities = new HashSet<>();
		final Set<BlockPos> smashedBlocks = new HashSet<>();
		int tick = 0;

		FallingTree(ServerWorld world, BlockPos pivot, Direction fallDir, WoodSpecies species,
					int trunkHeight, int totalLogs, List<BlockEntry> entries, List<UUID> displayIds) {
			this.world = world;
			this.pivot = pivot;
			this.fallDir = fallDir;
			this.species = species;
			this.trunkHeight = trunkHeight;
			this.totalLogs = totalLogs;
			this.entries = entries;
			this.displayIds = displayIds;
		}

		boolean step() {
			tick++;
			if (tick == 1) {
				// Phase 1: slow initial lean (0 -> 22 degrees over 10 ticks)
				updateDisplays(22.0f, 0, 10);
			} else if (tick == 11) {
				// Phase 2: accelerating fall to ground (22 -> 90 degrees over 15 ticks)
				updateDisplays(90.0f, 1, 15);
				world.playSound(null, pivot, SoundEvents.BLOCK_WOOD_STEP, SoundCategory.BLOCKS, 1.2f, 0.5f);
			}

			if (tick >= 12 && tick <= TOTAL_FALL_TICKS) {
				float frac = (tick - 10.0f) / (TOTAL_FALL_TICKS - 10.0f);
				float angleDeg = 22.0f + 68.0f * Math.min(1.0f, frac);
				applySweepCollisions(angleDeg);
			}

			if (tick >= TOTAL_FALL_TICKS) {
				land();
				return false;
			}
			return true;
		}

		private void updateDisplays(float angleDeg, int startInterp, int duration) {
			for (int i = 0; i < displayIds.size() && i < entries.size(); i++) {
				Entity entity = world.getEntity(displayIds.get(i));
				if (entity instanceof DisplayEntity.BlockDisplayEntity display) {
					writeDisplayTransform(display, entries.get(i).state, entries.get(i).offset,
							angleDeg, fallDir, startInterp, duration);
				}
			}
		}

		private void applySweepCollisions(float angleDeg) {
			double rad = Math.toRadians(angleDeg);
			double sinA = Math.sin(rad);
			double cosA = Math.cos(rad);
			int reach = Math.max(2, trunkHeight);

			double dx = fallDir.getOffsetX();
			double dz = fallDir.getOffsetZ();

			for (int d = 1; d <= reach; d++) {
				double wx = pivot.getX() + 0.5 + dx * d * sinA;
				double wy = pivot.getY() + d * cosA;
				double wz = pivot.getZ() + 0.5 + dz * d * sinA;

				Box hitBox = new Box(wx - 1.1, wy - 0.8, wz - 1.1, wx + 1.1, wy + 1.4, wz + 1.1);
				float damage = Math.min(22.0f, 5.0f + totalLogs * 0.45f * species.hardness());
				for (LivingEntity living : world.getEntitiesByClass(LivingEntity.class, hitBox,
						e -> !hitEntities.contains(e.getUuid()))) {
					hitEntities.add(living.getUuid());
					living.damage(world.getDamageSources().generic(), damage);
					living.addVelocity(dx * 0.45, 0.35, dz * 0.45);
					living.velocityModified = true;
				}

				if (angleDeg >= 65.0f) {
					BlockPos center = BlockPos.ofFloored(wx, wy, wz);
					for (int ox = -1; ox <= 1; ox++) {
						for (int oy = 0; oy <= 1; oy++) {
							for (int oz = -1; oz <= 1; oz++) {
								BlockPos tp = center.add(ox, oy, oz);
								if (smashedBlocks.contains(tp)) {
									continue;
								}
								BlockState ts = world.getBlockState(tp);
								if (isCrushableStructure(world, tp, ts)) {
									smashedBlocks.add(tp);
									world.breakBlock(tp, true);
								}
							}
						}
					}
				}
			}
		}

		private static boolean isCrushableStructure(ServerWorld world, BlockPos pos, BlockState state) {
			if (state.isAir() || state.getBlock() instanceof FallenTrunkBlock) {
				return false;
			}
			float hardness = state.getHardness(world, pos);
			if (hardness < 0 || hardness > 2.5f) {
				return false;
			}
			// Preserve natural terrain ground blocks; smash wood/glass/foliage/torches/fences/planks
			return state.isIn(BlockTags.PLANKS) || state.isIn(BlockTags.FENCES)
					|| state.isIn(BlockTags.WOODEN_DOORS) || state.isIn(BlockTags.WOODEN_SLABS)
					|| state.isIn(BlockTags.WOODEN_STAIRS) || state.isIn(BlockTags.LEAVES)
					|| state.isIn(BlockTags.CROPS) || state.isIn(BlockTags.FLOWERS)
					|| state.isOf(Blocks.GLASS) || state.isOf(Blocks.GLASS_PANE)
					|| state.isOf(Blocks.TORCH) || state.isOf(Blocks.WALL_TORCH)
					|| state.isOf(Blocks.CRAFTING_TABLE) || state.isOf(Blocks.SCAFFOLDING);
		}

		private void land() {
			for (UUID id : displayIds) {
				Entity entity = world.getEntity(id);
				if (entity != null) {
					entity.discard();
				}
			}

			int dx = fallDir.getOffsetX();
			int dz = fallDir.getOffsetZ();
			int trunkCount = Math.max(2, Math.min(trunkHeight, totalLogs));

			BlockPos impactCenter = pivot.add(dx * Math.max(1, trunkCount / 2), 0, dz * Math.max(1, trunkCount / 2));
			world.playSound(null, impactCenter, SoundEvents.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 1.5f, 0.45f);
			world.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, species.vanillaLog().getDefaultState()),
					impactCenter.getX() + 0.5, impactCenter.getY() + 0.5, impactCenter.getZ() + 0.5,
					24, 1.5, 0.4, 1.5, 0.1);

			BlockState fallenState = species.fallenTrunkBlock().getDefaultState()
					.with(FallenTrunkBlock.AXIS, fallDir.getAxis())
					.with(FallenTrunkBlock.BRANCHED, true);

			int placed = 0;
			for (int d = 1; d <= trunkCount; d++) {
				BlockPos targetXZ = pivot.add(dx * d, 0, dz * d);
				BlockPos ground = findLandingSpot(targetXZ);
				if (ground != null) {
					world.setBlockState(ground, fallenState, Block.NOTIFY_ALL);
					placed++;
				} else {
					Block.dropStack(world, impactCenter, new ItemStack(species.freshLogItem()));
				}
			}

			// Extra branch logs from large branched oaks / 2x2 dark oaks become additional trunk blocks or sticks
			int extra = Math.max(0, totalLogs - placed);
			for (int i = 0; i < extra; i++) {
				BlockPos sidePos = pivot.add(dx * (1 + (i % trunkCount)) + (dz != 0 ? ((i % 2 == 0) ? 1 : -1) : 0),
						0, dz * (1 + (i % trunkCount)) + (dx != 0 ? ((i % 2 == 0) ? 1 : -1) : 0));
				BlockPos ground = findLandingSpot(sidePos);
				if (ground != null && i < 4) {
					world.setBlockState(ground, fallenState, Block.NOTIFY_ALL);
				} else {
					Block.dropStack(world, impactCenter, new ItemStack(species.freshLogItem()));
				}
			}

			// Scatter a couple of sticks at the crown impact site
			int sticks = 1 + world.random.nextInt(3);
			BlockPos crown = pivot.add(dx * trunkCount, 0, dz * trunkCount);
			Block.dropStack(world, crown, new ItemStack(Items.STICK, sticks));
		}

		private BlockPos findLandingSpot(BlockPos xzPos) {
			for (int dy = 2; dy >= -4; dy--) {
				BlockPos candidate = xzPos.up(dy);
				BlockState cur = world.getBlockState(candidate);
				BlockState below = world.getBlockState(candidate.down());
				boolean curOpen = cur.isAir() || cur.isReplaceable() || cur.isIn(BlockTags.LEAVES);
				boolean belowSolid = !below.isAir() && below.isSolidBlock(world, candidate.down());
				if (curOpen && belowSolid) {
					return candidate;
				}
			}
			return null;
		}
	}
}
