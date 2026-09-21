package ch.bbcag.combatupdate;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.ClientAvatarEntity;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterRangeSelectItemModelPropertyEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;

import ch.bbcag.combatupdate.client.ElytraOrientation;
import ch.bbcag.combatupdate.client.ShortbowPullProperty;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = CombatUpdate.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = CombatUpdate.MODID, value = Dist.CLIENT)
public class CombatUpdateClient {
    public CombatUpdateClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        // Some client setup code
        CombatUpdate.LOGGER.info("HELLO FROM CLIENT SETUP");
        CombatUpdate.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }

    @SubscribeEvent
    static void onRegisterRangeSelectItemModelProperty(RegisterRangeSelectItemModelPropertyEvent event) {
        event.register(Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "shortbow_pull"), ShortbowPullProperty.MAP_CODEC);
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // Renders it the same way vanilla renders a Ghast fireball: a spinning icon of its held item (the fire charge).
        event.registerEntityRenderer(CombatUpdate.COMBAT_FIREBALL.get(), context -> new ThrownItemRenderer<>(context, 3.0F, true));
    }

    // Holding A or D while gliding rolls the player instead of strafing (vanilla's glide physics ignore
    // strafe input entirely, so this doesn't take anything away), and the camera banks to match.
    @SubscribeEvent
    static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft minecraft = Minecraft.getInstance();
        LivingEntity player = minecraft.player;
        if (player == null || event.getCamera().entity() != player) {
            return;
        }

        if (!player.isFallFlying()) {
            ElytraOrientation.stop();
            return;
        }

        ElytraOrientation.ensureActive(player);

        boolean left = minecraft.options.keyLeft.isDown();
        boolean right = minecraft.options.keyRight.isDown();
        ElytraOrientation.advanceRoll(left == right ? 0 : (left ? -1 : 1));

        event.setRoll(ElytraOrientation.roll());
    }

    // The render events hand over a snapshot of the entity rather than the entity, so the bank angle
    // has to be stashed on that snapshot here, while we can still tell whose avatar this is.
    @SubscribeEvent
    static void onRegisterRenderStateModifiers(RegisterRenderStateModifiersEvent event) {
        event.registerAvatarEntityModifier(new AvatarRenderStateModifier() {
            @Override
            public <T extends Avatar & ClientAvatarEntity> void accept(T avatar, AvatarRenderState renderState) {
                boolean banking = avatar == Minecraft.getInstance().player && avatar.isFallFlying();
                renderState.setRenderData(ElytraOrientation.RENDER_ROLL, banking ? ElytraOrientation.roll() : null);
            }
        });
    }

    // Banks the player's own model with the camera, so the body lies over in a turn instead of staying
    // level while the world tilts around it.
    @SubscribeEvent
    static void onRenderPlayerPre(RenderPlayerEvent.Pre<?> event) {
        AvatarRenderState state = event.getRenderState();
        Float roll = state.getRenderData(ElytraOrientation.RENDER_ROLL);
        if (roll == null || roll == 0.0F) {
            return;
        }

        // Rotation happens before the renderer moves into model space, so the pose is still lined up
        // with the world and the bank is taken about the nose direction in world terms.
        Vec3 nose = Entity.calculateViewVector(state.xRot, state.yRot);
        float pivot = state.boundingBoxHeight * 0.5F;

        PoseStack poseStack = event.getPoseStack();
        poseStack.pushPose();
        poseStack.translate(0.0F, pivot, 0.0F);
        poseStack.mulPose(new Matrix4f().rotation(
                (float) Math.toRadians(roll), (float) nose.x, (float) nose.y, (float) nose.z));
        poseStack.translate(0.0F, -pivot, 0.0F);
        banked = true;
    }

    @SubscribeEvent
    static void onRenderPlayerPost(RenderPlayerEvent.Post<?> event) {
        if (banked) {
            event.getPoseStack().popPose();
            banked = false;
        }
    }

    private static boolean banked;
}
