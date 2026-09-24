package ch.bbcag.gipfeliarmy.terraria;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import ch.bbcag.gipfeliarmy.GipfeliArmyMod;

// The one thing the server has to tell a client about the bosses that the client could not see for
// itself: where their lairs are. A lair two thousand blocks off is in a chunk nobody has loaded, so
// the waypoints to it are sent down as a list, whole, whenever one changes.
//
// As with TerritoryNetwork, the client side is a listener plugged in from client code, so this
// class never names a client-only class.
public final class TerrariaNetwork {
    private static final String VERSION = "1";

    public static @Nullable Consumer<Waypoints> waypointListener;

    private TerrariaNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToClient(Waypoints.TYPE, Waypoints.CODEC, (payload, context) -> {
            if (waypointListener != null) {
                waypointListener.accept(payload);
            }
        });
    }

    // One lair. y is only known once the lair has been built, since until then nobody knows how
    // high the ground there is; readyAt is the game time the boss can next be woken there.
    public record Waypoint(BossKind kind, int x, int y, int z, boolean yKnown, long readyAt) {
    }

    // Server -> client: every lair in the overworld.
    public record Waypoints(List<Waypoint> waypoints) implements CustomPacketPayload {
        public static final Type<Waypoints> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(GipfeliArmyMod.MODID, "terraria_waypoints"));
        public static final StreamCodec<FriendlyByteBuf, Waypoints> CODEC = StreamCodec.ofMember(Waypoints::write, Waypoints::read);

        private void write(FriendlyByteBuf buf) {
            buf.writeVarInt(this.waypoints.size());
            for (Waypoint waypoint : this.waypoints) {
                buf.writeVarInt(waypoint.kind().ordinal());
                buf.writeVarInt(waypoint.x());
                buf.writeVarInt(waypoint.y());
                buf.writeVarInt(waypoint.z());
                buf.writeBoolean(waypoint.yKnown());
                buf.writeVarLong(waypoint.readyAt());
            }
        }

        private static Waypoints read(FriendlyByteBuf buf) {
            int count = Math.min(buf.readVarInt(), 64);
            List<Waypoint> waypoints = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                waypoints.add(new Waypoint(BossKind.byOrdinal(buf.readVarInt()), buf.readVarInt(), buf.readVarInt(),
                        buf.readVarInt(), buf.readBoolean(), buf.readVarLong()));
            }

            return new Waypoints(waypoints);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
