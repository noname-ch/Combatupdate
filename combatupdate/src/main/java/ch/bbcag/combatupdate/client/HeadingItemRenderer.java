package ch.bbcag.combatupdate.client;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

// Draws a projectile as its item, pointed the way it is going, instead of the flat face-the-camera
// sprite ThrownItemRenderer gives everything - a thing in flight has to look like it is going
// somewhere. A rocket flies nose first, a crumb streaks, a spear leads with its tip.
//
// A flat sprite seen edge-on vanishes, so it is drawn twice, the second copy turned a quarter round
// its length: a cross that reads as a solid from any side.
//
// The heading is taken off where the entity was a tick ago rather than off its velocity, because a
// bomb walked along its arc by hand has no velocity to speak of and still has somewhere it is going.
// While it is not going anywhere it keeps its last heading, or the upright it started with, which is
// how a rocket sits on its pad.
public class HeadingItemRenderer<T extends Entity> extends EntityRenderer<T, HeadingItemRenderer.State> {
    // Below this a tick's travel is too short to take a direction from.
    private static final double EPSILON = 1.0E-4;

    // How fast a spinning projectile rolls about its own length, in radians a tick.
    private static final float ROLL_RATE = 0.35F;

    private final ItemModelResolver itemModelResolver;
    private final Function<T, ItemStack> item;
    // The direction the item's own texture points in: where the nose is on the sprite.
    private final Vector3f tip;
    private final float scale;
    private final boolean spin;
    private final boolean lit;

    public static class State extends ThrownItemRenderState {
        final Vector3f direction = new Vector3f(0.0F, 1.0F, 0.0F);
        float scale;
        float roll;
        float halfHeight;
    }

    // Lightweight per-entity memory of the last heading, so a projectile that stops still points
    // where it was going instead of snapping upright. Keyed on the entity id, and dropped with it.
    private final Map<Integer, Vector3f> headings = new HashMap<>();

    public HeadingItemRenderer(EntityRendererProvider.Context context, Function<T, ItemStack> item, Vector3f tip,
            float scale, boolean spin, boolean lit) {
        super(context);
        this.itemModelResolver = context.getItemModelResolver();
        this.item = item;
        this.tip = new Vector3f(tip).normalize();
        this.scale = scale;
        this.spin = spin;
        this.lit = lit;
    }

    // Upright on the sprite, at the given size, plain and lit by the world: a rocket, a bomb, a crumb.
    public static <T extends Entity> HeadingItemRenderer<T> of(EntityRendererProvider.Context context,
            Function<T, ItemStack> item, float scale, boolean spin) {
        return new HeadingItemRenderer<>(context, item, new Vector3f(0.0F, 1.0F, 0.0F), scale, spin, false);
    }

    // How big this one is drawn; a subclass can size each entity for itself.
    protected float scale(T entity) {
        return this.scale;
    }

    @Override
    protected int getBlockLightLevel(T entity, BlockPos blockPos) {
        return this.lit ? 15 : super.getBlockLightLevel(entity, blockPos);
    }

    @Override
    public State createRenderState() {
        return new State();
    }

    @Override
    public void extractRenderState(T entity, State state, float partialTicks) {
        super.extractRenderState(entity, state, partialTicks);
        this.itemModelResolver.updateForNonLiving(state.item, this.item.apply(entity), ItemDisplayContext.NONE, entity);

        Vector3f heading = this.headings.computeIfAbsent(entity.getId(), id -> new Vector3f(this.tip));
        Vec3 travel = new Vec3(entity.getX() - entity.xo, entity.getY() - entity.yo, entity.getZ() - entity.zo);
        if (travel.lengthSqr() < EPSILON * EPSILON) {
            travel = entity.getDeltaMovement();
        }

        if (travel.lengthSqr() >= EPSILON * EPSILON) {
            Vec3 unit = travel.normalize();
            heading.set((float) unit.x, (float) unit.y, (float) unit.z);
        }

        state.direction.set(heading);
        state.scale = this.scale(entity);
        state.roll = this.spin ? (entity.tickCount + partialTicks) * ROLL_RATE : 0.0F;
        state.halfHeight = entity.getBbHeight() / 2.0F;
        if (entity.isRemoved()) {
            this.headings.remove(entity.getId());
        }
    }

    @Override
    public void submit(State state, PoseStack poseStack, SubmitNodeCollector submitNodeCollector, CameraRenderState camera) {
        poseStack.pushPose();
        poseStack.translate(0.0F, state.halfHeight, 0.0F);
        poseStack.rotate(new Quaternionf().rotationTo(this.tip, state.direction));
        if (state.roll != 0.0F) {
            poseStack.rotate(new Quaternionf().rotationAxis(state.roll, this.tip));
        }

        poseStack.scale(state.scale, state.scale, state.scale);
        state.item.submit(poseStack, submitNodeCollector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        poseStack.rotate(new Quaternionf().rotationAxis((float) (Math.PI / 2.0), this.tip));
        state.item.submit(poseStack, submitNodeCollector, state.lightCoords, OverlayTexture.NO_OVERLAY, state.outlineColor);
        poseStack.popPose();
        super.submit(state, poseStack, submitNodeCollector, camera);
    }

    @Override
    protected AABB getBoundingBoxForCulling(T entity, float partialTicks) {
        // The sprite is drawn far longer than the entity's box.
        return super.getBoundingBoxForCulling(entity, partialTicks).inflate(this.scale(entity));
    }
}
