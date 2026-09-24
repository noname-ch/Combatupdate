# CombatUpdate

Two NeoForge mods for Minecraft Java 26.3, in one repository:

- `combatupdate/` - **CombatUpdate**: sword blocking, 3D elytra flight, ten enchantments, dash and slide, the endgame weapons.
- `Gipfeliarmy/` - **Gipfäli Army** (mod id `gipfeliarmy`): territory you can claim and capture, the Gipfäli arsenal, and an army that fires Gipfäli.

They share no code and each loads on its own, but the game as designed is both together. How to play is on the website: https://noname-ch.github.io/Combatupdate/

## Playing with friends

Everyone, the server included, needs NeoForge 26.3.0.7-beta and the same two jars.

1. Build the zip: `./gradlew build` leaves `build/distributions/Combatupdate-mods-<version>.zip`, holding both jars and an `INSTALL.txt`. Every push also uploads it on the GitHub Actions page of that run, and pushing a tag such as `v1.1.0` publishes it as a GitHub release.
2. Every player unzips the two jars into `.minecraft/mods` and starts the NeoForge profile.
3. The server gets the same two jars in its `mods` folder and a restart. To host one on this machine, `tools/setup-server.sh` installs a NeoForge server into `server/` with the jars in place; start it with `cd server && ./run.sh nogui` and friends join at this machine's address on port 25565.

The quickest game needs no server at all: one player opens their world to LAN (Esc, "Open to LAN") and the others join it from the multiplayer screen, as long as they all have the two jars.

Coming from the old single `combatupdate-1.0.0.jar`: take it out, put both new jars in. Worlds keep their items, soldiers, guard posts, territory and settings.

## Building and running

- `./gradlew build` builds both jars (`combatupdate/build/libs`, `Gipfeliarmy/build/libs`) and the zip.
- `./gradlew runClient` / `runServer` start a development game with both mods, using the `run/` folder at the root.
- `./gradlew runClientCombat` or `runClientArmy` start one mod alone. In IntelliJ the same runs appear as "Client", "Server", "Client (combatupdate only)" and so on.

## NeoForge MDK notes

Installation information
=======

This template repository can be directly cloned to get you started with a new
mod. Simply create a new repository cloned from this one, by following the
instructions provided by [GitHub](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-repository-from-a-template).

Once you have your clone, simply open the repository in the IDE of your choice. The usual recommendation for an IDE is either IntelliJ IDEA or Eclipse.

If at any point you are missing libraries in your IDE, or you've run into problems you can
run `gradlew --refresh-dependencies` to refresh the local cache. `gradlew clean` to reset everything 
{this does not affect your code} and then start the process again.

Mapping Names:
============
By default, the MDK is configured to use the official mapping names from Mojang for methods and fields 
in the Minecraft codebase. These names are covered by a specific license. All modders should be aware of this
license. For the latest license text, refer to the mapping file itself, or the reference copy here:
https://github.com/NeoForged/NeoForm/blob/main/Mojang.md

Additional Resources: 
==========
Community Documentation: https://docs.neoforged.net/  
NeoForged Discord: https://discord.neoforged.net/
