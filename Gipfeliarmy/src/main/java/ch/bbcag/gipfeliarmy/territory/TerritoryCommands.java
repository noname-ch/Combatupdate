package ch.bbcag.gipfeliarmy.territory;

import java.util.function.Predicate;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

// /territory, for anyone who would rather type than open the screen, and for the chunk they are
// standing in only: the screen is where a chunk gets picked off a map.
public final class TerritoryCommands {
    private TerritoryCommands() {
    }

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("territory")
                .then(Commands.literal("claim").executes(context -> run(context, TerritoryManager::claim)))
                .then(Commands.literal("unclaim").executes(context -> run(context,
                        player -> TerritoryManager.unclaim(player, player.chunkPosition().x(), player.chunkPosition().z()))))
                .then(Commands.literal("capture").executes(context -> run(context, TerritoryManager::capture)))
                .then(Commands.literal("cancel").executes(context -> run(context, TerritoryManager::cancelCapture)))
                .then(Commands.literal("info").executes(context -> run(context, TerritoryManager::info)))
                .then(Commands.literal("list").executes(context -> run(context, TerritoryManager::list)))
                .executes(context -> run(context, TerritoryManager::info)));
    }

    private static int run(CommandContext<CommandSourceStack> context, Predicate<ServerPlayer> action)
            throws CommandSyntaxException {
        return action.test(context.getSource().getPlayerOrException()) ? 1 : 0;
    }
}
