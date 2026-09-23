package ch.bbcag.combatupdate.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec2;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import ch.bbcag.combatupdate.Movement;

// The client's end of the dash and the slide: the dash key, and the line to the server that Movement
// cannot draw itself. The slide needs no key of its own - it is sneak, read off the player.
public final class MovementClient {
    public static final KeyMapping DASH = new KeyMapping("key.combatupdate.dash", InputConstants.KEY_R, TerritoryClient.CATEGORY);

    private MovementClient() {
    }

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(DASH);
    }

    public static void wire() {
        Movement.toServer = ClientPacketDistributor::sendToServer;
    }

    // Read once a tick, the way vanilla reads its own keys. The dash itself starts on the next player
    // tick (see Movement#tick), so it moves the player the same way whenever in the tick the key went.
    public static void tick() {
        LocalPlayer player = Minecraft.getInstance().player;
        while (DASH.consumeClick()) {
            if (player != null) {
                Vec2 move = player.input.getMoveVector();
                Movement.requestDash(player, move.x, move.y);
            }
        }
    }
}
