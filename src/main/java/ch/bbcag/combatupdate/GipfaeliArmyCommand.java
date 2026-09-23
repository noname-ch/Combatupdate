package ch.bbcag.combatupdate;

import java.util.Arrays;
import java.util.UUID;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.DyeColor;

// /gipfaeliarmy - the orders behind the command flag's menu.
//
// The menu's buttons are these commands with the target, the shape or the colour already filled
// in (see GipfaeliArmy#menu), which is what lets a whole screen of army controls be clickable
// without a single packet of our own. Typing them works just as well, and picking a target by
// player name is the one thing the menu cannot offer for somebody who has not loaded in yet.
//
// Everything but conscripting is open to any player: an order only ever reaches that player's own
// soldiers, so there is nothing here to protect. Conscripting soldiers out of thin air is another
// matter, and that one wants operator rights.
public final class GipfaeliArmyCommand {
    private static final int CONSCRIPT_MAX = 16;

    private GipfaeliArmyCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gipfaeliarmy")
                .requires(CommandSourceStack::isPlayer)
                .executes(context -> run(context, GipfaeliArmy::menu))
                .then(Commands.literal("status")
                        .executes(context -> run(context, GipfaeliArmy::status)))
                .then(Commands.literal("targets")
                        .executes(context -> run(context, GipfaeliArmy::menu)))
                .then(Commands.literal("menu")
                        .executes(context -> run(context, GipfaeliArmy::menu)))
                .then(Commands.literal("follow")
                        .executes(context -> run(context, GipfaeliArmy::follow)))
                .then(Commands.literal("hold")
                        .executes(context -> run(context, GipfaeliArmy::hold)))
                .then(Commands.literal("standdown")
                        .executes(context -> run(context, GipfaeliArmy::standDown)))
                .then(Commands.literal("dismiss")
                        .executes(context -> run(context, GipfaeliArmy::dismiss)))
                .then(Commands.literal("attacksighted")
                        .executes(context -> run(context, GipfaeliArmy::attackSighted)))
                .then(Commands.literal("manual")
                        .executes(context -> run(context, GipfaeliArmy::manual)))
                .then(Commands.literal("attack")
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        context.getSource().getServer().getPlayerNames(), builder))
                                .executes(GipfaeliArmyCommand::attack)))
                .then(Commands.literal("formation")
                        .then(Commands.argument("shape", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        Arrays.stream(GipfaeliFormation.values()).map(GipfaeliFormation::token), builder))
                                .executes(GipfaeliArmyCommand::formation)))
                .then(Commands.literal("colour")
                        .then(Commands.argument("colour", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        Arrays.stream(DyeColor.values()).map(DyeColor::getName), builder))
                                .executes(GipfaeliArmyCommand::colour)))
                // Signing on one soldier of a given role, paid for out of the pack the way the flag
                // pays: what the menu's recruit buttons run.
                .then(Commands.literal("enlist")
                        .then(Commands.argument("role", StringArgumentType.word())
                                .suggests(GipfaeliArmyCommand::roles)
                                .executes(GipfaeliArmyCommand::enlist)))
                // A guard post's menu and its settings; what the buttons on that menu run.
                .then(Commands.literal("post")
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(context -> post(context, (commander, pos) -> GipfaeliGuardPost.menu(commander, pos)))
                                                .then(Commands.literal("recall")
                                                        .executes(context -> post(context, GipfaeliGuardPost::recall)))
                                                .then(Commands.literal("station")
                                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                                                .executes(context -> post(context, (commander, pos) -> GipfaeliGuardPost.station(
                                                                        commander, pos, IntegerArgumentType.getInteger(context, "count"))))))
                                                .then(Commands.literal("mode")
                                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                                        Arrays.stream(GipfaeliGuardPost.Mode.values()).map(GipfaeliGuardPost.Mode::token), builder))
                                                                .executes(context -> post(context, (commander, pos) -> {
                                                                    String wanted = StringArgumentType.getString(context, "mode");
                                                                    GipfaeliGuardPost.Mode mode = GipfaeliGuardPost.Mode.byName(wanted);
                                                                    if (mode == null) {
                                                                        context.getSource().sendFailure(Component.translatable("combatupdate.army.post.no_such_mode", wanted));
                                                                        return;
                                                                    }

                                                                    GipfaeliGuardPost.setMode(commander, pos, mode);
                                                                }))))
                                                .then(Commands.literal("radius")
                                                        .then(Commands.argument("radius", IntegerArgumentType.integer(1, 64))
                                                                .executes(context -> post(context, (commander, pos) -> GipfaeliGuardPost.setRadius(
                                                                        commander, pos, IntegerArgumentType.getInteger(context, "radius"))))))))))
                .then(Commands.literal("recruit")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("role", StringArgumentType.word())
                                .suggests(GipfaeliArmyCommand::roles)
                                .executes(context -> conscript(context, 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, CONSCRIPT_MAX))
                                        .executes(context -> conscript(context, IntegerArgumentType.getInteger(context, "count")))))));
    }

    private static java.util.concurrent.CompletableFuture<com.mojang.brigadier.suggestion.Suggestions> roles(
            CommandContext<CommandSourceStack> context, com.mojang.brigadier.suggestion.SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(Arrays.stream(GipfaeliWeapon.values()).map(GipfaeliWeapon::token), builder);
    }

    // Every subcommand runs through here, so the feature switch is checked once and the source is
    // turned into a player once.
    private static int run(CommandContext<CommandSourceStack> context, Consumer<ServerPlayer> order)
            throws CommandSyntaxException {
        ServerPlayer commander = commander(context);
        if (commander == null) {
            return 0;
        }

        order.accept(commander);
        return 1;
    }

    private static @Nullable ServerPlayer commander(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer commander = context.getSource().getPlayerOrException();
        if (!Config.on(Config.ENABLE_GIPFAELI_ARMY)) {
            context.getSource().sendFailure(Component.translatable("combatupdate.army.disabled"));
            return null;
        }

        return commander;
    }

    private static int post(CommandContext<CommandSourceStack> context, java.util.function.BiConsumer<ServerPlayer, BlockPos> order)
            throws CommandSyntaxException {
        ServerPlayer commander = commander(context);
        if (commander == null) {
            return 0;
        }

        BlockPos pos = new BlockPos(IntegerArgumentType.getInteger(context, "x"),
                IntegerArgumentType.getInteger(context, "y"), IntegerArgumentType.getInteger(context, "z"));
        order.accept(commander, pos);
        return 1;
    }

    private static int attack(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer commander = commander(context);
        if (commander == null) {
            return 0;
        }

        String token = StringArgumentType.getString(context, "target");
        LivingEntity target = resolve(context.getSource().getServer(), token);
        if (target == null) {
            context.getSource().sendFailure(Component.translatable("combatupdate.army.gone"));
            return 0;
        }

        GipfaeliArmy.attack(commander, target);
        return 1;
    }

    private static int formation(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer commander = commander(context);
        if (commander == null) {
            return 0;
        }

        String wanted = StringArgumentType.getString(context, "shape");
        GipfaeliFormation formation = GipfaeliFormation.byName(wanted);
        if (formation == null) {
            context.getSource().sendFailure(Component.translatable("combatupdate.army.no_such_formation", wanted));
            return 0;
        }

        GipfaeliArmy.form(commander, formation);
        return 1;
    }

    private static int colour(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer commander = commander(context);
        if (commander == null) {
            return 0;
        }

        String wanted = StringArgumentType.getString(context, "colour");
        DyeColor color = DyeColor.byName(wanted, null);
        if (color == null) {
            context.getSource().sendFailure(Component.translatable("combatupdate.army.no_such_colour", wanted));
            return 0;
        }

        GipfaeliArmy.paint(commander, color);
        return 1;
    }

    private static int enlist(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer commander = commander(context);
        if (commander == null) {
            return 0;
        }

        GipfaeliWeapon role = role(context);
        if (role == null) {
            return 0;
        }

        GipfaeliArmy.recruit(commander, role);
        return 1;
    }

    private static int conscript(CommandContext<CommandSourceStack> context, int count) throws CommandSyntaxException {
        ServerPlayer commander = commander(context);
        if (commander == null) {
            return 0;
        }

        GipfaeliWeapon role = role(context);
        return role == null ? 0 : GipfaeliArmy.conscript(commander, role, count);
    }

    private static @Nullable GipfaeliWeapon role(CommandContext<CommandSourceStack> context) {
        String wanted = StringArgumentType.getString(context, "role");
        GipfaeliWeapon role = GipfaeliWeapon.byName(wanted);
        if (role == null) {
            context.getSource().sendFailure(Component.translatable("combatupdate.army.no_such_role", wanted));
        }

        return role;
    }

    // A target named either by the UUID the menu's buttons carry, or by the name of a player who is
    // on the server - the two ways of naming something that stay true while it moves about.
    private static @Nullable LivingEntity resolve(MinecraftServer server, String token) {
        try {
            UUID id = UUID.fromString(token);
            for (ServerLevel level : server.getAllLevels()) {
                if (level.getEntity(id) instanceof LivingEntity target && target.isAlive()) {
                    return target;
                }
            }
        } catch (IllegalArgumentException notAUuid) {
            // Then it was meant as a name, which is the next thing tried.
        }

        return server.getPlayerList().getPlayerByName(token);
    }
}
