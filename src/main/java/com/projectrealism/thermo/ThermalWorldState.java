package com.projectrealism.thermo;

import com.projectrealism.ProjectRealism;
import com.projectrealism.primitive.ToolCondition;
import com.projectrealism.wood.DryingWoodHelper;
import com.projectrealism.wood.FreshLogBlock;
import com.projectrealism.wood.RoughPlankBlock;
import com.projectrealism.wood.SplitWoodBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.PillarBlock;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.tag.BiomeTags;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.PersistentState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Per-world thermodynamic, moisture, combustion, material-stress, and dynamic airflow manager.
 * Persisted cleanly via {@link PersistentState} across restarts.
 */
public final class ThermalWorldState extends PersistentState {
	private static final String STATE_KEY = "project_realism_thermo";
	private static final Type<ThermalWorldState> TYPE = new Type<>(
			ThermalWorldState::new,
			ThermalWorldState::fromNbt,
			null
	);

	public static final class ThermalNode {
		public final BlockPos pos;
		public MaterialProfile.Properties props;
		public ThermalMath.BodyState body;
		public double pendingExternalHeatW = 0.0;
		public long lastTouchedTick = 0L;

		public ThermalNode(BlockPos pos, MaterialProfile.Properties props, ThermalMath.BodyState body) {
			this.pos = pos.toImmutable();
			this.props = props;
			this.body = body;
		}
	}

	public static final class HotStoneFood {
		public final BlockPos stonePos;
		public ItemStack stack;
		public long lastTick;

		public HotStoneFood(BlockPos stonePos, ItemStack stack, long lastTick) {
			this.stonePos = stonePos.toImmutable();
			this.stack = stack;
			this.lastTick = lastTick;
		}
	}

	private final Map<BlockPos, ThermalNode> nodes = new HashMap<>();
	private final AirGrid airGrid = new AirGrid();
	private final Map<BlockPos, HotStoneFood> hotStoneFoods = new HashMap<>();
	private int tickCounter = 0;

	public ThermalWorldState() {
	}

	public static ThermalWorldState get(ServerWorld world) {
		return world.getPersistentStateManager().getOrCreate(TYPE, STATE_KEY);
	}

	public AirGrid airGrid() {
		return airGrid;
	}

	public Map<BlockPos, ThermalNode> nodes() {
		return nodes;
	}

	public Map<BlockPos, HotStoneFood> hotStoneFoods() {
		return hotStoneFoods;
	}

	public double ambientTempAt(ServerWorld world, BlockPos pos) {
		double base = ProjectRealism.config().ambientTempKelvin;
		float biomeTemp = world.getBiome(pos).value().getTemperature();
		// Map Minecraft biome temp (~0.0 snowy .. 0.8 temperate .. 2.0 desert) to realistic Kelvin
		double offset = (biomeTemp - 0.7f) * 18.0;
		double dayCycle = world.isDay() ? 2.5 : -3.5;
		return Math.max(248.0, Math.min(318.0, base + offset + dayCycle));
	}

	public double relativeHumidityAt(ServerWorld world, BlockPos pos) {
		if (world.isRaining() && world.getBiome(pos).value().hasPrecipitation()) {
			return 0.92;
		}
		var biome = world.getBiome(pos);
		if (biome.isIn(BiomeTags.IS_JUNGLE) || biome.isIn(BiomeTags.IS_RIVER) || biome.isIn(BiomeTags.IS_OCEAN)) {
			return 0.82;
		}
		float temp = biome.value().getTemperature();
		if (temp >= 1.5f) {
			return 0.18;
		}
		if (temp <= 0.15f) {
			return 0.40;
		}
		return 0.52;
	}

