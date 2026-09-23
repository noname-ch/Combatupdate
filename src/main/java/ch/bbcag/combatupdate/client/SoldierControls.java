package ch.bbcag.combatupdate.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

import ch.bbcag.combatupdate.GipfaeliArmour;
import ch.bbcag.combatupdate.GipfaeliArmy;
import ch.bbcag.combatupdate.GipfaeliArmyNetwork.SoldierAction;
import ch.bbcag.combatupdate.GipfaeliArmyNetwork.SoldierOrder;
import ch.bbcag.combatupdate.GipfaeliWeapon;

// The buttons one soldier is told things with, shared by its kit bag and by the roster: the kit
// that makes it a rifleman or a marksman, the armour it stands in, the colour it wears - which is
// the squad it is in - its rank, the posts it can be sent to, and the door.
//
// Three tabs in a hundred pixels, because the roster has to fit beside a list and the kit bag
// beside an inventory, and neither has room for thirty buttons at once. Every button sends one
// SoldierOrder; the server does the work and answers with the roster, so nothing here keeps state
// about the soldier beyond which tab is showing.
//
// In survival the kit and armour buttons take the piece out of the player's pack, and do nothing
// if there is none; in creative they hand it out for nothing. The server decides which.
final class SoldierControls {
    static final int WIDTH = 100;
    static final int ROW = 13;
    static final int HEIGHT = 12;
    private static final int HALF = 48;
    private static final int TAB = 32;
    private static final int SWATCH = 10;
    private static final int SWATCHES_PER_ROW = 9;

    // How long a first press on Dismiss waits for the second, in ticks.
    private static final int CONFIRM_TICKS = 60;

    enum Tab {
        KIT, ARMOUR, MORE;

