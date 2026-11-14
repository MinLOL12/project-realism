package com.projectrealism.thermo;

import com.projectrealism.ProjectRealism;
import com.projectrealism.primitive.ToolCondition;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Simulates physical combustion of real fuel items placed in a {@link HearthBlock}, tracking
 * multi-stage pyrolysis, moisture evaporation, O2-limited gas flaming, glowing char embers,
 * charcoal/ash residue production, and multi-method food cooking.
 */
public class HearthBlockEntity extends BlockEntity {
	public static final int MAX_FUEL_ITEMS = 6;

	public static final class FuelItem {
		public ItemStack originalStack;
		public MaterialProfile.Properties profile;
		public ThermalMath.BodyState body;

		public FuelItem(ItemStack originalStack, MaterialProfile.Properties profile, ThermalMath.BodyState body) {
			this.originalStack = originalStack;
			this.profile = profile;
			this.body = body;
		}
	}

	private final List<FuelItem> fuels = new ArrayList<>();
	private ItemStack cookingFood = ItemStack.EMPTY;
	private double bedTempK = ThermalConstants.AMBIENT_DEFAULT_K;
	private double storedAshKg = 0.0;
	private double storedCharcoalKg = 0.0;
	private int tickCounter = 0;

	public HearthBlockEntity(BlockPos pos, BlockState state) {
		super(ThermoBlocks.HEARTH_ENTITY, pos, state);
	}

	public double temperatureK() {
		double max = bedTempK;
		for (FuelItem f : fuels) {
			max = Math.max(max, f.body.surfaceTempK);
		}
		return max;
	}

	public boolean isFlaming() {
		for (FuelItem f : fuels) {
			if (f.body.flaming) {
				return true;
			}
		}
		return false;
	}

	public boolean hasEmbers() {
		for (FuelItem f : fuels) {
			if (f.body.charMassKg > 0.05 && f.body.surfaceTempK >= ThermalConstants.CHAR_OXIDATION_K) {
				return true;
			}
		}
		return storedCharcoalKg > 0.05 && bedTempK > 450.0;
	}

	public boolean hasFuel() {
		return !fuels.isEmpty();
	}

	public boolean addFuel(ItemStack stack, World world) {
		if (fuels.size() >= MAX_FUEL_ITEMS || stack.isEmpty()) {
			return false;
		}
		MaterialProfile.Properties props = MaterialProfile.forFuelItem(stack);
		if (!props.isCombustible()) {
			return false;
		}
		double ambient = (world instanceof ServerWorld sw)
				? ThermalWorldState.get(sw).ambientTempAt(sw, pos)
				: ThermalConstants.AMBIENT_DEFAULT_K;
		double surfM = ToolCondition.getSurfaceMoisture(stack, props.defaultMoistureRatio());
		double coreM = ToolCondition.getCoreMoisture(stack, props.defaultMoistureRatio());
		ThermalMath.BodyState body = new ThermalMath.BodyState(ambient, props.dryMassKg(), surfM, coreM);
		body.surfaceTempK = Math.max(ambient, Math.min(bedTempK, ambient + 60.0));
		body.coreTempK = ambient;

		fuels.add(new FuelItem(stack.copyWithCount(1), props, body));
		markDirty();
		return true;
	}

	public boolean addFood(ItemStack stack) {
		if (!cookingFood.isEmpty() || !CookingPhysics.isFoodTrackable(stack)) {
			return false;
		}
		cookingFood = stack.copyWithCount(1);
		markDirty();
		return true;
	}

	public void takeTopItem(PlayerEntity player, World world, BlockPos pos) {
		if (!cookingFood.isEmpty()) {
			giveOrDrop(player, world, pos, cookingFood);
			cookingFood = ItemStack.EMPTY;
			markDirty();
			return;
		}
		if (storedCharcoalKg >= 0.45) {
			int count = Math.min(8, (int) Math.floor(storedCharcoalKg / 0.45));
			storedCharcoalKg -= count * 0.45;
			giveOrDrop(player, world, pos, new ItemStack(Items.CHARCOAL, count));
			markDirty();
			return;
		}
		if (storedAshKg >= 0.15) {
			int count = Math.min(8, (int) Math.floor(storedAshKg / 0.15));
			storedAshKg -= count * 0.15;
			giveOrDrop(player, world, pos, new ItemStack(ThermoItems.WOOD_ASH, count));
			markDirty();
			return;
		}
		if (!fuels.isEmpty()) {
			FuelItem last = fuels.remove(fuels.size() - 1);
			if (last.body.dryWoodMassKg > last.body.initialDryMassKg * 0.45) {
				ItemStack ret = last.originalStack.copy();
				ToolCondition.setMoisture(ret, last.body.surfaceMoistureRatio(), last.body.coreMoistureRatio());
				giveOrDrop(player, world, pos, ret);
			} else if (last.body.charMassKg > 0.25) {
				giveOrDrop(player, world, pos, new ItemStack(Items.CHARCOAL, 1));
			} else {
				giveOrDrop(player, world, pos, new ItemStack(ThermoItems.WOOD_ASH, 1));
			}
			markDirty();
		}
	}

