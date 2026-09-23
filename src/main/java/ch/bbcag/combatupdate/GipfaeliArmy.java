package ch.bbcag.combatupdate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import ch.bbcag.combatupdate.entity.GipfaeliSoldier;
import ch.bbcag.combatupdate.entity.GipfaeliSoldier.Stance;
import ch.bbcag.combatupdate.territory.TerritoryClaim;
import ch.bbcag.combatupdate.territory.TerritoryManager;

// The army: who is in it, how it is divided, what it has been told, and the menus it is told
// things through.
//
// An army is squads, and a squad is a colour: every soldier of one colour under one player, forty
// of them at most, plus the one of them that has been made their commander - who wears black with
// the squad's stripe, stands out in front on parade, and can be clicked for the whole squad's
// orders. Soldiers still in camouflage are the reserve, a squad of their own with no colour yet.
// That is the whole organisation: no squad ids, no rosters, nothing to go stale. Painting a
// soldier blue IS moving it to the blue squad.
//
// Every order takes a Scope - the whole army, one squad, or the reserve - so the same code answers
// the flag's menu, a commander's menu and the commands behind both. Three menus, all in chat:
// the army's (menu()), a squad's (squadMenu()), and one soldier's own (soldierMenu()).
//
// Server-side throughout. Orders decide what a mob attacks, which is the server's to settle; what
// the player sees of it goes out as chat and action-bar messages rather than through any state
// the client keeps of its own.
public final class GipfaeliArmy {
    // Who an order is for: everyone, one squad by its colour, or the reserve still in camouflage.
    public record Scope(boolean all, @Nullable DyeColor colour) {
        public static final Scope ALL = new Scope(true, null);
        public static final Scope RESERVE = new Scope(false, null);

        public static Scope of(@Nullable DyeColor colour) {
            return new Scope(false, colour);
        }

        // The word the commands use: "all", "camo", or a dye's name.
        public static @Nullable Scope parse(String token) {
            if (token.equalsIgnoreCase("all")) {
                return ALL;
            }

            if (token.equalsIgnoreCase("camo")) {
                return RESERVE;
            }

            DyeColor colour = DyeColor.byName(token, null);
            return colour == null ? null : of(colour);
        }

        public boolean includes(GipfaeliSoldier soldier) {
            return this.all || Objects.equals(this.colour, soldier.uniform());
        }

        public String token() {
            return this.all ? "all" : this.colour == null ? "camo" : this.colour.getName();
        }

        public Component name() {
            return this.all ? Component.translatable("combatupdate.army.scope.all") : colourName(this.colour);
        }
    }

    // How long the mark on a target stays lit after the squad is sent at it. The same glow the
    // launcher's sight uses, for the same reason: it says what the order landed on, through walls,
    // without any rendering of our own.
    private static final int MARK_DURATION_TICKS = 200;

    private static final int NO_SLOT = -1;

    private static final String COMMAND = "/gipfaeliarmy ";

    // A chunk the squad has been sent to take off the map, per player. In memory only: a restart
    // drops it, and the soldiers still standing there are the army's memory of it - send them
    // again and the siege picks up where it was.
    private record Siege(TerritoryClaim.Key key, boolean arrived, long nextAttempt) {
    }

    private static final Map<UUID, Siege> SIEGES = new ConcurrentHashMap<>();

    // How often a siege is looked at, and how long after a capture is refused before it is tried
    // again - the owner may have been offline, or the player at their claim limit, and neither
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

    // --- Who is in the army ---

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

    // The part of the army an order is for.
    public static List<GipfaeliSoldier> squad(ServerPlayer commander, Scope scope) {
        List<GipfaeliSoldier> squad = squad(commander);
        squad.removeIf(soldier -> !scope.includes(soldier));
        return squad;
    }

    // One soldier by UUID, if it is this player's and somewhere loaded: what the soldier menu's
    // buttons name.
    public static @Nullable GipfaeliSoldier soldierOf(ServerPlayer commander, UUID id) {
        MinecraftServer server = commander.level().getServer();
        if (server == null) {
            return null;
        }

        for (ServerLevel level : server.getAllLevels()) {
            if (level.getEntity(id) instanceof GipfaeliSoldier soldier && soldier.isAlive() && soldier.isOwnedBy(commander)) {
                return soldier;
            }
        }

        return null;
    }

    // The squad's commander, if it has one.
    private static @Nullable GipfaeliSoldier leaderOf(List<GipfaeliSoldier> squad) {
        for (GipfaeliSoldier soldier : squad) {
            if (soldier.commander()) {
                return soldier;
            }
        }

        return null;
    }

    // How many in the ranks: the squad without its commander, which is what the squad size caps.
    private static int ranks(List<GipfaeliSoldier> squad) {
        int ranks = 0;
        for (GipfaeliSoldier soldier : squad) {
            if (!soldier.commander()) {
                ranks++;
            }
        }

        return ranks;
    }

    // Whether two things fight under the same flag: a player and their own soldiers, or two
    // soldiers of the one player. This is what keeps a volley off the squad's own backs, and it
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

