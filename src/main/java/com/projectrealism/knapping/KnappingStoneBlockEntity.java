package com.projectrealism.knapping;

import com.projectrealism.anatomy.AnatomyItems;
import com.projectrealism.material.MaterialBlocks;
import com.projectrealism.primitive.PrimitiveItems;
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
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Random;

/**
 * Persistent BlockEntity owning a {@link DynamicStoneState}, executing raycast voxel knapping,
 * handle fitting, binding tensioning, and geometric inspection.
 */
public class KnappingStoneBlockEntity extends BlockEntity {
	private DynamicStoneState stone = DynamicStoneState.createCobble(StoneMaterial.FLINT, 1L, 0.88);
	private boolean pickedUpOrExhausted = false;
	private net.minecraft.util.shape.VoxelShape cachedShape;
	private Direction cachedShapeFacing;

	public KnappingStoneBlockEntity(BlockPos pos, BlockState state) {
		super(DynamicKnappingBlocks.KNAPPING_STONE_ENTITY, pos, state);
	}

	public DynamicStoneState stone() {
		return stone;
	}

	public void setStone(DynamicStoneState s) {
		this.stone = s != null ? s : DynamicStoneState.createCobble(StoneMaterial.FLINT, 1L, 0.88);
		this.cachedShape = null;
		sync();
	}

	public net.minecraft.util.shape.VoxelShape outlineShape(Direction facing) {
		if (cachedShape != null && cachedShapeFacing == facing) {
			return cachedShape;
		}
		int minX = 16, minZ = 16, maxX = -1, maxY = -1, maxZ = -1;
		for (int y = 0; y < VoxelRockGrid.SIZE; y++) {
			for (int z = 0; z < VoxelRockGrid.SIZE; z++) {
				for (int x = 0; x < VoxelRockGrid.SIZE; x++) {
					if (!stone.grid.isSolid(x, y, z)) continue;
					int rx, rz;
					switch (facing) {
						case SOUTH -> { rx = 15 - x; rz = 15 - z; }
						case WEST  -> { rx = z; rz = 15 - x; }
						case EAST  -> { rx = 15 - z; rz = x; }
						default    -> { rx = x; rz = z; }
					}
					if (rx < minX) minX = rx;
					if (rx > maxX) maxX = rx;
					if (y > maxY) maxY = y;
					if (rz < minZ) minZ = rz;
					if (rz > maxZ) maxZ = rz;
				}
			}
		}
		if (maxX < 0) {
			cachedShape = Block.createCuboidShape(2, 0, 2, 14, 4, 14);
		} else {
			double x0 = Math.max(0.5, minX);
			double x1 = Math.min(15.5, maxX + 1.0);
			double z0 = Math.max(0.5, minZ);
			double z1 = Math.min(15.5, maxZ + 1.0);
			double y1 = Math.max(2.5, Math.min(15.5, maxY + 1.25));
			cachedShape = Block.createCuboidShape(x0, 0.0, z0, x1, y1, z1);
		}
		cachedShapeFacing = facing;
		return cachedShape;
	}

	public void inspectAt(Direction facing, PlayerEntity player, Vec3d hitWorld, BlockPos pos) {
		FractureEngine.VoxelHit vHit = raycastFromPlayer(facing, player, hitWorld, pos);
		VoxelRockGrid.GeometryMetrics m = stone.metrics();
		if (vHit != null) {
			int cracks = stone.grid.getCrackLevel(vHit.x(), vHit.y(), vHit.z());
			boolean inc = stone.grid.hasInclusion(vHit.x(), vHit.y(), vHit.z());
			player.sendMessage(Text.translatable("project_realism.knapping.inspect_voxel",
					stone.material.id,
					String.format("%.2f", stone.totalMassKg()),
					m.voxelCount(),
					(int) Math.round(m.primaryEdgeAngleDeg()),
					String.format("%.2f", m.primaryEdgeRadiusMm()),
					String.format("%.1f", m.continuousEdgeCm()),
					cracks,
					inc ? "inclusion" : "clean").formatted(Formatting.AQUA), true);
		} else {
			player.sendMessage(Text.translatable("project_realism.knapping.inspect_stone",
					stone.material.id,
					String.format("%.2f", stone.totalMassKg()),
					m.voxelCount(),
					String.format("%.1f", m.lengthCm()),
					String.format("%.1f", m.widthCm()),
					String.format("%.1f", m.thicknessCm())).formatted(Formatting.GOLD), true);
		}
	}