	public ThermalNode getOrCreateNode(ServerWorld world, BlockPos pos) {
		BlockPos imm = pos.toImmutable();
		ThermalNode existing = nodes.get(imm);
		if (existing != null) {
			existing.lastTouchedTick = world.getTime();
			return existing;
		}
		BlockState state = world.getBlockState(imm);
		MaterialProfile.Properties props = MaterialProfile.forBlock(state);
		double ambient = ambientTempAt(world, imm);
		double m = props.defaultMoistureRatio();
		if (state.contains(DryingWoodHelper.MOISTURE)) {
			m = switch (state.get(DryingWoodHelper.MOISTURE)) {
				case 0 -> 0.11;
				case 1 -> 0.28;
				case 2 -> 0.48;
				default -> 0.72;
			};
		}
		ThermalMath.BodyState body = new ThermalMath.BodyState(ambient, props.dryMassKg(), m, m);
		ThermalNode node = new ThermalNode(imm, props, body);
		node.lastTouchedTick = world.getTime();
		nodes.put(imm, node);
		return node;
	}

	public ThermalNode getNodeReadOnly(BlockPos pos) {
		return nodes.get(pos);
	}

	public double blockSurfaceTempK(ServerWorld world, BlockPos pos) {
		ThermalNode node = nodes.get(pos);
		if (node != null) {
			return node.body.surfaceTempK;
		}
		BlockState s = world.getBlockState(pos);
		if (s.isOf(Blocks.LAVA)) return 1320.0;
		if (s.isOf(Blocks.MAGMA_BLOCK)) return 820.0;
		if (s.isOf(Blocks.FIRE) || s.isOf(Blocks.SOUL_FIRE)) return 1050.0;
		if ((s.isOf(Blocks.CAMPFIRE) || s.isOf(Blocks.SOUL_CAMPFIRE)) && (!s.contains(Properties.LIT) || s.get(Properties.LIT))) {
			return 920.0;
		}
		if (s.isOf(ThermoBlocks.HEARTH) && world.getBlockEntity(pos) instanceof HearthBlockEntity be) {
			return be.temperatureK();
		}
		return ambientTempAt(world, pos);
	}

