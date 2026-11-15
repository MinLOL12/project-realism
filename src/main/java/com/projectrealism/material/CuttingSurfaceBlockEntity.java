package com.projectrealism.material;

import com.projectrealism.anatomy.AnatomyItems;
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

/**
 * Cutting surface holding a hide for rotation, live knife-path tracing, and panel/scrap separation.
 */
public class CuttingSurfaceBlockEntity extends BlockEntity {
	@Nullable
	private HideSurface hide = null;
	private int rotationSteps = 0; // 0..3 quarter turns
	private double lastU = -1.0;
	private double lastV = -1.0;

	public CuttingSurfaceBlockEntity(BlockPos pos, BlockState state) {
		super(MaterialBlocks.CUTTING_SURFACE_ENTITY, pos, state);
	}

	public boolean hasHide() {
		return hide != null;
	}

	@Nullable
	public HideSurface hide() {
		return hide;
	}

	public int rotationSteps() {
		return rotationSteps;
	}

	public void placeHide(ItemStack stack, PlayerEntity player) {
		if (hide != null || !stack.isOf(MaterialItems.RAW_HIDE)) return;
		hide = RawHideItem.readSurface(stack);
		hide.clearCutMarks();
		rotationSteps = 0;
		lastU = -1.0;
		if (!player.isCreative()) stack.decrement(1);
		sync();
	}

	public void takeHide(PlayerEntity player, World world, BlockPos pos) {
		if (hide == null) return;
		hide.clearCutMarks();
		ItemStack item = RawHideItem.create(hide);
		hide = null;
		lastU = -1.0;
		if (!player.getInventory().insertStack(item)) {
			Block.dropStack(world, pos, item);
		}
		sync();
	}

	public void rotateAndInspect(PlayerEntity player, World world, BlockPos pos) {
		if (hide == null) return;
		rotationSteps = (rotationSteps + 1) % 4;
		lastU = -1.0;
		world.playSound(null, pos, SoundEvents.ITEM_ARMOR_EQUIP_LEATHER.value(), SoundCategory.BLOCKS, 0.45f, 1.15f);
		player.sendMessage(Text.translatable("project_realism.cutting.rotated",
				rotationSteps * 90,
				(int) Math.round(hide.usableAreaCm2()),
				(int) Math.round(hide.averageThickness() * 100.0))
				.formatted(Formatting.AQUA), true);
		sync();
	}

	private double[] rotateUV(double rawU, double rawV) {
		double u = rawU - 0.5;
		double v = rawV - 0.5;
		return switch (rotationSteps) {
			case 1 -> new double[]{v + 0.5, -u + 0.5};
			case 2 -> new double[]{-u + 0.5, -v + 0.5};
			case 3 -> new double[]{-v + 0.5, u + 0.5};
			default -> new double[]{u + 0.5, v + 0.5};
		};
	}

