package ch.bbcag.combatupdate.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.entity.GipfaeliSoldier;

// Draws a soldier of the Gipfaeli army: a plain humanoid in a uniform, with whatever gun it was
// handed in its fist.
//
// The model is vanilla's own humanoid, baked off the zombie's layer because that is the one built
// with exactly the parts a HumanoidModel expects and nothing else. What makes a soldier look like a
// soldier is the texture and the thing it is holding - and the holding comes free, because every
// humanoid renderer already draws the item in a mob's hand.
public final class GipfaeliSoldierRenderer extends HumanoidMobRenderer<GipfaeliSoldier, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {
    private static final Identifier TEXTURE =
            Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "textures/entity/gipfaeli_soldier.png");

    public GipfaeliSoldierRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5F);
    }

    @Override
    public HumanoidRenderState createRenderState() {
        return new HumanoidRenderState();
    }

    @Override
    public Identifier getTextureLocation(HumanoidRenderState state) {
        return TEXTURE;
    }
}
