package ch.bbcag.gipfeliarmy.terraria.client;

import net.minecraft.client.model.EntityModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.MeshTransformer;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import ch.bbcag.gipfeliarmy.GipfeliArmyMod;
import ch.bbcag.gipfeliarmy.terraria.EyeOfCthulhu;
import ch.bbcag.gipfeliarmy.terraria.Plantera;
import ch.bbcag.gipfeliarmy.terraria.TerrariaBoss;
import ch.bbcag.gipfeliarmy.terraria.WallOfFlesh;

// How the three bosses are drawn. Each is a big textured box or two, the way a Ghast is, with a
// second texture for its second phase: Terraria's bosses are sprites, and a box with the sprite
// painted on it is about as close as a block game comes.
public final class BossModels {
    private BossModels() {
    }

    private static Identifier texture(String name) {
        return Identifier.fromNamespaceAndPath(GipfeliArmyMod.MODID, "textures/entity/terraria/" + name + ".png");
    }

    private static ModelLayerLocation layer(String name) {
        return new ModelLayerLocation(Identifier.fromNamespaceAndPath(GipfeliArmyMod.MODID, name), "main");
    }

    public static final class State extends LivingEntityRenderState {
        boolean enraged;
    }

    private abstract static class BossRenderer<T extends TerrariaBoss, M extends EntityModel<? super State>> extends MobRenderer<T, State, M> {
        private final Identifier calm;
        private final Identifier enraged;

        BossRenderer(EntityRendererProvider.Context context, M model, float shadow, String texture) {
            super(context, model, shadow);
            this.calm = texture(texture);
            this.enraged = texture(texture + "_enraged");
        }

        @Override
        public State createRenderState() {
            return new State();
        }

        @Override
        public void extractRenderState(T boss, State state, float partialTicks) {
            super.extractRenderState(boss, state, partialTicks);
            state.enraged = boss.isEnraged();
        }

        @Override
        public Identifier getTextureLocation(State state) {
            return state.enraged ? this.enraged : this.calm;
        }
    }

    // --- Eye of Cthulhu: an eyeball with its nerves trailing out behind ---------------------------

    public static final class EyeModel extends EntityModel<State> {
        public static final ModelLayerLocation LAYER = layer("eye_of_cthulhu");
        private static final int TENDRILS = 5;
        private static final float[][] TENDRIL_AT = {{-4, -4}, {4, -4}, {0, 0}, {-4, 4}, {4, 4}};

        private final ModelPart body;
        private final ModelPart[] tendrils = new ModelPart[TENDRILS];

        public EyeModel(ModelPart root) {
            super(root);
            this.body = root.getChild("body");
            for (int i = 0; i < TENDRILS; i++) {
                this.tendrils[i] = this.body.getChild("tendril" + i);
            }
        }

        public static LayerDefinition createLayer() {
            MeshDefinition mesh = new MeshDefinition();
            PartDefinition root = mesh.getRoot();
            PartDefinition body = root.addOrReplaceChild("body",
                    CubeListBuilder.create().texOffs(0, 0).addBox(-8.0F, -8.0F, -8.0F, 16.0F, 16.0F, 16.0F),
                    PartPose.offset(0.0F, 16.0F, 0.0F));
            for (int i = 0; i < TENDRILS; i++) {
                body.addOrReplaceChild("tendril" + i,
                        CubeListBuilder.create().texOffs(0, 32).addBox(-1.0F, -1.0F, 0.0F, 2.0F, 2.0F, 12.0F),
                        PartPose.offset(TENDRIL_AT[i][0], TENDRIL_AT[i][1], 7.0F));
            }
            return LayerDefinition.create(mesh, 64, 64).apply(MeshTransformer.scaling(2.5F));
        }

        @Override
        public void setupAnim(State state) {
            super.setupAnim(state);
            this.body.xRot = state.xRot * Mth.DEG_TO_RAD;
            for (int i = 0; i < TENDRILS; i++) {
                this.tendrils[i].yRot = 0.3F * Mth.sin(state.ageInTicks * 0.35F + i * 1.3F);
                this.tendrils[i].xRot = 0.25F * Mth.cos(state.ageInTicks * 0.3F + i * 0.9F);
            }
        }
    }

    public static final class EyeRenderer extends BossRenderer<EyeOfCthulhu, EyeModel> {
        public EyeRenderer(EntityRendererProvider.Context context) {
            super(context, new EyeModel(context.bakeLayer(EyeModel.LAYER)), 1.2F, "eye_of_cthulhu");
        }
    }

    // --- Wall of Flesh: a wall of meat with two eyes and a mouth ----------------------------------

    public static final class WallModel extends EntityModel<State> {
        public static final ModelLayerLocation LAYER = layer("wall_of_flesh");

        private final ModelPart upperEye;
        private final ModelPart lowerEye;

        public WallModel(ModelPart root) {
            super(root);
            ModelPart body = root.getChild("body");
            this.upperEye = body.getChild("upper_eye");
            this.lowerEye = body.getChild("lower_eye");
        }

