# Project Realism

An unforgiving Minecraft survival overhaul. The premise is that Minecraft is not interesting when it
is hard because mobs have more health — it is interesting when it is hard because the world is
physical. Food rots, water is heavy, weather is dangerous, and the wilderness is genuinely
complicated to cross.

**Stage one, implemented here: block weight.** Every item has a mass in kilograms. Carry more than
your body comfortably supports and you slow down.

```
Dirt          2 kg
Cobblestone   5 kg
Oak Log      12 kg
Iron Block   40 kg
```

A player can carry **30 kg** for free. Past that, movement speed falls away smoothly.

| Load   | Speed  | What it feels like                          |
| ------ | ------ | ------------------------------------------- |
| 0–30 kg | 100%  | Unburdened.                                 |
| 35 kg  | 85%   | You notice it.                              |
| 40 kg  | 70%   | A long walk home becomes a decision.        |
| 50 kg  | 50%   | Half speed. Mobs are a real threat.         |
| 60 kg  | 38%   | A trudge. Every trip is a round trip.       |
| 90 kg  | 18%   | Something is going to kill you on the way.  |
| 130 kg+ | 12%  | The floor. You can still crawl home.        |

A stack of 64 cobblestone is 320 kg. That single fact reorganises the entire game: mining trips get
planned, bases move to the ore, chests become logistics, and an iron block is a genuine commitment.

## What it actually does

- **Weighs the whole player**, not just the hotbar: main inventory, worn armour, off hand, shulker
  boxes and bundles (containers nest three levels deep).
- **Slows the player smoothly.** Speed multiplier is `1 / (1 + (overload / 20 kg) ^ 1.25)`, clamped at
  12%. No cliff at the capacity boundary — 30.1 kg costs 0.1%.
- **Stacks correctly.** The penalty is one named attribute modifier
  (`project_realism:encumbrance`), so beacons, potions and Soul Speed all still work and removing the
  mod cannot leave anyone permanently crippled.
- **Costs hunger.** Hauling mass above capacity drains exhaustion, four times faster while walking
  than while standing.
- **Tells you quietly.** A readout above the hotbar (`23.4 / 30 kg`, plus `-45% speed`), and one
  action-bar line when you tip into a worse band. No spam.
- **Ignores creative mode** by default, so building is not a survival sim.

## Build it

You need **JDK 21** (not 17 — Loom targets release 21). Then:

```sh
./gradlew build
```

The jar lands in `build/libs/project-realism-0.1.0.jar`. Use that one, not the `-sources` jar.
Tests run as part of `build`; `./gradlew test` runs only the curve tests.

### Build it in GitHub Actions

`.github/workflows/build.yml` builds every push and pull request, runs the tests and uploads the jar:

1. Push a commit (or click **Actions → Build → Run workflow** for a manual run).
2. Open the finished run.
3. Download **`project-realism-jar`** under *Artifacts* — that zip contains
   `project-realism-0.1.0.jar`.

To get a permanent download link instead, tag a commit; the `release` job attaches the jar to a
GitHub Release:

```sh
git tag v0.1.0 && git push origin v0.1.0
```

Local build without Gradle installed at all (no, really, in Docker):

```sh
docker run --rm -v "$PWD":/work -w /work eclipse-temurin:21-jdk ./gradlew build
```

## Install

