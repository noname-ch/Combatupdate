package ch.bbcag.combatupdate;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;

import ch.bbcag.combatupdate.entity.GipfaeliSoldier;

// The guard post: a block set down wherever there is something to defend, that soldiers can be
// stationed at and told how to behave there. Right-click it for the menu.
//
// The post is a block rather than a spot in the ground so that it can be seen, walked up to,
// clicked, and broken: a fortress gate with a post beside it reads as guarded, and taking the
// post out is how an attacker takes the guard off it. What the post remembers - whose it is, how
// wide its watch is, how touchy its guards are - lives on the block itself, so it survives a
// restart with the walls around it. Which soldiers stand there is the soldiers' own memory (see
// GipfaeliSoldier#post), the same way a siege is.
public final class GipfaeliGuardPost {
    // How guards at a post treat what comes near it.
    public enum Mode {
        // Monsters and anything that starts a fight. The default: a post that keeps the night out.
        DEFEND,
        // Everyone who is not on the owner's side, players included, within the watch. A post on
        // the wall of somewhere strangers are not welcome.
        AGGRESSIVE,
        // Nothing until hit. A post that only ever answers.
        PASSIVE;

        private static final Mode[] ALL = values();

        public static Mode byOrdinal(int ordinal) {
            return ordinal >= 0 && ordinal < ALL.length ? ALL[ordinal] : DEFEND;
        }

        public static @Nullable Mode byName(String name) {
            for (Mode mode : ALL) {
                if (mode.token().equalsIgnoreCase(name)) {
                    return mode;
                }
            }

            return null;
        }

        public String token() {
            return this.name().toLowerCase(Locale.ROOT);
        }

        public String key() {
            return "combatupdate.army.post.mode." + this.token();
        }
    }

    // The three watches a post can keep. Small, so the menu is three buttons rather than a slider,
    // and so a patrol never wanders further than a soldier can walk back from in a moment.
    public static final int[] RADII = {4, 8, 16};
    private static final int DEFAULT_RADIUS = 8;

    private static final int CLICK_COOLDOWN_TICKS = 10;

    private GipfaeliGuardPost() {
    }

    // --- The block ---

    public static final class PostBlock extends Block implements EntityBlock {
        public PostBlock(Properties properties) {
            super(properties);
        }

        @Override
        public @Nullable BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
            return new Post(pos, state);
        }

