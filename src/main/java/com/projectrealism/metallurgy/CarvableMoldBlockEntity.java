package com.projectrealism.metallurgy;

import com.projectrealism.knapping.KnappingStoneBlock;
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
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

/**
 * BlockEntity holding a 16x16x16 {@link MoldGrid} and an optional solidified casting awaiting extraction.
 */
public class CarvableMoldBlockEntity extends BlockEntity {
	private MoldGrid mold = MoldGrid.createBlank(MoldGrid.MoldMaterial.FIRED_CLAY);
	private DynamicMetalState solidifiedCasting = null;

	public CarvableMoldBlockEntity(BlockPos pos, BlockState state) {
		super(MetallurgyBlocks.CARVABLE_MOLD_ENTITY, pos, state);
	}

	public MoldGrid mold() {
		return mold;
	}

	public boolean hasSolidifiedCasting() {
		return solidifiedCasting != null;
	}

	public DynamicMetalState casting() {
		return solidifiedCasting;
	}

	public void setMold(MoldGrid m) {
		this.mold = m != null ? m : MoldGrid.createBlank(MoldGrid.MoldMaterial.FIRED_CLAY);
		sync();
	}

	public void inspect(PlayerEntity player) {
		if (solidifiedCasting != null) {
			player.sendMessage(Text.literal(String.format("Cast [%s] ready inside mold (%.2f kg) — sneak + bare hand to extract.",
					solidifiedCasting.alloy.describeComposition(), solidifiedCasting.totalMassKg())).formatted(Formatting.GREEN), true);
			return;
		}
		int cav = mold.countCavityVoxels();
		player.sendMessage(Text.literal(String.format("Mold (%s) | Cavity: %d vox (~%.2f kg Cu) | %s",
				mold.material.id, cav, cav * 0.00085 * 8.9,
				mold.closedCavity ? (mold.vented ? "Closed & Vented" : "Closed [UNVENTED!]") : "Open Cavity"))
				.formatted(Formatting.AQUA), true);
	}

	public void carveAt(Direction facing, PlayerEntity player, Vec3d hitWorld, BlockPos pos, World world) {
		if (solidifiedCasting != null) {
			player.sendMessage(Text.literal("Extract solidified casting before carving further.").formatted(Formatting.YELLOW), true);
			return;
		}
		Vec3d local = KnappingStoneBlock.worldToVoxelPoint(facing, pos, hitWorld);
		Vec3d dir = KnappingStoneBlock.worldDirToVoxelDir(facing, player.getRotationVec(1.0f)).normalize();

		int cx = Math.max(1, Math.min(14, (int) Math.floor(local.x * 16.0)));
		int cy = Math.max(2, Math.min(11, (int) Math.floor(local.y * 16.0)));
		int cz = Math.max(1, Math.min(14, (int) Math.floor(local.z * 16.0)));

		// Step slightly into existing cavity wall so subsequent clicks deepen downward cleanly
		for (int step = 0; step < 6; step++) {
			int px = Math.max(1, Math.min(14, (int) Math.floor((local.x + dir.x * step * 0.06) * 16.0)));
			int py = Math.max(2, Math.min(11, (int) Math.floor((local.y + dir.y * step * 0.06) * 16.0)));
			int pz = Math.max(1, Math.min(14, (int) Math.floor((local.z + dir.z * step * 0.06) * 16.0)));
			if (!mold.isCavity(px, py, pz)) {
				cx = px; cy = py; cz = pz;
				break;
			}
		}

		int radius = player.isSneaking() ? 0 : 1;
		int carved = 0;
		if (radius == 0) {
			if (!mold.isCavity(cx, cy, cz)) {
				mold.setCavity(cx, cy, cz, true);
				carved = 1;
			}
		} else {
			carved = mold.carveBrush(cx, cy, cz, 1);
		}
		mold.checkVenting();
		world.playSound(null, pos, SoundEvents.BLOCK_GRAVEL_BREAK, SoundCategory.BLOCKS, 0.45f, 1.35f);
		player.sendMessage(Text.literal(String.format("Carved %d cavity cells (total %d vox)", carved, mold.countCavityVoxels()))
				.formatted(Formatting.GOLD), true);
		sync();
	}