1. Minecraft **1.21.1**, [Fabric Loader](https://fabricmc.net/use/) 0.16+.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) in `mods/`.
3. Drop the built `project-realism-0.1.0.jar` into `mods/` — client and server.
4. The mod **must be installed on both client and server**. Weight is computed server-side; the
   client only draws the readout. Vanilla clients can still connect to a modded server — they just
   don't get the HUD.

## Configuration

`config/project-realism.json`, generated on first launch:

| Key                          | Default | Meaning                                                    |
| ---------------------------- | ------- | ---------------------------------------------------------- |
| `capacityKg`                 | `30.0`  | Mass carried for free.                                      |
| `halfLifeKg`                 | `20.0`  | Extra mass beyond capacity that halves your speed.          |
| `curveExponent`              | `1.25`  | Curve shape. Higher = forgiving near capacity, brutal later. |
| `minSpeedMultiplier`         | `0.12`  | Floor. Even a hoarder can limp.                             |
| `weightHud`                  | `true`  | Show the readout above the hotbar.                          |
| `hudIntervalTicks`           | `10`    | Refresh rate for the readout.                               |
| `overloadExhaustion`         | `true`  | Heavy loads burn food faster.                               |
| `exhaustionPerKgPerSecond`   | `0.01`  | Hunger cost per kilogram over capacity, per second.         |
| `affectCreative`             | `false` | Whether creative players obey physics.                      |
| `hudInCreative`              | `true`  | Show the readout in creative.                               |

Want to know how the numbers feel before committing? Open `simulator/index.html` — it is the exact
same curve, with sliders for all four values and a clickable inventory.

## Weights

772 items are weighed out of the box, in `data/project_realism/project_realism/weights.json`
(regenerate with `python3 tools/generate_weights.py`). Anything unknown falls back to a conservative
estimate: block items 4 kg, tools 3 kg, food 0.3 kg, everything else 0.2 kg.

To retune, override ids from a datapack — later packs win:

```
data/<your_pack>/project_realism/weights.json
{
  "items": {
    "minecraft:dirt": 2,
    "minecraft:cobblestone": 5,
    "minecraft:oak_log": 12,
    "minecraft:iron_block": 40,
    "minecraft:elytra": 4
  }
}
```

Then `/reload` (or `/realism reload` to re-read only the weights).

## Commands

| Command                              | Permission | Effect                                     |
| ------------------------------------ | ---------- | ------------------------------------------ |
| `/realism weight`                    | all        | Your load, speed penalty, heaviest items.   |
| `/realism weight <player>`           | 2          | Same, for someone else.                     |
| `/realism item <item>`               | all        | What one of these weighs, and a full stack. |
| `/realism capacity <kg>`             | 4          | Change capacity for this session.           |
| `/realism reload`                    | 4          | Re-read the weight table.                   |

## Why these numbers

`1 / (1 + x^1.25)` was chosen over a linear penalty or a hard capacity wall because:

- It is **continuous**: no cliff to exploit or fall off, so there is no "one dirt block too many"
  moment where a player should obviously drop 3 items.
- It is **convex**: small overloads are cheap, large ones compound. A mining trip with 40 kg feels
  like a chore; 160 kg of iron blocks feels like a mistake.
- It **never reaches zero**, so players are never stranded by their own inventory in a way that
  requires admin help.

## Repository layout

```
src/main/java/com/projectrealism/
  ProjectRealism.java          mod entrypoint, registrations
  RealismConfig.java           JSON config with sane clamping
  weight/Encumbrance.java      the curve, pure math, no Minecraft types
  weight/WeightTable.java      item -> kg, with datapack merging and fallbacks
  weight/InventoryWeigher.java inventory -> kilograms, recursion into containers
  weight/WeightManager.java    per-tick driver, hunger, HUD packets
  weight/WeightAttributes.java the movement speed modifier
  weight/WeightPayload.java    S2C payload
  client/WeightHud.java        the readout
  command/WeightCommand.java   /realism
src/main/resources/data/project_realism/project_realism/weights.json
simulator/index.html           interactive tuning bench
tools/generate_weights.py      regenerates the weight table
src/test/java/                 unit tests for the curve
```

Build and test:

```sh
./gradlew build   # jar + tests
./gradlew test    # tests only
```

CI: [`.github/workflows/build.yml`](.github/workflows/build.yml) — build on every push/PR, artifact per run, release on `v*` tags.

## Roadmap

Stage one is weight. The rest of the overhaul, in the order it will actually get built:

1. **Weight** — done.
2. **Encumbrance consequences** — slower block breaking, no sprinting past 1.5× capacity, fall damage
   scaling with carried mass, and dropping your load when you take a hard hit.
3. **Water and food** — drinkable water that actually has volume, food that spoils, cooking that
   matters.
4. **Temperature and weather** — wet clothes, wind chill, and the reason to build a real house.
5. **Injury** — broken bones that heal over days instead of hearts that regenerate in seconds.
