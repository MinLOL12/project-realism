#!/usr/bin/env python3
"""Generates the built-in weight table for Project Realism.

Masses are in kilograms per single item. They are deliberately gamey rather than
literal: a real cubic metre of oak is about 700 kg, but Minecraft blocks are not
really a cubic metre of material and a 700 kg log would end the game instantly.
The reference points the design hangs on are:

    dirt        2 kg
    cobblestone 5 kg
    oak log    12 kg
    iron block 40 kg
    capacity   30 kg per player

Run this after changing any numbers:  python3 tools/generate_weights.py
"""

import json
import os

W = {}


def add(kg, *ids):
    for entry in ids:
        W[entry] = kg


def expand(kg, pattern, names):
    for name in names:
        W[pattern.format(name)] = kg


COLORS = [
    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
]

WOODS = ["oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove",
         "cherry", "bamboo", "crimson", "warped", "pale_oak"]

LOG_WEIGHTS = {
    "oak": 12.0, "spruce": 9.0, "birch": 10.0, "jungle": 13.0, "acacia": 11.0,
    "dark_oak": 14.0, "mangrove": 12.0, "cherry": 9.5, "bamboo": 4.0,
    "crimson": 11.0, "warped": 11.0, "pale_oak": 12.0,
}

# ---------------------------------------------------------------- terrain ----
add(2.0, "minecraft:dirt", "minecraft:grass_block", "minecraft:coarse_dirt")
add(2.2, "minecraft:podzol", "minecraft:rooted_dirt", "minecraft:mycelium")
add(2.3, "minecraft:mud", "minecraft:muddy_mangrove_roots", "minecraft:farmland")
add(2.4, "minecraft:clay", "minecraft:soul_soil", "minecraft:soul_sand")
add(2.6, "minecraft:sand", "minecraft:red_sand", "minecraft:suspicious_sand",
    "minecraft:suspicious_gravel")
add(3.0, "minecraft:gravel", "minecraft:dirt_path")
add(5.0, "minecraft:cobblestone", "minecraft:mossy_cobblestone", "minecraft:cobbled_deepslate")
add(5.5, "minecraft:stone", "minecraft:stone_bricks", "minecraft:mossy_stone_bricks",
    "minecraft:cracked_stone_bricks", "minecraft:chiseled_stone_bricks",
    "minecraft:smooth_stone", "minecraft:deepslate", "minecraft:deepslate_bricks",
    "minecraft:deepslate_tiles", "minecraft:polished_deepslate",
    "minecraft:cracked_deepslate_bricks", "minecraft:cracked_deepslate_tiles",
    "minecraft:chiseled_deepslate")
add(5.2, "minecraft:granite", "minecraft:diorite", "minecraft:andesite",
    "minecraft:polished_granite", "minecraft:polished_diorite",
    "minecraft:polished_andesite", "minecraft:blackstone", "minecraft:polished_blackstone",
    "minecraft:polished_blackstone_bricks", "minecraft:chiseled_polished_blackstone",
    "minecraft:basalt", "minecraft:polished_basalt", "minecraft:smooth_basalt")
add(4.5, "minecraft:tuff", "minecraft:tuff_bricks", "minecraft:polished_tuff",
    "minecraft:chiseled_tuff", "minecraft:chiseled_tuff_bricks")
add(4.8, "minecraft:calcite", "minecraft:dripstone_block", "minecraft:pointed_dripstone")
add(2.5, "minecraft:netherrack", "minecraft:nether_bricks", "minecraft:cracked_nether_bricks",
    "minecraft:chiseled_nether_bricks", "minecraft:magma_block", "minecraft:nether_wart_block")
add(5.0, "minecraft:end_stone", "minecraft:end_stone_bricks", "minecraft:purpur_block",
    "minecraft:purpur_pillar")
add(4.0, "minecraft:sandstone", "minecraft:red_sandstone", "minecraft:cut_sandstone",
    "minecraft:cut_red_sandstone", "minecraft:chiseled_sandstone",
    "minecraft:chiseled_red_sandstone", "minecraft:smooth_sandstone",
    "minecraft:smooth_red_sandstone")
