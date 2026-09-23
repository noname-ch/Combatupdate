package ch.bbcag.combatupdate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Prediction;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import ch.bbcag.combatupdate.entity.GipfaeliSoldier;
import ch.bbcag.combatupdate.territory.TerritoryClaim;
import ch.bbcag.combatupdate.territory.TerritoryManager;

// The army itself: who is in it, what it has been told, and the menu it is told things through.
//
// There is no roster kept anywhere. A squad is worked out when it is asked for, by sweeping the
// server for soldiers that say they belong to this commander - which is the one answer that cannot
// go stale, survives a restart, and cannot be thrown off by a soldier dying somewhere nobody was
// watching.
//
// Server-side throughout. Orders decide what a mob attacks, which is the server's to settle; what
// the commander sees of it goes out as chat and action-bar messages rather than through any state
// the client keeps of its own.
public final class GipfaeliArmy {
    // How long the mark on a target stays lit after the squad is sent at it. The same glow the
    // launcher's sight uses, for the same reason: it says what the order landed on, through walls,
    // without any rendering of our own.
    private static final int MARK_DURATION_TICKS = 200;

    private static final int NO_SLOT = -1;

    private static final String COMMAND = "/gipfaeliarmy ";

    // A chunk the squad has been sent to take off the map, per commander. In memory only: a
    // restart drops it, and the soldiers still standing there are the squad's memory of it -
    // send them again and the siege picks up where it was.
    private record Siege(TerritoryClaim.Key key, boolean arrived, long nextAttempt) {
    }

    private static final Map<UUID, Siege> SIEGES = new ConcurrentHashMap<>();

    // How often a siege is looked at, and how long after a capture is refused before it is tried
    // again - the owner may have been offline, or the commander at their claim limit, and neither
    // wants saying twenty times a second.
    private static final int SIEGE_INTERVAL_TICKS = 20;
    private static final int SIEGE_RETRY_TICKS = 200;

    // How near the chunk's owner has to come before the garrison turns its guns on them.
    private static final double SIEGE_DEFENDER_RANGE = 32.0;

    // How wide a spot the squad is spread over on arrival, so eight soldiers sent to one chunk are
    // not stood in one block.
    private static final double STATION_SPREAD = 3.0;

    private GipfaeliArmy() {
    }

    // --- Who is in the squad ---

    // Every soldier this player has, wherever in the world it is standing, marchers first and then
    // in the order they were signed on. Soldiers in chunks nobody has loaded are not in here, which
    // is the right answer for every caller: a soldier nobody is near is not fighting, marching, or
    // worth counting against the cap.
    public static List<GipfaeliSoldier> squad(ServerPlayer commander) {
        List<GipfaeliSoldier> squad = new ArrayList<>();
        MinecraftServer server = commander.level().getServer();
        if (server == null) {
            return squad;
        }

        for (ServerLevel level : server.getAllLevels()) {
            level.getEntities(EntityTypeTest.forClass(GipfaeliSoldier.class),
                    soldier -> soldier.isAlive() && soldier.isOwnedBy(commander), squad);
        }

        // The banner at the point of the wedge and the head of the column, where it is of most use
        // and where it looks like it is leading; everyone else keeps the place they joined in.
        squad.sort(Comparator.<GipfaeliSoldier>comparingInt(soldier -> soldier.weapon() == GipfaeliWeapon.MARCHER ? 0 : 1)
                .thenComparingInt(Entity::getId));
        return squad;
    }

    // Whether two things fight under the same flag: a commander and their own soldiers, or two
    // soldiers of the one commander. This is what keeps a volley off the squad's own backs, and it
    // is asked by every bullet in flight, so it does no lookups beyond the owner each side names.
    public static boolean sameSide(@Nullable Entity one, @Nullable Entity other) {
        if (one == null || other == null) {
            return false;
        }

        if (one == other) {
            return true;
        }

        UUID oneFlag = flag(one);
        return oneFlag != null && oneFlag.equals(flag(other));
    }

    private static @Nullable UUID flag(Entity entity) {
        if (entity instanceof GipfaeliSoldier soldier) {
            LivingEntity owner = soldier.getOwner();
            return owner == null ? null : owner.getUUID();
        }

        return entity instanceof Player player ? player.getUUID() : null;
    }

    // --- Recruiting ---

