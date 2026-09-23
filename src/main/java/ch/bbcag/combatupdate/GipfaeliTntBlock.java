package ch.bbcag.combatupdate;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.pattern.BlockInWorld;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gamerules.GameRules;

import ch.bbcag.combatupdate.entity.GipfaeliTnt;

// Gipfaeli TNT and Gipfaeli Ultra TNT as blocks. Vanilla's TNT block with what it lights swapped
// out: redstone, flint and steel, a fire charge, a burning arrow and a neighbouring blast all come
// through the same two hooks vanilla's do, and each of them sets down a GipfaeliTnt in place of a
// vanilla primed one.
//
// One class for both kinds, told apart by the block itself. The lit entity draws whichever block it
// came from and reads its kind off that (see GipfaeliTnt#isUltra), so this block never has to tell
// it anything but which state to carry.
public final class GipfaeliTntBlock extends TntBlock {
    public enum Kind {
        STANDARD, ULTRA;

        int fuseTicks() {
            return this == ULTRA
                    ? Config.ULTRA_TNT_FUSE_TICKS.getAsInt()
                    : Config.GIPFAELI_TNT_FUSE_TICKS.getAsInt();
        }
    }

    private final Kind kind;

    public GipfaeliTntBlock(Kind kind, Properties properties) {
        super(properties);
        this.kind = kind;
    }

    public Kind kind() {
        return this.kind;
    }

    // Vanilla's prime() with our entity in it. The checks are its checks: the game rule that turns
    // TNT off, and an adventure-mode player who is not allowed to break this block not being
    // allowed to light it either.
    @Override
    public boolean onCaughtFire(BlockState state, Level level, BlockPos pos, @Nullable Direction face,
            @Nullable LivingEntity igniter, ItemStack ignitionItem) {
        if (!Config.on(Config.ENABLE_GIPFAELI_EXPLOSIVES)) {
            return false;
        }

        if (!(level instanceof ServerLevel serverLevel) || !serverLevel.getGameRules().get(GameRules.TNT_EXPLODES)) {
            return false;
        }

        if (igniter instanceof Player player
                && player.gameMode() == GameType.ADVENTURE
                && !ignitionItem.canBreakBlockInAdventureMode(new BlockInWorld(level, pos, false))) {
            return false;
        }

        GipfaeliTnt tnt = new GipfaeliTnt(level, pos, this.defaultBlockState(), igniter, this.kind.fuseTicks());
        level.addFreshEntity(tnt);
        // The Ultra's fuse hisses lower, so anyone in earshot knows which one they are running from.
        level.playSound(null, tnt.getX(), tnt.getY(), tnt.getZ(),
                SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.0F, this.kind == Kind.ULTRA ? 0.6F : 1.0F);
        level.gameEvent(igniter, GameEvent.PRIME_FUSE, pos);
        return true;
    }

    // Caught in someone else's blast. Vanilla gives it a short random fuse so a chain of TNT goes
    // off as a ripple rather than all at once, and ours keeps that.
    @Override
    public void wasExploded(ServerLevel level, BlockPos pos, Explosion explosion) {
        if (!Config.on(Config.ENABLE_GIPFAELI_EXPLOSIVES) || !level.getGameRules().get(GameRules.TNT_EXPLODES)) {
            return;
        }

        GipfaeliTnt tnt = new GipfaeliTnt(level, pos, this.defaultBlockState(),
                explosion.getIndirectSourceEntity(), this.kind.fuseTicks());
        tnt.setFuse(PrimedTnt.getRandomShortFuse(tnt.getFuse(), level.getRandom()));
        level.addFreshEntity(tnt);
    }
}
