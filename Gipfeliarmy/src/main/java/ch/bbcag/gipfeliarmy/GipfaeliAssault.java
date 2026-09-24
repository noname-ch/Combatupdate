package ch.bbcag.gipfeliarmy;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.entity.EntityTypeTest;

import ch.bbcag.gipfeliarmy.GipfaeliArmy.Scope;
import ch.bbcag.gipfeliarmy.entity.GipfaeliSoldier;

// A squad sent at a whole side rather than at one target: "/soldats-red attack yellow" sends the
// red squad at every yellow soldier there is, and "/soldats attack training" the whole army at the
// training dummies, until there is nobody of them left standing.
//
// Once a second every soldier of the attacking squad that has nothing to shoot is given the
// nearest of what is left; when nothing is left the squad is told it is over and falls back in.
//
// Yellow may be the player's own yellow squad - a war game between two squads of one army. For as
// long as the assault lasts the two colours stop being on the same side (see feud()), both ways:
// the yellows fire back. Every other squad of that army stays friendly to both.
//
// In memory only, like a siege: a restart ends it, and so does any order that calls the attacking
// squad off (follow, stand down, stand, dismiss, or another attack).
public final class GipfaeliAssault {
    // Who is being attacked: every soldier in one uniform, the reserve in camouflage, or the
    // training dummies.
    // With a number, one numbered squad of the colour ("red2"); with 0, every squad of it.
    public record Foe(@Nullable DyeColor colour, boolean reserve, boolean training, int number) {
        public static final Foe TRAINING = new Foe(null, false, true, 0);
        public static final Foe RESERVE = new Foe(null, true, false, 0);

        // A dye's name, "camo" or "training" - and, since orders get typed in a hurry, the
        // German for them too.
        public static @Nullable Foe parse(String token) {
            String word = token.toLowerCase(Locale.ROOT);
            switch (word) {
                case "training", "dummy", "dummies", "puppen" -> {
                    return TRAINING;
                }
                case "camo", "reserve", "tarn" -> {
                    return RESERVE;
                }
                default -> {
                }
            }

            int split = word.length();
            while (split > 0 && Character.isDigit(word.charAt(split - 1))) {
                split--;
            }
            String base = word.substring(0, split);
            String digits = word.substring(split);
            Scope scope = Scope.parse(GERMAN.getOrDefault(base, base) + digits);
            if (scope == null || scope.all()) {
                return null;
            }

            return scope.colour() == null ? new Foe(null, true, false, scope.number()) : new Foe(scope.colour(), false, false, scope.number());
        }

        public boolean matches(GipfaeliSoldier soldier) {
            if (!soldier.isAlive()) {
                return false;
            }

            if (this.training) {
                return soldier.training();
            }

            return !soldier.training() && (this.reserve ? soldier.uniform() == null : this.colour != null && this.colour == soldier.uniform())
                    && (this.number == 0 || this.number == soldier.squadNumber());
        }

        public Component name() {
            return this.training
                    ? Component.translatable("gipfeliarmy.army.assault.training")
                    : Scope.of(this.colour, this.number).name();
        }
    }

    private static final Map<String, String> GERMAN = Map.ofEntries(
            Map.entry("rot", "red"), Map.entry("gelb", "yellow"), Map.entry("blau", "blue"),
            Map.entry("gruen", "green"), Map.entry("schwarz", "black"), Map.entry("weiss", "white"),
            Map.entry("lila", "purple"), Map.entry("violett", "purple"), Map.entry("rosa", "pink"),
            Map.entry("grau", "gray"), Map.entry("hellgrau", "light_gray"), Map.entry("hellblau", "light_blue"),
            Map.entry("hellgruen", "lime"), Map.entry("tuerkis", "cyan"), Map.entry("braun", "brown"));

    // The words the attack command suggests besides player names.
    public static final List<String> SUGGESTIONS;

