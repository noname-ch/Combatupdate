# CombatUpdate

[![Build](https://github.com/noname-ch/Combatupdate/actions/workflows/build.yml/badge.svg)](https://github.com/noname-ch/Combatupdate/actions/workflows/build.yml)

Two NeoForge mods for Minecraft Java 26.3 that go together:

- **CombatUpdate** (`combatupdate`) reworks combat: sword blocking, dashing and sliding, elytra
  flight and bombing, ten new enchantments, three endgame weapons, and more.
- **Gipfäli Army** (`gipfeliarmy`) adds the Gipfäli arsenal and army, and territory claims.

They share no code and each runs on its own, but the game as designed is both.

**Homepage with every feature, control, crafting recipe and command:**
https://noname-ch.github.io/Combatupdate/

## Features

CombatUpdate:

- **Melee:** 1.8-style sword blocking, shortswords, weapon reach, thrown fireballs, and three
  endgame weapons from Terraria (Exoblade, Scarlet Devil, Zenith)
- **Movement:** a dash on <kbd>R</kbd> and a slide on sneak while sprinting
- **Flight:** free-look elytra with roll and pitch, a continuous rocket boost, elytra chestplates,
  TNT bombing and the Battering Ram helmet
- **Enchantments:** ten new ones, from the enchanting table and librarians
- **Extras:** floating damage numbers, a training dummy and the Meat Obelisk

Gipfäli Army:

- **Gipfäli:** a croissant that is also ammunition: launcher, artillery rig, grenades and TNT
- **Army:** raise squads of Gipfäli soldiers, kit them out, drill formations and station guards
- **Territory:** claim chunks, protect them, and capture other players'

Every feature has an off switch in its mod's config.

## Requirements

| | Version |
|---|---|
| Minecraft Java Edition | 26.3 |
| NeoForge | 26.3.0.7-beta |
| Java | 25 (the Minecraft launcher ships it; only needed separately for a server or a build) |

Both mods have to be installed **on the server and on every client** that joins it. A client
missing one of them can't join a server that has it, and the other way round.

## Download

Get `Combatupdate-mods-<version>.zip` from the [Releases](https://github.com/noname-ch/Combatupdate/releases)
page. It holds the two jars, `combatupdate-<version>.jar` and `gipfeliarmy-<version>.jar`, and an
`INSTALL.txt` with the steps below.

To try the newest unreleased build instead, open the latest green run under
[Actions](https://github.com/noname-ch/Combatupdate/actions/workflows/build.yml) and download the
`Combatupdate-mods` artifact from its **Artifacts** section (you need to be signed in to GitHub).
These builds are untested.

## Install

### Singleplayer / client

1. Download the NeoForge **26.3.0.7-beta** installer from [neoforged.net](https://neoforged.net/),
   run it, pick **Install client** and click *OK*. It adds a NeoForge profile to the Minecraft
   launcher.
2. Start that profile once and close the game, so it creates the `mods` folder.
3. Put both jars into the `mods` folder:
   - Windows: `%APPDATA%\.minecraft\mods`
   - macOS: `~/Library/Application Support/minecraft/mods`
   - Linux: `~/.minecraft/mods`
4. Start the NeoForge profile again. CombatUpdate and Gipfäli Army show up under **Mods**.

### Playing with friends

The quickest game needs no server: one player opens their world to LAN (<kbd>Esc</kbd>, *Open to
LAN*) and the others join it from the multiplayer screen. Everyone needs the same two jars.

For a server that stays up when nobody is playing, see below.

### Server

1. Run the same NeoForge installer with **Install server** (or on the command line:
   `java -jar neoforge-26.3.0.7-beta-installer.jar --install-server`) into an empty folder.
2. Start it once with `run.sh` (Linux/macOS) or `run.bat` (Windows), accept the EULA in
   `eula.txt`, and start it again.
3. Stop the server, put both jars into the server's `mods` folder, and start it.
4. Every player needs the same versions of both mods in their own client.

From a clone of this repository, `tools/setup-server.sh` does steps 1 and 3 into `server/` and
asks about the EULA; start the result with `cd server && ./run.sh nogui`.

### Updating

Replace the old jars in `mods` with the new ones. Don't keep both: two versions of the same mod
stop the game from starting. Your config and worlds stay as they are.

Coming from the single `combatupdate-1.0.0.jar` that held everything: take it out and put both new
jars in, on the server and on every client. Worlds keep their items, soldiers, guard posts,
territory and settings; the Gipfäli Army's settings are carried over into its own config file the
first time it starts.

## Configuration

In game: **Mods → CombatUpdate → Config** and **Mods → Gipfäli Army → Config**. The first page of
each switches whole features on and off; the pages after it hold every number the mod uses.

The same settings live in `config/combatupdate-common.toml` and `config/gipfeliarmy-common.toml`,
in the game folder or the server folder. On a server, the server's files are the ones that count.

## Controls

| Control | Does |
|---|---|
| <kbd>R</kbd> | Dash (rebind under *Controls → CombatUpdate*) |
| <kbd>Sneak</kbd> while sprinting | Slide |
| Hold right-click with a sword | Block |
| <kbd>W</kbd> <kbd>S</kbd> / <kbd>A</kbd> <kbd>D</kbd> while gliding | Pitch / roll |
| <kbd>Shift</kbd> while gliding | Continuous rocket boost |
| <kbd>K</kbd> | Army screen (rebind under *Controls → Gipfäli Army*) |
| <kbd>M</kbd> | Territory map |

The homepage lists every control and all commands (`/gipfaeliarmy`, `/soldats`, `/territory`).

## Troubleshooting

- **The game says a mod needs a different NeoForge or Minecraft version.** Check you are on
  Minecraft 26.3 with NeoForge 26.3.0.7-beta, and started the NeoForge profile, not the vanilla one.
- **"Mod rejections" or a channel mismatch when joining a server.** The client and the server have
  different versions of the mods, or one side is missing one of the two jars.
- **The game crashes on startup.** Make sure there is only one CombatUpdate jar and one Gipfäli Army
  jar in `mods`. The crash report is in `crash-reports/`, and the full log in `logs/latest.log`.

Found a bug? [Open an issue](https://github.com/noname-ch/Combatupdate/issues) and attach
`logs/latest.log` or the crash report.

## Build from source

Requires JDK 25.

```sh
git clone https://github.com/noname-ch/Combatupdate.git
cd Combatupdate
./gradlew build          # both jars, and build/distributions/Combatupdate-mods-<version>.zip with the two of them
./gradlew runClient      # a dev client with both mods, in run/ (runServer likewise)
./gradlew runClientCombat   # the combat mod alone; runClientArmy for the army alone
```

The jars land in `combatupdate/build/libs` and `Gipfeliarmy/build/libs`. In IntelliJ the runs
appear as *Client*, *Server*, *Client (combatupdate only)* and so on.

On Windows use `gradlew.bat` instead of `./gradlew`. If your IDE is missing libraries, run
`./gradlew --refresh-dependencies`; `./gradlew clean` resets the build without touching your code.

## Repository layout

| Path | What's there |
|------|--------------|
| `combatupdate/` | The CombatUpdate mod: `src/main/java/ch/bbcag/combatupdate/` with feature classes at the top level and subpackages for `client`, `combat`, `enchantment`, `entity` and `mixin`; its assets and data under `src/main/resources/` |
| `Gipfeliarmy/` | The Gipfäli Army mod: `src/main/java/ch/bbcag/gipfeliarmy/` with subpackages for `client`, `entity` and `territory`, and its own resources |
| `build.gradle` | What both mods share: the toolchain, the NeoForge version, and the zip that packs both jars |
| `docs/` | The GitHub Pages homepage. `docs/shots/` holds its screenshots |
| `notes/` | Design notes on how individual features work and why they're tuned the way they are |
| `tools/` | Helper scripts: `soldier_skins.py` generates soldier textures, `setup-server.sh` installs a dedicated server, `release/INSTALL.txt` goes into the zip |

## License

All rights reserved. A fan-made mod, not affiliated with Mojang or Microsoft.

The build uses Mojang's official mapping names, which are covered by their own license:
https://github.com/NeoForged/NeoForm/blob/main/Mojang.md
