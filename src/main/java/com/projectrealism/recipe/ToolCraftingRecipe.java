package com.projectrealism.recipe;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.projectrealism.primitive.ToolCondition;
import com.projectrealism.wood.FreshLogBlock;
import com.projectrealism.wood.RoughPlankBlock;
import com.projectrealism.wood.SplitWoodBlock;
import com.projectrealism.wood.WoodSpecies;
import net.minecraft.block.Block;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.recipe.CraftingRecipe;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.RecipeSerializer;
import net.minecraft.recipe.book.CraftingRecipeCategory;
import net.minecraft.recipe.input.CraftingRecipeInput;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Custom shapeless crafting recipe supporting reusable/degradable tools, moisture checks,
 * tool sharpening, and cordage/fiber binding quality initialization.
 */
public final class ToolCraftingRecipe implements CraftingRecipe {
	private final String group;
	private final CraftingRecipeCategory category;
	private final List<Ingredient> ingredients;
	private final Optional<Ingredient> tool;
	private final ItemStack result;
	private final boolean keepTool;
	private final boolean damageTool;
	private final boolean requireDryWood;
	private final boolean inheritDryWood;
	private final boolean sharpenTool;
	private final boolean tightenBinding;
	private final boolean initCordage;
	private final boolean initFiber;

	public ToolCraftingRecipe(String group,
							  CraftingRecipeCategory category,
							  List<Ingredient> ingredients,
							  Optional<Ingredient> tool,
							  ItemStack result,
							  boolean keepTool,
							  boolean damageTool,
							  boolean requireDryWood,
							  boolean inheritDryWood,
							  boolean sharpenTool,
							  boolean tightenBinding,
							  boolean initCordage,
							  boolean initFiber) {
		this.group = group;
		this.category = category;
		this.ingredients = ingredients;
		this.tool = tool;
		this.result = result;
		this.keepTool = keepTool;
		this.damageTool = damageTool;
		this.requireDryWood = requireDryWood;
		this.inheritDryWood = inheritDryWood;
		this.sharpenTool = sharpenTool;
		this.tightenBinding = tightenBinding;
		this.initCordage = initCordage;
		this.initFiber = initFiber;
	}

	@Override
	public String getGroup() {
		return group;
	}

	@Override
	public CraftingRecipeCategory getCategory() {
		return category;
	}

	@Override
	public DefaultedList<Ingredient> getIngredients() {
		DefaultedList<Ingredient> list = DefaultedList.of();
		tool.ifPresent(list::add);
		list.addAll(ingredients);
		return list;
	}

	@Override
	public boolean isIgnoredInRecipeBook() {
		return false;
	}

	@Override
	public boolean fits(int width, int height) {
		int needed = ingredients.size() + (tool.isPresent() ? 1 : 0);
		return width * height >= needed;
	}

	@Override
	public ItemStack getResult(RegistryWrapper.WrapperLookup registriesLookup) {
		return result;
	}

	@Override
	public RecipeSerializer<?> getSerializer() {
		return ModRecipes.TOOL_CRAFTING_SERIALIZER;
	}

	private record MatchResult(int toolSlot, List<Integer> ingredientSlots) {
	}

	private Optional<MatchResult> findMatch(CraftingRecipeInput input) {
		List<Integer> nonEmptySlots = new ArrayList<>();
		for (int i = 0; i < input.getSize(); i++) {
			if (!input.getStackInSlot(i).isEmpty()) {
				nonEmptySlots.add(i);
			}
		}

		int expected = ingredients.size() + (tool.isPresent() ? 1 : 0);
		if (nonEmptySlots.size() != expected) {
			return Optional.empty();
		}

		if (tool.isPresent()) {
			Ingredient toolIng = tool.get();
			for (int candidateToolSlot : nonEmptySlots) {
				ItemStack toolStack = input.getStackInSlot(candidateToolSlot);
				if (!toolIng.test(toolStack)) {
					continue;
				}
				if (sharpenTool && ToolCondition.getSharpness(toolStack) >= 98) {
					continue;
				}
				if (tightenBinding && ToolCondition.getBinding(toolStack) >= 98
						&& ToolCondition.isCordageBound(toolStack)) {
					continue;
				}
				List<Integer> remaining = new ArrayList<>(nonEmptySlots);
				remaining.remove(Integer.valueOf(candidateToolSlot));
				if (matchIngredients(input, remaining, 0, new boolean[remaining.size()])) {
					return Optional.of(new MatchResult(candidateToolSlot, remaining));
				}
			}
			return Optional.empty();
		}

		if (matchIngredients(input, nonEmptySlots, 0, new boolean[nonEmptySlots.size()])) {
			return Optional.of(new MatchResult(-1, nonEmptySlots));
		}
		return Optional.empty();
	}

