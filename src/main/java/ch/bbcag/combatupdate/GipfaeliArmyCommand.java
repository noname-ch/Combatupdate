package ch.bbcag.combatupdate;

import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.DyeColor;

import ch.bbcag.combatupdate.GipfaeliArmy.Scope;
import ch.bbcag.combatupdate.entity.GipfaeliSoldier;
import ch.bbcag.combatupdate.entity.GipfaeliSoldier.Stance;

// /gipfaeliarmy - the orders behind every button on the army's menus.
//
// The menus are these commands with the target, the shape, the colour or the soldier already
// filled in (see GipfaeliArmy), which is what lets a whole screen of army controls be clickable
// without a single packet of our own. Typing them works just as well, and picking a target by
// player name is the one thing the menus cannot offer for somebody who has not loaded in yet.
//
// The orders themselves are registered twice: at the root, for the whole army, and again under
// "squad <colour>", for one squad - the same code either way, with a different scope in front.
//
// Everything but conscripting is open to any player: an order only ever reaches that player's own
// soldiers, so there is nothing here to protect. Conscripting soldiers out of thin air is another
// matter, and that one wants operator rights.
public final class GipfaeliArmyCommand {
    private static final int CONSCRIPT_MAX = 16;

    // Who an order is for, read off the command it came in on.
    private interface ScopeSource {
        @Nullable Scope scope(CommandContext<CommandSourceStack> context);
    }

    private interface Order {
        void run(ServerPlayer commander, Scope scope);
    }

    private GipfaeliArmyCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // Short forms for typing: /soldats attack Steve for the whole army, /soldats-red hold for
        // one squad, /soldats-camo follow for the reserve. Every order the long form takes.
        // /soldaten is the same again, for whoever thinks of them in German.
        for (String root : new String[] {"soldats", "soldaten"}) {
            dispatcher.register(orders(Commands.literal(root), context -> Scope.ALL)
                    .requires(CommandSourceStack::isPlayer)
                    .executes(context -> run(context, GipfaeliArmy::menu)));
            dispatcher.register(orders(Commands.literal(root + "-camo"), context -> Scope.RESERVE)
                    .requires(CommandSourceStack::isPlayer)
                    .executes(context -> scoped(context, c -> Scope.RESERVE, GipfaeliArmy::squadMenu)));
            for (DyeColor colour : DyeColor.values()) {
                Scope scope = Scope.of(colour);
                dispatcher.register(orders(Commands.literal(root + "-" + colour.getName()), context -> scope)
                        .requires(CommandSourceStack::isPlayer)
                        .executes(context -> scoped(context, c -> scope, GipfaeliArmy::squadMenu)));
            }
        }

