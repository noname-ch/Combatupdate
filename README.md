# CombatUpdate

[![Build](https://github.com/noname-ch/Combatupdate/actions/workflows/build.yml/badge.svg)](https://github.com/noname-ch/Combatupdate/actions/workflows/build.yml)

A NeoForge mod for Minecraft Java 26.3 that reworks combat: sword blocking, dashing and sliding,
elytra flight and bombing, ten new enchantments, the Gipfäli arsenal and army, territory claims,
and more.

**Homepage with every feature, control, crafting recipe and command:**
https://noname-ch.github.io/Combatupdate/

## Features

- **Melee:** 1.8-style sword blocking, shortswords, weapon reach, thrown fireballs, and three
  endgame weapons from Terraria (Exoblade, Scarlet Devil, Zenith)
- **Movement:** a dash on <kbd>R</kbd> and a slide on sneak while sprinting
- **Flight:** free-look elytra with roll and pitch, a continuous rocket boost, elytra chestplates,
  TNT bombing and the Battering Ram helmet
- **Enchantments:** ten new ones, from the enchanting table and librarians
- **Gipfäli:** a croissant that is also ammunition: launcher, artillery rig, grenades and TNT
- **Army:** raise squads of Gipfäli soldiers, kit them out, drill formations and station guards
- **Territory:** claim chunks, protect them, and capture other players'
- **Extras:** floating damage numbers, a training dummy and the Meat Obelisk

Every feature has an off switch in the config.

## Requirements

| | Version |
|---|---|
| Minecraft Java Edition | 26.3 |
| NeoForge | 26.3.0.7-beta |
| Java | 25 (the Minecraft launcher ships it; only needed separately for a server or a build) |

The mod has to be installed **on the server and on every client** that joins it. A client without
it can't join a server that has it, and the other way round.

## Download

Get `combatupdate-<version>.jar` from the [Releases](https://github.com/noname-ch/Combatupdate/releases)
page.

To try the newest unreleased build instead, open the latest green run under
[Actions](https://github.com/noname-ch/Combatupdate/actions/workflows/build.yml) and download the
jar from its **Artifacts** section (you need to be signed in to GitHub). These builds are untested.

## Install

### Singleplayer / client

1. Download the NeoForge **26.3.0.7-beta** installer from [neoforged.net](https://neoforged.net/),
   run it, pick **Install client** and click *OK*. It adds a NeoForge profile to the Minecraft
   launcher.
2. Start that profile once and close the game, so it creates the `mods` folder.
3. Put `combatupdate-<version>.jar` into the `mods` folder:
   - Windows: `%APPDATA%\.minecraft\mods`
   - macOS: `~/Library/Application Support/minecraft/mods`
   - Linux: `~/.minecraft/mods`
4. Start the NeoForge profile again. CombatUpdate shows up under **Mods**.

### Server

1. Run the same NeoForge installer with **Install server** (or on the command line:
   `java -jar neoforge-26.3.0.7-beta-installer.jar --installServer`) into an empty folder.
2. Start it once with `run.sh` (Linux/macOS) or `run.bat` (Windows), accept the EULA in
   `eula.txt`, and start it again.
3. Stop the server, put `combatupdate-<version>.jar` into the server's `mods` folder, and start it.
4. Every player needs the same version of the mod in their own client.

### Updating

Replace the old jar in `mods` with the new one. Don't keep both: two versions of the same mod
stop the game from starting. Your config and worlds stay as they are.

## Configuration

In game: **Mods → CombatUpdate → Config**. The first page switches whole features on and off; the
pages after it hold every number the mod uses.

The same settings live in `config/combatupdate-common.toml`, in the game folder or the server
folder. On a server, the server's file is the one that counts.

## Controls

| Control | Does |
|---|---|
| <kbd>R</kbd> | Dash (rebind under *Controls → CombatUpdate*) |
| <kbd>Sneak</kbd> while sprinting | Slide |
| Hold right-click with a sword | Block |
| <kbd>W</kbd> <kbd>S</kbd> / <kbd>A</kbd> <kbd>D</kbd> while gliding | Pitch / roll |
| <kbd>Shift</kbd> while gliding | Continuous rocket boost |
| <kbd>K</kbd> | Army screen |
| <kbd>M</kbd> | Territory map |

The homepage lists every control and all commands (`/gipfaeliarmy`, `/soldats`, `/territory`).

## Troubleshooting

- **The game says a mod needs a different NeoForge or Minecraft version.** Check you are on
  Minecraft 26.3 with NeoForge 26.3.0.7-beta, and started the NeoForge profile, not the vanilla one.
- **"Mod rejections" or a channel mismatch when joining a server.** The client and the server have
  different versions of CombatUpdate, or one of them doesn't have it at all.
- **The game crashes on startup.** Make sure there is only one CombatUpdate jar in `mods`. The
  crash report is in `crash-reports/`, and the full log in `logs/latest.log`.

Found a bug? [Open an issue](https://github.com/noname-ch/Combatupdate/issues) and attach
`logs/latest.log` or the crash report.

## Build from source

Requires JDK 25.

```sh
git clone https://github.com/noname-ch/Combatupdate.git
cd Combatupdate
./gradlew build          # the jar lands in build/libs
./gradlew runClient      # start a dev client
./gradlew runServer      # start a dev server
```

On Windows use `gradlew.bat` instead of `./gradlew`. If your IDE is missing libraries, run
`./gradlew --refresh-dependencies`; `./gradlew clean` resets the build without touching your code.

## Repository layout

| Path | What's there |
|------|--------------|
| `src/main/java/ch/bbcag/combatupdate/` | Mod code. Feature classes live at the top level, with subpackages for `client`, `combat`, `enchantment`, `entity`, `mixin` and `territory` |
| `src/main/resources/` | Assets (models, textures, sounds, lang) and data (recipes, loot, enchantments, tags) |
| `docs/` | The GitHub Pages homepage. `docs/shots/` holds its screenshots |
| `notes/` | Design notes on how individual features work and why they're tuned the way they are |
| `tools/` | Helper scripts, such as `soldier_skins.py` for generating soldier textures |

## License

All rights reserved. A fan-made mod, not affiliated with Mojang or Microsoft.

The build uses Mojang's official mapping names, which are covered by their own license:
https://github.com/NeoForged/NeoForm/blob/main/Mojang.md
