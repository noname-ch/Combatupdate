package ch.bbcag.gipfeliarmy;

import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

import ch.bbcag.gipfeliarmy.GipfaeliArmy.Scope;
import ch.bbcag.gipfeliarmy.entity.GipfaeliSoldier;

// The pattern a soldier's uniform is printed in, on top of the squad colour it wears.
//
// PLAIN is the uniform as it always was: cloth in the squad's dye, and the woodland camouflage for
// the reserve that has no dye yet. Every other pattern prints the whole uniform in that ground's
// colours - snow for the Alps, sand for the desert - and keeps the squad's colour to the cap band,
// the shoulders and a patch on the chest, so a squad in the snow can still be told from the next.
//
// A pattern is only a look. The squad is still the colour, and painting a soldier keeps its
// pattern; changing the pattern keeps its squad.
public enum GipfaeliCamo {
    PLAIN(0x5E6A44),
    WOODLAND(0x5B6B3A),
    SNOW(0xE8ECEE),
    DESERT(0xC9AE7C),
    JUNGLE(0x3F5E2A),
    URBAN(0x808284),
    NIGHT(0x2A2F3A);

    private static final GipfaeliCamo[] ALL = values();

    // The ground colour, which leather armour is dyed to match.
    private final int leather;

    GipfaeliCamo(int leather) {
        this.leather = leather;
    }

    public static GipfaeliCamo byOrdinal(int ordinal) {
        return ALL[Math.clamp(ordinal, 0, ALL.length - 1)];
    }

    public static @Nullable GipfaeliCamo byName(String name) {
        for (GipfaeliCamo camo : ALL) {
            if (camo.token().equalsIgnoreCase(name)) {
                return camo;
            }
        }

        return null;
    }

    public GipfaeliCamo next() {
        return ALL[(this.ordinal() + 1) % ALL.length];
    }

    public int leather() {
        return this.leather;
    }

    public String token() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    public Component displayName() {
        return Component.translatable("gipfeliarmy.army.camo." + this.token());
    }

    // --- Orders ---

    // Everyone in scope into the pattern, the commander too: a commander in black on a snowfield
    // is the first one shot.
    public static void dressSquad(ServerPlayer commander, Scope scope, GipfaeliCamo camo) {
        List<GipfaeliSoldier> squad = GipfaeliArmy.squad(commander, scope);
        if (squad.isEmpty()) {
            GipfaeliArmy.readout(commander, scope.all()
                    ? Component.translatable("gipfeliarmy.army.none")
                    : Component.translatable("gipfeliarmy.army.squad.none", scope.name()));
            return;
        }

        for (GipfaeliSoldier soldier : squad) {
            change(soldier, camo);
        }

        GipfaeliArmy.readout(commander, Component.translatable("gipfeliarmy.army.camo.squad", squad.size(), camo.displayName()));
    }

    public static void dressSoldier(ServerPlayer commander, GipfaeliSoldier soldier, GipfaeliCamo camo) {
        change(soldier, camo);
        GipfaeliArmy.readout(commander, Component.translatable("gipfeliarmy.army.camo.soldier", soldier.getDisplayName(), camo.displayName()));
    }

    private static void change(GipfaeliSoldier soldier, GipfaeliCamo camo) {
        if (soldier.camo() == camo) {
            return;
        }

        soldier.setCamo(camo);
        if (soldier.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.POOF, soldier.getX(), soldier.getY() + 1.0, soldier.getZ(), 4, 0.25, 0.4, 0.25, 0.01);
            level.playSound(null, soldier.getX(), soldier.getY(), soldier.getZ(),
                    SoundEvents.ARMOR_EQUIP_LEATHER.value(), SoundSource.NEUTRAL, 0.6F, 1.0F);
        }
    }
}
