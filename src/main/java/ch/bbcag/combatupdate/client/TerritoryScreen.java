package ch.bbcag.combatupdate.client;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.territory.TerritoryNetwork.Action;
import ch.bbcag.combatupdate.territory.TerritoryNetwork.ActionRequest;
import ch.bbcag.combatupdate.territory.TerritoryNetwork.ChunkInfoPayload;
import ch.bbcag.combatupdate.territory.TerritoryNetwork.ChunkInfoRequest;
import ch.bbcag.combatupdate.territory.TerritoryNetwork.MapPayload;
import ch.bbcag.combatupdate.territory.TerritoryNetwork.MapRequest;

// The territory screen: a map of the chunks around the player with every claim coloured in, an
// overview of whichever chunk is picked, and the buttons that claim, give up or capture it.
//
// The map is a texture rebuilt whenever the server sends a new one, then drawn in one call, rather
// than twenty thousand little rectangles every frame. Everything on top of it (claims, the
// selection, the player) is a handful of fills and is drawn live.
//
// The screen does not pause the game: a capture counts down while it is open, and that is
// exactly what somebody watching the screen wants to see.
public final class TerritoryScreen extends Screen {
    private static final Identifier MAP_TEXTURE = Identifier.fromNamespaceAndPath(CombatUpdate.MODID, "territory_map");

    private static final int MAP_X = 10;
    private static final int MAP_Y = 24;
    private static final int CHUNK = 16;
    private static final int PANEL_GAP = 12;
    private static final int LINE = 10;

    private static final int COLOUR_TEXT = 0xFFFFFFFF;
    private static final int COLOUR_MUTED = 0xFFA0A0A0;
    private static final int COLOUR_OWN = 0x6040FF40;
    private static final int COLOUR_OTHER = 0x60FF4040;
    private static final int COLOUR_CONTESTED = 0x70FFA020;
    private static final int COLOUR_GRID = 0x28000000;
    private static final int COLOUR_SELECTED = 0xFFFFFFFF;
    private static final int COLOUR_PLAYER = 0xFF40C0FF;
    private static final int COLOUR_UNLOADED = 0xFF1C1C1C;
    private static final int COLOUR_FRAME = 0xFF000000;

    // How often the map is asked for again while the screen is open, so claims and captures made
    // by others show up without a button press.
    private static final int REFRESH_TICKS = 40;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private @Nullable MapPayload map;
    private @Nullable ChunkInfoPayload info;
    private @Nullable DynamicTexture texture;
    private int textureSize;

    private int selectedX;
    private int selectedZ;
    private boolean selectionMade;
    private int ticks;

    private Button actionButton;
    private Button unclaimButton;

    public TerritoryScreen() {
        super(Component.translatable("combatupdate.territory.title"));
    }

    @Override
    protected void init() {
        LocalPlayer player = minecraft.player;
        if (!selectionMade && player != null) {
            ChunkPos here = player.chunkPosition();
            selectedX = here.x();
            selectedZ = here.z();
            selectionMade = true;
        }

        int y = height - 26;
        actionButton = addRenderableWidget(Button.builder(Component.empty(), button -> onAction())
                .bounds(MAP_X, y, 96, 20).build());
        unclaimButton = addRenderableWidget(Button.builder(text("button.unclaim"), button -> send(Action.UNCLAIM))
                .bounds(MAP_X + 100, y, 70, 20).build());
        addRenderableWidget(Button.builder(text("button.refresh"), button -> request())
                .bounds(MAP_X + 174, y, 60, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> onClose())
                .bounds(MAP_X + 238, y, 60, 20).build());

        // Whatever came last time is shown at once, and a fresh copy asked for on top.
        if (map == null && TerritoryClient.map != null) {
            onMap(TerritoryClient.map);
        }
        if (info == null && TerritoryClient.chunkInfo != null) {
            onChunkInfo(TerritoryClient.chunkInfo);
        }
        request();
        updateButtons();
    }