        dispatcher.register(orders(Commands.literal("gipfaeliarmy"), context -> Scope.ALL)
                .requires(CommandSourceStack::isPlayer)
                .executes(context -> run(context, GipfaeliArmy::menu))
                .then(Commands.literal("status")
                        .executes(context -> run(context, GipfaeliArmy::status)))
                .then(Commands.literal("targets")
                        .executes(context -> run(context, GipfaeliArmy::menu)))
                .then(Commands.literal("menu")
                        .executes(context -> run(context, GipfaeliArmy::menu)))
                .then(Commands.literal("manual")
                        .executes(context -> run(context, GipfaeliArmy::manual)))
                // Something to shoot at: dummies in front of the player, armed or not, and gone again.
                .then(Commands.literal("training")
                        .executes(context -> run(context, commander -> GipfaeliArmy.training(commander, 5, false)))
                        .then(Commands.literal("clear")
                                .executes(context -> run(context, GipfaeliArmy::clearTraining)))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 30))
                                .executes(context -> run(context, commander -> GipfaeliArmy.training(
                                        commander, IntegerArgumentType.getInteger(context, "count"), false)))
                                .then(Commands.literal("armed")
                                        .executes(context -> run(context, commander -> GipfaeliArmy.training(
                                                commander, IntegerArgumentType.getInteger(context, "count"), true))))))
                // A new squad: what the flag does.
                .then(Commands.literal("raise")
                        .executes(context -> run(context, GipfaeliArmy::raiseSquad)))
                // One squad: its menu, and every order the army takes, for it alone.
                .then(Commands.literal("squad")
                        .then(orders(Commands.argument("squad", StringArgumentType.word()), GipfaeliArmyCommand::squadScope)
                                .suggests(GipfaeliArmyCommand::scopes)
                                .executes(context -> scoped(context, GipfaeliArmyCommand::squadScope, GipfaeliArmy::squadMenu))
                                .then(Commands.literal("menu")
                                        .executes(context -> scoped(context, GipfaeliArmyCommand::squadScope, GipfaeliArmy::squadMenu)))))
                // One soldier's own menu and what it hands out; what clicking a soldier runs.
                .then(Commands.literal("soldier")
                        .then(Commands.argument("soldier", StringArgumentType.word())
                                .executes(context -> soldier(context, GipfaeliArmy::soldierMenu))
                                .then(Commands.literal("disarm")
                                        .executes(context -> soldier(context, GipfaeliArmy::disarmSoldier)))
                                .then(Commands.literal("strip")
                                        .executes(context -> soldier(context, GipfaeliArmy::stripSoldier)))
                                .then(Commands.literal("promote")
                                        .executes(context -> soldier(context, GipfaeliArmy::promote)))
                                .then(Commands.literal("post")
                                        .then(Commands.argument("number", IntegerArgumentType.integer(1))
                                                .executes(context -> soldier(context, (commander, soldier) -> GipfaeliArmy.sendToPost(
                                                        commander, soldier, IntegerArgumentType.getInteger(context, "number"))))))
                                .then(Commands.literal("demote")
                                        .executes(context -> soldier(context, GipfaeliArmy::demote)))
                                .then(Commands.literal("kit")
                                        .then(Commands.argument("role", StringArgumentType.word())
                                                .suggests(GipfaeliArmyCommand::roles)
                                                .executes(context -> soldier(context, (commander, soldier) -> {
                                                    GipfaeliWeapon role = role(context);
                                                    if (role != null) {
                                                        GipfaeliArmy.armSoldier(commander, soldier, role);
                                                    }
                                                }))))
                                .then(Commands.literal("armour")
                                        .then(Commands.argument("armour", StringArgumentType.word())
                                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                        Arrays.stream(GipfaeliArmour.values()).map(GipfaeliArmour::token), builder))
                                                .executes(context -> soldier(context, (commander, soldier) -> {
                                                    String wanted = StringArgumentType.getString(context, "armour");
                                                    GipfaeliArmour armour = GipfaeliArmour.byName(wanted);
                                                    if (armour == null) {
                                                        context.getSource().sendFailure(Component.translatable("combatupdate.army.no_such_armour", wanted));
                                                        return;
                                                    }

                                                    GipfaeliArmy.dressSoldier(commander, soldier, armour);
                                                }))))
                                .then(Commands.literal("camo")
                                        .then(Commands.argument("pattern", StringArgumentType.word())
                                                .suggests(GipfaeliArmyCommand::patterns)
                                                .executes(context -> soldier(context, (commander, soldier) -> {
                                                    GipfaeliCamo camo = pattern(context);
                                                    if (camo != null) {
                                                        GipfaeliCamo.dressSoldier(commander, soldier, camo);
                                                    }
                                                }))))
                                .then(Commands.literal("colour")
                                        .then(Commands.argument("colour", StringArgumentType.word())
                                                .suggests(GipfaeliArmyCommand::colours)
                                                .executes(context -> soldier(context, (commander, soldier) -> {
                                                    String wanted = StringArgumentType.getString(context, "colour");
                                                    if (!wanted.equalsIgnoreCase("camo") && DyeColor.byName(wanted, null) == null) {
                                                        context.getSource().sendFailure(Component.translatable("combatupdate.army.no_such_colour", wanted));
                                                        return;
                                                    }

                                                    GipfaeliArmy.paintSoldier(commander, soldier, DyeColor.byName(wanted, null));
                                                }))))))
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
                // A parade ground out of nothing: operator-only like recruiting, and without the
                // army cap, because the number asked for is the whole point of the order.
                .then(Commands.literal("parade")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("role", StringArgumentType.word())
                                .suggests(GipfaeliArmyCommand::roles)
                                .executes(context -> parade(context, GipfaeliParade.perBlock(), true))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1))
                                        .executes(context -> parade(context, IntegerArgumentType.getInteger(context, "count"), true))
                                        .then(Commands.literal("ahead")
                                                .executes(context -> parade(context, IntegerArgumentType.getInteger(context, "count"), true)))
                                        .then(Commands.literal("behind")
                                                .executes(context -> parade(context, IntegerArgumentType.getInteger(context, "count"), false))))))
                // A fortress with its garrison, around whoever asks: operator-only, since it lays
                // a few hundred blocks of wall across whatever was there.
                .then(Commands.literal("fortress")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> run(context, commander -> GipfaeliFortress.build(commander, GipfaeliFortress.defaultRadius(null))))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(6, 40))
                                .executes(context -> run(context, commander -> GipfaeliFortress.build(
                                        commander, IntegerArgumentType.getInteger(context, "radius"))))))
                .then(Commands.literal("recruit")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .then(Commands.argument("role", StringArgumentType.word())
                                .suggests(GipfaeliArmyCommand::roles)
                                .executes(context -> conscript(context, 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, CONSCRIPT_MAX))
                                        .executes(context -> conscript(context, IntegerArgumentType.getInteger(context, "count")))))));
    }

    // The orders the army and any one squad both take, hung off whichever node they belong under.
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T orders(T node, ScopeSource scope) {
        return node
                .then(Commands.literal("follow")
                        .executes(context -> scoped(context, scope, GipfaeliArmy::follow)))
                .then(Commands.literal("hold")
                        .executes(context -> scoped(context, scope, GipfaeliArmy::hold)))
                .then(Commands.literal("standdown")
                        .executes(context -> scoped(context, scope, GipfaeliArmy::standDown)))
                .then(Commands.literal("dismiss")
                        .executes(context -> scoped(context, scope, GipfaeliArmy::dismiss)))
                .then(Commands.literal("attacksighted")
                        .executes(context -> scoped(context, scope, GipfaeliArmy::attackSighted)))
                .then(Commands.literal("stance")
                        .then(Commands.literal("attack")
                                .executes(context -> scoped(context, scope, (commander, s) -> GipfaeliArmy.stance(commander, s, Stance.ATTACK, false))))
                        .then(Commands.literal("stand")
                                .executes(context -> scoped(context, scope, (commander, s) -> GipfaeliArmy.stance(commander, s, Stance.STAND, false))))
                        .then(Commands.literal("standfollow")
                                .executes(context -> scoped(context, scope, (commander, s) -> GipfaeliArmy.stance(commander, s, Stance.STAND, true)))))
                // A player by name, or a whole side - a squad's colour, the reserve, the training
                // dummies - to be fought until nobody of it is left (see GipfaeliAssault).
                .then(Commands.literal("attack")
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(Stream.concat(
                                        GipfaeliAssault.SUGGESTIONS.stream(),
                                        Arrays.stream(context.getSource().getServer().getPlayerNames())), builder))
                                .executes(context -> scoped(context, scope, (commander, s) -> {
                                    String token = StringArgumentType.getString(context, "target");
                                    GipfaeliAssault.Foe foe = GipfaeliAssault.Foe.parse(token);
                                    if (foe != null) {
                                        GipfaeliAssault.start(commander, s, foe);
                                        return;
                                    }

                                    LivingEntity target = resolve(context.getSource().getServer(), token);
                                    if (target == null) {
                                        context.getSource().sendFailure(Component.translatable("combatupdate.army.gone"));
                                        return;
                                    }

                                    GipfaeliArmy.attack(commander, s, target);
                                }))))
                .then(Commands.literal("formation")
                        .then(Commands.argument("shape", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        Arrays.stream(GipfaeliFormation.values()).map(GipfaeliFormation::token), builder))
                                .executes(context -> scoped(context, scope, (commander, s) -> {
                                    String wanted = StringArgumentType.getString(context, "shape");
                                    GipfaeliFormation formation = GipfaeliFormation.byName(wanted);
                                    if (formation == null) {
                                        context.getSource().sendFailure(Component.translatable("combatupdate.army.no_such_formation", wanted));
                                        return;
                                    }

                                    GipfaeliArmy.form(commander, s, formation);
                                }))))
                .then(Commands.literal("camo")
                        .then(Commands.argument("pattern", StringArgumentType.word())
                                .suggests(GipfaeliArmyCommand::patterns)
                                .executes(context -> scoped(context, scope, (commander, s) -> {
                                    GipfaeliCamo camo = pattern(context);
                                    if (camo != null) {
                                        GipfaeliCamo.dressSquad(commander, s, camo);
                                    }
                                }))))
                .then(Commands.literal("colour")
                        .then(Commands.argument("colour", StringArgumentType.word())
                                .suggests(GipfaeliArmyCommand::colours)
                                .executes(context -> scoped(context, scope, (commander, s) -> {
                                    String wanted = StringArgumentType.getString(context, "colour");
                                    if (!wanted.equalsIgnoreCase("camo") && DyeColor.byName(wanted, null) == null) {
                                        context.getSource().sendFailure(Component.translatable("combatupdate.army.no_such_colour", wanted));
                                        return;
                                    }

                                    GipfaeliArmy.paint(commander, s, DyeColor.byName(wanted, null));
                                }))))
                // The whole squad's kit and armour, from its commander's menu.
                .then(Commands.literal("kit")
                        .then(Commands.argument("role", StringArgumentType.word())
                                .suggests(GipfaeliArmyCommand::roles)
                                .executes(context -> scoped(context, scope, (commander, s) -> {
                                    GipfaeliWeapon role = role(context);
                                    if (role != null) {
                                        GipfaeliArmy.armSquad(commander, s, role);
                                    }
                                }))))
                .then(Commands.literal("disarm")
                        .executes(context -> scoped(context, scope, GipfaeliArmy::disarmSquad)))
                .then(Commands.literal("armour")
                        .then(Commands.argument("armour", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                        Arrays.stream(GipfaeliArmour.values()).map(GipfaeliArmour::token), builder))
                                .executes(context -> scoped(context, scope, (commander, s) -> {
                                    String wanted = StringArgumentType.getString(context, "armour");
                                    GipfaeliArmour armour = GipfaeliArmour.byName(wanted);
                                    if (armour == null) {
                                        context.getSource().sendFailure(Component.translatable("combatupdate.army.no_such_armour", wanted));
                                        return;
                                    }

                                    GipfaeliArmy.dressSquad(commander, s, armour);
                                }))))
                .then(Commands.literal("strip")
                        .executes(context -> scoped(context, scope, GipfaeliArmy::stripSquad)))
                // Filling the squad the order is for with unarmed soldiers: what a commander's spawn
                // buttons run.
                .then(Commands.literal("fill")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 200))
                                .executes(context -> scoped(context, scope, (commander, s) -> GipfaeliArmy.fill(
                                        commander, s, IntegerArgumentType.getInteger(context, "count"))))))
                // Signing on one soldier: unarmed, for its rations, into the squad the order is for
                // - the reserve, from the army's own menu; or with a role's kit out of the pack
                // when one is named.
                .then(Commands.literal("enlist")
                        .executes(context -> scoped(context, scope, (commander, s) -> GipfaeliArmy.recruit(commander, null, s)))
                        .then(Commands.argument("role", StringArgumentType.word())
                                .suggests(GipfaeliArmyCommand::roles)
                                .executes(context -> scoped(context, scope, (commander, s) -> {
                                    GipfaeliWeapon role = role(context);
                                    if (role != null) {
                                        GipfaeliArmy.recruit(commander, role, s);
                                    }
                                }))));
    }

    private static @Nullable Scope squadScope(CommandContext<CommandSourceStack> context) {
        return Scope.parse(StringArgumentType.getString(context, "squad"));
    }

    private static CompletableFuture<Suggestions> scopes(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                Stream.concat(Stream.of("all", "camo"), Arrays.stream(DyeColor.values()).map(DyeColor::getName)), builder);
    }

    private static CompletableFuture<Suggestions> colours(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(
                Stream.concat(Arrays.stream(DyeColor.values()).map(DyeColor::getName), Stream.of("camo")), builder);
    }

    private static CompletableFuture<Suggestions> patterns(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(Arrays.stream(GipfaeliCamo.values()).map(GipfaeliCamo::token), builder);
    }

    private static @Nullable GipfaeliCamo pattern(CommandContext<CommandSourceStack> context) {
        String wanted = StringArgumentType.getString(context, "pattern");
        GipfaeliCamo camo = GipfaeliCamo.byName(wanted);
        if (camo == null) {
            context.getSource().sendFailure(Component.translatable("combatupdate.army.no_such_camo", wanted));
        }

        return camo;
    }

    private static CompletableFuture<Suggestions> roles(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
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

    // The same, for an order that wants to know who it is for.
    private static int scoped(CommandContext<CommandSourceStack> context, ScopeSource source, Order order)
            throws CommandSyntaxException {
        ServerPlayer commander = commander(context);
        if (commander == null) {
            return 0;
        }

        Scope scope = source.scope(context);
        if (scope == null) {
            context.getSource().sendFailure(Component.translatable("combatupdate.army.no_such_squad", StringArgumentType.getString(context, "squad")));
            return 0;
        }

        order.run(commander, scope);
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

    // Every soldier-menu button runs through here: the soldier it names has to be the player's
    // own and somewhere loaded, or the button does nothing but say so.
    private static int soldier(CommandContext<CommandSourceStack> context, BiConsumer<ServerPlayer, GipfaeliSoldier> order)
            throws CommandSyntaxException {
        ServerPlayer commander = commander(context);
        if (commander == null) {
            return 0;
        }

        GipfaeliSoldier soldier;
        try {
            soldier = GipfaeliArmy.soldierOf(commander, UUID.fromString(StringArgumentType.getString(context, "soldier")));
        } catch (IllegalArgumentException notAUuid) {
            soldier = null;
        }

        if (soldier == null) {
            context.getSource().sendFailure(Component.translatable("combatupdate.army.soldier.gone"));
            return 0;
        }

        order.accept(commander, soldier);
        return 1;
    }

    private static int post(CommandContext<CommandSourceStack> context, BiConsumer<ServerPlayer, BlockPos> order)
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

    private static int parade(CommandContext<CommandSourceStack> context, int count, boolean ahead)
            throws CommandSyntaxException {
        ServerPlayer commander = commander(context);
        if (commander == null) {
            return 0;
        }

        GipfaeliWeapon role = role(context);
        return role == null ? 0 : GipfaeliArmy.parade(commander, role, count, ahead);
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