        public static LayerDefinition createLayer() {
            MeshDefinition mesh = new MeshDefinition();
            PartDefinition root = mesh.getRoot();
            PartDefinition body = root.addOrReplaceChild("body",
                    CubeListBuilder.create().texOffs(0, 0).addBox(-16.0F, -64.0F, -16.0F, 32.0F, 64.0F, 32.0F),
                    PartPose.offset(0.0F, 24.0F, 0.0F));
            body.addOrReplaceChild("upper_eye",
                    CubeListBuilder.create().texOffs(0, 96).addBox(-5.0F, -5.0F, -3.0F, 10.0F, 10.0F, 3.0F),
                    PartPose.offset(0.0F, -48.0F, -16.0F));
            body.addOrReplaceChild("lower_eye",
                    CubeListBuilder.create().texOffs(0, 96).addBox(-5.0F, -5.0F, -3.0F, 10.0F, 10.0F, 3.0F),
                    PartPose.offset(0.0F, -16.0F, -16.0F));
            return LayerDefinition.create(mesh, 128, 128).apply(MeshTransformer.scaling(2.5F));
        }

        @Override
        public void setupAnim(State state) {
            super.setupAnim(state);
            // The eyes roll about in their sockets.
            this.upperEye.yRot = 0.2F * Mth.sin(state.ageInTicks * 0.07F);
            this.upperEye.xRot = 0.15F * Mth.cos(state.ageInTicks * 0.05F);
            this.lowerEye.yRot = 0.2F * Mth.sin(state.ageInTicks * 0.06F + 2.0F);
            this.lowerEye.xRot = 0.15F * Mth.cos(state.ageInTicks * 0.08F + 1.0F);
            float heave = 1.0F + 0.015F * Mth.sin(state.ageInTicks * (state.enraged ? 0.5F : 0.25F));
            this.root().xScale = heave;
            this.root().zScale = heave;
        }
    }

    public static final class WallRenderer extends BossRenderer<WallOfFlesh, WallModel> {
        public WallRenderer(EntityRendererProvider.Context context) {
            super(context, new WallModel(context.bakeLayer(WallModel.LAYER)), 2.5F, "wall_of_flesh");
        }
    }

    // --- Plantera: a pink bulb with a jaw that opens in the second phase --------------------------

    public static final class PlanteraModel extends EntityModel<State> {
        public static final ModelLayerLocation LAYER = layer("plantera");
        private static final int VINES = 3;

        private final ModelPart body;
        private final ModelPart upperJaw;
        private final ModelPart lowerJaw;
        private final ModelPart[] vines = new ModelPart[VINES];

        public PlanteraModel(ModelPart root) {
            super(root);
            this.body = root.getChild("body");
            this.upperJaw = this.body.getChild("upper_jaw");
            this.lowerJaw = this.body.getChild("lower_jaw");
            for (int i = 0; i < VINES; i++) {
                this.vines[i] = this.body.getChild("vine" + i);
            }
        }

        public static LayerDefinition createLayer() {
            MeshDefinition mesh = new MeshDefinition();
            PartDefinition root = mesh.getRoot();
            PartDefinition body = root.addOrReplaceChild("body",
                    CubeListBuilder.create().texOffs(0, 0).addBox(-8.0F, -8.0F, -8.0F, 16.0F, 16.0F, 16.0F),
                    PartPose.offset(0.0F, 16.0F, 0.0F));
            body.addOrReplaceChild("upper_jaw",
                    CubeListBuilder.create().texOffs(0, 32).addBox(-7.0F, -4.0F, -10.0F, 14.0F, 4.0F, 10.0F),
                    PartPose.offset(0.0F, 0.0F, -6.0F));
            body.addOrReplaceChild("lower_jaw",
                    CubeListBuilder.create().texOffs(0, 46).addBox(-7.0F, 0.0F, -10.0F, 14.0F, 4.0F, 10.0F),
                    PartPose.offset(0.0F, 0.0F, -6.0F));
            float[][] at = {{-4, -3}, {4, -3}, {0, 4}};
            for (int i = 0; i < VINES; i++) {
                body.addOrReplaceChild("vine" + i,
                        CubeListBuilder.create().texOffs(64, 0).addBox(-1.0F, -1.0F, 0.0F, 2.0F, 2.0F, 16.0F),
                        PartPose.offset(at[i][0], at[i][1], 7.0F));
            }
            return LayerDefinition.create(mesh, 128, 64).apply(MeshTransformer.scaling(2.0F));
        }

        @Override
        public void setupAnim(State state) {
            super.setupAnim(state);
            this.body.xRot = state.xRot * Mth.DEG_TO_RAD;
            float open = state.enraged
                    ? 0.35F + 0.35F * Mth.sin(state.ageInTicks * 0.6F)
                    : 0.05F + 0.05F * Mth.sin(state.ageInTicks * 0.1F);
            this.upperJaw.xRot = -open;
            this.lowerJaw.xRot = open;
            for (int i = 0; i < VINES; i++) {
                this.vines[i].yRot = 0.35F * Mth.sin(state.ageInTicks * 0.2F + i * 2.1F);
                this.vines[i].xRot = 0.3F * Mth.cos(state.ageInTicks * 0.17F + i);
            }
        }
    }

    public static final class PlanteraRenderer extends BossRenderer<Plantera, PlanteraModel> {
        public PlanteraRenderer(EntityRendererProvider.Context context) {
            super(context, new PlanteraModel(context.bakeLayer(PlanteraModel.LAYER)), 1.2F, "plantera");
        }
    }
}
