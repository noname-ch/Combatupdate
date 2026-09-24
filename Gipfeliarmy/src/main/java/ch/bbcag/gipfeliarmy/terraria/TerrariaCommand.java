package ch.bbcag.gipfeliarmy.terraria;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntitySpawnReason;

// /terraria: where the lairs are, for anyone; and for operators, a boss summoned on the spot, every
// boss woken up again, or every lair forgotten and put somewhere new.
public final class TerrariaCommand {
    private TerrariaCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> summon = Commands.literal("summon")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS));
        for (BossKind kind : BossKind.values()) {
            summon.then(Commands.literal(kind.id()).executes(context -> summon(context.getSource(), kind)));
        }

        dispatcher.register(Commands.literal("terraria")
                .then(Commands.literal("where").executes(context -> where(context.getSource())))
                .then(summon)
                .then(Commands.literal("ready")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> ready(context.getSource())))
                .then(Commands.literal("relocate")
                        .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                        .executes(context -> relocate(context.getSource()))));
    }

    private static int where(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        BossArenas data = BossArenas.get(server);
        if (data.arenas().isEmpty()) {
            source.sendFailure(Component.translatable("gipfeliarmy.terraria.command.none"));
            return 0;
        }

        long now = server.overworld().getGameTime();
        for (BossArenas.Arena arena : data.arenas()) {
            var door = arena.door();
            Component status = arena.readyAt() > now
                    ? Component.translatable("gipfeliarmy.terraria.command.sleeping", (arena.readyAt() - now) / 1200 + 1)
                            .withStyle(ChatFormatting.GRAY)
                    : Component.translatable("gipfeliarmy.terraria.command.awake").withStyle(ChatFormatting.RED);
            source.sendSuccess(() -> Component.translatable("gipfeliarmy.terraria.command.where",
                    arena.kind().lairName(), door.getX(), arena.built() ? String.valueOf(door.getY()) : "?", door.getZ(), status), false);
        }
        return data.arenas().size();
    }

    private static int summon(CommandSourceStack source, BossKind kind) {
        ServerLevel level = source.getLevel();
        TerrariaBoss boss = kind.type().create(level, EntitySpawnReason.COMMAND);
        if (boss == null) {
            return 0;
        }

        boss.setPos(source.getPosition().add(0, kind == BossKind.EYE_OF_CTHULHU ? 8 : 0, 0));
        if (source.getEntity() instanceof ServerPlayer player && kind == BossKind.WALL_OF_FLESH) {
            // Far enough behind the player that there is time to turn round.
            boss.setPos(player.position().add(-12, 0, 0));
        }
        level.addFreshEntity(boss);
        source.sendSuccess(() -> Component.translatable("gipfeliarmy.terraria.awoken", kind.displayName())
                .withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD), true);
        return 1;
    }

    private static int ready(CommandSourceStack source) {
        BossArenas data = BossArenas.get(source.getServer());
        data.readyAll(source.getServer());
        source.sendSuccess(() -> Component.translatable("gipfeliarmy.terraria.command.ready"), true);
        return 1;
    }

    private static int relocate(CommandSourceStack source) {
        BossArenas.get(source.getServer()).reset(source.getServer());
        source.sendSuccess(() -> Component.translatable("gipfeliarmy.terraria.command.relocated"), true);
        return 1;
    }
}
