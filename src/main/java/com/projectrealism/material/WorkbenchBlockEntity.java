package com.projectrealism.material;

import com.projectrealism.primitive.PrimitiveItems;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.listener.ClientPlayPacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * 4-quadrant persistent workbench state keeping tools, materials, and vessels placed visibly in-world.
 */
public class WorkbenchBlockEntity extends BlockEntity {
	public static final int SLOTS = 4;
	private final ItemStack[] items = new ItemStack[]{
			ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY
	};
	private int gourdCarveProgress = 0;

	public WorkbenchBlockEntity(BlockPos pos, BlockState state) {
		super(MaterialBlocks.WOODEN_WORKBENCH_ENTITY, pos, state);
	}

	public ItemStack getSlot(int idx) {
		return (idx >= 0 && idx < SLOTS) ? items[idx] : ItemStack.EMPTY;
	}

	public void placeItem(int slot, ItemStack held, PlayerEntity player, World world, BlockPos pos) {
		if (slot < 0 || slot >= SLOTS || !items[slot].isEmpty() || held.isEmpty()) return;
		items[slot] = held.copyWithCount(1);
		if (!player.isCreative()) held.decrement(1);
		world.playSound(null, pos, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 0.45f, 1.15f);
		sync();
	}

	public void takeItem(int slot, PlayerEntity player, World world, BlockPos pos) {
		if (slot < 0 || slot >= SLOTS || items[slot].isEmpty()) return;
		ItemStack out = items[slot];
		items[slot] = ItemStack.EMPTY;
		if (!player.getInventory().insertStack(out)) {
			Block.dropStack(world, pos.up(), out);
		}
		world.playSound(null, pos, SoundEvents.BLOCK_WOOD_HIT, SoundCategory.BLOCKS, 0.35f, 1.3f);
		sync();
	}

	public void carveGourd(int slot, PlayerEntity player, ItemStack tool, World world, BlockPos pos) {
		ItemStack g = items[slot];
		if (!g.isOf(MaterialItems.RAW_GOURD)) return;
		MaterialAging.GourdState gs = MaterialItems.RawGourdItem.readGourdState(g);
		if (!gs.isCuredForCarving()) {
			player.sendMessage(Text.translatable("project_realism.gourd.too_wet",
					(int) Math.round(gs.moisture * 100.0)).formatted(Formatting.YELLOW), true);
			return;
		}
		if (tool.isOf(MaterialItems.BONE_AWL) && gourdCarveProgress == 0) {
			gourdCarveProgress = 1;
			if (!player.isCreative()) tool.damage(1, player, EquipmentSlot.MAINHAND);
			world.playSound(null, pos, SoundEvents.BLOCK_WOOD_HIT, SoundCategory.BLOCKS, 0.6f, 1.4f);
			player.sendMessage(Text.translatable("project_realism.gourd.bored").formatted(Formatting.AQUA), true);
			return;
		}
		if (tool.isOf(PrimitiveItems.STONE_KNIFE) && gourdCarveProgress >= 1) {
			ItemStack flask = new ItemStack(MaterialItems.GOURD_FLASK);
			GourdFlaskItem.writeGourdState(flask, gs);
			items[slot] = flask;
			gourdCarveProgress = 0;
			if (!player.isCreative()) tool.damage(1, player, EquipmentSlot.MAINHAND);
			world.playSound(null, pos, SoundEvents.BLOCK_BAMBOO_BREAK, SoundCategory.BLOCKS, 0.65f, 1.1f);
			player.sendMessage(Text.translatable("project_realism.gourd.carved").formatted(Formatting.GREEN), true);
			sync();
			return;
		}
		player.sendMessage(Text.translatable("project_realism.gourd.carve_hint").formatted(Formatting.GRAY), true);
	}

	public void sealGourdCrack(int slot, PlayerEntity player, ItemStack sealant, World world, BlockPos pos) {
		ItemStack f = items[slot];
		if (!f.isOf(MaterialItems.GOURD_FLASK)) return;
		MaterialAging.GourdState gs = GourdFlaskItem.readGourdState(f);
		gs.sealLevel = Math.min(1.0, gs.sealLevel + 0.6);
		GourdFlaskItem.writeGourdState(f, gs);
		if (!player.isCreative()) sealant.decrement(1);
		world.playSound(null, pos, SoundEvents.ITEM_HONEYCOMB_WAX_ON, SoundCategory.BLOCKS, 0.6f, 1.0f);
		player.sendMessage(Text.translatable("project_realism.gourd.sealed").formatted(Formatting.GREEN), true);
		sync();
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
		nbt.putInt("Carve", gourdCarveProgress);
		for (int i = 0; i < SLOTS; i++) {
			if (!items[i].isEmpty()) nbt.put("S" + i, items[i].encode(registryLookup));
		}
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		super.readNbt(nbt, registryLookup);
		gourdCarveProgress = nbt.getInt("Carve");
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
