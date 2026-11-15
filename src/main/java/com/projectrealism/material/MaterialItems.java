package com.projectrealism.material;

import com.projectrealism.ProjectRealism;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.List;

/**
 * Registers all raw material processing tools, anatomical hides, leather panels/scraps, natural
 * sealants, gourds, and physical crafting workstations.
 */
public final class MaterialItems {
	// --- Processing & Sewing Tools ---
	public static final Item STONE_SCRAPER = register("stone_scraper",
			new StoneScraperItem(new Item.Settings().maxCount(1).maxDamage(72)));
	public static final Item BONE_AWL = register("bone_awl",
			new ToolHintItem(new Item.Settings().maxCount(1).maxDamage(96),
					"item.project_realism.bone_awl.tooltip"));
	public static final Item BONE_NEEDLE = register("bone_needle",
			new ToolHintItem(new Item.Settings().maxCount(1).maxDamage(120),
					"item.project_realism.bone_needle.tooltip"));

	// --- Materials, Sealants & Rigid Vessels ---
	public static final Item RAW_HIDE = register("raw_hide", new RawHideItem(new Item.Settings()));
	public static final Item LEATHER_PIECE = register("leather_piece", new LeatherPieceItem(new Item.Settings()));
	public static final Item HIDE_SCRAP = register("hide_scrap",
			new ToolHintItem(new Item.Settings().maxCount(32), "item.project_realism.hide_scrap.tooltip"));
	public static final Item TALLOW_WAX = register("tallow_wax",
			new ToolHintItem(new Item.Settings().maxCount(32), "item.project_realism.tallow_wax.tooltip"));
	public static final Item PINE_RESIN = register("pine_resin",
			new ToolHintItem(new Item.Settings().maxCount(32), "item.project_realism.pine_resin.tooltip"));
	public static final Item RAW_GOURD = register("raw_gourd", new RawGourdItem(new Item.Settings()));
	public static final Item GOURD_FLASK = register("gourd_flask", new GourdFlaskItem(new Item.Settings()));

	// --- Workstation BlockItems ---
	public static final Item SCRAPING_FRAME = registerBlockItem("scraping_frame", MaterialBlocks.SCRAPING_FRAME);
	public static final Item DRYING_RACK = registerBlockItem("drying_rack", MaterialBlocks.DRYING_RACK);
	public static final Item CUTTING_SURFACE = registerBlockItem("cutting_surface", MaterialBlocks.CUTTING_SURFACE);
	public static final Item SEWING_FRAME = registerBlockItem("sewing_frame", MaterialBlocks.SEWING_FRAME);
	public static final Item WOODEN_WORKBENCH = registerBlockItem("wooden_workbench", MaterialBlocks.WOODEN_WORKBENCH);
	public static final Item STORAGE_RACK = registerBlockItem("storage_rack", MaterialBlocks.STORAGE_RACK);

	public static final ItemGroup GROUP = FabricItemGroup.builder()
			.displayName(Text.translatable("itemGroup.project_realism.materials"))
			.icon(() -> new ItemStack(STONE_SCRAPER))
			.entries((context, entries) -> {
				entries.add(SCRAPING_FRAME);
				entries.add(DRYING_RACK);
				entries.add(CUTTING_SURFACE);
				entries.add(SEWING_FRAME);
				entries.add(WOODEN_WORKBENCH);
				entries.add(STORAGE_RACK);
				entries.add(STONE_SCRAPER);
				entries.add(BONE_AWL);
				entries.add(BONE_NEEDLE);
				entries.add(RAW_HIDE);
				entries.add(LEATHER_PIECE);
				entries.add(HIDE_SCRAP);
				entries.add(TALLOW_WAX);
				entries.add(PINE_RESIN);
				entries.add(RAW_GOURD);
				entries.add(GOURD_FLASK);
			})
			.build();

	private MaterialItems() {
	}

	private static Item register(String path, Item item) {
		return Registry.register(Registries.ITEM, ProjectRealism.id(path), item);
	}

	private static Item registerBlockItem(String path, net.minecraft.block.Block block) {
		return register(path, new BlockItem(block, new Item.Settings()));
	}

	public static void register() {
		Registry.register(Registries.ITEM_GROUP,
				RegistryKey.of(RegistryKeys.ITEM_GROUP, ProjectRealism.id("materials")),
				GROUP);
		ProjectRealism.LOGGER.info("Material processing tools, hides, and workstations registered.");
	}

	public static class StoneScraperItem extends Item {
		public StoneScraperItem(Settings settings) {
			super(settings);
		}

		public static int getSharpness(ItemStack stack) {
			NbtComponent c = stack.get(DataComponentTypes.CUSTOM_DATA);
			if (c != null) {
				NbtCompound t = c.copyNbt();
				if (t.contains("Sharpness")) return Math.max(0, Math.min(100, t.getInt("Sharpness")));
			}
			return 100;
		}

		public static void setSharpness(ItemStack stack, int s) {
			NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, t -> t.putInt("Sharpness", Math.max(0, Math.min(100, s))));
		}

		@Override
		public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
			super.appendTooltip(stack, context, tooltip, type);
			int s = getSharpness(stack);
			tooltip.add(Text.translatable("project_realism.tooltip.scraper_edge", s)
					.formatted(s < 35 ? Formatting.RED : Formatting.GRAY));
			tooltip.add(Text.translatable("item.project_realism.stone_scraper.tooltip")
					.formatted(Formatting.DARK_GRAY));
		}
	}

	public static class RawGourdItem extends Item {
		public RawGourdItem(Settings settings) {
			super(settings.maxCount(4));
		}

		public static MaterialAging.GourdState readGourdState(ItemStack stack) {
			MaterialAging.GourdState gs = new MaterialAging.GourdState();
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
			NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, root -> {
				NbtCompound t = new NbtCompound();
				t.putDouble("Moist", gs.moisture);
				t.putDouble("Hard", gs.shellHardness);
				t.putDouble("Crack", gs.crackSeverity);
				t.putDouble("Seal", gs.sealLevel);
				root.put("Gourd", t);
			});
		}

		@Override
		public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
			super.appendTooltip(stack, context, tooltip, type);
			MaterialAging.GourdState gs = readGourdState(stack);
			tooltip.add(Text.translatable("project_realism.tooltip.raw_gourd",
					(int) Math.round(gs.moisture * 100.0),
					(int) Math.round(gs.shellHardness * 100.0),
					(int) Math.round(gs.crackSeverity * 100.0))
					.formatted(gs.isCuredForCarving() ? Formatting.GREEN : Formatting.GRAY));
		}
	}

	private static class ToolHintItem extends Item {
		private final String key;

		private ToolHintItem(Settings settings, String key) {
			super(settings);
			this.key = key;
		}

		@Override
		public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
			super.appendTooltip(stack, context, tooltip, type);
			tooltip.add(Text.translatable(key).formatted(Formatting.DARK_GRAY));
		}
	}
}