    // Signs one soldier on into a squad - the reserve, unless a colour is asked for. With no role
    // it comes unarmed, for its rations alone, and is handed everything else afterwards through
    // its own menu; with a role it is handed that role's kit out of the player's pack on the way
    // in. Returns whether anything was done at all, which for the flag is the cue to swallow the
    // click either way - being told the army is full is as much of an answer as a new soldier is.
    public static boolean recruit(ServerPlayer commander, @Nullable GipfaeliWeapon role, Scope scope) {
        int cap = Config.ARMY_MAX_SQUAD.getAsInt();
        List<GipfaeliSoldier> army = squad(commander);
        if (army.size() >= cap) {
            refuse(commander, Component.translatable("combatupdate.army.full", cap));
            return true;
        }

        DyeColor colour = scope.all ? null : scope.colour;
        if (!squadHasRoom(commander, colour)) {
            return true;
        }

        // Creative pays for nothing, and neither does anyone who has turned the supplies off - the
        // same bargain the launcher and the rig offer.
        boolean free = commander.getAbilities().instabuild || !Config.ARMY_CONSUMES_SUPPLIES.get();
        int weaponSlot = role == null ? NO_SLOT : findWeaponSlot(commander, role);
        if (role != null && weaponSlot == NO_SLOT && !free) {
            refuse(commander, Component.translatable("combatupdate.army.no_kit", role.stack().getHoverName()));
            return true;
        }

        ItemStack weapon = role == null ? ItemStack.EMPTY
                : weaponSlot == NO_SLOT ? role.stack()
                : commander.getInventory().getItem(weaponSlot).copyWithCount(1);

        int rations = Config.ARMY_RECRUIT_RATIONS.getAsInt();
        if (!free && countRations(commander) < rations) {
            refuse(commander, Component.translatable("combatupdate.army.no_rations", rations));
            return true;
        }

        GipfaeliSoldier soldier = enlist(commander, weapon, colour);

        if (!free) {
            if (weaponSlot != NO_SLOT) {
                commander.getInventory().getItem(weaponSlot).shrink(1);
            }

            spendRations(commander, rations);
        }

        readout(commander, Component.translatable("combatupdate.army.recruited",
                soldier.getName(), army.size() + 1, cap));

        // The first soldier anyone ever signs on comes with the manual. There are enough orders,
        // roles and shapes here that a tooltip cannot hold them, and nobody reads a wiki mid-fight.
        if (army.isEmpty() && !holdsManual(commander)) {
            commander.getInventory().placeItemBackInInventory(GipfaeliManual.book(), Prediction.SERVER_ONLY);
        }

        reform(commander);
        return true;
    }

    // The colour the next squad wears: the first in this order that has no commander yet. Red and
    // blue first, because "squad red and squad blue" is what an army is for.
    private static final DyeColor[] SQUAD_COLOURS = {
        DyeColor.RED, DyeColor.BLUE, DyeColor.GREEN, DyeColor.YELLOW, DyeColor.ORANGE, DyeColor.PURPLE,
        DyeColor.CYAN, DyeColor.LIGHT_BLUE, DyeColor.LIME, DyeColor.PINK, DyeColor.MAGENTA, DyeColor.WHITE,
        DyeColor.LIGHT_GRAY, DyeColor.GRAY, DyeColor.BROWN, DyeColor.BLACK,
    };

    // How far around its commander a squad's new soldiers are set down.
    private static final double MUSTER_SPREAD = 2.5;

    // Raises a new squad: the flag's own action. What appears is the squad's commander, in black
    // with the first colour nobody has taken, and the squad is filled by clicking it. Costs the
    // same rations a soldier does.
    public static boolean raiseSquad(ServerPlayer commander) {
        int cap = Config.ARMY_MAX_SQUAD.getAsInt();
        List<GipfaeliSoldier> army = squad(commander);
        if (army.size() >= cap) {
            refuse(commander, Component.translatable("combatupdate.army.full", cap));
            return true;
        }

        DyeColor colour = null;
        for (DyeColor candidate : SQUAD_COLOURS) {
            if (leaderOf(squad(commander, Scope.of(candidate))) == null) {
                colour = candidate;
                break;
            }
        }

        if (colour == null) {
            refuse(commander, Component.translatable("combatupdate.army.squads_full"));
            return true;
        }

        boolean free = commander.getAbilities().instabuild || !Config.ARMY_CONSUMES_SUPPLIES.get();
        int rations = Config.ARMY_RECRUIT_RATIONS.getAsInt();
        if (!free && countRations(commander) < rations) {
            refuse(commander, Component.translatable("combatupdate.army.no_rations", rations));
            return true;
        }

        GipfaeliSoldier leader = enlist(commander, ItemStack.EMPTY, colour);
        leader.setCommander(true);
        leader.setHealth(leader.getMaxHealth());
        if (!free) {
            spendRations(commander, rations);
        }

        if (army.isEmpty() && !holdsManual(commander)) {
            commander.getInventory().placeItemBackInInventory(GipfaeliManual.book(), Prediction.SERVER_ONLY);
        }

        commander.sendSystemMessage(Component.translatable("combatupdate.army.squad.raised", colourName(colour))
                .withStyle(ChatFormatting.GOLD));
        reform(commander);
        return true;
    }

    // Fills a squad: count unarmed soldiers in its colour, set down around its commander - or
    // around the player, for a squad with none. Stops at the squad's size, at the army's limit,
    // and at what the player can pay for, and says how many actually came.
    public static void fill(ServerPlayer commander, Scope scope, int count) {
        DyeColor colour = scope.all ? null : scope.colour;
        List<GipfaeliSoldier> squad = squad(commander, Scope.of(colour));
        int room = Math.min(Config.ARMY_SQUAD_SIZE.getAsInt() - ranks(squad),
                Config.ARMY_MAX_SQUAD.getAsInt() - squad(commander).size());
        if (room <= 0) {
            refuse(commander, Component.translatable("combatupdate.army.squad.full", colourName(colour), Config.ARMY_SQUAD_SIZE.getAsInt()));
            return;
        }

        boolean free = commander.getAbilities().instabuild || !Config.ARMY_CONSUMES_SUPPLIES.get();
        int rations = Config.ARMY_RECRUIT_RATIONS.getAsInt();
        int affordable = free || rations == 0 ? Integer.MAX_VALUE : countRations(commander) / rations;
        int mustered = Math.min(Math.min(Math.max(1, count), room), affordable);
        if (mustered <= 0) {
            refuse(commander, Component.translatable("combatupdate.army.no_rations", rations));
            return;
        }

        GipfaeliSoldier leader = leaderOf(squad);
        Entity around = leader == null ? commander : leader;
        ServerLevel level = commander.level();
        for (int index = 0; index < mustered; index++) {
            GipfaeliSoldier soldier = new GipfaeliSoldier(CombatUpdate.GIPFAELI_SOLDIER.get(), level);
            double dx = (commander.getRandom().nextDouble() * 2.0 - 1.0) * MUSTER_SPREAD;
            double dz = (commander.getRandom().nextDouble() * 2.0 - 1.0) * MUSTER_SPREAD;
            soldier.setPos(around.position().add(dx, 0.0, dz));
            soldier.setYRot(around.getYRot());
            soldier.setOwner(commander);
            soldier.setTame(true, false);
            soldier.setUniform(colour);
            soldier.arm(ItemStack.EMPTY);
            soldier.setHealth(soldier.getMaxHealth());
            level.addFreshEntity(soldier);
        }

        if (!free) {
            spendRations(commander, rations * mustered);
        }

        level.playSound(null, around.getX(), around.getY(), around.getZ(),
                SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS, 1.0F, 0.9F);
        readout(commander, Component.translatable("combatupdate.army.squad.filled", mustered, colourName(colour),
                ranks(squad) + mustered, Config.ARMY_SQUAD_SIZE.getAsInt()));
        reform(commander);
    }

