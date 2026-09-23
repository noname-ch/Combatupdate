package ch.bbcag.combatupdate;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

// Right-clicking TNT while gliding releases it as a bomb rather than placing it. It leaves your hands
// with your own velocity already on it, so it carries on forward as it falls instead of dropping
// straight down - the arc comes out of the speed you were doing when you let go, which is the whole
// point of running in low and fast.
//
// Nothing here has to model the falling: vanilla already scales a primed TNT's velocity by 0.98 every
// tick and pulls it down at 0.04, so the inherited speed bleeds off on its own and the throw flattens
// out the way a real bomb's does. All this does is hand it the right velocity to start with.
//
// Only while gliding: on the ground, right-clicking TNT still places it, untouched.
public final class ElytraBomb {
    // Released from below the pilot rather than out of their chest, so it falls clear instead of
    // looking like it passed through them.
    private static final double DROP_BELOW = 0.6;

    private ElytraBomb() {
    }

    public static boolean release(Player player, ItemStack stack, Level level) {
        if (!Config.on(Config.ENABLE_ELYTRA_BOMB)
                || !stack.is(Items.TNT)
                || !player.isFallFlying()
                || player.getCooldowns().isOnCooldown(stack)) {
            return false;
        }

        if (level instanceof ServerLevel serverLevel) {
            Vec3 from = player.position();
            PrimedTnt bomb = new PrimedTnt(serverLevel, from.x, from.y - DROP_BELOW, from.z, player);

            // The constructor gives it the little upward hop and random spin a block of TNT gets when
            // it is lit where it stands. A bomb coming off an aircraft wants none of that - it wants
            // whatever the aircraft was doing.
            bomb.setDeltaMovement(player.getDeltaMovement()
                    .scale(Config.BOMB_MOMENTUM_TRANSFER.getAsDouble()));
            bomb.setFuse(Config.BOMB_FUSE_TICKS.getAsInt());

            // Its blast is left to CombatUpdate#onEntityJoinLevel, which retunes every primed TNT to
            // the configured radius; a bomb has no business being a different size from the block.
            serverLevel.addFreshEntity(bomb);
        }

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.TNT_PRIMED, SoundSource.PLAYERS, 1.0F, 1.0F);

        stack.consume(1, player);
        player.getCooldowns().addCooldown(stack, Config.BOMB_COOLDOWN_TICKS.getAsInt());
        return true;
    }
}
