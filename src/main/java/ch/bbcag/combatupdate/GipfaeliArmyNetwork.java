package ch.bbcag.combatupdate;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import ch.bbcag.combatupdate.territory.TerritoryManager;

// The one thing the territory screen says to the army: march this many soldiers on that chunk.
//
// Everything else the army is told goes through chat and commands, which need no packet at all;
// this one comes off a button on a screen, where there is no chat to click in.
public final class GipfaeliArmyNetwork {
    private static final String VERSION = "1";

    private GipfaeliArmyNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(MarchRequest.TYPE, MarchRequest.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                GipfaeliArmy.march(player, payload.chunkX(), payload.chunkZ(), payload.count());
                // The screen is looking at the map; whatever the order did to it, show it now.
                ChunkPos here = player.chunkPosition();
                TerritoryManager.sendMap(player, here.x(), here.z());
            }
        });
    }

    // Client -> server: send count soldiers to hold this chunk. The server clamps the count to the
    // squad the player actually has, and the chunk to the ones its map could have shown.
    public record MarchRequest(int chunkX, int chunkZ, int count) implements CustomPacketPayload {
        public static final Type<MarchRequest> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "army_march"));
        public static final StreamCodec<FriendlyByteBuf, MarchRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, MarchRequest::chunkX,
                ByteBufCodecs.VAR_INT, MarchRequest::chunkZ,
                ByteBufCodecs.VAR_INT, MarchRequest::count,
                MarchRequest::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
