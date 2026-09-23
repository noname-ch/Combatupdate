package ch.bbcag.combatupdate;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.combatupdate.entity.GipfaeliGrenade;

// The Gipfaeli hand grenade: a pastry with a pin in it. Right-click lobs one out of the stack; what
// it does from there is in GipfaeliGrenade.
//
// Unlike the launcher and the rig, there is no ammunition to look for - the grenade is its own
// ammunition, and throwing one is spending it.
public final class GipfaeliHandGrenade {
    // How far in front of the eyes the grenade appears, so it is not thrown from inside the head.
    private static final double HAND_OFFSET = 0.4;

    private GipfaeliHandGrenade() {
    }

    public static boolean use(Player player, ItemStack stack, Level level) {
        if (!Config.on(Config.ENABLE_GIPFAELI_EXPLOSIVES) || !stack.is(CombatUpdate.GIPFAELI_GRENADE.get())) {
            return false;
        }

        if (player.getCooldowns().isOnCooldown(stack)) {
            return false;
        }

        if (level instanceof ServerLevel serverLevel) {
            Vec3 look = player.getLookAngle();
            Vec3 velocity = look.scale(Config.GRENADE_THROW_SPEED.getAsDouble());
            GipfaeliGrenade grenade = new GipfaeliGrenade(serverLevel,
                    player.getEyePosition().add(look.scale(HAND_OFFSET)), velocity, player,
                    Config.GRENADE_FUSE_TICKS.getAsInt());
            serverLevel.addFreshEntity(grenade);
        }

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.SNOWBALL_THROW, SoundSource.PLAYERS, 0.8F, 0.7F);

        // consume() already leaves a creative-mode stack alone.
        stack.consume(1, player);
        player.getCooldowns().addCooldown(stack, Config.GRENADE_COOLDOWN_TICKS.getAsInt());
        return true;
    }
}
