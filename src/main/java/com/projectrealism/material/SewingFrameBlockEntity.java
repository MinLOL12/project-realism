package com.projectrealism.material;

import com.projectrealism.hydration.HydrationItems;
import com.projectrealism.hydration.WaterQuality;
import com.projectrealism.hydration.WaterskinItem;
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
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * Clamp state holding leather panels or a waterskin in-progress through physical alignment,
 * awl hole punching, cordage needle stitching/tensioning, tear patching, and seam sealing.
 */
public class SewingFrameBlockEntity extends BlockEntity {
	private ItemStack pieceA = ItemStack.EMPTY;
	@Nullable
	private WaterskinConstruction construction = null;
	private double alignOffset = 0.45; // starts misaligned until player aligns pieces
	private double cordageBufferM = 0.0;
	private int repairAccum = 0;

	public SewingFrameBlockEntity(BlockPos pos, BlockState state) {
		super(MaterialBlocks.SEWING_FRAME_ENTITY, pos, state);
	}

	public boolean hasPendingPiece() {
		return !pieceA.isEmpty();
	}

	public boolean hasConstruction() {
		return construction != null;
	}

	@Nullable
	public WaterskinConstruction construction() {
		return construction;
	}

	public double alignOffset() {
		return alignOffset;
	}

	public void addLeatherPiece(ItemStack stack, PlayerEntity player, World world, BlockPos pos) {
		if (construction != null) return;
		if (pieceA.isEmpty()) {
			pieceA = stack.copyWithCount(1);
			if (!player.isCreative()) stack.decrement(1);
			world.playSound(null, pos, SoundEvents.ITEM_ARMOR_EQUIP_LEATHER.value(), SoundCategory.BLOCKS, 0.5f, 1.0f);
			player.sendMessage(Text.translatable("project_realism.sewing.placed_first").formatted(Formatting.AQUA), true);
			sync();
			return;
		}
		// Second piece placed! Initialize physical misaligned clamp assembly
		HideSurface.CutPieceProperties pa = LeatherPieceItem.readProperties(pieceA);
		HideSurface.CutPieceProperties pb = LeatherPieceItem.readProperties(stack);
		if (!player.isCreative()) stack.decrement(1);
		pieceA = ItemStack.EMPTY;

		alignOffset = (world.random.nextBoolean() ? 1.0 : -1.0) * 0.48;
		double align = Math.max(0.2, 1.0 - Math.abs(alignOffset));
		construction = WaterskinConstruction.fromPieces(
				(pa.thickness + pb.thickness) * 0.5,
				(pa.cleanliness + pb.cleanliness) * 0.5,
				(pa.edgeRegularity + pb.edgeRegularity) * 0.5,
				align,
				pa.animal,
				pa.areaCm2 + pb.areaCm2,
				(pa.stiffness + pb.stiffness) * 0.5,
				(pa.moisture + pb.moisture) * 0.5
		);
		world.playSound(null, pos, SoundEvents.ITEM_ARMOR_EQUIP_LEATHER.value(), SoundCategory.BLOCKS, 0.6f, 0.9f);
		player.sendMessage(Text.translatable("project_realism.sewing.align_hint").formatted(Formatting.GOLD), true);
		sync();
	}

	public void mountWaterskinForRepair(ItemStack stack, PlayerEntity player) {
		construction = WaterskinConstruction.read(stack);
		alignOffset = 0.0;
		if (!player.isCreative()) stack.decrement(1);
		sync();
	}

	public void nudgeAlignment(double delta, int seamIdx, PlayerEntity player, World world, BlockPos pos) {
		if (construction == null) return;
		WaterskinConstruction.SeamSection s = construction.seams[seamIdx];
		// Only permit nudging unsewn assemblies; if already stitched, inspect that specific seam!
		if (s.stitchedHoles == 0 && Math.abs(alignOffset) > 0.01) {
			alignOffset = Math.max(-0.6, Math.min(0.6, alignOffset + delta));
			double align = Math.max(0.20, Math.min(1.0, 1.0 - Math.abs(alignOffset) * 1.4));
			for (WaterskinConstruction.SeamSection sec : construction.seams) {
				if (sec.stitchedHoles == 0) sec.edgeAlignment = align;
			}
			world.playSound(null, pos, SoundEvents.ITEM_ARMOR_EQUIP_LEATHER.value(), SoundCategory.BLOCKS, 0.4f, 1.25f);
			player.sendMessage(Text.translatable("project_realism.sewing.alignment",
					(int) Math.round(align * 100.0)).formatted(align >= 0.88 ? Formatting.GREEN : Formatting.YELLOW), true);
			sync();
			return;
		}
		WaterskinConstruction.SeamZone zone = WaterskinConstruction.SeamZone.values()[seamIdx];
		player.sendMessage(Text.translatable("project_realism.sewing.seam_inspect",
				zone.key, s.punchedHoles, s.stitchedHoles,
				(int) Math.round(s.meanTension * 100.0),
				(int) Math.round(s.sealLevel * 100.0),
				(int) Math.round(s.integrity() * 100.0))
				.formatted(s.integrity() >= 0.85 ? Formatting.GREEN : Formatting.GOLD), true);
	}