    @Override
    public void tick() {
        if (++ticks % REFRESH_TICKS == 0) {
            request();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void removed() {
        if (texture != null) {
            minecraft.getTextureManager().release(MAP_TEXTURE);
            texture = null;
        }
    }

    // ---- What the server sends ----

    public void onMap(MapPayload payload) {
        map = payload;
        rebuildTexture(payload);
        updateButtons();
    }

    public void onChunkInfo(ChunkInfoPayload payload) {
        if (payload.chunkX() == selectedX && payload.chunkZ() == selectedZ) {
            info = payload;
        }
    }

    private void request() {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        ChunkPos here = player.chunkPosition();
        ClientPacketDistributor.sendToServer(new MapRequest(here.x(), here.z()), new ChunkInfoRequest(selectedX, selectedZ));
    }

    private void send(Action action) {
        ClientPacketDistributor.sendToServer(new ActionRequest(action, selectedX, selectedZ));
    }

    private void rebuildTexture(MapPayload payload) {
        int size = (payload.radius() * 2 + 1) * CHUNK;
        if (texture == null || textureSize != size) {
            texture = new DynamicTexture("combatupdate territory map", size, size, false);
            textureSize = size;
            minecraft.getTextureManager().register(MAP_TEXTURE, texture);
        }

        NativeImage image = texture.getPixels();
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                image.setPixel(x, y, COLOUR_UNLOADED);
            }
        }

        int minChunkX = payload.centerX() - payload.radius();
        int minChunkZ = payload.centerZ() - payload.radius();
        for (MapPayload.Tile tile : payload.tiles()) {
            int baseX = (tile.chunkX() - minChunkX) * CHUNK;
            int baseY = (tile.chunkZ() - minChunkZ) * CHUNK;
            byte[] colors = tile.colors();
            for (int z = 0; z < CHUNK; z++) {
                for (int x = 0; x < CHUNK; x++) {
                    int argb = MapColor.getColorFromPackedId(colors[z * CHUNK + x] & 0xFF);
                    image.setPixel(baseX + x, baseY + z, argb == 0 ? COLOUR_UNLOADED : argb);
                }
            }
        }
        texture.upload();
    }

    // ---- Buttons ----

    private void updateButtons() {
        if (actionButton == null || map == null) {
            return;
        }
        MapPayload.ClaimInfo selected = map.claimAt(selectedX, selectedZ);
        boolean here = standingIn(selectedX, selectedZ);
        boolean mine = selected != null && selected.owner().equals(me());

        if (capturingSelected()) {
            actionButton.setMessage(text("button.cancel_capture"));
            actionButton.active = true;
            actionButton.setTooltip(null);
        } else if (selected == null) {
            actionButton.setMessage(text("button.claim"));
            actionButton.active = here;
            actionButton.setTooltip(here ? null : Tooltip.create(text("button.must_stand")));
        } else if (mine) {
            actionButton.setMessage(text("button.claim"));
            actionButton.active = false;
            actionButton.setTooltip(Tooltip.create(text("button.already_yours")));
        } else {
            actionButton.setMessage(text("button.capture"));
            actionButton.active = here;
            actionButton.setTooltip(Tooltip.create(here
                    ? text("button.capture_hint", map.captureSeconds())
                    : text("button.must_stand")));
        }
        unclaimButton.active = mine;
    }

    private void onAction() {
        if (map == null) {
            return;
        }
        if (capturingSelected()) {
            send(Action.CANCEL_CAPTURE);
        } else if (map.claimAt(selectedX, selectedZ) == null) {
            send(Action.CLAIM);
        } else {
            send(Action.CAPTURE);
        }
    }

    private boolean capturingSelected() {
        return map != null && map.capture() != null
                && map.capture().chunkX() == selectedX && map.capture().chunkZ() == selectedZ;
    }

    private boolean standingIn(int chunkX, int chunkZ) {
        LocalPlayer player = minecraft.player;
        if (player == null) {
            return false;
        }
        ChunkPos here = player.chunkPosition();
        return here.x() == chunkX && here.z() == chunkZ;
    }

    private @Nullable UUID me() {
        LocalPlayer player = minecraft.player;
        return player == null ? null : player.getUUID();
    }

    // ---- Mouse ----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && map != null && overMap(event.x(), event.y())) {
            selectedX = chunkAtX(event.x());
            selectedZ = chunkAtZ(event.y());
            info = null;
            ClientPacketDistributor.sendToServer(new ChunkInfoRequest(selectedX, selectedZ));
            updateButtons();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private int mapSize() {
        return map == null ? 9 * CHUNK : (map.radius() * 2 + 1) * CHUNK;
    }

    private boolean overMap(double x, double y) {
        int size = mapSize();
        return x >= MAP_X && x < MAP_X + size && y >= MAP_Y && y < MAP_Y + size;
    }

