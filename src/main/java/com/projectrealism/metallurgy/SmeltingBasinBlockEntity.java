package com.projectrealism.metallurgy;

import com.projectrealism.thermo.AirGrid;
import com.projectrealism.thermo.ThermalConstants;
import com.projectrealism.thermo.ThermalWorldState;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Random;

/**
 * BlockEntity driving live freeform furnace structure analysis, combustion/bellows thermodynamics,
 * ore reduction, slag separation, refractory clay vitrification, and seated crucible heating.
 */
public class SmeltingBasinBlockEntity extends BlockEntity {
	private final SmeltingChemistry.ChamberState chamber = new SmeltingChemistry.ChamberState();
	private CrucibleState seatedCrucible = null;
	private int tickCounter = 0;

	public SmeltingBasinBlockEntity(BlockPos pos, BlockState state) {
		super(MetallurgyBlocks.SMELTING_BASIN_ENTITY, pos, state);
	}

	public SmeltingChemistry.ChamberState chamber() {
		return chamber;
	}

	public FurnaceStructure.GeometrySummary scanFurnace(World world, BlockPos pos) {
		return FurnaceStructure.analyze(new FurnaceStructure.BlockProbe() {
			@Override
			public FurnaceStructure.WallMaterial probe(int dx, int dy, int dz) {
				BlockState bs = world.getBlockState(pos.add(dx, dy, dz));
				if (bs.isAir() || bs.isOf(MetallurgyBlocks.CLAY_CRUCIBLE)) return FurnaceStructure.WallMaterial.AIR;
				if (bs.isOf(MetallurgyBlocks.FIRE_CLAY_BRICKS)) return FurnaceStructure.WallMaterial.REFRACTORY_CLAY;
				if (bs.isOf(MetallurgyBlocks.FIRED_CLAY_WALL) || bs.isOf(Blocks.TERRACOTTA)
						|| bs.isOf(Blocks.BRICKS)) return FurnaceStructure.WallMaterial.FIRED_CLAY;
				if (bs.isOf(Blocks.BASALT) || bs.isOf(Blocks.SMOOTH_BASALT) || bs.isOf(Blocks.DEEPSLATE)
						|| bs.isOf(Blocks.COBBLED_DEEPSLATE) || bs.isOf(Blocks.BLACKSTONE)) {
					return FurnaceStructure.WallMaterial.BASALT_DEEPSLATE;
				}
				if (bs.isOf(Blocks.STONE) || bs.isOf(Blocks.COBBLESTONE) || bs.isOf(Blocks.GRANITE)
						|| bs.isOf(Blocks.ANDESITE) || bs.isOf(Blocks.DIORITE)) {
					return FurnaceStructure.WallMaterial.GRANITE_STONE;
				}
				if (bs.isOf(Blocks.CALCITE)) return FurnaceStructure.WallMaterial.LIMESTONE;
				if (bs.isOf(MetallurgyBlocks.RAW_CLAY_BLOCK) || bs.isOf(Blocks.CLAY)) {
					return FurnaceStructure.WallMaterial.RAW_CLAY;
				}
				return FurnaceStructure.WallMaterial.DIRT_OR_WOOD;
			}

			@Override
			public boolean isOpenToSky(int dx, int dy, int dz) {
				return world.isSkyVisible(pos.add(dx, dy, dz));
			}
		});
	}

