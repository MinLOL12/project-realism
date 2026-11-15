package com.projectrealism.anatomy;

import com.projectrealism.ProjectRealism;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * Registers all persistent anatomical carcass items, bones, muscle cuts, organs, sinew, tendons,
 * wool, raw fat, horns, horn vessels, and bone-worked tools.
 */
public final class AnatomyItems {
	public static final Item CARCASS_ITEM = register("carcass_item", new CarcassItem(new Item.Settings()));
	public static final Item RAW_BONE = register("raw_bone", new RawBoneItem(new Item.Settings()));

	// Distinct anatomical muscle cuts & internal organs
	public static final Item PRIME_CUT = register("prime_cut",
			new AnatomyMeatItem(MeatCutData.CutKind.PRIME_LOIN, new Item.Settings()));
	public static final Item FATTY_CUT = register("fatty_cut",
			new AnatomyMeatItem(MeatCutData.CutKind.FATTY_RIB, new Item.Settings()));
	public static final Item TOUGH_CUT = register("tough_cut",
			new AnatomyMeatItem(MeatCutData.CutKind.TOUGH_HAUNCH, new Item.Settings()));
	public static final Item ORGAN_HEART = register("organ_heart",
			new AnatomyMeatItem(MeatCutData.CutKind.HEART, new Item.Settings()));
	public static final Item ORGAN_LIVER = register("organ_liver",
			new AnatomyMeatItem(MeatCutData.CutKind.LIVER, new Item.Settings()));
	public static final Item ORGAN_GUT = register("organ_gut",
			new AnatomyMeatItem(MeatCutData.CutKind.GUT_CASING, new Item.Settings()));

	// Connective tissue, fat, fleece, horn & shaped bone tools
	public static final Item ANIMAL_FAT = register("animal_fat",
			new HintItem(new Item.Settings().maxCount(16), "item.project_realism.animal_fat.tooltip"));
	public static final Item SINEW = register("sinew",
			new HintItem(new Item.Settings().maxCount(32), "item.project_realism.sinew.tooltip"));
	public static final Item TENDON = register("tendon",
			new HintItem(new Item.Settings().maxCount(32), "item.project_realism.tendon.tooltip"));
	public static final Item RAW_WOOL = register("raw_wool",
			new HintItem(new Item.Settings().maxCount(16), "item.project_realism.raw_wool.tooltip"));
	public static final Item ANIMAL_HORN = register("animal_horn",
			new HintItem(new Item.Settings().maxCount(8), "item.project_realism.animal_horn.tooltip"));
	public static final Item HORN_FLASK = register("horn_flask", new HornFlaskItem(new Item.Settings()));

	public static final Item BONE_BLADE = register("bone_blade",
			new BoneToolItem(new Item.Settings().maxCount(1).maxDamage(78), "item.project_realism.bone_blade.tooltip"));
	public static final Item BONE_CHISEL = register("bone_chisel",
			new BoneToolItem(new Item.Settings().maxCount(1).maxDamage(96), "item.project_realism.bone_chisel.tooltip"));

	public static final ItemGroup GROUP = FabricItemGroup.builder()
			.displayName(Text.translatable("itemGroup.project_realism.anatomy"))
			.icon(() -> new ItemStack(RAW_BONE))
			.entries((context, entries) -> {
				entries.add(CARCASS_ITEM);
				entries.add(RAW_WOOL);
				entries.add(ANIMAL_FAT);
				entries.add(SINEW);
				entries.add(TENDON);
				entries.add(ANIMAL_HORN);
				entries.add(HORN_FLASK);
				entries.add(RAW_BONE);
				entries.add(BONE_BLADE);
				entries.add(BONE_CHISEL);
				entries.add(PRIME_CUT);
				entries.add(FATTY_CUT);
				entries.add(TOUGH_CUT);
				entries.add(ORGAN_HEART);
				entries.add(ORGAN_LIVER);
				entries.add(ORGAN_GUT);
			})
			.build();

	private AnatomyItems() {
	}

	private static Item register(String path, Item item) {
		return Registry.register(Registries.ITEM, ProjectRealism.id(path), item);
	}

	public static void register() {
		Registry.register(Registries.ITEM_GROUP,
				RegistryKey.of(RegistryKeys.ITEM_GROUP, ProjectRealism.id("anatomy")),
				GROUP);
		ProjectRealism.LOGGER.info("Registered persistent anatomical carcasses, bones, organs, sinew & cuts.");
	}

	public static class HintItem extends Item {
		private final String key;

		public HintItem(Settings settings, String key) {
			super(settings);
			this.key = key;
		}

		@Override
		public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
			super.appendTooltip(stack, context, tooltip, type);
			tooltip.add(Text.translatable(key).formatted(Formatting.DARK_GRAY));
		}
	}

	public static class BoneToolItem extends Item {
		private final String key;

		public BoneToolItem(Settings settings, String key) {
			super(settings);
			this.key = key;
		}

		public static int getWorkQualityPct(ItemStack stack) {
			NbtComponent c = stack.get(DataComponentTypes.CUSTOM_DATA);
			if (c != null && c.copyNbt().contains("BoneQual")) {
				return Math.max(10, Math.min(100, c.copyNbt().getInt("BoneQual")));
			}
			return 75;
		}

		public static void setWorkQualityPct(ItemStack stack, int pct) {
			NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack,
					t -> t.putInt("BoneQual", Math.max(10, Math.min(100, pct))));
		}

		@Override
		public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
			super.appendTooltip(stack, context, tooltip, type);
			int q = getWorkQualityPct(stack);
			tooltip.add(Text.translatable("project_realism.tooltip.bone_tool_quality", q)
					.formatted(q < 45 ? Formatting.RED : Formatting.GOLD));
			tooltip.add(Text.translatable(key).formatted(Formatting.DARK_GRAY));
		}
	}
}
