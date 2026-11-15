package com.projectrealism.wood;

import com.projectrealism.ProjectRealism;
import com.projectrealism.anatomy.AnatomyItems;
import com.projectrealism.primitive.PrimitiveItems;
import com.projectrealism.primitive.ToolCondition;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;

import java.util.Optional;

/**
 * In-world log processing: stripping branches from fallen trunks, cutting sections, splitting logs
 * into firewood, hewing rough planks, and planing seasoned rough planks into finished planks.
 */
public final class WoodEvents {
	private WoodEvents() {
	}

	public static void register() {
		UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
			if (hand != Hand.MAIN_HAND || player.isSpectator() || !ProjectRealism.config().logProcessing) {
				return ActionResult.PASS;
			}
			BlockPos pos = hit.getBlockPos();
			BlockState state = world.getBlockState(pos);
			ItemStack held = player.getStackInHand(hand);
			Block block = state.getBlock();

			// 1. Fallen Trunk: Axe removes branches first, then cuts trunk into fresh logs
			if (block instanceof FallenTrunkBlock trunk && isAxe(held)) {
				if (world instanceof ServerWorld server) {
					WoodSpecies sp = trunk.species();
					if (state.get(FallenTrunkBlock.BRANCHED)) {
						server.setBlockState(pos, state.with(FallenTrunkBlock.BRANCHED, false), Block.NOTIFY_ALL);
						server.playSound(null, pos, SoundEvents.ITEM_AXE_STRIP, SoundCategory.BLOCKS, 0.9f, 1.0f);
						server.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, state),
								pos.getX() + 0.5, pos.getY() + 0.4, pos.getZ() + 0.5, 8, 0.3, 0.2, 0.3, 0.04);
						if (server.random.nextFloat() < 0.65f) {
							Block.dropStack(server, pos, new ItemStack(Items.STICK));
						}
						if (server.random.nextFloat() < 0.45f) {
							Block.dropStack(server, pos, new ItemStack(PrimitiveItems.PLANT_FIBER));
						}
						wearWoodAxe(player, hand, held, sp.hardness() * 0.7f, server);
						player.sendMessage(Text.translatable("project_realism.wood.debranched", sp.displayName())
								.formatted(Formatting.YELLOW), true);
					} else {
						server.removeBlock(pos, false);
						server.playSound(null, pos, SoundEvents.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 0.9f, 0.9f);
						Block.dropStack(server, pos, new ItemStack(sp.freshLogItem(), 2));
						wearWoodAxe(player, hand, held, sp.hardness(), server);
						player.sendMessage(Text.translatable("project_realism.wood.cut_logs", sp.displayName())
								.formatted(Formatting.GOLD), true);
					}
				}
				return ActionResult.SUCCESS;
			}

			// 2. Fresh Log: Axe splits into firewood; Stone Knife hews into wet rough planks
			if (block instanceof FreshLogBlock fresh) {
				WoodSpecies sp = fresh.species();
				if (isAxe(held)) {
					if (world instanceof ServerWorld server) {
						server.removeBlock(pos, false);
						server.playSound(null, pos, SoundEvents.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 0.9f, 1.0f);
						for (int i = 0; i < 4; i++) {
							ItemStack split = new ItemStack(sp.splitWoodItem());
							ToolCondition.setDry(split, false);
							Block.dropStack(server, pos, split);
						}
						wearWoodAxe(player, hand, held, sp.hardness(), server);
						player.sendMessage(Text.translatable("project_realism.wood.split_logs", sp.displayName())
								.formatted(Formatting.YELLOW), true);
					}
					return ActionResult.SUCCESS;
				}
				if (isWoodShaper(held)) {
					if (world instanceof ServerWorld server) {
						server.removeBlock(pos, false);
						server.playSound(null, pos, SoundEvents.ITEM_AXE_STRIP, SoundCategory.BLOCKS, 0.9f, 1.15f);
						for (int i = 0; i < 2; i++) {
							ItemStack rough = new ItemStack(sp.roughPlankItem());
							ToolCondition.setDry(rough, false);
							Block.dropStack(server, pos, rough);
						}
						wearWoodShaper(player, hand, held, sp.hardness() * 0.8f, server);
						player.sendMessage(Text.translatable("project_realism.wood.made_rough_planks_wet", sp.displayName())
								.formatted(Formatting.GREEN), true);
					}
					return ActionResult.SUCCESS;
				}
			}

			// 3. Dry vanilla logs: Sneak + Axe splits into dry firewood; Stone Knife / Bone tool hews into dry rough planks
			if (WoodSpecies.isDryLog(block)) {
				Optional<WoodSpecies> maybe = WoodSpecies.fromBlock(block);
				if (maybe.isPresent()) {
					WoodSpecies sp = maybe.get();
					if (player.isSneaking() && isAxe(held)) {
						if (world instanceof ServerWorld server) {
							server.removeBlock(pos, false);
							server.playSound(null, pos, SoundEvents.BLOCK_WOOD_BREAK, SoundCategory.BLOCKS, 0.9f, 1.0f);
							for (int i = 0; i < 4; i++) {
								ItemStack split = new ItemStack(sp.splitWoodItem());
							ToolCondition.setDry(split, true);
							Block.dropStack(server, pos, split);
						}
						wearWoodAxe(player, hand, held, sp.hardness(), server);
							player.sendMessage(Text.translatable("project_realism.wood.split_logs", sp.displayName())
									.formatted(Formatting.YELLOW), true);
						}
						return ActionResult.SUCCESS;
					}
					if (isWoodShaper(held)) {
						if (world instanceof ServerWorld server) {
							server.removeBlock(pos, false);
							server.playSound(null, pos, SoundEvents.ITEM_AXE_STRIP, SoundCategory.BLOCKS, 0.9f, 1.15f);
							for (int i = 0; i < 2; i++) {
								ItemStack rough = new ItemStack(sp.roughPlankItem());
								ToolCondition.setDry(rough, true);
								Block.dropStack(server, pos, rough);
							}
							wearWoodShaper(player, hand, held, sp.hardness() * 0.8f, server);
							player.sendMessage(Text.translatable("project_realism.wood.made_rough_planks_dry", sp.displayName())
									.formatted(Formatting.GOLD), true);
						}
						return ActionResult.SUCCESS;
					}
				}
			}

			// 4. Split Wood: Stone Knife or Bone Blade/Chisel hews into rough plank (inherits moisture)
			if (block instanceof SplitWoodBlock split && isWoodShaper(held)) {
				if (world instanceof ServerWorld server) {
					boolean isDry = state.get(SplitWoodBlock.MOISTURE) == 0;
					WoodSpecies sp = split.species();
					server.removeBlock(pos, false);
					server.playSound(null, pos, SoundEvents.ITEM_AXE_STRIP, SoundCategory.BLOCKS, 0.8f, 1.2f);
					ItemStack rough = new ItemStack(sp.roughPlankItem());
					ToolCondition.setDry(rough, isDry);
					Block.dropStack(server, pos, rough);
					wearWoodShaper(player, hand, held, sp.hardness() * 0.6f, server);
				}
				return ActionResult.SUCCESS;
			}

			// 5. Rough Plank: Stone Knife or Bone Blade/Chisel planes dry rough plank into finished vanilla plank
			if (block instanceof RoughPlankBlock rough && isWoodShaper(held)) {
				WoodSpecies sp = rough.species();
				int moisture = state.get(RoughPlankBlock.MOISTURE);
				if (moisture > 0) {
					if (!world.isClient) {
						player.sendMessage(Text.translatable("project_realism.wood.still_wet",
								sp.displayName(), moisture * 33).formatted(Formatting.RED), true);
					}
					return ActionResult.SUCCESS;
				}
				if (world instanceof ServerWorld server) {
					server.removeBlock(pos, false);
					server.playSound(null, pos, SoundEvents.ITEM_AXE_STRIP, SoundCategory.BLOCKS, 0.8f, 1.3f);
					Block.dropStack(server, pos, new ItemStack(sp.vanillaPlanks(), 1));
					wearWoodShaper(player, hand, held, sp.hardness() * 0.5f, server);
					player.sendMessage(Text.translatable("project_realism.wood.planed_plank", sp.displayName())
							.formatted(Formatting.GOLD), true);
				}
				return ActionResult.SUCCESS;
			}

			return ActionResult.PASS;
		});

		// Breaking a FallenTrunkBlock directly with an axe strips branches first if still branched,
		// otherwise cuts it into fresh log sections.
		PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, entity) -> {
			if (!(world instanceof ServerWorld server) || player.isCreative() || player.isSpectator()) {
				return true;
			}
			if (state.getBlock() instanceof FallenTrunkBlock trunk) {
				ItemStack held = player.getMainHandStack();
				if (!isAxe(held)) {
					player.sendMessage(Text.translatable("project_realism.gate.axe").formatted(Formatting.RED), true);
					return false;
				}
				WoodSpecies sp = trunk.species();
				if (state.get(FallenTrunkBlock.BRANCHED)) {
					server.setBlockState(pos, state.with(FallenTrunkBlock.BRANCHED, false), Block.NOTIFY_ALL);
					server.playSound(null, pos, SoundEvents.ITEM_AXE_STRIP, SoundCategory.BLOCKS, 0.9f, 1.0f);
					Block.dropStack(server, pos, new ItemStack(Items.STICK));
					ToolCondition.applyWear(player, Hand.MAIN_HAND, held, sp.hardness() * 0.7f, server, true);
					player.sendMessage(Text.translatable("project_realism.wood.debranched", sp.displayName())
							.formatted(Formatting.YELLOW), true);
					return false;
				}
				Block.dropStack(server, pos, new ItemStack(sp.freshLogItem(), 2));
				ToolCondition.applyWear(player, Hand.MAIN_HAND, held, sp.hardness(), server, false);
				return true;
			}
			return true;
		});
	}

	private static boolean isAxe(ItemStack stack) {
		if (stack.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE)) {
			var s = com.projectrealism.knapping.DynamicStoneItem.readState(stack);
			return com.projectrealism.knapping.StoneInteractionPhysics.evaluate(
					s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.WOOD_CHOP, 140.0)
					.effectiveness() >= 0.36;
		}
		if (stack.isOf(com.projectrealism.metallurgy.MetallurgyItems.DYNAMIC_METAL)) {
			var s = com.projectrealism.metallurgy.DynamicMetalItem.readState(stack);
			return com.projectrealism.metallurgy.ContactPhysics.evaluateMetal(
					s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.WOOD_CHOP, 150.0)
					.effectiveness() >= 0.36;
		}
		return !stack.isEmpty() && (stack.isIn(ItemTags.AXES) || stack.getItem() instanceof AxeItem);
	}

	private static boolean isWoodShaper(ItemStack stack) {
		if (stack.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE)) {
			var s = com.projectrealism.knapping.DynamicStoneItem.readState(stack);
			return com.projectrealism.knapping.StoneInteractionPhysics.evaluate(
					s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.WOOD_SHAVE, 110.0)
					.effectiveness() >= 0.32;
		}
		if (stack.isOf(com.projectrealism.metallurgy.MetallurgyItems.DYNAMIC_METAL)) {
			var s = com.projectrealism.metallurgy.DynamicMetalItem.readState(stack);
			return com.projectrealism.metallurgy.ContactPhysics.evaluateMetal(
					s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.WOOD_SHAVE, 110.0)
					.effectiveness() >= 0.32;
		}
		return stack.isOf(PrimitiveItems.STONE_KNIFE)
				|| stack.isOf(AnatomyItems.BONE_BLADE)
				|| stack.isOf(AnatomyItems.BONE_CHISEL);
	}

	private static void wearWoodAxe(PlayerEntity player, Hand hand, ItemStack stack,
									float hardness, ServerWorld server) {
		if (stack.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE)) {
			var s = com.projectrealism.knapping.DynamicStoneItem.readState(stack);
			com.projectrealism.knapping.StoneInteractionPhysics.applyContactWear(
					s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.WOOD_CHOP,
					140.0 * hardness, new java.util.Random(server.random.nextLong()));
			com.projectrealism.knapping.DynamicStoneItem.writeState(stack, s);
		} else if (stack.isOf(com.projectrealism.metallurgy.MetallurgyItems.DYNAMIC_METAL)) {
			var s = com.projectrealism.metallurgy.DynamicMetalItem.readState(stack);
			com.projectrealism.metallurgy.ContactPhysics.applyMetalWear(
					s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.WOOD_CHOP,
					140.0 * hardness, new java.util.Random(server.random.nextLong()));
			com.projectrealism.metallurgy.DynamicMetalItem.writeState(stack, s);
		} else {
			ToolCondition.applyWear(player, hand, stack, hardness, server, true);
		}
	}

	private static void wearWoodShaper(PlayerEntity player, Hand hand, ItemStack stack,
									   float hardness, ServerWorld server) {
		if (stack.isOf(com.projectrealism.knapping.DynamicKnappingItems.DYNAMIC_STONE)) {
			var s = com.projectrealism.knapping.DynamicStoneItem.readState(stack);
			com.projectrealism.knapping.StoneInteractionPhysics.applyContactWear(
					s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.WOOD_SHAVE,
					110.0 * hardness, new java.util.Random(server.random.nextLong()));
			com.projectrealism.knapping.DynamicStoneItem.writeState(stack, s);
		} else if (stack.isOf(com.projectrealism.metallurgy.MetallurgyItems.DYNAMIC_METAL)) {
			var s = com.projectrealism.metallurgy.DynamicMetalItem.readState(stack);
			com.projectrealism.metallurgy.ContactPhysics.applyMetalWear(
					s, com.projectrealism.knapping.StoneInteractionPhysics.TargetMaterial.WOOD_SHAVE,
					110.0 * hardness, new java.util.Random(server.random.nextLong()));
			com.projectrealism.metallurgy.DynamicMetalItem.writeState(stack, s);
		} else if (ToolCondition.isPrimitiveTool(stack)) {
			ToolCondition.applyWear(player, hand, stack, hardness, server, true);
		} else if (stack.isDamageable() && !player.isCreative()) {
			stack.damage(1, player, hand == Hand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
		}
	}
}
