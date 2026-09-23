package ch.bbcag.combatupdate.territory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.TriState;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import ch.bbcag.combatupdate.Config;
import ch.bbcag.combatupdate.territory.TerritoryNetwork.ActionRequest;
import ch.bbcag.combatupdate.territory.TerritoryNetwork.ChunkInfoPayload;
import ch.bbcag.combatupdate.territory.TerritoryNetwork.MapPayload;

// Territory, server side: who holds which chunk, what that keeps everyone else from doing there,
// and how a chunk changes hands.
//
// Claiming is free and instant, but only the chunk you are standing in. Taking a chunk off
// someone else is a capture: stand in it for a configured stretch of time, during which the owner
// is told, by name and by chunk, that it is happening. Leave, die or log out and the capture
// lapses. That window is the whole defence a claim has, so it is what the config knob is for.
//
// Whatever the owner is told reaches them one way or another: in chat with a sound if they are
// on, queued in the save and delivered the moment they next log in if they are not.
public final class TerritoryManager {
    // A capture under way. Kept in memory only: a restart mid-capture simply drops it, which is
    // the same as the attacker having stepped out.
    private record Capture(UUID attacker, String attackerName, TerritoryClaim.Key key, UUID owner,
            String ownerName, int ticksLeft) {
        Capture tick() {
            return new Capture(attacker, attackerName, key, owner, ownerName, ticksLeft - 1);
        }
    }

    private static final Map<UUID, Capture> CAPTURES = new HashMap<>();

    // A blocked click is answered on the action bar, but not on every click: the same message
    // twenty times a second is noise, not information.
    private static final Map<UUID, Long> LAST_DENIAL = new HashMap<>();
    private static final int DENIAL_INTERVAL_TICKS = 20;

    private static final float SOUND_VOLUME = 1.0F;

    private TerritoryManager() {
    }

    // ---- Lookups ----

    public static @Nullable TerritoryClaim claimAt(ServerLevel level, ChunkPos pos) {
        return TerritoryData.get(level.getServer()).get(TerritoryClaim.Key.of(level, pos));
    }

    private static @Nullable Capture captureOf(TerritoryClaim.Key key) {
        for (Capture capture : CAPTURES.values()) {
            if (capture.key().equals(key)) {
                return capture;
            }
        }
        return null;
    }

    private static boolean enabled() {
        return Config.on(Config.ENABLE_TERRITORY);
    }

    // ---- Actions ----

    // Claims the chunk the player is standing in.
    public static boolean claim(ServerPlayer player) {
        if (!enabled()) {
            return false;
        }
        ServerLevel level = player.level();
        ChunkPos pos = player.chunkPosition();
        TerritoryClaim.Key key = TerritoryClaim.Key.of(level, pos);
        TerritoryData data = TerritoryData.get(level.getServer());
        UUID me = player.getUUID();

        TerritoryClaim existing = data.get(key);
        if (existing != null) {
            chat(player, existing.ownedBy(me)
                    ? text("already_yours").withStyle(ChatFormatting.YELLOW)
                    : text("already_claimed", existing.ownerName()).withStyle(ChatFormatting.RED));
            return false;
        }

        int max = Config.TERRITORY_MAX_CLAIMS.get();
        int held = data.count(me);
        if (held >= max) {
            chat(player, text("limit", max).withStyle(ChatFormatting.RED));
            return false;
        }

        data.put(new TerritoryClaim(key.dimension(), pos.x(), pos.z(), me, nameOf(player), System.currentTimeMillis()));
        chat(player, text("claimed", pos.x(), pos.z(), held + 1, max).withStyle(ChatFormatting.GREEN));
        ping(player, SoundEvents.NOTE_BLOCK_PLING, 1.2F);
        return true;
    }

    // Gives up a claim. Any of the player's own chunks may be let go of from a distance: there is
    // nothing to defend in walking back to a place only to leave it.
    public static boolean unclaim(ServerPlayer player, int chunkX, int chunkZ) {
        if (!enabled()) {
            return false;
        }
        ServerLevel level = player.level();
        TerritoryClaim.Key key = TerritoryClaim.Key.of(level, chunkX, chunkZ);
        TerritoryData data = TerritoryData.get(level.getServer());

        TerritoryClaim existing = data.get(key);
        if (existing == null) {
            chat(player, text("not_claimed").withStyle(ChatFormatting.YELLOW));
            return false;
        }
        if (!existing.ownedBy(player.getUUID()) && !bypasses(player)) {
            chat(player, text("not_yours", existing.ownerName()).withStyle(ChatFormatting.RED));
            return false;
        }

        data.remove(key);
        chat(player, text("unclaimed", chunkX, chunkZ).withStyle(ChatFormatting.YELLOW));

        // Whoever was in the middle of taking it now has nothing to take; it is theirs for the
        // asking, the ordinary way.
        Capture capture = captureOf(key);
        if (capture != null) {
            CAPTURES.remove(capture.attacker());
            ServerPlayer attacker = level.getServer().getPlayerList().getPlayer(capture.attacker());
            if (attacker != null) {
                chat(attacker, text("capture.cancelled_gone", chunkX, chunkZ).withStyle(ChatFormatting.YELLOW));
            }
        }
        return true;
    }