    // Whether a squad can take one more, and a word to the player if it cannot.
    private static boolean squadHasRoom(ServerPlayer commander, @Nullable DyeColor colour) {
        int size = Config.ARMY_SQUAD_SIZE.getAsInt();
        if (ranks(squad(commander, Scope.of(colour))) >= size) {
            refuse(commander, Component.translatable("combatupdate.army.squad.full", colourName(colour), size));
            return false;
        }

        return true;
    }

    // Soldiers out of nothing, for whoever is running the server: the same recruits, armed with the
    // kit that was asked for and paid for with none of it. Returns how many actually fell in.
    public static int conscript(ServerPlayer commander, GipfaeliWeapon role, int count) {
        int cap = Config.ARMY_MAX_SQUAD.getAsInt();
        List<GipfaeliSoldier> army = squad(commander);
        int signed = Math.min(count, Math.max(0, cap - army.size()));
        if (signed == 0) {
            refuse(commander, Component.translatable("combatupdate.army.full", cap));
            return 0;
        }

        for (int soldier = 0; soldier < signed; soldier++) {
            enlist(commander, role.stack(), null);
        }

        readout(commander, Component.translatable("combatupdate.army.conscripted", signed, role.roleName()));
        reform(commander);
        return signed;
    }

    // A parade ground out of nothing, for whoever runs the server: count soldiers of one role, set
    // down already in their ranks (see GipfaeliParade) out in front of the player or at their
    // back, standing at attention where they were put. Not capped by the army limit, because the
    // number asked for is the whole point of the order.
    public static int parade(ServerPlayer commander, GipfaeliWeapon role, int count, boolean ahead) {
        ServerLevel level = commander.level();
        Vec3 anchor = commander.position();
        float yaw = commander.getYRot();
        // Out in front, the ranks face the player they were paraded for; at their back, they
        // face the way the player faces.
        float facing = ahead ? yaw + 180.0F : yaw;

        for (int index = 0; index < count; index++) {
            Vec3 spot = GipfaeliParade.onGround(level, commander, GipfaeliParade.slot(index, count, anchor, yaw, ahead));
            GipfaeliSoldier soldier = new GipfaeliSoldier(CombatUpdate.GIPFAELI_SOLDIER.get(), level);
            soldier.setPos(spot);
            soldier.setYRot(facing);
            soldier.setYBodyRot(facing);
            soldier.setYHeadRot(facing);
            soldier.setOwner(commander);
            soldier.setTame(true, false);
            soldier.setUniform(null);
            soldier.arm(role.stack());
            soldier.setHealth(soldier.getMaxHealth());
            soldier.setStance(Stance.STAND);
            soldier.standAt(anchor, facing);
            soldier.holdPosition(true);
            level.addFreshEntity(soldier);
        }

        level.playSound(null, commander.getX(), commander.getY(), commander.getZ(),
                SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS, 1.0F, 0.9F);
        readout(commander, Component.translatable("combatupdate.army.paraded", count, role.roleName(),
                Component.translatable(ahead ? "combatupdate.army.parade.ahead" : "combatupdate.army.parade.behind")));
        return count;
    }

