package ch.bbcag.combatupdate;

import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.joml.Vector3f;

import com.mojang.math.Transformation;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;

import ch.bbcag.combatupdate.mixin.DisplayAccessor;
import ch.bbcag.combatupdate.mixin.TextDisplayAccessor;

// Every hit a player lands puts the number up in the air beside whatever they hit, where it drifts
// up for a moment and then clears itself away.
//
// These are text display entities rather than actual particles, because a particle cannot draw text:
// the particle pipeline only ever puts quads on screen from the particle atlas, and the one thing
// that draws text out in the world - SubmitNodeCollector#submitNameTag - is reachable only from
// inside an entity renderer, which a particle never enters. A text display is the game's own answer
// to that, and it brings billboarding, distance culling and multiplayer sync along with it: every
// player who can see the mob sees the number, with no packet of our own.
//
// Server-side throughout, for the same reason.
public final class DamageNumbers {
    // The numbers currently in the air, against the ticks each has left. Only ever touched from the
    // server thread, but these entities outlive the hit that made them, so the map has to survive
    // between ticks rather than being rebuilt.
    private static final Map<Display.TextDisplay, Integer> LIVE = new ConcurrentHashMap<>();

    // A hit worth shouting about. Below the first it is an ordinary chip of damage, above the second
    // it is something that hurt.
    private static final float NOTABLE_DAMAGE = 5.0F;
    private static final float HEAVY_DAMAGE = 15.0F;

    private DamageNumbers() {
    }

    public static void spawn(LivingEntity victim, float amount) {
        if (!Config.DAMAGE_NUMBERS.get() || amount < Config.DAMAGE_NUMBER_MINIMUM.getAsDouble()) {
            return;
        }

        if (!(victim.level() instanceof ServerLevel level)) {
            return;
        }

        Display.TextDisplay display = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        if (display == null) {
            return;
        }

        // Scattered around the victim rather than stacked on the one spot, so a flurry of hits reads
        // as a flurry instead of as a single number flickering in place.
        RandomSource random = level.getRandom();
        double spread = Config.DAMAGE_NUMBER_SPREAD.getAsDouble();
        display.setPos(
                victim.getX() + (random.nextDouble() - 0.5) * spread,
                victim.getY() + victim.getBbHeight() * 0.75,
                victim.getZ() + (random.nextDouble() - 0.5) * spread);

        TextDisplayAccessor text = (TextDisplayAccessor) display;
        text.invokeSetText(format(amount));
        // The default background is a quarter-opaque black plate behind the text, which reads as a
        // label hanging in the air; a damage number wants to be just the number.
        text.invokeSetBackgroundColor(0);
        text.invokeSetFlags(Display.TextDisplay.FLAG_SHADOW);

        DisplayAccessor shape = (DisplayAccessor) display;
        shape.invokeSetBillboardConstraints(Display.BillboardConstraints.CENTER);
        float scale = (float) Config.DAMAGE_NUMBER_SCALE.getAsDouble();
        shape.invokeSetTransformation(new Transformation(null, null, new Vector3f(scale, scale, scale), null));

        level.addFreshEntity(display);
        LIVE.put(display, Config.DAMAGE_NUMBER_LIFETIME_TICKS.getAsInt());
    }

    // Driven once per server tick rather than per level: what needs doing is the same either way, and
    // one pass over one map cannot double-count a number the way a per-level pass could.
    public static void tick() {
        if (LIVE.isEmpty()) {
            return;
        }

        double rise = Config.DAMAGE_NUMBER_RISE.getAsDouble();
        Iterator<Map.Entry<Display.TextDisplay, Integer>> entries = LIVE.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<Display.TextDisplay, Integer> entry = entries.next();
            Display.TextDisplay display = entry.getKey();
            int remaining = entry.getValue() - 1;

            // Also drops anything already gone for reasons of its own - the chunk unloading beneath
            // it, say - so the map can't accumulate entities that no longer exist.
            if (remaining <= 0 || display.isRemoved()) {
                display.discard();
                entries.remove();
                continue;
            }

            display.setPos(display.getX(), display.getY() + rise, display.getZ());
            entry.setValue(remaining);
        }
    }

    // Whole numbers where the damage is whole, one decimal where it is not, so half-hearts still read
    // as half-hearts instead of rounding away to nothing.
    private static Component format(float amount) {
        String shown = amount == Math.round(amount)
                ? String.valueOf(Math.round(amount))
                : String.format(Locale.ROOT, "%.1f", amount);

        ChatFormatting colour = amount >= HEAVY_DAMAGE ? ChatFormatting.RED
                : amount >= NOTABLE_DAMAGE ? ChatFormatting.GOLD
                : ChatFormatting.WHITE;
        return Component.literal(shown).withStyle(colour, ChatFormatting.BOLD);
    }
}
