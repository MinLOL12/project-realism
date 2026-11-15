package com.projectrealism.material;

import com.projectrealism.thermo.AirGrid;
import com.projectrealism.thermo.ThermalConstants;
import com.projectrealism.thermo.ThermalWorldState;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Environmental tension-drying simulation for hides and gourds coupled directly to
 * {@link ThermalWorldState}, {@link AirGrid}, nearby hearths, and rain exposure.
 */
public class DryingRackBlockEntity extends BlockEntity {
	@Nullable
	private HideSurface hide = null;
	@Nullable
	private MaterialAging.GourdState gourd = null;
	private int tickCount = 0;

	public DryingRackBlockEntity(BlockPos pos, BlockState state) {
		super(MaterialBlocks.DRYING_RACK_ENTITY, pos, state);
	}

	public boolean hasItem() {
		return hide != null || gourd != null;
	}

	public boolean hasHide() {
		return hide != null;
	}

	@Nullable
	public HideSurface hide() {
		return hide;
	}

	@Nullable
	public MaterialAging.GourdState gourd() {
		return gourd;
	}

	public void mountItem(ItemStack stack, PlayerEntity player) {
		if (hasItem()) return;
		if (stack.isOf(MaterialItems.RAW_HIDE)) {
			hide = RawHideItem.readSurface(stack);
			if (!player.isCreative()) stack.decrement(1);
		} else if (stack.isOf(MaterialItems.RAW_GOURD)) {
			gourd = MaterialItems.RawGourdItem.readGourdState(stack);
			if (!player.isCreative()) stack.decrement(1);
		}
		sync();
	}

	public void takeItem(PlayerEntity player, World world, BlockPos pos) {
		ItemStack out = ItemStack.EMPTY;
		if (hide != null) {
			out = RawHideItem.create(hide);
			hide = null;
		} else if (gourd != null) {
			out = new ItemStack(MaterialItems.RAW_GOURD);
			MaterialItems.RawGourdItem.writeGourdState(out, gourd);
			gourd = null;
		}
		if (!out.isEmpty() && !player.getInventory().insertStack(out)) {
			Block.dropStack(world, pos, out);
		}
		sync();
	}

	public void adjustCornerTension(int cornerIdx, double delta, PlayerEntity player, World world, BlockPos pos) {
		if (hide == null) return;
		hide.adjustCornerTension(cornerIdx, delta);
		world.playSound(null, pos, SoundEvents.ENTITY_LEASH_KNOT_PLACE, SoundCategory.BLOCKS, 0.55f,
				(float) (0.9 + hide.cornerTension[cornerIdx] * 0.45));
		player.sendMessage(Text.translatable("project_realism.drying.tension",
				cornerIdx + 1, (int) Math.round(hide.cornerTension[cornerIdx] * 100.0))
				.formatted(Formatting.AQUA), true);
		sync();
	}

	public static void tick(World world, BlockPos pos, BlockState state, DryingRackBlockEntity be) {
		if (!(world instanceof ServerWorld sw) || !be.hasItem()) return;
		be.tickCount++;
		if (be.tickCount % 20 != 0) return; // 1 Hz environmental material physics

		ThermalWorldState thermo = ThermalWorldState.get(sw);
		double ambK = thermo.ambientTempAt(sw, pos);
		double rh = thermo.relativeHumidityAt(sw, pos);
		AirGrid.AirCell air = thermo.airGrid().getReadOnly(AirGrid.CellPos.fromBlock(pos), ambK);
		double wind = air.speedMS() + (sw.isSkyVisible(pos.up()) ? 0.5 : 0.12);
		boolean rain = sw.isRaining() && sw.isSkyVisible(pos.up());

		// Sample nearby radiant heat and lateral directional bias
		double radW = 0.0;
		double radBiasX = 0.0;
		for (int dx = -3; dx <= 3; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				for (int dz = -3; dz <= 3; dz++) {
					if (dx == 0 && dy == 0 && dz == 0) continue;
					BlockPos src = pos.add(dx, dy, dz);
					double t = thermo.blockSurfaceTempK(sw, src);
					if (t > 400.0 && thermo.hasLineOfSight(sw, src, pos)) {
						double d2 = dx * dx + dy * dy + dz * dz;
						double w = (ThermalConstants.STEFAN_BOLTZMANN * 0.9 * (Math.pow(t, 4) - Math.pow(ambK, 4)))
								/ (4.0 * Math.PI * Math.max(0.7, d2));
						radW += w;
						radBiasX += Math.signum(dx) * Math.min(1.0, w / 250.0);
					}
				}
			}
		}

