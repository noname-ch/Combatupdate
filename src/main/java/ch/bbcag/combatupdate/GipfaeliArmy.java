package ch.bbcag.combatupdate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
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
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.combatupdate.entity.GipfaeliSoldier;

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

    private GipfaeliArmy() {
    }

    // --- Who is in the squad ---

    // Every soldier this player has, wherever in the world it is standing. Soldiers in chunks
    // nobody has loaded are not in here, which is the right answer for every caller: a soldier
    // nobody is near is not fighting, marching, or worth counting against the cap.
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

    // Signs one soldier on, armed out of the commander's own pack. Returns whether anything was
    // done at all, which for the flag is the cue to swallow the click either way: being told the
    // squad is full is as much of an answer as a new soldier is.
    public static boolean recruit(ServerPlayer commander) {
        ServerLevel level = commander.level();
        int cap = Config.ARMY_MAX_SQUAD.getAsInt();
        int strength = squad(commander).size();
        if (strength >= cap) {
            refuse(commander, Component.translatable("combatupdate.army.full", cap));
            return true;
        }

        // Creative pays for nothing, and neither does anyone who has turned the supplies off - the
        // same bargain the launcher and the rig offer.
        boolean free = commander.getAbilities().instabuild || !Config.ARMY_CONSUMES_SUPPLIES.get();
        int weaponSlot = findWeaponSlot(commander);
        if (weaponSlot == NO_SLOT && !free) {
            refuse(commander, Component.translatable("combatupdate.army.no_weapon"));
            return true;
        }

        // A recruit is handed a gun out of the pack; with nothing to hand over and nothing to pay,
        // it brings the standard rifle of its own.
        ItemStack weapon = weaponSlot == NO_SLOT
                ? GipfaeliWeapon.AK47.stack()
                : commander.getInventory().getItem(weaponSlot).copyWithCount(1);

        int rations = Config.ARMY_RECRUIT_RATIONS.getAsInt();
        if (!free && countRations(commander) < rations) {
            refuse(commander, Component.translatable("combatupdate.army.no_rations", rations));
            return true;
        }

        enlist(commander, weapon);

        if (!free) {
            if (weaponSlot != NO_SLOT) {
                commander.getInventory().getItem(weaponSlot).shrink(1);
            }

            spendRations(commander, rations);
        }

        readout(commander, Component.translatable("combatupdate.army.recruited",
                weapon.getHoverName(), strength + 1, cap));
        return true;
    }

    // Soldiers out of nothing, for whoever is running the server: the same recruits, armed with the
    // weapon that was asked for and paid for with none of it. Returns how many actually fell in.
    public static int conscript(ServerPlayer commander, GipfaeliWeapon weapon, int count) {
        int cap = Config.ARMY_MAX_SQUAD.getAsInt();
        int room = Math.max(0, cap - squad(commander).size());
        int signed = Math.min(count, room);
        if (signed == 0) {
            refuse(commander, Component.translatable("combatupdate.army.full", cap));
            return 0;
        }

        for (int soldier = 0; soldier < signed; soldier++) {
            enlist(commander, weapon.stack());
        }

        readout(commander, Component.translatable("combatupdate.army.conscripted",
                signed, weapon.stack().getHoverName()));
        return signed;
    }

    // Puts one soldier on the ground beside the commander, armed and already theirs. The one place
    // a soldier is ever made, so a recruit signed on with the flag and one conscripted by command
    // are the same soldier.
    private static GipfaeliSoldier enlist(ServerPlayer commander, ItemStack weapon) {
        ServerLevel level = commander.level();
        GipfaeliSoldier soldier = new GipfaeliSoldier(CombatUpdate.GIPFAELI_SOLDIER.get(), level);
        soldier.setPos(fallInSpot(commander));
        soldier.setYRot(commander.getYRot());
        soldier.setOwner(commander);
        soldier.setTame(true, false);
        soldier.arm(weapon);
        toughen(soldier);
        level.addFreshEntity(soldier);

        level.playSound(null, soldier.getX(), soldier.getY(), soldier.getZ(),
                SoundEvents.ARMOR_EQUIP_IRON.value(), SoundSource.PLAYERS, 1.0F, 0.9F);
        return soldier;
    }

    // Health and armour come off the config rather than the attribute defaults, because a soldier
    // is recruited long after the config has loaded and these are the two numbers anyone running a
    // server will actually want to move.
    private static void toughen(GipfaeliSoldier soldier) {
        AttributeInstance health = soldier.getAttribute(Attributes.MAX_HEALTH);
        if (health != null) {
            health.setBaseValue(Config.ARMY_SOLDIER_HEALTH.getAsDouble());
        }

        AttributeInstance armor = soldier.getAttribute(Attributes.ARMOR);
        if (armor != null) {
            armor.setBaseValue(Config.ARMY_SOLDIER_ARMOR.getAsDouble());
        }

        soldier.setHealth(soldier.getMaxHealth());
    }

    // A pace to the side of the commander rather than under their feet, so recruiting a squad in
    // one go doesn't stack eight soldiers in the one block.
    private static Vec3 fallInSpot(ServerPlayer commander) {
        Vec3 look = commander.getLookAngle();
        Vec3 sideways = new Vec3(-look.z, 0.0, look.x).normalize();
        double offset = commander.getRandom().nextDouble() * 2.0 - 1.0;
        return commander.position().add(look.x, 0.0, look.z).add(sideways.scale(offset * 1.5));
    }

    private static int findWeaponSlot(Player player) {
        Inventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (GipfaeliWeapon.of(inventory.getItem(slot)) != null) {
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

    // Break off and fall back in.
    public static void follow(ServerPlayer commander) {
        List<GipfaeliSoldier> squad = squad(commander);
        for (GipfaeliSoldier soldier : squad) {
            soldier.holdPosition(false);
            soldier.order(null);
            soldier.setTarget(null);
        }

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

    // Sends the squad home. The guns go back to whoever paid for them rather than out of the world
    // with their carriers - dismissing an army should cost nothing but the rations it ate.
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

        report(commander, squad.size(), "combatupdate.army.dismissed");
    }

    // What the squad is made of and what it is doing, as one line in chat.
    public static void status(ServerPlayer commander) {
        List<GipfaeliSoldier> squad = squad(commander);
        if (squad.isEmpty()) {
            refuse(commander, Component.translatable("combatupdate.army.none"));
            return;
        }

        int holding = 0;
        int engaged = 0;
        for (GipfaeliSoldier soldier : squad) {
            if (soldier.holdingPosition()) {
                holding++;
            }

            if (soldier.getTarget() != null) {
                engaged++;
            }
        }

        commander.sendSystemMessage(Component.translatable("combatupdate.army.status",
                squad.size(), Config.ARMY_MAX_SQUAD.getAsInt(), engaged, holding)
                .withStyle(ChatFormatting.GOLD));
    }

    // --- The target menu ---

    // Everything the squad can be pointed at, as a list of buttons: every player on the server,
    // every animal and every monster within reach, each one a click away from being the target.
    //
    // Chat rather than a screen of its own, and deliberately so: a clickable line needs nothing on
    // the client, works over a normal connection to a vanilla-side player, and leaves the list
    // sitting in the log to be clicked again a minute later.
    public static void menu(ServerPlayer commander) {
        ServerLevel level = commander.level();
        double range = Config.ARMY_MENU_RANGE.getAsDouble();
        int entries = Config.ARMY_MENU_ENTRIES.getAsInt();

        commander.sendSystemMessage(Component.translatable("combatupdate.army.menu.title")
                .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));

        List<LivingEntity> players = new ArrayList<>();
        List<LivingEntity> animals = new ArrayList<>();
        List<LivingEntity> monsters = new ArrayList<>();

        MinecraftServer server = commander.level().getServer();
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

        commander.sendSystemMessage(group(commander, "combatupdate.army.menu.players", players, entries));
        commander.sendSystemMessage(group(commander, "combatupdate.army.menu.animals", animals, entries));
        commander.sendSystemMessage(group(commander, "combatupdate.army.menu.monsters", monsters, entries));
        commander.sendSystemMessage(orders());
    }

    // One row of the menu: a heading, then a button per candidate, nearest first.
    private static Component group(ServerPlayer commander, String heading, List<LivingEntity> candidates, int entries) {
        MutableComponent row = Component.translatable(heading).withStyle(ChatFormatting.YELLOW);
        if (candidates.isEmpty()) {
            return row.append(Component.translatable("combatupdate.army.menu.none").withStyle(ChatFormatting.DARK_GRAY));
        }

        candidates.sort(Comparator.comparingDouble(candidate -> reach(commander, candidate)));
        int shown = Math.min(entries, candidates.size());
        for (int index = 0; index < shown; index++) {
            row.append(Component.literal(" ")).append(button(commander, candidates.get(index)));
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
    private static Component button(ServerPlayer commander, LivingEntity candidate) {
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

        return Component.literal("[").append(label).append(Component.literal("]"))
                .withStyle(style -> style
                        .withColor(elsewhere ? ChatFormatting.DARK_GRAY : ChatFormatting.AQUA)
                        .withClickEvent(new ClickEvent.RunCommand("/gipfaeliarmy attack " + candidate.getUUID()))
                        .withHoverEvent(new HoverEvent.ShowText(hover)));
    }

    private static Component orders() {
        return Component.translatable("combatupdate.army.menu.orders").withStyle(ChatFormatting.YELLOW)
                .append(Component.literal(" ")).append(order("combatupdate.army.order.follow", "follow"))
                .append(Component.literal(" ")).append(order("combatupdate.army.order.hold", "hold"))
                .append(Component.literal(" ")).append(order("combatupdate.army.order.stand_down", "standdown"))
                .append(Component.literal(" ")).append(order("combatupdate.army.order.dismiss", "dismiss"))
                .append(Component.literal(" ")).append(order("combatupdate.army.order.refresh", "targets"));
    }

    private static Component order(String label, String command) {
        return Component.literal("[").append(Component.translatable(label)).append(Component.literal("]"))
                .withStyle(style -> style
                        .withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent.RunCommand("/gipfaeliarmy " + command)));
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
