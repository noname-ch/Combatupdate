package ch.bbcag.combatupdate.client;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.entity.GipfaeliSoldier;

// Draws a soldier of the Gipfaeli army: a plain humanoid in a uniform of the squad's colour, with
// whatever kit it was handed in its fist.
//
// The model is vanilla's own humanoid, baked off the zombie's layer because that is the one built
// with exactly the parts a HumanoidModel expects and nothing else. What makes a soldier look like a
// soldier is the texture and the thing it is holding - and the holding comes free, because every
// humanoid renderer already draws the item in a mob's hand.
//
// The colour is a texture per dye rather than a tint: sixteen small files, and no second render
// pass to composite an overlay on every soldier every frame.
public final class GipfaeliSoldierRenderer extends HumanoidMobRenderer<GipfaeliSoldier, GipfaeliSoldierRenderer.State, HumanoidModel<GipfaeliSoldierRenderer.State>> {
    private static final Identifier[] UNIFORMS = new Identifier[DyeColor.values().length];

    static {
        for (DyeColor color : DyeColor.values()) {
            UNIFORMS[color.getId()] = Identifier.fromNamespaceAndPath(CombatUpdate.MODID,
                    "textures/entity/gipfaeli_soldier/" + color.getName() + ".png");
        }
    }

    public GipfaeliSoldierRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5F);
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(GipfaeliSoldier soldier, State state, float partialTicks) {
        super.extractRenderState(soldier, state, partialTicks);
        state.uniform = soldier.uniform();
    }

    @Override
    public Identifier getTextureLocation(State state) {
        return UNIFORMS[state.uniform.getId()];
    }

    // The humanoid snapshot plus the one thing of ours the renderer needs: which uniform to draw.
    public static final class State extends HumanoidRenderState {
        DyeColor uniform = DyeColor.WHITE;
    }
}