	/** Injects mechanical friction or spark thermal energy directly into the driest/most reactive fuel. */
	public Firemaking.IgnitionOutcome applyIgnitionEnergy(ServerWorld world, double heatJoules, boolean spark) {
		if (fuels.isEmpty()) {
			return Firemaking.IgnitionOutcome.NO_FUEL;
		}
		ThermalWorldState state = ThermalWorldState.get(world);
		AirGrid.CellPos cellPos = AirGrid.CellPos.fromBlock(pos);
		double ambient = state.ambientTempAt(world, pos);
		AirGrid.AirCell air = state.airGrid().getReadOnly(cellPos, ambient);
		if (air.o2 < ThermalConstants.MIN_O2_FLAME) {
			return Firemaking.IgnitionOutcome.LOW_OXYGEN;
		}
		if (world.isRaining() && world.isSkyVisible(pos.up())) {
			heatJoules *= 0.25;
		}

		// Target the lowest ignition-threshold / driest fuel item first (tinder/kindling before heavy oak!)
		FuelItem target = fuels.get(0);
		double bestScore = Double.MAX_VALUE;
		for (FuelItem f : fuels) {
			double score = f.profile.ignitionTempK() + f.body.surfaceMoistureRatio() * 450.0 + f.body.dryWoodMassKg * 20.0;
			if (score < bestScore) {
				bestScore = score;
				target = f;
			}
		}

		double shellCap = ThermalMath.heatCapacityJPerK(
				Math.max(0.03, target.body.dryWoodMassKg * 0.30),
				target.profile.specificHeat(),
				target.body.surfaceMoistureKg);

		// Friction / spark heats surface; wet fuel wastes energy evaporating surface moisture first!
		double effectiveJ = heatJoules;
		if (target.body.surfaceMoistureKg > 0.004) {
			double evap = Math.min(target.body.surfaceMoistureKg,
					(effectiveJ * 0.70) / ThermalConstants.WATER_LATENT_VAPORIZATION);
			target.body.surfaceMoistureKg -= evap;
			effectiveJ -= evap * ThermalConstants.WATER_LATENT_VAPORIZATION;
			world.spawnParticles(ParticleTypes.CLOUD,
					pos.getX() + 0.5, pos.getY() + 0.35, pos.getZ() + 0.5,
					2, 0.1, 0.05, 0.1, 0.01);
		}
		target.body.surfaceTempK = Math.min(1250.0,
				target.body.surfaceTempK + Math.max(0.0, effectiveJ) / shellCap);
		bedTempK = Math.max(bedTempK, target.body.surfaceTempK * 0.85);
		markDirty();

		double effIgnK = target.profile.ignitionTempK() + target.body.surfaceMoistureRatio() * 220.0;
		if (target.body.surfaceTempK >= effIgnK || (spark && target.body.surfaceMoistureRatio() < 0.14
				&& target.body.dryWoodMassKg < 0.6)) {
			target.body.surfaceTempK = Math.max(target.body.surfaceTempK, effIgnK + 45.0);
			target.body.flaming = true;
			bedTempK = Math.max(bedTempK, target.body.surfaceTempK);
			updateBlockState(world);
			return Firemaking.IgnitionOutcome.IGNITED;
		}
		return target.body.surfaceMoistureRatio() > 0.22
				? Firemaking.IgnitionOutcome.TOO_WET
				: Firemaking.IgnitionOutcome.WARMING;
	}