        Component label() {
            return Component.translatable("combatupdate.army.screen.tab." + this.name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    private final UUID soldier;
    private final int x;
    private final int y;
    private final Button[] tabs = new Button[Tab.values().length];
    private final List<AbstractWidget> kit = new ArrayList<>();
    private final List<AbstractWidget> armour = new ArrayList<>();
    private final List<AbstractWidget> more = new ArrayList<>();
    private final Button promote;
    private final Button dismiss;
    private final int swatchY;
    private final int bottom;

    private Tab tab = Tab.KIT;
    private boolean commander;
    private int confirmTicks;

    SoldierControls(Consumer<AbstractWidget> add, int x, int y, UUID soldier, boolean commander, int posts, Tab initial) {
        this.soldier = soldier;
        this.x = x;
        this.y = y;

        for (Tab candidate : Tab.values()) {
            Button button = Button.builder(candidate.label(), b -> this.select(candidate))
                    .bounds(x + candidate.ordinal() * (TAB + 2), y, TAB, HEIGHT).build();
            this.tabs[candidate.ordinal()] = button;
            add.accept(button);
        }

        int top = y + ROW + 3;

        // Kit: the six roles two abreast, and the button that takes the gun back.
        GipfaeliWeapon[] roles = GipfaeliWeapon.values();
        for (int index = 0; index < roles.length; index++) {
            GipfaeliWeapon role = roles[index];
            Button button = Button.builder(role.roleName(), b -> this.send(SoldierAction.KIT, role.token()))
                    .bounds(x + (index % 2) * (HALF + 4), top + (index / 2) * ROW, HALF, HEIGHT)
                    .tooltip(Tooltip.create(Component.translatable("combatupdate.army.soldier.kit.hover", role.stack().getHoverName())
                            .append(Component.literal("\n"))
                            .append(Component.translatable(role.key() + ".hover"))))
                    .build();
            this.kit.add(button);
            add.accept(button);
        }
        Button disarm = Button.builder(Component.translatable("combatupdate.army.soldier.disarm"), b -> this.send(SoldierAction.DISARM, ""))
                .bounds(x, top + ((roles.length + 1) / 2) * ROW, WIDTH, HEIGHT).build();
        this.kit.add(disarm);
        add.accept(disarm);

        // Armour: the six suits two abreast, and the button that takes it all off.
        GipfaeliArmour[] suits = GipfaeliArmour.values();
        for (int index = 0; index < suits.length; index++) {
            GipfaeliArmour suit = suits[index];
            Button button = Button.builder(suit.displayName(), b -> this.send(SoldierAction.ARMOUR, suit.token()))
                    .bounds(x + (index % 2) * (HALF + 4), top + (index / 2) * ROW, HALF, HEIGHT)
                    .tooltip(Tooltip.create(Component.translatable("combatupdate.army.soldier.armour.hover")))
                    .build();
            this.armour.add(button);
            add.accept(button);
        }
        Button strip = Button.builder(Component.translatable("combatupdate.army.soldier.strip"), b -> this.send(SoldierAction.STRIP, ""))
                .bounds(x, top + ((suits.length + 1) / 2) * ROW, WIDTH, HEIGHT).build();
        this.armour.add(strip);
        add.accept(strip);

        // More: the colours are drawn and clicked by hand (see draw and click), then rank, posts
        // and the door.
        this.swatchY = top;
        int cursor = top + 2 * (SWATCH + 1) + 3;
        this.promote = Button.builder(Component.empty(), b -> this.send(commanderNow() ? SoldierAction.DEMOTE : SoldierAction.PROMOTE, ""))
                .bounds(x, cursor, WIDTH, HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("combatupdate.army.soldier.promote.hover")))
                .build();
        this.more.add(this.promote);
        add.accept(this.promote);
        this.update(commander);
        cursor += ROW;

        int perRow = 4;
        int postWidth = (WIDTH - (perRow - 1) * 2) / perRow;
        for (int number = 1; number <= Math.min(posts, perRow * 2); number++) {
            int slot = number - 1;
            String argument = Integer.toString(number);
            Button button = Button.builder(Component.translatable("combatupdate.army.soldier.post", number), b -> this.send(SoldierAction.POST, argument))
                    .bounds(x + (slot % perRow) * (postWidth + 2), cursor + (slot / perRow) * ROW, postWidth, HEIGHT)
                    .tooltip(Tooltip.create(Component.translatable("combatupdate.army.screen.post.hover", number)))
                    .build();
            this.more.add(button);
            add.accept(button);
        }
        if (posts > 0) {
            cursor += ROW * Math.min(2, (posts + perRow - 1) / perRow);
        }

        this.dismiss = Button.builder(Component.translatable("combatupdate.army.order.dismiss"), b -> this.onDismiss())
                .bounds(x, cursor, WIDTH, HEIGHT)
                .tooltip(Tooltip.create(Component.translatable("combatupdate.army.screen.dismiss.hover")))
                .build();
        this.more.add(this.dismiss);
        add.accept(this.dismiss);
        cursor += ROW;

        this.bottom = cursor;
        this.select(initial);
    }

    // Which tab is showing, so a screen rebuilt around new controls can put it back.
    Tab tab() {
        return this.tab;
    }

    private boolean commanderNow() {
        return this.commander;
    }

    // The rank button says whichever way the soldier can go.
    void update(boolean commander) {
        this.commander = commander;
        this.promote.setMessage(Component.translatable(commander ? "combatupdate.army.soldier.demote" : "combatupdate.army.screen.promote"));
    }

    // Where the next thing under these should go.
    int bottom() {
        return this.bottom;
    }

    private void select(Tab tab) {
        this.tab = tab;
        for (Tab candidate : Tab.values()) {
            this.tabs[candidate.ordinal()].active = candidate != tab;
        }
        for (AbstractWidget widget : this.kit) {
            widget.visible = tab == Tab.KIT;
        }
        for (AbstractWidget widget : this.armour) {
            widget.visible = tab == Tab.ARMOUR;
        }
        for (AbstractWidget widget : this.more) {
            widget.visible = tab == Tab.MORE;
        }
    }

    private void send(SoldierAction action, String argument) {
        ClientPacketDistributor.sendToServer(new SoldierOrder(this.soldier, action, argument));
    }

    // Dismiss takes two presses a few seconds apart, because it is the one button here that
    // cannot be undone with another button.
    private void onDismiss() {
        if (this.confirmTicks > 0) {
            this.confirmTicks = 0;
            this.dismiss.setMessage(Component.translatable("combatupdate.army.order.dismiss"));
            this.send(SoldierAction.DISMISS, "");
            return;
        }

        this.confirmTicks = CONFIRM_TICKS;
        this.dismiss.setMessage(Component.translatable("combatupdate.army.screen.dismiss.confirm"));
    }

    void tick() {
        if (this.confirmTicks > 0 && --this.confirmTicks == 0) {
            this.dismiss.setMessage(Component.translatable("combatupdate.army.order.dismiss"));
        }
    }

    // The colour swatches: camouflage first, then the sixteen dyes, the one it wears framed white.
    void draw(GuiGraphicsExtractor graphics, net.minecraft.client.gui.Font font, int mouseX, int mouseY, @Nullable DyeColor current) {
        if (this.tab != Tab.MORE) {
            return;
        }

        for (int index = 0; index <= DyeColor.VALUES.size(); index++) {
            DyeColor colour = index == 0 ? null : DyeColor.VALUES.get(index - 1);
            int sx = this.swatchX(index);
            int sy = this.swatchY(index);
            boolean worn = colour == current;
            graphics.fill(sx, sy, sx + SWATCH, sy + SWATCH, worn ? 0xFFFFFFFF : 0xFF000000);
            graphics.fill(sx + 1, sy + 1, sx + SWATCH - 1, sy + SWATCH - 1, swatchColour(colour));
            if (mouseX >= sx && mouseX < sx + SWATCH && mouseY >= sy && mouseY < sy + SWATCH) {
                graphics.setTooltipForNextFrame(font, Component.translatable("combatupdate.army.screen.colour.hover", GipfaeliArmy.colourName(colour)), mouseX, mouseY);
            }
        }
    }

    // A click on a swatch paints the soldier; true when the click was on one.
    boolean click(double mouseX, double mouseY) {
        if (this.tab != Tab.MORE) {
            return false;
        }

        for (int index = 0; index <= DyeColor.VALUES.size(); index++) {
            int sx = this.swatchX(index);
            int sy = this.swatchY(index);
            if (mouseX >= sx && mouseX < sx + SWATCH && mouseY >= sy && mouseY < sy + SWATCH) {
                DyeColor colour = index == 0 ? null : DyeColor.VALUES.get(index - 1);
                this.send(SoldierAction.COLOUR, colour == null ? "camo" : colour.getName());
                return true;
            }
        }

        return false;
    }

    private int swatchX(int index) {
        return this.x + (index % SWATCHES_PER_ROW) * (SWATCH + 1);
    }

    private int swatchY(int index) {
        return this.swatchY + (index / SWATCHES_PER_ROW) * (SWATCH + 1);
    }

    // The field green of a camouflage uniform, or the dye as it looks on wool.
    static int swatchColour(@Nullable DyeColor colour) {
        return colour == null ? 0xFF5E6A44 : 0xFF000000 | colour.getTextureDiffuseColor();
    }
}
