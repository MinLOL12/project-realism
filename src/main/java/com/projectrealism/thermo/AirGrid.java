package com.projectrealism.thermo;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Coarse 2x2x2 block spatial fluid/gas simulation tracking temperature, buoyancy pressure, velocity,
 * O2, CO2, CO, smoke concentration, and water vapor around active heat and combustion zones.
 */
public final class AirGrid {
	/** Side length of one airflow grid cell in blocks (2 m). */
	public static final int CELL_SIZE = 2;
	private static final double CELL_VOLUME_M3 = CELL_SIZE * CELL_SIZE * CELL_SIZE; // 8 m^3
	private static final double AIR_MASS_PER_CELL_KG = CELL_VOLUME_M3 * ThermalConstants.AIR_DENSITY; // ~9.8 kg

	public record CellPos(int x, int y, int z) {
		public static CellPos fromBlock(BlockPos pos) {
			return new CellPos(Math.floorDiv(pos.getX(), CELL_SIZE),
					Math.floorDiv(pos.getY(), CELL_SIZE),
					Math.floorDiv(pos.getZ(), CELL_SIZE));
		}

		public BlockPos centerBlock() {
			return new BlockPos(x * CELL_SIZE + 1, y * CELL_SIZE + 1, z * CELL_SIZE + 1);
		}

		public CellPos offset(int dx, int dy, int dz) {
			return new CellPos(x + dx, y + dy, z + dz);
		}
	}

	public static final class AirCell {
		public double temperatureK;
		public double pressurePa;
		public double vx;
		public double vy;
		public double vz;
		public double o2 = ThermalConstants.AMBIENT_O2;
		public double co2 = ThermalConstants.AMBIENT_CO2;
		public double co = 0.0;
		public double smoke = 0.0;
		public double waterVapor = 0.008;
		public long lastTouchedTick = 0L;

		public AirCell(double ambientK) {
			this.temperatureK = ambientK;
		}

		public double speedMS() {
			return Math.sqrt(vx * vx + vy * vy + vz * vz);
		}

		public boolean isNearAmbient(double ambientK) {
			return Math.abs(temperatureK - ambientK) < 1.5
					&& Math.abs(o2 - ThermalConstants.AMBIENT_O2) < 0.003
					&& co < 1.0e-5
					&& smoke < 0.015;
		}

		public NbtCompound toNbt(CellPos pos) {
			NbtCompound tag = new NbtCompound();
			tag.putInt("x", pos.x);
			tag.putInt("y", pos.y);
			tag.putInt("z", pos.z);
			tag.putDouble("t", temperatureK);
			tag.putDouble("p", pressurePa);
			tag.putDouble("vx", vx);
			tag.putDouble("vy", vy);
			tag.putDouble("vz", vz);
			tag.putDouble("o2", o2);
			tag.putDouble("co2", co2);
			tag.putDouble("co", co);
			tag.putDouble("sm", smoke);
			tag.putDouble("wv", waterVapor);
			return tag;
		}

		public static AirCell fromNbt(NbtCompound tag) {
			AirCell c = new AirCell(tag.getDouble("t"));
			c.pressurePa = tag.getDouble("p");
			c.vx = tag.getDouble("vx");
			c.vy = tag.getDouble("vy");
			c.vz = tag.getDouble("vz");
			c.o2 = tag.getDouble("o2");
			c.co2 = tag.getDouble("co2");
			c.co = tag.getDouble("co");
			c.smoke = tag.getDouble("sm");
			c.waterVapor = tag.getDouble("wv");
			return c;
		}
	}

	/** Supplies geometry permeability (0=sealed wall, 1=open air) and outdoor ventilation status. */
	public interface GeometrySampler {
		double cellOpenness(CellPos cell);
		boolean isOutdoorVented(CellPos cell);
	}

	private final Map<CellPos, AirCell> cells = new HashMap<>();

	public Map<CellPos, AirCell> cells() {
		return cells;
	}

	public AirCell getOrCreate(CellPos pos, double ambientK, long tick) {
		AirCell cell = cells.computeIfAbsent(pos, k -> new AirCell(ambientK));
		cell.lastTouchedTick = tick;
		return cell;
	}