	public void toggleCap(PlayerEntity player, World world, BlockPos pos) {
		mold.closedCavity = !mold.closedCavity;
		mold.checkVenting();
		world.playSound(null, pos, SoundEvents.BLOCK_STONE_PLACE, SoundCategory.BLOCKS, 0.5f, 1.0f);
		player.sendMessage(Text.literal(mold.closedCavity
				? (mold.vented ? "Closed 2-part mold (sprue + vent detected)." : "Closed 2-part mold (carve a 2nd top vent opening!)")
				: "Opened mold top.").formatted(Formatting.YELLOW), true);
		sync();
	}

	public void pourFromCrucible(CrucibleState crucible, PlayerEntity player, World world, BlockPos pos) {
		if (solidifiedCasting != null) {
			player.sendMessage(Text.literal("Extract previous casting first!").formatted(Formatting.RED), true);
			return;
		}
		if (mold.countCavityVoxels() < 6) {
			player.sendMessage(Text.literal("Carve a deeper mold cavity first.").formatted(Formatting.YELLOW), true);
			return;
		}
		CastingEngine.CastResult res = CastingEngine.pourAndSolidify(crucible, mold, 0.95);
		if (!res.success() || res.casting() == null) {
			player.sendMessage(Text.literal("Metal too cold or insufficient volume to pour into cavity!")
					.formatted(Formatting.RED), true);
			return;
		}
		solidifiedCasting = res.casting();
		if (world instanceof ServerWorld sw) {
			sw.playSound(null, pos, SoundEvents.BLOCK_LAVA_EXTINGUISH, SoundCategory.BLOCKS, 0.7f, 1.15f);
			sw.spawnParticles(ParticleTypes.SMOKE, pos.getX() + 0.5, pos.getY() + 0.85, pos.getZ() + 0.5,
					10, 0.2, 0.15, 0.2, 0.03);
		}
		String warnings = (res.shortPour() ? " [Short Pour]" : "")
				+ (res.coldShut() ? " [Cold Shut]" : "")
				+ (res.gasDefect() ? " [Gas Porosity]" : "");
		player.sendMessage(Text.literal(String.format("Cast %.0f%% cavity (%.2f kg %s)%s — sneak bare-hand to extract!",
						res.fillFraction() * 100.0, res.usedMetalKg(), solidifiedCasting.alloy.describeComposition(), warnings))
				.formatted(warnings.isEmpty() ? Formatting.GREEN : Formatting.YELLOW), true);
		sync();
	}

	public void extractCasting(PlayerEntity player, World world, BlockPos pos) {
		if (solidifiedCasting == null) return;
		if (solidifiedCasting.temperatureK > 420.0 && !DynamicMetalItem.hasHeatProtection(player)) {
			player.damage(world.getDamageSources().hotFloor(), 1.0f);
			player.sendMessage(Text.literal("Hot casting! Wait to cool or use Iron Tongs / Leather.").formatted(Formatting.RED), true);
			return;
		}
		ItemStack item = DynamicMetalItem.create(solidifiedCasting);
		solidifiedCasting = null;
		if (!player.getInventory().insertStack(item)) {
			Block.dropStack(world, pos.up(), item);
		}
		world.playSound(null, pos, SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.PLAYERS, 0.55f, 1.0f);
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
		nbt.put("Mold", mold.toNbt());
		if (solidifiedCasting != null) {
			nbt.put("Cast", solidifiedCasting.toNbt());
		}
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		super.readNbt(nbt, registryLookup);
		if (nbt.contains("Mold")) mold = MoldGrid.fromNbt(nbt.getCompound("Mold"));
		solidifiedCasting = nbt.contains("Cast") ? DynamicMetalState.fromNbt(nbt.getCompound("Cast")) : null;
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
