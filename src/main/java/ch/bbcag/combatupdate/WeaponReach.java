package ch.bbcag.combatupdate;

import java.util.List;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.AttackRange;
import net.neoforged.neoforge.event.ModifyDefaultComponentsEvent;

// Gives tridents extra reach and shortens axes, so weapon choice trades reach against damage rather
// than every melee weapon swinging the same distance.
//
// Vanilla leaves the attack range component off both, which falls back to the player's generic
// interaction range attribute (3 blocks); setting the component overrides that per item.
public final class WeaponReach {
    private static final float TRIDENT_REACH = 3.5F;
    private static final float AXE_REACH = 2.5F;

    // Matches the defaults of the attack range component, which vanilla items rely on.
    private static final float HITBOX_MARGIN = 0.3F;
    private static final float MOB_FACTOR = 1.0F;

    private static final List<Item> AXES = List.of(
            Items.WOODEN_AXE, Items.STONE_AXE, Items.IRON_AXE,
            Items.GOLDEN_AXE, Items.DIAMOND_AXE, Items.NETHERITE_AXE);

    private WeaponReach() {
    }

    public static void modifyDefaultComponents(ModifyDefaultComponentsEvent event) {
        event.modify(Items.TRIDENT, (components, context, item) -> components.set(DataComponents.ATTACK_RANGE, reach(TRIDENT_REACH)));

        for (Item axe : AXES) {
            event.modify(axe, (components, context, item) -> components.set(DataComponents.ATTACK_RANGE, reach(AXE_REACH)));
        }
    }

    private static AttackRange reach(float blocks) {
        return new AttackRange(0.0F, blocks, 0.0F, blocks, HITBOX_MARGIN, MOB_FACTOR);
    }
}
