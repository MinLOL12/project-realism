package com.projectrealism.metallurgy;

import com.projectrealism.thermo.ThermalWorldState;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
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
 * BlockEntity holding a live {@link CrucibleState}.
 */
public class ClayCrucibleBlockEntity extends BlockEntity {
	private CrucibleState crucible = new CrucibleState();
	private boolean pickedUp = false;
	private int ticks = 0;

	public ClayCrucibleBlockEntity(BlockPos pos, BlockState state) {
		super(MetallurgyBlocks.CLAY_CRUCIBLE_ENTITY, pos, state);
	}

	public CrucibleState crucible() {
		return crucible;
	}

	public void setCrucible(CrucibleState s) {
		this.crucible = s != null ? s : new CrucibleState();
		sync();
	}

	public void serverTick(World world, BlockPos pos) {
		if (++ticks % 10 != 0) return;
		if (!(world instanceof ServerWorld sw)) return;
		double envK = ThermalWorldState.get(sw).ambientTempAt(sw, pos);
		if (world.getBlockEntity(pos.down()) instanceof SmeltingBasinBlockEntity basin) {
			envK = Math.max(envK, basin.chamber().temperatureK);
		}
		crucible.stepThermal(envK, 0.5);
		sync();
	}

	public void inspect(PlayerEntity player) {
		if (crucible.isEmpty()) {
			player.sendMessage(Text.literal(String.format("Crucible Empty | %.0f°C | Carbon %.2f kg",
					crucible.temperatureK - 273.15, crucible.enclosedCarbonKg)).formatted(Formatting.GRAY), true);
		} else {
			player.sendMessage(Text.literal(String.format("Crucible [%s] %.2f kg | Slag %.2f kg | %.0f°C (Liquidus %.0f°C) | Fluidity %.2f",
							crucible.alloy.describeComposition(),
							crucible.metalMassKg,
							crucible.slagMassKg,
							crucible.temperatureK - 273.15,
							crucible.alloy.liquidusTempK() - 273.15,
							crucible.fluidity()))
					.formatted(crucible.isMolten() ? Formatting.GOLD : Formatting.AQUA), true);
		}
	}

	public void addMetal(PlayerEntity player, ItemStack stack, World world, BlockPos pos) {
		DynamicMetalState ms = DynamicMetalItem.readState(stack);
		double kg = ms.metrics().massKg();
		crucible.addMetalCharge(ms.alloy, kg, ms.temperatureK);
		if (!player.isCreative()) stack.decrement(1);
		world.playSound(null, pos, SoundEvents.BLOCK_METAL_PLACE, SoundCategory.BLOCKS, 0.6f, 1.15f);
		player.sendMessage(Text.literal(String.format("Loaded %.2f kg %s into crucible (total %.2f kg)",
				kg, ms.alloy.describeComposition(), crucible.metalMassKg)).formatted(Formatting.GREEN), true);
		sync();
	}

	public void interactWithStickOrCarbon(PlayerEntity player, ItemStack held, World world, BlockPos pos) {
		if (held.isOf(Items.STICK)) {
			double skimmed = crucible.skimSlag();
			if (skimmed > 0.005) {
				Block.dropStack(world, pos.up(), new ItemStack(MetallurgyItems.SLAG_CLUMP, 1));
				world.playSound(null, pos, SoundEvents.BLOCK_GRAVEL_STEP, SoundCategory.BLOCKS, 0.6f, 1.0f);
				player.sendMessage(Text.literal(String.format("Skimmed %.2f kg floating slag!", skimmed)).formatted(Formatting.YELLOW), true);
				sync();
			} else {
				player.sendMessage(Text.literal("No floating slag to skim.").formatted(Formatting.GRAY), true);
			}
		} else if (held.isOf(Items.CHARCOAL)) {
			crucible.enclosedCarbonKg += 0.12;
			if (!player.isCreative()) held.decrement(1);
			player.sendMessage(Text.literal("Added sealed charcoal carbon charge for crucible steel carburization.")
					.formatted(Formatting.GOLD), true);
			sync();
		}
	}

	public void pickUp(PlayerEntity player, World world, BlockPos pos) {
		if (crucible.temperatureK > 450.0 && !DynamicMetalItem.hasHeatProtection(player)) {
			player.damage(world.getDamageSources().hotFloor(), 1.5f);
			player.sendMessage(Text.literal("Crucible is glowing hot! Hold Iron Tongs or Leather in offhand.")
					.formatted(Formatting.RED), true);
			return;
		}
		pickedUp = true;
		ItemStack item = new ItemStack(MetallurgyItems.CLAY_CRUCIBLE_ITEM);
		ClayCrucibleItem.writeState(item, crucible);
		if (!player.getInventory().insertStack(item)) {
			Block.dropStack(world, pos, item);
		}
		world.removeBlock(pos, false);
		world.playSound(null, pos, SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.55f, 1.0f);
	}

	public void dropOnBreak(World world, BlockPos pos) {
		if (!pickedUp) {
			ItemStack item = new ItemStack(MetallurgyItems.CLAY_CRUCIBLE_ITEM);
			ClayCrucibleItem.writeState(item, crucible);
			Block.dropStack(world, pos, item);
		}
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
		nbt.put("Crucible", crucible.toNbt());
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		super.readNbt(nbt, registryLookup);
		if (nbt.contains("Crucible")) crucible = CrucibleState.fromNbt(nbt.getCompound("Crucible"));
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
