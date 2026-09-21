package ch.bbcag.combatupdate;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterRangeSelectItemModelPropertyEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

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
}
