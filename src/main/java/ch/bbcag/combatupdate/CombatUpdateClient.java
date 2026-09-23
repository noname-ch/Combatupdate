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
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterRangeSelectItemModelPropertyEvent;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.extensions.common.RegisterClientExtensionsEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.client.renderstate.AvatarRenderStateModifier;
import net.neoforged.neoforge.client.renderstate.RegisterRenderStateModifiersEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import ch.bbcag.combatupdate.client.BatteringRamHelmetModel;
import ch.bbcag.combatupdate.client.ElytraOrientation;
import ch.bbcag.combatupdate.client.ShortbowPullProperty;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = CombatUpdate.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = CombatUpdate.MODID, value = Dist.CLIENT)
public final class CombatUpdateClient {
    // Whether onRenderPlayerPre pushed a pose that onRenderPlayerPost still has to pop.
    private static boolean banked;

    public CombatUpdateClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onRegisterRangeSelectItemModelProperty(RegisterRangeSelectItemModelPropertyEvent event) {
        event.register(Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "shortbow_pull"), ShortbowPullProperty.MAP_CODEC);
    }

    // Vanilla lists an enchantment by name and level and stops there, which is fine for Sharpness
    // and no use at all for one whose whole behaviour is this mod's invention. Every enchantment of
    // ours gets a line under the name saying what it actually does.
    //
    // Driven off the namespace rather than a list of keys, so a new enchantment only has to add its
    // .desc line to the language file to be described here; and off whether that line exists, so one
    // that hasn't got round to it shows nothing rather than a raw translation key.
    @SubscribeEvent
    static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        describeAll(event, stack.getOrDefault(DataComponents.ENCHANTMENTS, ItemEnchantments.EMPTY));
        // An enchanted book keeps what it teaches in a different component from what it is enchanted
        // with, and a book is exactly where someone reads up on an enchantment.
        describeAll(event, stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY));
    }

    private static void describeAll(ItemTooltipEvent event, ItemEnchantments enchantments) {
        for (Holder<Enchantment> enchantment : enchantments.keySet()) {
            enchantment.unwrapKey()
                    .map(key -> key.identifier())
                    .filter(id -> id.getNamespace().equals(CombatUpdate.MODID))
                    .map(id -> "enchantment." + CombatUpdate.MODID + "." + id.getPath() + ".desc")
                    .filter(Language.getInstance()::has)
                    .ifPresent(line -> event.getToolTip()
                            .add(Component.translatable(line).withStyle(ChatFormatting.DARK_GRAY)));
        }
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // Renders it the same way vanilla renders a Ghast fireball: a spinning icon of its held item (the fire charge).
        event.registerEntityRenderer(CombatUpdate.COMBAT_FIREBALL.get(), context -> new ThrownItemRenderer<>(context, 3.0F, true));
        // At its own size and lit by the world, unlike the fireball: a pastry does not glow in the dark.
        event.registerEntityRenderer(CombatUpdate.GIPFAELI_ROCKET.get(), context -> new ThrownItemRenderer<>(context, 1.0F, false));
    }

    @SubscribeEvent
    static void onRegisterLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(BatteringRamHelmetModel.LAYER, BatteringRamHelmetModel::buildLayerDefinition);
    }

    // A Battering Ram helmet renders flattened on top (see BatteringRamHelmetModel), on every vanilla
    // item a player can actually get the enchantment onto.
    @SubscribeEvent
    static void onRegisterClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(BatteringRamHelmetModel.INSTANCE,
                Items.LEATHER_HELMET, Items.CHAINMAIL_HELMET, Items.IRON_HELMET,
                Items.GOLDEN_HELMET, Items.DIAMOND_HELMET, Items.NETHERITE_HELMET, Items.TURTLE_HELMET);
    }

    // Holding A or D while gliding rolls the player and W or S pitches it, instead of steering on foot
    // (vanilla's glide physics ignore movement input entirely, so this doesn't take anything away), and
    // the camera banks to match.
    @SubscribeEvent
    static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft minecraft = Minecraft.getInstance();
        LivingEntity player = minecraft.player;
        if (player == null || event.getCamera().entity() != player) {
            return;
        }

        // Switching free look off mid-glide goes through stop() rather than straight out, so the
        // orientation is let go of and the camera drops back to level instead of holding its last bank.
        if (!Config.on(Config.ENABLE_FREE_LOOK) || !player.isFallFlying()) {
            ElytraOrientation.stop();
            return;
        }

        ElytraOrientation.ensureActive(player);

        boolean left = minecraft.options.keyLeft.isDown();
        boolean right = minecraft.options.keyRight.isDown();
        boolean noseDown = minecraft.options.keyUp.isDown();
        boolean noseUp = minecraft.options.keyDown.isDown();

        // S pulls the nose up and W pushes it down, the way a flight stick works rather than the way
        // walking does: pulling back climbs.
        int rollDirection = left == right ? 0 : (left ? -1 : 1);
        int pitchDirection = noseUp == noseDown ? 0 : (noseUp ? 1 : -1);

        // Unlike a bank, a pitch swings the nose somewhere new, and the game still runs movement and
        // aim off the entity's own yaw and pitch, so those have to follow it.
        if (ElytraOrientation.advanceKeys(rollDirection, pitchDirection)) {
            ElytraOrientation.writeRotation(player);
        }

        event.setRoll(ElytraOrientation.roll());
    }

    // The render events hand over a snapshot of the entity rather than the entity, so the bank angle
    // has to be stashed on that snapshot here, while we can still tell whose avatar this is.
    @SubscribeEvent
    static void onRegisterRenderStateModifiers(RegisterRenderStateModifiersEvent event) {
        event.registerAvatarEntityModifier(new AvatarRenderStateModifier() {
            @Override
            public <T extends Avatar & ClientAvatarEntity> void accept(T avatar, AvatarRenderState renderState) {
                boolean banking = Config.on(Config.ENABLE_FREE_LOOK)
                        && avatar == Minecraft.getInstance().player
                        && avatar.isFallFlying();
                renderState.setRenderData(ElytraOrientation.RENDER_ROLL, banking ? ElytraOrientation.roll() : null);
                if (!banking) {
                    return;
                }

                // Draw the body along the nose. Vanilla draws it at yBodyRot instead, which only ever
                // eases 30% of the way towards the head each tick and is then pinned to within 50
                // degrees of it (LivingEntity#tickHeadTurn), so under free look the body swims along
                // behind the camera through every turn - and every time a loop over the top flips the
                // yaw by 180 degrees, it spends half a second spiralling round to catch up.
                renderState.bodyRot = renderState.yRot;

                // Vanilla also yaws the model by the angle between where it is looking and where it is
                // actually travelling, so it crabs into a turn. That angle is folded into 0-90 degrees
                // (acos of an absolute dot, in AvatarRenderer#extractFlightData), which holds only
                // while pitch cannot pass vertical and you cannot fly backwards. Free look makes both
                // routine, and past 90 degrees apart the angle starts reading backwards and the model
                // snaps about. Nothing here needs it: the body is already on the nose.
                renderState.shouldApplyFlyingYRot = false;
            }
        });
    }

    // Banks the player's own model with the camera, so the body lies over in a turn instead of staying
    // level while the world tilts around it.
    @SubscribeEvent
    static void onRenderPlayerPre(RenderPlayerEvent.Pre<?> event) {
        AvatarRenderState state = event.getRenderState();
        Float roll = state.getRenderData(ElytraOrientation.RENDER_ROLL);
        if (roll == null) {
            return;
        }

        // Eased in by the same factor vanilla eases the body's own pitch in with over the first few
        // ticks of a glide. Until that pitch has arrived the body is not on the nose yet, and rolling
        // it about the nose early would screw the model round its own waist.
        float rollDegrees = roll * state.fallFlyingScale();
        if (rollDegrees == 0.0F) {
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
                (float) Math.toRadians(rollDegrees), (float) nose.x, (float) nose.y, (float) nose.z));
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
}
