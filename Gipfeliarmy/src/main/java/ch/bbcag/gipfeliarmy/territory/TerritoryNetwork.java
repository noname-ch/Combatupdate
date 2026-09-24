package ch.bbcag.gipfeliarmy.territory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import ch.bbcag.gipfeliarmy.GipfeliArmyMod;

// What the territory screen and the server say to each other.
//
// The client asks (for the map around it, for one chunk's contents, or for something to be done)
// and the server answers with the whole picture again rather than a diff: a map is small, the
// screen redraws it in one go, and there is then no way for the two sides to drift apart.
//
// Payloads coming down to the client are handed to listeners that GipfeliArmyClient plugs in,
// so this class never names a client-only class and loads cleanly on a dedicated server.
public final class TerritoryNetwork {
    private static final String VERSION = "1";

    public static @Nullable Consumer<MapPayload> mapListener;
    public static @Nullable Consumer<ChunkInfoPayload> chunkInfoListener;

    private TerritoryNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);

        registrar.playToServer(MapRequest.TYPE, MapRequest.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                TerritoryManager.sendMap(player, payload.centerX(), payload.centerZ());
            }
        });
        registrar.playToServer(ChunkInfoRequest.TYPE, ChunkInfoRequest.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                TerritoryManager.sendChunkInfo(player, payload.chunkX(), payload.chunkZ());
            }
        });
        registrar.playToServer(ActionRequest.TYPE, ActionRequest.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                TerritoryManager.handleAction(player, payload);
            }
        });

        registrar.playToClient(MapPayload.TYPE, MapPayload.CODEC, (payload, context) -> {
            if (mapListener != null) {
                mapListener.accept(payload);
            }
        });
        registrar.playToClient(ChunkInfoPayload.TYPE, ChunkInfoPayload.CODEC, (payload, context) -> {
            if (chunkInfoListener != null) {
                chunkInfoListener.accept(payload);
            }
        });
    }

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(GipfeliArmyMod.MODID, path));
    }

    public enum Action {
        CLAIM, UNCLAIM, CAPTURE, CANCEL_CAPTURE;

        static Action byOrdinal(int ordinal) {
            Action[] values = values();
            return values[Math.clamp(ordinal, 0, values.length - 1)];
        }
    }

    // Client -> server: draw me the map around this chunk.
    public record MapRequest(int centerX, int centerZ) implements CustomPacketPayload {
        public static final Type<MapRequest> TYPE = payloadType("territory_map_request");
        public static final StreamCodec<FriendlyByteBuf, MapRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, MapRequest::centerX,
                ByteBufCodecs.VAR_INT, MapRequest::centerZ,
                MapRequest::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // Client -> server: what is in this chunk, and whose is it.
    public record ChunkInfoRequest(int chunkX, int chunkZ) implements CustomPacketPayload {
        public static final Type<ChunkInfoRequest> TYPE = payloadType("territory_chunk_info_request");
        public static final StreamCodec<FriendlyByteBuf, ChunkInfoRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, ChunkInfoRequest::chunkX,
                ByteBufCodecs.VAR_INT, ChunkInfoRequest::chunkZ,
                ChunkInfoRequest::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // Client -> server: claim, give up, capture or stop capturing this chunk. The server checks
    // every one of these against where the player actually is; the chunk is what was clicked.
    public record ActionRequest(Action action, int chunkX, int chunkZ) implements CustomPacketPayload {
        public static final Type<ActionRequest> TYPE = payloadType("territory_action");
        public static final StreamCodec<FriendlyByteBuf, ActionRequest> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT.map(Action::byOrdinal, Action::ordinal), ActionRequest::action,
                ByteBufCodecs.VAR_INT, ActionRequest::chunkX,
                ByteBufCodecs.VAR_INT, ActionRequest::chunkZ,
                ActionRequest::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // Server -> client: the map. Tiles are only sent for chunks the server has loaded; the screen
    // draws the rest dark. Colours are vanilla's packed map colours, one byte a block.
    public record MapPayload(int centerX, int centerZ, int radius, List<Tile> tiles, List<ClaimInfo> claims,
            int ownBlocks, int maxBlocks, int captureSeconds, @Nullable CaptureState capture)
            implements CustomPacketPayload {
        public static final Type<MapPayload> TYPE = payloadType("territory_map");
        public static final StreamCodec<FriendlyByteBuf, MapPayload> CODEC = StreamCodec.ofMember(MapPayload::write, MapPayload::new);

        public record Tile(int chunkX, int chunkZ, byte[] colors) {
        }

        public record ClaimInfo(int chunkX, int chunkZ, UUID owner, String ownerName, long claimedAt, @Nullable String capturer) {
        }

        // The local player's own capture in progress, if any, so the screen can count it down.
        public record CaptureState(int chunkX, int chunkZ, int ticksLeft) {
        }

        private MapPayload(FriendlyByteBuf buf) {
            this(buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), readTiles(buf), readClaims(buf),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readNullable(b -> new CaptureState(b.readVarInt(), b.readVarInt(), b.readVarInt())));
        }

        private static List<Tile> readTiles(FriendlyByteBuf buf) {
            int count = buf.readVarInt();
            List<Tile> tiles = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                tiles.add(new Tile(buf.readVarInt(), buf.readVarInt(), buf.readByteArray()));
            }
            return tiles;
        }

        private static List<ClaimInfo> readClaims(FriendlyByteBuf buf) {
            int count = buf.readVarInt();
            List<ClaimInfo> claims = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                claims.add(new ClaimInfo(buf.readVarInt(), buf.readVarInt(), buf.readUUID(), buf.readUtf(),
                        buf.readLong(), buf.readNullable(FriendlyByteBuf::readUtf)));
            }
            return claims;
        }

        private void write(FriendlyByteBuf buf) {
            buf.writeVarInt(centerX);
            buf.writeVarInt(centerZ);
            buf.writeVarInt(radius);
            buf.writeVarInt(tiles.size());
            for (Tile tile : tiles) {
                buf.writeVarInt(tile.chunkX());
                buf.writeVarInt(tile.chunkZ());
                buf.writeByteArray(tile.colors());
            }
            buf.writeVarInt(claims.size());
            for (ClaimInfo claim : claims) {
                buf.writeVarInt(claim.chunkX());
                buf.writeVarInt(claim.chunkZ());
                buf.writeUUID(claim.owner());
                buf.writeUtf(claim.ownerName());
                buf.writeLong(claim.claimedAt());
                buf.writeNullable(claim.capturer(), FriendlyByteBuf::writeUtf);
            }
            buf.writeVarInt(ownBlocks);
            buf.writeVarInt(maxBlocks);
            buf.writeVarInt(captureSeconds);
            buf.writeNullable(capture, (b, state) -> {
                b.writeVarInt(state.chunkX());
                b.writeVarInt(state.chunkZ());
                b.writeVarInt(state.ticksLeft());
            });
        }

        public @Nullable ClaimInfo claimAt(int chunkX, int chunkZ) {
            for (ClaimInfo claim : claims) {
                if (claim.chunkX() == chunkX && claim.chunkZ() == chunkZ) {
                    return claim;
                }
            }
            return null;
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    // Server -> client: what one chunk is made of, most common block first. Block ids rather than
    // names, so the client can show them in its own language. A chunk the server has not got
    // loaded comes back with no blocks and a total of -1.
    public record ChunkInfoPayload(int chunkX, int chunkZ, int totalBlocks, List<BlockCount> blocks)
            implements CustomPacketPayload {
        public static final Type<ChunkInfoPayload> TYPE = payloadType("territory_chunk_info");
        public static final StreamCodec<FriendlyByteBuf, ChunkInfoPayload> CODEC = StreamCodec.ofMember(ChunkInfoPayload::write, ChunkInfoPayload::new);

        public record BlockCount(String blockId, int count) {
        }

        private ChunkInfoPayload(FriendlyByteBuf buf) {
            this(buf.readVarInt(), buf.readVarInt(), buf.readInt(), readBlocks(buf));
        }

        private static List<BlockCount> readBlocks(FriendlyByteBuf buf) {
            int count = buf.readVarInt();
            List<BlockCount> blocks = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                blocks.add(new BlockCount(buf.readUtf(), buf.readVarInt()));
            }
            return blocks;
        }

        private void write(FriendlyByteBuf buf) {
            buf.writeVarInt(chunkX);
            buf.writeVarInt(chunkZ);
            buf.writeInt(totalBlocks);
            buf.writeVarInt(blocks.size());
            for (BlockCount block : blocks) {
                buf.writeUtf(block.blockId());
                buf.writeVarInt(block.count());
            }
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