add(6.0, "minecraft:bricks", "minecraft:terracotta", "minecraft:packed_mud")
add(4.2, "minecraft:prismarine", "minecraft:prismarine_bricks", "minecraft:dark_prismarine")
add(3.5, "minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice", "minecraft:frosted_ice")
add(1.0, "minecraft:snow_block", "minecraft:snow")
add(3.0, "minecraft:glass", "minecraft:tinted_glass", "minecraft:glowstone",
    "minecraft:sea_lantern", "minecraft:ochre_froglight", "minecraft:verdant_froglight",
    "minecraft:pearlescent_froglight")
expand(2.7, "minecraft:{}_stained_glass", COLORS)
expand(2.7, "minecraft:{}_stained_glass_pane", COLORS)
add(1.0, "minecraft:glass_pane")

# --------------------------------------------------------------- minerals ----
add(40.0, "minecraft:iron_block")
add(60.0, "minecraft:gold_block")
add(45.0, "minecraft:copper_block", "minecraft:exposed_copper", "minecraft:weathered_copper",
    "minecraft:oxidized_copper", "minecraft:cut_copper", "minecraft:waxed_copper_block")
add(80.0, "minecraft:netherite_block")
add(55.0, "minecraft:diamond_block")
add(50.0, "minecraft:emerald_block", "minecraft:lapis_block")
add(42.0, "minecraft:redstone_block")
add(30.0, "minecraft:coal_block")
add(25.0, "minecraft:obsidian", "minecraft:crying_obsidian")
add(20.0, "minecraft:ancient_debris")
add(4.0, "minecraft:raw_iron_block", "minecraft:raw_copper_block")
add(6.0, "minecraft:raw_gold_block")
add(3.0, "minecraft:amethyst_block", "minecraft:budding_amethyst", "minecraft:anvil",
    "minecraft:chipped_anvil", "minecraft:damaged_anvil")  # anvil re-added below

W["minecraft:anvil"] = 60.0
W["minecraft:chipped_anvil"] = 55.0
W["minecraft:damaged_anvil"] = 50.0
W["minecraft:amethyst_block"] = 3.0
W["minecraft:budding_amethyst"] = 3.0

add(4.0, "minecraft:iron_ingot", "minecraft:copper_ingot")
add(7.0, "minecraft:gold_ingot", "minecraft:netherite_ingot")
add(4.6, "minecraft:netherite_scrap")
add(1.5, "minecraft:diamond", "minecraft:emerald", "minecraft:amethyst_shard")
add(0.45, "minecraft:iron_nugget", "minecraft:gold_nugget")
add(0.3, "minecraft:redstone", "minecraft:lapis_lazuli", "minecraft:quartz")
add(0.5, "minecraft:coal", "minecraft:charcoal")
add(0.2, "minecraft:flint", "minecraft:gunpowder", "minecraft:glowstone_dust",
    "minecraft:sugar", "minecraft:blaze_powder", "minecraft:bone_meal")
add(0.1, "minecraft:stick", "minecraft:bone", "minecraft:string", "minecraft:feather",
    "minecraft:paper", "minecraft:flint_and_steel")
add(0.05, "minecraft:arrow", "minecraft:spectral_arrow", "minecraft:torch",
    "minecraft:soul_torch", "minecraft:redstone_torch", "minecraft:firework_rocket",
    "minecraft:snowball", "minecraft:egg", "minecraft:slime_ball")
add(0.05, "minecraft:oak_sapling", "minecraft:spruce_sapling", "minecraft:birch_sapling",
    "minecraft:jungle_sapling", "minecraft:acacia_sapling", "minecraft:dark_oak_sapling",
    "minecraft:cherry_sapling", "minecraft:mangrove_propagule", "minecraft:bamboo")
add(0.2, "minecraft:ender_pearl", "minecraft:ender_eye", "minecraft:blaze_rod",
    "minecraft:breeze_rod", "minecraft:shulker_shell", "minecraft:nautilus_shell",
    "minecraft:prismarine_shard", "minecraft:prismarine_crystals")
add(0.4, "minecraft:book", "minecraft:writable_book", "minecraft:written_book",
    "minecraft:ink_sac", "minecraft:leather")
add(1.0, "minecraft:sugar_cane", "minecraft:kelp", "minecraft:vine", "minecraft:lily_pad",
    "minecraft:brown_mushroom", "minecraft:red_mushroom", "minecraft:nether_wart")

