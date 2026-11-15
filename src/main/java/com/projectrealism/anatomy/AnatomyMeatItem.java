package com.projectrealism.anatomy;

import com.projectrealism.thermo.ThermalConstants;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsage;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.UseAction;
import net.minecraft.world.World;

import java.util.List;

/**
 * Physical muscle cut or organ carrying species, mass, marbling fat, connective toughness,
 * temperature, cooking/charring, spoilage, and microbial safety data.
 */
public class AnatomyMeatItem extends Item {
	private final MeatCutData.CutKind defaultKind;

	public AnatomyMeatItem(MeatCutData.CutKind defaultKind, Settings settings) {
		super(settings.maxCount(16));
		this.defaultKind = defaultKind;
	}

	public MeatCutData.CutKind defaultKind() {
		return defaultKind;
	}

	public static MeatCutData readMeat(ItemStack stack, MeatCutData.CutKind fallback) {
		NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (comp != null) {
			NbtCompound root = comp.copyNbt();
			if (root.contains("Meat")) {
				return MeatCutData.fromNbt(root.getCompound("Meat"));
			}
		}
		MeatCutData m = new MeatCutData();
		m.kind = fallback;
		return m;
	}

	public static void writeMeat(ItemStack stack, MeatCutData m) {
		if (stack.isEmpty() || m == null) return;
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, root -> root.put("Meat", m.toNbt()));
	}

	public static ItemStack create(MeatCutData data) {
		Item item = switch (data.kind) {
			case FATTY_RIB -> AnatomyItems.FATTY_CUT;
			case TOUGH_HAUNCH -> AnatomyItems.TOUGH_CUT;
			case HEART -> AnatomyItems.ORGAN_HEART;
			case LIVER -> AnatomyItems.ORGAN_LIVER;
			case GUT_CASING -> AnatomyItems.ORGAN_GUT;
			default -> AnatomyItems.PRIME_CUT;
		};
		ItemStack st = new ItemStack(item);
		writeMeat(st, data);
		return st;
	}

	public static double massKg(ItemStack stack) {
		if (stack.getItem() instanceof AnatomyMeatItem ami) {
			return readMeat(stack, ami.defaultKind).massKg;
		}
		return 0.40;
	}

	@Override
	public UseAction getUseAction(ItemStack stack) {
		return UseAction.EAT;
	}

	@Override
	public int getMaxUseTime(ItemStack stack, LivingEntity user) {
		MeatCutData m = readMeat(stack, defaultKind);
		// Tough unrendered meat takes noticeably longer to chew!
		return (int) Math.round(28 + m.toughness * (1.0 - m.cookedFrac * 0.45) * 16);
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		if (user.canConsume(true)) {
			return ItemUsage.consumeHeldItem(world, user, hand);
		}
		return TypedActionResult.pass(stack);
	}

	@Override
	public ItemStack finishUsing(ItemStack stack, World world, LivingEntity user) {
		MeatCutData m = readMeat(stack, defaultKind);
		if (user instanceof ServerPlayerEntity sp) {
			double cookBonus = 0.55 + 0.75 * m.cookedFrac - 0.50 * m.charredFrac;
			double calorieWeight = Math.min(1.8, m.massKg / 0.35) * (1.0 + m.marblingFat * 0.45);
			int hunger = Math.max(1, (int) Math.round(4.0 * cookBonus * calorieWeight * (1.0 - m.spoilage * 0.6)));
			float sat = (float) Math.max(0.1, (0.35 + m.marblingFat * 0.5) * cookBonus);
			sp.getHungerManager().add(hunger, sat);

			if (!m.isSafeToEat() || (m.cookedFrac < 0.35 && m.microbialLoad > 0.25)) {
				sp.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 160, 0));
				sp.addStatusEffect(new StatusEffectInstance(StatusEffects.HUNGER, 240, 0));
				if (m.spoilage > 0.65 || m.gutContamination > 0.45) {
					sp.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 120, 0));
				}
			}
		}
		if (!(user instanceof PlayerEntity p && p.isCreative())) {
			stack.decrement(1);
		}
		return stack;
	}

	@Override
	public boolean isItemBarVisible(ItemStack stack) {
		MeatCutData m = readMeat(stack, defaultKind);
		return m.spoilage > 0.08 || m.cookedFrac > 0.05;
	}

	@Override
	public int getItemBarStep(ItemStack stack) {
		MeatCutData m = readMeat(stack, defaultKind);
		return Math.max(1, Math.min(13, (int) Math.round((1.0 - m.spoilage) * 13.0)));
	}

	@Override
	public int getItemBarColor(ItemStack stack) {
		MeatCutData m = readMeat(stack, defaultKind);
		if (m.spoilage > 0.45) return 0x628A38;
		if (m.cookedFrac >= 0.75) return 0xC86B32;
		return 0xC84D4D;
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		super.appendTooltip(stack, context, tooltip, type);
		MeatCutData m = readMeat(stack, defaultKind);
		tooltip.add(Text.translatable("project_realism.tooltip.meat_info",
				m.speciesId,
				String.format("%.2f", m.massKg),
				(int) Math.round(m.marblingFat * 100.0),
				(int) Math.round(m.toughness * 100.0))
				.formatted(Formatting.GOLD));
		tooltip.add(Text.translatable("project_realism.tooltip.meat_state",
				(int) Math.round(ThermalConstants.toCelsius(m.tempK)),
				(int) Math.round(m.cookedFrac * 100.0),
				(int) Math.round(m.spoilage * 100.0))
				.formatted(m.spoilage > 0.40 ? Formatting.RED : (m.cookedFrac >= 0.75 ? Formatting.GREEN : Formatting.GRAY)));
		if (m.gutContamination > 0.15) {
			tooltip.add(Text.translatable("project_realism.tooltip.meat_contam",
					(int) Math.round(m.gutContamination * 100.0)).formatted(Formatting.DARK_GREEN));
		}
	}
}
