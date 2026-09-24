package ch.bbcag.gipfeliarmy.entity;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.gipfeliarmy.GipfeliArmyMod;
import ch.bbcag.gipfeliarmy.Config;

// Lit Gipfaeli TNT, plain or Ultra (see GipfaeliTntBlock for what lights it). Vanilla's primed TNT
// with the bang swapped out: it falls, flashes and counts down exactly the way TNT does, and the
// block it draws is whichever of ours was lit - which is also how it knows, on going off, whether it
// is the plain kind or the Ultra.
//
// The plain kind is TNT with more behind it. The Ultra is that and then a shower of live hand
// grenades (see GipfaeliGrenade) thrown out of the crater, each on a short fuse of its own, so the
// first blast is only where it starts.
public final class GipfaeliTnt extends PrimedTnt {
    // How fast a scattered grenade leaves the blast, and how much of that is upwards. Spread over
    // a range so they land in a ring rather than a heap.
    private static final double SCATTER_SPEED_MIN = 0.5;
    private static final double SCATTER_SPEED_MAX = 1.1;
    private static final double SCATTER_LIFT_MIN = 0.4;
    private static final double SCATTER_LIFT_MAX = 0.9;

    // Short and staggered, so the ring goes off as a rattle rather than as one second bang.
    private static final int SCATTER_FUSE_MIN = 15;
    private static final int SCATTER_FUSE_MAX = 45;

    // Kept here rather than in PrimedTnt's own field, which is private and only ever set by the
    // constructor that also fixes the type to vanilla TNT.
    private @Nullable EntityReference<LivingEntity> thrower;

    public GipfaeliTnt(EntityType<? extends GipfaeliTnt> type, Level level) {
        super(type, level);
    }

    public GipfaeliTnt(Level level, BlockPos pos, BlockState block, @Nullable LivingEntity owner, int fuse) {
        this(GipfeliArmyMod.GIPFAELI_TNT.get(), level);
        this.setPos(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
        // The little hop vanilla gives lit TNT, so it reads as having been set off rather than
        // having merely stopped being a block.
        double rot = level.getRandom().nextDouble() * (Math.PI * 2);
        this.setDeltaMovement(-Math.sin(rot) * 0.02, 0.2F, -Math.cos(rot) * 0.02);
        this.setFuse(fuse);
        this.setBlockState(block);
        this.xo = this.getX();
        this.yo = this.getY();
        this.zo = this.getZ();
        this.thrower = EntityReference.of(owner);
    }

    public boolean isUltra() {
        return this.getBlockState().is(GipfeliArmyMod.GIPFAELI_ULTRA_TNT_BLOCK.get());
    }

    @Override
    protected void explode() {
        if (!(this.level() instanceof ServerLevel level) || !level.getGameRules().get(GameRules.TNT_EXPLODES)) {
            return;
        }

        boolean ultra = isUltra();
        double power = ultra
                ? Config.ULTRA_TNT_EXPLOSION_POWER.getAsDouble()
                : Config.GIPFAELI_TNT_EXPLOSION_POWER.getAsDouble();
        Vec3 at = new Vec3(this.getX(), this.getY(0.0625), this.getZ());

        if (power > 0.0) {
            level.explode(this, this.damageSources().explosion(this, this.getOwner()), null, at,
                    (float) power, false,
                    Config.GIPFAELI_TNT_BREAKS_BLOCKS.get()
                            ? Level.ExplosionInteraction.TNT
                            : Level.ExplosionInteraction.NONE);
        }

        if (ultra) {
            level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, at.x, at.y, at.z, 3, 1.5, 0.5, 1.5, 0.0);
            scatterGrenades(level, at);
        }
    }

    private void scatterGrenades(ServerLevel level, Vec3 from) {
        int count = Config.ULTRA_TNT_GRENADES.getAsInt();
        if (count <= 0) {
            return;
        }

        RandomSource random = level.getRandom();
        LivingEntity owner = this.getOwner();
        for (int i = 0; i < count; i++) {
            // Spaced evenly round the compass with a little jitter, rather than drawn at random:
            // twelve random headings leave gaps, and a ring is what this is meant to be.
            double heading = (Math.PI * 2) * (i + random.nextDouble() * 0.5) / count;
            double speed = SCATTER_SPEED_MIN + random.nextDouble() * (SCATTER_SPEED_MAX - SCATTER_SPEED_MIN);
            double lift = SCATTER_LIFT_MIN + random.nextDouble() * (SCATTER_LIFT_MAX - SCATTER_LIFT_MIN);
            Vec3 velocity = new Vec3(Math.cos(heading) * speed, lift, Math.sin(heading) * speed);
            int fuse = SCATTER_FUSE_MIN + random.nextInt(SCATTER_FUSE_MAX - SCATTER_FUSE_MIN + 1);

            // Started a block up so none of them spawn inside the crater floor and stick there.
            level.addFreshEntity(new GipfaeliGrenade(level, from.add(0.0, 1.0, 0.0), velocity, owner, fuse));
        }
    }

    @Override
    public @Nullable LivingEntity getOwner() {
        return EntityReference.getLivingEntity(this.thrower, this.level());
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        EntityReference.store(this.thrower, output, "Thrower");
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.thrower = EntityReference.read(input, "Thrower");
    }
}