        // Whoever sets it down owns it, and is the only one its menu answers to.
        @Override
        public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity by, ItemStack stack) {
            super.setPlacedBy(level, pos, state, by, stack);
            if (by instanceof ServerPlayer player && level instanceof ServerLevel serverLevel
                    && level.getBlockEntity(pos) instanceof Post post) {
                post.claim(player);
                GipfaeliArmyData.get(serverLevel.getServer()).addPost(player.getUUID(), serverLevel, pos);
                GipfaeliArmy.readout(player, Component.translatable("combatupdate.army.post.placed",
                        number(player, serverLevel, pos)));
            }
        }

        @Override
        protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
            if (level instanceof ServerLevel && player instanceof ServerPlayer commander) {
                menu(commander, pos);
            }

            return InteractionResult.SUCCESS;
        }
    }

    // --- What the block remembers ---

    public static final class Post extends BlockEntity {
        private @Nullable UUID owner;
        private String ownerName = "";
        private Mode mode = Mode.DEFEND;
        private int radius = DEFAULT_RADIUS;

        public Post(BlockPos pos, BlockState state) {
            super(CombatUpdate.GIPFAELI_GUARD_POST_ENTITY.get(), pos, state);
        }

        void claim(ServerPlayer player) {
            this.owner = player.getUUID();
            this.ownerName = player.getGameProfile().name();
            this.setChanged();
        }

        public @Nullable UUID owner() {
            return this.owner;
        }

        public Mode mode() {
            return this.mode;
        }

        public int radius() {
            return this.radius;
        }

        void setMode(Mode mode) {
            this.mode = mode;
            this.setChanged();
        }

        void setRadius(int radius) {
            this.radius = radius;
            this.setChanged();
        }

        @Override
        protected void saveAdditional(ValueOutput output) {
            super.saveAdditional(output);
            if (this.owner != null) {
                output.store("Owner", UUIDUtil.CODEC, this.owner);
            }

            output.putString("OwnerName", this.ownerName);
            output.putInt("Mode", this.mode.ordinal());
            output.putInt("Radius", this.radius);
        }

        @Override
        protected void loadAdditional(ValueInput input) {
            super.loadAdditional(input);
            this.owner = input.read("Owner", UUIDUtil.CODEC).orElse(null);
            this.ownerName = input.getStringOr("OwnerName", "");
            this.mode = Mode.byOrdinal(input.getIntOr("Mode", 0));
            this.radius = input.getIntOr("Radius", DEFAULT_RADIUS);
        }
    }

    // --- What it is told ---

    // The post's menu, in chat like the army's: how many stand here, how they behave, how far
    // they range, each a button.
    public static void menu(ServerPlayer commander, BlockPos pos) {
        Post post = postAt(commander, pos);
        if (post == null) {
            return;
        }

        List<GipfaeliSoldier> guards = GipfaeliArmy.guardsAt(commander.level(), pos, commander);
        String at = pos.getX() + " " + pos.getY() + " " + pos.getZ() + " ";

        commander.sendSystemMessage(Component.literal(" ═══════ ").withStyle(ChatFormatting.DARK_GRAY)
                .append(Component.translatable("combatupdate.army.post.title", number(commander, commander.level(), pos),
                        pos.getX(), pos.getY(), pos.getZ())
                        .withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD))
                .append(Component.literal(" ═══════ ").withStyle(ChatFormatting.DARK_GRAY)));
        commander.sendSystemMessage(Component.translatable("combatupdate.army.post.status",
                guards.size(), Component.translatable(post.mode().key()), post.radius()).withStyle(ChatFormatting.GRAY));

        MutableComponent station = heading("combatupdate.army.post.station");
        for (int count : new int[] {1, 2, 4}) {
            station.append(button(Component.literal("+" + count), at + "station " + count, null, ChatFormatting.YELLOW));
        }
        station.append(button(Component.translatable("combatupdate.army.post.all"), at + "station 64", null, ChatFormatting.YELLOW));
        station.append(button(Component.translatable("combatupdate.army.post.recall"), at + "recall",
                Component.translatable("combatupdate.army.post.recall.hover"), ChatFormatting.GREEN));
        commander.sendSystemMessage(station);

        MutableComponent modes = heading("combatupdate.army.post.mode");
        for (Mode mode : Mode.values()) {
            modes.append(button(Component.translatable(mode.key()), at + "mode " + mode.token(),
                    Component.translatable(mode.key() + ".hover"),
                    mode == post.mode() ? ChatFormatting.WHITE : ChatFormatting.AQUA));
        }
        commander.sendSystemMessage(modes);

        MutableComponent radii = heading("combatupdate.army.post.radius");
        for (int radius : RADII) {
            radii.append(button(Component.literal(Integer.toString(radius)), at + "radius " + radius, null,
                    radius == post.radius() ? ChatFormatting.WHITE : ChatFormatting.AQUA));
        }
        commander.sendSystemMessage(radii);
    }

    public static void station(ServerPlayer commander, BlockPos pos, int count) {
        Post post = postAt(commander, pos);
        if (post != null) {
            GipfaeliArmy.garrisonPost(commander, pos, post.radius(), count);
        }
    }

    public static void recall(ServerPlayer commander, BlockPos pos) {
        Post post = postAt(commander, pos);
        if (post != null) {
            GipfaeliArmy.recallPost(commander, pos);
        }
    }

    public static void setMode(ServerPlayer commander, BlockPos pos, Mode mode) {
        Post post = postAt(commander, pos);
        if (post != null) {
            post.setMode(mode);
            GipfaeliArmy.readout(commander, Component.translatable("combatupdate.army.post.set_mode", Component.translatable(mode.key())));
        }
    }

    public static void setRadius(ServerPlayer commander, BlockPos pos, int radius) {
        Post post = postAt(commander, pos);
        if (post != null) {
            int chosen = RADII[0];
            for (int allowed : RADII) {
                if (allowed == radius) {
                    chosen = allowed;
                }
            }

            post.setRadius(chosen);
            GipfaeliArmy.readout(commander, Component.translatable("combatupdate.army.post.set_radius", chosen));
        }
    }

    // Which number this post is to its owner: the order it was set down in.
    public static int number(ServerPlayer owner, ServerLevel level, BlockPos pos) {
        return GipfaeliArmyData.get(level.getServer()).number(level.getServer(), owner.getUUID(), level, pos);
    }

    // The post at pos, if there is one and this player may give it orders: its owner, or an op.
    // Anyone else is told whose it is and gets nothing.
    private static @Nullable Post postAt(ServerPlayer commander, BlockPos pos) {
        if (!(commander.level().getBlockEntity(pos) instanceof Post post)) {
            refuse(commander, Component.translatable("combatupdate.army.post.gone"));
            return null;
        }

        boolean owner = post.owner() == null || post.owner().equals(commander.getUUID());
        boolean op = commander.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
        if (!owner && !op) {
            refuse(commander, Component.translatable("combatupdate.army.post.not_yours", post.ownerName));
            return null;
        }

        // A post that was set down by nobody in particular - a structure, a command block -
        // belongs to the first commander who uses it.
        if (post.owner() == null) {
            post.claim(commander);
        }

        return post;
    }

    // The command flag on a post opens its menu rather than signing a recruit on beside it.
    public static boolean useFlag(ServerPlayer commander, ItemStack stack, BlockPos pos) {
        if (!Config.on(Config.ENABLE_GIPFAELI_ARMY)
                || !stack.is(CombatUpdate.GIPFAELI_COMMAND_FLAG.get())
                || !(commander.level().getBlockEntity(pos) instanceof Post)) {
            return false;
        }

        if (!commander.getCooldowns().isOnCooldown(stack)) {
            menu(commander, pos);
            commander.getCooldowns().addCooldown(stack, CLICK_COOLDOWN_TICKS);
        }

        return true;
    }

    private static Component button(Component label, String command, @Nullable Component hover, ChatFormatting color) {
        return Component.literal(" [").append(label).append(Component.literal("]"))
                .withStyle(style -> {
                    var styled = style.withColor(color).withClickEvent(new ClickEvent.RunCommand("/gipfaeliarmy post " + command));
                    return hover == null ? styled : styled.withHoverEvent(new HoverEvent.ShowText(hover));
                });
    }

    private static MutableComponent heading(String key) {
        return Component.translatable(key).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
    }

    private static void refuse(ServerPlayer commander, Component message) {
        commander.level().playSound(null, commander.getX(), commander.getY(), commander.getZ(),
                SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.8F, 1.0F);
        GipfaeliArmy.readout(commander, message);
    }
}
