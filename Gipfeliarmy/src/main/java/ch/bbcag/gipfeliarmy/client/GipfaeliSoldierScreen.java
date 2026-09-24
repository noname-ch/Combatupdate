package ch.bbcag.gipfeliarmy.client;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.DyeColor;

import ch.bbcag.gipfeliarmy.GipfaeliArmour;
import ch.bbcag.gipfeliarmy.GipfaeliArmy;
import ch.bbcag.gipfeliarmy.GipfaeliSoldierMenu;
import ch.bbcag.gipfeliarmy.GipfaeliWeapon;
import ch.bbcag.gipfeliarmy.entity.GipfaeliSoldier;

// One soldier's kit bag: the soldier itself turning to follow the mouse, its armour and gun in a
// column of slots beside it, the player's inventory underneath, and the quick buttons (kit,
// armour, colour, rank, posts) in a panel down the right. Drag a helmet onto the helmet slot and
// it is wearing it; drag it back and it is in your pack again.
//
// Drawn without a texture of its own: a bevelled panel and the vanilla slot sprite are all a
// container screen is made of, and this way the panel can be whatever size the buttons need.
public final class GipfaeliSoldierScreen extends AbstractContainerScreen<GipfaeliSoldierMenu> {
    private static final Identifier SLOT_SPRITE = Identifier.withDefaultNamespace("container/slot");

    private static final int WIDTH = 284;
    private static final int HEIGHT = 196;
    private static final int PANEL_X = 178;
    private static final int STATUS_X = 86;
    private static final int STATUS_WIDTH = 86;
    private static final int LINE = 10;

    private static final int ENTITY_X0 = 30;
    private static final int ENTITY_Y0 = 8;
    private static final int ENTITY_X1 = 80;
    private static final int ENTITY_Y1 = 100;

    private static final int COLOUR_PANEL = 0xFFC6C6C6;
    private static final int COLOUR_LIGHT = 0xFFFFFFFF;
    private static final int COLOUR_SHADE = 0xFF555555;
    private static final int COLOUR_FRAME = 0xFF000000;
    private static final int COLOUR_WELL = 0xFF303030;
    private static final int COLOUR_TEXT = 0xFF404040;

    private float xMouse;
    private float yMouse;
    private SoldierControls controls;

    public GipfaeliSoldierScreen(GipfaeliSoldierMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, WIDTH, HEIGHT);
        this.titleLabelX = STATUS_X;
        this.titleLabelY = 8;
        this.inventoryLabelY = GipfaeliSoldierMenu.INVENTORY_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        GipfaeliSoldier soldier = this.menu.soldier();
        if (soldier == null) {
            return;
        }

        this.controls = new SoldierControls(this::addRenderableWidget, this.leftPos + PANEL_X, this.topPos + 8,
                soldier.getUUID(), soldier.commander(), this.menu.posts().size(), SoldierControls.Tab.KIT);