    // Starts taking the chunk the player is standing in off whoever holds it.
    public static boolean capture(ServerPlayer player) {
        if (!enabled()) {
            return false;
        }
        ServerLevel level = player.level();
        MinecraftServer server = level.getServer();
        ChunkPos pos = player.chunkPosition();
        TerritoryClaim.Key key = TerritoryClaim.Key.of(level, pos);
        TerritoryData data = TerritoryData.get(server);
        UUID me = player.getUUID();

        TerritoryClaim existing = data.get(key);
        if (existing == null) {
            chat(player, text("capture.unclaimed").withStyle(ChatFormatting.YELLOW));
            return false;
        }
        if (existing.ownedBy(me)) {
            chat(player, text("capture.own").withStyle(ChatFormatting.YELLOW));
            return false;
        }
        Capture mine = CAPTURES.get(me);
        if (mine != null) {
            chat(player, text("capture.already", mine.key().chunkX(), mine.key().chunkZ()).withStyle(ChatFormatting.YELLOW));
            return false;
        }
        Capture other = captureOf(key);
        if (other != null) {
            chat(player, text("capture.busy", other.attackerName()).withStyle(ChatFormatting.RED));
            return false;
        }
        int max = Config.TERRITORY_MAX_CLAIMS.get();
        if (data.count(me) >= max) {
            chat(player, text("limit", max).withStyle(ChatFormatting.RED));
            return false;
        }
        boolean ownerOnline = server.getPlayerList().getPlayer(existing.owner()) != null;
        if (Config.TERRITORY_CAPTURE_NEEDS_OWNER_ONLINE.get() && !ownerOnline) {
            chat(player, text("capture.owner_offline", existing.ownerName()).withStyle(ChatFormatting.RED));
            return false;
        }

        int seconds = Config.TERRITORY_CAPTURE_SECONDS.get();
        if (seconds <= 0) {
            transfer(player, existing, data);
            return true;
        }

        CAPTURES.put(me, new Capture(me, nameOf(player), key, existing.owner(), existing.ownerName(), seconds * 20));
        chat(player, text("capture.started", pos.x(), pos.z(), existing.ownerName(), seconds).withStyle(ChatFormatting.GOLD));
        notify(server, existing.owner(), true, SoundEvents.NOTE_BLOCK_BELL, 0.7F,
                "notify.capture_started", nameOf(player), pos.x(), pos.z());
        return true;
    }

    public static boolean cancelCapture(ServerPlayer player) {
        Capture capture = CAPTURES.remove(player.getUUID());
        if (capture == null) {
            chat(player, text("capture.none").withStyle(ChatFormatting.YELLOW));
            return false;
        }
        chat(player, text("capture.cancelled", capture.key().chunkX(), capture.key().chunkZ()).withStyle(ChatFormatting.YELLOW));
        notify(player.level().getServer(), capture.owner(), false, SoundEvents.NOTE_BLOCK_PLING, 1.0F,
                "notify.capture_failed", capture.attackerName(), capture.key().chunkX(), capture.key().chunkZ());
        return true;
    }

    // Tells the player about the chunk they are standing in.
    public static boolean info(ServerPlayer player) {
        ServerLevel level = player.level();
        ChunkPos pos = player.chunkPosition();
        TerritoryClaim claim = claimAt(level, pos);
        if (claim == null) {
            chat(player, text("info.unclaimed", pos.x(), pos.z()));
        } else if (claim.ownedBy(player.getUUID())) {
            chat(player, text("info.yours", pos.x(), pos.z()).withStyle(ChatFormatting.GREEN));
        } else {
            chat(player, text("info.theirs", pos.x(), pos.z(), claim.ownerName()).withStyle(ChatFormatting.RED));
        }
        Capture capture = captureOf(TerritoryClaim.Key.of(level, pos));
        if (capture != null) {
            chat(player, text("info.contested", capture.attackerName(), capture.ticksLeft() / 20).withStyle(ChatFormatting.GOLD));
        }
        return true;
    }