	private boolean matchIngredients(CraftingRecipeInput input, List<Integer> slots,
									 int ingIdx, boolean[] used) {
		if (ingIdx == ingredients.size()) {
			return true;
		}
		Ingredient target = ingredients.get(ingIdx);
		for (int i = 0; i < slots.size(); i++) {
			if (used[i]) {
				continue;
			}
			ItemStack stack = input.getStackInSlot(slots.get(i));
			if (!target.test(stack)) {
				continue;
			}
			if (requireDryWood && !isDryInput(stack)) {
				continue;
			}
			used[i] = true;
			if (matchIngredients(input, slots, ingIdx + 1, used)) {
				return true;
			}
			used[i] = false;
		}
		return false;
	}

	private static boolean isDryInput(ItemStack stack) {
		Block b = Block.getBlockFromItem(stack.getItem());
		if (b instanceof FreshLogBlock) {
			return false;
		}
		if (b instanceof SplitWoodBlock || b instanceof RoughPlankBlock) {
			return ToolCondition.isDry(stack);
		}
		return WoodSpecies.isDryLog(b) || ToolCondition.isDry(stack);
	}

	@Override
	public boolean matches(CraftingRecipeInput input, World world) {
		return findMatch(input).isPresent();
	}

	@Override
	public ItemStack craft(CraftingRecipeInput input, RegistryWrapper.WrapperLookup lookup) {
		Optional<MatchResult> match = findMatch(input);
		if (match.isEmpty()) {
			return ItemStack.EMPTY;
		}
		MatchResult mr = match.get();

		if (sharpenTool && mr.toolSlot >= 0) {
			ItemStack copy = input.getStackInSlot(mr.toolSlot).copyWithCount(1);
			ToolCondition.SharpenResult sr = ToolCondition.sharpen(copy, Random.create());
			return sr == ToolCondition.SharpenResult.SHATTERED ? new ItemStack(Items.STICK) : copy;
		}

		if (tightenBinding && mr.toolSlot >= 0) {
			ItemStack copy = input.getStackInSlot(mr.toolSlot).copyWithCount(1);
			ToolCondition.tightenBinding(copy, true);
			return copy;
		}

		ItemStack out = result.copy();
		if (initCordage) {
			ToolCondition.initTool(out, true);
		} else if (initFiber) {
			ToolCondition.initTool(out, false);
		}

		if (inheritDryWood) {
			boolean allDry = true;
			for (int slot : mr.ingredientSlots) {
				if (!isDryInput(input.getStackInSlot(slot))) {
					allDry = false;
					break;
				}
			}
			ToolCondition.setDry(out, allDry);
		}
		return out;
	}

	@Override
	public DefaultedList<ItemStack> getRemainder(CraftingRecipeInput input) {
		DefaultedList<ItemStack> rem = DefaultedList.ofSize(input.getSize(), ItemStack.EMPTY);
		if (!keepTool) {
			return rem;
		}
		Optional<MatchResult> match = findMatch(input);
		if (match.isEmpty() || match.get().toolSlot < 0) {
			return rem;
		}
		int toolSlot = match.get().toolSlot;
		ItemStack copy = input.getStackInSlot(toolSlot).copyWithCount(1);
		if (damageTool) {
			ToolCondition.applyCraftingWear(copy);
			if (copy.isDamageable() && copy.getDamage() >= copy.getMaxDamage()) {
				return rem;
			}
		}
		rem.set(toolSlot, copy);
		return rem;
	}

