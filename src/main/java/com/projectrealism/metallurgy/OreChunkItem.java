package com.projectrealism.metallurgy;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.NbtComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.TypedActionResult;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.List;
import java.util.Random;

/**
 * Raw or processed mineral rock chunk carrying continuous {@link OreComposition} data.
 * Right-clicking water washes out light silica gangue; striking on hard stone crushes and concentrates it.
 */
public class OreChunkItem extends Item {
	public OreChunkItem(Settings settings) {
		super(settings.maxCount(16));
	}

	public static OreComposition readOre(ItemStack stack) {
		NbtComponent comp = stack.get(DataComponentTypes.CUSTOM_DATA);
		if (comp != null) {
			NbtCompound root = comp.copyNbt();
			if (root.contains("Ore")) {
				return OreComposition.fromNbt(root.getCompound("Ore"));
			}
		}
		return OreComposition.malachiteOre(0.35, 0.30);
	}

	public static void writeOre(ItemStack stack, OreComposition ore) {
		if (stack.isEmpty() || ore == null) return;
		NbtComponent.set(DataComponentTypes.CUSTOM_DATA, stack, root -> root.put("Ore", ore.toNbt()));
	}

	public static ItemStack create(OreComposition ore) {
		ItemStack stack = new ItemStack(MetallurgyItems.ORE_CHUNK);
		writeOre(stack, ore);
		return stack;
	}

	public static double massKg(ItemStack stack) {
		return Math.max(0.08, readOre(stack).massKg);
	}

	@Override
	public Text getName(ItemStack stack) {
		OreComposition ore = readOre(stack);
		MineralSpecies dom = ore.dominantSpecies();
		String prefix = ore.crushedDegree >= 0.65 ? "Washed/Crushed " : (ore.crushedDegree >= 0.25 ? "Crushed " : "Raw ");
		return Text.literal(prefix + dom.displayName + " Ore");
	}

	@Override
	public TypedActionResult<ItemStack> use(World world, PlayerEntity user, Hand hand) {
		ItemStack stack = user.getStackInHand(hand);
		BlockHitResult fluidHit = raycast(world, user, RaycastContext.FluidHandling.SOURCE_ONLY);
		if (fluidHit.getType() == HitResult.Type.BLOCK) {
			BlockPos pos = fluidHit.getBlockPos();
			if (world.getFluidState(pos).isIn(FluidTags.WATER)) {
				if (!world.isClient) {
					OreComposition ore = readOre(stack);
					if (ore.crushedDegree < 0.25) {
						user.sendMessage(Text.literal("Crush this ore on stone before washing out gangue.")
								.formatted(Formatting.YELLOW), true);
						return TypedActionResult.fail(stack);
					}
					double before = ore.gangueFraction();
					ore.washConcentrate(0.65);
					ItemStack out = stack.copy();
					out.setCount(1);
					writeOre(out, ore);
					if (stack.getCount() > 1) {
						stack.decrement(1);
						if (!user.getInventory().insertStack(out)) user.dropItem(out, false);
					} else {
						user.setStackInHand(hand, out);
					}
					world.playSound(null, pos, SoundEvents.ITEM_BUCKET_EMPTY, SoundCategory.PLAYERS, 0.55f, 1.25f);
					user.sendMessage(Text.literal(String.format("Washed gangue %.0f%% -> %.0f%% (%.0f%% metal grade)",
									before * 100, ore.gangueFraction() * 100, (1.0 - ore.gangueFraction()) * 100))
							.formatted(Formatting.AQUA), true);
				}
				return TypedActionResult.success(stack, world.isClient);
			}
		}
		return super.use(world, user, hand);
	}

	@Override
	public ActionResult useOnBlock(ItemUsageContext ctx) {
		World world = ctx.getWorld();
		PlayerEntity player = ctx.getPlayer();
		BlockPos pos = ctx.getBlockPos();
		BlockState state = world.getBlockState(pos);
		if (player == null) return ActionResult.PASS;

		// Crushing ore chunk on hard stone / anvil surface with offhand stone or bare-hand impact
		if (player.isSneaking() && (state.isOf(Blocks.STONE) || state.isOf(Blocks.DEEPSLATE)
				|| state.isOf(Blocks.BASALT) || state.isOf(Blocks.GRANITE) || state.isOf(Blocks.COBBLESTONE))) {
			if (!world.isClient) {
				ItemStack stack = ctx.getStack();
				OreComposition ore = readOre(stack);
				ore.crushAndSort(0.50, new Random(world.random.nextLong()));
				ItemStack out = stack.copy();
				out.setCount(1);
				writeOre(out, ore);
				if (stack.getCount() > 1) {
					stack.decrement(1);
					if (!player.getInventory().insertStack(out)) player.dropItem(out, false);
				} else {
					player.setStackInHand(ctx.getHand(), out);
				}
				world.playSound(null, pos, SoundEvents.BLOCK_STONE_BREAK, SoundCategory.BLOCKS, 0.6f, 1.2f);
				player.sendMessage(Text.literal(String.format("Crushed ore (%d%% sorted, %.0f%% gangue left)",
								(int) Math.round(ore.crushedDegree * 100), ore.gangueFraction() * 100))
						.formatted(Formatting.GOLD), true);
			}
			return ActionResult.SUCCESS;
		}
		return super.useOnBlock(ctx);
	}

	@Override
	public void appendTooltip(ItemStack stack, TooltipContext context, List<Text> tooltip, TooltipType type) {
		OreComposition ore = readOre(stack);
		tooltip.add(Text.literal(String.format("Mass: %.2f kg | Grade: %.0f%% | Crushed: %.0f%%",
				ore.massKg, (1.0 - ore.gangueFraction()) * 100.0, ore.crushedDegree * 100.0)).formatted(Formatting.GRAY));
		double cu = ore.recoverableElementFraction(MineralSpecies.TargetElement.CU) * 100;
		double sn = ore.recoverableElementFraction(MineralSpecies.TargetElement.SN) * 100;
		double fe = ore.recoverableElementFraction(MineralSpecies.TargetElement.FE) * 100;
		double as = ore.recoverableElementFraction(MineralSpecies.TargetElement.AS) * 100;
		tooltip.add(Text.literal(String.format("Assay Cu:%.1f%% Sn:%.1f%% Fe:%.1f%% As:%.1f%% S:%.1f%%",
				cu, sn, fe, as, ore.sulfurContent * 100)).formatted(Formatting.AQUA));
		if (ore.sulfurContent > 0.02 && ore.roastedFraction < 0.7) {
			tooltip.add(Text.literal("Sulfidic ore — roast with oxygen before reduction!").formatted(Formatting.YELLOW));
		}
	}
}
