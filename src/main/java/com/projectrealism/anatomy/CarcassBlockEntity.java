package com.projectrealism.anatomy;

import com.projectrealism.material.HideSurface;
import com.projectrealism.material.MaterialItems;
import com.projectrealism.material.RawHideItem;
import com.projectrealism.primitive.PrimitiveItems;
import com.projectrealism.primitive.ToolCondition;
import com.projectrealism.thermo.AirGrid;
import com.projectrealism.thermo.ThermalConstants;
import com.projectrealism.thermo.ThermalWorldState;
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

import java.util.Random;

/**
 * Persistent BlockEntity holding a {@link CarcassState}, ticking thermodynamic cooling, rigor,
 * insect activity, and decomposition at 1 Hz and executing region-specific anatomical harvesting.
 */
public class CarcassBlockEntity extends BlockEntity {
	private CarcassState carcass = new CarcassState();
	private boolean pickedUpOrExhausted = false;
	private int tickCount = 0;

	public CarcassBlockEntity(BlockPos pos, BlockState state) {
		super(AnatomyBlocks.CARCASS_ENTITY, pos, state);
	}

	public CarcassState carcass() {
		return carcass;
	}

	public void setCarcass(CarcassState state) {
		this.carcass = state != null ? state : new CarcassState();
		sync();
	}