    private int chunkAtX(double x) {
        return map.centerX() - map.radius() + (int) ((x - MAP_X) / CHUNK);
    }

    private int chunkAtZ(double y) {
        return map.centerZ() - map.radius() + (int) ((y - MAP_Y) / CHUNK);
    }

    // ---- Drawing ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        graphics.centeredText(font, title, width / 2, 8, COLOUR_TEXT);

        int size = mapSize();
        graphics.fill(MAP_X - 1, MAP_Y - 1, MAP_X + size + 1, MAP_Y + size + 1, COLOUR_FRAME);
        if (texture != null) {
            graphics.blit(RenderPipelines.GUI_TEXTURED, MAP_TEXTURE, MAP_X, MAP_Y, 0.0F, 0.0F, size, size, size, size);
        } else {
            graphics.fill(MAP_X, MAP_Y, MAP_X + size, MAP_Y + size, COLOUR_UNLOADED);
            graphics.centeredText(font, text("loading"), MAP_X + size / 2, MAP_Y + size / 2 - 4, COLOUR_MUTED);
        }

        // Everything laid over the map goes in a stratum of its own, so it sits on top of the
        // texture whatever order the renderer happens to take the two in.
        graphics.nextStratum();
        if (map != null) {
            drawOverlays(graphics, size);
        }

        drawPanel(graphics, size);
        drawFooter(graphics, size);