	public void serverTick(World world, BlockPos pos) {
		if (++tickCounter % 10 != 0) return;
		if (!(world instanceof ServerWorld sw)) return;

		double ambientK = ThermalWorldState.get(sw).ambientTempAt(sw, pos);
		FurnaceStructure.GeometrySummary geom = scanFurnace(world, pos);
		SmeltingChemistry.step(chamber, geom, ambientK, 0.5);

		// Inject heat and CO/exhaust plumes into the thermo AirGrid!
		if (chamber.temperatureK > 520.0 && (chamber.charcoalMassKg > 0.01 || chamber.woodMassKg > 0.01)) {
			ThermalWorldState thermo = ThermalWorldState.get(sw);
			double heatW = Math.max(800.0, (chamber.temperatureK - ambientK) * 14.0);
			thermo.airGrid().injectCombustion(AirGrid.CellPos.fromBlock(pos), ambientK, sw.getTime(),
					heatW, 0.008, 0.009, chamber.coConcentration * 0.008, 0.15, 0.001, 0.5);
			thermo.radiateHeatSource(sw, pos, chamber.temperatureK, 1.5, 0.5);
			sw.spawnParticles(ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 0.72, pos.getZ() + 0.5,
					2, 0.14, 0.1, 0.14, 0.01);
			if (tickCounter % 30 == 0 && geom.chimneyHeightBlocks() > 0) {
				sw.spawnParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE,
						pos.getX() + 0.5, pos.getY() + geom.chimneyHeightBlocks() + 0.8, pos.getZ() + 0.5,
						1, 0.1, 0.1, 0.1, 0.02);
			}
		}

		// Sync seated crucible directly above or embedded inside chamber
		if (seatedCrucible != null) {
			seatedCrucible.stepThermal(chamber.temperatureK, 0.5);
		}
		if (world.getBlockEntity(pos.up()) instanceof ClayCrucibleBlockEntity cbe) {
			cbe.crucible().stepThermal(chamber.temperatureK, 0.5);
		}

		// Thermally vitrify green clay walls into fired clay & fracture weak limestone/stone walls when overheated!
		if (tickCounter % 60 == 0 && chamber.temperatureK > 950.0) {
			checkWallThermalEvolution(sw, pos);
		}