	public void rotateFace(Direction side, PlayerEntity player, World world, BlockPos pos) {
		int axis = (side.getAxis() == Direction.Axis.X) ? 2 : 0;
		stone.grid.rotate90(axis);
		stone.invalidateGeometry();
		world.playSound(null, pos, SoundEvents.BLOCK_STONE_STEP, SoundCategory.BLOCKS, 0.5f, 1.1f);
		player.sendMessage(Text.translatable("project_realism.knapping.rotated").formatted(Formatting.GRAY), true);
		sync();
	}

	public void strikeWithRock(Direction facing, PlayerEntity player, Hand hand, ItemStack striker,
							   Vec3d hitWorld, World world, BlockPos pos) {
		if (!(world instanceof ServerWorld sw)) return;

		FractureEngine.VoxelHit vHit = raycastFromPlayer(facing, player, hitWorld, pos);
		if (vHit == null) {
			player.sendMessage(Text.translatable("project_realism.knapping.missed_voxel").formatted(Formatting.GRAY), true);
			return;
		}

		StoneMaterial strikerMat = DynamicKnappingItems.materialOfItem(striker);
		if (strikerMat == null) strikerMat = StoneMaterial.GRANITE;

		// Impact energy modulated intentionally by player posture (sneak = precision flake; sprint = heavy split)
		double baseEnergy = player.isSprinting() ? 2.45 : (player.isSneaking() ? 0.68 : 1.25);
		if (striker.isOf(DynamicKnappingItems.DYNAMIC_STONE)) {
			double sMass = DynamicStoneItem.readState(striker).metrics().massKg();
			baseEnergy *= Math.max(0.55, Math.min(1.55, sMass / 1.35));
		}

		Vec3d localDir = KnappingStoneBlock.worldDirToVoxelDir(facing, player.getRotationVec(1.0f)).normalize();
		FractureEngine.SupportCondition support = detectGroundSupport(world, pos.down());

		FractureEngine.KnapOutcome outcome = FractureEngine.strike(
				stone, vHit, localDir, baseEnergy, strikerMat, support, new Random(sw.random.nextLong()));

		Vec3d wp = KnappingStoneBlock.voxelToWorldPoint(facing, pos,
				(vHit.x() + 0.5) / 16.0, (vHit.y() + 0.5) / 16.0, (vHit.z() + 0.5) / 16.0);

		if (outcome.removedVoxels() > 0) {
			float pitch = outcome.kind() == FractureEngine.OutcomeKind.THIN_BLADE_SCAR ? 1.55f
					: (outcome.kind() == FractureEngine.OutcomeKind.CLEAN_FLAKE ? 1.35f : 0.82f);
			sw.playSound(null, pos, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 0.55f, pitch);
			sw.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, Blocks.COBBLESTONE.getDefaultState()),
					wp.x, wp.y, wp.z, Math.min(18, 4 + outcome.removedVoxels() / 2), 0.12, 0.08, 0.12, 0.04);

			for (DynamicStoneState flake : outcome.detachedFlakes()) {
				Block.dropStack(sw, pos.up(), DynamicStoneItem.create(flake));
			}