        if (map != null && overMap(mouseX, mouseY)) {
            drawHover(graphics, mouseX, mouseY);
        }
    }

    private void drawOverlays(GuiGraphicsExtractor graphics, int size) {
        int minChunkX = map.centerX() - map.radius();
        int minChunkZ = map.centerZ() - map.radius();
        UUID me = me();

        for (MapPayload.ClaimInfo claim : map.claims()) {
            int x = MAP_X + (claim.chunkX() - minChunkX) * CHUNK;
            int y = MAP_Y + (claim.chunkZ() - minChunkZ) * CHUNK;
            int colour = claim.capturer() != null ? COLOUR_CONTESTED
                    : claim.owner().equals(me) ? COLOUR_OWN : COLOUR_OTHER;
            graphics.fill(x, y, x + CHUNK, y + CHUNK, colour);
        }

        // A faint grid, so a chunk reads as a chunk even where nothing is claimed.
        for (int i = CHUNK; i < size; i += CHUNK) {
            graphics.fill(MAP_X + i, MAP_Y, MAP_X + i + 1, MAP_Y + size, COLOUR_GRID);
            graphics.fill(MAP_X, MAP_Y + i, MAP_X + size, MAP_Y + i + 1, COLOUR_GRID);
        }

        // The selection, as a frame.
        int sx = MAP_X + (selectedX - minChunkX) * CHUNK;
        int sy = MAP_Y + (selectedZ - minChunkZ) * CHUNK;
        if (sx >= MAP_X && sx < MAP_X + size && sy >= MAP_Y && sy < MAP_Y + size) {
            graphics.fill(sx, sy, sx + CHUNK, sy + 1, COLOUR_SELECTED);
            graphics.fill(sx, sy + CHUNK - 1, sx + CHUNK, sy + CHUNK, COLOUR_SELECTED);
            graphics.fill(sx, sy, sx + 1, sy + CHUNK, COLOUR_SELECTED);
            graphics.fill(sx + CHUNK - 1, sy, sx + CHUNK, sy + CHUNK, COLOUR_SELECTED);
        }

        // The player, as a dot where they stand.
        LocalPlayer player = minecraft.player;
        if (player != null) {
            int px = MAP_X + (player.blockPosition().getX() - minChunkX * CHUNK);
            int py = MAP_Y + (player.blockPosition().getZ() - minChunkZ * CHUNK);
            if (px >= MAP_X && px < MAP_X + size && py >= MAP_Y && py < MAP_Y + size) {
                graphics.fill(px - 2, py - 2, px + 3, py + 3, COLOUR_FRAME);
                graphics.fill(px - 1, py - 1, px + 2, py + 2, COLOUR_PLAYER);
            }
        }
    }

    private void drawPanel(GuiGraphicsExtractor graphics, int size) {
        int x = MAP_X + size + PANEL_GAP;
        int panelWidth = width - x - 10;
        int y = MAP_Y;
        UUID me = me();

        graphics.text(font, text("chunk", selectedX, selectedZ).withStyle(ChatFormatting.BOLD), x, y, COLOUR_TEXT);
        y += LINE;
        graphics.text(font, text("blocks_range", selectedX * CHUNK, selectedX * CHUNK + 15,
                selectedZ * CHUNK, selectedZ * CHUNK + 15), x, y, COLOUR_MUTED);
        y += LINE + 2;

        MapPayload.ClaimInfo claim = map == null ? null : map.claimAt(selectedX, selectedZ);
        if (claim == null) {
            graphics.text(font, text("owner.none").withStyle(ChatFormatting.GRAY), x, y, COLOUR_TEXT);
            y += LINE;
        } else {
            boolean mine = claim.owner().equals(me);
            graphics.text(font, text(mine ? "owner.you" : "owner", claim.ownerName())
                    .withStyle(mine ? ChatFormatting.GREEN : ChatFormatting.RED), x, y, COLOUR_TEXT);
            y += LINE;
            graphics.text(font, text("claimed_at", DATE.format(Instant.ofEpochMilli(claim.claimedAt())
                    .atZone(ZoneId.systemDefault()))), x, y, COLOUR_MUTED);
            y += LINE;
            if (claim.capturer() != null) {
                graphics.text(font, text("contested", claim.capturer()).withStyle(ChatFormatting.GOLD), x, y, COLOUR_TEXT);
                y += LINE;
            }
        }
        if (capturingSelected()) {
            graphics.text(font, text("capturing", map.capture().ticksLeft() / 20).withStyle(ChatFormatting.GOLD), x, y, COLOUR_TEXT);
            y += LINE;
        }

        y += 4;
        graphics.fill(x, y, x + panelWidth, y + 1, COLOUR_MUTED);
        y += 5;

        graphics.text(font, text("overview").withStyle(ChatFormatting.BOLD), x, y, COLOUR_TEXT);
        y += LINE;
        if (info == null) {
            graphics.text(font, text("loading"), x, y, COLOUR_MUTED);
            return;
        }
        if (info.totalBlocks() < 0) {
            graphics.text(font, text("not_loaded"), x, y, COLOUR_MUTED);
            return;
        }
        graphics.text(font, text("total_blocks", String.format("%,d", info.totalBlocks())), x, y, COLOUR_MUTED);
        y += LINE;

        int bottom = height - 30;
        for (ChunkInfoPayload.BlockCount block : info.blocks()) {
            if (y + LINE > bottom) {
                break;
            }
            Component name = BuiltInRegistries.BLOCK.getValue(Identifier.parse(block.blockId())).getName();
            String line = String.format("%,d", block.count()) + " × " + name.getString();
            graphics.text(font, font.plainSubstrByWidth(line, panelWidth), x, y, COLOUR_TEXT);
            y += LINE;
        }
    }

    private void drawFooter(GuiGraphicsExtractor graphics, int size) {
        int y = MAP_Y + size + 4;
        if (map != null) {
            graphics.text(font, text("held", map.ownClaims(), map.maxClaims()), MAP_X, y, COLOUR_MUTED);
        }
        y += LINE;
        graphics.text(font, text("legend"), MAP_X, y, COLOUR_MUTED);
    }

    private void drawHover(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int chunkX = chunkAtX(mouseX);
        int chunkZ = chunkAtZ(mouseY);
        List<Component> lines = new ArrayList<>();
        lines.add(text("chunk", chunkX, chunkZ));
        MapPayload.ClaimInfo claim = map.claimAt(chunkX, chunkZ);
        if (claim == null) {
            lines.add(text("owner.none").withStyle(ChatFormatting.GRAY));
        } else if (claim.owner().equals(me())) {
            lines.add(text("owner.you", claim.ownerName()).withStyle(ChatFormatting.GREEN));
        } else {
            lines.add(text("owner", claim.ownerName()).withStyle(ChatFormatting.RED));
        }
        if (claim != null && claim.capturer() != null) {
            lines.add(text("contested", claim.capturer()).withStyle(ChatFormatting.GOLD));
        }
        lines.add(text("click_to_select").withStyle(ChatFormatting.DARK_GRAY));
        graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
    }

    private static MutableComponent text(String key, Object... args) {
        return Component.translatable("combatupdate.territory.screen." + key, args);
    }
}