	public static void tick(World world, BlockPos pos, BlockState state, HearthBlockEntity be) {
		if (!(world instanceof ServerWorld server) || !ProjectRealism.config().thermodynamicsSimulation) {
			return;
		}
		be.tickCounter++;
		// Simulate at 2 Hz (every 10 ticks = 0.5 s) for crisp response and ultra-low CPU overhead
		if (be.tickCounter % 10 != 0) {
			return;
		}
		double dt = 0.5;
		ThermalWorldState thermo = ThermalWorldState.get(server);
		double ambientK = thermo.ambientTempAt(server, pos);
		double rh = thermo.relativeHumidityAt(server, pos);

		AirGrid.CellPos cellPos = AirGrid.CellPos.fromBlock(pos);
		AirGrid.AirCell air = thermo.airGrid().getOrCreate(cellPos, ambientK, server.getTime());
		if (be.isFlaming() || be.hasEmbers()) {
			thermo.airGrid().activateRadius(cellPos, 2, ambientK, server.getTime(), AirGrid.worldSampler(server));
		}

		double rainRate = (server.isRaining() && server.isSkyVisible(pos.up())) ? 0.008 : 0.0;
		double totalHeatReleasedW = 0.0;
		double totalO2Used = 0.0;
		double totalCo2 = 0.0;
		double totalCo = 0.0;
		double totalSmoke = 0.0;
		double totalSteam = 0.0;

		// Mutual radiative/convective exchange inside the fuel bed
		double bedMaxT = be.bedTempK;
		for (FuelItem f : be.fuels) {
			bedMaxT = Math.max(bedMaxT, f.body.surfaceTempK);
		}

		Iterator<FuelItem> it = be.fuels.iterator();
		while (it.hasNext()) {
			FuelItem f = it.next();
			double extHeatW = 0.0;
			if (bedMaxT > f.body.surfaceTempK) {
				extHeatW += ThermalMath.radiativePowerWatts(0.92, f.profile.surfaceAreaM2() * 0.45,
						bedMaxT, f.body.surfaceTempK);
				extHeatW += ThermalMath.convectivePowerWatts(air.speedMS() + 0.5, f.profile.surfaceAreaM2() * 0.35,
						bedMaxT, f.body.surfaceTempK);
			}
			ThermalMath.stepBody(f.body, f.profile, dt, extHeatW, air.temperatureK,
					air.o2, air.speedMS(), rh, rainRate);

			totalHeatReleasedW += f.body.lastHeatReleasedW;
			totalO2Used += f.body.lastO2ConsumedKgS;
			totalCo2 += f.body.lastCo2ProducedKgS;
			totalCo += f.body.lastCoProducedKgS;
			totalSmoke += f.body.lastSmokeProduced;
			totalSteam += f.body.lastSteamProducedKgS;

			// Harvest residue when a fuel piece finishes burning or turns into stable charcoal in low O2
			if (f.body.dryWoodMassKg < 0.015 && f.body.charMassKg < 0.02) {
				be.storedAshKg += f.body.ashMassKg;
				it.remove();
			} else if (f.body.dryWoodMassKg < 0.015 && air.o2 < ThermalConstants.MIN_O2_EMBER
					&& f.body.surfaceTempK < 540.0) {
				// Choked oxygen during pyrolysis leaves genuine solid charcoal!
				be.storedCharcoalKg += f.body.charMassKg;
				be.storedAshKg += f.body.ashMassKg;
				it.remove();
			}
		}

		// Update stone hearth bed temperature from fire heat & conductive loss into ground
		double bedHeatW = totalHeatReleasedW * 0.16;
		double bedLossW = ThermalMath.conductivePowerWatts(1.8, 0.8, 0.8, be.bedTempK, ambientK, 0.25)
				+ ThermalMath.radiativePowerWatts(0.9, 0.5, be.bedTempK, air.temperatureK);
		be.bedTempK = Math.max(ambientK, Math.min(1300.0, be.bedTempK + ((bedHeatW - bedLossW) * dt) / 14000.0));

		// Feed AirGrid & ThermalWorldState radiation/convection field
		if (totalHeatReleasedW > 1.0 || totalSmoke > 0.01 || totalSteam > 0.001) {
			thermo.airGrid().injectCombustion(cellPos, ambientK, server.getTime(),
					totalHeatReleasedW, totalO2Used, totalCo2, totalCo, totalSmoke, totalSteam, dt);
			thermo.radiateHeatSource(server, pos, be.temperatureK(), be.isFlaming() ? 1.4 : 0.7, dt);
		}

		// Advance food cooking if an item sits on the hearth
		if (!be.cookingFood.isEmpty() && be.temperatureK() > 335.0) {
			ThermalMath.CookingMethod method = detectCookingMethod(server, pos);
			ItemStack before = be.cookingFood;
			ItemStack after = CookingPhysics.stepCooking(before, method, be.temperatureK(), air.speedMS(), dt);
			be.cookingFood = after;
			if (after.getItem() != before.getItem()) {
				server.playSound(null, pos, SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.BLOCKS, 0.4f, 1.4f);
			}
			if (be.tickCounter % 20 == 0) {
				server.spawnParticles(ParticleTypes.SMOKE,
						pos.getX() + 0.5, pos.getY() + 0.45, pos.getZ() + 0.5,
						1, 0.08, 0.05, 0.08, 0.01);
			}
		}

		// Visual flames & steam
		if (be.isFlaming()) {
			server.spawnParticles(ParticleTypes.FLAME,
					pos.getX() + 0.5, pos.getY() + 0.35, pos.getZ() + 0.5,
					2, 0.16, 0.10, 0.16, 0.015);
			if (server.random.nextFloat() < 0.25f) {
				server.playSound(null, pos, SoundEvents.BLOCK_CAMPFIRE_CRACKLE, SoundCategory.BLOCKS, 0.45f, 1.0f);
			}
		} else if (be.hasEmbers() && server.random.nextFloat() < 0.45f) {
			server.spawnParticles(ParticleTypes.LAVA,
					pos.getX() + 0.5, pos.getY() + 0.25, pos.getZ() + 0.5,
					1, 0.15, 0.02, 0.15, 0.0);
		}
		if (totalSteam > 0.002) {
			server.spawnParticles(ParticleTypes.CLOUD,
					pos.getX() + 0.5, pos.getY() + 0.45, pos.getZ() + 0.5,
					1, 0.12, 0.08, 0.12, 0.01);
		}

		be.updateBlockState(server);
		be.markDirty();
	}

