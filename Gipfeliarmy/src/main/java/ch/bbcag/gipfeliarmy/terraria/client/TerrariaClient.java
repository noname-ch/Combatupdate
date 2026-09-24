package ch.bbcag.gipfeliarmy.terraria.client;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.renderer.entity.ThrownItemRenderer;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;

import ch.bbcag.gipfeliarmy.GipfeliArmyMod;
import ch.bbcag.gipfeliarmy.terraria.TerrariaContent;
import ch.bbcag.gipfeliarmy.terraria.TerrariaNetwork;

// The client half of the Terraria content: the bosses' models, their shots, and the waypoints to
// their lairs. Wired in on its own, so GipfeliArmyClient does not have to know any of it is here.
@EventBusSubscriber(modid = GipfeliArmyMod.MODID, value = Dist.CLIENT)
public final class TerrariaClient {
    static {
        TerrariaNetwork.waypointListener = payload -> WaypointHud.waypoints = List.copyOf(payload.waypoints());
    }

    private TerrariaClient() {
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(TerrariaContent.EYE_OF_CTHULHU.get(), BossModels.EyeRenderer::new);
        event.registerEntityRenderer(TerrariaContent.WALL_OF_FLESH.get(), BossModels.WallRenderer::new);
        event.registerEntityRenderer(TerrariaContent.PLANTERA.get(), BossModels.PlanteraRenderer::new);
        // Lasers glow; seeds and thorns are lit by the world like anything else.
        event.registerEntityRenderer(TerrariaContent.BOSS_BOLT.get(), context -> new ThrownItemRenderer<>(context, 1.0F, true));
    }

    @SubscribeEvent
    static void onRegisterLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(BossModels.EyeModel.LAYER, BossModels.EyeModel::createLayer);
        event.registerLayerDefinition(BossModels.WallModel.LAYER, BossModels.WallModel::createLayer);
        event.registerLayerDefinition(BossModels.PlanteraModel.LAYER, BossModels.PlanteraModel::createLayer);
    }

    // Last, so it sees the field of view the world really is drawn at, zoom and all.
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void onComputeFov(ViewportEvent.ComputeFov event) {
        WaypointHud.renderFov = event.getFOV();
    }

    @SubscribeEvent
    static void onRenderGui(RenderGuiEvent.Post event) {
        WaypointHud.render(event.getGuiGraphics());
    }

    // The prism has no way of saying it must be held down, so it says so here.
    @SubscribeEvent
    static void onItemTooltip(ItemTooltipEvent event) {
        if (event.getItemStack().is(TerrariaContent.LAST_PRISM.get()) || event.getItemStack().is(TerrariaContent.LAST_PRISM_RANDOM.get())) {
            event.getToolTip().add(Component.translatable("item.gipfeliarmy.last_prism.desc").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    @SubscribeEvent
    static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        WaypointHud.waypoints = List.of();
    }
}