	/** Steps world thermodynamics, heat transfer, airflow, fire spread, material damage, and cooking. */
	public void tick(ServerWorld world) {
		if (!ProjectRealism.config().thermodynamicsSimulation) {
			return;
		}
		tickCounter++;
		if (tickCounter % 10 != 0) {
			return;
		}
		double dt = 0.5;
		long worldTime = world.getTime();

		// 1. Scan vanilla environmental fires / lava / placed moist wood around online players every 1 s
		if (tickCounter % 20 == 0) {
			scanEnvironmentAroundPlayers(world, worldTime);
		}

		// 2. Conductive & radiative heat transfer across active nodes
		List<ThermalNode> snapshot = new ArrayList<>(nodes.values());
		for (ThermalNode n : snapshot) {
			if (!world.isChunkLoaded(n.pos.getX() >> 4, n.pos.getZ() >> 4)) {
				continue;
			}
			double ambient = ambientTempAt(world, n.pos);
			if (n.body.surfaceTempK > ambient + 8.0) {
				conductToNeighbors(world, n);
			}
			if (n.body.flaming || n.body.surfaceTempK > 540.0) {
				radiateHeatSource(world, n.pos, n.body.surfaceTempK, n.body.flaming ? 1.5 : 0.8, dt);
			}
		}

		// 3. Step all active thermal bodies (Heating -> Drying -> Pyrolysis -> Gas -> Char -> Ash)
		Iterator<Map.Entry<BlockPos, ThermalNode>> it = nodes.entrySet().iterator();
		List<Runnable> deferredWorldMutations = new ArrayList<>();

		while (it.hasNext()) {
			Map.Entry<BlockPos, ThermalNode> entry = it.next();
			BlockPos pos = entry.getKey();
			ThermalNode node = entry.getValue();
			if (!world.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
				continue;
			}

			BlockState currentState = world.getBlockState(pos);
			if (currentState.isAir() || currentState.isOf(Blocks.FIRE) || currentState.isOf(Blocks.SOUL_FIRE)) {
				it.remove();
				continue;
			}
			node.props = MaterialProfile.forBlock(currentState);

			double ambient = ambientTempAt(world, pos);
			double rh = relativeHumidityAt(world, pos);
			AirGrid.CellPos cellPos = AirGrid.CellPos.fromBlock(pos);
			AirGrid.AirCell air = airGrid.getReadOnly(cellPos, ambient);

			double rainWetting = computeEnvironmentalWetting(world, pos, currentState);
			double incomingW = node.pendingExternalHeatW;
			node.pendingExternalHeatW = 0.0;

			// Convective heat input if surrounded by hot rising air
			if (air.temperatureK > node.body.surfaceTempK + 5.0) {
				incomingW += ThermalMath.convectivePowerWatts(
						air.speedMS(), node.props.surfaceAreaM2() * 0.5, air.temperatureK, node.body.surfaceTempK);
			}

			ThermalMath.stepBody(node.body, node.props, dt, incomingW, air.temperatureK,
					air.o2, air.speedMS(), rh, rainWetting);

			// Inject released combustion gases & steam into AirGrid
			if (node.body.lastHeatReleasedW > 1.0 || node.body.lastSmokeProduced > 0.01
					|| node.body.lastSteamProducedKgS > 0.001) {
				airGrid.activateRadius(cellPos, 2, ambient, worldTime, AirGrid.worldSampler(world));
				airGrid.injectCombustion(cellPos, ambient, worldTime,
						node.body.lastHeatReleasedW,
						node.body.lastO2ConsumedKgS,
						node.body.lastCo2ProducedKgS,
						node.body.lastCoProducedKgS,
						node.body.lastSmokeProduced,
						node.body.lastSteamProducedKgS,
						dt);
			}

			// Synchronize placed wood block moisture state & visual steam
			if (currentState.contains(DryingWoodHelper.MOISTURE)) {
				double totalM = node.body.totalMoistureRatio();
				int visualStage = totalM <= 0.20 ? 0 : (totalM <= 0.38 ? 1 : (totalM <= 0.60 ? 2 : 3));
				int oldStage = currentState.get(DryingWoodHelper.MOISTURE);
				if (visualStage != oldStage) {
					if (currentState.getBlock() instanceof FreshLogBlock fresh && visualStage == 0) {
						deferredWorldMutations.add(() -> world.setBlockState(pos,
								fresh.species().vanillaLog().getDefaultState()
										.with(PillarBlock.AXIS, currentState.get(FreshLogBlock.AXIS)),
								Block.NOTIFY_ALL));
					} else {
						deferredWorldMutations.add(() -> world.setBlockState(pos,
								currentState.with(DryingWoodHelper.MOISTURE, visualStage), Block.NOTIFY_LISTENERS));
					}
				}
				if (node.body.lastSteamProducedKgS > 0.001) {
					world.spawnParticles(ParticleTypes.CLOUD,
							pos.getX() + 0.5, pos.getY() + 0.85, pos.getZ() + 0.5,
							1, 0.15, 0.1, 0.15, 0.01);
				}
			}

			// Visual fire / embers / burnout / material thermal damage
			evaluateBlockTransformations(world, pos, currentState, node, air, deferredWorldMutations, it);

			// Prune dormant ambient nodes to keep memory bounded
			boolean nearAmbient = Math.abs(node.body.surfaceTempK - ambient) < 1.5
					&& Math.abs(node.body.coreTempK - ambient) < 1.5
					&& !node.body.flaming
					&& node.body.stressDamage < 0.02
					&& !currentState.contains(DryingWoodHelper.MOISTURE);
			if (nearAmbient && worldTime - node.lastTouchedTick > 160) {
				it.remove();
			}
		}

		for (Runnable mut : deferredWorldMutations) {
			mut.run();
		}

		// 4. Advance dynamic airflow & smoke field
		if (ProjectRealism.config().dynamicAirflow && !airGrid.cells().isEmpty()) {
			double meanAmb = world.getPlayers().isEmpty()
					? ProjectRealism.config().ambientTempKelvin
					: ambientTempAt(world, world.getPlayers().get(0).getBlockPos());
			double windX = world.isThundering() ? 2.1 : (world.isRaining() ? 1.1 : 0.45);
			double windZ = world.isRaining() ? 0.6 : 0.25;
			airGrid.step(dt, meanAmb, windX, windZ, worldTime, AirGrid.worldSampler(world));
			airGrid.applyVisualsAndPlayerEffects(world, meanAmb);
		}

		// 5. Player radiant heating (with line-of-sight wall shielding!) & hot-stone cooking
		applyPlayerRadiantHeat(world, dt);
		stepHotStoneFoods(world, dt);
		markDirty();
	}