    // Lists everything the player holds.
    public static boolean list(ServerPlayer player) {
        TerritoryData data = TerritoryData.get(player.level().getServer());
        List<TerritoryClaim> mine = data.claimsOf(player.getUUID());
        chat(player, text("list.header", mine.size(), Config.TERRITORY_MAX_CLAIMS.get()).withStyle(ChatFormatting.GOLD));
        for (TerritoryClaim claim : mine) {
            chat(player, text("list.entry", claim.chunkX(), claim.chunkZ(),
                    claim.chunkX() * 16 + 8, claim.chunkZ() * 16 + 8, claim.dimension()));
        }
        return true;
    }

    private static void transfer(ServerPlayer attacker, TerritoryClaim claim, TerritoryData data) {
        data.put(new TerritoryClaim(claim.dimension(), claim.chunkX(), claim.chunkZ(),
                attacker.getUUID(), nameOf(attacker), System.currentTimeMillis()));
        chat(attacker, text("capture.done", claim.chunkX(), claim.chunkZ(), claim.ownerName()).withStyle(ChatFormatting.GREEN));
        ping(attacker, SoundEvents.NOTE_BLOCK_PLING, 1.2F);
        notify(attacker.level().getServer(), claim.owner(), true, SoundEvents.NOTE_BLOCK_BELL, 0.5F,
                "notify.captured", nameOf(attacker), claim.chunkX(), claim.chunkZ());
    }

    // ---- The screen's requests ----

    public static void sendMap(ServerPlayer player, int centerX, int centerZ) {
        if (!enabled()) {
            return;
        }
        ServerLevel level = player.level();
        MinecraftServer server = level.getServer();
        TerritoryData data = TerritoryData.get(server);
        int radius = Config.TERRITORY_MAP_RADIUS.get();
        UUID me = player.getUUID();

        // The map is always drawn around the player. The request carries a centre only so the
        // client can say what it thinks that is; it is not allowed to scroll off to look at
        // somewhere it is not.
        ChunkPos own = player.chunkPosition();
        centerX = Math.clamp(centerX, own.x() - 1, own.x() + 1);
        centerZ = Math.clamp(centerZ, own.z() - 1, own.z() + 1);

        String dimension = TerritoryClaim.Key.dimensionOf(level);
        List<MapPayload.Tile> tiles = new ArrayList<>();
        List<MapPayload.ClaimInfo> claims = new ArrayList<>();
        for (int cz = centerZ - radius; cz <= centerZ + radius; cz++) {
            for (int cx = centerX - radius; cx <= centerX + radius; cx++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk != null) {
                    tiles.add(new MapPayload.Tile(cx, cz, TerritoryMap.render(level, chunk)));
                }
                TerritoryClaim claim = data.get(new TerritoryClaim.Key(dimension, cx, cz));
                if (claim != null) {
                    Capture capture = captureOf(claim.key());
                    claims.add(new MapPayload.ClaimInfo(cx, cz, claim.owner(), claim.ownerName(), claim.claimedAt(),
                            capture == null ? null : capture.attackerName()));
                }
            }
        }

        Capture mine = CAPTURES.get(me);
        MapPayload.CaptureState state = mine == null || !mine.key().dimension().equals(dimension) ? null
                : new MapPayload.CaptureState(mine.key().chunkX(), mine.key().chunkZ(), mine.ticksLeft());

