package ch.bbcag.combatupdate.client;

import org.joml.Vector3f;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.item.ItemStack;

import ch.bbcag.combatupdate.entity.ZenithBlade;

// Draws a Zenith phantom as the sword it is a ghost of, tip first along its loop (see
// HeadingItemRenderer), lit from within the way Terraria's phantoms glow.
public final class ZenithBladeRenderer extends HeadingItemRenderer<ZenithBlade> {
    // The direction the tip points in a sword's own texture: up and to the right.
    private static final Vector3f TIP = new Vector3f(1.0F, 1.0F, 0.0F);

    // How many blocks long the one-block sprite is drawn.
    private static final float SCALE = 1.5F;

    // Made on first use, for the reason ScarletSpearRenderer gives.
    private static ItemStack[] swords;

    public ZenithBladeRenderer(EntityRendererProvider.Context context) {
        super(context, entity -> sword(entity.sword()), TIP, SCALE, false, true);
    }

    private static ItemStack sword(int sword) {
        if (swords == null) {
            swords = new ItemStack[ZenithBlade.SWORD_COUNT];
            for (int i = 0; i < swords.length; i++) {
                swords[i] = new ItemStack(ZenithBlade.swordItem(i));
            }
        }

        return swords[sword];
    }
}
