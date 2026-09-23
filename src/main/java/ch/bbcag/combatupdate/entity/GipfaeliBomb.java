package ch.bbcag.combatupdate.entity;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.UUIDUtil;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.EventHooks;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.Config;

// A Gipfaeli bomb, set down by the launch rig (see GipfaeliLaunchRig). It waits where it was put
// until a spot is called on it, counts that spot down where everyone within earshot can hear it,
// then lobs itself over in an arc and goes off on arrival.
//
// The flight is walked along a parabola by hand rather than thrown with a velocity and handed to
// gravity. A bomb called in on a spot has to land on that spot, and vanilla's air drag and the
// discrete steps of a physics tick would each take their cut out of a ballistic solution worked out
// up front - an artillery piece that lands near where it was aimed is a different weapon from one
// that lands on it.
//
// Server-side throughout. Where the bomb is, is the server's to say, and the client is told that the
// same way it is told where any other entity is; all it does of its own is draw the pastry.
public final class GipfaeliBomb extends Entity implements ItemSupplier {
    // Set down with no spot called on it yet; counting a called spot down; on its way there. Saved as
    // an ordinal, so the order of these is part of the save format.
    private enum Phase { WAITING, COUNTDOWN, FLIGHT }

    private static final Phase[] PHASES = Phase.values();

    // How far the bomb is lifted as it leaves the pad, so the first step of the arc is measured from
    // the air above whatever it was standing on rather than from inside it.
    private static final double LIFT_OFF = 0.5;

    // And how long it flies before anything in the way counts as an impact, for the same reason: the
    // ground under the pad is not what the bomb was fired into.
    private static final int FLIGHT_GRACE_TICKS = 2;

    // A lob has to read as a lob even across a courtyard, so the arc never flattens below this
    // however short the shot.
    private static final double MIN_ARC_HEIGHT = 6.0;

    private static final int MIN_FLIGHT_TICKS = 10;
    private static final int MAX_FLIGHT_TICKS = 600;

    // Counted out once a second, and four times a second over the last one, so the moment it goes is
    // unmistakable to anyone standing near it.
    private static final int BEEP_INTERVAL_TICKS = 20;
    private static final int FINAL_SECOND_TICKS = 20;
    private static final int FINAL_BEEP_INTERVAL_TICKS = 5;

    // All three of these go out as packets, so none is done every tick. A bomb still waiting for a
    // spot only ticks over; one counting down is meant to be noticed from across a field.
    private static final int READOUT_INTERVAL_TICKS = 5;
    private static final int SMOKE_INTERVAL_TICKS = 4;
    private static final int IDLE_SMOKE_INTERVAL_TICKS = 20;

    // What the renderer draws. Held rather than made on demand: the renderer asks once a frame.
    private final ItemStack item = new ItemStack(CombatUpdate.GIPFAELI.get());

    private Phase phase = Phase.WAITING;

    // Ticks left of the arming window or the countdown, and ticks flown once it is in the air. One
    // counter, because the bomb is only ever in one of those three.
    private int timer;

    private int flightTicks = MIN_FLIGHT_TICKS;
    private double arcHeight = MIN_ARC_HEIGHT;
    private @Nullable Vec3 launchFrom;
    private @Nullable Vec3 target;

    // Whoever set it down, held as a UUID so it survives being written out and read back rather than
    // pinning the player in memory. Only used to address the readout and to ask whether they are
    // allowed to break blocks.
    private @Nullable UUID ownerId;

    // Whether a Gipfaeli was actually spent to put this one here, and so whether there is anything to
    // hand back if it is never sent anywhere. Without it, a bomb set down for free and left to expire
    // would be a pastry out of thin air - which in survival with the ammo turned off is a food source.
    private boolean spentAmmo;

    public GipfaeliBomb(EntityType<? extends GipfaeliBomb> type, Level level) {
        super(type, level);
    }

    public GipfaeliBomb(Level level, Vec3 position, @Nullable Player owner, boolean spentAmmo) {
        this(CombatUpdate.GIPFAELI_BOMB.get(), level);
        this.setPos(position);
        this.timer = Config.GIPFAELI_BOMB_ARM_WINDOW_TICKS.getAsInt();
        this.ownerId = owner == null ? null : owner.getUUID();
        this.spentAmmo = spentAmmo;
    }

