package com.projectrealism.metallurgy;

import com.projectrealism.anatomy.AnatomyItems;
import com.projectrealism.knapping.DynamicKnappingItems;
import com.projectrealism.knapping.DynamicStoneItem;
import com.projectrealism.knapping.FractureEngine;
import com.projectrealism.knapping.HaftData;
import com.projectrealism.knapping.KnappingStoneBlock;
import com.projectrealism.knapping.StoneMaterial;
import com.projectrealism.knapping.VoxelRockGrid;
import com.projectrealism.primitive.PrimitiveItems;
import com.projectrealism.thermo.ThermalWorldState;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Random;

/**
 * BlockEntity holding a {@link DynamicMetalState} on a stone/metal forging anvil.
 */
public class PlacedMetalBlockEntity extends BlockEntity {
	private DynamicMetalState metal = DynamicMetalState.createNativeNugget(7L, 0.85, 0.97);
	private boolean pickedUpOrExhausted = false;
	private int tickCounter = 0;

	public PlacedMetalBlockEntity(BlockPos pos, BlockState state) {
		super(MetallurgyBlocks.PLACED_METAL_ENTITY, pos, state);
	}

	public DynamicMetalState metal() {
		return metal;
	}

	public void setMetal(DynamicMetalState state) {
		this.metal = state != null ? state : DynamicMetalState.createNativeNugget(7L, 0.85, 0.97);
		sync();
	}

	public void serverTick(World world, BlockPos pos) {
		if (++tickCounter % 10 != 0) return;
		if (!(world instanceof ServerWorld sw)) return;
		double envK = ThermalWorldState.get(sw).ambientTempAt(sw, pos);
		// Check adjacent hearth/smelting basin heat if placed beside coals
		BlockState below = world.getBlockState(pos.down());
		if (below.isOf(Blocks.CAMPFIRE) || below.isOf(Blocks.MAGMA_BLOCK)) {
			envK = Math.max(envK, 920.0);
		}
		if (Math.abs(metal.temperatureK - envK) > 4.0) {
			metal.stepThermal(envK, 0.5, false);
			sync();
		}
	}

	public void inspect(PlayerEntity player) {
		VoxelRockGrid.GeometryMetrics m = metal.metrics();
		player.sendMessage(Text.literal(String.format("%s | %.0f°C | %.2f kg | Hard %.1f | Disloc %.0f%% | Edge %.0f° (%.2fmm, %.1fcm)",
						metal.alloy.describeComposition(),
						metal.temperatureK - 273.15,
						metal.totalMassKg(),
						metal.effectiveHardness(),
						metal.grid.micro.averageDislocation() * 100,
						m.primaryEdgeAngleDeg(),
						m.primaryEdgeRadiusMm(),
						m.continuousEdgeCm()))
				.formatted(metal.temperatureK > 720.0 ? Formatting.GOLD : Formatting.AQUA), true);
	}

	public void rotateFace(Direction side, PlayerEntity player, World world) {
		if (metal.temperatureK > 420.0 && !DynamicMetalItem.hasHeatProtection(player)) {
			player.damage(world.getDamageSources().hotFloor(), 1.0f);
			player.sendMessage(Text.literal("Too hot to turn bare-handed — use Iron Tongs or Leather!").formatted(Formatting.RED), true);
			return;
		}
		int axis = side.getAxis() == Direction.Axis.X ? 2 : 0;
		metal.grid.rotate90(axis);
		metal.invalidateGeometry();
		world.playSound(null, pos, SoundEvents.BLOCK_ANVIL_STEP, SoundCategory.BLOCKS, 0.5f, 1.3f);
		sync();
	}