	public static final class Serializer implements RecipeSerializer<ToolCraftingRecipe> {
		private static final MapCodec<ToolCraftingRecipe> CODEC = RecordCodecBuilder.mapCodec(inst -> inst.group(
				Codec.STRING.optionalFieldOf("group", "").forGetter(r -> r.group),
				CraftingRecipeCategory.CODEC.optionalFieldOf("category", CraftingRecipeCategory.MISC)
						.forGetter(r -> r.category),
				Ingredient.DISALLOW_EMPTY_CODEC.listOf().fieldOf("ingredients").forGetter(r -> r.ingredients),
				Ingredient.DISALLOW_EMPTY_CODEC.optionalFieldOf("tool").forGetter(r -> r.tool),
				ItemStack.VALIDATED_CODEC.fieldOf("result").forGetter(r -> r.result),
				Codec.BOOL.optionalFieldOf("keep_tool", true).forGetter(r -> r.keepTool),
				Codec.BOOL.optionalFieldOf("damage_tool", true).forGetter(r -> r.damageTool),
				Codec.BOOL.optionalFieldOf("require_dry_wood", false).forGetter(r -> r.requireDryWood),
				Codec.BOOL.optionalFieldOf("inherit_dry_wood", false).forGetter(r -> r.inheritDryWood),
				Codec.BOOL.optionalFieldOf("sharpen_tool", false).forGetter(r -> r.sharpenTool),
				Codec.BOOL.optionalFieldOf("tighten_binding", false).forGetter(r -> r.tightenBinding),
				Codec.BOOL.optionalFieldOf("init_cordage", false).forGetter(r -> r.initCordage),
				Codec.BOOL.optionalFieldOf("init_fiber", false).forGetter(r -> r.initFiber)
		).apply(inst, ToolCraftingRecipe::new));

		private static final PacketCodec<RegistryByteBuf, ToolCraftingRecipe> PACKET_CODEC =
				new PacketCodec<>() {
					@Override
					public ToolCraftingRecipe decode(RegistryByteBuf buf) {
						String group = buf.readString();
						CraftingRecipeCategory category = buf.readEnumConstant(CraftingRecipeCategory.class);
						int count = buf.readVarInt();
						List<Ingredient> ingredients = new ArrayList<>(count);
						for (int i = 0; i < count; i++) {
							ingredients.add(Ingredient.PACKET_CODEC.decode(buf));
						}
						Optional<Ingredient> tool = buf.readBoolean()
								? Optional.of(Ingredient.PACKET_CODEC.decode(buf))
								: Optional.empty();
						ItemStack result = ItemStack.PACKET_CODEC.decode(buf);
						boolean keepTool = buf.readBoolean();
						boolean damageTool = buf.readBoolean();
						boolean requireDryWood = buf.readBoolean();
						boolean inheritDryWood = buf.readBoolean();
						boolean sharpenTool = buf.readBoolean();
						boolean tightenBinding = buf.readBoolean();
						boolean initCordage = buf.readBoolean();
						boolean initFiber = buf.readBoolean();
						return new ToolCraftingRecipe(group, category, ingredients, tool, result,
								keepTool, damageTool, requireDryWood, inheritDryWood,
								sharpenTool, tightenBinding, initCordage, initFiber);
					}

					@Override
					public void encode(RegistryByteBuf buf, ToolCraftingRecipe value) {
						buf.writeString(value.group);
						buf.writeEnumConstant(value.category);
						buf.writeVarInt(value.ingredients.size());
						for (Ingredient ing : value.ingredients) {
							Ingredient.PACKET_CODEC.encode(buf, ing);
						}
						buf.writeBoolean(value.tool.isPresent());
						value.tool.ifPresent(ing -> Ingredient.PACKET_CODEC.encode(buf, ing));
						ItemStack.PACKET_CODEC.encode(buf, value.result);
						buf.writeBoolean(value.keepTool);
						buf.writeBoolean(value.damageTool);
						buf.writeBoolean(value.requireDryWood);
						buf.writeBoolean(value.inheritDryWood);
						buf.writeBoolean(value.sharpenTool);
						buf.writeBoolean(value.tightenBinding);
						buf.writeBoolean(value.initCordage);
						buf.writeBoolean(value.initFiber);
					}
				};

		@Override
		public MapCodec<ToolCraftingRecipe> codec() {
			return CODEC;
		}

		@Override
		public PacketCodec<RegistryByteBuf, ToolCraftingRecipe> packetCodec() {
			return PACKET_CODEC;
		}
	}
}
