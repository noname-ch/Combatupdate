package ch.bbcag.combatupdate.client;

import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.ThrownItemRenderState;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.entity.ScarletSpear;

// Draws a thrown Scarlet Devil as the item itself, pointing the way it is flying, rather than the flat
// face-the-camera sprite ThrownItemRenderer would give it - a spear has to look like it is going
// somewhere.
//
// A flat sprite seen edge-on vanishes, so it is drawn twice, the second copy turned a quarter round
// the shaft: a cross that reads as a spear from any side.
public final class ScarletSpearRenderer extends EntityRenderer<ScarletSpear, ScarletSpearRenderer.State> {
    // The direction the tip points in the item's own texture: up and to the right, like a sword's.
    private static final Vector3f TIP = new Vector3f(1.0F, 1.0F, 0.0F).normalize();

    // How many blocks long the item's one-block sprite is drawn, and how much bigger a Gungnir is.
    private static final float SCALE = 2.0F;
    private static final float GUNGNIR_SCALE = 3.5F;

    private final ItemModelResolver itemModelResolver;
    // Made on first use: renderers are built during the first resource reload, before item components
    // are bound, and an ItemStack cannot be made until they are.
    private ItemStack spear;

    public static final class State extends ThrownItemRenderState {
        private final Vector3f direction = new Vector3f(0.0F, 0.0F, 1.0F);
        private float scale = SCALE;
        private float halfHeight;
    }

    public ScarletSpearRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.itemModelResolver = context.getItemModelResolver();
    }

    @Override
    protected int getBlockLightLevel(ScarletSpear entity, BlockPos blockPos) {
        // It is made of light.
        return 15;
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(ScarletSpear entity, State state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        if (this.spear == null) {
            this.spear = new ItemStack(CombatUpdate.SCARLET_DEVIL.get());
        }
        this.itemModelResolver.updateForNonLiving(state.item, this.spear, ItemDisplayContext.NONE, entity);
        Vec3 velocity = entity.getDeltaMovement();
        if (velocity.lengthSqr() > 1.0E-6) {
            Vec3 heading = velocity.normalize();
            state.direction.set((float) heading.x, (float) heading.y, (float) heading.z);
        }

        state.scale = entity.isGungnir() ? GUNGNIR_SCALE : SCALE;
        state.halfHeight = entity.getBbHeight() / 2.0F;
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.translate(0.0F, state.halfHeight, 0.0F);
        poseStack.rotate(new Quaternionf().rotationTo(TIP, state.direction));
        poseStack.scale(state.scale, state.scale, state.scale);
        state.item.submit(poseStack, submitNodeCollector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        poseStack.rotate(new Quaternionf().rotationAxis((float) (Math.PI / 2.0), TIP));
        state.item.submit(poseStack, submitNodeCollector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        poseStack.popPose();
        super.submit(state, poseStack, submitNodeCollector, camera);
    }

    @Override
    protected AABB getBoundingBoxForCulling(ScarletSpear entity, float partialTicks) {
        // The sprite is drawn far longer than the entity's box.
        return super.getBoundingBoxForCulling(entity, partialTicks).inflate(entity.isGungnir() ? GUNGNIR_SCALE : SCALE);
    }
}
