package com.projectrealism.primitive;

import com.projectrealism.ProjectRealism;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.math.random.Random;

import java.util.List;

/**
 * Tracks edge sharpness, binding integrity, and stone-head fracture state on primitive tools, plus
 * dryness state on processed wood items via {@link DataComponentTypes#CUSTOM_DATA}.
 */
public final class ToolCondition {
	private static final String KEY_SHARPNESS = "Sharpness";
	private static final String KEY_BINDING = "Binding";
	private static final String KEY_HEAD_DAMAGE = "HeadDamage";
	private static final String KEY_CORDAGE = "CordageBound";
	private static final String KEY_DRY = "Dry";

	public enum SharpenResult {
		SHARPENED,
		ALREADY_SHARP,
		SHATTERED
	}

	private ToolCondition() {
	}

	public static boolean isPrimitiveTool(ItemStack stack) {
		return stack.isOf(PrimitiveItems.STONE_AXE)
				|| stack.isOf(PrimitiveItems.STONE_KNIFE)
				|| stack.isOf(PrimitiveItems.STONE_PICKAXE);
	}

	public static void initTool(ItemStack stack, boolean cordageBound) {
		if (stack.isEmpty() || !isPrimitiveTool(stack)) {
			return;
		}
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, nbt -> {
			nbt.putInt(KEY_SHARPNESS, 100);
			nbt.putInt(KEY_BINDING, cordageBound ? 100 : 55);
			nbt.putInt(KEY_HEAD_DAMAGE, 0);
			nbt.putBoolean(KEY_CORDAGE, cordageBound);
		});
	}

	private static NbtCompound readTag(ItemStack stack) {
		NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
		return comp != null ? comp.copyNbt() : new NbtCompound();
	}

	public static int getSharpness(ItemStack stack) {
		NbtCompound nbt = readTag(stack);
		return nbt.contains(KEY_SHARPNESS) ? clampPct(nbt.getInt(KEY_SHARPNESS)) : 100;
	}

	public static int getBinding(ItemStack stack) {
		NbtCompound nbt = readTag(stack);
		return nbt.contains(KEY_BINDING) ? clampPct(nbt.getInt(KEY_BINDING)) : 100;
	}

	public static int getHeadDamage(ItemStack stack) {
		NbtCompound nbt = readTag(stack);
		return nbt.contains(KEY_HEAD_DAMAGE) ? clampPct(nbt.getInt(KEY_HEAD_DAMAGE)) : 0;
	}

	public static boolean isCordageBound(ItemStack stack) {
		NbtCompound nbt = readTag(stack);
		return !nbt.contains(KEY_CORDAGE) || nbt.getBoolean(KEY_CORDAGE);
	}

	public static boolean isDull(ItemStack stack) {
		return isPrimitiveTool(stack) && isDullSharpness(getSharpness(stack));
	}

	/** Multiplier applied to mining and chopping speed based on edge sharpness. */
	public static float speedMultiplier(ItemStack stack) {
		if (!isPrimitiveTool(stack) || !ProjectRealism.config().toolDegradation) {
			return 1.0f;
		}
		return speedMultiplierForSharpness(getSharpness(stack));
	}

	/** Pure speed scaling formula for a given sharpness percentage (0..100). */
	public static float speedMultiplierForSharpness(int sharpness) {
		int clamped = clampPct(sharpness);
		return 0.25f + 0.75f * (clamped / 100.0f);
	}

	/** Pure dullness threshold check matching {@link #isDull(ItemStack)}. */
	public static boolean isDullSharpness(int sharpness) {
		return clampPct(sharpness) < 35;
	}

	public static boolean applyWear(PlayerEntity player, Hand hand, ItemStack stack,
									float hardness, ServerWorld world) {
		return applyWear(player, hand, stack, hardness, world, true);
	}

	/**
	 * Applies edge dulling, binding loosening, and stone head fatigue from a tool strike or processing step.
	 * Returns true if the tool survived; false if its binding failed or the stone head shattered.
	 */
	public static boolean applyWear(PlayerEntity player, Hand hand, ItemStack stack,
									float hardness, ServerWorld world, boolean damageDurability) {
		if (stack.isEmpty() || !isPrimitiveTool(stack) || player.isCreative()
				|| !ProjectRealism.config().toolDegradation) {
			return true;
		}

		int oldSharpness = getSharpness(stack);
		int oldBinding = getBinding(stack);
		int oldHeadDamage = getHeadDamage(stack);
		boolean cordage = isCordageBound(stack);

		int sharpLoss = Math.max(2, Math.round(4.0f * hardness));
		int bindLoss = cordage ? 2 : 5;
		int headLoss = Math.max(1, Math.round((oldSharpness < 25 ? 6.0f : 2.5f) * hardness));

		int newSharpness = Math.max(0, oldSharpness - sharpLoss);
		int newBinding = Math.max(0, oldBinding - bindLoss);
		int newHeadDamage = Math.min(100, oldHeadDamage + headLoss);

		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, nbt -> {
			nbt.putInt(KEY_SHARPNESS, newSharpness);
			nbt.putInt(KEY_BINDING, newBinding);
			nbt.putInt(KEY_HEAD_DAMAGE, newHeadDamage);
			nbt.putBoolean(KEY_CORDAGE, cordage);
		});

		if (damageDurability) {
			EquipmentSlot slot = hand == Hand.MAIN_HAND ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND;
			stack.damage(1, player, slot);
			if (stack.isEmpty()) {
				return false;
			}
		}

		// Poor-quality or worn bindings can loosen and drop the tool apart
		if (newBinding <= 0 || (!cordage && newBinding <= 15 && world.random.nextFloat() < 0.22f)) {
			unfastenTool(player, hand, stack, newHeadDamage, world);
			return false;
		}

		// Severely damaged stone heads can fracture under impact
		if (newHeadDamage >= 100 || (newHeadDamage >= 82 && hardness >= 1.2f && world.random.nextFloat() < 0.20f)) {
			shatterHead(player, hand, stack, world);
			return false;
		}

		if (oldSharpness >= 35 && newSharpness < 35) {
			player.sendMessage(Text.translatable("project_realism.tool.dull_warning")
					.formatted(Formatting.YELLOW), true);
		} else if (oldBinding > 25 && newBinding <= 25) {
			player.sendMessage(Text.translatable("project_realism.tool.loose_warning")
					.formatted(Formatting.GOLD), true);
		}
		return true;
	}

	/** Applies wear in a crafting grid (no world entity/hand reference). */
	public static void applyCraftingWear(ItemStack stack) {
		if (stack.isEmpty() || !isPrimitiveTool(stack) || !ProjectRealism.config().toolDegradation) {
			return;
		}
		int sharp = Math.max(0, getSharpness(stack) - 3);
		int bind = Math.max(1, getBinding(stack) - (isCordageBound(stack) ? 1 : 3));
		int head = Math.min(99, getHeadDamage(stack) + 1);
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, nbt -> {
			nbt.putInt(KEY_SHARPNESS, sharp);
			nbt.putInt(KEY_BINDING, bind);
			nbt.putInt(KEY_HEAD_DAMAGE, head);
			nbt.putBoolean(KEY_CORDAGE, isCordageBound(stack));
		});
		if (stack.isDamageable()) {
			stack.setDamage(stack.getDamage() + 1);
		}
	}

	private static void unfastenTool(PlayerEntity player, Hand hand, ItemStack stack,
									 int headDamage, ServerWorld world) {
		Item headItem = stack.isOf(PrimitiveItems.STONE_KNIFE)
				? PrimitiveItems.SHARP_STONE : PrimitiveItems.STONE_AXE_HEAD;
		boolean headSurvived = headDamage < 90;

		player.setStackInHand(hand, new ItemStack(Items.STICK));
		if (headSurvived) {
			ItemStack headStack = new ItemStack(headItem);
			if (!player.getInventory().insertStack(headStack)) {
				player.dropItem(headStack, false);
			}
		}

		world.playSound(null, player.getBlockPos(), SoundEvents.ITEM_BUNDLE_DROP_CONTENTS,
				SoundCategory.PLAYERS, 0.9f, 0.8f);
		player.sendMessage(Text.translatable("project_realism.tool.unbound")
				.formatted(Formatting.RED), true);
	}

	private static void shatterHead(PlayerEntity player, Hand hand, ItemStack stack, ServerWorld world) {
		player.setStackInHand(hand, new ItemStack(Items.STICK));
		world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_STONE_BREAK,
				SoundCategory.PLAYERS, 0.9f, 0.6f);
		world.spawnParticles(ParticleTypes.CRIT,
				player.getX(), player.getY() + 1.0, player.getZ(),
				10, 0.25, 0.2, 0.25, 0.08);
		player.sendMessage(Text.translatable("project_realism.tool.head_shattered")
				.formatted(Formatting.RED), true);
	}

	/** Sharpens a dull primitive stone tool by knapping against a rock. */
	public static SharpenResult sharpen(ItemStack stack, Random random) {
		if (!isPrimitiveTool(stack)) {
			return SharpenResult.ALREADY_SHARP;
		}
		int sharpness = getSharpness(stack);
		int headDamage = getHeadDamage(stack);
		if (sharpness >= 98) {
			return SharpenResult.ALREADY_SHARP;
		}
		if (headDamage >= 92 && random.nextFloat() < 0.45f) {
			return SharpenResult.SHATTERED;
		}

		int newSharpness = Math.min(100, sharpness + 40);
		int newHeadDamage = Math.min(100, headDamage + 8);
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, nbt -> {
			nbt.putInt(KEY_SHARPNESS, newSharpness);
			nbt.putInt(KEY_HEAD_DAMAGE, newHeadDamage);
			if (!nbt.contains(KEY_BINDING)) {
				nbt.putInt(KEY_BINDING, 100);
			}
		});
		if (stack.isDamageable() && stack.getDamage() > 0) {
			stack.setDamage(Math.max(0, stack.getDamage() - 10));
		}
		return SharpenResult.SHARPENED;
	}

	public static void tightenBinding(ItemStack stack, boolean cordage) {
		if (!isPrimitiveTool(stack)) {
			return;
		}
		int current = getBinding(stack);
		int target = cordage ? 100 : Math.max(current, 70);
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, nbt -> {
			nbt.putInt(KEY_BINDING, target);
			nbt.putBoolean(KEY_CORDAGE, cordage || isCordageBound(stack));
		});
	}

	public static void appendToolTooltip(ItemStack stack, List<Text> tooltip) {
		if (!isPrimitiveTool(stack)) {
			return;
		}
		int sharp = getSharpness(stack);
		int bind = getBinding(stack);
		int integrity = Math.max(0, 100 - getHeadDamage(stack));
		boolean cordage = isCordageBound(stack);

		Formatting sharpColor = sharp >= 65 ? Formatting.GREEN : (sharp >= 35 ? Formatting.YELLOW : Formatting.RED);
		Formatting bindColor = bind >= 65 ? Formatting.GREEN : (bind >= 30 ? Formatting.GOLD : Formatting.RED);
		Formatting headColor = integrity >= 55 ? Formatting.GRAY : (integrity >= 25 ? Formatting.YELLOW : Formatting.RED);

		tooltip.add(Text.translatable("project_realism.tooltip.sharpness", sharp).formatted(sharpColor));
		tooltip.add(Text.translatable(
				cordage ? "project_realism.tooltip.binding_cordage" : "project_realism.tooltip.binding_fiber",
				bind).formatted(bindColor));
		tooltip.add(Text.translatable("project_realism.tooltip.head_integrity", integrity).formatted(headColor));
	}

	// ------------------------------------------------------------------ wood dryness ----

	public static boolean isDry(ItemStack stack) {
		NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
		return comp != null && comp.copyNbt().getBoolean(KEY_DRY);
	}

	public static ItemStack setDry(ItemStack stack, boolean dry) {
		if (stack.isEmpty()) {
			return stack;
		}
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, nbt -> nbt.putBoolean(KEY_DRY, dry));
		return stack;
	}

	public static void appendWoodTooltip(ItemStack stack, List<Text> tooltip, boolean alwaysWet) {
		if (!alwaysWet && isDry(stack)) {
			tooltip.add(Text.translatable("project_realism.tooltip.wood_dry").formatted(Formatting.GOLD));
		} else {
			tooltip.add(Text.translatable("project_realism.tooltip.wood_wet").formatted(Formatting.DARK_GREEN));
		}
	}

	private static int clampPct(int v) {
		return Math.max(0, Math.min(100, v));
	}
}