# ------------------------------------------------------------------ wood ----
for wood, kg in LOG_WEIGHTS.items():
    W[f"minecraft:{wood}_log"] = kg
    W[f"minecraft:{wood}_wood"] = kg
    if wood not in ("bamboo", "crimson", "warped", "pale_oak"):
        W[f"minecraft:stripped_{wood}_log"] = kg - 1.0
        W[f"minecraft:stripped_{wood}_wood"] = kg - 1.0
    W[f"minecraft:{wood}_planks"] = 3.0 if wood not in ("bamboo", "crimson", "warped") else 2.5
    W[f"minecraft:{wood}_slab"] = 1.5
    W[f"minecraft:{wood}_stairs"] = 2.2
    W[f"minecraft:{wood}_fence"] = 1.2
    W[f"minecraft:{wood}_fence_gate"] = 1.6
    W[f"minecraft:{wood}_door"] = 4.0
    W[f"minecraft:{wood}_trapdoor"] = 2.0
    W[f"minecraft:{wood}_pressure_plate"] = 0.6
    W[f"minecraft:{wood}_button"] = 0.2
    W[f"minecraft:{wood}_sign"] = 1.0
    W[f"minecraft:{wood}_hanging_sign"] = 1.4
    W[f"minecraft:{wood}_boat"] = 20.0 if wood not in ("bamboo",) else 12.0
    W[f"minecraft:{wood}_chest_boat"] = 24.0 if wood != "bamboo" else 16.0
    W[f"minecraft:{wood}_leaves"] = 0.5

add(3.0, "minecraft:bamboo_block", "minecraft:stripped_bamboo_block",
    "minecraft:bamboo_mosaic", "minecraft:bamboo_mosaic_slab")
add(10.0, "minecraft:crafting_table", "minecraft:cartography_table",
    "minecraft:fletching_table", "minecraft:smithing_table", "minecraft:loom")
add(12.0, "minecraft:bookshelf", "minecraft:chiseled_bookshelf", "minecraft:chest")
add(8.0, "minecraft:lectern", "minecraft:composter", "minecraft:barrel")
add(25.0, "minecraft:furnace", "minecraft:blast_furnace", "minecraft:smoker")
add(40.0, "minecraft:enchanting_table")
add(10.0, "minecraft:brewing_stand", "minecraft:grindstone", "minecraft:stonecutter")
add(15.0, "minecraft:cauldron", "minecraft:water_cauldron", "minecraft:lava_cauldron")
add(15.0, "minecraft:shulker_box")
expand(15.5, "minecraft:{}_shulker_box", COLORS)
add(60.0, "minecraft:beacon")
add(30.0, "minecraft:conduit", "minecraft:respawn_anchor")
add(10.0, "minecraft:bed")
expand(10.0, "minecraft:{}_bed", COLORS)
add(2.0, "minecraft:torch", "minecraft:lantern")
W["minecraft:torch"] = 0.2
add(3.0, "minecraft:soul_lantern", "minecraft:sea_pickle")
add(0.5, "minecraft:ladder", "minecraft:scaffolding")

# ---------------------------------------------------------------- organics ----
expand(0.5, "minecraft:{}_wool", COLORS)
add(0.5, "minecraft:wool")
expand(0.15, "minecraft:{}_carpet", COLORS)
add(0.15, "minecraft:carpet")
add(8.0, "minecraft:hay_block")
add(10.0, "minecraft:melon")
add(6.0, "minecraft:pumpkin", "minecraft:carved_pumpkin", "minecraft:jack_o_lantern")
add(1.5, "minecraft:cactus")
add(1.0, "minecraft:sponge", "minecraft:wet_sponge", "minecraft:moss_block",
    "minecraft:moss_carpet")
add(6.0, "minecraft:honey_block", "minecraft:honeycomb_block")
add(5.0, "minecraft:slime_block")
add(0.2, "minecraft:sweet_berries", "minecraft:glow_berries", "minecraft:wheat_seeds",
    "minecraft:beetroot_seeds", "minecraft:melon_seeds", "minecraft:pumpkin_seeds",
    "minecraft:torchflower_seeds", "minecraft:pitcher_pod")