    static {
        List<String> words = new ArrayList<>();
        words.add("training");
        words.add("camo");
        for (DyeColor colour : DyeColor.VALUES) {
            words.add(colour.getName());
        }
        SUGGESTIONS = List.copyOf(words);
    }

    private record Assault(Scope attackers, Foe foe) {
        boolean attacking(GipfaeliSoldier soldier) {
            return this.attackers.includes(soldier) && !this.foe.matches(soldier);
        }
    }

    private static final Map<UUID, List<Assault>> ASSAULTS = new ConcurrentHashMap<>();

    private static final int INTERVAL_TICKS = 20;

    private GipfaeliAssault() {
    }

    // Sends everyone in scope at every foe of that kind in reach, and keeps sending them until
    // there are none.
    public static void start(ServerPlayer commander, Scope scope, Foe foe) {
        Assault assault = new Assault(scope, foe);
        List<GipfaeliSoldier> squad = attackers(commander, assault);
        if (squad.isEmpty()) {
            // Everyone in scope is the foe itself: red told to attack red.
            boolean own = !GipfaeliArmy.squad(commander, scope).isEmpty();
            GipfaeliArmy.readout(commander, own ? Component.translatable("gipfeliarmy.army.own_side")
                    : scope.all() ? Component.translatable("gipfeliarmy.army.none")
                    : Component.translatable("gipfeliarmy.army.squad.none", scope.name()));
            return;
        }

        end(commander, scope);
        List<Assault> list = ASSAULTS.computeIfAbsent(commander.getUUID(), id -> new CopyOnWriteArrayList<>());
        list.add(assault);

        List<GipfaeliSoldier> foes = foes(commander, assault);
        if (foes.isEmpty()) {
            list.remove(assault);
            GipfaeliArmy.readout(commander, Component.translatable("gipfeliarmy.army.assault.none", foe.name()));
            return;
        }

        for (GipfaeliSoldier soldier : squad) {
            soldier.setStance(GipfaeliSoldier.Stance.ATTACK);
            soldier.standAt(null, 0.0F);
            soldier.guard(null);
            soldier.station(null);
            soldier.holdPosition(false);
            soldier.order(null);
        }
        for (GipfaeliSoldier target : foes) {
            target.addEffect(new MobEffectInstance(MobEffects.GLOWING, 200, 0, false, false, false));
        }

        assign(squad, foes);
        commander.level().playSound(null, commander.getX(), commander.getY(), commander.getZ(),
                SoundEvents.RAID_HORN.value(), SoundSource.PLAYERS, 0.6F, 1.2F);
        commander.sendSystemMessage(Component.translatable("gipfeliarmy.army.assault.start",
                squad.size(), scope.name(), foes.size(), foe.name()).withStyle(ChatFormatting.GOLD));
    }

    // Calls off whatever assault the soldiers in scope are on. The whole army's orders call off
    // every one.
    public static void end(ServerPlayer commander, Scope scope) {
        List<Assault> list = ASSAULTS.get(commander.getUUID());
        if (list == null) {
            return;
        }

        list.removeIf(assault -> assault.attackers().overlaps(scope));
        if (list.isEmpty()) {
            ASSAULTS.remove(commander.getUUID());
        }
    }

    public static void forget(UUID commander) {
        ASSAULTS.remove(commander);
    }

    // Whether two soldiers of one army are at war with each other right now: one of them is in a
    // squad sent at the other's colour.
    public static boolean feud(Entity one, Entity other) {
        if (ASSAULTS.isEmpty() || !(one instanceof GipfaeliSoldier first) || !(other instanceof GipfaeliSoldier second)) {
            return false;
        }

        LivingEntity owner = first.getOwner();
        List<Assault> list = owner == null ? null : ASSAULTS.get(owner.getUUID());
        if (list == null) {
            return false;
        }

        for (Assault assault : list) {
            if ((assault.attacking(first) && assault.foe().matches(second))
                    || (assault.attacking(second) && assault.foe().matches(first))) {
                return true;
            }
        }

        return false;
    }

