package ch.bbcag.gipfeliarmy;

import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.client.renderer.entity.TntRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import ch.bbcag.gipfeliarmy.client.ArmyClient;
import ch.bbcag.gipfeliarmy.client.GipfaeliSight;
import ch.bbcag.gipfeliarmy.client.GipfaeliSoldierRenderer;
import ch.bbcag.gipfeliarmy.client.TerritoryClient;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = GipfeliArmyMod.MODID, dist = Dist.CLIENT)
// You can use EventBusSubscriber to automatically register all static methods in the class annotated with @SubscribeEvent
@EventBusSubscriber(modid = GipfeliArmyMod.MODID, value = Dist.CLIENT)
public final class GipfeliArmyClient {
    public GipfeliArmyClient(ModContainer container) {
        // Allows NeoForge to create a config screen for this mod's configs.
        // The config screen is accessed by going to the Mods screen > clicking on your mod > clicking on config.
        // Do not forget to add translations for your config options to the en_us.json file.
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);

        // Hands the territory packets to the screen; see TerritoryClient.
        TerritoryClient.wire();
        // And the army's: the roster and the order to open the army screen; see ArmyClient.
        ArmyClient.wire();
    }

    // Nothing here does what its looks suggest, so every item says what its buttons do.
    @SubscribeEvent
    static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        // The launcher has two actions on the one button and no way to guess at either, so it says so.
        if (stack.is(GipfeliArmyMod.GIPFAELI_LAUNCHER.get())) {
            event.getToolTip().add(Component.translatable("item.gipfeliarmy.gipfaeli_launcher.sight")
                    .withStyle(ChatFormatting.DARK_GRAY));
            event.getToolTip().add(Component.translatable("item.gipfeliarmy.gipfaeli_launcher.fire")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        // And the rig has three presses in an order, which is less guessable still.
        if (stack.is(GipfeliArmyMod.GIPFAELI_LAUNCH_RIG.get())) {
            event.getToolTip().add(Component.translatable("item.gipfeliarmy.gipfaeli_launch_rig.call")
                    .withStyle(ChatFormatting.DARK_GRAY));
            event.getToolTip().add(Component.translatable("item.gipfeliarmy.gipfaeli_launch_rig.place")
                    .withStyle(ChatFormatting.DARK_GRAY));
            event.getToolTip().add(Component.translatable("item.gipfeliarmy.gipfaeli_launch_rig.reaim")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        // The flag has three orders on the one button and no way to guess at any of them.
        if (stack.is(GipfeliArmyMod.GIPFAELI_COMMAND_FLAG.get())) {
            event.getToolTip().add(Component.translatable("item.gipfeliarmy.gipfaeli_command_flag.recruit")
                    .withStyle(ChatFormatting.DARK_GRAY));
            event.getToolTip().add(Component.translatable("item.gipfeliarmy.gipfaeli_command_flag.attack")
                    .withStyle(ChatFormatting.DARK_GRAY));
            event.getToolTip().add(Component.translatable("item.gipfeliarmy.gipfaeli_command_flag.menu")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        // And a gun says what it is for, since what separates the three of them is entirely in how
        // they shoot rather than in anything you can see on them.
        if (stack.is(GipfeliArmyMod.LETONY_MATE_AK47.get())) {
            event.getToolTip().add(Component.translatable("item.gipfeliarmy.letony_mate_ak47.desc")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        if (stack.is(GipfeliArmyMod.GIPFAELI_SHOTGUN.get())) {
            event.getToolTip().add(Component.translatable("item.gipfeliarmy.gipfaeli_shotgun.desc")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        if (stack.is(GipfeliArmyMod.GIPFAELI_MARKSMAN.get())) {
            event.getToolTip().add(Component.translatable("item.gipfeliarmy.gipfaeli_marksman.desc")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        if (stack.is(GipfeliArmyMod.GIPFAELI_HEAVY_MG.get())) {
            event.getToolTip().add(Component.translatable("item.gipfeliarmy.gipfaeli_heavy_mg.desc")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        if (stack.is(GipfeliArmyMod.GIPFAELI_GUARD_POST_ITEM.get())) {
            event.getToolTip().add(Component.translatable("block.gipfeliarmy.gipfaeli_guard_post.desc")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        if (stack.is(GipfeliArmyMod.GIPFAELI_WAR_BANNER.get())) {
            event.getToolTip().add(Component.translatable("item.gipfeliarmy.gipfaeli_war_banner.desc")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        // The explosives say what they do, since the grenade is the first pastry here that goes off
        // on a timer rather than on arrival, and the Ultra is not just a bigger block of the other.
        if (stack.is(GipfeliArmyMod.GIPFAELI_GRENADE.get())) {
            event.getToolTip().add(Component.translatable("item.gipfeliarmy.gipfaeli_grenade.desc")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        if (stack.is(GipfeliArmyMod.GIPFAELI_TNT_ITEM.get())) {
            event.getToolTip().add(Component.translatable("block.gipfeliarmy.gipfaeli_tnt.desc")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }

        if (stack.is(GipfeliArmyMod.GIPFAELI_ULTRA_TNT_ITEM.get())) {
            event.getToolTip().add(Component.translatable("block.gipfeliarmy.gipfaeli_ultra_tnt.desc")
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        // A launched pastry is drawn at its own size and lit by the world: it does not glow in the dark.
        event.registerEntityRenderer(GipfeliArmyMod.GIPFAELI_ROCKET.get(), context -> new ThrownItemRenderer<>(context, 1.0F, false));
        // The same pastry, sitting on its pad and then arcing over: a bomb is a Gipfaeli taking the
        // slow way round, and it has no business looking like anything else.
        event.registerEntityRenderer(GipfeliArmyMod.GIPFAELI_BOMB.get(), context -> new ThrownItemRenderer<>(context, 1.0F, false));
        // A round out of a Gipfaeli gun: the same pastry again, small and quick enough to read as a
        // tracer rather than as lunch going past.
        event.registerEntityRenderer(GipfeliArmyMod.GIPFAELI_BULLET.get(), context -> new ThrownItemRenderer<>(context, 0.5F, false));
        event.registerEntityRenderer(GipfeliArmyMod.GIPFAELI_SOLDIER.get(), GipfaeliSoldierRenderer::new);
        // The grenade is drawn as its own item, pin and all, tumbling the way a thrown thing does.
        event.registerEntityRenderer(GipfeliArmyMod.GIPFAELI_GRENADE_ENTITY.get(), context -> new ThrownItemRenderer<>(context, 1.0F, false));
        // Lit Gipfaeli TNT is drawn by vanilla's own TNT renderer, which draws whatever block state
        // the entity carries - so the plain and Ultra kinds each come out looking like their block.
        event.registerEntityRenderer(GipfeliArmyMod.GIPFAELI_TNT.get(), TntRenderer::new);
    }

    // The launcher's sight: the view pulls in while something is locked, and the reticle goes over it.
    @SubscribeEvent
    static void onComputeFov(ViewportEvent.ComputeFov event) {
        event.setFOV(GipfaeliSight.computeFov(event.getFOV()));
    }

    @SubscribeEvent
    static void onRenderGui(RenderGuiEvent.Post event) {
        GipfaeliSight.render(event.getGuiGraphics());
    }

    @SubscribeEvent
    static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        TerritoryClient.registerKeys(event);
        ArmyClient.registerKeys(event);
    }

    // The territory and army keys are read once a tick rather than on the key event, the way vanilla
    // reads its own, so a press held across a lag spike still opens the screen exactly once.
    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        TerritoryClient.tick();
        ArmyClient.tick();
    }
}
