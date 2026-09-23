package ch.bbcag.combatupdate;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ArmorSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import ch.bbcag.combatupdate.entity.GipfaeliSoldier;

// One soldier's kit bag, opened like a horse's: its four armour slots and the slot its gun sits
// in, above the player's own inventory. Whatever is dragged across goes onto the soldier and
// leaves the pack; whatever is dragged back comes off it and lands in the pack. That is the whole
// supply rule for survival - a soldier wears what you handed it and nothing else.
//
// The slots read and write the soldier's equipment directly rather than a copy of it, so the
// buttons on the screen (which hand out kit through GipfaeliArmy the way the chat menu does) and
// anything the soldier picks up on its own both show up in the open menu at once.
//
// In creative, and with the supplies switched off, kit costs nothing; the screen's buttons hand it
// out for free then. Taking things back out is refused in the supplies-off case, for the same
// reason GipfaeliArmy#handBack refuses: nothing was paid for it, so handing it back would be
// minting it.
public final class GipfaeliSoldierMenu extends AbstractContainerMenu {
    public static final DeferredRegister<MenuType<?>> MENUS = DeferredRegister.create(Registries.MENU, CombatUpdate.MODID);
    public static final DeferredHolder<MenuType<?>, MenuType<GipfaeliSoldierMenu>> TYPE =
            MENUS.register("gipfaeli_soldier", () -> IMenuTypeExtension.create(GipfaeliSoldierMenu::fromNetwork));

    // The soldier's slots, in the order they are added: the armour column top to bottom, then the
    // gun under it. Indices into the menu's slot list; the player's inventory follows.
    public static final EquipmentSlot[] EQUIPMENT = {
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.MAINHAND};
    public static final int KIT_SLOT = 4;
    public static final int PLAYER_SLOTS_START = EQUIPMENT.length;
    public static final int PLAYER_SLOTS_END = PLAYER_SLOTS_START + 36;

    // Where the slots sit on the screen, in the screen's own coordinates (see GipfaeliSoldierScreen).
    public static final int SLOT_X = 8;
    public static final int SLOT_Y = 8;
    public static final int INVENTORY_Y = 106;

    private static final Identifier[] EMPTY_ICONS = {
            InventoryMenu.EMPTY_ARMOR_SLOT_HELMET, InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
            InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS, InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS};

    private final @Nullable GipfaeliSoldier soldier;
    private final List<BlockPos> posts;

    public GipfaeliSoldierMenu(int containerId, Inventory inventory, @Nullable GipfaeliSoldier soldier, List<BlockPos> posts) {
        super(TYPE.get(), containerId);
        this.soldier = soldier;
        this.posts = List.copyOf(posts);

        Container kit = new Kit();
        for (int index = 0; index < EQUIPMENT.length; index++) {
            EquipmentSlot slot = EQUIPMENT[index];
            int y = SLOT_Y + index * 18;
            if (slot == EquipmentSlot.MAINHAND) {
                this.addSlot(new KitSlot(kit, index, SLOT_X, y));
            } else if (soldier != null) {
                this.addSlot(new ArmorSlot(kit, soldier, slot, index, SLOT_X, y, EMPTY_ICONS[index]) {
                    @Override
                    public boolean mayPickup(Player player) {
                        return canTakeBack(player) && super.mayPickup(player);
                    }
                });
            } else {
                this.addSlot(new Slot(kit, index, SLOT_X, y));
            }
        }

        this.addStandardInventorySlots(inventory, 8, INVENTORY_Y);
    }

    // The client's copy, built from what the server wrote when it opened the menu: which soldier,
    // and the posts the player has to send it to. A soldier the client is not tracking - which
    // cannot happen from a click on it, but can from a stale packet - leaves the menu empty, and
    // stillValid closes it on the next tick.
    private static GipfaeliSoldierMenu fromNetwork(int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        GipfaeliSoldier soldier = inventory.player.level().getEntity(buffer.readVarInt()) instanceof GipfaeliSoldier found ? found : null;
        int count = buffer.readVarInt();
        List<BlockPos> posts = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            posts.add(buffer.readBlockPos());
        }