	private void scanEnvironmentAroundPlayers(ServerWorld world, long worldTime) {
		AirGrid.GeometrySampler sampler = AirGrid.worldSampler(world);
		for (ServerPlayerEntity player : world.getPlayers()) {
			BlockPos center = player.getBlockPos();
			for (int dx = -12; dx <= 12; dx++) {
				for (int dy = -6; dy <= 6; dy++) {
					for (int dz = -12; dz <= 12; dz++) {
						BlockPos p = center.add(dx, dy, dz);
						if (!world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) {
							continue;
						}
						BlockState s = world.getBlockState(p);
						if (s.isAir()) {
							continue;
						}
						Block b = s.getBlock();
						if (b instanceof FreshLogBlock || b instanceof SplitWoodBlock || b instanceof RoughPlankBlock) {
							getOrCreateNode(world, p);
							continue;
						}
						boolean litFire = b == Blocks.FIRE || b == Blocks.SOUL_FIRE
								|| ((b == Blocks.CAMPFIRE || b == Blocks.SOUL_CAMPFIRE
								|| b == Blocks.FURNACE || b == Blocks.SMOKER || b == Blocks.BLAST_FURNACE)
								&& (!s.contains(Properties.LIT) || s.get(Properties.LIT)));
						if (litFire || b == Blocks.LAVA || b == Blocks.MAGMA_BLOCK) {
							double srcT = (b == Blocks.LAVA) ? 1320.0
									: ((b == Blocks.MAGMA_BLOCK) ? 800.0 : 980.0);
							AirGrid.CellPos cp = AirGrid.CellPos.fromBlock(p);
							double amb = ambientTempAt(world, p);
							airGrid.activateRadius(cp, 2, amb, worldTime, sampler);
							airGrid.injectCombustion(cp, amb, worldTime,
									b == Blocks.MAGMA_BLOCK ? 350.0 : 2800.0,
									litFire ? 0.0025 : 0.0,
									litFire ? 0.003 : 0.0,
									0.0,
									litFire ? 0.10 : 0.0,
									0.0, 1.0);
							radiateHeatSource(world, p, srcT, 1.2, 0.5);
							// Conduct heat to block directly beneath fire/lava/campfire
							if (p.getY() > world.getBottomY()) {
								BlockPos below = p.down();
								if (!world.getBlockState(below).isAir()) {
									ThermalNode bn = getOrCreateNode(world, below);
									bn.pendingExternalHeatW += ThermalMath.conductivePowerWatts(
											1.5, bn.props.thermalConductivity(), 1.0,
											srcT, bn.body.surfaceTempK, 0.25);
								}
							}
						}
					}
				}
			}
		}
	}

	private void conductToNeighbors(ServerWorld world, ThermalNode source) {
		for (Direction dir : Direction.values()) {
			BlockPos np = source.pos.offset(dir);
			if (!world.isChunkLoaded(np.getX() >> 4, np.getZ() >> 4)) {
				continue;
			}
			BlockState ns = world.getBlockState(np);
			if (ns.isAir() || ns.isOf(Blocks.FIRE)) {
				continue;
			}
			ThermalNode target = getOrCreateNode(world, np);
			if (source.body.surfaceTempK > target.body.surfaceTempK) {
				double q = ThermalMath.conductivePowerWatts(
						source.props.thermalConductivity(), target.props.thermalConductivity(),
						0.85, source.body.surfaceTempK, target.body.surfaceTempK, 0.45);
				target.pendingExternalHeatW += q;
				source.pendingExternalHeatW -= q * 0.65;
			}
		}
	}