    // Puts one soldier on the ground beside the player, already theirs and in the colour of the
    // squad it joins, carrying whatever it was given - which may be nothing. The one place a
    // soldier is ever made, so a recruit signed on with the flag and one conscripted by command
    // are the same soldier.
    private static GipfaeliSoldier enlist(ServerPlayer commander, ItemStack weapon, @Nullable DyeColor uniform) {
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

    // A pace to the side of the player rather than under their feet, so recruiting a squad in one
    // go doesn't stack eight soldiers in the one block.
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

    private static int findItemSlot(Player player, Item item) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (inventory.getItem(slot).is(item)) {
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

    // --- One soldier's kit, armour, colour and rank ---

    // Hands a soldier the kit for a role, out of the player's pack unless nothing costs anything,
    // and takes back whatever it was carrying.
    public static void armSoldier(ServerPlayer commander, GipfaeliSoldier soldier, GipfaeliWeapon role) {
        boolean free = commander.getAbilities().instabuild || !Config.ARMY_CONSUMES_SUPPLIES.get();
        int slot = findWeaponSlot(commander, role);
        if (slot == NO_SLOT && !free) {
            refuse(commander, Component.translatable("combatupdate.army.no_kit", role.stack().getHoverName()));
            return;
        }

        ItemStack kit = slot == NO_SLOT ? role.stack() : commander.getInventory().getItem(slot).copyWithCount(1);
        ItemStack previous = soldier.getMainHandItem().copy();
        soldier.arm(kit);
        if (slot != NO_SLOT && !free) {
            commander.getInventory().getItem(slot).shrink(1);
        }

        handBack(commander, previous);
        soldier.playSound(SoundEvents.ARMOR_EQUIP_IRON.value(), 1.0F, 1.0F);
        readout(commander, Component.translatable("combatupdate.army.rearmed", kit.getHoverName()));
    }

    public static void disarmSoldier(ServerPlayer commander, GipfaeliSoldier soldier) {
        ItemStack previous = soldier.getMainHandItem().copy();
        soldier.arm(ItemStack.EMPTY);
        handBack(commander, previous);
        readout(commander, Component.translatable("combatupdate.army.soldier.disarmed"));
    }

    // Dresses a soldier in a suit of armour: every piece of it the player has, or the whole suit
    // when nothing costs anything. Whatever comes off goes back in the pack.
    public static void dressSoldier(ServerPlayer commander, GipfaeliSoldier soldier, GipfaeliArmour armour) {
        boolean free = commander.getAbilities().instabuild || !Config.ARMY_CONSUMES_SUPPLIES.get();
        int dressed = 0;
        for (int index = 0; index < GipfaeliArmour.SLOTS.length; index++) {
            Item item = armour.piece(index);
            int slot = findItemSlot(commander, item);
            if (slot == NO_SLOT && !free) {
                continue;
            }

            ItemStack piece = slot == NO_SLOT ? new ItemStack(item) : commander.getInventory().getItem(slot).copyWithCount(1);
            ItemStack previous = soldier.equip(GipfaeliArmour.SLOTS[index], piece);
            if (slot != NO_SLOT && !free) {
                commander.getInventory().getItem(slot).shrink(1);
            }

            handBack(commander, previous);
            dressed++;
        }

        if (dressed == 0) {
            refuse(commander, Component.translatable("combatupdate.army.soldier.no_armour", armour.displayName()));
            return;
        }

        soldier.playSound(SoundEvents.ARMOR_EQUIP_IRON.value(), 1.0F, 1.0F);
        readout(commander, Component.translatable("combatupdate.army.soldier.dressed", dressed, armour.displayName()));
    }

    public static void stripSoldier(ServerPlayer commander, GipfaeliSoldier soldier) {
        for (EquipmentSlot slot : GipfaeliArmour.SLOTS) {
            handBack(commander, soldier.equip(slot, ItemStack.EMPTY));
        }

        readout(commander, Component.translatable("combatupdate.army.soldier.stripped"));
    }

    // Moves one soldier to another squad, which is what a colour is. A commander who moves into a
    // squad that already has one goes back into the ranks: a squad has one commander.
    public static void paintSoldier(ServerPlayer commander, GipfaeliSoldier soldier, @Nullable DyeColor colour) {
        if (Objects.equals(soldier.uniform(), colour)) {
            return;
        }

        if (!soldier.commander() && !squadHasRoom(commander, colour)) {
            return;
        }

        if (soldier.commander() && leaderOf(squad(commander, Scope.of(colour))) != null) {
            soldier.setCommander(false);
        }

        soldier.setUniform(colour);
        soldier.playSound(SoundEvents.DYE_USE, 1.0F, 1.0F);
        reformParade(commander);
        readout(commander, Component.translatable("combatupdate.army.painted", 1, colourName(colour)));
    }

    // Makes a soldier its squad's commander, standing down whoever held that before.
    public static void promote(ServerPlayer commander, GipfaeliSoldier soldier) {
        GipfaeliSoldier previous = leaderOf(squad(commander, Scope.of(soldier.uniform())));
        if (previous != null && previous != soldier) {
            previous.setCommander(false);
        }

        soldier.setCommander(true);
        soldier.playSound(SoundEvents.ARMOR_EQUIP_IRON.value(), 1.0F, 0.8F);
        reformParade(commander);
        readout(commander, Component.translatable("combatupdate.army.soldier.promoted", soldier.getName(), colourName(soldier.uniform())));
    }

    public static void demote(ServerPlayer commander, GipfaeliSoldier soldier) {
        soldier.setCommander(false);
        reformParade(commander);
        readout(commander, Component.translatable("combatupdate.army.soldier.demoted"));
    }

    // Sends one soldier to the player's post number n: the soldier menu's "Post 1", "Post 2".
    public static void sendToPost(ServerPlayer commander, GipfaeliSoldier soldier, int number) {
        MinecraftServer server = commander.level().getServer();
        List<GipfaeliArmyData.PostRef> posts = server == null ? List.of() : GipfaeliArmyData.get(server).posts(server, commander.getUUID());
        if (number < 1 || number > posts.size()) {
            refuse(commander, Component.translatable("combatupdate.army.post.no_such", number));
            return;
        }

        GipfaeliArmyData.PostRef ref = posts.get(number - 1);
        if (!ref.dimension().equals(TerritoryClaim.Key.dimensionOf(soldier.level()))) {
            refuse(commander, Component.translatable("combatupdate.army.other_world"));
            return;
        }

        int radius = soldier.level().getBlockEntity(ref.pos()) instanceof GipfaeliGuardPost.Post post
                ? post.radius() : GipfaeliGuardPost.RADII[1];
        postSoldier(commander, soldier, ref.pos(), radius);
        readout(commander, Component.translatable("combatupdate.army.post.sent", soldier.getName(), number));
    }

    // Points one soldier at a post: it marches there and walks its beat on arrival.
    private static void postSoldier(ServerPlayer commander, GipfaeliSoldier soldier, BlockPos pos, int radius) {
        Vec3 centre = Vec3.atBottomCenterOf(pos.above());
        double spread = Math.max(1.0, radius * 0.5);
        soldier.setStance(Stance.ATTACK);
        soldier.standAt(null, 0.0F);
        soldier.order(null);
        soldier.guard(pos);
        double dx = (commander.getRandom().nextDouble() * 2.0 - 1.0) * spread;
        double dz = (commander.getRandom().nextDouble() * 2.0 - 1.0) * spread;
        soldier.station(centre.add(dx, 0.0, dz));
    }

    // Something taken off a soldier goes back to whoever paid for it - and only then. With the
    // supplies switched off nothing was paid, and handing it back would be minting it.
    private static void handBack(ServerPlayer commander, ItemStack stack) {
        if (!stack.isEmpty() && Config.ARMY_CONSUMES_SUPPLIES.get()) {
            commander.getInventory().placeItemBackInInventory(stack, Prediction.SERVER_ONLY);
        }
    }

    // --- Orders ---

    // Sends everyone in scope after one thing. Everything that can be selected - a player on the
    // server, an animal in a field, whatever is under the crosshair - arrives here. An attack is
    // the end of any parade: the squad goes back into the field to carry it out.
    public static void attack(ServerPlayer commander, Scope scope, LivingEntity target) {
        List<GipfaeliSoldier> squad = squad(commander, scope);
        if (squad.isEmpty()) {
            refuse(commander, nobody(scope));
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

        field(commander, squad, GipfaeliFormation.LOOSE, false);
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
    public static void attackSighted(ServerPlayer commander, Scope scope) {
        LivingEntity target = GipfaeliLock.findTarget(commander.level(), commander);
        if (target == null) {
            refuse(commander, Component.translatable("combatupdate.gipfaeli.no_target"));
            return;
        }

        attack(commander, scope, target);
    }

    // Attack or stand. ATTACK puts the squad back in the field, loose. STAND draws it up in ranks -
    // every squad in scope a block of its own, its commander out in front - around the spot the
    // player is standing on, facing the way they face, or around the player wherever they go when
    // told to follow; and takes its guns off everything that has not shot first. Guards at posts
    // are left where they are: a post is its own order.
    public static void stance(ServerPlayer commander, Scope scope, Stance stance, boolean follow) {
        List<GipfaeliSoldier> squad = squad(commander, scope);
        if (squad.isEmpty()) {
            refuse(commander, nobody(scope));
            return;
        }

        if (stance == Stance.STAND) {
            Vec3 anchor = follow ? null : commander.position();
            for (GipfaeliSoldier soldier : squad) {
                if (soldier.post() != null) {
                    continue;
                }

                soldier.setStance(Stance.STAND);
                soldier.order(null);
                soldier.setTarget(null);
                soldier.station(null);
                soldier.holdPosition(false);
                soldier.standAt(anchor, commander.getYRot());
            }

            if (scope.all) {
                SIEGES.remove(commander.getUUID());
            }

            reformParade(commander);
            readout(commander, Component.translatable(follow ? "combatupdate.army.stood_follow" : "combatupdate.army.stood", squad.size()));
            return;
        }

        field(commander, squad, GipfaeliFormation.LOOSE, true);
        readout(commander, Component.translatable("combatupdate.army.stance_attack", squad.size()));
    }

    // Break off and fall back in.
    public static void follow(ServerPlayer commander, Scope scope) {
        List<GipfaeliSoldier> squad = squad(commander, scope);
        for (GipfaeliSoldier soldier : squad) {
            soldier.guard(null);
            soldier.station(null);
            soldier.standAt(null, 0.0F);
            soldier.holdPosition(false);
            soldier.order(null);
            soldier.setTarget(null);
        }

        if (scope.all) {
            SIEGES.remove(commander.getUUID());
        }

        report(commander, scope, squad.size(), "combatupdate.army.follow");
    }

    // Stand where you are. A held soldier still shoots what comes at it; it just stops walking
    // after anything, the player included.
    public static void hold(ServerPlayer commander, Scope scope) {
        List<GipfaeliSoldier> squad = squad(commander, scope);
        for (GipfaeliSoldier soldier : squad) {
            soldier.holdPosition(true);
        }

        report(commander, scope, squad.size(), "combatupdate.army.hold");
    }

    // Stop shooting, stay where you were put.
    public static void standDown(ServerPlayer commander, Scope scope) {
        List<GipfaeliSoldier> squad = squad(commander, scope);
        for (GipfaeliSoldier soldier : squad) {
            soldier.order(null);
            soldier.setTarget(null);
        }

        report(commander, scope, squad.size(), "combatupdate.army.stood_down");
    }

    // Sends everyone in scope home. Kit and armour go back to whoever paid for them rather than
    // out of the world with their carriers - dismissing an army should cost nothing but the
    // rations it ate.
    public static void dismiss(ServerPlayer commander, Scope scope) {
        List<GipfaeliSoldier> squad = squad(commander, scope);
        for (GipfaeliSoldier soldier : squad) {
            handBack(commander, soldier.getMainHandItem().copy());
            for (EquipmentSlot slot : GipfaeliArmour.SLOTS) {
                handBack(commander, soldier.getItemBySlot(slot).copy());
            }

            soldier.level().playSound(null, soldier.getX(), soldier.getY(), soldier.getZ(),
                    SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS, 0.7F, 1.4F);
            soldier.discard();
        }

        if (scope.all) {
            SIEGES.remove(commander.getUUID());
        }

        report(commander, scope, squad.size(), "combatupdate.army.dismissed");
        reformParade(commander);
    }

    // Puts everyone in scope in a shape, and tells the player which. Parade is the standing
    // stance under another name; every other shape is a field shape, so it puts the squad back on
    // the attack as well.
    public static void form(ServerPlayer commander, Scope scope, GipfaeliFormation formation) {
        if (formation == GipfaeliFormation.PARADE) {
            stance(commander, scope, Stance.STAND, false);
            return;
        }

        List<GipfaeliSoldier> squad = squad(commander, scope);
        if (squad.isEmpty()) {
            refuse(commander, nobody(scope));
            return;
        }

        for (GipfaeliSoldier soldier : squad) {
            soldier.holdPosition(false);
        }

        field(commander, squad, formation, true);
        readout(commander, Component.translatable("combatupdate.army.formed", Component.translatable(formation.key())));
    }

    // Takes a squad off parade and into a field shape.
    private static void field(ServerPlayer commander, List<GipfaeliSoldier> squad, GipfaeliFormation formation, boolean reshape) {
        boolean paraded = false;
        for (GipfaeliSoldier soldier : squad) {
            paraded |= soldier.formation() == GipfaeliFormation.PARADE;
            soldier.setStance(Stance.ATTACK);
            soldier.standAt(null, 0.0F);
        }

        if (reshape || paraded) {
            for (int slot = 0; slot < squad.size(); slot++) {
                squad.get(slot).formUp(formation, slot, squad.size());
            }
        }

        if (paraded) {
            reformParade(commander);
        }
    }

    // Hands every soldier its number again: the ones on parade their block and place in it, the
    // ones in the field their place in the field shape. Called whenever the army changes, because
    // a wedge with a hole where its third soldier used to be is not a wedge.
    public static void reform(ServerPlayer commander) {
        List<GipfaeliSoldier> inField = new ArrayList<>();
        GipfaeliFormation shape = GipfaeliFormation.LOOSE;
        for (GipfaeliSoldier soldier : squad(commander)) {
            if (soldier.formation() != GipfaeliFormation.PARADE) {
                inField.add(soldier);
                if (soldier.formation() != GipfaeliFormation.LOOSE) {
                    shape = soldier.formation();
                }
            }
        }

        for (int slot = 0; slot < inField.size(); slot++) {
            inField.get(slot).formUp(shape, slot, inField.size());
        }

        reformParade(commander);
    }

    // Draws the parade up again: every squad that is standing gets a block, side by side in the
    // order of their colours with the reserve last, and every soldier in it a rank and a file -
    // the commander its place out in front.
    private static void reformParade(ServerPlayer commander) {
        Map<String, List<GipfaeliSoldier>> standing = new LinkedHashMap<>();
        List<GipfaeliSoldier> army = squad(commander);
        army.sort(Comparator.<GipfaeliSoldier>comparingInt(soldier -> soldier.uniform() == null ? Integer.MAX_VALUE : soldier.uniform().getId())
                .thenComparingInt(Entity::getId));
        for (GipfaeliSoldier soldier : army) {
            if (soldier.stance() == Stance.STAND && soldier.post() == null) {
                standing.computeIfAbsent(Scope.of(soldier.uniform()).token(), colour -> new ArrayList<>()).add(soldier);
            }
        }

        int block = 0;
        for (List<GipfaeliSoldier> squad : standing.values()) {
            int index = 0;
            for (GipfaeliSoldier soldier : squad) {
                int slot = soldier.commander() ? GipfaeliFormation.COMMANDER_SLOT : index++;
                soldier.formUp(GipfaeliFormation.PARADE, slot, squad.size(), block, standing.size());
            }

            block++;
        }
    }

    // Repaints everyone in scope: a whole squad moved to a new colour is that squad renamed, and
    // its commander comes with it unless the new colour already has one. What a soldier wears is
    // only ever cosmetic, but a red army and a blue army on the same server is the whole reason
    // to have the colours.
    public static void paint(ServerPlayer commander, Scope scope, @Nullable DyeColor colour) {
        List<GipfaeliSoldier> squad = squad(commander, scope);
        if (squad.isEmpty()) {
            refuse(commander, nobody(scope));
            return;
        }

        GipfaeliSoldier resident = leaderOf(squad(commander, Scope.of(colour)));
        for (GipfaeliSoldier soldier : squad) {
            if (soldier.commander() && resident != null && resident != soldier) {
                soldier.setCommander(false);
            }

            soldier.setUniform(colour);
        }

        commander.level().playSound(null, commander.getX(), commander.getY(), commander.getZ(),
                SoundEvents.DYE_USE, SoundSource.PLAYERS, 1.0F, 1.0F);
        reformParade(commander);
        readout(commander, scope.all
                ? Component.translatable("combatupdate.army.painted", squad.size(), colourName(colour))
                : Component.translatable("combatupdate.army.squad.painted", scope.name(), colourName(colour)));
    }

    private static Component colourName(@Nullable DyeColor colour) {
        return colour == null
                ? Component.translatable("combatupdate.army.menu.camo")
                : Component.translatable("color.minecraft." + colour.getName());
    }

    public static void manual(ServerPlayer commander) {
        commander.getInventory().placeItemBackInInventory(GipfaeliManual.book(), Prediction.SERVER_ONLY);
        readout(commander, Component.translatable("combatupdate.army.manual_given"));
    }

    // What the army is made of and what it is doing, as a couple of lines in chat.
    public static void status(ServerPlayer commander) {
        List<GipfaeliSoldier> army = squad(commander);
        if (army.isEmpty()) {
            refuse(commander, Component.translatable("combatupdate.army.none"));
            return;
        }

        commander.sendSystemMessage(strength(army));
        commander.sendSystemMessage(doing(army));
    }

    // --- The menus ---

    // Clicking a soldier: a commander opens its squad's orders, anyone else its own kit.
    public static void click(ServerPlayer commander, GipfaeliSoldier soldier) {
        if (soldier.commander()) {
            squadMenu(commander, Scope.of(soldier.uniform()));
        } else {
            soldierMenu(commander, soldier);
        }
    }

    // The army's menu: every squad a click away, and the four orders the whole army takes.
    //
    // Chat rather than a screen of its own, and deliberately so: a clickable line needs nothing on
    // the client, works over a normal connection to a vanilla-side player, and leaves the whole
    // thing sitting in the log to be clicked again a minute later.
    public static void menu(ServerPlayer commander) {
        List<GipfaeliSoldier> army = squad(commander);

        commander.sendSystemMessage(rule().append(Component.translatable("combatupdate.army.menu.title")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)).append(rule()));
        commander.sendSystemMessage(strength(army));
        commander.sendSystemMessage(squads(commander, army));
        commander.sendSystemMessage(ordersRow(army, ""));
    }

    // One squad's menu: how full it is, the four orders for it alone, and the buttons that fill
    // it. What clicking its commander opens.
    public static void squadMenu(ServerPlayer commander, Scope scope) {
        List<GipfaeliSoldier> squad = squad(commander, scope);
        String prefix = "squad " + scope.token() + " ";
        GipfaeliSoldier leader = leaderOf(squad);
        int size = Config.ARMY_SQUAD_SIZE.getAsInt();

        commander.sendSystemMessage(rule().append(Component.translatable("combatupdate.army.squad.title", scope.name())
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)).append(rule()));
        commander.sendSystemMessage(Component.translatable("combatupdate.army.squad.status",
                ranks(squad), size,
                leader == null ? Component.translatable("combatupdate.army.squad.no_commander") : leader.getName())
                .withStyle(ChatFormatting.GRAY));
        commander.sendSystemMessage(ordersRow(squad, prefix));

        MutableComponent spawn = heading("combatupdate.army.squad.spawn");
        for (int count : new int[] {1, 5, 10}) {
            spawn.append(button(Component.literal("+" + count), prefix + "fill " + count,
                    Component.translatable("combatupdate.army.squad.spawn.hover", count, count * Config.ARMY_RECRUIT_RATIONS.getAsInt()),
                    ChatFormatting.YELLOW));
        }
        spawn.append(button(Component.translatable("combatupdate.army.squad.spawn.fill", size), prefix + "fill " + size,
                Component.translatable("combatupdate.army.squad.spawn.fill.hover"), ChatFormatting.YELLOW));
        commander.sendSystemMessage(spawn);
    }

    // Attack, stand, hold, follow: the four orders, for whoever the prefix names. Stand shows
    // white while the squad is on parade.
    private static Component ordersRow(List<GipfaeliSoldier> squad, String prefix) {
        boolean standing = !squad.isEmpty() && squad.getFirst().stance() == Stance.STAND;
        return heading("combatupdate.army.menu.orders")
                .append(button(Component.translatable("combatupdate.army.stance.attack"), prefix + "attacksighted",
                        Component.translatable("combatupdate.army.order.sighted.hover"), ChatFormatting.RED))
                .append(button(Component.translatable("combatupdate.army.stance.stand"), prefix + "stance stand",
                        Component.translatable("combatupdate.army.stance.stand.hover"),
                        standing ? ChatFormatting.WHITE : ChatFormatting.AQUA))
                .append(button(Component.translatable("combatupdate.army.order.hold"), prefix + "hold",
                        Component.translatable("combatupdate.army.order.hold.hover"), ChatFormatting.GREEN))
                .append(button(Component.translatable("combatupdate.army.order.follow"), prefix + "follow",
                        Component.translatable("combatupdate.army.order.follow.hover"), ChatFormatting.GREEN));
    }

    // One soldier's menu: the kit that makes it a rifleman or a marksman, the armour it stands
    // in, the colour it wears - which is to say the squad it is in - and whether it leads it.
    public static void soldierMenu(ServerPlayer commander, GipfaeliSoldier soldier) {
        String at = "soldier " + soldier.getUUID() + " ";
        GipfaeliWeapon kit = soldier.weapon();
        GipfaeliArmour worn = GipfaeliArmour.of(soldier.getItemBySlot(EquipmentSlot.CHEST).getItem());

        commander.sendSystemMessage(rule().append(Component.translatable("combatupdate.army.soldier.title")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD)).append(rule()));
        commander.sendSystemMessage(Component.translatable("combatupdate.army.soldier.status",
                soldier.getName(), (int) Math.ceil(soldier.getHealth()), (int) Math.ceil(soldier.getMaxHealth()),
                worn == null ? Component.translatable("combatupdate.army.soldier.none") : worn.displayName(),
                colourName(soldier.uniform())).withStyle(ChatFormatting.GRAY));

        MutableComponent kits = heading("combatupdate.army.soldier.kit");
        for (GipfaeliWeapon role : GipfaeliWeapon.values()) {
            kits.append(button(role.roleName(), at + "kit " + role.token(),
                    Component.translatable("combatupdate.army.soldier.kit.hover", role.stack().getHoverName())
                            .append(Component.literal("\n"))
                            .append(Component.translatable(role.key() + ".hover")),
                    role == kit ? ChatFormatting.WHITE : ChatFormatting.YELLOW));
        }
        kits.append(button(Component.translatable("combatupdate.army.soldier.disarm"), at + "disarm", null, ChatFormatting.GRAY));
        commander.sendSystemMessage(kits);

        MutableComponent suits = heading("combatupdate.army.soldier.armour");
        for (GipfaeliArmour armour : GipfaeliArmour.values()) {
            suits.append(button(armour.displayName(), at + "armour " + armour.token(),
                    Component.translatable("combatupdate.army.soldier.armour.hover"),
                    armour == worn ? ChatFormatting.WHITE : ChatFormatting.AQUA));
        }
        suits.append(button(Component.translatable("combatupdate.army.soldier.strip"), at + "strip", null, ChatFormatting.GRAY));
        commander.sendSystemMessage(suits);

        commander.sendSystemMessage(colours("combatupdate.army.soldier.colour", at + "colour ", null));

        // Every post the player has, by number, and only when there are any: a soldier with no
        // posts to go to is not offered a row of nothing.
        MinecraftServer server = commander.level().getServer();
        List<GipfaeliArmyData.PostRef> posts = server == null ? List.of() : GipfaeliArmyData.get(server).posts(server, commander.getUUID());
        if (!posts.isEmpty()) {
            MutableComponent row = heading("combatupdate.army.soldier.posts");
            for (int number = 1; number <= posts.size(); number++) {
                GipfaeliArmyData.PostRef ref = posts.get(number - 1);
                boolean here = ref.pos().equals(soldier.post());
                row.append(button(Component.translatable("combatupdate.army.soldier.post", number), at + "post " + number,
                        Component.translatable("combatupdate.army.soldier.post.hover", ref.pos().getX(), ref.pos().getY(), ref.pos().getZ()),
                        here ? ChatFormatting.WHITE : ChatFormatting.AQUA));
            }

            commander.sendSystemMessage(row);
        }

        MutableComponent rank = heading("combatupdate.army.soldier.rank");
        if (soldier.commander()) {
            rank.append(button(Component.translatable("combatupdate.army.soldier.demote"), at + "demote", null, ChatFormatting.GRAY))
                    .append(button(Component.translatable("combatupdate.army.squad.name", colourName(soldier.uniform())),
                            "squad " + Scope.of(soldier.uniform()).token() + " menu",
                            Component.translatable("combatupdate.army.menu.squad.hover"), ChatFormatting.AQUA));
        } else {
            rank.append(button(Component.translatable("combatupdate.army.soldier.promote"), at + "promote",
                    Component.translatable("combatupdate.army.soldier.promote.hover"), ChatFormatting.LIGHT_PURPLE));
        }
        commander.sendSystemMessage(rank);
    }

    // The army's squads, each a button that opens it: its colour, how full it is, and a star if
    // it has a commander.
    private static Component squads(ServerPlayer commander, List<GipfaeliSoldier> army) {
        Map<String, List<GipfaeliSoldier>> byColour = new LinkedHashMap<>();
        List<GipfaeliSoldier> sorted = new ArrayList<>(army);
        sorted.sort(Comparator.comparingInt(soldier -> soldier.uniform() == null ? Integer.MAX_VALUE : soldier.uniform().getId()));
        for (GipfaeliSoldier soldier : sorted) {
            byColour.computeIfAbsent(Scope.of(soldier.uniform()).token(), colour -> new ArrayList<>()).add(soldier);
        }

        MutableComponent row = heading("combatupdate.army.menu.squads");
        if (byColour.isEmpty()) {
            return row.append(Component.translatable("combatupdate.army.menu.none").withStyle(ChatFormatting.DARK_GRAY));
        }

        for (Map.Entry<String, List<GipfaeliSoldier>> squad : byColour.entrySet()) {
            Scope scope = Objects.requireNonNull(Scope.parse(squad.getKey()));
            MutableComponent label = Component.empty().append(scope.name())
                    .append(Component.literal(" " + ranks(squad.getValue()) + "/" + Config.ARMY_SQUAD_SIZE.getAsInt()));
            if (leaderOf(squad.getValue()) != null) {
                label.append(Component.literal(" ")).append(Component.translatable("combatupdate.army.menu.commanded"));
            }

            ChatFormatting tint = scope.colour == null ? ChatFormatting.DARK_GREEN : ChatFormatting.AQUA;
            row.append(button(label, "squad " + scope.token() + " menu",
                    Component.translatable("combatupdate.army.menu.squad.hover"), tint));
        }

        return row;
    }

    // Sixteen dyes and the camouflage, as squares in their own colour, each of which runs the
    // given command with the colour's name on the end.
    private static Component colours(String heading, String command, @Nullable Component hover) {
        MutableComponent row = heading(heading);
        for (DyeColor colour : DyeColor.values()) {
            Component tip = hover == null ? Component.translatable("color.minecraft." + colour.getName()) : hover;
            row.append(Component.literal(" ■")
                    .withStyle(style -> style
                            .withColor(colour.getTextColor())
                            .withClickEvent(new ClickEvent.RunCommand(COMMAND + command + colour.getName()))
                            .withHoverEvent(new HoverEvent.ShowText(tip))));
        }

        return row.append(button(Component.translatable("combatupdate.army.menu.camo"), command + "camo", hover, ChatFormatting.DARK_GREEN));
    }

    // How many, of what, out of how many allowed.
    private static Component strength(List<GipfaeliSoldier> squad) {
        MutableComponent line = Component.translatable("combatupdate.army.menu.strength",
                squad.size(), Config.ARMY_MAX_SQUAD.getAsInt()).withStyle(ChatFormatting.GRAY);

        Map<GipfaeliWeapon, Integer> roles = new EnumMap<>(GipfaeliWeapon.class);
        int unarmed = 0;
        for (GipfaeliSoldier soldier : squad) {
            GipfaeliWeapon role = soldier.weapon();
            if (role != null) {
                roles.merge(role, 1, Integer::sum);
            } else {
                unarmed++;
            }
        }

        for (Map.Entry<GipfaeliWeapon, Integer> role : roles.entrySet()) {
            line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(role.getValue() + " ").withStyle(ChatFormatting.WHITE))
                    .append(role.getKey().roleName().copy().withStyle(ChatFormatting.GRAY));
        }

        if (unarmed > 0) {
            line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(unarmed + " ").withStyle(ChatFormatting.WHITE))
                    .append(Component.translatable("combatupdate.army.menu.unarmed").withStyle(ChatFormatting.GRAY));
        }

