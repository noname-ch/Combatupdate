package ch.bbcag.combatupdate;

import java.util.List;
import java.util.Optional;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BlocksAttacks;
import net.neoforged.neoforge.event.ModifyDefaultComponentsEvent;

// Brings back pre-1.9 sword blocking: holding right-click with a sword raises it and soaks part of
// an incoming hit, so a sword alone is not defenceless the moment the off hand is busy.
//
// It is deliberately the poor relation of a shield. A shield stops a frontal hit outright; a sword
// only halves it, takes longer to bring up, and stays knocked aside for longer once an axe has
// broken the guard. Blocking with a sword is what you do when you have nothing better, not a reason
// to leave the shield at home.
//
// Vanilla leaves the blocks-attacks component off swords entirely; setting it is all that is
// needed, because the item use handling, the block animation, the durability cost and the axe
// disable all key off that one component.
public final class SwordBlocking {
    // Seconds of holding right-click before the guard is actually up (a shield takes 0.25).
    private static final float BLOCK_DELAY_SECONDS = 0.5F;

    // Multiplies how long an axe hit keeps the guard down. Above 1.0, so breaking a sword's guard
    // punishes more than breaking a shield's.
    private static final float DISABLE_COOLDOWN_SCALE = 1.5F;

    // Same frontal arc a shield covers: a hit from behind is never blocked.
    private static final float BLOCKING_ANGLE = 90.0F;

    // Fraction of the incoming damage the sword eats. A shield runs at 1.0 - everything.
    private static final float DAMAGE_REDUCTION_FACTOR = 0.5F;

    // Durability the block costs, scored on the damage that came in: hits below the threshold are
    // free, the rest cost a point per point of damage plus one. These are the shield's numbers, and
    // a sword has far less durability to spend on them.
    private static final BlocksAttacks.ItemDamageFunction ITEM_DAMAGE =
            new BlocksAttacks.ItemDamageFunction(3.0F, 1.0F, 1.0F);

    private static final List<Item> SWORDS = List.of(
            Items.WOODEN_SWORD, Items.STONE_SWORD, Items.COPPER_SWORD, Items.IRON_SWORD,
            Items.GOLDEN_SWORD, Items.DIAMOND_SWORD, Items.NETHERITE_SWORD);

    private SwordBlocking() {
    }

    public static void modifyDefaultComponents(ModifyDefaultComponentsEvent event) {
        if (!Config.on(Config.ENABLE_SWORD_BLOCKING)) {
            return;
        }

        for (Item sword : SWORDS) {
            event.modify(sword, (components, context, item) -> components.set(
                    DataComponents.BLOCKS_ATTACKS,
                    new BlocksAttacks(
                            BLOCK_DELAY_SECONDS,
                            DISABLE_COOLDOWN_SCALE,
                            List.of(new BlocksAttacks.DamageReduction(
                                    BLOCKING_ANGLE, Optional.empty(), 0.0F, DAMAGE_REDUCTION_FACTOR)),
                            ITEM_DAMAGE,
                            // Whatever goes through a shield goes through a raised sword too.
                            Optional.of(context.lookupOrThrow(Registries.DAMAGE_TYPE).getOrThrow(DamageTypeTags.BYPASSES_SHIELD)),
                            Optional.of(SoundEvents.SHIELD_BLOCK),
                            Optional.of(SoundEvents.SHIELD_BREAK))));
        }
    }
}
