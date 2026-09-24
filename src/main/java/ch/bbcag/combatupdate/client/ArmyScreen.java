package ch.bbcag.combatupdate.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import ch.bbcag.combatupdate.Config;
import ch.bbcag.combatupdate.GipfaeliArmour;
import ch.bbcag.combatupdate.GipfaeliArmy;
import ch.bbcag.combatupdate.GipfaeliArmy.Scope;
import ch.bbcag.combatupdate.GipfaeliArmyNetwork.MarchRequest;
import ch.bbcag.combatupdate.GipfaeliArmyNetwork.Roster;
import ch.bbcag.combatupdate.GipfaeliArmyNetwork.SoldierAction;
import ch.bbcag.combatupdate.GipfaeliArmyNetwork.SoldierOrder;
import ch.bbcag.combatupdate.GipfaeliArmyNetwork.SquadAction;
import ch.bbcag.combatupdate.GipfaeliArmyNetwork.SquadOrder;
import ch.bbcag.combatupdate.GipfaeliAssault;
import ch.bbcag.combatupdate.GipfaeliCamo;
import ch.bbcag.combatupdate.GipfaeliFormation;
import ch.bbcag.combatupdate.GipfaeliWeapon;

// The army screen: every soldier the player has in a list, a tab per squad, and down the right a
// panel for whatever is picked - the squad's orders, kit and armour with nothing picked, or one
// soldier's own buttons with a row picked. Double-click a soldier that is standing near enough
// and its kit bag opens (see GipfaeliSoldierScreen).
//
// It is the territory map's other half: the map has an Army button that lands here with the
// chunk it had selected, and the panel's Send button marches soldiers on that chunk; the Map
// button goes back. Neither screen pauses the game, so a squad that was sent somewhere can be
// watched arriving in the list.
//
// Everything shown is the roster the server last sent (see GipfaeliArmyNetwork.Roster). Every
// button sends an order and the server answers with the roster again; the screen keeps nothing
// of its own but the tab, the selection and the scroll.
public final class ArmyScreen extends Screen {
    private static final int MARGIN = 10;
    private static final int TABS_Y = 20;
    private static final int TAB_WIDTH = 56;
    private static final int TAB_HEIGHT = 14;
    private static final int ROW = 12;
    private static final int LINE = 10;
    private static final int PANE_WIDTH = SoldierControls.WIDTH;
    private static final int BUTTON = SoldierControls.HEIGHT;
    private static final int STEP = SoldierControls.ROW;
    private static final int HALF = 48;
    private static final int SWATCH = 8;

    private static final int COLOUR_TEXT = 0xFFFFFFFF;
    private static final int COLOUR_MUTED = 0xFFA0A0A0;
    private static final int COLOUR_FRAME = 0xFF000000;
    private static final int COLOUR_LIST = 0x60000000;
    private static final int COLOUR_HOVER = 0x20FFFFFF;
    private static final int COLOUR_SELECTED = 0x50FFFFFF;
    private static final int COLOUR_BAR = 0xFFA0A0A0;
    private static final int COLOUR_HEALTH = 0xFF40C040;
    private static final int COLOUR_HURT = 0xFFC04040;
    private static final int COLOUR_HEALTH_BACK = 0xFF303030;

    // How often the roster is asked for again while the screen is open, so soldiers getting
    // shot, arriving somewhere or being recruited show up without a button press.
    private static final int REFRESH_TICKS = 40;
    private static final int CONFIRM_TICKS = 60;
    private static final int SOLDIERS_MAX = 40;

    private @Nullable Roster roster;
    private String scope;
    private @Nullable UUID selected;
    private final @Nullable ChunkPos target;

    // The rows the current tab shows, in roster order.
    private final List<Roster.Entry> shown = new ArrayList<>();
    private int scroll;
    private int ticks;
    private int soldiers = 1;
    private int confirmTicks;

    private int listTop;
    private int listBottom;
    private int listWidth;
    private int paneX;

    private @Nullable SoldierControls controls;
    private @Nullable Button bagButton;
    private @Nullable Button sendButton;
    private @Nullable Button dismissButton;
    private final List<AbstractWidget> orderWidgets = new ArrayList<>();
    private final List<AbstractWidget> kitWidgets = new ArrayList<>();
    private final List<AbstractWidget> armourWidgets = new ArrayList<>();
    private final Button[] squadTabs = new Button[3];
    private int squadTab;
    private SoldierControls.Tab controlsTab = SoldierControls.Tab.KIT;
    private boolean requested;

    // What the assault row would send the squad at, and the pattern the camouflage row would put
    // it in: picked with the arrows, sent with the middle button. Kept across rebuilds.
    private int assaultIndex;
    private int camoIndex = GipfaeliCamo.SNOW.ordinal();
    private @Nullable Button assaultButton;
    private @Nullable Button camoButton;