			VoxelRockGrid.GeometryMetrics m = stone.metrics();
			Formatting color = switch (outcome.kind()) {
				case THIN_BLADE_SCAR, CLEAN_FLAKE -> Formatting.GREEN;
				case HINGED_CHIP -> Formatting.YELLOW;
				case SPLIT_FAILURE -> Formatting.RED;
				default -> Formatting.GRAY;
			};
			player.sendMessage(Text.translatable("project_realism.knapping.outcome",
					outcome.kind().id,
					outcome.removedVoxels(),
					(int) Math.round(m.primaryEdgeAngleDeg()),
					String.format("%.2f", m.primaryEdgeRadiusMm()),
					String.format("%.1f", m.continuousEdgeCm())).formatted(color), true);
		} else {
			sw.playSound(null, pos, SoundEvents.BLOCK_STONE_HIT, SoundCategory.BLOCKS, 0.45f, 0.9f);
			player.sendMessage(Text.translatable("project_realism.knapping.glanced",
					(int) Math.round(outcome.localPlatformAngleDeg())).formatted(Formatting.GRAY), true);
		}

		// Striker rock wears slightly on contact (or sheds edge voxels if another DynamicStoneItem)
		if (!player.isCreative()) {
			if (striker.isOf(DynamicKnappingItems.DYNAMIC_STONE)) {
				DynamicStoneState ss = DynamicStoneItem.readState(striker);
				StoneInteractionPhysics.applyContactWear(ss, StoneInteractionPhysics.TargetMaterial.STONE_MINE, 95.0,
						new Random(sw.random.nextLong()));
				DynamicStoneItem.writeState(striker, ss);
			} else if (sw.random.nextFloat() < 0.06f) {
				striker.decrement(1);
			}
		}

		if (stone.metrics().voxelCount() <= 4) {
			pickedUpOrExhausted = true;
			sw.removeBlock(pos, false);
			return;
		}
		sync();
	}

	public void attachStick(PlayerEntity player, Hand hand, ItemStack stick, World world, BlockPos pos) {
		VoxelRockGrid.GeometryMetrics m = stone.metrics();
		HaftData.Orientation ori;
		if (!stone.haft.hafted) {
			// Initial orientation chosen naturally by posture: sneak = perpendicular chop; stand = inline/adze by look pitch
			if (player.isSneaking()) {
				ori = HaftData.Orientation.PERPENDICULAR_CHOP;
			} else if (player.getPitch() > 35.0f) {
				ori = HaftData.Orientation.TRANSVERSE_ADZE;
			} else {
				ori = HaftData.Orientation.INLINE;
			}
			if (!player.isCreative()) stick.decrement(1);
		} else {
			// Cycle mounting orientation cleanly on an already-fitted wooden handle
			HaftData.Orientation[] all = HaftData.Orientation.values();
			ori = all[(stone.haft.orientation.ordinal() + 1) % all.length];
		}

		stone.haft.attachHandle(ori, ori == HaftData.Orientation.INLINE ? 30.0 : 62.0,
				ori == HaftData.Orientation.INLINE ? 0.18 : 0.42, m);
		world.playSound(null, pos, SoundEvents.BLOCK_WOOD_PLACE, SoundCategory.BLOCKS, 0.65f, 1.15f);
		player.sendMessage(Text.translatable("project_realism.knapping.hafted",
				ori.id,
				(int) Math.round(stone.haft.socketFit * 100.0),
				(int) Math.round(stone.haft.edgeClearance * 100.0)).formatted(Formatting.GOLD), true);
		sync();
	}

	public void wrapBinding(PlayerEntity player, Hand hand, ItemStack binding, World world, BlockPos pos) {
		if (!stone.haft.hafted) {
			player.sendMessage(Text.translatable("project_realism.knapping.needs_stick_first").formatted(Formatting.YELLOW), true);
			return;
		}
		String mat = binding.isOf(AnatomyItems.SINEW) ? "sinew"
				: (binding.isOf(AnatomyItems.TENDON) ? "tendon"
				: (binding.isOf(PrimitiveItems.CORDAGE) ? "cordage" : "plant_fiber"));
		stone.haft.wrapBinding(mat, player.isSneaking() ? 0.96 : 0.80);
		if (!player.isCreative()) binding.decrement(1);
		world.playSound(null, pos, SoundEvents.ITEM_BUNDLE_INSERT, SoundCategory.BLOCKS, 0.7f, 1.0f);
		player.sendMessage(Text.translatable("project_realism.knapping.bound",
				mat,
				(int) Math.round(stone.haft.bindingTension * 100.0),
				(int) Math.round(stone.haft.slipResistance * 100.0)).formatted(Formatting.GREEN), true);
		sync();
	}

	public void pickUp(PlayerEntity player, World world, BlockPos pos) {
		ItemStack out = DynamicStoneItem.create(stone);
		pickedUpOrExhausted = true;
		if (!player.getInventory().insertStack(out)) {
			Block.dropStack(world, pos, out);
		}
		world.playSound(null, pos, SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.BLOCKS, 0.55f, 1.1f);
		world.removeBlock(pos, false);
	}

	public void dropOnBreak(World world, BlockPos pos) {
		if (!pickedUpOrExhausted && stone.metrics().voxelCount() > 4) {
			Block.dropStack(world, pos, DynamicStoneItem.create(stone));
		}
	}

	@Nullable
	private FractureEngine.VoxelHit raycastFromPlayer(Direction facing, PlayerEntity player, Vec3d hitWorld, BlockPos pos) {
		Vec3d eyeLocal = KnappingStoneBlock.worldToVoxelPoint(facing, pos, player.getEyePos());
		Vec3d hitLocal = KnappingStoneBlock.worldToVoxelPoint(facing, pos, hitWorld);
		Vec3d dir = hitLocal.subtract(eyeLocal).normalize();
		// Start slightly ahead of hitLocal toward eyeLocal so DDA hits exact outermost struck voxel
		Vec3d start = hitLocal.subtract(dir.multiply(0.18));
		FractureEngine.VoxelHit hit = FractureEngine.raycastVoxel(stone.grid, start, dir);
		if (hit != null) return hit;
		// Fallback raycast straight from eyeLocal if player stood ultra-close
		return FractureEngine.raycastVoxel(stone.grid, eyeLocal, dir);
	}

	private static FractureEngine.SupportCondition detectGroundSupport(World world, BlockPos below) {
		BlockState bs = world.getBlockState(below);
		if (bs.isOf(Blocks.ANVIL) || bs.isOf(Blocks.STONE) || bs.isOf(Blocks.COBBLESTONE)
				|| bs.isOf(Blocks.GRANITE) || bs.isOf(Blocks.ANDESITE) || bs.isOf(Blocks.DIORITE)
				|| bs.isOf(Blocks.DEEPSLATE) || bs.isOf(Blocks.BASALT) || bs.isOf(Blocks.OBSIDIAN)
				|| bs.isIn(BlockTags.BASE_STONE_OVERWORLD)) {
			return FractureEngine.SupportCondition.HARD_ANVIL;
		}
		if (bs.isOf(MaterialBlocks.WOODEN_WORKBENCH) || bs.isIn(BlockTags.LOGS) || bs.isIn(BlockTags.PLANKS)) {
			return FractureEngine.SupportCondition.FIRM_WOOD;
		}
		return FractureEngine.SupportCondition.SOFT_EARTH;
	}

	private void sync() {
		cachedShape = null;
		markDirty();
		if (world != null && !world.isClient) {
			BlockState st = getCachedState();
			world.updateListeners(pos, st, st, Block.NOTIFY_LISTENERS);
		}
	}

	@Override
	protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		super.writeNbt(nbt, registryLookup);
		nbt.put("Stone", stone.toNbt());
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		super.readNbt(nbt, registryLookup);
		if (nbt.contains("Stone")) {
			stone = DynamicStoneState.fromNbt(nbt.getCompound("Stone"));
			cachedShape = null;
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