		boolean isLit = chamber.temperatureK > 540.0 && (chamber.charcoalMassKg > 0.01 || chamber.woodMassKg > 0.01);
		BlockState cur = world.getBlockState(pos);
		if (cur.contains(SmeltingBasinBlock.LIT) && cur.get(SmeltingBasinBlock.LIT) != isLit) {
			world.setBlockState(pos, cur.with(SmeltingBasinBlock.LIT, isLit), Block.NOTIFY_ALL);
		}
		sync();
	}

	private void checkWallThermalEvolution(ServerWorld sw, BlockPos pos) {
		for (int dy = 0; dy <= 3; dy++) {
			for (int dz = -1; dz <= 1; dz++) {
				for (int dx = -1; dx <= 1; dx++) {
					BlockPos wp = pos.add(dx, dy, dz);
					BlockState bs = sw.getBlockState(wp);
					if (bs.isOf(MetallurgyBlocks.RAW_CLAY_BLOCK) && chamber.temperatureK >= 1020.0) {
						sw.setBlockState(wp, MetallurgyBlocks.FIRED_CLAY_WALL.getDefaultState(), Block.NOTIFY_ALL);
					} else if (chamber.wallThermalStress > 0.85 && bs.isOf(Blocks.CALCITE) && sw.random.nextFloat() < 0.25f) {
						sw.breakBlock(wp, true);
					}
				}
			}
		}
	}

	public void inspect(PlayerEntity player, World world, BlockPos pos) {
		FurnaceStructure.GeometrySummary g = scanFurnace(world, pos);
		String oreDesc = chamber.activeOre == null ? "none"
				: String.format("%s (%.2f kg)", chamber.activeOre.dominantSpecies().displayName, chamber.activeOre.massKg);
		player.sendMessage(Text.literal(String.format("Furnace %.0f°C | Eff %.0f%% (Chimney %dm, Tuyere %d) | CO %.0f%% | Fuel %.2f kg | Ore: %s | Pool: %.2f kg (%s) | Slag %.2f kg",
						chamber.temperatureK - 273.15,
						g.thermalEfficiency() * 100,
						g.chimneyHeightBlocks(),
						g.tuyereOpenings(),
						chamber.coConcentration * 100,
						chamber.charcoalMassKg + chamber.woodMassKg,
						oreDesc,
						chamber.pooledMetalMassKg,
						chamber.pooledAlloy.describeComposition(),
						chamber.slagMassKg))
				.formatted(chamber.temperatureK > 950.0 ? Formatting.GOLD : Formatting.AQUA), true);
	}

	public void ignite(PlayerEntity player, World world, BlockPos pos) {
		if (chamber.charcoalMassKg <= 0.01 && chamber.woodMassKg <= 0.01) {
			player.sendMessage(Text.literal("Load wood or charcoal into the furnace chamber first!").formatted(Formatting.RED), true);
			return;
		}
		chamber.temperatureK = Math.max(chamber.temperatureK, 640.0);
		world.playSound(null, pos, SoundEvents.ITEM_FIRECHARGE_USE, SoundCategory.BLOCKS, 0.7f, 1.0f);
		player.sendMessage(Text.literal("Ignited smelting bed — pump bellows for high temperatures!").formatted(Formatting.GOLD), true);
		sync();
	}

	public void pumpBellows(PlayerEntity player, World world, BlockPos pos) {
		SmeltingChemistry.pumpBellows(chamber, 0.055);
		world.playSound(null, pos, SoundEvents.ENTITY_BLAZE_SHOOT, SoundCategory.BLOCKS, 0.45f, 0.65f);
		if (world instanceof ServerWorld sw) {
			sw.spawnParticles(ParticleTypes.FLAME, pos.getX() + 0.5, pos.getY() + 0.7, pos.getZ() + 0.5,
					6, 0.15, 0.12, 0.15, 0.05);
		}
		player.sendMessage(Text.literal(String.format("Pumped bellows! Furnace temp: %.0f°C | O2/CO balanced",
				chamber.temperatureK - 273.15)).formatted(Formatting.YELLOW), true);
		sync();
	}

	public void addFuel(PlayerEntity player, ItemStack stack, World world, BlockPos pos) {
		if (stack.isOf(Items.CHARCOAL) || stack.isOf(Items.COAL)) {
			chamber.charcoalMassKg += 0.55;
		} else {
			chamber.woodMassKg += 0.65;
		}
		if (!player.isCreative()) stack.decrement(1);
		world.playSound(null, pos, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 0.45f, 0.9f);
		sync();
	}

	public void addOre(PlayerEntity player, ItemStack stack, World world, BlockPos pos) {
		OreComposition added = OreChunkItem.readOre(stack);
		if (chamber.activeOre == null) {
			chamber.activeOre = added.copy();
		} else {
			double total = chamber.activeOre.massKg + added.massKg;
			for (MineralSpecies ms : MineralSpecies.values()) {
				double f = (chamber.activeOre.fraction(ms) * chamber.activeOre.massKg + added.fraction(ms) * added.massKg) / total;
				chamber.activeOre.setFraction(ms, f);
			}
			chamber.activeOre.crushedDegree = (chamber.activeOre.crushedDegree * chamber.activeOre.massKg
					+ added.crushedDegree * added.massKg) / total;
			chamber.activeOre.sulfurContent = (chamber.activeOre.sulfurContent * chamber.activeOre.massKg
					+ added.sulfurContent * added.massKg) / total;
			chamber.activeOre.massKg = total;
			chamber.activeOre.normalize();
		}
		if (!player.isCreative()) stack.decrement(1);
		world.playSound(null, pos, SoundEvents.BLOCK_GRAVEL_PLACE, SoundCategory.BLOCKS, 0.5f, 1.0f);
		player.sendMessage(Text.literal(String.format("Charged %s ore (%.2f kg active)",
				chamber.activeOre.dominantSpecies().displayName, chamber.activeOre.massKg)).formatted(Formatting.AQUA), true);
		sync();
	}

	public void addFlux(PlayerEntity player, ItemStack stack, World world, BlockPos pos) {
		chamber.fluxCalciteKg += 0.35;
		if (!player.isCreative()) stack.decrement(1);
		world.playSound(null, pos, SoundEvents.BLOCK_SAND_PLACE, SoundCategory.BLOCKS, 0.5f, 1.0f);
		player.sendMessage(Text.literal("Added lime/ash flux to promote fluid slag separation!").formatted(Formatting.GREEN), true);
		sync();
	}

	public void seatCrucible(CrucibleState cs, PlayerEntity player, World world, BlockPos pos, ItemStack stack, Hand hand) {
		if (seatedCrucible != null) {
			ItemStack prev = new ItemStack(MetallurgyItems.CLAY_CRUCIBLE_ITEM);
			ClayCrucibleItem.writeState(prev, seatedCrucible);
			if (!player.getInventory().insertStack(prev)) Block.dropStack(world, pos.up(), prev);
		}
		seatedCrucible = CrucibleState.fromNbt(cs.toNbt());
		if (!player.isCreative()) stack.decrement(1);
		player.sendMessage(Text.literal("Seated crucible into smelting chamber!").formatted(Formatting.GOLD), true);
		sync();
	}

	public void heatMetalObject(PlayerEntity player, ItemStack held, World world, BlockPos pos) {
		DynamicMetalState ms = DynamicMetalItem.readState(held);
		if (chamber.temperatureK <= ms.temperatureK + 15.0) {
			player.sendMessage(Text.literal("Furnace isn't hotter than metal yet!").formatted(Formatting.GRAY), true);
			return;
		}
		// Heat transfer soak + carburization if deep hot reducing charcoal bed!
		ms.temperatureK = Math.min(ms.alloy.solidusTempK() - 18.0,
				ms.temperatureK + (chamber.temperatureK - ms.temperatureK) * 0.72);
		boolean reducing = chamber.coConcentration >= 0.45 && chamber.charcoalMassKg > 0.1;
		ms.grid.micro.stepThermalEvolution(ms.alloy, ms.temperatureK, 12.0, reducing);
		// Diffusion also nudges surface steel carbon slightly upward when held in glowing charcoal!
		if (reducing && ms.alloy.fe >= ms.alloy.cu && ms.temperatureK >= 1150.0) {
			ms.alloy.c = Math.min(0.018, ms.alloy.c + 0.0018);
			ms.alloy.normalize();
		}
		DynamicMetalItem.writeState(held, ms);
		world.playSound(null, pos, SoundEvents.BLOCK_FIRE_AMBIENT, SoundCategory.BLOCKS, 0.6f, 1.2f);
		player.sendMessage(Text.literal(String.format("Heated %s to %.0f°C (Dislocation %.0f%%, C %.2f%%)",
						ms.alloy.describeComposition(), ms.temperatureK - 273.15,
						ms.grid.micro.averageDislocation() * 100, ms.alloy.c * 100))
				.formatted(Formatting.GOLD), true);
	}

	public void extractProduct(PlayerEntity player, World world, BlockPos pos) {
		if (seatedCrucible != null) {
			if (seatedCrucible.temperatureK > 450.0 && !DynamicMetalItem.hasHeatProtection(player)) {
				player.damage(world.getDamageSources().hotFloor(), 1.5f);
				player.sendMessage(Text.literal("Crucible is glowing hot — hold Iron Tongs or Leather!").formatted(Formatting.RED), true);
				return;
			}
			ItemStack item = new ItemStack(MetallurgyItems.CLAY_CRUCIBLE_ITEM);
			ClayCrucibleItem.writeState(item, seatedCrucible);
			seatedCrucible = null;
			if (!player.getInventory().insertStack(item)) Block.dropStack(world, pos.up(), item);
			sync();
			return;
		}
		if (chamber.pooledMetalMassKg >= 0.04) {
			double takeKg = chamber.pooledMetalMassKg;
			AlloyComposition alloy = chamber.pooledAlloy.copy();
			chamber.pooledMetalMassKg = 0.0;
			chamber.pooledAlloy = AlloyComposition.pureCopper();

			double scale = Math.max(0.55, Math.min(1.0, Math.cbrt(takeKg / 0.85)));
			MetalVolumeGrid grid = MetalVolumeGrid.createNativeNugget(new Random(world.random.nextLong()).nextLong(), scale);
			for (int i = 0; i < MicrostructureState.TOTAL_CELLS; i++) {
				MicrostructureState.MicroCell c = grid.micro.cell(i);
				c.slagInclusion = alloy.slag;
				c.porosity = alloy.fe > alloy.cu ? 0.16 : 0.06;
				c.grainSizeUm = 120.0;
				c.localCarbon = alloy.c;
			}
			DynamicMetalState product = new DynamicMetalState(alloy, 0.95, grid, chamber.temperatureK);
			ItemStack out = DynamicMetalItem.create(product);
			if (!player.getInventory().insertStack(out)) Block.dropStack(world, pos.up(), out);
			player.sendMessage(Text.literal(String.format("Extracted %.2f kg %s bloom/prill!",
					takeKg, alloy.describeComposition())).formatted(Formatting.GREEN), true);
		}
		if (chamber.slagMassKg >= 0.15) {
			int clumps = Math.min(4, (int) Math.floor(chamber.slagMassKg / 0.25));
			if (clumps > 0) {
				chamber.slagMassKg = Math.max(0.0, chamber.slagMassKg - clumps * 0.25);
				Block.dropStack(world, pos.up(), new ItemStack(MetallurgyItems.SLAG_CLUMP, clumps));
			}
		}
		sync();
	}

	private void sync() {
		markDirty();
		if (world != null && !world.isClient) {
			world.updateListeners(pos, getCachedState(), getCachedState(), Block.NOTIFY_ALL);
		}
	}

	@Override
	protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		super.writeNbt(nbt, registryLookup);
		nbt.putDouble("TempK", chamber.temperatureK);
		nbt.putDouble("CharKg", chamber.charcoalMassKg);
		nbt.putDouble("WoodKg", chamber.woodMassKg);
		nbt.putDouble("FluxKg", chamber.fluxCalciteKg);
		nbt.putDouble("CO", chamber.coConcentration);
		nbt.putDouble("O2", chamber.o2Concentration);
		nbt.putDouble("PoolKg", chamber.pooledMetalMassKg);
		nbt.put("PoolAlloy", chamber.pooledAlloy.toNbt());
		nbt.putDouble("SlagKg", chamber.slagMassKg);
		nbt.putDouble("TrapKg", chamber.trappedMetalInSlagKg);
		nbt.putDouble("WallStress", chamber.wallThermalStress);
		if (chamber.activeOre != null) nbt.put("Ore", chamber.activeOre.toNbt());
		if (seatedCrucible != null) nbt.put("Crucible", seatedCrucible.toNbt());
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		super.readNbt(nbt, registryLookup);
		chamber.temperatureK = nbt.contains("TempK") ? nbt.getDouble("TempK") : ThermalConstants.AMBIENT_DEFAULT_K;
		chamber.charcoalMassKg = nbt.getDouble("CharKg");
		chamber.woodMassKg = nbt.getDouble("WoodKg");
		chamber.fluxCalciteKg = nbt.getDouble("FluxKg");
		chamber.coConcentration = nbt.getDouble("CO");
		chamber.o2Concentration = nbt.contains("O2") ? nbt.getDouble("O2") : ThermalConstants.AMBIENT_O2;
		chamber.pooledMetalMassKg = nbt.getDouble("PoolKg");
		if (nbt.contains("PoolAlloy")) chamber.pooledAlloy = AlloyComposition.fromNbt(nbt.getCompound("PoolAlloy"));
		chamber.slagMassKg = nbt.getDouble("SlagKg");
		chamber.trappedMetalInSlagKg = nbt.getDouble("TrapKg");
		chamber.wallThermalStress = nbt.getDouble("WallStress");
		chamber.activeOre = nbt.contains("Ore") ? OreComposition.fromNbt(nbt.getCompound("Ore")) : null;
		seatedCrucible = nbt.contains("Crucible") ? CrucibleState.fromNbt(nbt.getCompound("Crucible")) : null;
	}

	@Nullable
	@Override
	public Packet<ClientPlayPacketListener> toUpdatePacket() {
		return BlockEntityUpdateS2CPacket.create(this);
	}

	@Override
	public NbtCompound toInitialChunkDataNbt(RegistryWrapper.WrapperLookup registryLookup) {
		return createNbt(registryLookup);
	}
}