    // Once a second: whoever is idle gets the nearest foe left, and an assault with no foes left,
    // or nobody left to carry it, is over.
    public static void tick(MinecraftServer server) {
        if (ASSAULTS.isEmpty() || server.getTickCount() % INTERVAL_TICKS != 0) {
            return;
        }

        for (Map.Entry<UUID, List<Assault>> entry : ASSAULTS.entrySet()) {
            ServerPlayer commander = server.getPlayerList().getPlayer(entry.getKey());
            if (commander == null) {
                ASSAULTS.remove(entry.getKey());
                continue;
            }

            for (Assault assault : entry.getValue()) {
                List<GipfaeliSoldier> squad = attackers(commander, assault);
                if (squad.isEmpty()) {
                    entry.getValue().remove(assault);
                    commander.sendSystemMessage(Component.translatable("gipfeliarmy.army.assault.lost",
                            assault.attackers().name(), assault.foe().name()).withStyle(ChatFormatting.RED));
                    continue;
                }

                List<GipfaeliSoldier> foes = foes(commander, assault);
                if (foes.isEmpty()) {
                    entry.getValue().remove(assault);
                    for (GipfaeliSoldier soldier : squad) {
                        soldier.order(null);
                        soldier.setTarget(null);
                    }
                    commander.level().playSound(null, commander.getX(), commander.getY(), commander.getZ(),
                            SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.7F, 1.0F);
                    commander.sendSystemMessage(Component.translatable("gipfeliarmy.army.assault.won",
                            assault.attackers().name(), assault.foe().name()).withStyle(ChatFormatting.GREEN));
                    continue;
                }

                assign(squad, foes);
            }

            if (entry.getValue().isEmpty()) {
                ASSAULTS.remove(entry.getKey());
            }
        }
    }

    // Every soldier with nothing ordered, or with an order on something that is not a foe, is
    // given the nearest foe it can reach.
    private static void assign(List<GipfaeliSoldier> squad, List<GipfaeliSoldier> foes) {
        double range = Config.ARMY_MARCH_RANGE.getAsDouble();
        for (GipfaeliSoldier soldier : squad) {
            LivingEntity current = soldier.orderedTarget();
            if (current instanceof GipfaeliSoldier busy && foes.contains(busy)) {
                continue;
            }

            GipfaeliSoldier nearest = null;
            double best = range * range;
            for (GipfaeliSoldier foe : foes) {
                if (foe.level() != soldier.level()) {
                    continue;
                }

                double distance = foe.distanceToSqr(soldier);
                if (distance < best) {
                    best = distance;
                    nearest = foe;
                }
            }

            if (nearest != null) {
                soldier.order(nearest);
            }
        }
    }

    private static List<GipfaeliSoldier> attackers(ServerPlayer commander, Assault assault) {
        List<GipfaeliSoldier> squad = GipfaeliArmy.squad(commander, assault.attackers());
        squad.removeIf(soldier -> assault.foe().matches(soldier));
        return squad;
    }

    // Every foe of this kind in the commander's world within march range of the commander or of
    // anyone in the attacking squad: another player's yellows as much as the commander's own.
    private static List<GipfaeliSoldier> foes(ServerPlayer commander, Assault assault) {
        ServerLevel level = commander.level();
        double range = Config.ARMY_MARCH_RANGE.getAsDouble();
        List<GipfaeliSoldier> squad = attackers(commander, assault);
        List<GipfaeliSoldier> foes = new ArrayList<>();
        level.getEntities(EntityTypeTest.forClass(GipfaeliSoldier.class), soldier -> {
            if (!assault.foe().matches(soldier)) {
                return false;
            }

            if (soldier.distanceToSqr(commander) <= range * range) {
                return true;
            }

            for (GipfaeliSoldier attacker : squad) {
                if (attacker.level() == level && attacker.distanceToSqr(soldier) <= range * range) {
                    return true;
                }
            }

            return false;
        }, foes);
        return foes;
    }
}