		double dt = 1.0;
		if (be.hide != null) {
			be.hide.stepDrying(Math.max(ambK, air.temperatureK), rh, wind, Math.min(1200.0, radW),
					Math.max(-1.0, Math.min(1.0, radBiasX)), rain, dt);
			if (be.hide.averageMoisture() > 0.55 && sw.random.nextFloat() < 0.25f) {
				sw.spawnParticles(ParticleTypes.DRIPPING_WATER,
						pos.getX() + 0.5 + (sw.random.nextDouble() - 0.5) * 0.4,
						pos.getY() + 0.35,
						pos.getZ() + 0.5 + (sw.random.nextDouble() - 0.5) * 0.4,
						1, 0, 0, 0, 0);
			}
		} else if (be.gourd != null) {
			double effK = Math.max(ambK, air.temperatureK) + Math.min(1200.0, radW) / 48.0;
			be.gourd.stepDrying(effK, rh, wind, rain, dt);
		}
		be.sync();
	}

	public Text statusText() {
		if (hide != null) {
			return Text.translatable("project_realism.drying.hide_status",
					(int) Math.round(hide.averageMoisture() * 100.0),
					(int) Math.round(hide.contractionFrac * 100.0),
					(int) Math.round(hide.unevenDeformation * 100.0))
					.formatted(hide.unevenDeformation > 0.25 ? Formatting.YELLOW : Formatting.AQUA);
		}
		if (gourd != null) {
			return Text.translatable("project_realism.drying.gourd_status",
					(int) Math.round(gourd.moisture * 100.0),
					(int) Math.round(gourd.shellHardness * 100.0),
					(int) Math.round(gourd.crackSeverity * 100.0))
					.formatted(gourd.isCuredForCarving() ? Formatting.GREEN : Formatting.GOLD);
		}
		return Text.empty();
	}

	public void dropContents(World world, BlockPos pos) {
		if (hide != null) {
			Block.dropStack(world, pos, RawHideItem.create(hide));
			hide = null;
		}
		if (gourd != null) {
			ItemStack g = new ItemStack(MaterialItems.RAW_GOURD);
			MaterialItems.RawGourdItem.writeGourdState(g, gourd);
			Block.dropStack(world, pos, g);
			gourd = null;
		}
	}

	private void sync() {
		markDirty();
		if (world != null && !world.isClient) {
			BlockState st = getCachedState();
			world.updateListeners(pos, st, st, Block.NOTIFY_LISTENERS);
		}
	}

	@Override
	protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		super.writeNbt(nbt, registryLookup);
		if (hide != null) nbt.put("Hide", hide.toNbt());
		if (gourd != null) {
			NbtCompound g = new NbtCompound();
			g.putDouble("Moist", gourd.moisture);
			g.putDouble("Hard", gourd.shellHardness);
			g.putDouble("Crack", gourd.crackSeverity);
			g.putDouble("Seal", gourd.sealLevel);
			nbt.put("Gourd", g);
		}
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		super.readNbt(nbt, registryLookup);
		hide = nbt.contains("Hide") ? HideSurface.fromNbt(nbt.getCompound("Hide")) : null;
		if (nbt.contains("Gourd")) {
			NbtCompound g = nbt.getCompound("Gourd");
			gourd = new MaterialAging.GourdState();
			gourd.moisture = g.getDouble("Moist");
			gourd.shellHardness = g.getDouble("Hard");
			gourd.crackSeverity = g.getDouble("Crack");
			gourd.sealLevel = g.getDouble("Seal");
		} else {
			gourd = null;
		}
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
