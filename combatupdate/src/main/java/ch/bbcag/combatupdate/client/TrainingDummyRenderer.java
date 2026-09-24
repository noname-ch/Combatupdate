package ch.bbcag.combatupdate.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.entity.TrainingDummy;

// Draws a training dummy: a sack of straw on a wooden post, arms out like a scarecrow, in whatever
// armour it has been dressed in. Baked off the zombie's layers for the reason GipfaeliSoldierRenderer
// gives. A hit makes it wobble on its post the way an armour stand does.
public final class TrainingDummyRenderer extends HumanoidMobRenderer<TrainingDummy, TrainingDummyRenderer.State, TrainingDummyRenderer.Model> {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "textures/entity/training_dummy.png");

    public TrainingDummyRenderer(EntityRendererProvider.Context context) {
        super(context, new Model(context.bakeLayer(ModelLayers.ZOMBIE)), 0.5F);
        ArmorModelSet<HumanoidModel<State>> armour = ArmorModelSet.bake(ModelLayers.ZOMBIE_ARMOR, context.getModelSet(), HumanoidModel::new);
        this.addLayer(new HumanoidArmorLayer<>(this, armour, context.getEquipmentRenderer()));
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(TrainingDummy dummy, State state, float partialTicks) {
        super.extractRenderState(dummy, state, partialTicks);
        state.wiggle = dummy.hurtTime > 0 ? dummy.hurtDuration - dummy.hurtTime + partialTicks : Float.MAX_VALUE;
    }

    @Override
    public Identifier getTextureLocation(State state) {
        return TEXTURE;
    }

    @Override
    protected void setupRotations(State state, PoseStack poseStack, float bodyRot, float entityScale) {
        super.setupRotations(state, poseStack, bodyRot, entityScale);
        if (state.wiggle < 5.0F) {
            poseStack.rotateDegrees(Axis.YP, Mth.sin(state.wiggle / 1.5F * (float) Math.PI) * 3.0F);
        }
    }

    public static final class State extends HumanoidRenderState {
        // Ticks since the last hit, for the wobble; out of range when it has not been hit lately.
        float wiggle = Float.MAX_VALUE;
    }

    // Arms out, a little drooped, and the post planted: whatever vanilla's humanoid would have done
    // with walking or looking is put back, because a dummy does neither.
    public static final class Model extends HumanoidModel<State> {
        private static final float ARMS_OUT = 1.35F;

        public Model(ModelPart root) {
            super(root);
        }

        @Override
        public void setupAnim(State state) {
            super.setupAnim(state);
            this.head.xRot = 0.0F;
            this.head.yRot = 0.0F;
            this.rightArm.xRot = 0.0F;
            this.rightArm.yRot = 0.0F;
            this.rightArm.zRot = ARMS_OUT;
            this.leftArm.xRot = 0.0F;
            this.leftArm.yRot = 0.0F;
            this.leftArm.zRot = -ARMS_OUT;
            this.rightLeg.xRot = 0.0F;
            this.leftLeg.xRot = 0.0F;
        }
    }
}