    // Calls a spot on a bomb that is still waiting for one, and returns the countdown that starts, in
    // ticks, for the rig to tell the player about.
    public int arm(Vec3 spot) {
        this.target = spot;
        this.phase = Phase.COUNTDOWN;
        this.timer = Config.GIPFAELI_BOMB_COUNTDOWN_TICKS.getAsInt();

        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.TNT_PRIMED, SoundSource.BLOCKS, 1.2F, 0.7F);
        return this.timer;
    }

    // Whether this bomb is still waiting to be told where to go. A bomb already counting down cannot
    // be re-aimed: the spot was called, and the five seconds are the price of calling it.
    public boolean isWaiting() {
        return this.phase == Phase.WAITING;
    }

    @Override
    public void tick() {
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }

        switch (this.phase) {
            case WAITING -> tickWaiting(level);
            case COUNTDOWN -> tickCountdown(level);
            case FLIGHT -> tickFlight(level);
        }
    }

    private void tickWaiting(ServerLevel level) {
        settle();

        if (--this.timer > 0) {
            if (this.tickCount % IDLE_SMOKE_INTERVAL_TICKS == 0) {
                level.sendParticles(ParticleTypes.SMOKE,
                        this.getX(), this.getY() + 0.3, this.getZ(), 1, 0.05, 0.0, 0.05, 0.0);
            }
            return;
        }

        // Nobody ever called a spot on it. Hand the Gipfaeli back rather than leaving it lying there:
        // ammunition that was spent and did nothing is ammunition someone is entitled to eat.
        if (this.spentAmmo) {
            level.addFreshEntity(new ItemEntity(level, this.getX(), this.getY() + 0.25, this.getZ(),
                    new ItemStack(CombatUpdate.GIPFAELI.get())));
        }

        readout(level, Component.translatable("combatupdate.gipfaeli.bomb.expired"));
        this.discard();
    }

    private void tickCountdown(ServerLevel level) {
        settle();

        if (--this.timer <= 0) {
            launch(level);
            return;
        }

        int interval = this.timer <= FINAL_SECOND_TICKS ? FINAL_BEEP_INTERVAL_TICKS : BEEP_INTERVAL_TICKS;
        if (this.timer % interval == 0) {
            // Rising as the count runs down, from flat at five seconds out to sharp at nothing left.
            float pitch = 2.0F - Math.min(this.timer, 100) / 100.0F;
            level.playSound(null, this.getX(), this.getY(), this.getZ(),
                    SoundEvents.COMPARATOR_CLICK, SoundSource.BLOCKS, 1.0F, pitch);
        }

        if (this.timer % SMOKE_INTERVAL_TICKS == 0) {
            level.sendParticles(ParticleTypes.SMOKE,
                    this.getX(), this.getY() + 0.3, this.getZ(), 3, 0.1, 0.05, 0.1, 0.01);
        }

        if (this.timer % READOUT_INTERVAL_TICKS == 0) {
            readout(level, Component.translatable("combatupdate.gipfaeli.bomb.countdown",
                    seconds(this.timer)));
        }
    }

    private void launch(ServerLevel level) {
        if (this.target == null) {
            // A countdown with no spot behind it can only have come from a save that lost one. Going
            // off where it stands is the honest answer; flying nowhere is not.
            detonate(level);
            return;
        }

        this.launchFrom = this.position().add(0.0, LIFT_OFF, 0.0);
        double distance = this.launchFrom.distanceTo(this.target);
        this.flightTicks = Math.clamp(
                Math.round(distance / Config.GIPFAELI_BOMB_FLIGHT_SPEED.getAsDouble()),
                MIN_FLIGHT_TICKS, MAX_FLIGHT_TICKS);
        this.arcHeight = Math.max(MIN_ARC_HEIGHT, distance * Config.GIPFAELI_BOMB_ARC.getAsDouble());

        this.phase = Phase.FLIGHT;
        this.timer = 0;
        this.setPos(this.launchFrom);
        // The arc is walked by hand from here, so whatever fall speed it had sitting on the pad is no
        // longer part of anything.
        this.setDeltaMovement(Vec3.ZERO);

        level.playSound(null, this.getX(), this.getY(), this.getZ(),
                SoundEvents.FIREWORK_ROCKET_LAUNCH, SoundSource.BLOCKS, 2.0F, 0.5F);
        level.sendParticles(ParticleTypes.LARGE_SMOKE,
                this.getX(), this.getY(), this.getZ(), 30, 0.3, 0.1, 0.3, 0.05);
        level.sendParticles(ParticleTypes.FLAME,
                this.getX(), this.getY(), this.getZ(), 15, 0.2, 0.1, 0.2, 0.05);
        readout(level, Component.translatable("combatupdate.gipfaeli.bomb.launched"));
    }

    private void tickFlight(ServerLevel level) {
        if (this.launchFrom == null || this.target == null) {
            detonate(level);
            return;
        }

        this.timer++;
        double progress = Math.min((double) this.timer / Math.max(1, this.flightTicks), 1.0);
        Vec3 from = this.position();
        Vec3 to = pointAt(progress);

        // Whatever is in the way is where the bomb goes off. A shot called over a ridge is a shot into
        // the ridge, which is the answer a mortar gives as well.
        if (this.timer > FLIGHT_GRACE_TICKS) {
            BlockHitResult hit = level.clip(new ClipContext(
                    from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, this));
            if (hit.getType() != HitResult.Type.MISS) {
                this.setPos(hit.getLocation());
                detonate(level);
                return;
            }
        }

        this.setPos(to);
        level.sendParticles(ParticleTypes.SMOKE, this.getX(), this.getY(), this.getZ(), 2, 0.05, 0.05, 0.05, 0.0);

        if (progress >= 1.0) {
            detonate(level);
        }
    }

    // Where along the arc the bomb is, for a fraction of the way there. The straight line from the pad
    // to the spot, with a hump added on top of it that is worth nothing at either end and arcHeight
    // over the middle.
    private Vec3 pointAt(double progress) {
        return this.launchFrom.lerp(this.target, progress)
                .add(0.0, this.arcHeight * 4.0 * progress * (1.0 - progress), 0.0);
    }

    // TNT's own falling, so a bomb set on the lip of a hole drops into it instead of hanging over it,
    // and one placed on a block that is mined out goes with the ground.
    private void settle() {
        this.applyGravity();
        this.move(MoverType.SELF, this.getDeltaMovement());
        this.setDeltaMovement(this.getDeltaMovement().scale(this.getAirDrag()));
        if (this.onGround()) {
            this.setDeltaMovement(this.getDeltaMovement().multiply(0.7, -0.5, 0.7));
        }
    }

    private void detonate(ServerLevel level) {
        double power = Config.GIPFAELI_BOMB_EXPLOSION_POWER.getAsDouble();
        if (power > 0.0) {
            boolean grief = Config.GIPFAELI_BOMB_BREAKS_BLOCKS.get()
                    && EventHooks.canEntityGrief(level, owner(level));
            level.explode(this, this.getX(), this.getY(), this.getZ(),
                    (float) power, grief, Level.ExplosionInteraction.MOB);
        }

        this.discard();
    }

    // Rounded up, so a countdown with anything left on it never reads as zero seconds to go.
    private static int seconds(int ticks) {
        return (ticks + 19) / 20;
    }

    private @Nullable Player owner(ServerLevel level) {
        return this.ownerId != null && level.getEntity(this.ownerId) instanceof Player player ? player : null;
    }

    // The owner's running commentary, put on the action bar rather than into chat: it replaces itself
    // every few ticks, and nobody wants a scrollback of a countdown.
    private void readout(ServerLevel level, Component message) {
        if (owner(level) instanceof ServerPlayer player) {
            player.sendSystemMessage(message, true);
        }
    }

    @Override
    public ItemStack getItem() {
        return this.item;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder entityData) {
        // Nothing the client has to be told. It draws the pastry and is put where the server says.
    }

    @Override
    protected double getDefaultGravity() {
        return 0.04;
    }

    @Override
    protected Entity.MovementEmission getMovementEmission() {
        return Entity.MovementEmission.NONE;
    }

    @Override
    public boolean isPickable() {
        return !this.isRemoved();
    }

    @Override
    public boolean hurtServer(ServerLevel level, DamageSource source, float damage) {
        // Like TNT: once it is down, it is down. Shooting the bomb is not a way of stopping it.
        return false;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putInt("Phase", this.phase.ordinal());
        output.putInt("Timer", this.timer);
        output.putInt("FlightTicks", this.flightTicks);
        output.putDouble("ArcHeight", this.arcHeight);
        output.storeNullable("LaunchFrom", Vec3.CODEC, this.launchFrom);
        output.storeNullable("Target", Vec3.CODEC, this.target);
        output.storeNullable("Owner", UUIDUtil.CODEC, this.ownerId);
        output.putBoolean("SpentAmmo", this.spentAmmo);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        this.phase = PHASES[Math.clamp(input.getIntOr("Phase", 0), 0, PHASES.length - 1)];
        this.timer = input.getIntOr("Timer", 0);
        this.flightTicks = input.getIntOr("FlightTicks", MIN_FLIGHT_TICKS);
        this.arcHeight = input.getDoubleOr("ArcHeight", MIN_ARC_HEIGHT);
        this.launchFrom = input.read("LaunchFrom", Vec3.CODEC).orElse(null);
        this.target = input.read("Target", Vec3.CODEC).orElse(null);
        this.ownerId = input.read("Owner", UUIDUtil.CODEC).orElse(null);
        this.spentAmmo = input.getBooleanOr("SpentAmmo", false);
    }
}
