package com.projectrealism.hydration;

import com.projectrealism.material.WaterskinConstruction;
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
 * Reusable stitched leather waterskin storing actual {@link WaterQuality} properties, persistent
 * per-seam construction integrity ({@link WaterskinConstruction}), fluid slosh/expansion state,
 * and dynamic total mass (empty leather + water contents).
 */
public class WaterskinItem extends Item {
	public static final int CAPACITY_ML = 1000;
	public static final int SIP_ML = 250;

	public WaterskinItem(Settings settings) {
		super(settings.maxCount(1));
	}

	public static int getCapacityMl(ItemStack stack) {
		return WaterskinConstruction.read(stack).capacityMl;
	}

	public static int getVolumeMl(ItemStack stack) {
		return Math.min(getCapacityMl(stack), WaterQuality.readVolumeMl(stack, 0));
	}

	public static void setContents(ItemStack stack, int volumeMl, WaterQuality quality) {
		int cap = getCapacityMl(stack);
		int clamped = Math.max(0, Math.min(cap, volumeMl));
		WaterQuality.writeVolumeMl(stack, clamped);
		if (clamped > 0 && quality != null) {
			WaterQuality.write(stack, quality);
		}
	}

	@Override
	public boolean isItemBarVisible(ItemStack stack) {
		return getVolumeMl(stack) > 0 || WaterskinConstruction.read(stack).overallIntegrity() < 0.85;
	}

	@Override
	public int getItemBarStep(ItemStack stack) {
		int cap = Math.max(250, getCapacityMl(stack));
		return Math.round((getVolumeMl(stack) / (float) cap) * 13.0f);
	}

	@Override
	public int getItemBarColor(ItemStack stack) {
		WaterskinConstruction c = WaterskinConstruction.read(stack);
		WaterQuality q = WaterQuality.read(stack, WaterQuality.RIVER_WATER);
		if (c.totalLeakMlPerSec(getVolumeMl(stack), q.tempK(), null) > 0.25) return 0xE67E22;
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
		int cap = getCapacityMl(stack);

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
					setContents(stack, cap, q);
					sw.playSound(null, user.getBlockPos(), SoundEvents.ITEM_BOTTLE_FILL,
							SoundCategory.PLAYERS, 0.8f, 1.0f);
					user.sendMessage(Text.translatable("project_realism.waterskin.filled",
							cap).formatted(Formatting.AQUA), true);
				}
				return TypedActionResult.success(stack, world.isClient());
			}
		}

		// 2. Collect pure rainwater if sneaking and looking up in rain
		if (user.isSneaking() && user.getPitch() < -35.0f && world.isRaining() && world.isSkyVisible(user.getBlockPos())) {
			if (world instanceof ServerWorld sw) {
				int cur = getVolumeMl(stack);
				int next = Math.min(cap, cur + SIP_ML);
				setContents(stack, next, WaterQuality.fromRain(sw, user.getBlockPos()));
				sw.playSound(null, user.getBlockPos(), SoundEvents.ITEM_BOTTLE_FILL,
						SoundCategory.PLAYERS, 0.6f, 1.3f);
				user.sendMessage(Text.translatable("project_realism.waterskin.rain_filled",
						next, cap).formatted(Formatting.AQUA), true);
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
		setContents(stack, vol - drunkMl, q);
		return stack;
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		super.appendTooltip(stack, context, tooltip, type);
		WaterskinConstruction c = WaterskinConstruction.read(stack);
		int vol = getVolumeMl(stack);
		tooltip.add(Text.translatable("project_realism.tooltip.waterskin_volume", vol, c.capacityMl)
				.formatted(vol > 0 ? Formatting.AQUA : Formatting.GRAY));
		tooltip.add(Text.translatable("project_realism.tooltip.waterskin_craft",
				c.leatherType.id,
				String.format("%.2f", c.totalMassKg(vol)),
				(int) Math.round(c.overallIntegrity() * 100.0),
				(int) Math.round(c.leatherStiffness * 100.0))
				.formatted(Formatting.GOLD));
		WaterQuality q = WaterQuality.read(stack, WaterQuality.RIVER_WATER);
		int worst = c.worstLeakingSeamIndex(Math.max(vol, c.capacityMl / 2), q.tempK());
		if (worst >= 0 && c.seams[worst].integrity() < 0.88) {
			tooltip.add(Text.translatable("project_realism.tooltip.waterskin_weak_seam",
					WaterskinConstruction.SeamZone.values()[worst].key,
					(int) Math.round(c.seams[worst].integrity() * 100.0))
					.formatted(Formatting.RED));
		}
		if (vol > 0) {
			WaterQuality.appendTooltip(stack, tooltip);
		} else {
			tooltip.add(Text.translatable("project_realism.tooltip.waterskin_hint").formatted(Formatting.DARK_GRAY));
		}
	}
}
