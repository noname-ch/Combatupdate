package ch.bbcag.combatupdate;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

// /gipfaeliarmy - the orders behind the command flag's menu.
//
// The menu's buttons are these commands with the target's UUID already filled in (see
// GipfaeliArmy#menu), which is what lets a list of everything on the map be clickable without a
// single packet of our own. Typing them works just as well, and picking a target by player name is
// the one thing the menu cannot offer for somebody who has not loaded in yet.
//
// Everything but recruiting is open to any player: an order only ever reaches that player's own
// soldiers, so there is nothing here to protect. Conscripting soldiers out of thin air is another
// matter, and that one wants operator rights.
public final class GipfaeliArmyCommand {
    private static final int CONSCRIPT_MAX = 16;

    private GipfaeliArmyCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("gipfaeliarmy")
                .requires(CommandSourceStack::isPlayer)
                .executes(context -> run(context, GipfaeliArmy::status))
                .then(Commands.literal("status")
                        .executes(context -> run(context, GipfaeliArmy::status)))
                .then(Commands.literal("targets")
                        .executes(context -> run(context, GipfaeliArmy::menu)))
                .then(Commands.literal("follow")
                        .executes(context -> run(context, GipfaeliArmy::follow)))
                .then(Commands.literal("hold")
                        .executes(context -> run(context, GipfaeliArmy::hold)))
                .then(Commands.literal("standdown")
                        .executes(context -> run(context, GipfaeliArmy::standDown)))
                .then(Commands.literal("dismiss")
                        .executes(context -> run(context, GipfaeliArmy::dismiss)))
                .then(Commands.literal("attack")
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        context.getSource().getServer().getPlayerNames(), builder))
                                .executes(GipfaeliArmyCommand::attack)))
                .then(Commands.literal("recruit")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("weapon", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        Arrays.stream(GipfaeliWeapon.values()).map(GipfaeliArmyCommand::name), builder))
                                .executes(context -> conscript(context, 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, CONSCRIPT_MAX))
                                        .executes(context -> conscript(context, IntegerArgumentType.getInteger(context, "count")))))));
    }

    // Every subcommand runs through here, so the feature switch is checked once and the source is
    // turned into a player once.
    private static int run(CommandContext<CommandSourceStack> context, java.util.function.Consumer<ServerPlayer> order)
            throws CommandSyntaxException {
        ServerPlayer commander = context.getSource().getPlayerOrException();
        if (!Config.on(Config.ENABLE_GIPFAELI_ARMY)) {
            context.getSource().sendFailure(Component.translatable("combatupdate.army.disabled"));
            return 0;
        }

        order.accept(commander);
        return 1;
    }

    private static int attack(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer commander = context.getSource().getPlayerOrException();
        if (!Config.on(Config.ENABLE_GIPFAELI_ARMY)) {
            context.getSource().sendFailure(Component.translatable("combatupdate.army.disabled"));
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

    private static int conscript(CommandContext<CommandSourceStack> context, int count) throws CommandSyntaxException {
        ServerPlayer commander = context.getSource().getPlayerOrException();
        String wanted = StringArgumentType.getString(context, "weapon");
        GipfaeliWeapon weapon = null;
        for (GipfaeliWeapon candidate : GipfaeliWeapon.values()) {
            if (name(candidate).equalsIgnoreCase(wanted)) {
                weapon = candidate;
            }
        }

        if (weapon == null) {
            context.getSource().sendFailure(Component.translatable("combatupdate.army.no_such_weapon", wanted));
            return 0;
        }

        return GipfaeliArmy.conscript(commander, weapon, count);
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

    private static String name(GipfaeliWeapon weapon) {
        return weapon.name().toLowerCase(Locale.ROOT);
    }
}
