package ch.bbcag.combatupdate;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ToolMaterial;

// A sword that trades damage away for the swing timer. Every hit lands at full strength no matter
// how fast it is thrown, which is the opposite bargain to a normal sword: that one wants you to
// wait for the meter, this one never asks.
//
// Only iron and diamond get one. The shortsword is meant as a sidegrade to the sword of the same
// metal rather than a tier ladder of its own, so a wooden or golden one would be a strictly worse
// version of something a player already has.
public final class Shortsword {
    // Vanilla swords pass 3.0 here, which lands at 6 damage for iron and 7 for diamond once the
    // material bonus and the player's own base damage are added. Two points less is what the
    // shorter blade costs: 4 for iron, 5 for diamond.
    private static final float ATTACK_DAMAGE_BASELINE = 1.0F;

    // Vanilla swords pass -2.4, which leaves a player at 1.6 swings a second - a full-strength hit
    // only every 12 ticks or so. The player's base attack speed is 4.0, so +16.0 puts the total at
    // 20 a second, and the strength meter refills inside a single tick. That is as close to no
    // cooldown as the attribute allows, and it is a real attribute rather than a special case, so
    // the tooltip and anything else reading attack speed still tell the truth.
    private static final float ATTACK_SPEED_BASELINE = 16.0F;

    private Shortsword() {
    }

    public static Item.Properties properties(Item.Properties properties, ToolMaterial material) {
        return properties.sword(material, ATTACK_DAMAGE_BASELINE, ATTACK_SPEED_BASELINE);
    }
}