        return new GipfaeliSoldierMenu(containerId, inventory, soldier, posts);
    }

    // What the server writes for fromNetwork to read.
    public static void write(RegistryFriendlyByteBuf buffer, GipfaeliSoldier soldier, List<BlockPos> posts) {
        buffer.writeVarInt(soldier.getId());
        buffer.writeVarInt(posts.size());
        for (BlockPos post : posts) {
            buffer.writeBlockPos(post);
        }
    }

    public @Nullable GipfaeliSoldier soldier() {
        return this.soldier;
    }

    // The player's guard posts by number, for the screen's "Post 1", "Post 2" buttons.
    public List<BlockPos> posts() {
        return this.posts;
    }

    // Whether things come back out of the soldier's slots: always in creative, and otherwise only
    // when they cost something to put in.
    private static boolean canTakeBack(Player player) {
        return player.hasInfiniteMaterials() || Config.ARMY_CONSUMES_SUPPLIES.get();
    }

    @Override
    public boolean stillValid(Player player) {
        return this.soldier != null
                && this.soldier.isAlive()
                && this.soldier.isOwnedBy(player)
                && player.isWithinEntityInteractionRange(this.soldier, 4.0);
    }

    // Shift-click: a piece of the soldier's goes to the pack; a piece of the pack's goes into the
    // first of the soldier's slots that will take it, or failing that between hotbar and bag.
    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        Slot slot = this.slots.get(slotIndex);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }

        ItemStack stack = slot.getItem();
        ItemStack clicked = stack.copy();
        if (slotIndex < PLAYER_SLOTS_START) {
            if (!slot.mayPickup(player) || !this.moveItemStackTo(stack, PLAYER_SLOTS_START, PLAYER_SLOTS_END, true)) {
                return ItemStack.EMPTY;
            }
        } else {
            boolean placed = false;
            for (int index = 0; index < PLAYER_SLOTS_START && !placed; index++) {
                Slot target = this.slots.get(index);
                if (target.mayPlace(stack) && !target.hasItem()) {
                    placed = this.moveItemStackTo(stack, index, index + 1, false);
                }
            }

            if (!placed) {
                int hotbarStart = PLAYER_SLOTS_END - 9;
                boolean fromHotbar = slotIndex >= hotbarStart;
                if (!this.moveItemStackTo(stack, fromHotbar ? PLAYER_SLOTS_START : hotbarStart, fromHotbar ? hotbarStart : PLAYER_SLOTS_END, false)) {
                    return ItemStack.EMPTY;
                }
            }
        }

        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }

        return clicked;
    }

    // The soldier's equipment seen as a container: five slots that are the soldier's own, read
    // live. The gun goes on through arm(), so the soldier's frame follows the kit the way it does
    // when the gun is handed over by hand.
    private final class Kit implements Container {
        @Override
        public int getContainerSize() {
            return EQUIPMENT.length;
        }

        @Override
        public boolean isEmpty() {
            for (int index = 0; index < EQUIPMENT.length; index++) {
                if (!this.getItem(index).isEmpty()) {
                    return false;
                }
            }

            return true;
        }

        @Override
        public ItemStack getItem(int index) {
            return soldier == null ? ItemStack.EMPTY : soldier.getItemBySlot(EQUIPMENT[index]);
        }

        @Override
        public ItemStack removeItem(int index, int amount) {
            ItemStack current = this.getItem(index);
            if (current.isEmpty() || amount <= 0) {
                return ItemStack.EMPTY;
            }

            ItemStack taken = current.split(amount);
            this.setItem(index, current);
            return taken;
        }

        @Override
        public ItemStack removeItemNoUpdate(int index) {
            ItemStack current = this.getItem(index);
            this.setItem(index, ItemStack.EMPTY);
            return current;
        }

        @Override
        public void setItem(int index, ItemStack stack) {
            if (soldier == null) {
                return;
            }

            if (index == KIT_SLOT) {
                soldier.arm(stack);
            } else {
                soldier.equip(EQUIPMENT[index], stack);
            }
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public void setChanged() {
        }

        @Override
        public boolean stillValid(Player player) {
            return GipfaeliSoldierMenu.this.stillValid(player);
        }

        @Override
        public void clearContent() {
            for (int index = 0; index < EQUIPMENT.length; index++) {
                this.setItem(index, ItemStack.EMPTY);
            }
        }
    }

    // The gun's slot: only one of the army's weapons goes in, one at a time, and it makes the same
    // noise going on that a handed-over gun does.
    private final class KitSlot extends Slot {
        KitSlot(Container container, int index, int x, int y) {
            super(container, index, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return GipfaeliWeapon.of(stack) != null;
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean mayPickup(Player player) {
            return canTakeBack(player) && super.mayPickup(player);
        }

        @Override
        public void setByPlayer(ItemStack stack, ItemStack previous) {
            super.setByPlayer(stack, previous);
            if (soldier != null && !stack.isEmpty() && !ItemStack.isSameItem(stack, previous)) {
                soldier.playSound(SoundEvents.ARMOR_EQUIP_IRON.value(), 1.0F, 1.0F);
            }
        }
    }
}
