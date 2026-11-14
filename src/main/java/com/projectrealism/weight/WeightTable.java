package com.projectrealism.weight;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.projectrealism.ProjectRealism;
import net.minecraft.registry.Registries;
import net.minecraft.item.Item;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps every item to a mass in kilograms.
 *
 * <p>Three layers, in order of precedence:</p>
 * <ol>
 *   <li>datapack entries ({@code data/<namespace>/project_realism/weights.json})</li>
 *   <li>the built-in table shipped with the mod</li>
 *   <li>a conservative estimate for anything nobody bothered to weigh</li>
 * </ol>
 */
public final class WeightTable {
	private static final Gson GSON = new Gson();
	private static final String BUILT_IN_PATH = "/data/project_realism/project_realism/weights.json";

	/** Fallback masses, in kg, for items the table does not know about. */
	private static final double FALLBACK_BLOCK = 4.0;
	private static final double FALLBACK_TOOL = 3.0;
	private static final double FALLBACK_FOOD = 0.3;
	private static final double FALLBACK_DEFAULT = 0.2;

	private final Map<Identifier, Double> weights = new HashMap<>();

	public Map<Identifier, Double> weights() {
		return weights;
	}

	public void loadBuiltIn() {
		weights.clear();
		try (InputStream stream = WeightTable.class.getResourceAsStream(BUILT_IN_PATH)) {
			if (stream == null) {
				ProjectRealism.LOGGER.error("The built-in weight table is missing from the jar. Everything weighs nothing. Enjoy.");
				return;
			}
			JsonObject root = GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
			merge(root, "built-in table");
		} catch (Exception e) {
			ProjectRealism.LOGGER.error("Failed to read the built-in weight table.", e);
		}
	}

	/**
	 * Merges a weight JSON document into the table. Accepted shapes:
	 * <pre>
	 * { "minecraft:dirt": 2.0 }
	 * { "items": { "minecraft:dirt": 2.0 } }
	 * </pre>
	 */
	public int merge(JsonObject root, String sourceName) {
		if (root == null) {
			return 0;
		}
		JsonObject items = root.has("items") && root.get("items").isJsonObject()
				? root.getAsJsonObject("items")
				: root;

		int applied = 0;
		for (Map.Entry<String, JsonElement> entry : items.entrySet()) {
			String key = entry.getKey();
			if (key.startsWith("_") || key.startsWith("#") || !entry.getValue().isJsonPrimitive()) {
				continue; // comments and unsupported tag entries
			}
			Identifier id = Identifier.tryParse(key);
			if (id == null) {
				ProjectRealism.LOGGER.warn("Ignoring bad item id '{}' in {}.", key, sourceName);
				continue;
			}
			double kg = Math.max(0.0, entry.getValue().getAsDouble());
			weights.put(id, kg);
			applied++;
		}
		return applied;
	}

	/** Mass of a single unit of this item, in kg. */
	public double weightOf(Item item) {
		Identifier id = Registries.ITEM.getId(item);
		Double known = weights.get(id);
		if (known != null) {
			return known;
		}
		return estimate(item);
	}

	/**
	 * Unknown items still need a mass. The estimate is intentionally on the light side: a modded
	 * item that weighs less than it should is a minor inaccuracy, whereas a modded item that weighs
	 * 40 kg because it happens to be a block is a bug report.
	 */
	private static double estimate(Item item) {
		Identifier id = Registries.ITEM.getId(item);
		String path = id.getPath();

		if (item instanceof net.minecraft.item.BlockItem) {
			return FALLBACK_BLOCK;
		}
		if (path.contains("arrow") || path.contains("torch") || path.contains("string")
				|| path.contains("feather") || path.contains("paper") || path.contains("seed")
				|| path.contains("sapling") || path.contains("dust") || path.contains("nugget")) {
			return 0.05;
		}
		if (path.endsWith("_ingot") || path.endsWith("_tool") || path.endsWith("_pickaxe")
				|| path.endsWith("_axe") || path.endsWith("_shovel") || path.endsWith("_hoe")
				|| path.endsWith("_sword") || path.endsWith("_helmet") || path.endsWith("_chestplate")
				|| path.endsWith("_leggings") || path.endsWith("_boots") || path.endsWith("_bucket")) {
			return FALLBACK_TOOL;
		}
		if (path.contains("apple") || path.contains("bread") || path.contains("beef")
				|| path.contains("porkchop") || path.contains("chicken") || path.contains("mutton")
				|| path.contains("carrot") || path.contains("potato") || path.contains("cod")
				|| path.contains("salmon") || path.contains("cookie") || path.contains("pie")
				|| path.contains("stew") || path.contains("soup")) {
			return FALLBACK_FOOD;
		}
		return FALLBACK_DEFAULT;
	}

	/** Ids that were weighed but no longer exist — used only for logging during reloads. */
	public List<Identifier> unresolvedItems() {
		List<Identifier> missing = new ArrayList<>();
		for (Identifier id : weights.keySet()) {
			if (!Registries.ITEM.containsId(id)) {
				missing.add(id);
			}
		}
		return missing;
	}

	public int size() {
		return weights.size();
	}
}
