package com.projectrealism.hydration;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsage;
import net.minecraft.item.tooltip.TooltipType;
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
 * Reusable 1000 mL stitched leather waterskin storing actual {@link WaterQuality} properties and
 * current water volume in {@link net.minecraft.component.DataComponentTypes#CUSTOM_DATA}.
 */
public class WaterskinItem extends Item {
	public static final int CAPACITY_ML = 1000;
	public static final int SIP_ML = 250;

	public WaterskinItem(Settings settings) {
		super(settings);
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

	@Override
	public boolean isItemBarVisible(ItemStack stack) {
		return getVolumeMl(stack) > 0;
	}

	@Override
	public int getItemBarStep(ItemStack stack) {
		return Math.round((getVolumeMl(stack) / (float) CAPACITY_ML) * 13.0f);
	}

	@Override
	public int getItemBarColor(ItemStack stack) {
		WaterQuality q = WaterQuality.read(stack, WaterQuality.RIVER_WATER);
		if (q.chemicalLoad() >= 0.15) return 0x9944CC;
		if (q.salinityGPerL() >= 5.0) return 0x55BBAA;
		if (q.turbidity() >= 0.45) return 0x8A7448;
		return 0x3399FF;
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

		// 1. Raycast water block to refill
		Vec3d eye = user.getEyePos();
		Vec3d end = eye.add(user.getRotationVec(1.0f).multiply(4.5));
		BlockHitResult hit = world.raycast(new RaycastContext(
				eye, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.SOURCE_ONLY, user));
		if (hit.getType() == HitResult.Type.BLOCK) {
			BlockPos pos = hit.getBlockPos();
			FluidState fs = world.getFluidState(pos);
			if (fs.isIn(FluidTags.WATER)) {
				if (world instanceof ServerWorld sw) {
					WaterQuality q = WaterQuality.fromWorld(sw, pos);
					setContents(stack, CAPACITY_ML, q);
					sw.playSound(null, user.getBlockPos(), SoundEvents.ITEM_BOTTLE_FILL,
							SoundCategory.PLAYERS, 0.8f, 1.0f);
					user.sendMessage(Text.translatable("project_realism.waterskin.filled",
							CAPACITY_ML).formatted(Formatting.AQUA), true);
				}
				return TypedActionResult.success(stack, world.isClient());
			}
		}

		// 2. Collect pure rainwater if sneaking and looking up in rain
		if (user.isSneaking() && user.getPitch() < -35.0f && world.isRaining() && world.isSkyVisible(user.getBlockPos())) {
			if (world instanceof ServerWorld sw) {
				int cur = getVolumeMl(stack);
				int next = Math.min(CAPACITY_ML, cur + SIP_ML);
				setContents(stack, next, WaterQuality.fromRain(sw, user.getBlockPos()));
				sw.playSound(null, user.getBlockPos(), SoundEvents.ITEM_BOTTLE_FILL,
						SoundCategory.PLAYERS, 0.6f, 1.3f);
				user.sendMessage(Text.translatable("project_realism.waterskin.rain_filled",
						next, CAPACITY_ML).formatted(Formatting.AQUA), true);
			}
			return TypedActionResult.success(stack, world.isClient());
		}

		// 3. Drink a sip if water remains
		if (getVolumeMl(stack) > 0) {
			return ItemUsage.consumeHeldItem(world, user, hand);
		}
		if (!world.isClient) {
			user.sendMessage(Text.translatable("project_realism.waterskin.empty").formatted(Formatting.GRAY), true);
		}
		return TypedActionResult.fail(stack);
	}

	@Override
	public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
		int vol = getVolumeMl(stack);
		if (vol <= 0) {
			return stack;
		}
		int drunkMl = Math.min(SIP_ML, vol);
		WaterQuality q = WaterQuality.read(stack, WaterQuality.RIVER_WATER);
		if (user instanceof ServerPlayerEntity player) {
			HydrationManager.get(player.getServerWorld()).drink(player, drunkMl / 1000.0, q);
			world.playSound(null, player.getBlockPos(), SoundEvents.ENTITY_GENERIC_DRINK,
					SoundCategory.PLAYERS, 0.65f, 1.0f);
		}
		int remaining = vol - drunkMl;
		setContents(stack, remaining, q);
		return stack;
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		super.appendTooltip(stack, context, tooltip, type);
		int vol = getVolumeMl(stack);
		tooltip.add(Text.translatable("project_realism.tooltip.waterskin_volume", vol, CAPACITY_ML)
				.formatted(vol > 0 ? Formatting.AQUA : Formatting.GRAY));
		if (vol > 0) {
			WaterQuality.appendTooltip(stack, tooltip);
		} else {
			tooltip.add(Text.translatable("project_realism.tooltip.waterskin_hint").formatted(Formatting.DARK_GRAY));
		}
	}
}