    // The scope tabs and the word each stands for, for the swatch drawn on each.
    private final Map<Button, String> tabs = new LinkedHashMap<>();

    public ArmyScreen(String scope, @Nullable ChunkPos target) {
        super(Component.translatable("combatupdate.army.screen.title"));
        this.scope = scope;
        this.target = target;
    }

    @Override
    protected void init() {
        if (this.roster == null) {
            this.roster = ArmyClient.roster;
        }

        this.rebuildShown();

        int y = this.height - 26;
        int x = MARGIN;
        if (Config.on(Config.ENABLE_TERRITORY)) {
            this.addRenderableWidget(Button.builder(Component.translatable("combatupdate.army.screen.button.map"),
                    button -> this.minecraft.gui.setScreen(new TerritoryScreen(this.target)))
                    .bounds(x, y, 60, 20).build());
            x += 64;
        }
        this.addRenderableWidget(Button.builder(Component.translatable("combatupdate.army.screen.button.refresh"), button -> ArmyClient.request())
                .bounds(x, y, 60, 20).build());
        x += 64;
        this.addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, button -> this.onClose())
                .bounds(x, y, 60, 20).build());

        int rows = this.buildTabs();
        this.listTop = TABS_Y + rows * (TAB_HEIGHT + 2) + 4;
        this.listBottom = this.height - 32;
        this.paneX = this.width - MARGIN - PANE_WIDTH;
        this.listWidth = this.paneX - MARGIN - MARGIN;

        if (this.selected != null) {
            this.buildSoldierPane();
        } else {
            this.buildSquadPane();
        }

        this.clampScroll();
        if (!this.requested) {
            this.requested = true;
            ArmyClient.request();
        }
    }

    @Override
    public void tick() {
        if (++this.ticks % REFRESH_TICKS == 0) {
            ArmyClient.request();
        }

        if (this.controls != null) {
            this.controls.tick();
        }

        if (this.confirmTicks > 0 && --this.confirmTicks == 0 && this.dismissButton != null) {
            this.dismissButton.setMessage(Component.translatable("combatupdate.army.order.dismiss"));
        }

        if (this.bagButton != null) {
            this.bagButton.active = this.near(this.selectedEntry());
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---- What the server sends ----

    // A roster that changes only numbers - health, who is where - is drawn from directly; one
    // that changes what there are buttons for (a squad gone, a post built, the picked soldier
    // promoted or dismissed) has the widgets built again. Rebuilding on every roster would pull
    // the tabs and the buttons out from under the mouse every two seconds.
    public void onRoster(Roster payload) {
        String before = this.signature();
        this.roster = payload;
        if (this.selected != null && this.entry(this.selected) == null) {
            this.selected = null;
        }

        this.rebuildShown();
        if (!before.equals(this.signature())) {
            this.rebuildWidgets();
            return;
        }

        Roster.Entry picked = this.selectedEntry();
        if (this.controls != null && picked != null) {
            this.controls.camo(GipfaeliCamo.byOrdinal(picked.camo()));
        }

        Map<String, Integer> counts = this.counts();
        for (Map.Entry<Button, String> tab : this.tabs.entrySet()) {
            tab.getKey().setMessage(tabLabel(tab.getValue(), counts.getOrDefault(tab.getValue(), 0)));
        }
        this.clampScroll();
    }

    private String signature() {
        Roster.Entry picked = this.selectedEntry();
        return String.join("|", this.counts().keySet())
                + "|" + (this.roster == null ? -1 : this.roster.posts().size())
                + "|" + (this.roster == null ? "" : this.roster.free() + "/" + this.roster.rations() + "/" + this.roster.squadSize())
                + "|" + this.selected
                + "|" + (picked != null && picked.commander());
    }

    private void rebuildShown() {
        this.shown.clear();
        if (this.roster == null) {
            return;
        }

        Scope parsed = Scope.parse(this.scope);
        for (Roster.Entry entry : this.roster.soldiers()) {
            if (parsed == null || parsed.all() || entry.uniform() == (parsed.colour() == null ? -1 : parsed.colour().getId())) {
                this.shown.add(entry);
            }
        }
    }

    private Roster.@Nullable Entry entry(UUID id) {
        if (this.roster == null) {
            return null;
        }

        for (Roster.Entry entry : this.roster.soldiers()) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }

        return null;
    }

    private Roster.@Nullable Entry selectedEntry() {
        return this.selected == null ? null : this.entry(this.selected);
    }

    // Whether the soldier is standing close enough for its kit bag: it has to be loaded here and
    // within reach, the same reach the server checks.
    private boolean near(Roster.@Nullable Entry entry) {
        LocalPlayer player = this.minecraft.player;
        if (entry == null || player == null || this.minecraft.level == null || !entry.here()) {
            return false;
        }

        Entity soldier = this.minecraft.level.getEntity(entry.entityId());
        return soldier != null && player.isWithinEntityInteractionRange(soldier, 4.0);
    }

    // ---- Tabs ----

    // One tab for the whole army, one per squad that has anyone in it, and one for the reserve;
    // returns how many rows they took.
    private int buildTabs() {
        Map<String, Integer> counts = this.counts();
        if (!counts.containsKey(this.scope)) {
            this.scope = Scope.ALL.token();
            this.rebuildShown();
        }

        this.tabs.clear();
        int perRow = Math.max(1, (this.width - 2 * MARGIN) / (TAB_WIDTH + 4));
        int index = 0;
        for (Map.Entry<String, Integer> tab : counts.entrySet()) {
            String token = tab.getKey();
            Button button = Button.builder(tabLabel(token, tab.getValue()), b -> this.selectScope(token))
                    .bounds(MARGIN + (index % perRow) * (TAB_WIDTH + 4), TABS_Y + (index / perRow) * (TAB_HEIGHT + 2), TAB_WIDTH, TAB_HEIGHT)
                    .tooltip(Tooltip.create(tabName(token)))
                    .build();
            button.active = !token.equals(this.scope);
            this.tabs.put(this.addRenderableWidget(button), token);
            index++;
        }

        return (counts.size() + perRow - 1) / perRow;
    }

    // How many soldiers each tab would show, in tab order: the whole army, each colour that has
    // anyone in it, then the reserve.
    private Map<String, Integer> counts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put(Scope.ALL.token(), this.roster == null ? 0 : this.roster.soldiers().size());
        if (this.roster == null) {
            return counts;
        }

        for (DyeColor colour : DyeColor.VALUES) {
            int count = 0;
            for (Roster.Entry entry : this.roster.soldiers()) {
                if (entry.uniform() == colour.getId()) {
                    count++;
                }
            }
            if (count > 0) {
                counts.put(colour.getName(), count);
            }
        }

        int camo = 0;
        for (Roster.Entry entry : this.roster.soldiers()) {
            if (entry.uniform() < 0) {
                camo++;
            }
        }
        if (camo > 0) {
            counts.put(Scope.RESERVE.token(), camo);
        }

        return counts;
    }

    // Room for the swatch first, then the name and the count.
    private static Component tabLabel(String token, int count) {
        return Component.literal("  ").append(tabName(token)).append(Component.literal(" " + count));
    }

    private static Component tabName(String token) {
        if (token.equals(Scope.ALL.token())) {
            return Component.translatable("combatupdate.army.screen.tab.all");
        }

        Scope scope = Scope.parse(token);
        return scope == null ? Component.literal(token) : scope.name();
    }

    private void selectScope(String token) {
        this.scope = token;
        this.selected = null;
        this.scroll = 0;
        this.rebuildWidgets();
    }

    // ---- The squad pane ----

    private void buildSquadPane() {
        this.rememberControlsTab();
        this.controls = null;
        this.bagButton = null;
        this.orderWidgets.clear();
        this.kitWidgets.clear();
        this.armourWidgets.clear();

        int x = this.paneX;
        int y = this.listTop + 2 * LINE + 4;

        String[] tabKeys = {"orders", "kit", "armour"};
        for (int index = 0; index < tabKeys.length; index++) {
            int which = index;
            Button button = Button.builder(Component.translatable("combatupdate.army.screen.tab." + tabKeys[index]), b -> this.selectSquadTab(which))
                    .bounds(x + index * 34, y, 32, BUTTON).build();
            this.squadTabs[index] = this.addRenderableWidget(button);
        }
        int top = y + STEP + 3;

        // Orders: the four the chat menu gives, the shapes, recruiting, the march, the door.
        int cursor = top;
        this.order(x, cursor, HALF, Component.translatable("combatupdate.army.stance.attack"), SquadAction.ATTACK, "",
                Component.translatable("combatupdate.army.order.sighted.hover"));
        this.order(x + HALF + 4, cursor, HALF, Component.translatable("combatupdate.army.stance.stand"), SquadAction.STAND, "",
                Component.translatable("combatupdate.army.stance.stand.hover"));
        cursor += STEP;
        this.order(x, cursor, HALF, Component.translatable("combatupdate.army.order.hold"), SquadAction.HOLD, "",
                Component.translatable("combatupdate.army.order.hold.hover"));
        this.order(x + HALF + 4, cursor, HALF, Component.translatable("combatupdate.army.order.follow"), SquadAction.FOLLOW, "",
                Component.translatable("combatupdate.army.order.follow.hover"));
        cursor += STEP;

        GipfaeliFormation[] shapes = GipfaeliFormation.values();
        for (int index = 0; index < shapes.length; index++) {
            GipfaeliFormation shape = shapes[index];
            this.order(x + (index % 3) * 34, cursor + (index / 3) * STEP, 32, Component.translatable(shape.key()), SquadAction.FORM, shape.token(),
                    Component.translatable(shape.key() + ".hover"));
        }
        cursor += STEP * ((shapes.length + 2) / 3);

        int rations = this.roster == null ? 0 : this.roster.rations();
        boolean free = this.roster != null && this.roster.free();
        this.order(x, cursor, HALF, Component.literal("+1"), SquadAction.FILL, "1",
                recruitHint(1, rations, free));
        this.order(x + HALF + 4, cursor, HALF, Component.literal("+5"), SquadAction.FILL, "5",
                recruitHint(5, rations, free));
        cursor += STEP;
        int size = this.roster == null ? 0 : this.roster.squadSize();
        this.order(x, cursor, HALF, Component.translatable("combatupdate.army.screen.fill"), SquadAction.FILL, Integer.toString(Math.max(1, size)),
                Component.translatable("combatupdate.army.squad.spawn.fill.hover"));
        this.order(x + HALF + 4, cursor, HALF, Component.translatable("combatupdate.army.screen.raise"), SquadAction.RAISE, "",
                Component.translatable("combatupdate.army.screen.raise.hover", free ? Component.translatable("combatupdate.army.screen.free") : Component.literal(Integer.toString(rations))));
        cursor += STEP;

        // The march: how many, and where - the chunk the map handed over, or the one the player
        // is standing in.
        Button fewer = Button.builder(Component.literal("-"), b -> this.adjustSoldiers(-1)).bounds(x, cursor, 14, BUTTON).build();
        Button more = Button.builder(Component.literal("+"), b -> this.adjustSoldiers(1)).bounds(x + 16, cursor, 14, BUTTON).build();
        ChunkPos chunk = this.marchTarget();
        this.sendButton = Button.builder(Component.translatable("combatupdate.army.screen.send", this.soldiers), b -> this.sendSoldiers())
                .bounds(x + 32, cursor, PANE_WIDTH - 32, BUTTON)
                .tooltip(Tooltip.create(Component.translatable("combatupdate.army.screen.send.hover", chunk.x(), chunk.z())))
                .build();
        this.orderWidgets.add(this.addRenderableWidget(fewer));
        this.orderWidgets.add(this.addRenderableWidget(more));
        this.orderWidgets.add(this.addRenderableWidget(this.sendButton));
        cursor += STEP;

        this.dismissButton = Button.builder(Component.translatable(this.confirmTicks > 0
                        ? "combatupdate.army.screen.dismiss.confirm" : "combatupdate.army.order.dismiss"), b -> this.onDismissSquad())
                .bounds(x, cursor, PANE_WIDTH, BUTTON)
                .tooltip(Tooltip.create(Component.translatable("combatupdate.army.screen.squad.dismiss.hover")))
                .build();
        this.orderWidgets.add(this.addRenderableWidget(this.dismissButton));
        cursor += STEP;

        // The assault: a side picked with the arrows - the training dummies, the reserve or a
        // colour - and fought until nobody of it is left.
        this.orderWidgets.add(this.addRenderableWidget(Button.builder(Component.literal("<"), b -> this.cycleAssault(-1))
                .bounds(x, cursor, 14, BUTTON).build()));
        this.assaultButton = Button.builder(this.assaultLabel(), b -> this.send(SquadAction.ASSAULT, this.assaultToken()))
                .bounds(x + 16, cursor, PANE_WIDTH - 32, BUTTON)
                .tooltip(Tooltip.create(Component.translatable("combatupdate.army.screen.assault.hover")))
                .build();
        this.orderWidgets.add(this.addRenderableWidget(this.assaultButton));
        this.orderWidgets.add(this.addRenderableWidget(Button.builder(Component.literal(">"), b -> this.cycleAssault(1))
                .bounds(x + PANE_WIDTH - 14, cursor, 14, BUTTON).build()));

        // Kit for all, and armour for all: the same buttons a soldier has, for everyone in scope
        // but the commander.
        GipfaeliWeapon[] roles = GipfaeliWeapon.values();
        for (int index = 0; index < roles.length; index++) {
            GipfaeliWeapon role = roles[index];
            this.kitWidgets.add(this.button(x + (index % 2) * (HALF + 4), top + (index / 2) * STEP, HALF, role.roleName(),
                    SquadAction.KIT, role.token(),
                    Component.translatable("combatupdate.army.squad.kit.hover", role.stack().getHoverName())
                            .append(Component.literal("\n"))
                            .append(Component.translatable(role.key() + ".hover"))));
        }
        this.kitWidgets.add(this.button(x, top + ((roles.length + 1) / 2) * STEP, PANE_WIDTH,
                Component.translatable("combatupdate.army.soldier.disarm"), SquadAction.DISARM, "", null));

        GipfaeliArmour[] suits = GipfaeliArmour.values();
        for (int index = 0; index < suits.length; index++) {
            GipfaeliArmour suit = suits[index];
            this.armourWidgets.add(this.button(x + (index % 2) * (HALF + 4), top + (index / 2) * STEP, HALF, suit.displayName(),
                    SquadAction.ARMOUR, suit.token(), Component.translatable("combatupdate.army.squad.armour.hover")));
        }
        this.armourWidgets.add(this.button(x, top + ((suits.length + 1) / 2) * STEP, PANE_WIDTH,
                Component.translatable("combatupdate.army.soldier.strip"), SquadAction.STRIP, "", null));

        // And the squad's colour, under the armour it goes on: sixteen dyes and the camouflage,
        // each a small button in its own colour. Painting a squad is renaming it, so the whole
        // roster changes hands with the click.
        int swatchTop = top + ((suits.length + 1) / 2 + 1) * STEP;
        int perRow = 6;
        int swatchWidth = (PANE_WIDTH - (perRow - 1) * 2) / perRow;
        for (int index = 0; index <= DyeColor.VALUES.size(); index++) {
            DyeColor colour = index == 0 ? null : DyeColor.VALUES.get(index - 1);
            Component label = Component.literal("■").withStyle(style -> style.withColor(SoldierControls.swatchColour(colour) & 0xFFFFFF));
            this.armourWidgets.add(this.button(x + (index % perRow) * (swatchWidth + 2), swatchTop + (index / perRow) * STEP, swatchWidth, label,
                    SquadAction.COLOUR, colour == null ? "camo" : colour.getName(),
                    Component.translatable("combatupdate.army.screen.colour.hover", GipfaeliArmy.colourName(colour))));
        }

        // And the pattern over the colour: woodland, snow, desert and the rest, picked with the
        // arrows and put on with the middle button.
        int camoTop = swatchTop + ((DyeColor.VALUES.size() + perRow) / perRow) * STEP + 2;
        this.armourWidgets.add(this.addRenderableWidget(Button.builder(Component.literal("<"), b -> this.cycleCamo(-1))
                .bounds(x, camoTop, 14, BUTTON).build()));
        this.camoButton = Button.builder(this.camoLabel(), b -> this.send(SquadAction.CAMO, GipfaeliCamo.byOrdinal(this.camoIndex).token()))
                .bounds(x + 16, camoTop, PANE_WIDTH - 32, BUTTON)
                .tooltip(Tooltip.create(Component.translatable("combatupdate.army.screen.camo.hover")))
                .build();
        this.armourWidgets.add(this.addRenderableWidget(this.camoButton));
        this.armourWidgets.add(this.addRenderableWidget(Button.builder(Component.literal(">"), b -> this.cycleCamo(1))
                .bounds(x + PANE_WIDTH - 14, camoTop, 14, BUTTON).build()));

        this.selectSquadTab(this.squadTab);
    }

    private String assaultToken() {
        return GipfaeliAssault.SUGGESTIONS.get(Math.floorMod(this.assaultIndex, GipfaeliAssault.SUGGESTIONS.size()));
    }

    private Component assaultLabel() {
        GipfaeliAssault.Foe foe = GipfaeliAssault.Foe.parse(this.assaultToken());
        return Component.translatable("combatupdate.army.screen.assault", foe == null ? Component.literal("?") : foe.name());
    }

    private void cycleAssault(int by) {
        this.assaultIndex = Math.floorMod(this.assaultIndex + by, GipfaeliAssault.SUGGESTIONS.size());
        if (this.assaultButton != null) {
            this.assaultButton.setMessage(this.assaultLabel());
        }
    }

    private Component camoLabel() {
        return Component.translatable("combatupdate.army.screen.camo", GipfaeliCamo.byOrdinal(this.camoIndex).displayName());
    }

    private void cycleCamo(int by) {
        this.camoIndex = Math.floorMod(this.camoIndex + by, GipfaeliCamo.values().length);
        if (this.camoButton != null) {
            this.camoButton.setMessage(this.camoLabel());
        }
    }

    // The controls are built afresh with the pane; which tab they were on is kept here across it.
    private void rememberControlsTab() {
        if (this.controls != null) {
            this.controlsTab = this.controls.tab();
        }
    }

    private static Component recruitHint(int count, int rations, boolean free) {
        return free
                ? Component.translatable("combatupdate.army.screen.recruit.free", count)
                : Component.translatable("combatupdate.army.squad.spawn.hover", count, count * rations);
    }

    private void order(int x, int y, int width, Component label, SquadAction action, String argument, @Nullable Component hover) {
        this.orderWidgets.add(this.button(x, y, width, label, action, argument, hover));
    }

    private Button button(int x, int y, int width, Component label, SquadAction action, String argument, @Nullable Component hover) {
        Button.Builder builder = Button.builder(label, b -> this.send(action, argument)).bounds(x, y, width, BUTTON);
        if (hover != null) {
            builder.tooltip(Tooltip.create(hover));
        }

        return this.addRenderableWidget(builder.build());
    }

    private void selectSquadTab(int which) {
        this.squadTab = which;
        for (int index = 0; index < this.squadTabs.length; index++) {
            if (this.squadTabs[index] != null) {
                this.squadTabs[index].active = index != which;
            }
        }
        for (AbstractWidget widget : this.orderWidgets) {
            widget.visible = which == 0;
        }
        for (AbstractWidget widget : this.kitWidgets) {
            widget.visible = which == 1;
        }
        for (AbstractWidget widget : this.armourWidgets) {
            widget.visible = which == 2;
        }
    }

    private void send(SquadAction action, String argument) {
        ClientPacketDistributor.sendToServer(new SquadOrder(this.scope, action, argument));
    }

    private ChunkPos marchTarget() {
        if (this.target != null) {
            return this.target;
        }

        LocalPlayer player = this.minecraft.player;
        return player == null ? new ChunkPos(0, 0) : player.chunkPosition();
    }

    private void adjustSoldiers(int by) {
        this.soldiers = Math.clamp(this.soldiers + by, 1, SOLDIERS_MAX);
        if (this.sendButton != null) {
            this.sendButton.setMessage(Component.translatable("combatupdate.army.screen.send", this.soldiers));
        }
    }

    // The march goes through the same request the territory map sends, and only ever to the
    // soldiers in scope is not something that request knows - it takes from the whole army, the
    // way the map does.
    private void sendSoldiers() {
        ChunkPos chunk = this.marchTarget();
        ClientPacketDistributor.sendToServer(new MarchRequest(chunk.x(), chunk.z(), this.soldiers));
    }

    private void onDismissSquad() {
        if (this.dismissButton == null) {
            return;
        }

        if (this.confirmTicks > 0) {
            this.confirmTicks = 0;
            this.dismissButton.setMessage(Component.translatable("combatupdate.army.order.dismiss"));
            this.send(SquadAction.DISMISS, "");
            return;
        }

        this.confirmTicks = CONFIRM_TICKS;
        this.dismissButton.setMessage(Component.translatable("combatupdate.army.screen.dismiss.confirm"));
    }

    // ---- The soldier pane ----

    private void buildSoldierPane() {
        this.rememberControlsTab();
        Roster.Entry entry = this.selectedEntry();
        if (entry == null || this.roster == null) {
            this.selected = null;
            this.buildSquadPane();
            return;
        }

        this.orderWidgets.clear();
        this.kitWidgets.clear();
        this.armourWidgets.clear();
        java.util.Arrays.fill(this.squadTabs, null);
        this.dismissButton = null;
        this.sendButton = null;
        this.confirmTicks = 0;

        int x = this.paneX;
        int y = this.listTop + 4 * LINE + 4;

        this.bagButton = this.addRenderableWidget(Button.builder(Component.translatable("combatupdate.army.screen.button.bag"),
                b -> ClientPacketDistributor.sendToServer(new SoldierOrder(entry.id(), SoldierAction.OPEN, "")))
                .bounds(x, y, PANE_WIDTH, BUTTON)
                .tooltip(Tooltip.create(Component.translatable("combatupdate.army.screen.button.bag.hover")))
                .build());
        this.bagButton.active = this.near(entry);
        y += STEP + 2;

        this.controls = new SoldierControls(this::addRenderableWidget, x, y, entry.id(), entry.commander(), this.roster.posts().size(), this.controlsTab);
        this.controls.camo(GipfaeliCamo.byOrdinal(entry.camo()));
        y = this.controls.bottom() + 2;

        this.addRenderableWidget(Button.builder(Component.translatable("combatupdate.army.screen.button.back"), b -> {
            this.selected = null;
            this.rebuildWidgets();
        }).bounds(x, y, PANE_WIDTH, BUTTON).build());
    }

    // ---- Mouse ----

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == 0 && this.controls != null && this.controls.click(event.x(), event.y())) {
            return true;
        }

        if (event.button() == 0 && this.overList(event.x(), event.y())) {
            int index = ((int) event.y() - this.listTop + this.scroll) / ROW;
            if (index >= 0 && index < this.shown.size()) {
                Roster.Entry entry = this.shown.get(index);
                if (doubleClick && entry.id().equals(this.selected)) {
                    if (this.near(entry)) {
                        ClientPacketDistributor.sendToServer(new SoldierOrder(entry.id(), SoldierAction.OPEN, ""));
                    }
                    return true;
                }

                if (!entry.id().equals(this.selected)) {
                    this.selected = entry.id();
                    this.rebuildWidgets();
                }
                return true;
            }
        }

        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        if (this.overList(x, y)) {
            this.scroll -= (int) (scrollY * ROW * 2);
            this.clampScroll();
            return true;
        }

        return super.mouseScrolled(x, y, scrollX, scrollY);
    }

    private boolean overList(double x, double y) {
        return x >= MARGIN && x < MARGIN + this.listWidth && y >= this.listTop && y < this.listBottom;
    }

    private void clampScroll() {
        int overflow = this.shown.size() * ROW - (this.listBottom - this.listTop);
        this.scroll = Math.clamp(this.scroll, 0, Math.max(0, overflow));
    }

    // ---- Drawing ----

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);

        graphics.centeredText(this.font, this.title, this.width / 2, 8, COLOUR_TEXT);
        this.drawTabSwatches(graphics);
        this.drawList(graphics, mouseX, mouseY);
        if (this.selected != null) {
            this.drawSoldierHeader(graphics);
        } else {
            this.drawSquadHeader(graphics);
        }

        if (this.controls != null) {
            Roster.Entry entry = this.selectedEntry();
            this.controls.draw(graphics, this.font, mouseX, mouseY, entry == null || entry.uniform() < 0 ? null : DyeColor.byId(entry.uniform()));
        }
    }

    // A square of the squad's colour on each tab, since a button cannot be tinted.
    private void drawTabSwatches(GuiGraphicsExtractor graphics) {
        for (Map.Entry<Button, String> tab : this.tabs.entrySet()) {
            Scope scope = Scope.parse(tab.getValue());
            if (scope == null || scope.all()) {
                continue;
            }

            Button button = tab.getKey();
            int sx = button.getX() + 3;
            int sy = button.getY() + (TAB_HEIGHT - 6) / 2;
            graphics.fill(sx, sy, sx + 6, sy + 6, COLOUR_FRAME);
            graphics.fill(sx + 1, sy + 1, sx + 5, sy + 5, SoldierControls.swatchColour(scope.colour()));
        }
    }

    private void drawList(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        int x = MARGIN;
        int right = x + this.listWidth;
        graphics.fill(x - 1, this.listTop - 1, right + 1, this.listBottom + 1, COLOUR_FRAME);
        graphics.fill(x, this.listTop, right, this.listBottom, COLOUR_LIST);

        if (this.roster == null) {
            graphics.centeredText(this.font, Component.translatable("combatupdate.territory.screen.loading"), x + this.listWidth / 2, this.listTop + 8, COLOUR_MUTED);
            return;
        }

        if (this.shown.isEmpty()) {
            graphics.centeredText(this.font, Component.translatable("combatupdate.army.screen.empty"), x + this.listWidth / 2, this.listTop + 8, COLOUR_MUTED);
            return;
        }

        // Columns: colour, name, role, health, doing, where. The last two go when there is no
        // room for them, which on a small window there is not.
        int nameX = x + 14;
        int roleX = x + Math.min(120, this.listWidth * 2 / 5);
        int healthX = roleX + 56;
        int doingX = healthX + 46;
        int whereX = doingX + 54;
        boolean showDoing = doingX + 50 < right;
        boolean showWhere = whereX + 44 < right;

        graphics.enableScissor(x, this.listTop, right, this.listBottom);
        for (int index = 0; index < this.shown.size(); index++) {
            int ry = this.listTop + index * ROW - this.scroll;
            if (ry + ROW < this.listTop || ry > this.listBottom) {
                continue;
            }

            Roster.Entry entry = this.shown.get(index);
            boolean picked = entry.id().equals(this.selected);
            boolean hovered = mouseX >= x && mouseX < right && mouseY >= ry && mouseY < ry + ROW && mouseY >= this.listTop && mouseY < this.listBottom;
            if (picked || hovered) {
                graphics.fill(x, ry, right, ry + ROW, picked ? COLOUR_SELECTED : COLOUR_HOVER);
            }

            int sy = ry + (ROW - SWATCH) / 2;
            graphics.fill(x + 2, sy, x + 2 + SWATCH, sy + SWATCH, COLOUR_FRAME);
            graphics.fill(x + 3, sy + 1, x + 1 + SWATCH, sy + SWATCH - 1,
                    SoldierControls.swatchColour(entry.uniform() < 0 ? null : DyeColor.byId(entry.uniform())));

            MutableComponent name = Component.literal(entry.commander() ? "★ " : "").append(Component.literal(entry.name()));
            if (entry.commander()) {
                name.withStyle(ChatFormatting.GOLD);
            }
            this.clipped(graphics, name, nameX, ry + 2, roleX - nameX - 4, COLOUR_TEXT);

            Component role = entry.weapon() < 0
                    ? Component.translatable("combatupdate.army.menu.unarmed").withStyle(ChatFormatting.GRAY)
                    : GipfaeliWeapon.values()[entry.weapon()].roleName();
            this.clipped(graphics, role, roleX, ry + 2, healthX - roleX - 4, COLOUR_TEXT);

            int barWidth = 40;
            int fill = entry.maxHealth() <= 0 ? 0 : Math.round(barWidth * Math.clamp(entry.health() / entry.maxHealth(), 0.0F, 1.0F));
            graphics.fill(healthX, ry + 3, healthX + barWidth, ry + ROW - 3, COLOUR_HEALTH_BACK);
            graphics.fill(healthX, ry + 3, healthX + fill, ry + ROW - 3, entry.health() < entry.maxHealth() / 3.0F ? COLOUR_HURT : COLOUR_HEALTH);

            if (showDoing) {
                this.clipped(graphics, Component.translatable(entry.activity().key()), doingX, ry + 2, whereX - doingX - 4, COLOUR_MUTED);
            }
            if (showWhere) {
                Component where = entry.here()
                        ? Component.translatable("combatupdate.army.screen.where", entry.chunkX(), entry.chunkZ())
                        : Component.translatable("combatupdate.army.screen.elsewhere");
                this.clipped(graphics, where, whereX, ry + 2, right - whereX - 6, COLOUR_MUTED);
            }

            if (hovered && !showDoing) {
                graphics.setTooltipForNextFrame(this.font, Component.translatable(entry.activity().key()), mouseX, mouseY);
            }
        }
        graphics.disableScissor();

        // The scrollbar, when there is more than fits.
        int content = this.shown.size() * ROW;
        int visible = this.listBottom - this.listTop;
        if (content > visible) {
            int barHeight = Math.max(8, visible * visible / content);
            int barY = this.listTop + (visible - barHeight) * this.scroll / (content - visible);
            graphics.fill(right - 3, barY, right - 1, barY + barHeight, COLOUR_BAR);
        }
    }

    private void drawSquadHeader(GuiGraphicsExtractor graphics) {
        int x = this.paneX;
        int y = this.listTop;
        Scope parsed = Scope.parse(this.scope);
        Component name = parsed == null || parsed.all()
                ? Component.translatable("combatupdate.army.scope.all")
                : Component.translatable("combatupdate.army.squad.name", parsed.name());
        this.clipped(graphics, name.copy().withStyle(ChatFormatting.BOLD), x, y, PANE_WIDTH, COLOUR_TEXT);
        y += LINE;

        if (this.roster != null) {
            int ranks = 0;
            Roster.Entry leader = null;
            for (Roster.Entry entry : this.shown) {
                if (entry.commander()) {
                    leader = leader == null ? entry : leader;
                } else {
                    ranks++;
                }
            }

            Component strength = parsed == null || parsed.all()
                    ? Component.translatable("combatupdate.army.screen.strength", this.shown.size(), this.roster.armyCap())
                    : Component.translatable("combatupdate.army.screen.strength", ranks, this.roster.squadSize());
            Component line = parsed == null || parsed.all() || leader == null
                    ? strength
                    : strength.copy().append(Component.literal(" · ★ ")).append(Component.literal(leader.name()));
            this.clipped(graphics, line, x, y, PANE_WIDTH, COLOUR_MUTED);
        }
    }

    private void drawSoldierHeader(GuiGraphicsExtractor graphics) {
        Roster.Entry entry = this.selectedEntry();
        if (entry == null) {
            return;
        }

        int x = this.paneX;
        int y = this.listTop;
        MutableComponent name = Component.literal(entry.name()).withStyle(ChatFormatting.BOLD);
        if (entry.commander()) {
            name.withStyle(ChatFormatting.GOLD);
        }
        this.clipped(graphics, name, x, y, PANE_WIDTH, COLOUR_TEXT);
        y += LINE;

        Component role = entry.weapon() < 0
                ? Component.translatable("combatupdate.army.menu.unarmed")
                : GipfaeliWeapon.values()[entry.weapon()].roleName();
        this.clipped(graphics, Component.translatable("combatupdate.army.screen.health", (int) Math.ceil(entry.health()), (int) Math.ceil(entry.maxHealth()))
                .append(Component.literal(" · ")).append(role), x, y, PANE_WIDTH, COLOUR_MUTED);
        y += LINE;

        Component armour = entry.armour() < 0
                ? Component.translatable("combatupdate.army.soldier.none")
                : GipfaeliArmour.values()[entry.armour()].displayName();
        this.clipped(graphics, Component.translatable("combatupdate.army.screen.armour", armour)
                .append(Component.literal(" · "))
                .append(GipfaeliArmy.colourName(entry.uniform() < 0 ? null : DyeColor.byId(entry.uniform()))), x, y, PANE_WIDTH, COLOUR_MUTED);
        y += LINE;

        Component where = entry.here()
                ? Component.translatable("combatupdate.army.screen.where", entry.chunkX(), entry.chunkZ())
                : Component.translatable("combatupdate.army.screen.elsewhere");
        this.clipped(graphics, Component.translatable(entry.activity().key()).append(Component.literal(" · ")).append(where), x, y, PANE_WIDTH, COLOUR_MUTED);
    }

    private void clipped(GuiGraphicsExtractor graphics, Component text, int x, int y, int width, int colour) {
        graphics.text(this.font, Language.getInstance().getVisualOrder(this.font.substrByWidth(text, width)), x, y, colour);
    }
}