        // The way to the rest of the army: closes the bag properly first, or the server would
        // keep its side of it open.
        String scope = GipfaeliArmy.Scope.squadOf(soldier).token();
        this.addRenderableWidget(Button.builder(Component.translatable("gipfeliarmy.army.screen.button.squad"), button -> {
            this.minecraft.player.closeContainer();
            this.minecraft.gui.setScreen(new ArmyScreen(scope, null));
        }).bounds(this.leftPos + STATUS_X, this.topPos + 84, STATUS_WIDTH, SoldierControls.HEIGHT).build());
    }

    @Override
    protected void containerTick() {
        GipfaeliSoldier soldier = this.menu.soldier();
        if (soldier != null && this.controls != null) {
            this.controls.tick();
            this.controls.update(soldier.commander());
            this.controls.camo(soldier.camo());
            this.controls.squad(soldier.squadNumber());
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        this.xMouse = mouseX;
        this.yMouse = mouseY;
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        int x = this.leftPos;
        int y = this.topPos;
        panel(graphics, x, y, WIDTH, HEIGHT);

        for (Slot slot : this.menu.slots) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, x + slot.x - 1, y + slot.y - 1, 18, 18);
        }

        // The soldier in a dark well, the way the player stands in the inventory.
        graphics.fill(x + ENTITY_X0 - 1, y + ENTITY_Y0 - 1, x + ENTITY_X1 + 1, y + ENTITY_Y1 + 1, COLOUR_FRAME);
        graphics.fill(x + ENTITY_X0, y + ENTITY_Y0, x + ENTITY_X1, y + ENTITY_Y1, COLOUR_WELL);
        GipfaeliSoldier soldier = this.menu.soldier();
        if (soldier != null) {
            InventoryScreen.extractEntityInInventoryFollowsMouse(graphics, x + ENTITY_X0, y + ENTITY_Y0, x + ENTITY_X1, y + ENTITY_Y1,
                    32, 0.0625F, this.xMouse, this.yMouse, soldier);
        }

        // A groove between the bag and the buttons.
        graphics.fill(x + PANEL_X - 4, y + 8, x + PANEL_X - 3, y + GipfaeliSoldierMenu.INVENTORY_Y - 12, COLOUR_SHADE);
        graphics.fill(x + PANEL_X - 3, y + 8, x + PANEL_X - 2, y + GipfaeliSoldierMenu.INVENTORY_Y - 12, COLOUR_LIGHT);

        if (soldier != null && this.controls != null) {
            this.controls.draw(graphics, this.font, mouseX, mouseY, soldier.uniform());
        }
    }

    // Name on top (the title), then what it has and what it is, in the column beside it.
    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        super.extractLabels(graphics, mouseX, mouseY);
        GipfaeliSoldier soldier = this.menu.soldier();
        if (soldier == null) {
            return;
        }

        int y = this.titleLabelY + LINE + 2;
        GipfaeliWeapon kit = soldier.weapon();
        GipfaeliArmour worn = GipfaeliArmour.of(soldier.getItemBySlot(EquipmentSlot.CHEST).getItem());
        DyeColor uniform = soldier.uniform();

        line(graphics, Component.translatable("gipfeliarmy.army.screen.health",
                (int) Math.ceil(soldier.getHealth()), (int) Math.ceil(soldier.getMaxHealth())), y);
        y += LINE;
        line(graphics, kit == null
                ? Component.translatable("gipfeliarmy.army.menu.unarmed")
                : kit.roleName(), y);
        y += LINE;
        line(graphics, Component.translatable("gipfeliarmy.army.screen.armour",
                worn == null ? Component.translatable("gipfeliarmy.army.soldier.none") : worn.displayName()), y);
        y += LINE;
        line(graphics, Component.translatable("gipfeliarmy.army.screen.colour", GipfaeliArmy.colourName(uniform)), y);
        y += LINE;
        line(graphics, Component.translatable(soldier.commander()
                ? "gipfeliarmy.army.screen.rank.commander"
                : "gipfeliarmy.army.screen.rank.soldier").withStyle(ChatFormatting.ITALIC), y);
    }

    private void line(GuiGraphicsExtractor graphics, Component text, int y) {
        graphics.text(this.font, Language.getInstance().getVisualOrder(this.font.substrByWidth(text, STATUS_WIDTH)), STATUS_X, y, COLOUR_TEXT, false);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && this.controls != null && this.controls.click(event.x(), event.y())) {
            return true;
        }

        return super.mouseClicked(event, doubleClick);
    }

    // A vanilla-looking panel: light face, a bright edge top and left, a shadow bottom and right,
    // and a black line round the lot.
    static void panel(GuiGraphicsExtractor graphics, int x, int y, int width, int height) {
        graphics.fill(x - 1, y - 1, x + width + 1, y + height + 1, COLOUR_FRAME);
        graphics.fill(x, y, x + width, y + height, COLOUR_PANEL);
        graphics.fill(x, y, x + width - 1, y + 1, COLOUR_LIGHT);
        graphics.fill(x, y, x + 1, y + height - 1, COLOUR_LIGHT);
        graphics.fill(x + 1, y + height - 1, x + width, y + height, COLOUR_SHADE);
        graphics.fill(x + width - 1, y + 1, x + width, y + height, COLOUR_SHADE);
    }
}
