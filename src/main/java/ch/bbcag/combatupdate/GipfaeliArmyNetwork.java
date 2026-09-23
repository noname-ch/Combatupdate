package ch.bbcag.combatupdate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
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

// What the army's screens and the server say to each other.
//
// The territory screen asks for soldiers to be marched on a chunk. The army screen asks for the
// roster - every soldier the player has, what it carries and what it is doing - and sends the
// orders its buttons stand for, for one soldier or for a squad. The server answers every order
// with the roster again, so the screen shows what the order did rather than what it hoped.
//
// Payloads coming down to the client are handed to listeners that the client plugs in, so this
// class never names a client-only class and loads cleanly on a dedicated server.
public final class GipfaeliArmyNetwork {
    private static final String VERSION = "1";

    public static @Nullable Consumer<Roster> rosterListener;
    public static @Nullable Consumer<OpenArmy> openListener;

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
                GipfaeliArmy.sendRoster(player);
            }
        });
        registrar.playToServer(RosterRequest.TYPE, RosterRequest.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                GipfaeliArmy.sendRoster(player);
            }
        });
        registrar.playToServer(SoldierOrder.TYPE, SoldierOrder.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                GipfaeliArmy.soldierOrder(player, payload.soldier(), payload.action(), payload.argument());
            }
        });
        registrar.playToServer(SquadOrder.TYPE, SquadOrder.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                GipfaeliArmy.squadOrder(player, payload.scope(), payload.action(), payload.argument());
            }
        });

        registrar.playToClient(Roster.TYPE, Roster.CODEC, (payload, context) -> {
            if (rosterListener != null) {
                rosterListener.accept(payload);
            }
        });
        registrar.playToClient(OpenArmy.TYPE, OpenArmy.CODEC, (payload, context) -> {
            if (openListener != null) {
                openListener.accept(payload);
            }
        });
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(CombatUpdate.MODID, path));
    }

    private static <E extends Enum<E>> StreamCodec<FriendlyByteBuf, E> ordinal(E[] values) {
        return ByteBufCodecs.VAR_INT.map(index -> values[Math.clamp(index, 0, values.length - 1)], Enum::ordinal);
    }

    // Client -> server: send count soldiers to hold this chunk. The server clamps the count to the
    // squad the player actually has, and the chunk to the ones its map could have shown.
    public record MarchRequest(int chunkX, int chunkZ, int count) implements CustomPacketPayload {
        public static final Type<MarchRequest> TYPE = type("army_march");
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

    // Client -> server: tell me about my army.
    public record RosterRequest() implements CustomPacketPayload {
        public static final Type<RosterRequest> TYPE = type("army_roster_request");
        public static final StreamCodec<FriendlyByteBuf, RosterRequest> CODEC = StreamCodec.unit(new RosterRequest());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // What one soldier can be told from its screen. The argument is the kit's, the suit's, the
    // colour's or the post's name, as the commands spell them; empty where the action takes none.
    public enum SoldierAction {
        OPEN, KIT, DISARM, ARMOUR, STRIP, COLOUR, PROMOTE, DEMOTE, POST, DISMISS;

        private static final SoldierAction[] ALL = values();
    }

    // Client -> server: one soldier, one order. The soldier is named by UUID rather than entity id
    // because the roster lists soldiers standing in chunks the client has never seen.
    public record SoldierOrder(UUID soldier, SoldierAction action, String argument) implements CustomPacketPayload {
        public static final Type<SoldierOrder> TYPE = type("army_soldier_order");
        public static final StreamCodec<FriendlyByteBuf, SoldierOrder> CODEC = StreamCodec.composite(
                StreamCodec.of(FriendlyByteBuf::writeUUID, FriendlyByteBuf::readUUID), SoldierOrder::soldier,
                ordinal(SoldierAction.ALL), SoldierOrder::action,
                ByteBufCodecs.STRING_UTF8, SoldierOrder::argument,
                SoldierOrder::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // What a squad - or the whole army - can be told from its screen.
    public enum SquadAction {
        ATTACK, STAND, HOLD, FOLLOW, KIT, DISARM, ARMOUR, STRIP, FILL, FORM, DISMISS, RAISE;

        private static final SquadAction[] ALL = values();
    }

    // Client -> server: everyone in scope, one order. The scope is the word the commands use:
    // "all", "camo", or a dye's name.
    public record SquadOrder(String scope, SquadAction action, String argument) implements CustomPacketPayload {
        public static final Type<SquadOrder> TYPE = type("army_squad_order");
        public static final StreamCodec<FriendlyByteBuf, SquadOrder> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, SquadOrder::scope,
                ordinal(SquadAction.ALL), SquadOrder::action,
                ByteBufCodecs.STRING_UTF8, SquadOrder::argument,
                SquadOrder::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // What a soldier is up to, as one word for the roster.
    public enum Activity {
        FOLLOWING, FIGHTING, HOLDING, MARCHING, GUARDING, PARADE;

        private static final Activity[] ALL = values();

        public static Activity byOrdinal(int ordinal) {
            return ALL[Math.clamp(ordinal, 0, ALL.length - 1)];
        }

        public String key() {
            return "combatupdate.army.screen.activity." + this.name().toLowerCase(Locale.ROOT);
        }
    }

    // Server -> client: the army as it stands. One entry a soldier, with what the screen shows of
    // it; the squad limits and the recruiting price, so the screen can say what a button costs;
    // the player's guard posts, by number; and whether kit is free right now (creative, or the
    // supplies switched off), which decides whether the kit buttons are offered at all.
    public record Roster(List<Entry> soldiers, int squadSize, int armyCap, int rations, List<BlockPos> posts, boolean free)
            implements CustomPacketPayload {
        public static final Type<Roster> TYPE = type("army_roster");
        public static final StreamCodec<FriendlyByteBuf, Roster> CODEC = StreamCodec.of(Roster::write, Roster::read);

        // The entity id is what the client looks the soldier up by when it is near enough to be
        // loaded there; a soldier off in another chunk has one too, it just finds nothing.
        public record Entry(UUID id, int entityId, String name, int weapon, int armour, int uniform, boolean commander,
                            float health, float maxHealth, Activity activity, boolean here, int chunkX, int chunkZ) {
            private void write(FriendlyByteBuf buffer) {
                buffer.writeUUID(this.id);
                buffer.writeVarInt(this.entityId);
                buffer.writeUtf(this.name);
                buffer.writeVarInt(this.weapon);
                buffer.writeVarInt(this.armour);
                buffer.writeVarInt(this.uniform);
                buffer.writeBoolean(this.commander);
                buffer.writeFloat(this.health);
                buffer.writeFloat(this.maxHealth);
                buffer.writeVarInt(this.activity.ordinal());
                buffer.writeBoolean(this.here);
                buffer.writeVarInt(this.chunkX);
                buffer.writeVarInt(this.chunkZ);
            }

            private static Entry read(FriendlyByteBuf buffer) {
                return new Entry(buffer.readUUID(), buffer.readVarInt(), buffer.readUtf(), buffer.readVarInt(), buffer.readVarInt(),
                        buffer.readVarInt(), buffer.readBoolean(), buffer.readFloat(), buffer.readFloat(),
                        Activity.byOrdinal(buffer.readVarInt()), buffer.readBoolean(), buffer.readVarInt(), buffer.readVarInt());
            }
        }

        private static void write(FriendlyByteBuf buffer, Roster roster) {
            buffer.writeVarInt(roster.soldiers.size());
            for (Entry entry : roster.soldiers) {
                entry.write(buffer);
            }

            buffer.writeVarInt(roster.squadSize);
            buffer.writeVarInt(roster.armyCap);
            buffer.writeVarInt(roster.rations);
            buffer.writeVarInt(roster.posts.size());
            for (BlockPos post : roster.posts) {
                buffer.writeBlockPos(post);
            }

            buffer.writeBoolean(roster.free);
        }

        private static Roster read(FriendlyByteBuf buffer) {
            int count = buffer.readVarInt();
            List<Entry> soldiers = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                soldiers.add(Entry.read(buffer));
            }

            int squadSize = buffer.readVarInt();
            int armyCap = buffer.readVarInt();
            int rations = buffer.readVarInt();
            int postCount = buffer.readVarInt();
            List<BlockPos> posts = new ArrayList<>(postCount);
            for (int index = 0; index < postCount; index++) {
                posts.add(buffer.readBlockPos());
            }

            return new Roster(soldiers, squadSize, armyCap, rations, posts, buffer.readBoolean());
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // Server -> client: open the army screen on this scope. What clicking a commander, or
    // sneaking with the flag, does.
    public record OpenArmy(String scope) implements CustomPacketPayload {
        public static final Type<OpenArmy> TYPE = type("army_open");
        public static final StreamCodec<FriendlyByteBuf, OpenArmy> CODEC = StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, OpenArmy::scope,
                OpenArmy::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
