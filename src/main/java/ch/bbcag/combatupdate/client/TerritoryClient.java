package ch.bbcag.combatupdate.client;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.Config;
import ch.bbcag.combatupdate.territory.TerritoryNetwork;
import ch.bbcag.combatupdate.territory.TerritoryNetwork.ChunkInfoPayload;
import ch.bbcag.combatupdate.territory.TerritoryNetwork.MapPayload;

// The client's end of territory: the key that opens the screen, and where what the server sends
// lands so the screen has something to draw.
public final class TerritoryClient {
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "main"));
    public static final KeyMapping OPEN = new KeyMapping("key.combatupdate.territory", InputConstants.KEY_M, CATEGORY);

    // The last map and chunk overview received, kept between openings so the screen has something
    // to show the instant it opens rather than a round trip later.
    static @Nullable MapPayload map;
    static @Nullable ChunkInfoPayload chunkInfo;

    private TerritoryClient() {
    }

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(OPEN);
    }

    // Plugs this class into the network layer, which cannot name it itself without dragging
    // client classes onto a dedicated server.
    public static void wire() {
        TerritoryNetwork.mapListener = TerritoryClient::onMap;
        TerritoryNetwork.chunkInfoListener = TerritoryClient::onChunkInfo;
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        while (OPEN.consumeClick()) {
            if (minecraft.player == null || !Config.on(Config.ENABLE_TERRITORY)) {
                continue;
            }
            minecraft.gui.setScreen(new TerritoryScreen());
        }
    }

    private static void onMap(MapPayload payload) {
        map = payload;
        if (Minecraft.getInstance().gui.screen() instanceof TerritoryScreen screen) {
            screen.onMap(payload);
        }
    }

    private static void onChunkInfo(ChunkInfoPayload payload) {
        chunkInfo = payload;
        if (Minecraft.getInstance().gui.screen() instanceof TerritoryScreen screen) {
            screen.onChunkInfo(payload);
        }
    }
}
