package ch.bbcag.gipfeliarmy.terraria;

import java.util.function.Supplier;

import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.EntityType;

// The three bosses out of Terraria, each with the lair it lives in. The order is the order they
// come in Terraria as well, and it is what a waypoint is sent over the wire as, so new ones go on
// the end.
public enum BossKind {
    EYE_OF_CTHULHU("eye_of_cthulhu", 0xFFE0453A, BossEvent.BossBarColor.RED, () -> TerrariaContent.EYE_OF_CTHULHU.get()),
    WALL_OF_FLESH("wall_of_flesh", 0xFFC0306A, BossEvent.BossBarColor.PURPLE, () -> TerrariaContent.WALL_OF_FLESH.get()),
    PLANTERA("plantera", 0xFFE87BD0, BossEvent.BossBarColor.PINK, () -> TerrariaContent.PLANTERA.get());

    private final String id;
    private final int colour;
    private final BossEvent.BossBarColor barColour;
    private final Supplier<EntityType<? extends TerrariaBoss>> type;

    BossKind(String id, int colour, BossEvent.BossBarColor barColour, Supplier<EntityType<? extends TerrariaBoss>> type) {
        this.id = id;
        this.colour = colour;
        this.barColour = barColour;
        this.type = type;
    }

    public String id() {
        return this.id;
    }

    // ARGB, for the waypoint marker and the lair's name on it.
    public int colour() {
        return this.colour;
    }

    // The nearest the boss bar comes to that colour.
    public BossEvent.BossBarColor barColour() {
        return this.barColour;
    }

    public EntityType<? extends TerrariaBoss> type() {
        return this.type.get();
    }

    public Component displayName() {
        return Component.translatable("entity.gipfeliarmy." + this.id);
    }

    // What the waypoint says: the lair, not the boss, since that is what is standing there.
    public Component lairName() {
        return Component.translatable("gipfeliarmy.terraria.lair." + this.id);
    }

    public static BossKind byOrdinal(int ordinal) {
        BossKind[] values = values();
        return values[Math.clamp(ordinal, 0, values.length - 1)];
    }
}
