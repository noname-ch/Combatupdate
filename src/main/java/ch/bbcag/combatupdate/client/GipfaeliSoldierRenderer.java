package ch.bbcag.combatupdate.client;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.entity.GipfaeliSoldier;

// Draws a soldier of the Gipfaeli army: a humanoid in the squad's uniform, in whatever armour it
// was dressed in, with whatever kit it was handed in its fist - and, on parade, with its hands
// behind its back.
//
// The model is vanilla's own humanoid, baked off the zombie's layer because that is the one built
// with exactly the parts a HumanoidModel expects and nothing else. The armour comes off the same
// zombie's armour layers for the same reason. What makes a soldier look like a soldier is the
// texture and the things it is wearing and holding, and vanilla already draws all of those.
//
// The colour is a texture per dye rather than a tint: sixteen small files and a seventeenth for
// the camouflage, and no second render pass to composite an overlay on every soldier every frame.
public final class GipfaeliSoldierRenderer extends HumanoidMobRenderer<GipfaeliSoldier, GipfaeliSoldierRenderer.State, GipfaeliSoldierRenderer.Model> {
    private static final Identifier CAMO = Identifier.fromNamespaceAndPath(CombatUpdate.MODID,
            "textures/entity/gipfaeli_soldier/camo.png");
    private static final Identifier[] UNIFORMS = new Identifier[DyeColor.values().length];

    static {
        for (DyeColor color : DyeColor.values()) {
            UNIFORMS[color.getId()] = Identifier.fromNamespaceAndPath(CombatUpdate.MODID,
                    "textures/entity/gipfaeli_soldier/" + color.getName() + ".png");
        }
    }

    public GipfaeliSoldierRenderer(EntityRendererProvider.Context context) {
        super(context, new Model(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5F);
        ArmorModelSet<HumanoidModel<State>> armour = ArmorModelSet.bake(ModelLayers.ZOMBIE_ARMOR, context.getModelSet(), HumanoidModel::new);
        this.addLayer(new HumanoidArmorLayer<>(this, armour, context.getEquipmentRenderer()));
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(GipfaeliSoldier soldier, State state, float partialTicks) {
        super.extractRenderState(soldier, state, partialTicks);
        state.uniform = soldier.uniform();
        state.atAttention = soldier.stance() == GipfaeliSoldier.Stance.STAND;
        state.armed = !soldier.getMainHandItem().isEmpty();
    }

    @Override
    public Identifier getTextureLocation(State state) {
        return state.uniform == null ? CAMO : UNIFORMS[state.uniform.getId()];
    }

    // The humanoid snapshot plus the three things of ours the renderer needs: which uniform to
    // draw, whether the soldier is on parade, and whether it has something in its hands.
    public static final class State extends HumanoidRenderState {
        @Nullable DyeColor uniform;
        boolean atAttention;
        boolean armed;
    }

    // Vanilla's humanoid with one pose of its own: at ease, hands clasped behind the back, which
    // is how a squad stands when it has been told to stand. Only while standing still and only
    // with empty hands - a soldier holding a rifle behind its back would hold it through its own
    // spine, and one marching swings its arms like anyone.
    public static final class Model extends HumanoidModel<State> {
        private static final float ARMS_BACK = 0.55F;
        private static final float HANDS_IN = 0.3F;

        public Model(ModelPart root) {
            super(root);
        }

        @Override
        public void setupAnim(State state) {
            super.setupAnim(state);
            if (!state.atAttention || state.armed || state.walkAnimationSpeed > 0.15F) {
                return;
            }

            this.rightArm.xRot = ARMS_BACK;
            this.rightArm.yRot = 0.0F;
            this.rightArm.zRot = HANDS_IN;
            this.leftArm.xRot = ARMS_BACK;
            this.leftArm.yRot = 0.0F;
            this.leftArm.zRot = -HANDS_IN;
        }
    }
}