    // Signs one soldier on, armed out of the commander's own pack: with the kit for the role that
    // was asked for, or with the first kit found when none was. Returns whether anything was done
    // at all, which for the flag is the cue to swallow the click either way - being told the squad
    // is full is as much of an answer as a new soldier is.
    public static boolean recruit(ServerPlayer commander, @Nullable GipfaeliWeapon role) {
        int cap = Config.ARMY_MAX_SQUAD.getAsInt();
        List<GipfaeliSoldier> squad = squad(commander);
        if (squad.size() >= cap) {
            refuse(commander, Component.translatable("combatupdate.army.full", cap));
            return true;
        }

        // Creative pays for nothing, and neither does anyone who has turned the supplies off - the
        // same bargain the launcher and the rig offer.
        boolean free = commander.getAbilities().instabuild || !Config.ARMY_CONSUMES_SUPPLIES.get();
        int weaponSlot = findWeaponSlot(commander, role);
        if (weaponSlot == NO_SLOT && !free) {
            refuse(commander, role == null
                    ? Component.translatable("combatupdate.army.no_weapon")
                    : Component.translatable("combatupdate.army.no_kit", role.stack().getHoverName()));
            return true;
        }

        // A recruit is handed a kit out of the pack; with nothing to hand over and nothing to pay,
        // it brings the kit for the role it was asked for, or the standard rifle, of its own.
        ItemStack weapon = weaponSlot == NO_SLOT
                ? (role == null ? GipfaeliWeapon.RIFLEMAN : role).stack()
                : commander.getInventory().getItem(weaponSlot).copyWithCount(1);

        int rations = Config.ARMY_RECRUIT_RATIONS.getAsInt();
        if (!free && countRations(commander) < rations) {
            refuse(commander, Component.translatable("combatupdate.army.no_rations", rations));
            return true;
        }

        // A new soldier wears what the squad wears, so a squad that was painted blue stays blue as
        // it grows.
        DyeColor uniform = squad.isEmpty() ? DyeColor.WHITE : squad.getFirst().uniform();
        enlist(commander, weapon, uniform);

        if (!free) {
            if (weaponSlot != NO_SLOT) {
                commander.getInventory().getItem(weaponSlot).shrink(1);
            }

            spendRations(commander, rations);
        }

        readout(commander, Component.translatable("combatupdate.army.recruited",
                weapon.getHoverName(), squad.size() + 1, cap));

        // The first soldier anyone ever signs on comes with the manual. There are enough orders,
        // roles and shapes here that a tooltip cannot hold them, and nobody reads a wiki mid-fight.
        if (squad.isEmpty() && !holdsManual(commander)) {
            commander.getInventory().placeItemBackInInventory(GipfaeliManual.book(), Prediction.SERVER_ONLY);
        }

        reform(commander);
        return true;
    }

    // Soldiers out of nothing, for whoever is running the server: the same recruits, armed with the
    // kit that was asked for and paid for with none of it. Returns how many actually fell in.
    public static int conscript(ServerPlayer commander, GipfaeliWeapon role, int count) {
        int cap = Config.ARMY_MAX_SQUAD.getAsInt();
        List<GipfaeliSoldier> squad = squad(commander);
        int signed = Math.min(count, Math.max(0, cap - squad.size()));
        if (signed == 0) {
            refuse(commander, Component.translatable("combatupdate.army.full", cap));
            return 0;
        }

        DyeColor uniform = squad.isEmpty() ? DyeColor.WHITE : squad.getFirst().uniform();
        for (int soldier = 0; soldier < signed; soldier++) {
            enlist(commander, role.stack(), uniform);
        }

        readout(commander, Component.translatable("combatupdate.army.conscripted", signed, role.roleName()));
        reform(commander);
        return signed;
    }

    // Puts one soldier on the ground beside the commander, armed and already theirs. The one place
    // a soldier is ever made, so a recruit signed on with the flag and one conscripted by command
    // are the same soldier.
    private static GipfaeliSoldier enlist(ServerPlayer commander, ItemStack weapon, DyeColor uniform) {
        ServerLevel level = commander.level();
        GipfaeliSoldier soldier = new GipfaeliSoldier(CombatUpdate.GIPFAELI_SOLDIER.get(), level);
        soldier.setPos(fallInSpot(commander));
        soldier.setYRot(commander.getYRot());
        soldier.setOwner(commander);
        soldier.setTame(true, false);
        soldier.setUniform(uniform);
        soldier.arm(weapon);
        soldier.setHealth(soldier.getMaxHealth());
        level.addFreshEntity(soldier);

        level.playSound(null, soldier.getX(), soldier.getY(), soldier.getZ(),
                SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS, 1.0F, 0.9F);
        return soldier;
    }

    // A pace to the side of the commander rather than under their feet, so recruiting a squad in
    // one go doesn't stack eight soldiers in the one block.
    private static Vec3 fallInSpot(ServerPlayer commander) {
        Vec3 look = commander.getLookAngle();
        Vec3 sideways = new Vec3(-look.z, 0.0, look.x).normalize();
        double offset = commander.getRandom().nextDouble() * 2.0 - 1.0;
        return commander.position().add(look.x, 0.0, look.z).add(sideways.scale(offset * 1.5));
    }