	/**
	 * Radiates Stefan-Boltzmann heat to nearby world blocks, respecting opaque-wall occlusion shielding.
	 */
	public void radiateHeatSource(ServerWorld world, BlockPos sourcePos, double sourceTempK,
								  double emitterAreaM2, double dtSeconds) {
		if (sourceTempK < 380.0) {
			return;
		}
		int r = sourceTempK > 850.0 ? 4 : 3;
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -2; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					if (dx == 0 && dy == 0 && dz == 0) {
						continue;
					}
					double dist2 = dx * dx + dy * dy + dz * dz;
					if (dist2 > r * r + 1.0) {
						continue;
					}
					BlockPos targetPos = sourcePos.add(dx, dy, dz);
					if (!world.isChunkLoaded(targetPos.getX() >> 4, targetPos.getZ() >> 4)) {
						continue;
					}
					BlockState ts = world.getBlockState(targetPos);
					if (ts.isAir() || ts.isOf(Blocks.FIRE)) {
						continue;
					}
					// Only heat blocks exposed on at least one air face (surface heating!)
					if (!hasExposedAirFace(world, targetPos)) {
						continue;
					}
					boolean los = hasLineOfSight(world, sourcePos, targetPos);
					double viewFactor = (los ? 1.0 : 0.04) * Math.min(1.0, 1.15 / (4.0 * Math.PI * dist2));
					if (viewFactor < 0.012) {
						continue;
					}
					ThermalNode target = getOrCreateNode(world, targetPos);
					double qRad = ThermalMath.radiativePowerWatts(
							0.92, emitterAreaM2, sourceTempK, target.body.surfaceTempK) * viewFactor;
					if (qRad > 5.0) {
						target.pendingExternalHeatW += qRad;
					}
				}
			}
		}
	}

	private void evaluateBlockTransformations(ServerWorld world, BlockPos pos, BlockState state,
											  ThermalNode node, AirGrid.AirCell air,
											  List<Runnable> deferred,
											  Iterator<Map.Entry<BlockPos, ThermalNode>> it) {
		// 1. Combustible blocks: ignite flame visibly or complete burnout -> charcoal / ash!
		if (node.props.isCombustible()) {
			if (node.body.flaming) {
				if (world.random.nextFloat() < 0.45f) {
					world.spawnParticles(ParticleTypes.FLAME,
							pos.getX() + 0.5, pos.getY() + 0.9, pos.getZ() + 0.5,
							2, 0.35, 0.35, 0.35, 0.02);
				}
				// Spawn visible fire block on an open adjacent face if space is free
				if (world.isAir(pos.up()) && world.random.nextFloat() < 0.25f) {
					deferred.add(() -> {
						if (world.isAir(pos.up())) {
							world.setBlockState(pos.up(), Blocks.FIRE.getDefaultState(), Block.NOTIFY_ALL);
						}
					});
				}
			} else if (node.body.charMassKg > 0.1 && node.body.surfaceTempK >= ThermalConstants.CHAR_OXIDATION_K) {
				if (world.random.nextFloat() < 0.25f) {
					world.spawnParticles(ParticleTypes.LAVA,
							pos.getX() + 0.5, pos.getY() + 0.6, pos.getZ() + 0.5,
							1, 0.3, 0.2, 0.3, 0.0);
				}
			}

			// Burnout: wood exhausted -> drop/place real Ash or Charcoal (low O2 kiln!)
			if (node.body.dryWoodMassKg <= 0.05 && node.body.charMassKg <= 0.08) {
				double ashKg = node.body.ashMassKg;
				deferred.add(() -> {
					world.removeBlock(pos, false);
					if (world.getBlockState(pos.down()).isSolidBlock(world, pos.down())) {
						int layers = Math.max(1, Math.min(4, (int) Math.ceil(ashKg / 0.15)));
						world.setBlockState(pos, ThermoBlocks.WOOD_ASH_PILE.getDefaultState()
								.with(AshPileBlock.LAYERS, layers), Block.NOTIFY_ALL);
					} else if (ashKg >= 0.05) {
						Block.dropStack(world, pos, new ItemStack(ThermoItems.WOOD_ASH, 1));
					}
				});
				it.remove();
				return;
			}

			// Choked oxygen pyrolysis -> drops solid charcoal!
			if (node.body.dryWoodMassKg <= 0.05 && node.body.charMassKg > 0.25
					&& air.o2 < ThermalConstants.MIN_O2_EMBER && node.body.surfaceTempK < 560.0) {
				int charCount = Math.max(1, Math.min(8, (int) Math.round(node.body.charMassKg / 0.45)));
				deferred.add(() -> {
					world.removeBlock(pos, false);
					Block.dropStack(world, pos, new ItemStack(Items.CHARCOAL, charCount));
				});
				it.remove();
				return;
			}

			// Weakened charred wood beam/plank collapses early before turning fully to ash
			if (ProjectRealism.config().thermalMaterialDamage
					&& node.body.structuralIntegrity() < 0.22
					&& (state.isIn(BlockTags.PLANKS) || state.isIn(BlockTags.WOODEN_FENCES)
					|| state.isIn(BlockTags.WOODEN_SLABS) || state.isIn(BlockTags.WOODEN_STAIRS))) {
				int charDrops = Math.max(1, (int) Math.round(node.body.charMassKg / 0.45));
				deferred.add(() -> {
					world.playSound(null, pos, SoundEvents.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 0.8f, 0.7f);
					world.removeBlock(pos, false);
					Block.dropStack(world, pos, new ItemStack(Items.CHARCOAL, charDrops));
					Block.dropStack(world, pos, new ItemStack(ThermoItems.WOOD_ASH, 1));
				});
				it.remove();
				return;
			}
		}

		// 2. Material thermal damage (Stone, Clay, Metal)
		if (ProjectRealism.config().thermalMaterialDamage) {
			switch (node.props.category()) {
				case STONE -> {
					if (node.body.stressDamage >= 0.90) {
						deferred.add(() -> {
							world.playSound(null, pos, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 0.9f, 0.65f);
							if (state.isOf(Blocks.COBBLESTONE) || state.isOf(Blocks.SANDSTONE)) {
								world.setBlockState(pos, Blocks.GRAVEL.getDefaultState(), Block.NOTIFY_ALL);
							} else {
								world.setBlockState(pos, Blocks.COBBLESTONE.getDefaultState(), Block.NOTIFY_ALL);
							}
						});
						node.body.stressDamage = 0.0;
					}
				}
				case CLAY -> {
					if (state.isOf(Blocks.CLAY)) {
						if (node.body.stressDamage >= 0.82) {
							deferred.add(() -> {
								world.playSound(null, pos, SoundEvents.BLOCK_GRAVEL_BREAK, SoundCategory.BLOCKS, 0.9f, 0.8f);
								world.removeBlock(pos, false);
								Block.dropStack(world, pos, new ItemStack(Items.CLAY_BALL, 2));
							});
							it.remove();
						} else if (node.body.coreTempK >= 830.0 && node.body.totalMoistureRatio() < 0.04) {
							deferred.add(() -> {
								world.setBlockState(pos, Blocks.TERRACOTTA.getDefaultState(), Block.NOTIFY_ALL);
								world.playSound(null, pos, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.5f, 1.3f);
							});
							node.body.stressDamage = 0.0;
						}
					}
				}
				case METAL -> {
					if (node.body.stressDamage >= 0.78 && state.isOf(Blocks.IRON_BARS)) {
						deferred.add(() -> {
							world.playSound(null, pos, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.BLOCKS, 0.4f, 0.6f);
							world.breakBlock(pos, true);
						});
						it.remove();
					}
				}
				default -> {
				}
			}
		}
	}

	private void applyPlayerRadiantHeat(ServerWorld world, double dt) {
		for (ServerPlayerEntity player : world.getPlayers()) {
			if (player.isCreative() || player.isSpectator()) {
				continue;
			}
			BlockPos pPos = player.getBlockPos();
			double totalRadiantFluxW = 0.0;
			for (int dx = -5; dx <= 5; dx++) {
				for (int dy = -2; dy <= 3; dy++) {
					for (int dz = -5; dz <= 5; dz++) {
						BlockPos src = pPos.add(dx, dy, dz);
						double dist2 = dx * dx + dy * dy + dz * dz;
						if (dist2 < 0.5 || dist2 > 28.0) {
							continue;
						}
						double srcT = blockSurfaceTempK(world, src);
						if (srcT < 430.0) {
							continue;
						}
						if (!hasLineOfSight(world, src, pPos.up())) {
							continue; // Solid wall shields direct radiant heat!
						}
						double p = ThermalMath.radiativePowerWatts(0.92, 1.0, srcT, ThermalConstants.AMBIENT_DEFAULT_K);
						totalRadiantFluxW += p / (4.0 * Math.PI * Math.max(0.75, dist2));
					}
				}
			}
			if (totalRadiantFluxW > 4200.0) {
				player.damage(world.getDamageSources().inFire(), (float) Math.min(3.0, (totalRadiantFluxW - 3500.0) / 3500.0 * dt * 2.0));
			}
		}
	}

	private void stepHotStoneFoods(ServerWorld world, double dt) {
		if (hotStoneFoods.isEmpty()) {
			return;
		}
		Iterator<Map.Entry<BlockPos, HotStoneFood>> it = hotStoneFoods.entrySet().iterator();
		while (it.hasNext()) {
			HotStoneFood hf = it.next();
			BlockPos stonePos = hf.stonePos;
			if (!world.isChunkLoaded(stonePos.getX() >> 4, stonePos.getZ() >> 4)) {
				continue;
			}
			if (world.getBlockState(stonePos).isAir() || hf.stack.isEmpty()) {
				if (!hf.stack.isEmpty()) {
					world.spawnEntity(new ItemEntity(world, stonePos.getX() + 0.5, stonePos.getY() + 1.1, stonePos.getZ() + 0.5, hf.stack));
				}
				it.remove();
				continue;
			}
			double stoneT = blockSurfaceTempK(world, stonePos);
			AirGrid.AirCell air = airGrid.getReadOnly(AirGrid.CellPos.fromBlock(stonePos.up()), ambientTempAt(world, stonePos));
			ItemStack before = hf.stack;
			ItemStack after = CookingPhysics.stepCooking(before, ThermalMath.CookingMethod.HOT_STONE, stoneT, air.speedMS(), dt);
			hf.stack = after;

			if (stoneT > 375.0 && world.random.nextFloat() < 0.35f) {
				world.spawnParticles(ParticleTypes.CLOUD,
						stonePos.getX() + 0.5, stonePos.getY() + 1.06, stonePos.getZ() + 0.5,
						1, 0.1, 0.05, 0.1, 0.01);
			}
			if (after.getItem() != before.getItem()) {
				world.playSound(null, stonePos, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.45f, 1.35f);
			}
		}
	}

	private static double computeEnvironmentalWetting(ServerWorld world, BlockPos pos, BlockState state) {
		double rate = 0.0;
		// Rain wetting on sky-exposed blocks
		if (world.isRaining() && world.isSkyVisible(pos.up())) {
			rate += 0.008;
		}
		// Immersion or water adjacency
		for (Direction d : Direction.values()) {
			BlockState ns = world.getBlockState(pos.offset(d));
			if (ns.isOf(Blocks.WATER)) {
				rate += 0.045;
			} else if (d == Direction.DOWN && (ns.isOf(Blocks.MUD)
					|| (ns.isOf(Blocks.GRASS_BLOCK) && world.isRaining()))) {
				// Ground wicking from mud or rain-soaked grass
				rate += 0.003;
			}
		}
		// Prevent non-porous sealed metals from soaking water
		MaterialProfile.Properties p = MaterialProfile.forBlock(state);
		return rate * Math.max(0.0, p.porosity());
	}

	private static boolean hasExposedAirFace(ServerWorld world, BlockPos pos) {
		for (Direction d : Direction.values()) {
			BlockState s = world.getBlockState(pos.offset(d));
			if (s.isAir() || s.isOf(Blocks.FIRE) || !s.isOpaqueFullCube(world, pos.offset(d))) {
				return true;
			}
		}
		return false;
	}

	/** Simple 3D voxel line trace checking whether an opaque wall blocks direct thermal radiation. */
	public static boolean hasLineOfSight(ServerWorld world, BlockPos from, BlockPos to) {
		Vec3d start = Vec3d.ofCenter(from);
		Vec3d end = Vec3d.ofCenter(to);
		Vec3d diff = end.subtract(start);
		double len = diff.length();
		if (len < 1.2) {
			return true;
		}
		int steps = (int) Math.ceil(len * 2.0);
		for (int i = 1; i < steps; i++) {
			double t = i / (double) steps;
			BlockPos sample = BlockPos.ofFloored(
					start.x + diff.x * t,
					start.y + diff.y * t,
					start.z + diff.z * t);
			if (sample.equals(from) || sample.equals(to)) {
				continue;
			}
			if (world.getBlockState(sample).isOpaqueFullCube(world, sample)) {
				return false;
			}
		}
		return true;
	}

	@Override
	public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		NbtList nodeList = new NbtList();
		for (ThermalNode n : nodes.values()) {
			NbtCompound tag = new NbtCompound();
			tag.putInt("x", n.pos.getX());
			tag.putInt("y", n.pos.getY());
			tag.putInt("z", n.pos.getZ());
			tag.putDouble("st", n.body.surfaceTempK);
			tag.putDouble("ct", n.body.coreTempK);
			tag.putDouble("sm", n.body.surfaceMoistureKg);
			tag.putDouble("cm", n.body.coreMoistureKg);
			tag.putDouble("dw", n.body.dryWoodMassKg);
			tag.putDouble("ch", n.body.charMassKg);
			tag.putDouble("as", n.body.ashMassKg);
			tag.putDouble("idw", n.body.initialDryMassKg);
			tag.putDouble("sd", n.body.stressDamage);
			tag.putBoolean("fl", n.body.flaming);
			nodeList.add(tag);
		}
		nbt.put("Nodes", nodeList);
		nbt.put("AirGrid", airGrid.toNbt());

		NbtList foodList = new NbtList();
		for (HotStoneFood hf : hotStoneFoods.values()) {
			if (!hf.stack.isEmpty()) {
				NbtCompound ft = new NbtCompound();
				ft.putInt("x", hf.stonePos.getX());
				ft.putInt("y", hf.stonePos.getY());
				ft.putInt("z", hf.stonePos.getZ());
				ft.put("Item", hf.stack.encode(registryLookup));
				foodList.add(ft);
			}
		}
		nbt.put("HotStoneFoods", foodList);
		return nbt;
	}

	public static ThermalWorldState fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		ThermalWorldState state = new ThermalWorldState();
		NbtList nodeList = nbt.getList("Nodes", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < nodeList.size(); i++) {
			NbtCompound tag = nodeList.getCompound(i);
			BlockPos pos = new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
			double idw = Math.max(0.05, tag.getDouble("idw"));
			ThermalMath.BodyState b = new ThermalMath.BodyState(ThermalConstants.AMBIENT_DEFAULT_K, idw, 0.1, 0.1);
			b.surfaceTempK = tag.getDouble("st");
			b.coreTempK = tag.getDouble("ct");
			b.surfaceMoistureKg = tag.getDouble("sm");
			b.coreMoistureKg = tag.getDouble("cm");
			b.dryWoodMassKg = tag.getDouble("dw");
			b.charMassKg = tag.getDouble("ch");
			b.ashMassKg = tag.getDouble("as");
			b.stressDamage = tag.getDouble("sd");
			b.flaming = tag.getBoolean("fl");
			state.nodes.put(pos, new ThermalNode(pos, MaterialProfile.OAK_WOOD, b));
		}
		if (nbt.contains("AirGrid")) {
			state.airGrid.loadNbt(nbt.getList("AirGrid", NbtElement.COMPOUND_TYPE));
		}
		NbtList foodList = nbt.getList("HotStoneFoods", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < foodList.size(); i++) {
			NbtCompound ft = foodList.getCompound(i);
			BlockPos pos = new BlockPos(ft.getInt("x"), ft.getInt("y"), ft.getInt("z"));
			ItemStack item = ItemStack.fromNbtOrEmpty(registryLookup, ft.getCompound("Item"));
			if (!item.isEmpty()) {
				state.hotStoneFoods.put(pos, new HotStoneFood(pos, item, 0L));
			}
		}
		return state;
	}
}