	private static double knifeSharpness(ItemStack knife) {
		if (knife.isOf(PrimitiveItems.STONE_KNIFE)) {
			return ToolCondition.getSharpness(knife) / 100.0;
		}
		if (knife.isOf(AnatomyItems.BONE_BLADE)) {
			return Math.max(0.35, AnatomyItems.BoneToolItem.getWorkQualityPct(knife) / 100.0);
		}
		if (knife.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE)) {
			var s = com.projectrealism.knapping.DynamicStoneItem.readState(knife);
			return Math.max(0.15, Math.min(1.0, com.projectrealism.knapping.StoneInteractionPhysics.evaluate(
					s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.HIDE_CUT, 85.0)
					.effectiveness()));
		}
		return 0.80;
	}

	private static void wearKnife(PlayerEntity player, Hand hand, ItemStack knife, float hardness, ServerWorld sw) {
		if (knife.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE)) {
			var s = com.projectrealism.knapping.DynamicStoneItem.readState(knife);
			com.projectrealism.knapping.StoneInteractionPhysics.applyContactWear(
					s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.HIDE_CUT,
					85.0 * hardness, new java.util.Random(sw.random.nextLong()));
			com.projectrealism.knapping.DynamicStoneItem.writeState(knife, s);
		} else if (ToolCondition.isPrimitiveTool(knife)) {
			ToolCondition.applyWear(player, hand, knife, hardness, sw);
		} else if (knife.isDamageable() && !player.isCreative()) {
			knife.damage(1, player, hand == Hand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
		}
	}

	public void cutTo(PlayerEntity player, Hand hand, ItemStack knife, double rawU, double rawV,
					  World world, BlockPos pos) {
		if (hide == null) return;
		double[] uv = rotateUV(rawU, rawV);
		double sharpness = knifeSharpness(knife);
		if (lastU < 0.0) {
			lastU = uv[0];
			lastV = uv[1];
			hide.cutSegment(uv[0], uv[1], uv[0], uv[1], sharpness);
		} else {
			hide.cutSegment(lastU, lastV, uv[0], uv[1], sharpness);
			lastU = uv[0];
			lastV = uv[1];
		}

		if (world instanceof ServerWorld sw) {
			boolean dullSnag = sharpness < 0.40 && hide.averageThickness() > 0.65;
			sw.playSound(null, pos,
					dullSnag ? SoundEvents.BLOCK_WOOD_HIT : SoundEvents.ENTITY_SHEEP_SHEAR,
					SoundCategory.BLOCKS, 0.55f, dullSnag ? 0.7f : (float) (1.1 + sharpness * 0.25));
			sw.spawnParticles(ParticleTypes.CRIT, pos.getX() + rawU, pos.getY() + 0.78, pos.getZ() + rawV,
					2, 0.05, 0.02, 0.05, 0.01);
			wearKnife(player, hand, knife, (float) (0.6 + hide.averageThickness() * 0.5), sw);
			player.sendMessage(Text.translatable("project_realism.cutting.tracing",
					hide.cutMarkCount(), (int) Math.round(hide.lastCutRegularity * 100.0))
					.formatted(dullSnag ? Formatting.YELLOW : Formatting.AQUA), true);
		}
		sync();
	}

	public void separateCutPiece(PlayerEntity player, Hand hand, ItemStack knife, World world, BlockPos pos) {
		if (hide == null) return;
		double sharpness = knifeSharpness(knife);
		lastU = -1.0;

		HideSurface.CutPieceProperties props = hide.extractCutPiece(sharpness);
		if (props != null) {
			ItemStack piece = LeatherPieceItem.create(props);
			if (!player.getInventory().insertStack(piece)) {
				Block.dropStack(world, pos.up(), piece);
			}
			// Any small excess trimmed scrap drops cleanly
			if (props.areaCm2 > 600.0 && world.random.nextFloat() < 0.7f) {
				Block.dropStack(world, pos.up(), new ItemStack(MaterialItems.HIDE_SCRAP, 1));
			}
			if (world instanceof ServerWorld sw) {
				sw.playSound(null, pos, SoundEvents.ENTITY_SHEEP_SHEAR, SoundCategory.BLOCKS, 0.75f, 0.95f);
				wearKnife(player, hand, knife, 0.8f, sw);
			}
			player.sendMessage(Text.translatable("project_realism.cutting.separated",
					(int) Math.round(props.areaCm2), (int) Math.round(props.edgeRegularity * 100.0))
					.formatted(Formatting.GREEN), true);
			if (!hide.hasRemainingUsableCells()) {
				if (hide.intactCount() > 0) {
					Block.dropStack(world, pos.up(), new ItemStack(MaterialItems.HIDE_SCRAP, 1));
				}
				hide = null;
			}
		}
		sync();
	}

	public void dropContents(World world, BlockPos pos) {
		if (hide != null) {
			hide.clearCutMarks();
			Block.dropStack(world, pos, RawHideItem.create(hide));
			hide = null;
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
		nbt.putInt("Rot", rotationSteps);
		nbt.putDouble("LastU", lastU);
		nbt.putDouble("LastV", lastV);
		if (hide != null) nbt.put("Hide", hide.toNbt());
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		super.readNbt(nbt, registryLookup);
		rotationSteps = nbt.getInt("Rot");
		lastU = nbt.contains("LastU") ? nbt.getDouble("LastU") : -1.0;
		lastV = nbt.contains("LastV") ? nbt.getDouble("LastV") : -1.0;
		hide = nbt.contains("Hide") ? HideSurface.fromNbt(nbt.getCompound("Hide")) : null;
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