    // The first kit in the pack that fits, or the first kit at all when no role was asked for.
    private static int findWeaponSlot(Player player, @Nullable GipfaeliWeapon role) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            GipfaeliWeapon found = GipfaeliWeapon.of(inventory.getItem(slot));
            if (found != null && (role == null || found == role)) {
                return slot;
            }
        }

        return NO_SLOT;
    }

    private static int countRations(Player player) {
        Inventory inventory = player.getInventory();
        int rations = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(CombatUpdate.GIPFAELI.get())) {
                rations += stack.getCount();
            }
        }

        return rations;
    }

    private static void spendRations(Player player, int rations) {
        Inventory inventory = player.getInventory();
        int left = rations;
        for (int slot = 0; slot < inventory.getContainerSize() && left > 0; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(CombatUpdate.GIPFAELI.get())) {
                int taken = Math.min(left, stack.getCount());
                stack.shrink(taken);
                left -= taken;
            }
        }
    }

    private static boolean holdsManual(Player player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            WrittenBookContent content = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
            if (stack.is(Items.WRITTEN_BOOK) && content != null && GipfaeliManual.TITLE.equals(content.title().raw())) {
                return true;
            }
        }

        return false;
    }

    // --- Orders ---

    // Sends the whole squad after one thing. Everything that can be selected - a player on the
    // server, an animal in a field, whatever is under the crosshair - arrives here.
    public static void attack(ServerPlayer commander, LivingEntity target) {
        List<GipfaeliSoldier> squad = squad(commander);
        if (squad.isEmpty()) {
            refuse(commander, Component.translatable("combatupdate.army.none"));
            return;
        }

        if (sameSide(commander, target)) {
            refuse(commander, Component.translatable("combatupdate.army.own_side"));
            return;
        }

        if (target.level() != commander.level()) {
            refuse(commander, Component.translatable("combatupdate.army.other_world"));
            return;
        }

        for (GipfaeliSoldier soldier : squad) {
            soldier.holdPosition(false);
            soldier.order(target);
        }

        mark(target);
        commander.sendSystemMessage(Component.translatable("combatupdate.army.attack",
                squad.size(), target.getDisplayName()).withStyle(ChatFormatting.GOLD));
    }

    // Whatever is under the crosshair, out to the launcher's own sighting range: the same search
    // the launcher's lock runs, so pointing the army at something works the way pointing the
    // launcher at it does.
    public static void attackSighted(ServerPlayer commander) {
        LivingEntity target = GipfaeliLock.findTarget(commander.level(), commander);
        if (target == null) {
            refuse(commander, Component.translatable("combatupdate.gipfaeli.no_target"));
            return;
        }

        attack(commander, target);
    }

    // Break off and fall back in.
    public static void follow(ServerPlayer commander) {
        List<GipfaeliSoldier> squad = squad(commander);
        for (GipfaeliSoldier soldier : squad) {
            soldier.guard(null);
            soldier.station(null);
            soldier.holdPosition(false);
            soldier.order(null);
            soldier.setTarget(null);
        }

        SIEGES.remove(commander.getUUID());
        report(commander, squad.size(), "combatupdate.army.follow");
    }

    // Stand where you are. A held soldier still shoots what comes at it; it just stops walking
    // after anything, the commander included.
    public static void hold(ServerPlayer commander) {
        List<GipfaeliSoldier> squad = squad(commander);
        for (GipfaeliSoldier soldier : squad) {
            soldier.holdPosition(true);
        }

        report(commander, squad.size(), "combatupdate.army.hold");
    }

    // Stop shooting, stay where you were put.
    public static void standDown(ServerPlayer commander) {
        List<GipfaeliSoldier> squad = squad(commander);
        for (GipfaeliSoldier soldier : squad) {
            soldier.order(null);
            soldier.setTarget(null);
        }

        report(commander, squad.size(), "combatupdate.army.stood_down");
    }

    // Sends the squad home. The kit goes back to whoever paid for it rather than out of the world
    // with its carriers - dismissing an army should cost nothing but the rations it ate.
    public static void dismiss(ServerPlayer commander) {
        List<GipfaeliSoldier> squad = squad(commander);
        for (GipfaeliSoldier soldier : squad) {
            ItemStack weapon = soldier.getMainHandItem().copy();
            if (!weapon.isEmpty() && Config.ARMY_CONSUMES_SUPPLIES.get()) {
                commander.getInventory().placeItemBackInInventory(weapon, Prediction.SERVER_ONLY);
            }

            soldier.level().playSound(null, soldier.getX(), soldier.getY(), soldier.getZ(),
                    SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS, 0.7F, 1.4F);
            soldier.discard();
        }

        SIEGES.remove(commander.getUUID());
        report(commander, squad.size(), "combatupdate.army.dismissed");
    }

    // --- Taking ground off the map ---

    // Sends count soldiers to stand in a chunk: the territory screen's order. Free ground they
    // claim for the commander when they get there; somebody else's they lay siege to, and for as
    // long as one of them is standing in it the capture runs as if the commander stood there
    // themselves (see TerritoryManager, and garrisons() below). Nearest go first, so the squad
    // does not send its marksman across the map while its assault troopers stand next door.
    public static void march(ServerPlayer commander, int chunkX, int chunkZ, int count) {
        List<GipfaeliSoldier> squad = squad(commander);
        if (squad.isEmpty()) {
            refuse(commander, Component.translatable("combatupdate.army.none"));
            return;
        }

        ServerLevel level = commander.level();
        ChunkPos own = commander.chunkPosition();
        int reach = Config.TERRITORY_MAP_RADIUS.get() + 1;
        if (Math.abs(chunkX - own.x()) > reach || Math.abs(chunkZ - own.z()) > reach) {
            refuse(commander, Component.translatable("combatupdate.army.march.too_far"));
            return;
        }

        if (level.getChunkSource().getChunkNow(chunkX, chunkZ) == null) {
            refuse(commander, Component.translatable("combatupdate.army.march.not_loaded"));
            return;
        }

        BlockPos ground = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING,
                new BlockPos(chunkX * 16 + 8, 0, chunkZ * 16 + 8));
        Vec3 post = Vec3.atBottomCenterOf(ground);

        squad.sort(Comparator.comparingDouble(soldier -> soldier.position().distanceToSqr(post)));
        int sent = Math.min(Math.max(1, count), squad.size());
        for (int index = 0; index < sent; index++) {
            GipfaeliSoldier soldier = squad.get(index);
            soldier.order(null);
            // Spread over the middle of the chunk rather than all on its centre block, and never
            // past its edge: a soldier stood one block into the next chunk is not holding this one.
            double dx = (commander.getRandom().nextDouble() * 2.0 - 1.0) * STATION_SPREAD;
            double dz = (commander.getRandom().nextDouble() * 2.0 - 1.0) * STATION_SPREAD;
            soldier.station(post.add(dx, 0.0, dz));
        }

        SIEGES.put(commander.getUUID(), new Siege(TerritoryClaim.Key.of(level, chunkX, chunkZ), false, 0L));
        commander.sendSystemMessage(Component.translatable("combatupdate.army.march", sent, chunkX, chunkZ)
                .withStyle(ChatFormatting.GOLD));
    }

    // --- Guard posts ---

    // The soldiers stationed at a post - the commander's own when one is given, anyone's when not.
    public static List<GipfaeliSoldier> guardsAt(ServerLevel level, BlockPos pos, @Nullable ServerPlayer commander) {
        AABB around = new AABB(pos).inflate(Config.ARMY_MARCH_RANGE.getAsDouble());
        return level.getEntitiesOfClass(GipfaeliSoldier.class, around,
                soldier -> soldier.isAlive() && pos.equals(soldier.post())
                        && (commander == null || soldier.isOwnedBy(commander)));
    }

    // Sends count soldiers to a guard post, nearest first and never one already there. They march
    // to it the way they march on a chunk, and walk its beat once they arrive (see
    // GipfaeliSoldier.GuardGoal); the post itself says how (see GipfaeliGuardPost).
    public static void garrisonPost(ServerPlayer commander, BlockPos pos, int radius, int count) {
        List<GipfaeliSoldier> squad = squad(commander);
        squad.removeIf(soldier -> pos.equals(soldier.post()));
        if (squad.isEmpty()) {
            refuse(commander, Component.translatable("combatupdate.army.post.nobody"));
            return;
        }

        Vec3 centre = Vec3.atBottomCenterOf(pos.above());
        squad.sort(Comparator.comparingDouble(soldier -> soldier.position().distanceToSqr(centre)));
        int sent = Math.min(Math.max(1, count), squad.size());
        double spread = Math.max(1.0, radius * 0.5);
        for (int index = 0; index < sent; index++) {
            GipfaeliSoldier soldier = squad.get(index);
            soldier.order(null);
            soldier.guard(pos);
            double dx = (commander.getRandom().nextDouble() * 2.0 - 1.0) * spread;
            double dz = (commander.getRandom().nextDouble() * 2.0 - 1.0) * spread;
            soldier.station(centre.add(dx, 0.0, dz));
        }

        readout(commander, Component.translatable("combatupdate.army.post.stationed", sent));
    }

    // Takes every guard off a post and lets it fall back in with the commander.
    public static void recallPost(ServerPlayer commander, BlockPos pos) {
        List<GipfaeliSoldier> guards = guardsAt(commander.level(), pos, commander);
        for (GipfaeliSoldier guard : guards) {
            guard.guard(null);
            guard.station(null);
            guard.holdPosition(false);
            guard.order(null);
            guard.setTarget(null);
        }

        report(commander, guards.size(), "combatupdate.army.post.recalled");
    }

    // Whether any of this player's soldiers is standing in the chunk: what lets a capture run
    // without the player there. Asked every tick for every capture under way, so it is a query on
    // one chunk's worth of space rather than a sweep of the server.
    public static boolean garrisons(ServerPlayer commander, TerritoryClaim.Key key) {
        return !garrison(commander, key).isEmpty();
    }

    private static List<GipfaeliSoldier> garrison(ServerPlayer commander, TerritoryClaim.Key key) {
        MinecraftServer server = commander.level().getServer();
        ServerLevel level = server == null ? null
                : server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(key.dimension())));
        if (level == null) {
            return List.of();
        }

        AABB chunk = new AABB(key.chunkX() * 16, level.getMinY(), key.chunkZ() * 16,
                key.chunkX() * 16 + 16, level.getMaxY(), key.chunkZ() * 16 + 16);
        return level.getEntitiesOfClass(GipfaeliSoldier.class, chunk,
                soldier -> soldier.isAlive() && soldier.isOwnedBy(commander));
    }

    // Looks in on every siege once a second: claims the ground the moment the squad is standing
    // on it, starts the capture if it is somebody else's, and turns the garrison on the owner if
    // they come to argue about it.
    public static void tick(MinecraftServer server) {
        if (SIEGES.isEmpty() || server.getTickCount() % SIEGE_INTERVAL_TICKS != 0) {
            return;
        }

        for (Map.Entry<UUID, Siege> entry : SIEGES.entrySet()) {
            ServerPlayer commander = server.getPlayerList().getPlayer(entry.getKey());
            if (commander == null) {
                SIEGES.remove(entry.getKey());
                continue;
            }

            Siege siege = entry.getValue();
            TerritoryClaim.Key key = siege.key();
            List<GipfaeliSoldier> garrison = garrison(commander, key);
            if (garrison.isEmpty()) {
                // Nobody there yet, or nobody left. Told apart by whether anyone is still on the
                // way; a siege with no one marching and no one standing is over.
                boolean marching = squad(commander).stream().anyMatch(GipfaeliSoldier::marching);
                if (!marching) {
                    SIEGES.remove(entry.getKey());
                    readout(commander, Component.translatable("combatupdate.army.siege.broken", key.chunkX(), key.chunkZ()));
                }

                continue;
            }

            if (!siege.arrived()) {
                siege = new Siege(key, true, siege.nextAttempt());
                entry.setValue(siege);
                commander.sendSystemMessage(Component.translatable("combatupdate.army.siege.arrived", key.chunkX(), key.chunkZ())
                        .withStyle(ChatFormatting.GOLD));
            }

            if (!Config.on(Config.ENABLE_TERRITORY)) {
                continue;
            }

            ServerLevel level = garrison.getFirst().level() instanceof ServerLevel there ? there : null;
            if (level == null || !TerritoryClaim.Key.dimensionOf(level).equals(key.dimension())) {
                continue;
            }

            ChunkPos pos = new ChunkPos(key.chunkX(), key.chunkZ());
            TerritoryClaim claim = TerritoryManager.claimAt(level, pos);
            if (claim != null && claim.ownedBy(commander.getUUID())) {
                SIEGES.remove(entry.getKey());
                commander.sendSystemMessage(Component.translatable("combatupdate.army.siege.taken", key.chunkX(), key.chunkZ())
                        .withStyle(ChatFormatting.GREEN));
                continue;
            }

            if (claim != null) {
                // The owner is about: the garrison's business with them is no longer polite. Held
                // soldiers do not chase, so this only ever turns their guns, never walks them off
                // the chunk they were sent to hold.
                ServerPlayer owner = server.getPlayerList().getPlayer(claim.owner());
                if (owner != null && owner.level() == level
                        && owner.position().distanceTo(Vec3.atCenterOf(pos.getMiddleBlockPosition(owner.getBlockY()))) < SIEGE_DEFENDER_RANGE) {
                    for (GipfaeliSoldier soldier : garrison) {
                        if (soldier.orderedTarget() == null) {
                            soldier.order(owner);
                        }
                    }
                }
            }

            if (server.getTickCount() < siege.nextAttempt() || TerritoryManager.isCapturing(commander.getUUID())) {
                continue;
            }

            boolean begun = claim == null
                    ? TerritoryManager.claim(commander, pos)
                    : TerritoryManager.capture(commander, pos);
            if (!begun) {
                entry.setValue(new Siege(key, true, server.getTickCount() + SIEGE_RETRY_TICKS));
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        SIEGES.remove(event.getEntity().getUUID());
    }

    // Puts the squad in a shape, and tells the commander which.
    public static void form(ServerPlayer commander, GipfaeliFormation formation) {
        List<GipfaeliSoldier> squad = squad(commander);
        if (squad.isEmpty()) {
            refuse(commander, Component.translatable("combatupdate.army.none"));
            return;
        }

        for (GipfaeliSoldier soldier : squad) {
            soldier.holdPosition(false);
        }

        reform(commander, squad, formation);
        readout(commander, Component.translatable("combatupdate.army.formed", Component.translatable(formation.key())));
    }

    // Hands every soldier its number in the line again. Called whenever the squad or its shape
    // changes, because a wedge with a hole where its third soldier used to be is not a wedge.
    public static void reform(ServerPlayer commander) {
        List<GipfaeliSoldier> squad = squad(commander);
        reform(commander, squad, squad.isEmpty() ? GipfaeliFormation.LOOSE : squad.getFirst().formation());
    }

    private static void reform(ServerPlayer commander, List<GipfaeliSoldier> squad, GipfaeliFormation formation) {
        for (int slot = 0; slot < squad.size(); slot++) {
            squad.get(slot).formUp(formation, slot, squad.size());
        }
    }

    // Repaints the whole squad. What a soldier wears is only ever cosmetic, but a red army and a
    // blue army on the same server is the whole reason to have the colours.
    public static void paint(ServerPlayer commander, DyeColor color) {
        List<GipfaeliSoldier> squad = squad(commander);
        for (GipfaeliSoldier soldier : squad) {
            soldier.setUniform(color);
        }

        if (squad.isEmpty()) {
            refuse(commander, Component.translatable("combatupdate.army.none"));
            return;
        }

        commander.level().playSound(null, commander.getX(), commander.getY(), commander.getZ(),
                SoundEvents.DYE_USE, SoundSource.PLAYERS, 1.0F, 1.0F);
        readout(commander, Component.translatable("combatupdate.army.painted", squad.size(),
                Component.translatable("color.minecraft." + color.getName())));
    }

    public static void manual(ServerPlayer commander) {
        commander.getInventory().placeItemBackInInventory(GipfaeliManual.book(), Prediction.SERVER_ONLY);
        readout(commander, Component.translatable("combatupdate.army.manual_given"));
    }

    // What the squad is made of and what it is doing, as a couple of lines in chat.
    public static void status(ServerPlayer commander) {
        List<GipfaeliSoldier> squad = squad(commander);
        if (squad.isEmpty()) {
            refuse(commander, Component.translatable("combatupdate.army.none"));
            return;
        }

        commander.sendSystemMessage(strength(squad));
        commander.sendSystemMessage(doing(squad));
    }

    // --- The menu ---

    // Everything the army can be told, as one screen of buttons in chat: what to shoot, how to
    // stand, who to sign on, what to wear. Every player on the server and every animal and monster
    // within reach is a click away from being the target.
    //
    // Chat rather than a screen of its own, and deliberately so: a clickable line needs nothing on
    // the client, works over a normal connection to a vanilla-side player, and leaves the whole
    // thing sitting in the log to be clicked again a minute later.
    public static void menu(ServerPlayer commander) {
        ServerLevel level = commander.level();
        double range = Config.ARMY_MENU_RANGE.getAsDouble();
        int entries = Config.ARMY_MENU_ENTRIES.getAsInt();
        List<GipfaeliSoldier> squad = squad(commander);

        commander.sendSystemMessage(rule().append(Component.translatable("combatupdate.army.menu.title")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)).append(rule()));
        commander.sendSystemMessage(strength(squad));
        commander.sendSystemMessage(doing(squad));

        List<LivingEntity> players = new ArrayList<>();
        List<LivingEntity> animals = new ArrayList<>();
        List<LivingEntity> monsters = new ArrayList<>();

        MinecraftServer server = level.getServer();
        if (server != null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player != commander && player.isAlive() && !player.isSpectator()) {
                    players.add(player);
                }
            }
        }

        for (LivingEntity candidate : level.getEntitiesOfClass(LivingEntity.class,
                commander.getBoundingBox().inflate(range),
                entity -> entity != commander && entity.isAlive() && entity.isPickable()
                        && !(entity instanceof Player)
                        && !sameSide(commander, entity))) {
            if (candidate instanceof Enemy) {
                monsters.add(candidate);
            } else {
                animals.add(candidate);
            }
        }

        commander.sendSystemMessage(heading("combatupdate.army.menu.targets"));
        commander.sendSystemMessage(group(commander, "combatupdate.army.menu.players", players, entries));
        commander.sendSystemMessage(group(commander, "combatupdate.army.menu.animals", animals, entries));
        commander.sendSystemMessage(group(commander, "combatupdate.army.menu.monsters", monsters, entries));

        commander.sendSystemMessage(heading("combatupdate.army.menu.orders")
                .append(button(Component.translatable("combatupdate.army.order.sighted"), "attacksighted",
                        Component.translatable("combatupdate.army.order.sighted.hover"), ChatFormatting.RED))
                .append(button(Component.translatable("combatupdate.army.order.follow"), "follow", null, ChatFormatting.GREEN))
                .append(button(Component.translatable("combatupdate.army.order.hold"), "hold", null, ChatFormatting.GREEN))
                .append(button(Component.translatable("combatupdate.army.order.stand_down"), "standdown", null, ChatFormatting.GREEN))
                .append(button(Component.translatable("combatupdate.army.order.dismiss"), "dismiss",
                        Component.translatable("combatupdate.army.order.dismiss.hover"), ChatFormatting.DARK_RED)));

        GipfaeliFormation current = squad.isEmpty() ? GipfaeliFormation.LOOSE : squad.getFirst().formation();
        MutableComponent formations = heading("combatupdate.army.menu.formation");
        for (GipfaeliFormation formation : GipfaeliFormation.values()) {
            formations.append(button(Component.translatable(formation.key()), "formation " + formation.token(),
                    Component.translatable(formation.key() + ".hover"),
                    formation == current ? ChatFormatting.WHITE : ChatFormatting.AQUA));
        }
        commander.sendSystemMessage(formations);

        MutableComponent recruits = heading("combatupdate.army.menu.recruit");
        for (GipfaeliWeapon role : GipfaeliWeapon.values()) {
            recruits.append(button(role.roleName(), "enlist " + role.token(),
                    Component.translatable("combatupdate.army.menu.recruit.hover", role.stack().getHoverName())
                            .append(Component.literal("\n"))
                            .append(Component.translatable(role.key() + ".hover")),
                    ChatFormatting.YELLOW));
        }
        commander.sendSystemMessage(recruits);

        MutableComponent colours = heading("combatupdate.army.menu.colour");
        for (DyeColor color : DyeColor.values()) {
            colours.append(Component.literal("■")
                    .withStyle(style -> style
                            .withColor(color.getTextColor())
                            .withClickEvent(new ClickEvent.RunCommand(COMMAND + "colour " + color.getName()))
                            .withHoverEvent(new HoverEvent.ShowText(Component.translatable("color.minecraft." + color.getName())))));
        }
        commander.sendSystemMessage(colours);

        commander.sendSystemMessage(Component.literal("          ")
                .append(button(Component.translatable("combatupdate.army.order.manual"), "manual", null, ChatFormatting.LIGHT_PURPLE))
                .append(button(Component.translatable("combatupdate.army.order.refresh"), "targets", null, ChatFormatting.GRAY)));
    }

    // How many, of what, out of how many allowed.
    private static Component strength(List<GipfaeliSoldier> squad) {
        MutableComponent line = Component.translatable("combatupdate.army.menu.strength",
                squad.size(), Config.ARMY_MAX_SQUAD.getAsInt()).withStyle(ChatFormatting.GRAY);

        Map<GipfaeliWeapon, Integer> roles = new EnumMap<>(GipfaeliWeapon.class);
        for (GipfaeliSoldier soldier : squad) {
            GipfaeliWeapon role = soldier.weapon();
            if (role != null) {
                roles.merge(role, 1, Integer::sum);
            }
        }

        for (Map.Entry<GipfaeliWeapon, Integer> role : roles.entrySet()) {
            line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(role.getValue() + " ").withStyle(ChatFormatting.WHITE))
                    .append(role.getKey().roleName().copy().withStyle(ChatFormatting.GRAY));
        }

        return line;
    }

    // Shape, stance, and what it has been sent after.
    private static Component doing(List<GipfaeliSoldier> squad) {
        int holding = 0;
        int engaged = 0;
        LivingEntity ordered = null;
        for (GipfaeliSoldier soldier : squad) {
            if (soldier.holdingPosition()) {
                holding++;
            }

            if (soldier.getTarget() != null) {
                engaged++;
            }

            if (ordered == null) {
                ordered = soldier.orderedTarget();
            }
        }

        GipfaeliFormation formation = squad.isEmpty() ? GipfaeliFormation.LOOSE : squad.getFirst().formation();
        return Component.translatable("combatupdate.army.menu.doing",
                Component.translatable(formation.key()), engaged, holding,
                ordered == null ? Component.translatable("combatupdate.army.menu.no_order") : ordered.getDisplayName())
                .withStyle(ChatFormatting.GRAY);
    }

    // One row of the target list: a heading, then a button per candidate, nearest first.
    private static Component group(ServerPlayer commander, String heading, List<LivingEntity> candidates, int entries) {
        MutableComponent row = Component.literal("  ").append(Component.translatable(heading).withStyle(ChatFormatting.YELLOW));
        if (candidates.isEmpty()) {
            return row.append(Component.translatable("combatupdate.army.menu.none").withStyle(ChatFormatting.DARK_GRAY));
        }

        candidates.sort(Comparator.comparingDouble(candidate -> reach(commander, candidate)));
        int shown = Math.min(entries, candidates.size());
        for (int index = 0; index < shown; index++) {
            row.append(target(commander, candidates.get(index)));
        }

        if (candidates.size() > shown) {
            row.append(Component.literal(" "))
                    .append(Component.translatable("combatupdate.army.menu.more", candidates.size() - shown)
                            .withStyle(ChatFormatting.DARK_GRAY));
        }

        return row;
    }

    // How far off something is, or a long way off indeed when it is in another world - which is
    // what sorts players in the Nether down to the bottom of the list rather than into the middle
    // of it on a distance that means nothing across dimensions.
    private static double reach(ServerPlayer commander, LivingEntity candidate) {
        return candidate.level() == commander.level()
                ? candidate.distanceTo(commander)
                : Double.MAX_VALUE;
    }

    // A target, as something to click. The command behind it names the target by UUID, so clicking
    // it a minute later still picks out the same animal rather than whatever has since wandered
    // into that spot.
    private static Component target(ServerPlayer commander, LivingEntity candidate) {
        boolean elsewhere = candidate.level() != commander.level();
        MutableComponent label = Component.empty()
                .append(candidate.getDisplayName())
                .append(Component.literal(elsewhere
                        ? " *"
                        : " " + (int) candidate.distanceTo(commander) + "m"));

        MutableComponent hover = Component.translatable("combatupdate.army.menu.hover",
                candidate.getType().getDescription(),
                (int) Math.ceil(candidate.getHealth()),
                (int) Math.ceil(candidate.getMaxHealth()),
                candidate.level().dimension().identifier().toString());

        return button(label, "attack " + candidate.getUUID(), hover,
                elsewhere ? ChatFormatting.DARK_GRAY : ChatFormatting.AQUA);
    }

    // A word in brackets that runs one of the army's commands when clicked.
    private static Component button(Component label, String command, @Nullable Component hover, ChatFormatting color) {
        return Component.literal(" [").append(label).append(Component.literal("]"))
                .withStyle(style -> {
                    var styled = style.withColor(color).withClickEvent(new ClickEvent.RunCommand(COMMAND + command));
                    return hover == null ? styled : styled.withHoverEvent(new HoverEvent.ShowText(hover));
                });
    }

    private static MutableComponent heading(String key) {
        return Component.translatable(key).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }

    private static MutableComponent rule() {
        return Component.literal(" ═══════ ").withStyle(ChatFormatting.DARK_GRAY);
    }

    // --- Telling the commander about it ---

    // Orders land on the action bar: they replace each other, and nobody wants a scrollback of
    // every time they told the squad to fall in. The menu and its answers go to chat, where they
    // can be clicked and read back.
    public static void readout(Player player, Component message) {
        if (player instanceof ServerPlayer serverPlayer) {
            serverPlayer.sendSystemMessage(message, true);
        }
    }

    private static void refuse(ServerPlayer commander, Component message) {
        commander.level().playSound(null, commander.getX(), commander.getY(), commander.getZ(),
                SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.8F, 1.0F);
        readout(commander, message);
    }

    private static void report(ServerPlayer commander, int strength, String message) {
        if (strength == 0) {
            refuse(commander, Component.translatable("combatupdate.army.none"));
            return;
        }

        readout(commander, Component.translatable(message, strength));
    }

    private static void mark(LivingEntity target) {
        target.addEffect(new MobEffectInstance(MobEffects.GLOWING, MARK_DURATION_TICKS, 0, false, false, false));
    }
}