	public void hammerStrike(Direction facing, PlayerEntity player, ItemStack striker,
							 Vec3d hitWorld, World world, BlockPos pos) {
		if (!(world instanceof ServerWorld sw)) return;

		// Require a reasonably hard solid surface underneath to back up plastic deformation!
		BlockState anvil = world.getBlockState(pos.down());
		double anvilRigid = (anvil.isOf(Blocks.ANVIL) || anvil.isOf(Blocks.DEEPSLATE)
				|| anvil.isOf(Blocks.BASALT) || anvil.isOf(Blocks.GRANITE) || anvil.isOf(Blocks.STONE)) ? 1.0 : 0.45;

		Vec3d eyeLocal = KnappingStoneBlock.worldToVoxelPoint(facing, pos, player.getEyePos());
		Vec3d hitLocal = KnappingStoneBlock.worldToVoxelPoint(facing, pos, hitWorld);
		Vec3d rayDir = hitLocal.subtract(eyeLocal).normalize();
		Vec3d start = hitLocal.subtract(rayDir.multiply(0.18));
		FractureEngine.VoxelHit vHit = ForgeEngine.raycastMetal(metal.grid, start, rayDir);
		if (vHit == null) {
			player.sendMessage(Text.literal("Strike directly onto the metal mass!").formatted(Formatting.GRAY), true);
			return;
		}

		double strikerHardness = 5.5;
		double contactRadius = 1.55;
		double energy = (player.isSprinting() ? 2.5 : (player.isSneaking() ? 0.75 : 1.35)) * anvilRigid;

		if (striker.isOf(DynamicKnappingItems.DYNAMIC_STONE)) {
			var s = DynamicStoneItem.readState(striker);
			strikerHardness = s.material.mohsHardness;
			contactRadius = Math.max(0.95, Math.min(2.1, 0.9 + s.metrics().flatSurfaceAreaCm2() / 16.0));
			energy *= Math.max(0.6, Math.min(1.45, s.metrics().massKg() / 1.3));
		} else if (striker.isOf(MetallurgyItems.DYNAMIC_METAL)) {
			DynamicMetalState ms = DynamicMetalItem.readState(striker);
			strikerHardness = ms.effectiveHardness();
			contactRadius = Math.max(0.90, Math.min(2.0, 0.9 + ms.metrics().flatSurfaceAreaCm2() / 14.0));
			energy *= Math.max(0.65, Math.min(1.65, ms.totalMassKg() / 1.4));
		} else {
			StoneMaterial sm = DynamicKnappingItems.materialOfItem(striker);
			if (sm != null) strikerHardness = sm.mohsHardness;
		}

		Vec3d localDir = KnappingStoneBlock.worldDirToVoxelDir(facing, player.getRotationVec(1.0f)).normalize();
		ForgeEngine.ForgeOutcome out = ForgeEngine.hammerStrike(
				metal, vHit, contactRadius, strikerHardness, energy, localDir, new Random(sw.random.nextLong()));

		float pitch = out.cracked() ? 0.65f : (metal.temperatureK > 820.0 ? 1.35f : 1.05f);
		sw.playSound(null, pos, SoundEvents.BLOCK_ANVIL_LAND, SoundCategory.BLOCKS, 0.45f, pitch);
		if (metal.temperatureK > 820.0 && out.displacedMillivoxels() > 0) {
			sw.spawnParticles(ParticleTypes.LAVA, pos.getX() + 0.5, pos.getY() + 0.25, pos.getZ() + 0.5,
					4, 0.15, 0.08, 0.15, 0.02);
		}

		if (out.cracked()) {
			player.sendMessage(Text.literal("Work-hardened crack! Heat metal above recrystallization to anneal before further cold hammering.")
					.formatted(Formatting.RED), true);
		} else if (out.displacedMillivoxels() > 0) {
			VoxelRockGrid.GeometryMetrics m = metal.metrics();
			player.sendMessage(Text.literal(String.format("%s | Edge: %.0f° (%.2f mm, %.1f cm) | Work-hard %.0f%% | Slag %.1f%%",
							out.kind().id, m.primaryEdgeAngleDeg(), m.primaryEdgeRadiusMm(),
							m.continuousEdgeCm(), out.avgDislocationAfter() * 100, metal.alloy.slag * 100))
					.formatted(out.slagExpelledFrac() > 0.005 ? Formatting.YELLOW : Formatting.GREEN), true);
		} else {
			player.sendMessage(Text.literal("Metal too hard/cold for this light blow — heat or use heavier hammer.")
					.formatted(Formatting.GRAY), true);
		}
		sync();
	}

	public void honeEdge(PlayerEntity player, World world, BlockPos pos) {
		double honed = ForgeEngine.grindEdge(metal, 7.0);
		world.playSound(null, pos, SoundEvents.BLOCK_GRINDSTONE_USE, SoundCategory.BLOCKS, 0.5f, 1.25f);
		player.sendMessage(Text.literal(String.format("Honed metal edge (%.0f zones, %.2f mm radius)",
				honed, metal.metrics().primaryEdgeRadiusMm())).formatted(Formatting.GREEN), true);
		sync();
	}

