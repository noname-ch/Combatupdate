package ch.bbcag.combatupdate.client;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec2;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.Movement;
import ch.bbcag.combatupdate.mixin.ClientInputMoveAccessor;

// The client's end of the dash and the slide: the dash key, and the line to the server that Movement
// cannot draw itself. The slide needs no key of its own - it is sneak, read off the player.
public final class MovementClient {
    // The mod's own heading in the controls screen. The dash is the only key under it so far.
    public static final KeyMapping.Category CATEGORY = new KeyMapping.Category(
            Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "main"));
    public static final KeyMapping DASH = new KeyMapping("key.combatupdate.dash", InputConstants.KEY_R, CATEGORY);

    private MovementClient() {
    }

    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
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

    // A slide goes where the player looks, not where they walk, so the movement keys are dropped
    // while it lasts. Jump is left alone: that is how a slide is jumped out of.
    public static void onMovementInput(MovementInputUpdateEvent event) {
        if (Movement.isSliding(event.getEntity())) {
            ((ClientInputMoveAccessor) event.getInput()).combatupdate$setMoveVector(Vec2.ZERO);
        }
    }
}