	public AirCell getReadOnly(CellPos pos, double ambientK) {
		AirCell c = cells.get(pos);
		return c != null ? c : new AirCell(ambientK);
	}

	/** Ensures an active neighborhood around a combustion source is seeded in the grid. */
	public void activateRadius(CellPos center, int radius, double ambientK, long tick, GeometrySampler geo) {
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -1; dy <= radius + 2; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					CellPos p = center.offset(dx, dy, dz);
					if (geo.cellOpenness(p) > 0.01) {
						getOrCreate(p, ambientK, tick);
					}
				}
			}
		}
	}

	/**
	 * Injects combustion heat, O2 consumption, CO2/CO/smoke/steam production into the local air cell.
	 */
	public void injectCombustion(CellPos pos, double ambientK, long tick,
								 double heatWatts, double o2ConsumedKgS,
								 double co2ProducedKgS, double coProducedKgS,
								 double smokeRate, double steamKgS, double dtSeconds) {
		AirCell cell = getOrCreate(pos, ambientK, tick);
		double dt = Math.max(0.01, dtSeconds);

		// dT = Q / (m_air * c_p_air)
		double airCap = AIR_MASS_PER_CELL_KG * ThermalConstants.AIR_SPECIFIC_HEAT;
		cell.temperatureK = Math.min(1350.0, cell.temperatureK + (heatWatts * dt) / airCap);

		// Gas composition updates scaled to cell mass (~9.8 kg)
		double dO2 = (o2ConsumedKgS * dt) / AIR_MASS_PER_CELL_KG;
		double dCo2 = (co2ProducedKgS * dt) / (AIR_MASS_PER_CELL_KG * 1.52);
		double dCo = (coProducedKgS * dt) / (AIR_MASS_PER_CELL_KG * 0.97);
		cell.o2 = Math.max(0.0, cell.o2 - dO2);
		cell.co2 = Math.min(0.45, cell.co2 + dCo2);
		cell.co = Math.min(0.08, cell.co + dCo);
		cell.smoke = Math.min(2.5, cell.smoke + (smokeRate * dt) * 0.65);
		cell.waterVapor = Math.min(0.18, cell.waterVapor + (steamKgS * dt) / AIR_MASS_PER_CELL_KG);
	}

	/**
	 * Advances buoyancy, velocity, pressure-driven diffusion, smoke rising/pooling, and outdoor exchange.
	 */
	public void step(double dtSeconds, double ambientK, double windX, double windZ,
					 long currentTick, GeometrySampler geo) {
		if (cells.isEmpty()) {
			return;
		}
		double dt = Math.max(0.05, Math.min(1.0, dtSeconds));

		// Precompute openness per active cell
		Map<CellPos, Double> openness = new HashMap<>(cells.size() * 2);
		List<CellPos> keys = new ArrayList<>(cells.keySet());
		for (CellPos pos : keys) {
			openness.put(pos, geo.cellOpenness(pos));
		}

		// 1. Update buoyancy, pressure anomaly, and wind forcing
		for (CellPos pos : keys) {
			AirCell c = cells.get(pos);
			double open = openness.getOrDefault(pos, 0.0);
			if (open <= 0.01) {
				c.vx = 0;
				c.vy = 0;
				c.vz = 0;
				continue;
			}
			double tempExcess = c.temperatureK - ambientK;
			// Buoyant upward acceleration: g * dT / T_amb
			double buoyAccel = ThermalConstants.GRAVITY * (tempExcess / Math.max(220.0, ambientK));
			c.vy = Math.max(-2.5, Math.min(4.5, c.vy * 0.65 + buoyAccel * dt * 1.8));
			c.pressurePa = (tempExcess / Math.max(220.0, ambientK)) * 12.0;

			if (geo.isOutdoorVented(pos)) {
				c.vx = c.vx * 0.6 + windX * 0.4;
				c.vz = c.vz * 0.6 + windZ * 0.4;
			} else {
				c.vx *= 0.85;
				c.vz *= 0.85;
			}
		}

		// 2. Compute pairwise exchange fluxes across 6 faces
		int[][] dirs = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		Map<CellPos, double[]> deltas = new HashMap<>(cells.size() * 2);
		for (CellPos pos : keys) {
			deltas.put(pos, new double[8]); // dT, dO2, dCo2, dCo, dSmoke, dVapor, dVx, dVz
		}

		for (CellPos pos : keys) {
			AirCell a = cells.get(pos);
			double openA = openness.getOrDefault(pos, 0.0);
			if (openA <= 0.01) {
				continue;
			}

			for (int[] d : dirs) {
				// Only process positive half-directions once per interior pair; boundary open neighbors also exchange!
				int dx = d[0], dy = d[1], dz = d[2];
				CellPos nbPos = pos.offset(dx, dy, dz);
				double openB = openness.computeIfAbsent(nbPos, geo::cellOpenness);
				double facePerm = Math.min(openA, openB);
				if (facePerm <= 0.01) {
					// Blocked by wall/ceiling! Deflect rising smoke/pressure sideways when roof blocks +Y
					if (dy == 1 && a.vy > 0.15) {
						double deflect = a.vy * 0.25;
						double[] da = deltas.get(pos);
						da[6] += (dx == 0 ? (pos.x % 2 == 0 ? deflect : -deflect) : 0);
						a.vy *= 0.2;
					}
					continue;
				}

				AirCell b = cells.get(nbPos);
				boolean bMissing = (b == null);
				boolean positiveDir = dx > 0 || dy > 0 || dz > 0;
				if (!bMissing && !positiveDir) {
					// Already processed symmetrically from the neighbor's positive axis
					continue;
				}
				if (bMissing) {
					b = new AirCell(ambientK);
				}

				// Directional advection velocity across face A -> B (average normal velocity on interface)
				double faceVel = ((a.vx + b.vx) * 0.5) * dx + ((a.vy + b.vy) * 0.5) * dy + ((a.vz + b.vz) * 0.5) * dz;
				// Pressure gradient induces flow from high pressure -> low pressure
				double pressureFlow = (a.pressurePa - b.pressurePa) * 0.14;
				double signedFlow = faceVel + pressureFlow;
				double flowMag = Math.min(0.35, Math.abs(signedFlow) * 0.28 * facePerm * dt);
				double diff = Math.min(0.22, 0.14 * facePerm * dt);
				double ex = Math.min(0.42, diff + flowMag);

				// Upwind donor value for advected scalar, plus symmetric diffusive exchange
				AirCell upwind = signedFlow >= 0 ? a : b;
				double dT = (b.temperatureK - a.temperatureK) * diff
						+ ((signedFlow >= 0 ? -(a.temperatureK - b.temperatureK) : (b.temperatureK - a.temperatureK)) * flowMag);
				double dO2 = (b.o2 - a.o2) * (diff + flowMag);
				double dCo2 = (b.co2 - a.co2) * diff + (upwind == b ? (b.co2 - a.co2) : -(a.co2 - b.co2)) * flowMag;
				double dCo = (b.co - a.co) * diff + (upwind == b ? (b.co - a.co) : -(a.co - b.co)) * flowMag;
				double dSmoke = (b.smoke - a.smoke) * diff + (upwind == b ? (b.smoke - a.smoke) : -(a.smoke - b.smoke)) * flowMag;
				double dVapor = (b.waterVapor - a.waterVapor) * diff
						+ (upwind == b ? (b.waterVapor - a.waterVapor) : -(a.waterVapor - b.waterVapor)) * flowMag;

				double[] da = deltas.get(pos);
				da[0] += dT;
				da[1] += dO2;
				da[2] += dCo2;
				da[3] += dCo;
				da[4] += dSmoke;
				da[5] += dVapor;

				if (!bMissing) {
					double[] db = deltas.get(nbPos);
					db[0] -= dT;
					db[1] -= dO2;
					db[2] -= dCo2;
					db[3] -= dCo;
					db[4] -= dSmoke;
					db[5] -= dVapor;
					// Continuity: upward plume draws cooler air laterally inward
					if (dy == 1 && a.vy > 0.2) {
						db[6] += (a.temperatureK - ambientK) * 0.002 * facePerm;
					}
				} else {
					// Open sky chimney/window exchanges directly with infinite outdoor atmosphere
					if (dy == 1 && a.vy > 0.2 && geo.isOutdoorVented(nbPos)) {
						da[1] += (ThermalConstants.AMBIENT_O2 - a.o2) * 0.20 * facePerm * dt;
					}
					if (ex > 0.02 && (a.smoke > 0.06 || a.temperatureK > ambientK + 18.0) && !a.isNearAmbient(ambientK)) {
						if (dy >= 0 && cells.size() < 2500) {
							AirCell spawned = cells.computeIfAbsent(nbPos, k -> new AirCell(ambientK));
							spawned.lastTouchedTick = currentTick;
							spawned.temperatureK += Math.max(0.0, -dT * 0.5);
							spawned.smoke += Math.max(0.0, -dSmoke * 0.5);
							spawned.co += Math.max(0.0, -dCo * 0.5);
						}
					}
				}
			}
		}

		// 3. Apply deltas, outdoor wind exchange, particulate settling, and prune dormant cells
		Iterator<Map.Entry<CellPos, AirCell>> it = cells.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<CellPos, AirCell> e = it.next();
			CellPos pos = e.getKey();
			AirCell c = e.getValue();
			double[] d = deltas.get(pos);
			if (d != null) {
				c.temperatureK = Math.max(220.0, Math.min(1400.0, c.temperatureK + d[0]));
				c.o2 = Math.max(0.0, Math.min(0.215, c.o2 + d[1]));
				c.co2 = Math.max(ThermalConstants.AMBIENT_CO2, Math.min(0.45, c.co2 + d[2]));
				c.co = Math.max(0.0, Math.min(0.08, c.co + d[3]));
				c.smoke = Math.max(0.0, Math.min(3.0, c.smoke + d[4]));
				c.waterVapor = Math.max(0.002, Math.min(0.20, c.waterVapor + d[5]));
				c.vx = Math.max(-4.0, Math.min(4.0, c.vx + d[6]));
			}

			// Outdoor cells rapidly exchange with ambient wind atmosphere
			if (geo.isOutdoorVented(pos)) {
				double relax = 0.14 * dt;
				c.temperatureK += (ambientK - c.temperatureK) * relax;
				c.o2 += (ThermalConstants.AMBIENT_O2 - c.o2) * relax * 1.4;
				c.co2 += (ThermalConstants.AMBIENT_CO2 - c.co2) * relax;
				c.co *= (1.0 - relax * 1.4);
				c.smoke *= (1.0 - relax * 1.2);
				c.waterVapor += (0.009 - c.waterVapor) * relax;
			} else {
				// Indoors: slow surface cooling & gradual soot settling only
				c.temperatureK += (ambientK - c.temperatureK) * (0.012 * dt);
				c.smoke *= (1.0 - 0.01 * dt);
				c.co *= (1.0 - 0.004 * dt);
			}

			if (currentTick - c.lastTouchedTick > 100 && c.isNearAmbient(ambientK)) {
				it.remove();
			}
		}
	}

	/** Geometry sampler backed by live Minecraft blocks in a ServerWorld. */
	public static GeometrySampler worldSampler(ServerWorld world) {
		return new GeometrySampler() {
			@Override
			public double cellOpenness(CellPos cell) {
				BlockPos origin = new BlockPos(cell.x * CELL_SIZE, cell.y * CELL_SIZE, cell.z * CELL_SIZE);
				if (!world.isChunkLoaded(origin.getX() >> 4, origin.getZ() >> 4)) {
					return 1.0;
				}
				double sum = 0.0;
				for (int dx = 0; dx < CELL_SIZE; dx++) {
					for (int dy = 0; dy < CELL_SIZE; dy++) {
						for (int dz = 0; dz < CELL_SIZE; dz++) {
							sum += blockAirPermeability(world, origin.add(dx, dy, dz));
						}
					}
				}
				return sum / 8.0;
			}

			@Override
			public boolean isOutdoorVented(CellPos cell) {
				BlockPos center = cell.centerBlock();
				if (!world.isChunkLoaded(center.getX() >> 4, center.getZ() >> 4)) {
					return true;
				}
				return world.isSkyVisible(center);
			}
		};
	}

	private static double blockAirPermeability(ServerWorld world, BlockPos pos) {
		BlockState s = world.getBlockState(pos);
		if (s.isAir() || s.isOf(Blocks.FIRE) || s.isOf(Blocks.SOUL_FIRE)
				|| s.isOf(Blocks.CAMPFIRE) || s.isOf(Blocks.SOUL_CAMPFIRE)
				|| s.isIn(BlockTags.FLOWERS) || s.isOf(Blocks.SHORT_GRASS)
				|| s.isOf(Blocks.TALL_GRASS) || s.isOf(Blocks.TORCH) || s.isOf(Blocks.WALL_TORCH)) {
			return 1.0;
		}
		if (s.getBlock() instanceof DoorBlock && s.contains(DoorBlock.OPEN)) {
			return s.get(DoorBlock.OPEN) ? 0.90 : 0.05;
		}
		if (s.getBlock() instanceof TrapdoorBlock && s.contains(TrapdoorBlock.OPEN)) {
			return s.get(TrapdoorBlock.OPEN) ? 0.85 : 0.06;
		}
		if (s.isIn(BlockTags.FENCES) || s.isOf(Blocks.IRON_BARS) || s.isIn(BlockTags.LEAVES)) {
			return 0.65;
		}
		if (!s.isOpaqueFullCube(world, pos)) {
			return 0.45;
		}
		return 0.0;
	}

	/** Spawns visible rising smoke / soot particles and applies smoke/CO/hypoxia penalties to players. */
	public void applyVisualsAndPlayerEffects(ServerWorld world, double ambientK) {
		for (Map.Entry<CellPos, AirCell> entry : cells.entrySet()) {
			AirCell c = entry.getValue();
			if (c.smoke > 0.14 && world.random.nextFloat() < Math.min(0.65f, (float) (c.smoke * 0.45))) {
				BlockPos p = entry.getKey().centerBlock();
				if (world.isChunkLoaded(p.getX() >> 4, p.getZ() >> 4)) {
					var particle = c.smoke > 0.55 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.CAMPFIRE_COSY_SMOKE;
					world.spawnParticles(particle,
							p.getX() + (world.random.nextDouble() - 0.5) * 1.2,
							p.getY() + (world.random.nextDouble() - 0.5) * 0.8,
							p.getZ() + (world.random.nextDouble() - 0.5) * 1.2,
							1, c.vx * 0.03, Math.max(0.02, c.vy * 0.05), c.vz * 0.03, 0.008);
				}
			}
		}

		for (ServerPlayerEntity player : world.getPlayers()) {
			if (player.isCreative() || player.isSpectator()) {
				continue;
			}
			CellPos headCell = CellPos.fromBlock(BlockPos.ofFloored(player.getX(), player.getEyeY(), player.getZ()));
			AirCell air = getReadOnly(headCell, ambientK);

			boolean lowO2 = air.o2 < 0.165;
			boolean toxicCo = air.co > 0.0005;
			boolean heavySmoke = air.smoke > 0.45;

			if (toxicCo || air.o2 < 0.13) {
				player.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 90, 0, true, false));
				player.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 90, 0, true, false));
				if (air.co > 0.0018 || air.o2 < 0.105) {
					player.damage(world.getDamageSources().generic(), 1.5f);
				}
				player.sendMessage(Text.translatable("project_realism.air.toxic",
								Math.round(air.o2 * 100.0), Math.round(air.co * 100000.0))
						.formatted(Formatting.DARK_RED), true);
			} else if (lowO2 || heavySmoke) {
				player.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 0, true, false));
				player.sendMessage(Text.translatable("project_realism.air.smoky",
								Math.round(air.o2 * 100.0))
						.formatted(Formatting.GOLD), true);
			}
		}
	}

	public NbtList toNbt() {
		NbtList list = new NbtList();
		for (Map.Entry<CellPos, AirCell> e : cells.entrySet()) {
			list.add(e.getValue().toNbt(e.getKey()));
		}
		return list;
	}

	public void loadNbt(NbtList list) {
		cells.clear();
		for (int i = 0; i < list.size(); i++) {
			NbtCompound tag = list.getCompound(i);
			CellPos pos = new CellPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z"));
			cells.put(pos, AirCell.fromNbt(tag));
		}
	}
}
