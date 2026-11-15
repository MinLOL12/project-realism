package com.projectrealism.material;

import com.projectrealism.hydration.HydrationItems;
import com.projectrealism.hydration.WaterskinItem;
import com.projectrealism.thermo.AirGrid;
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
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * 4-shelf ventilated storage rack protecting stored hides, leather, fibers, and containers from
 * ground dampness while allowing slow equilibrium conditioning in surrounding air.
 */
public class StorageRackBlockEntity extends BlockEntity {
	public static final int SLOTS = 4;
	private final ItemStack[] items = new ItemStack[]{
			ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY
	};
	private int tickCount = 0;

	public StorageRackBlockEntity(BlockPos pos, BlockState state) {
		super(MaterialBlocks.STORAGE_RACK_ENTITY, pos, state);
	}

	public ItemStack getSlot(int idx) {
		return (idx >= 0 && idx < SLOTS) ? items[idx] : ItemStack.EMPTY;
	}

	public void placeItem(int slot, ItemStack held, PlayerEntity player, World world, BlockPos pos) {
		if (slot < 0 || slot >= SLOTS || !items[slot].isEmpty() || held.isEmpty()) return;
		items[slot] = held.copyWithCount(1);
		if (!player.isCreative()) held.decrement(1);
		world.playSound(null, pos, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 0.45f, 1.05f);
		sync();
	}

	public void takeItem(int slot, PlayerEntity player, World world, BlockPos pos) {
		if (slot < 0 || slot >= SLOTS || items[slot].isEmpty()) return;
		ItemStack out = items[slot];
		items[slot] = ItemStack.EMPTY;
		if (!player.getInventory().insertStack(out)) {
			Block.dropStack(world, pos.up(), out);
		}
		world.playSound(null, pos, SoundEvents.BLOCK_WOOD_HIT, SoundCategory.BLOCKS, 0.35f, 1.2f);
		sync();
	}

	public static void tick(World world, BlockPos pos, BlockState state, StorageRackBlockEntity be) {
		if (!(world instanceof ServerWorld sw)) return;
		be.tickCount++;
		if (be.tickCount % 40 != 0) return;

		ThermalWorldState thermo = ThermalWorldState.get(sw);
		double ambK = thermo.ambientTempAt(sw, pos);
		double rh = thermo.relativeHumidityAt(sw, pos);
		AirGrid.AirCell air = thermo.airGrid().getReadOnly(AirGrid.CellPos.fromBlock(pos), ambK);
		boolean rain = sw.isRaining() && sw.isSkyVisible(pos.up());
		boolean changed = false;

		for (int i = 0; i < SLOTS; i++) {
			ItemStack st = be.items[i];
			if (st.isEmpty()) continue;
			if (st.isOf(HydrationItems.WATERSKIN)) {
				WaterskinConstruction c = WaterskinConstruction.read(st);
				MaterialAging.stepLeatherContainer(c, WaterskinItem.getVolumeMl(st) > 0,
						Math.max(ambK, air.temperatureK), rh * 0.85, rain, 2.0);
				WaterskinConstruction.write(st, c);
				changed = true;
			} else if (st.isOf(MaterialItems.RAW_HIDE)) {
				HideSurface hs = RawHideItem.readSurface(st);
				hs.stepDrying(Math.max(ambK, air.temperatureK), rh * 0.82, air.speedMS() + 0.25,
						0.0, 0.0, rain, 2.0);
				RawHideItem.writeSurface(st, hs);
				changed = true;
			}
		}
		if (changed) be.sync();
	}

	public void dropContents(World world, BlockPos pos) {
		for (int i = 0; i < SLOTS; i++) {
			if (!items[i].isEmpty()) {
				Block.dropStack(world, pos, items[i]);
				items[i] = ItemStack.EMPTY;
			}
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
		for (int i = 0; i < SLOTS; i++) {
			if (!items[i].isEmpty()) nbt.put("S" + i, items[i].encode(registryLookup));
		}
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		super.readNbt(nbt, registryLookup);
		for (int i = 0; i < SLOTS; i++) {
			items[i] = nbt.contains("S" + i)
					? ItemStack.fromNbtOrEmpty(registryLookup, nbt.getCompound("S" + i))
					: ItemStack.EMPTY;
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