	public void punchHole(int seamIdx, PlayerEntity player, Hand hand, ItemStack awl, World world, BlockPos pos) {
		if (construction == null) return;
		WaterskinConstruction.SeamSection s = construction.seams[seamIdx];
		if (s.punchedHoles >= WaterskinConstruction.HOLES_PER_SEAM) {
			player.sendMessage(Text.translatable("project_realism.sewing.holes_full").formatted(Formatting.GRAY), true);
			return;
		}
		// Thick dense leather takes more effort to pierce!
		repairAccum++;
		int needed = construction.leatherThickness > 0.78 ? 2 : 1;
		world.playSound(null, pos, SoundEvents.BLOCK_WOOD_HIT, SoundCategory.BLOCKS, 0.5f, 1.35f);
		if (repairAccum < needed) {
			return;
		}
		repairAccum = 0;
		s.punchedHoles++;
		if (!player.isCreative()) {
			awl.damage(1, player, hand == Hand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
		}
		player.sendMessage(Text.translatable("project_realism.sewing.punched",
				WaterskinConstruction.SeamZone.values()[seamIdx].key,
				s.punchedHoles, WaterskinConstruction.HOLES_PER_SEAM).formatted(Formatting.AQUA), true);
		sync();
	}

	public void stitchOrTighten(int seamIdx, PlayerEntity player, Hand hand, ItemStack needle, World world, BlockPos pos) {
		if (construction == null) return;
		WaterskinConstruction.SeamSection s = construction.seams[seamIdx];
		if (s.punchedHoles <= 0) {
			player.sendMessage(Text.translatable("project_realism.sewing.need_holes").formatted(Formatting.RED), true);
			return;
		}

		// If un-stitched holes remain, pull cordage through next hole
		if (s.stitchedHoles < s.punchedHoles) {
			double neededM = 0.16 + construction.leatherThickness * 0.12;
			if (!player.isCreative() && cordageBufferM < neededM) {
				if (!consumeCordage(player)) {
					player.sendMessage(Text.translatable("project_realism.sewing.need_cordage").formatted(Formatting.RED), true);
					return;
				}
				cordageBufferM += 1.0; // 1 cordage strand = ~1.0 meter of usable thread
			}
			cordageBufferM = Math.max(0.0, cordageBufferM - neededM);
			construction.consumedCordageMeters += neededM;
			s.stitchedHoles++;
			s.meanTension = Math.max(0.35, s.meanTension == 0.0 ? 0.42 : s.meanTension);
			s.seamWear = Math.max(0.0, s.seamWear - 0.15); // re-stitching reinforces wear
			if (!player.isCreative()) {
				needle.damage(1, player, hand == Hand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
			}
			world.playSound(null, pos, SoundEvents.ENTITY_LEASH_KNOT_PLACE, SoundCategory.BLOCKS, 0.55f, 1.15f);
			player.sendMessage(Text.translatable("project_realism.sewing.stitched",
					WaterskinConstruction.SeamZone.values()[seamIdx].key,
					s.stitchedHoles, s.punchedHoles).formatted(Formatting.AQUA), true);
			sync();
			return;
		}

		// All punched holes stitched -> pulling needle draws stitches tighter!
		s.meanTension = Math.min(1.15, s.meanTension + 0.13);
		if (s.meanTension > 0.90) {
			// Over-tightening tears holes in the leather!
			s.tearDamage = Math.min(1.0, s.tearDamage + 0.22);
			s.meanTension = 0.65;
			world.playSound(null, pos, SoundEvents.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 0.6f, 1.4f);
			player.sendMessage(Text.translatable("project_realism.sewing.overtension").formatted(Formatting.RED), true);
		} else {
			world.playSound(null, pos, SoundEvents.ENTITY_LEASH_KNOT_PLACE, SoundCategory.BLOCKS, 0.6f,
					(float) (0.95 + s.meanTension * 0.45));
			player.sendMessage(Text.translatable("project_realism.sewing.tension",
					(int) Math.round(s.meanTension * 100.0)).formatted(
					s.meanTension >= 0.68 && s.meanTension <= 0.86 ? Formatting.GREEN : Formatting.GOLD), true);
		}
		sync();
	}

	public void sealSeam(int seamIdx, PlayerEntity player, ItemStack sealant, World world, BlockPos pos) {
		if (construction == null) return;
		WaterskinConstruction.SeamSection s = construction.seams[seamIdx];
		if (s.stitchedHoles <= 0) {
			player.sendMessage(Text.translatable("project_realism.sewing.stitch_first").formatted(Formatting.YELLOW), true);
			return;
		}
		s.sealLevel = Math.min(1.0, s.sealLevel + 0.55);
		if (!player.isCreative()) sealant.decrement(1);
		world.playSound(null, pos, SoundEvents.ITEM_HONEYCOMB_WAX_ON, SoundCategory.BLOCKS, 0.7f, 1.0f);
		player.sendMessage(Text.translatable("project_realism.sewing.sealed",
				WaterskinConstruction.SeamZone.values()[seamIdx].key,
				(int) Math.round(s.integrity() * 100.0)).formatted(Formatting.GREEN), true);
		sync();
	}

	public void patchSeamWithScrap(int seamIdx, PlayerEntity player, ItemStack scrap, World world, BlockPos pos) {
		if (construction == null) return;
		WaterskinConstruction.SeamSection s = construction.seams[seamIdx];
		s.tearDamage = Math.max(0.0, s.tearDamage - 0.55);
		s.seamWear = Math.max(0.0, s.seamWear - 0.55);
		if (!player.isCreative()) scrap.decrement(1);
		world.playSound(null, pos, SoundEvents.ITEM_ARMOR_EQUIP_LEATHER.value(), SoundCategory.BLOCKS, 0.6f, 0.9f);
		player.sendMessage(Text.translatable("project_realism.sewing.patched",
				WaterskinConstruction.SeamZone.values()[seamIdx].key).formatted(Formatting.AQUA), true);
		sync();
	}

	public void takeResult(PlayerEntity player, World world, BlockPos pos) {
		if (!pieceA.isEmpty()) {
			if (!player.getInventory().insertStack(pieceA)) Block.dropStack(world, pos, pieceA);
			pieceA = ItemStack.EMPTY;
			sync();
			return;
		}
		if (construction == null) return;
		ItemStack skin = new ItemStack(HydrationItems.WATERSKIN);
		WaterskinConstruction.write(skin, construction);
		WaterskinItem.setContents(skin, 0, WaterQuality.FRESH_SPRING);
		construction = null;
		cordageBufferM = 0.0;
		if (!player.getInventory().insertStack(skin)) {
			Block.dropStack(world, pos, skin);
		}
		world.playSound(null, pos, SoundEvents.ITEM_ARMOR_EQUIP_LEATHER.value(), SoundCategory.BLOCKS, 0.6f, 1.0f);
		sync();
	}

	private static boolean consumeCordage(PlayerEntity player) {
		ItemStack off = player.getOffHandStack();
		if (off.isOf(PrimitiveItems.CORDAGE)) {
			off.decrement(1);
			return true;
		}
		for (int i = 0; i < player.getInventory().size(); i++) {
			ItemStack st = player.getInventory().getStack(i);
			if (st.isOf(PrimitiveItems.CORDAGE)) {
				st.decrement(1);
				return true;
			}
		}
		return false;
	}

	public void dropContents(World world, BlockPos pos) {
		if (!pieceA.isEmpty()) {
			Block.dropStack(world, pos, pieceA);
			pieceA = ItemStack.EMPTY;
		}
		if (construction != null) {
			ItemStack skin = new ItemStack(HydrationItems.WATERSKIN);
			WaterskinConstruction.write(skin, construction);
			WaterskinItem.setContents(skin, 0, WaterQuality.FRESH_SPRING);
			Block.dropStack(world, pos, skin);
			construction = null;
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
		if (!pieceA.isEmpty()) nbt.put("PieceA", pieceA.encode(registryLookup));
		if (construction != null) nbt.put("Constr", construction.toNbt());
		nbt.putDouble("AlignOff", alignOffset);
		nbt.putDouble("CordBuf", cordageBufferM);
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		super.readNbt(nbt, registryLookup);
		pieceA = nbt.contains("PieceA")
				? ItemStack.fromNbtOrEmpty(registryLookup, nbt.getCompound("PieceA"))
				: ItemStack.EMPTY;
		construction = nbt.contains("Constr")
				? WaterskinConstruction.fromNbt(nbt.getCompound("Constr"))
				: null;
		alignOffset = nbt.getDouble("AlignOff");
		cordageBufferM = nbt.getDouble("CordBuf");
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
