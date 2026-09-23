# CombatUpdate

A NeoForge mod for Minecraft Java 26.3 that reworks combat: sword blocking, dashing and sliding,
elytra flight and bombing, new enchantments, the Gipfäli arsenal and army, territory claims, and more.

**Homepage with every feature, control and command:** https://noname-ch.github.io/Combatupdate/

## Install

1. Install [NeoForge](https://neoforged.net/) `26.3.0.7-beta` for Minecraft 26.3 (pick *Install client*).
2. Drop `combatupdate-<version>.jar` into `.minecraft/mods`. Servers need it in their own `mods` folder too.
3. Launch the NeoForge profile. CombatUpdate shows up under *Mods*, with its config one click away.

## Build and run

Requires JDK 25.

```sh
./gradlew build          # jar lands in build/libs
./gradlew runClient      # start a dev client
./gradlew runServer      # start a dev server
```

If your IDE is missing libraries, run `./gradlew --refresh-dependencies`. `./gradlew clean` resets
the build without touching your code.

LAN test server: `10.100.60.79:25565`

## Repository layout

| Path | What's there |
|------|--------------|
| `src/main/java/ch/bbcag/combatupdate/` | Mod code. Feature classes live at the top level, with subpackages for `client`, `combat`, `enchantment`, `entity`, `mixin` and `territory` |
| `src/main/resources/` | Assets (models, textures, sounds, lang) and data (recipes, loot, enchantments, tags) |
| `docs/` | The GitHub Pages homepage. `docs/shots/` holds its screenshots |
| `notes/` | Design notes on how individual features work and why they're tuned the way they are |
| `tools/` | Helper scripts, such as `soldier_skins.py` for generating soldier textures |

## Mappings

The MDK uses Mojang's official mapping names, which are covered by their own license:
https://github.com/NeoForged/NeoForm/blob/main/Mojang.md

## Resources

- NeoForge docs: https://docs.neoforged.net/
- NeoForged Discord: https://discord.neoforged.net/