	public void attachStick(PlayerEntity player, ItemStack stick, World world, BlockPos pos) {
		if (metal.haft.hafted) {
			var all = HaftData.Orientation.values();
			var next = all[(metal.haft.orientation.ordinal() + 1) % all.length];
			metal.haft.attachHandle(next, next == HaftData.Orientation.INLINE ? 30.0 : 62.0,
					next == HaftData.Orientation.INLINE ? 0.18 : 0.42, metal.metrics());
			metal.invalidateGeometry();
			world.playSound(null, pos, SoundEvents.ITEM_ARMOR_EQUIP_LEATHER.value(), SoundCategory.BLOCKS, 0.6f, 1.1f);
			player.sendMessage(Text.literal("Handle orientation: " + metal.haft.orientation.id).formatted(Formatting.AQUA), true);
			sync();
			return;
		}
		if (metal.temperatureK > 450.0) {
			player.sendMessage(Text.literal("Cool metal below 180°C before fitting a wooden haft!").formatted(Formatting.RED), true);
			return;
		}
		VoxelRockGrid.GeometryMetrics m = metal.metrics();
		HaftData.Orientation ori = m.continuousEdgeCm() >= 3.5 && m.primaryEdgeAngleDeg() > 26.0
				? HaftData.Orientation.PERPENDICULAR_CHOP
				: HaftData.Orientation.INLINE;
		metal.haft.attachHandle(ori, ori == HaftData.Orientation.INLINE ? 30.0 : 62.0,
				ori == HaftData.Orientation.INLINE ? 0.18 : 0.42, m);
		metal.invalidateGeometry();
		if (!player.isCreative()) stick.decrement(1);
		world.playSound(null, pos, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 0.6f, 1.0f);
		player.sendMessage(Text.literal("Fitted wooden handle — wrap fiber/cordage/sinew to tighten.").formatted(Formatting.YELLOW), true);
		sync();
	}

	public void bindHandle(PlayerEntity player, ItemStack fiber, World world, BlockPos pos) {
		if (!metal.haft.hafted) {
			player.sendMessage(Text.literal("Attach a stick first before binding.").formatted(Formatting.GRAY), true);
			return;
		}
		String mat = fiber.isOf(AnatomyItems.SINEW) ? "sinew"
				: (fiber.isOf(AnatomyItems.TENDON) ? "tendon"
				: (fiber.isOf(PrimitiveItems.CORDAGE) ? "cordage" : "plant_fiber"));
		metal.haft.wrapBinding(mat, player.isSneaking() ? 0.96 : 0.82);
		if (!player.isCreative()) fiber.decrement(1);
		world.playSound(null, pos, SoundEvents.ITEM_ARMOR_EQUIP_LEATHER.value(), SoundCategory.BLOCKS, 0.7f, 1.25f);
		player.sendMessage(Text.literal(String.format("Bound %s (tension %.0f%%)",
				mat, metal.haft.bindingTension * 100)).formatted(Formatting.GREEN), true);
		sync();
	}

	public void pickUp(PlayerEntity player, World world, BlockPos pos) {
		if (metal.temperatureK > 400.0 && !DynamicMetalItem.hasHeatProtection(player)) {
			player.damage(world.getDamageSources().hotFloor(), 1.5f);
			player.sendMessage(Text.literal("Hot metal! Hold Iron Tongs or Leather in offhand to lift safely.")
					.formatted(Formatting.RED), true);
			return;
		}
		pickedUpOrExhausted = true;
		ItemStack item = DynamicMetalItem.create(metal.copy());
		if (!player.getInventory().insertStack(item)) {
			Block.dropStack(world, pos, item);
		}
		world.removeBlock(pos, false);
		world.playSound(null, pos, SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.55f, 1.1f);
	}

	public void dropOnBreak(World world, BlockPos pos) {
		if (!pickedUpOrExhausted) {
			Block.dropStack(world, pos, DynamicMetalItem.create(metal.copy()));
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
		nbt.put("Metal", metal.toNbt());
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		super.readNbt(nbt, registryLookup);
		if (nbt.contains("Metal")) {
			metal = DynamicMetalState.fromNbt(nbt.getCompound("Metal"));
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