	private static ThermalMath.CookingMethod detectCookingMethod(ServerWorld world, BlockPos pos) {
		BlockState above = world.getBlockState(pos.up());
		if (above.isOf(Blocks.WATER_CAULDRON)) {
			return ThermalMath.CookingMethod.BOILING;
		}
		int solidWalls = 0;
		for (var dir : net.minecraft.util.math.Direction.values()) {
			if (dir != net.minecraft.util.math.Direction.DOWN
					&& world.getBlockState(pos.offset(dir)).isOpaqueFullCube(world, pos.offset(dir))) {
				solidWalls++;
			}
		}
		if (solidWalls >= 3) {
			return ThermalMath.CookingMethod.BAKING;
		}
		return ThermalMath.CookingMethod.ROASTING;
	}

	private void updateBlockState(World world) {
		BlockState cur = world.getBlockState(pos);
		if (!cur.isOf(ThermoBlocks.HEARTH)) {
			return;
		}
		boolean lit = isFlaming();
		boolean embers = hasEmbers();
		if (cur.get(HearthBlock.LIT) != lit || cur.get(HearthBlock.HAS_EMBERS) != embers) {
			world.setBlockState(pos, cur.with(HearthBlock.LIT, lit).with(HearthBlock.HAS_EMBERS, embers),
					Block.NOTIFY_ALL);
		}
	}

	public Text statusText() {
		int tempC = (int) Math.round(ThermalConstants.toCelsius(temperatureK()));
		double totalWood = fuels.stream().mapToDouble(f -> f.body.dryWoodMassKg).sum();
		double totalChar = storedCharcoalKg + fuels.stream().mapToDouble(f -> f.body.charMassKg).sum();
		double totalWater = fuels.stream().mapToDouble(f -> f.body.surfaceMoistureKg + f.body.coreMoistureKg).sum();
		if (!cookingFood.isEmpty()) {
			ThermalMath.FoodThermalState fs = CookingPhysics.readState(cookingFood);
			return Text.translatable("project_realism.hearth.status_cooking",
					tempC, cookingFood.getName().getString(),
					(int) Math.round(ThermalConstants.toCelsius(fs.coreTempK)),
					(int) Math.round(fs.cookedProgress * 100.0)).formatted(Formatting.GOLD);
		}
		return Text.translatable("project_realism.hearth.status",
						tempC,
						String.format("%.1f", totalWood),
						String.format("%.1f", totalChar),
						String.format("%.1f", totalWater),
						fuels.size(), MAX_FUEL_ITEMS)
				.formatted(isFlaming() ? Formatting.GOLD : Formatting.GRAY);
	}

