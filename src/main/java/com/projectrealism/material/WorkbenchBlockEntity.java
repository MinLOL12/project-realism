package com.projectrealism.material;

import com.projectrealism.anatomy.AnatomyItems;
import com.projectrealism.anatomy.BoneData;
import com.projectrealism.anatomy.RawBoneItem;
import com.projectrealism.primitive.PrimitiveItems;
import com.projectrealism.primitive.ToolCondition;
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
		if ((tool.isOf(MaterialItems.BONE_AWL)
				|| (tool.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE)
				&& com.projectrealism.knapping.DynamicStoneItem.readState(tool).metrics().pointScore() >= 0.35))
				&& gourdCarveProgress == 0) {
			gourdCarveProgress = 1;
			if (!player.isCreative() && tool.isDamageable()) tool.damage(1, player, EquipmentSlot.MAINHAND);
			world.playSound(null, pos, SoundEvents.BLOCK_WOOD_HIT, SoundCategory.BLOCKS, 0.6f, 1.4f);
			player.sendMessage(Text.translatable("project_realism.gourd.bored").formatted(Formatting.AQUA), true);
			return;
		}
		if ((tool.isOf(PrimitiveItems.STONE_KNIFE) || tool.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE))
				&& gourdCarveProgress >= 1) {
			ItemStack flask = new ItemStack(MaterialItems.GOURD_FLASK);
			GourdFlaskItem.writeGourdState(flask, gs);
			items[slot] = flask;
			gourdCarveProgress = 0;
			if (!player.isCreative() && tool.isDamageable()) tool.damage(1, player, EquipmentSlot.MAINHAND);
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

	public void carveBone(int slot, PlayerEntity player, ItemStack knife, World world, BlockPos pos) {
		ItemStack stack = items[slot];
		if (!stack.isOf(AnatomyItems.RAW_BONE)) return;
		BoneData b = RawBoneItem.readBone(stack);

		// Cycle compatible tool target when sneaking (Needle -> Awl -> Blade -> Chisel)
		BoneData.ToolTarget target = b.target == BoneData.ToolTarget.NONE ? BoneData.ToolTarget.AWL : b.target;
		if (player.isSneaking() || !b.canShape(target)) {
			BoneData.ToolTarget[] opts = new BoneData.ToolTarget[]{
					BoneData.ToolTarget.NEEDLE, BoneData.ToolTarget.AWL,
					BoneData.ToolTarget.BLADE, BoneData.ToolTarget.CHISEL
			};
			int start = 0;
			for (int i = 0; i < opts.length; i++) {
				if (opts[i] == target) start = i;
			}
			for (int off = 1; off <= opts.length; off++) {
				BoneData.ToolTarget cand = opts[(start + off) % opts.length];
				if (b.canShape(cand)) {
					target = cand;
					break;
				}
			}
		}

		double sharp;
		if (knife.isOf(PrimitiveItems.STONE_KNIFE)) {
			sharp = ToolCondition.getSharpness(knife) / 100.0;
		} else if (knife.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE)) {
			var s = com.projectrealism.knapping.DynamicStoneItem.readState(knife);
			sharp = Math.max(0.20, Math.min(1.0, com.projectrealism.knapping.StoneInteractionPhysics.evaluate(
					s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.BONE_CARVE, 95.0)
					.effectiveness()));
			if (!player.isCreative()) {
				com.projectrealism.knapping.StoneInteractionPhysics.applyContactWear(
						s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.BONE_CARVE,
						95.0, new java.util.Random(world.random.nextLong()));
				com.projectrealism.knapping.DynamicStoneItem.writeState(knife, s);
			}
		} else {
			sharp = AnatomyItems.BoneToolItem.getWorkQualityPct(knife) / 100.0;
		}
		b.carveWithKnife(sharp, target);
		RawBoneItem.writeBone(stack, b);
		world.playSound(null, pos, SoundEvents.BLOCK_BONE_BLOCK_STEP, SoundCategory.BLOCKS, 0.65f, 1.2f);
		if (b.isReadyToFinish()) {
			finishBoneTool(slot, b, player, world, pos);
		} else {
			player.sendMessage(Text.translatable("project_realism.bone.carved",
					b.target.id, (int) Math.round(b.cutProgress * 100.0),
					(int) Math.round(b.abrasionSmooth * 100.0)).formatted(Formatting.AQUA), true);
			sync();
		}
	}

	public void abradeBone(int slot, PlayerEntity player, ItemStack stone, World world, BlockPos pos) {
		ItemStack stack = items[slot];
		if (!stack.isOf(AnatomyItems.RAW_BONE)) return;
		BoneData b = RawBoneItem.readBone(stack);
		if (b.target == BoneData.ToolTarget.NONE) {
			b.target = b.canShape(BoneData.ToolTarget.AWL) ? BoneData.ToolTarget.AWL : BoneData.ToolTarget.NEEDLE;
		}
		var stoneMat = com.projectrealism.knapping.DynamicKnappingItems.materialOfItem(stone);
		double grit = (stoneMat != null && stoneMat.mohsHardness >= 6.0) || stone.isOf(PrimitiveItems.SHARP_STONE) ? 0.90 : 0.72;
		b.abradeWithStone(grit);
		RawBoneItem.writeBone(stack, b);
		world.playSound(null, pos, SoundEvents.BLOCK_GRINDSTONE_USE, SoundCategory.BLOCKS, 0.55f, 1.25f);
		if (b.isReadyToFinish()) {
			finishBoneTool(slot, b, player, world, pos);
		} else {
			player.sendMessage(Text.translatable("project_realism.bone.abraded",
					b.target.id, (int) Math.round(b.abrasionSmooth * 100.0),
					b.resultingToolIntegrityPct()).formatted(Formatting.GOLD), true);
			sync();
		}
	}

	private void finishBoneTool(int slot, BoneData b, PlayerEntity player, World world, BlockPos pos) {
		if (!b.canShape(b.target)) {
			player.sendMessage(Text.translatable("project_realism.bone.too_small",
					b.size.id, b.target.id).formatted(Formatting.RED), true);
			sync();
			return;
		}
		ItemStack out = switch (b.target) {
			case NEEDLE -> new ItemStack(MaterialItems.BONE_NEEDLE);
			case AWL -> new ItemStack(MaterialItems.BONE_AWL);
			case BLADE -> new ItemStack(AnatomyItems.BONE_BLADE);
			default -> new ItemStack(AnatomyItems.BONE_CHISEL);
		};
		int qualPct = b.resultingToolIntegrityPct();
		if (out.isOf(AnatomyItems.BONE_BLADE) || out.isOf(AnatomyItems.BONE_CHISEL)) {
			AnatomyItems.BoneToolItem.setWorkQualityPct(out, qualPct);
		}
		// Poor density or heat-brittle workmanship produces fragile tools with less remaining durability!
		if (out.isDamageable() && qualPct < 90) {
			int dmg = (int) Math.round(out.getMaxDamage() * (1.0 - qualPct / 100.0) * 0.75);
			out.setDamage(Math.min(out.getMaxDamage() - 4, Math.max(0, dmg)));
		}
		items[slot] = out;
		world.playSound(null, pos, SoundEvents.BLOCK_BONE_BLOCK_BREAK, SoundCategory.BLOCKS, 0.5f, 1.4f);
		player.sendMessage(Text.translatable("project_realism.bone.finished",
				b.target.id, qualPct).formatted(qualPct >= 70 ? Formatting.GREEN : Formatting.YELLOW), true);
		sync();
	}

	public void carveHornFlask(int slot, PlayerEntity player, ItemStack tool, World world, BlockPos pos) {
		if (!items[slot].isOf(AnatomyItems.ANIMAL_HORN)) return;
		if ((tool.isOf(MaterialItems.BONE_AWL)
				|| (tool.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE)
				&& com.projectrealism.knapping.DynamicStoneItem.readState(tool).metrics().pointScore() >= 0.35))
				&& gourdCarveProgress == 0) {
			gourdCarveProgress = 1;
			if (!player.isCreative() && tool.isDamageable()) tool.damage(1, player, EquipmentSlot.MAINHAND);
			world.playSound(null, pos, SoundEvents.BLOCK_BONE_BLOCK_STEP, SoundCategory.BLOCKS, 0.6f, 1.3f);
			player.sendMessage(Text.translatable("project_realism.horn.bored").formatted(Formatting.AQUA), true);
			return;
		}
		if ((tool.isOf(PrimitiveItems.STONE_KNIFE) || tool.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE))
				&& gourdCarveProgress >= 1) {
			items[slot] = new ItemStack(AnatomyItems.HORN_FLASK);
			gourdCarveProgress = 0;
			if (!player.isCreative() && tool.isDamageable()) tool.damage(1, player, EquipmentSlot.MAINHAND);
			world.playSound(null, pos, SoundEvents.BLOCK_BONE_BLOCK_BREAK, SoundCategory.BLOCKS, 0.6f, 1.15f);
			player.sendMessage(Text.translatable("project_realism.horn.carved").formatted(Formatting.GREEN), true);
			sync();
		}
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