add(0.1, "minecraft:wheat")
add(0.3, "minecraft:apple", "minecraft:carrot", "minecraft:golden_carrot",
    "minecraft:potato", "minecraft:baked_potato", "minecraft:poisonous_potato",
    "minecraft:beetroot", "minecraft:melon_slice", "minecraft:chorus_fruit",
    "minecraft:glow_lichen", "minecraft:sea_grass", "minecraft:fern", "minecraft:short_grass")
add(0.5, "minecraft:bread", "minecraft:beef", "minecraft:porkchop", "minecraft:chicken",
    "minecraft:mutton", "minecraft:rabbit", "minecraft:cod", "minecraft:salmon",
    "minecraft:tropical_fish", "minecraft:cooked_beef", "minecraft:cooked_porkchop",
    "minecraft:cooked_chicken", "minecraft:cooked_mutton", "minecraft:cooked_rabbit",
    "minecraft:cooked_cod", "minecraft:cooked_salmon", "minecraft:pumpkin_pie",
    "minecraft:cookie", "minecraft:mushroom_stew", "minecraft:beetroot_soup",
    "minecraft:rabbit_stew", "minecraft:suspicious_stew")
add(0.4, "minecraft:golden_apple", "minecraft:enchanted_golden_apple")
add(1.5, "minecraft:cake")
add(0.3, "minecraft:spider_eye", "minecraft:fermented_spider_eye", "minecraft:rabbit_foot",
    "minecraft:rabbit_hide", "minecraft:phantom_membrane", "minecraft:heart_of_the_sea")
add(0.1, "minecraft:gunpowder")

# ------------------------------------------------------------- containers ----
add(2.0, "minecraft:bucket")
add(3.0, "minecraft:water_bucket", "minecraft:milk_bucket", "minecraft:powder_snow_bucket",
    "minecraft:cod_bucket", "minecraft:salmon_bucket", "minecraft:tropical_fish_bucket",
    "minecraft:pufferfish_bucket", "minecraft:axolotl_bucket", "minecraft:tadpole_bucket")
add(4.0, "minecraft:lava_bucket")
add(2.0, "minecraft:glass_bottle", "minecraft:potion", "minecraft:splash_potion",
    "minecraft:lingering_potion", "minecraft:dragon_breath")
add(0.5, "minecraft:bowl", "minecraft:flint")
add(1.0, "minecraft:shield")
add(4.0, "minecraft:elytra")
add(3.0, "minecraft:saddle", "minecraft:horse_armor")
add(6.0, "minecraft:iron_horse_armor", "minecraft:golden_horse_armor")
add(8.0, "minecraft:diamond_horse_armor")
add(9.0, "minecraft:wolf_armor")

# ----------------------------------------------------------------- tools -----
TOOL_WEIGHTS = {
    "wooden": 1.5, "stone": 3.0, "iron": 4.5, "golden": 5.0,
    "diamond": 4.0, "netherite": 6.0,
}
for material, kg in TOOL_WEIGHTS.items():
    for tool in ("sword", "pickaxe", "axe", "shovel", "hoe"):
        W[f"minecraft:{material}_{tool}"] = kg
add(6.0, "minecraft:shears", "minecraft:fishing_rod", "minecraft:carrot_on_a_stick")
add(1.0, "minecraft:bow", "minecraft:crossbow")
add(2.0, "minecraft:trident", "minecraft:mace")
add(0.3, "minecraft:compass", "minecraft:recovery_compass", "minecraft:clock",
    "minecraft:spyglass", "minecraft:brush", "minecraft:name_tag", "minecraft:lead")

ARMOR_WEIGHTS = {
    "leather": (1.2, 3.0, 2.4, 1.2),
    "chainmail": (2.5, 6.0, 5.0, 2.5),
    "iron": (4.5, 10.0, 8.0, 4.5),
    "golden": (4.0, 9.0, 7.5, 4.0),
    "diamond": (3.5, 8.0, 6.5, 3.5),
    "netherite": (5.0, 11.0, 9.0, 5.0),
}
ARMOR_SLOTS = ("helmet", "chestplate", "leggings", "boots")
for material, weights in ARMOR_WEIGHTS.items():
    for slot, kg in zip(ARMOR_SLOTS, weights):
        W[f"minecraft:{material}_{slot}"] = kg
add(5.0, "minecraft:turtle_helmet")
add(0.5, "minecraft:totem_of_undying")

