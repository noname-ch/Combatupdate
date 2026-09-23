package ch.bbcag.combatupdate.client;

import org.joml.Vector3f;

import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.world.item.ItemStack;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.entity.ScarletSpear;

// Draws a thrown Scarlet Devil as the item itself, tip first (see HeadingItemRenderer), lit from
// within: it is made of light. A Gungnir is the same spear, bigger.
public final class ScarletSpearRenderer extends HeadingItemRenderer<ScarletSpear> {
    // The direction the tip points in the item's own texture: up and to the right, like a sword's.
    private static final Vector3f TIP = new Vector3f(1.0F, 1.0F, 0.0F);

    // How many blocks long the item's one-block sprite is drawn, and how much bigger a Gungnir is.
    private static final float SCALE = 2.0F;
    private static final float GUNGNIR_SCALE = 3.5F;

    // Made on first use: renderers are built during the first resource reload, before item components
    // are bound, and an ItemStack cannot be made until they are.
    private static ItemStack spear;

    public ScarletSpearRenderer(EntityRendererProvider.Context context) {
        super(context, entity -> spear(), TIP, SCALE, false, true);
    }

    private static ItemStack spear() {
        if (spear == null) {
            spear = new ItemStack(CombatUpdate.SCARLET_DEVIL.get());
        }

        return spear;
    }

    @Override
    protected float scale(ScarletSpear entity) {
        return entity.isGungnir() ? GUNGNIR_SCALE : SCALE;
    }
}
