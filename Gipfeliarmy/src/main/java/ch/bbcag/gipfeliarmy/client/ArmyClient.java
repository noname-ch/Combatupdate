package ch.bbcag.gipfeliarmy.client;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import ch.bbcag.gipfeliarmy.Config;
import ch.bbcag.gipfeliarmy.GipfaeliArmy;
import ch.bbcag.gipfeliarmy.GipfaeliArmyNetwork;
import ch.bbcag.gipfeliarmy.GipfaeliArmyNetwork.OpenArmy;
import ch.bbcag.gipfeliarmy.GipfaeliArmyNetwork.Roster;

// The client's end of the army: the key that opens the roster, and where the roster the server
// sends lands so whichever army screen is open can redraw from it.
public final class ArmyClient {
    public static final KeyMapping OPEN = new KeyMapping("key.gipfeliarmy.army", InputConstants.KEY_K, TerritoryClient.CATEGORY);

    // The last roster received, kept between openings so the screen has something to show the
    // instant it opens rather than a round trip later.
    static @Nullable Roster roster;

    private ArmyClient() {
    }

    // The category is the territory key's, registered there; this runs after it.
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN);
    }

    // Plugs this class into the network layer, which cannot name it itself without dragging
    // client classes onto a dedicated server.
    public static void wire() {
        GipfaeliArmyNetwork.rosterListener = ArmyClient::onRoster;
        GipfaeliArmyNetwork.openListener = ArmyClient::onOpen;
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        while (OPEN.consumeClick()) {
            if (minecraft.player == null || !Config.on(Config.ENABLE_GIPFAELI_ARMY)) {
                continue;
            }
            minecraft.gui.setScreen(new ArmyScreen(GipfaeliArmy.Scope.ALL.token(), null));
        }
    }

    public static void request() {
        ClientPacketDistributor.sendToServer(new GipfaeliArmyNetwork.RosterRequest());
    }

    private static void onRoster(Roster payload) {
        roster = payload;
        if (Minecraft.getInstance().gui.screen() instanceof ArmyScreen screen) {
            screen.onRoster(payload);
        }
    }

    private static void onOpen(OpenArmy payload) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null) {
            return;
        }

        // Opened from a click on a commander while a kit bag is up: the bag has to be closed
        // properly, or the server keeps its menu open.
        if (minecraft.player.containerMenu != minecraft.player.inventoryMenu) {
            minecraft.player.closeContainer();
        }

        minecraft.gui.setScreen(new ArmyScreen(payload.scope(), null));
    }
}