	public void dropContents(World world, BlockPos pos) {
		if (!cookingFood.isEmpty()) {
			Block.dropStack(world, pos, cookingFood);
			cookingFood = ItemStack.EMPTY;
		}
		int charCount = (int) Math.floor(storedCharcoalKg / 0.45);
		if (charCount > 0) {
			Block.dropStack(world, pos, new ItemStack(Items.CHARCOAL, charCount));
		}
		int ashCount = (int) Math.floor(storedAshKg / 0.15);
		if (ashCount > 0) {
			Block.dropStack(world, pos, new ItemStack(ThermoItems.WOOD_ASH, ashCount));
		}
		for (FuelItem f : fuels) {
			if (f.body.dryWoodMassKg > f.body.initialDryMassKg * 0.45) {
				ItemStack ret = f.originalStack.copy();
				ToolCondition.setMoisture(ret, f.body.surfaceMoistureRatio(), f.body.coreMoistureRatio());
				Block.dropStack(world, pos, ret);
			} else if (f.body.charMassKg >= 0.25) {
				Block.dropStack(world, pos, new ItemStack(Items.CHARCOAL, 1));
			} else if (f.body.ashMassKg >= 0.05) {
				Block.dropStack(world, pos, new ItemStack(ThermoItems.WOOD_ASH, 1));
			}
		}
		fuels.clear();
	}

	private static void giveOrDrop(PlayerEntity player, World world, BlockPos pos, ItemStack stack) {
		if (!player.getInventory().insertStack(stack)) {
			Block.dropStack(world, pos, stack);
		}
	}

	@Override
	protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		super.writeNbt(nbt, registryLookup);
		nbt.putDouble("BedTempK", bedTempK);
		nbt.putDouble("StoredAsh", storedAshKg);
		nbt.putDouble("StoredChar", storedCharcoalKg);
		if (!cookingFood.isEmpty()) {
			nbt.put("CookingFood", cookingFood.encode(registryLookup));
		}
		NbtList fuelList = new NbtList();
		for (FuelItem f : fuels) {
			NbtCompound entry = new NbtCompound();
			entry.put("Item", f.originalStack.encode(registryLookup));
			entry.putDouble("ST", f.body.surfaceTempK);
			entry.putDouble("CT", f.body.coreTempK);
			entry.putDouble("SM", f.body.surfaceMoistureKg);
			entry.putDouble("CM", f.body.coreMoistureKg);
			entry.putDouble("DW", f.body.dryWoodMassKg);
			entry.putDouble("CH", f.body.charMassKg);
			entry.putDouble("AS", f.body.ashMassKg);
			entry.putDouble("IDW", f.body.initialDryMassKg);
			entry.putBoolean("FL", f.body.flaming);
			fuelList.add(entry);
		}
		nbt.put("Fuels", fuelList);
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		super.readNbt(nbt, registryLookup);
		bedTempK = nbt.contains("BedTempK") ? nbt.getDouble("BedTempK") : ThermalConstants.AMBIENT_DEFAULT_K;
		storedAshKg = nbt.getDouble("StoredAsh");
		storedCharcoalKg = nbt.getDouble("StoredChar");
		cookingFood = nbt.contains("CookingFood")
				? ItemStack.fromNbtOrEmpty(registryLookup, nbt.getCompound("CookingFood"))
				: ItemStack.EMPTY;
		fuels.clear();
		NbtList fuelList = nbt.getList("Fuels", NbtElement.COMPOUND_TYPE);
		for (int i = 0; i < fuelList.size(); i++) {
			NbtCompound entry = fuelList.getCompound(i);
			ItemStack stack = ItemStack.fromNbtOrEmpty(registryLookup, entry.getCompound("Item"));
			if (stack.isEmpty()) {
				continue;
			}
			MaterialProfile.Properties props = MaterialProfile.forFuelItem(stack);
			ThermalMath.BodyState body = new ThermalMath.BodyState(
					ThermalConstants.AMBIENT_DEFAULT_K,
					Math.max(0.05, entry.getDouble("IDW")),
					0.1, 0.1);
			body.surfaceTempK = entry.getDouble("ST");
			body.coreTempK = entry.getDouble("CT");
			body.surfaceMoistureKg = entry.getDouble("SM");
			body.coreMoistureKg = entry.getDouble("CM");
			body.dryWoodMassKg = entry.getDouble("DW");
			body.charMassKg = entry.getDouble("CH");
			body.ashMassKg = entry.getDouble("AS");
			body.flaming = entry.getBoolean("FL");
			fuels.add(new FuelItem(stack, props, body));
		}
	}
}
