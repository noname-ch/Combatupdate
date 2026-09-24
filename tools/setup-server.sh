#!/usr/bin/env bash
# Sets up a NeoForge dedicated server with both mods in ./server, to host a game for friends.
#
#   tools/setup-server.sh          install (first time) or refresh the jars (every time after)
#   cd server && ./run.sh nogui    start it; friends join at this machine's address, port 25565
#
# Needs Java 25 on the PATH and, the first time, the internet for the NeoForge installer.
set -euo pipefail
cd "$(dirname "$0")/.."

NEO=$(sed -n 's/^neo_version=//p' gradle.properties)
DIR=server
INSTALLER="$DIR/neoforge-$NEO-installer.jar"

if ! command -v java >/dev/null; then
    echo "Java is not on the PATH; install Java 25 first." >&2
    exit 1
fi

echo "Building both mods ..."
./gradlew build --console=plain -q

mkdir -p "$DIR/mods"
if [ ! -f "$DIR/run.sh" ]; then
    echo "Installing the NeoForge $NEO server into $DIR/ ..."
    curl -fL -o "$INSTALLER" "https://maven.neoforged.net/releases/net/neoforged/neoforge/$NEO/neoforge-$NEO-installer.jar"
    # From inside the folder: the installer writes its log next to wherever it is run from.
    (cd "$DIR" && java -jar "$(basename "$INSTALLER")" --install-server .)
    rm -f "$INSTALLER" "$INSTALLER.log"
fi

# Old builds out, current builds in: the two jars are the only things of ours in there.
rm -f "$DIR"/mods/combatupdate-*.jar "$DIR"/mods/gipfeliarmy-*.jar
cp combatupdate/build/libs/combatupdate-*.jar Gipfeliarmy/build/libs/gipfeliarmy-*.jar "$DIR/mods/"

if ! grep -qs '^eula=true' "$DIR/eula.txt"; then
    echo
    echo "A Minecraft server only starts once its EULA is accepted: https://aka.ms/MinecraftEULA"
    read -r -p "Accept it now? [y/N] " answer
    if [[ "$answer" =~ ^[Yy]$ ]]; then
        echo "eula=true" > "$DIR/eula.txt"
    else
        echo "Not accepted. Put eula=true into $DIR/eula.txt when you have read it."
    fi
fi

echo
echo "Server ready in $DIR/ with $(ls "$DIR/mods" | tr '\n' ' ')"
echo "Start it with:   cd $DIR && ./run.sh nogui"
echo "Friends join at this machine's address on port 25565 (see $DIR/server.properties)."