	public static void tick(World world, BlockPos pos, BlockState state, CarcassBlockEntity be) {
		if (!(world instanceof ServerWorld sw)) return;
		be.tickCount++;
		if (be.tickCount % 20 != 0) return;

		ThermalWorldState thermo = ThermalWorldState.get(sw);
		double ambK = thermo.ambientTempAt(sw, pos);
		double rh = thermo.relativeHumidityAt(sw, pos);
		AirGrid.AirCell air = thermo.airGrid().getReadOnly(AirGrid.CellPos.fromBlock(pos), ambK);
		boolean rain = sw.isRaining() && sw.isSkyVisible(pos.up());
		boolean sheltered = !sw.isSkyVisible(pos.up());

		// Nearby fire radiant heat & protective smoke reducing insect flies
		double radW = 0.0;
		boolean smoked = air.smoke > 0.12;
		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				BlockPos src = pos.add(dx, 0, dz);
				double t = thermo.blockSurfaceTempK(sw, src);
				if (t > 400.0) radW += (t - 400.0) * 0.4;
			}
		}

		be.carcass.stepEnvironment(Math.max(ambK, air.temperatureK), rh,
				air.speedMS() + (sheltered ? 0.15 : 0.45),
				Math.min(400.0, radW), rain, sheltered || smoked, 1.0);

		if (be.carcass.bleedIncised && be.carcass.remainingBloodFrac > 0.12 && sw.random.nextFloat() < 0.45f) {
			sw.spawnParticles(ParticleTypes.DRIPPING_WATER,
					pos.getX() + 0.5, pos.getY() + 0.25, pos.getZ() + 0.5, 1, 0.1, 0.02, 0.1, 0.0);
		}
		if (be.carcass.insectActivity > 0.40 && sw.random.nextFloat() < 0.35f) {
			sw.spawnParticles(ParticleTypes.ASH,
					pos.getX() + 0.5, pos.getY() + 0.45, pos.getZ() + 0.5, 2, 0.22, 0.12, 0.22, 0.01);
		}
		be.sync();
	}

	public void shearCoat(PlayerEntity player, Hand hand, ItemStack shears, World world, BlockPos pos) {
		if (carcass.woolRemainingKg <= 0.02) {
			player.sendMessage(Text.translatable("project_realism.carcass.no_wool").formatted(Formatting.GRAY), true);
			return;
		}
		double taken = carcass.shearWoolKg();
		ItemStack wool = new ItemStack(AnatomyItems.RAW_WOOL, Math.max(1, (int) Math.round(taken * 2.0)));
		giveOrDrop(player, world, pos, wool);
		if (!player.isCreative()) {
			shears.damage(1, player, hand == Hand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
		}
		world.playSound(null, pos, SoundEvents.ENTITY_SHEEP_SHEAR, SoundCategory.BLOCKS, 0.7f, 1.0f);
		player.sendMessage(Text.translatable("project_realism.carcass.sheared",
				String.format("%.2f", taken)).formatted(Formatting.AQUA), true);
		sync();
	}

	public void processRegion(CarcassState.Region region, PlayerEntity player, Hand hand,
							  ItemStack tool, World world, BlockPos pos) {
		if (!(world instanceof ServerWorld sw)) return;
		double sharp = getBladeSharpness(tool);
		boolean heavy = tool.isOf(PrimitiveItems.STONE_AXE) || tool.isOf(AnatomyItems.BONE_CHISEL)
				|| (tool.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE)
				&& com.projectrealism.knapping.StoneInteractionPhysics.evaluate(
						com.projectrealism.knapping.DynamicStoneItem.readState(tool),
						com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.BONE_CHISEL, 120.0)
						.effectiveness() >= 0.45
				&& com.projectrealism.knapping.DynamicStoneItem.readState(tool).metrics().massKg() >= 1.1)
				|| (tool.isOf(com.projectrealism.metallurgy.MetallurgyItems.DYNAMIC_METAL)
				&& com.projectrealism.metallurgy.ContactPhysics.evaluateMetal(
						com.projectrealism.metallurgy.DynamicMetalItem.readState(tool),
						com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.BONE_CHISEL, 120.0)
						.effectiveness() >= 0.45
				&& com.projectrealism.metallurgy.DynamicMetalItem.readState(tool).totalMassKg() >= 0.9);
		boolean scraper = tool.isOf(MaterialItems.STONE_SCRAPER);

		switch (region) {
			case HEAD_NECK -> {
				if (carcass.remainingBloodFrac > 0.20 && !carcass.bleedIncised && !heavy && !scraper) {
					carcass.stickNeckToBleed();
					sw.playSound(null, pos, SoundEvents.ITEM_BOTTLE_EMPTY, SoundCategory.BLOCKS, 0.5f, 0.85f);
					player.sendMessage(Text.translatable("project_realism.carcass.bled",
							(int) Math.round(carcass.remainingBloodFrac * 100.0)).formatted(Formatting.AQUA), true);
				} else if (carcass.hornsRemaining > 0 && (heavy || sharp > 0.55)) {
					carcass.removeOneHorn();
					giveOrDrop(player, world, pos, new ItemStack(AnatomyItems.ANIMAL_HORN, 1));
					sw.playSound(null, pos, SoundEvents.BLOCK_BONE_BLOCK_BREAK, SoundCategory.BLOCKS, 0.7f, 0.9f);
					player.sendMessage(Text.translatable("project_realism.carcass.horn").formatted(Formatting.GREEN), true);
				} else if (carcass.remainingBloodFrac > 0.15 && !heavy && !scraper) {
					carcass.stickNeckToBleed();
					sw.playSound(null, pos, SoundEvents.ITEM_BOTTLE_EMPTY, SoundCategory.BLOCKS, 0.5f, 0.85f);
					player.sendMessage(Text.translatable("project_realism.carcass.bled",
							(int) Math.round(carcass.remainingBloodFrac * 100.0)).formatted(Formatting.AQUA), true);
				} else {
					harvestBoneOrFallback(player, sw, pos);
				}
			}
			case COAT_TORSO -> {
				if (carcass.woolRemainingKg > 0.02) {
					shearCoat(player, hand, tool, sw, pos);
				} else if (carcass.hidePresent) {
					HideSurface hs = carcass.skinStep(sharp, new Random(sw.random.nextLong()));
					sw.playSound(null, pos, SoundEvents.ITEM_ARMOR_EQUIP_LEATHER.value(), SoundCategory.BLOCKS, 0.6f, 0.95f);
					if (hs != null) {
						giveOrDrop(player, sw, pos, RawHideItem.create(hs));
						player.sendMessage(Text.translatable("project_realism.carcass.skinned",
								carcass.species.id).formatted(Formatting.GREEN), true);
					} else {
						player.sendMessage(Text.translatable("project_realism.carcass.skinning",
								(int) Math.round(carcass.skinProgress * 100.0)).formatted(Formatting.AQUA), true);
					}
				} else if (carcass.fatRemainingKg > 0.05) {
					double fat = carcass.flenseFatKg(sharp);
					giveOrDrop(player, sw, pos, new ItemStack(AnatomyItems.ANIMAL_FAT, Math.max(1, (int) Math.round(fat * 2.0))));
					sw.playSound(null, pos, SoundEvents.BLOCK_SLIME_BLOCK_STEP, SoundCategory.BLOCKS, 0.55f, 1.0f);
					player.sendMessage(Text.translatable("project_realism.carcass.flensed",
							String.format("%.2f", fat)).formatted(Formatting.GOLD), true);
				} else if (carcass.fattyMeatRemainingKg > 0.04) {
					MeatCutData cut = carcass.butcherMuscle(MeatCutData.CutKind.FATTY_RIB, sharp);
					if (cut != null) {
						giveOrDrop(player, sw, pos, AnatomyMeatItem.create(cut));
						sw.playSound(null, pos, SoundEvents.ENTITY_SHEEP_SHEAR, SoundCategory.BLOCKS, 0.6f, 0.9f);
						player.sendMessage(Text.translatable("project_realism.carcass.butchered",
								cut.kind.id, String.format("%.2f", cut.massKg)).formatted(Formatting.GREEN), true);
					}
				} else {
					harvestBoneOrFallback(player, sw, pos);
				}
			}
			case VISCERA_CAVITY -> {
				if (carcass.hidePresent && !carcass.eviscerated) {
					// Opening belly incision
					carcass.skinProgress = Math.min(0.95, carcass.skinProgress + 0.15);
				}
				CarcassState.EviscerateOutcome out = carcass.processViscera(sharp, new Random(sw.random.nextLong()));
				if (out.newlyOpened) {
					sw.playSound(null, pos, SoundEvents.BLOCK_SLIME_BLOCK_STEP, SoundCategory.BLOCKS, 0.65f, 0.8f);
					player.sendMessage(Text.translatable(
							out.rupturedGut ? "project_realism.carcass.gut_ruptured" : "project_realism.carcass.eviscerated")
							.formatted(out.rupturedGut ? Formatting.RED : Formatting.AQUA), true);
				} else if (out.harvestedOrgan != null) {
					giveOrDrop(player, sw, pos, AnatomyMeatItem.create(out.harvestedOrgan));
					sw.playSound(null, pos, SoundEvents.ENTITY_ITEM_PICKUP, SoundCategory.BLOCKS, 0.55f, 0.85f);
					player.sendMessage(Text.translatable("project_realism.carcass.organ",
							out.harvestedOrgan.kind.id).formatted(Formatting.GREEN), true);
				} else {
					harvestBoneOrFallback(player, sw, pos);
				}
			}
			case LOIN_RIBS -> {
				if (carcass.hidePresent) {
					processRegion(CarcassState.Region.COAT_TORSO, player, hand, tool, sw, pos);
					return;
				}
				if (carcass.sinewRemainingKg > 0.02 && !heavy) {
					double sin = carcass.extractSinewKg();
					giveOrDrop(player, sw, pos, new ItemStack(AnatomyItems.SINEW, Math.max(1, (int) Math.round(sin * 3.0))));
					sw.playSound(null, pos, SoundEvents.ENTITY_LEASH_KNOT_PLACE, SoundCategory.BLOCKS, 0.6f, 1.15f);
					player.sendMessage(Text.translatable("project_realism.carcass.sinew",
							String.format("%.2f", sin)).formatted(Formatting.AQUA), true);
				} else if (carcass.primeMeatRemainingKg > 0.04) {
					MeatCutData cut = carcass.butcherMuscle(MeatCutData.CutKind.PRIME_LOIN, sharp);
					if (cut != null) {
						giveOrDrop(player, sw, pos, AnatomyMeatItem.create(cut));
						sw.playSound(null, pos, SoundEvents.ENTITY_SHEEP_SHEAR, SoundCategory.BLOCKS, 0.6f, 1.0f);
						player.sendMessage(Text.translatable("project_realism.carcass.butchered",
								cut.kind.id, String.format("%.2f", cut.massKg)).formatted(Formatting.GREEN), true);
					}
				} else {
					harvestBoneOrFallback(player, sw, pos);
				}
			}
			case HAUNCH_LIMB -> {
				if (carcass.tendonRemainingKg > 0.02 && !heavy) {
					double ten = carcass.extractTendonKg();
					giveOrDrop(player, sw, pos, new ItemStack(AnatomyItems.TENDON, Math.max(1, (int) Math.round(ten * 3.0))));
					sw.playSound(null, pos, SoundEvents.ENTITY_LEASH_KNOT_PLACE, SoundCategory.BLOCKS, 0.6f, 1.25f);
					player.sendMessage(Text.translatable("project_realism.carcass.tendon",
							String.format("%.2f", ten)).formatted(Formatting.AQUA), true);
				} else if (carcass.toughMeatRemainingKg > 0.04) {
					MeatCutData cut = carcass.butcherMuscle(MeatCutData.CutKind.TOUGH_HAUNCH, sharp);
					if (cut != null) {
						giveOrDrop(player, sw, pos, AnatomyMeatItem.create(cut));
						sw.playSound(null, pos, SoundEvents.ENTITY_SHEEP_SHEAR, SoundCategory.BLOCKS, 0.6f, 0.85f);
						player.sendMessage(Text.translatable("project_realism.carcass.butchered",
								cut.kind.id, String.format("%.2f", cut.massKg)).formatted(Formatting.GREEN), true);
					}
				} else {
					harvestBoneOrFallback(player, sw, pos);
				}
			}
		}

		wearTool(player, hand, tool, sw);

		if (carcass.isCompletelyExhausted()) {
			pickedUpOrExhausted = true;
			sw.removeBlock(pos, false);
			return;
		}
		sync();
	}

	private void harvestBoneOrFallback(PlayerEntity player, ServerWorld world, BlockPos pos) {
		BoneData bone = carcass.disarticulateOneBone();
		if (bone != null) {
			giveOrDrop(player, world, pos, RawBoneItem.create(bone));
			world.playSound(null, pos, SoundEvents.BLOCK_BONE_BLOCK_BREAK, SoundCategory.BLOCKS, 0.7f, 1.1f);
			player.sendMessage(Text.translatable("project_realism.carcass.bone",
					bone.size.id, bone.boneType.id).formatted(Formatting.GOLD), true);
		} else {
			player.sendMessage(Text.translatable("project_realism.carcass.nothing_left").formatted(Formatting.GRAY), true);
		}
	}

	private static double getBladeSharpness(ItemStack tool) {
		if (tool.isOf(PrimitiveItems.STONE_KNIFE) || tool.isOf(PrimitiveItems.STONE_AXE)) {
			return ToolCondition.getSharpness(tool) / 100.0;
		}
		if (tool.isOf(MaterialItems.STONE_SCRAPER)) {
			return MaterialItems.StoneScraperItem.getSharpness(tool) / 100.0;
		}
		if (tool.isOf(AnatomyItems.BONE_BLADE) || tool.isOf(AnatomyItems.BONE_CHISEL)) {
			return AnatomyItems.BoneToolItem.getWorkQualityPct(tool) / 100.0;
		}
		if (tool.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE)) {
			var s = com.projectrealism.knapping.DynamicStoneItem.readState(tool);
			return Math.max(0.15, Math.min(1.0, com.projectrealism.knapping.StoneInteractionPhysics.evaluate(
					s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.FLESH_SLICE, 85.0)
					.effectiveness()));
		}
		if (tool.isOf(com.projectrealism.metallurgy.MetallurgyItems.DYNAMIC_METAL)) {
			var s = com.projectrealism.metallurgy.DynamicMetalItem.readState(tool);
			return Math.max(0.15, Math.min(1.2, com.projectrealism.metallurgy.ContactPhysics.evaluateMetal(
					s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.FLESH_SLICE, 85.0)
					.effectiveness()));
		}
		return 0.75;
	}

	private static void wearTool(PlayerEntity player, Hand hand, ItemStack tool, ServerWorld sw) {
		if (player.isCreative()) return;
		if (tool.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE)) {
			var s = com.projectrealism.knapping.DynamicStoneItem.readState(tool);
			com.projectrealism.knapping.StoneInteractionPhysics.applyContactWear(
					s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.FLESH_SLICE,
					85.0, new Random(sw.random.nextLong()));
			com.projectrealism.knapping.DynamicStoneItem.writeState(tool, s);
		} else if (tool.isOf(com.projectrealism.metallurgy.MetallurgyItems.DYNAMIC_METAL)) {
			var s = com.projectrealism.metallurgy.DynamicMetalItem.readState(tool);
			com.projectrealism.metallurgy.ContactPhysics.applyMetalWear(
					s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.FLESH_SLICE,
					85.0, new Random(sw.random.nextLong()));
			com.projectrealism.metallurgy.DynamicMetalItem.writeState(tool, s);
		} else if (tool.isOf(PrimitiveItems.STONE_KNIFE) || tool.isOf(PrimitiveItems.STONE_AXE)) {
			ToolCondition.applyWear(player, hand, tool, 0.75f, sw);
		} else {
			tool.damage(1, player, hand == Hand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
		}
	}

	public void pickUpWhole(PlayerEntity player, World world, BlockPos pos) {
		pickedUpOrExhausted = true;
		ItemStack item = CarcassItem.create(carcass);
		giveOrDrop(player, world, pos, item);
		world.removeBlock(pos, false);
	}

	public void dropOnBreak(World world, BlockPos pos) {
		if (!pickedUpOrExhausted && !carcass.isCompletelyExhausted()) {
			Block.dropStack(world, pos, CarcassItem.create(carcass));
		}
	}

	public Text inspectText(CarcassState.Region region) {
		return Text.translatable("project_realism.carcass.inspect",
				carcass.species.id,
				region.key,
				String.format("%.1f", carcass.currentTotalMassKg()),
				(int) Math.round(ThermalConstants.toCelsius(carcass.bodyTempK)),
				(int) Math.round(carcass.remainingBloodFrac * 100.0),
				(int) Math.round(carcass.spoilageFrac * 100.0),
				carcass.bonesRemaining.size())
				.formatted(carcass.spoilageFrac > 0.45 ? Formatting.RED : Formatting.AQUA);
	}

	private static void giveOrDrop(PlayerEntity player, World world, BlockPos pos, ItemStack st) {
		if (!player.getInventory().insertStack(st)) {
			Block.dropStack(world, pos.up(), st);
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
		nbt.put("Carcass", carcass.toNbt());
	}

	@Override
	protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registryLookup) {
		super.readNbt(nbt, registryLookup);
		if (nbt.contains("Carcass")) {
			carcass = CarcassState.fromNbt(nbt.getCompound("Carcass"));
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