# ------------------------------------------------------------------ misc -----
add(25.0, "minecraft:minecart")
add(30.0, "minecraft:chest_minecart", "minecraft:furnace_minecart", "minecraft:hopper_minecart")
add(2.0, "minecraft:rail", "minecraft:powered_rail", "minecraft:detector_rail",
    "minecraft:activator_rail")
add(8.0, "minecraft:hopper", "minecraft:dispenser", "minecraft:dropper",
    "minecraft:observer", "minecraft:piston", "minecraft:sticky_piston")
add(6.0, "minecraft:redstone_lamp", "minecraft:target", "minecraft:note_block",
    "minecraft:jukebox", "minecraft:daylight_detector")
add(3.0, "minecraft:tnt")
add(0.5, "minecraft:lever", "minecraft:stone_button", "minecraft:tripwire_hook")
add(0.2, "minecraft:repeater", "minecraft:comparator", "minecraft:redstone_wire")
add(1.0, "minecraft:lightning_rod", "minecraft:end_rod")
add(0.1, "minecraft:item_frame", "minecraft:glow_item_frame", "minecraft:painting")
add(2.0, "minecraft:bell", "minecraft:campfire", "minecraft:soul_campfire")
add(4.0, "minecraft:sculk", "minecraft:sculk_vein", "minecraft:sculk_sensor")
add(8.0, "minecraft:calibrated_sculk_sensor")
add(6.0, "minecraft:sculk_catalyst", "minecraft:sculk_shrieker")
add(4.0, "minecraft:frogspawn", "minecraft:froglight")
add(0.2, "minecraft:map", "minecraft:filled_map", "minecraft:firework_star")
add(1.0, "minecraft:armor_stand")
add(0.3, "minecraft:music_disc_13", "minecraft:music_disc_cat")
add(5.0, "minecraft:crafter", "minecraft:trial_spawner", "minecraft:vault")
add(2.0, "minecraft:decorated_pot")
add(0.1, "minecraft:banner")
expand(0.4, "minecraft:{}_banner", COLORS)
add(0.4, "minecraft:shield")

BLOCK_SUFFIXES = ("_slab", "_stairs", "_wall", "_fence", "_fence_gate", "_pressure_plate",
                  "_button", "_pane", "_door", "_trapdoor")

# Anything still unweighed that is clearly a block gets a sane default via the Java fallback;
# this only tightens the very common ones.
for stone in ("stone", "cobblestone", "deepslate", "granite", "diorite", "andesite"):
    W.setdefault(f"minecraft:{stone}_slab", 2.7)
    W.setdefault(f"minecraft:{stone}_stairs", 3.7)
    W.setdefault(f"minecraft:{stone}_wall", 3.4)
W["minecraft:cobblestone_slab"] = 2.5
W["minecraft:cobblestone_stairs"] = 3.4
W["minecraft:cobblestone_wall"] = 3.1
W["minecraft:stone_slab"] = 2.7
W["minecraft:stone_stairs"] = 3.7

# ----------------------------------------------------------------- output ----
# --- Project Realism's own items: stage two -------------------------------------------
add(0.6, "project_realism:granite_rock", "project_realism:limestone_rock", "project_realism:flint_rock")
add(0.4, "project_realism:sharp_stone", "project_realism:stone_axe_head")
add(0.05, "project_realism:plant_fiber", "project_realism:cordage")
add(0.5, "project_realism:stone_knife")
add(0.9, "project_realism:stone_axe", "project_realism:stone_pickaxe")


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    out = os.path.normpath(os.path.join(
        here, "..", "src", "main", "resources", "data", "project_realism",
        "project_realism", "weights.json"))
    os.makedirs(os.path.dirname(out), exist_ok=True)

    document = {
        "_comment": [
            "Project Realism built-in item weights, in kilograms per single item.",
            "Override any entry from a datapack: data/<your_pack>/project_realism/weights.json",
            "with the same item id, then run /reload.",
        ],
        "items": {key: round(value, 2) for key, value in sorted(W.items())},
    }

    with open(out, "w", encoding="utf-8") as handle:
        json.dump(document, handle, indent=2)
        handle.write("\n")

    print(f"wrote {len(W)} item weights to {out}")


if __name__ == "__main__":
    main()