        PacketDistributor.sendToPlayer(player, new MapPayload(centerX, centerZ, radius, tiles, claims,
                data.count(me), Config.TERRITORY_MAX_CLAIMS.get(), Config.TERRITORY_CAPTURE_SECONDS.get(), state));
    }

    public static void sendChunkInfo(ServerPlayer player, int chunkX, int chunkZ) {
        if (!enabled()) {
            return;
        }
        ServerLevel level = player.level();
        ChunkPos own = player.chunkPosition();
        int reach = Config.TERRITORY_MAP_RADIUS.get() + 1;
        if (Math.abs(chunkX - own.x()) > reach || Math.abs(chunkZ - own.z()) > reach) {
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        ChunkInfoPayload payload = chunk == null
                ? new ChunkInfoPayload(chunkX, chunkZ, -1, List.of())
                : TerritoryMap.overview(chunk);
        PacketDistributor.sendToPlayer(player, payload);
    }

    public static void handleAction(ServerPlayer player, ActionRequest request) {
        if (!enabled()) {
            return;
        }
        ChunkPos own = player.chunkPosition();
        boolean standingThere = own.x() == request.chunkX() && own.z() == request.chunkZ();
        switch (request.action()) {
            case CLAIM -> {
                if (standingThere) {
                    claim(player);
                } else {
                    chat(player, text("not_here").withStyle(ChatFormatting.RED));
                }
            }
            case CAPTURE -> {
                if (standingThere) {
                    capture(player);
                } else {
                    chat(player, text("not_here").withStyle(ChatFormatting.RED));
                }
            }
            case UNCLAIM -> unclaim(player, request.chunkX(), request.chunkZ());
            case CANCEL_CAPTURE -> cancelCapture(player);
        }
        // Whatever happened, the screen gets the new state of things straight away rather than on
        // its next refresh.
        sendMap(player, own.x(), own.z());
        sendChunkInfo(player, request.chunkX(), request.chunkZ());
    }

    // ---- Captures running down ----

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (CAPTURES.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        TerritoryData data = TerritoryData.get(server);

        Iterator<Map.Entry<UUID, Capture>> captures = CAPTURES.entrySet().iterator();
        while (captures.hasNext()) {
            Map.Entry<UUID, Capture> entry = captures.next();
            Capture capture = entry.getValue();
            int chunkX = capture.key().chunkX();
            int chunkZ = capture.key().chunkZ();

            ServerPlayer attacker = server.getPlayerList().getPlayer(capture.attacker());
            boolean holding = attacker != null && attacker.isAlive()
                    && TerritoryClaim.Key.of(attacker.level(), attacker.chunkPosition()).equals(capture.key());
            if (!holding) {
                captures.remove();
                if (attacker != null) {
                    chat(attacker, text("capture.cancelled", chunkX, chunkZ).withStyle(ChatFormatting.YELLOW));
                }
                notify(server, capture.owner(), false, SoundEvents.NOTE_BLOCK_PLING, 1.0F,
                        "notify.capture_failed", capture.attackerName(), chunkX, chunkZ);
                continue;
            }

            // The claim being captured may have been given up or already changed hands.
            TerritoryClaim claim = data.get(capture.key());
            if (claim == null || !claim.owner().equals(capture.owner())) {
                captures.remove();
                chat(attacker, text("capture.cancelled_gone", chunkX, chunkZ).withStyle(ChatFormatting.YELLOW));
                continue;
            }

            Capture next = capture.tick();
            if (next.ticksLeft() <= 0) {
                captures.remove();
                int max = Config.TERRITORY_MAX_CLAIMS.get();
                if (data.count(attacker.getUUID()) >= max) {
                    chat(attacker, text("limit", max).withStyle(ChatFormatting.RED));
                    continue;
                }
                transfer(attacker, claim, data);
                continue;
            }

            entry.setValue(next);
            if (next.ticksLeft() % 20 == 0) {
                bar(attacker, text("capture.progress", next.ticksLeft() / 20).withStyle(ChatFormatting.GOLD));
            }
        }
    }

    // ---- Coming and going ----

    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !enabled()) {
            return;
        }
        TerritoryData data = TerritoryData.get(player.level().getServer());
        List<TerritoryData.Notice> notices = data.drainNotices(player.getUUID());
        if (notices.isEmpty()) {
            return;
        }
        chat(player, text("notify.pending", notices.size()).withStyle(ChatFormatting.GOLD));
        for (TerritoryData.Notice notice : notices) {
            chat(player, Component.literal("  ").append(
                    Component.translatable(notice.key(), notice.args().toArray()).withStyle(ChatFormatting.YELLOW)));
        }
        ping(player, SoundEvents.NOTE_BLOCK_BELL, 0.8F);
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        LAST_DENIAL.remove(player.getUUID());
        Capture capture = CAPTURES.remove(player.getUUID());
        if (capture != null) {
            notify(player.level().getServer(), capture.owner(), false, SoundEvents.NOTE_BLOCK_PLING, 1.0F,
                    "notify.capture_failed", capture.attackerName(), capture.key().chunkX(), capture.key().chunkZ());
        }
    }

    // ---- What a claim keeps out ----

    @SubscribeEvent
    static void onBreakBlock(BreakBlockEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        if (!enabled() || !Config.TERRITORY_PROTECT_BLOCKS.get()) {
            return;
        }
        TerritoryClaim claim = claimAt(level, ChunkPos.containing(event.getPos()));
        if (claim == null || mayBuild(player, claim)) {
            return;
        }
        event.setCanceled(true);
        // The client has already knocked the block out on its own screen; this puts it back.
        event.setNotifyClient(true);
        deny(player, claim);
    }

    @SubscribeEvent
    static void onPlaceBlock(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!enabled() || !Config.TERRITORY_PROTECT_BLOCKS.get()) {
            return;
        }
        TerritoryClaim claim = claimAt(level, ChunkPos.containing(event.getPos()));
        if (claim == null || mayBuild(player, claim)) {
            return;
        }
        event.setCanceled(true);
        deny(player, claim);
    }

    // Chests, furnaces, doors, buttons and the like. Only the block use is refused: whatever is in
    // the hand still works, so a snack can be eaten while standing in front of somebody's door.
    @SubscribeEvent
    static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (!enabled() || !Config.TERRITORY_PROTECT_INTERACTIONS.get()) {
            return;
        }
        BlockState state = level.getBlockState(event.getPos());
        boolean interactive = state.hasBlockEntity()
                || state.getBlock() instanceof DoorBlock
                || state.getBlock() instanceof TrapDoorBlock
                || state.getBlock() instanceof FenceGateBlock
                || state.getBlock() instanceof ButtonBlock
                || state.getBlock() instanceof LeverBlock;
        if (!interactive) {
            return;
        }
        TerritoryClaim claim = claimAt(level, ChunkPos.containing(event.getPos()));
        if (claim == null || mayBuild(player, claim)) {
            return;
        }
        event.setUseBlock(TriState.FALSE);
        deny(player, claim);
    }

    // An explosion leaves claimed ground alone, unless it was the owner's own doing.
    @SubscribeEvent
    static void onDetonate(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!enabled() || !Config.TERRITORY_PROTECT_FROM_EXPLOSIONS.get()) {
            return;
        }
        TerritoryData data = TerritoryData.get(level.getServer());
        String dimension = TerritoryClaim.Key.dimensionOf(level);
        UUID culprit = event.getExplosion().getIndirectSourceEntity() instanceof Player player ? player.getUUID() : null;
        event.getAffectedBlocks().removeIf(pos -> {
            ChunkPos chunk = ChunkPos.containing(pos);
            TerritoryClaim claim = data.get(new TerritoryClaim.Key(dimension, chunk.x(), chunk.z()));
            return claim != null && !claim.owner().equals(culprit);
        });
    }

    private static boolean mayBuild(ServerPlayer player, TerritoryClaim claim) {
        return claim.ownedBy(player.getUUID()) || bypasses(player);
    }

    private static boolean bypasses(ServerPlayer player) {
        return Config.TERRITORY_OPS_BYPASS.get() && player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private static void deny(ServerPlayer player, TerritoryClaim claim) {
        long now = player.level().getGameTime();
        Long last = LAST_DENIAL.get(player.getUUID());
        if (last != null && now - last < DENIAL_INTERVAL_TICKS) {
            return;
        }
        LAST_DENIAL.put(player.getUUID(), now);
        bar(player, text("protected", claim.ownerName()).withStyle(ChatFormatting.RED));
    }

    // ---- Telling people things ----

    // Delivers a message to a player by UUID: now, if they are on; on their next login, if they are
    // not and the message is worth keeping until then.
    private static void notify(MinecraftServer server, UUID target, boolean queueIfOffline,
            Holder<SoundEvent> sound, float pitch, String key, Object... args) {
        ServerPlayer player = server.getPlayerList().getPlayer(target);
        if (player != null) {
            MutableComponent message = text(key, args).withStyle(ChatFormatting.YELLOW);
            chat(player, message);
            bar(player, message);
            ping(player, sound, pitch);
            return;
        }
        if (queueIfOffline) {
            List<String> stringArgs = new ArrayList<>();
            for (Object arg : args) {
                stringArgs.add(String.valueOf(arg));
            }
            TerritoryData.get(server).addNotice(new TerritoryData.Notice(target, "combatupdate.territory." + key,
                    stringArgs, System.currentTimeMillis()));
        }
    }

    private static void chat(ServerPlayer player, Component message) {
        player.sendSystemMessage(message);
    }

    private static void bar(ServerPlayer player, Component message) {
        player.sendSystemMessage(message, true);
    }

    // A sound for one player only, played where they stand: sent straight down their connection
    // rather than into the level, so nobody nearby hears somebody else's notification.
    private static void ping(ServerPlayer player, Holder<SoundEvent> sound, float pitch) {
        if (!Config.TERRITORY_NOTIFY_SOUND.get()) {
            return;
        }
        player.connection.send(new ClientboundSoundPacket(sound, SoundSource.PLAYERS,
                player.getX(), player.getY(), player.getZ(), SOUND_VOLUME, pitch, player.level().getRandom().nextLong()));
    }

    private static MutableComponent text(String key, Object... args) {
        return Component.translatable("combatupdate.territory." + key, args);
    }

    private static String nameOf(ServerPlayer player) {
        return player.getGameProfile().name();
    }
}
