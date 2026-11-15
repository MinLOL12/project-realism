package com.projectrealism.material;

import com.projectrealism.hydration.HydrationManager;
import com.projectrealism.hydration.WaterQuality;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsage;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.List;

/**
 * Rigid 750 mL carved gourd vessel. Maintains fixed geometry with zero expansion, provides stronger
 * thermal insulation than leather, and can suffer physical cracks under impacts that leak water
 * until patched with pine resin or tallow wax.
 */
public class GourdFlaskItem extends Item {
	public static final int CAPACITY_ML = 750;
	public static final int SIP_ML = 250;
	public static final double EMPTY_MASS_KG = 0.24;

	public GourdFlaskItem(Settings settings) {
		super(settings.maxCount(1));
	}

	public static int getVolumeMl(ItemStack stack) {
		return Math.min(CAPACITY_ML, WaterQuality.readVolumeMl(stack, 0));
	}

	public static void setContents(ItemStack stack, int volumeMl, WaterQuality quality) {
		int clamped = Math.max(0, Math.min(CAPACITY_ML, volumeMl));
		WaterQuality.writeVolumeMl(stack, clamped);
		if (clamped > 0 && quality != null) {
			WaterQuality.write(stack, quality);
		}
	}

	public static MaterialAging.GourdState readGourdState(ItemStack stack) {
		MaterialAging.GourdState gs = new MaterialAging.GourdState();
		gs.moisture = 0.08;
		gs.shellHardness = 0.88;
		NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (comp != null) {
			NbtCompound root = comp.copyNbt();
			if (root.contains("Gourd")) {
				NbtCompound t = root.getCompound("Gourd");
				if (t.contains("Moist")) gs.moisture = t.getDouble("Moist");
				if (t.contains("Hard")) gs.shellHardness = t.getDouble("Hard");
				gs.crackSeverity = t.getDouble("Crack");
				gs.sealLevel = t.getDouble("Seal");
			}
		}
		return gs;
	}

	public static void writeGourdState(ItemStack stack, MaterialAging.GourdState gs) {
		if (stack.isEmpty() || gs == null) return;
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, root -> {
			NbtCompound t = new NbtCompound();
			t.putDouble("Moist", gs.moisture);
			t.putDouble("Hard", gs.shellHardness);
			t.putDouble("Crack", gs.crackSeverity);
			t.putDouble("Seal", gs.sealLevel);
			root.put("Gourd", t);
		});
	}

	public static double totalMassKg(ItemStack stack) {
		return EMPTY_MASS_KG + getVolumeMl(stack) / 1000.0;
	}

	@Override
	public boolean isItemBarVisible(ItemStack stack) {
		return getVolumeMl(stack) > 0 || readGourdState(stack).crackSeverity > 0.05;
	}

	@Override
	public int getItemBarStep(ItemStack stack) {
		return Math.round((getVolumeMl(stack) / (float) CAPACITY_ML) * 13.0f);
	}

	@Override
	public int getItemBarColor(ItemStack stack) {
		MaterialAging.GourdState gs = readGourdState(stack);
		if (gs.crackSeverity * (1.0 - gs.sealLevel * 0.9) > 0.15) return 0xE67E22;
		return 0x4EA8DE;
	}

	@Override
	public UseAction getUseAction(ItemStack stack) {
		return UseAction.DRINK;
	}

	@Override
	public int getMaxUseTime(ItemStack stack, LivingEntity user) {
		return 28;
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		Vec3d eye = user.getEyePos();
		Vec3d end = eye.add(user.getRotationVec(1.0f).multiply(4.5));
		BlockHitResult hit = world.raycast(new RaycastContext(
				eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.SOURCE_ONLY, user));
		if (hit.getType() == HitResult.Type.BLOCK) {
			BlockPos pos = hit.getBlockPos();
			FluidState fs = world.getFluidState(pos);
			if (fs.isIn(FluidTags.WATER)) {
				if (world instanceof ServerWorld sw) {
					setContents(stack, CAPACITY_ML, WaterQuality.fromWorld(sw, pos));
					sw.playSound(null, user.getBlockPos(), SoundEvents.ITEM_BOTTLE_FILL,
							SoundCategory.PLAYERS, 0.8f, 1.1f);
					user.sendMessage(Text.translatable("project_realism.waterskin.filled", CAPACITY_ML)
							.formatted(Formatting.AQUA), true);
				}
				return TypedActionResult.success(stack, world.isClient());
			}
		}

		if (getVolumeMl(stack) > 0) {
			return ItemUsage.consumeHeldItem(world, user, hand);
		}
		if (!world.isClient) {
			user.sendMessage(Text.translatable("project_realism.gourd.empty").formatted(Formatting.GRAY), true);
		}
		return TypedActionResult.fail(stack);
	}

	@Override
	public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
		int vol = getVolumeMl(stack);
		if (vol <= 0) return stack;
		int drunk = Math.min(SIP_ML, vol);
		WaterQuality q = WaterQuality.read(stack, WaterQuality.RIVER_WATER);
		if (user instanceof ServerPlayerEntity player) {
			HydrationManager.get(player.getServerWorld()).drink(player, drunk / 1000.0, q);
			world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_GENERIC_DRINK,
					SoundCategory.PLAYERS, 0.65f, 1.0f);
		}
		setContents(stack, vol - drunk, q);
		return stack;
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		super.appendTooltip(stack, context, tooltip, type);
		int vol = getVolumeMl(stack);
		MaterialAging.GourdState gs = readGourdState(stack);
		tooltip.add(Text.translatable("project_realism.tooltip.gourd_volume", vol, CAPACITY_ML,
				String.format("%.2f", totalMassKg(stack))).formatted(Formatting.AQUA));
		if (gs.crackSeverity > 0.05) {
			tooltip.add(Text.translatable("project_realism.tooltip.gourd_crack",
					(int) Math.round(gs.crackSeverity * 100.0),
					(int) Math.round(gs.sealLevel * 100.0)).formatted(Formatting.GOLD));
		}
		if (vol > 0) {
			WaterQuality.appendTooltip(stack, tooltip);
		}
	}
}