        return line;
    }

    // Stance, shape, and what it has been sent after.
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
        Stance stance = squad.isEmpty() ? Stance.ATTACK : squad.getFirst().stance();
        return Component.translatable("combatupdate.army.menu.doing",
                Component.translatable(stance.key()), Component.translatable(formation.key()), engaged, holding,
                ordered == null ? Component.translatable("combatupdate.army.menu.no_order") : ordered.getDisplayName())
                .withStyle(ChatFormatting.GRAY);
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

    // --- Guard posts ---

    // The soldiers stationed at a post - the player's own when one is given, anyone's when not.
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
        List<GipfaeliSoldier> army = squad(commander);
        army.removeIf(soldier -> pos.equals(soldier.post()));
        if (army.isEmpty()) {
            refuse(commander, Component.translatable("combatupdate.army.post.nobody"));
            return;
        }

        Vec3 centre = Vec3.atBottomCenterOf(pos.above());
        army.sort(Comparator.comparingDouble(soldier -> soldier.position().distanceToSqr(centre)));
        int sent = Math.min(Math.max(1, count), army.size());
        for (int index = 0; index < sent; index++) {
            postSoldier(commander, army.get(index), pos, radius);
        }

        reformParade(commander);
        readout(commander, Component.translatable("combatupdate.army.post.stationed", sent));
    }

    // Takes every guard off a post and lets it fall back in with the player.
    public static void recallPost(ServerPlayer commander, BlockPos pos) {
        List<GipfaeliSoldier> guards = guardsAt(commander.level(), pos, commander);
        for (GipfaeliSoldier guard : guards) {
            guard.guard(null);
            guard.station(null);
            guard.holdPosition(false);
            guard.order(null);
            guard.setTarget(null);
        }

        report(commander, Scope.ALL, guards.size(), "combatupdate.army.post.recalled");
    }

    // --- Taking ground off the map ---

    // Sends count soldiers to stand in a chunk: the territory screen's order. Free ground they
    // claim for the player when they get there; somebody else's they lay siege to, and for as
    // long as one of them is standing in it the capture runs as if the player stood there
    // themselves (see TerritoryManager, and garrisons() below). Nearest go first, so the army
    // does not send its marksman across the map while its assault troopers stand next door.
    public static void march(ServerPlayer commander, int chunkX, int chunkZ, int count) {
        List<GipfaeliSoldier> army = squad(commander);
        if (army.isEmpty()) {
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

        army.sort(Comparator.comparingDouble(soldier -> soldier.position().distanceToSqr(post)));
        int sent = Math.min(Math.max(1, count), army.size());
        for (int index = 0; index < sent; index++) {
            GipfaeliSoldier soldier = army.get(index);
            soldier.setStance(Stance.ATTACK);
            soldier.standAt(null, 0.0F);
            soldier.order(null);
            // Spread over the middle of the chunk rather than all on its centre block, and never
            // past its edge: a soldier stood one block into the next chunk is not holding this one.
            double dx = (commander.getRandom().nextDouble() * 2.0 - 1.0) * STATION_SPREAD;
            double dz = (commander.getRandom().nextDouble() * 2.0 - 1.0) * STATION_SPREAD;
            soldier.station(post.add(dx, 0.0, dz));
        }

        reformParade(commander);
        SIEGES.put(commander.getUUID(), new Siege(TerritoryClaim.Key.of(level, chunkX, chunkZ), false, 0L));
        commander.sendSystemMessage(Component.translatable("combatupdate.army.march", sent, chunkX, chunkZ)
                .withStyle(ChatFormatting.GOLD));
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

    // --- Telling the player about it ---

    // Orders land on the action bar: they replace each other, and nobody wants a scrollback of
    // every time they told the squad to fall in. The menus and their answers go to chat, where
    // they can be clicked and read back.
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

    private static Component nobody(Scope scope) {
        return scope.all
                ? Component.translatable("combatupdate.army.none")
                : Component.translatable("combatupdate.army.squad.none", scope.name());
    }

    private static void report(ServerPlayer commander, Scope scope, int strength, String message) {
        if (strength == 0) {
            refuse(commander, nobody(scope));
            return;
        }

        readout(commander, Component.translatable(message, strength));
    }

    private static void mark(LivingEntity target) {
        target.addEffect(new MobEffectInstance(MobEffects.GLOWING, MARK_DURATION_TICKS, 0, false, false, false));
    }
}
