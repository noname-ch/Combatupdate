package ch.bbcag.combatupdate.entity;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityReference;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.Config;

// A Gipfaeli hand grenade in the air (see GipfaeliHandGrenade for the throw). It flies in an arc,
// bounces off whatever it meets, and goes off when its fuse runs out - not on impact, which is what
// separates a grenade from a fired Gipfaeli: it can be lobbed round a corner or over a wall and
// still be lying there when it goes.
//
// Ultra TNT throws a dozen of these out on top of its own blast (see GipfaeliTnt), which is why the
// fuse is handed in rather than always read from the config.
//
// Server-side throughout, the same way GipfaeliBomb is: the client is put where the server says and
// draws the pastry.
public final class GipfaeliGrenade extends Entity implements ItemSupplier, TraceableEntity {
    // How much of its speed a grenade keeps off a wall or the floor. Enough to skitter, not enough
    // to come back at whoever threw it.
    private static final double BOUNCE = 0.45;

    // Below this a fall is a settle, not a bounce, so a grenade that has come to rest stays there
    // instead of hopping forever on a diminishing series of nothings.
    private static final double MIN_BOUNCE_SPEED = 0.12;

    private static final double GROUND_FRICTION = 0.7;

    // What the renderer draws. Held rather than made on demand: the renderer asks once a frame.
    private final ItemStack item = new ItemStack(CombatUpdate.GIPFAELI_GRENADE.get());

    private int fuse = 40;
    private @Nullable EntityReference<LivingEntity> owner;

    public GipfaeliGrenade(EntityType<? extends GipfaeliGrenade> type, Level level) {
        super(type, level);
    }

    public GipfaeliGrenade(Level level, Vec3 position, Vec3 velocity, @Nullable LivingEntity owner, int fuse) {
        this(CombatUpdate.GIPFAELI_GRENADE_ENTITY.get(), level);
        this.setPos(position);
        this.setDeltaMovement(velocity);
        this.owner = EntityReference.of(owner);
        this.fuse = fuse;
    }

    @Override
    public void tick() {
        if (!(this.level() instanceof ServerLevel level)) {
            return;
        }

        fly();

        if (--this.fuse <= 0) {
            detonate(level);
            return;
        }

        // A thin trail, so the thing can be followed after it leaves the hand and found where it
        // came to rest; a grenade you cannot see is one you are standing next to.
        if (this.fuse % 2 == 0) {
            level.sendParticles(ParticleTypes.SMOKE, this.getX(), this.getY() + 0.1, this.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    // TNT's fall with a bounce put back into it. Entity#move zeroes whichever axis of the movement
    // ran into something, so the velocity from before the move is what says how hard the wall was
    // hit and which way to send it back.
    private void fly() {
        Vec3 before = this.getDeltaMovement();
        this.applyGravity();
        this.move(MoverType.SELF, this.getDeltaMovement());
        Vec3 after = this.getDeltaMovement();

        double x = after.x;
        double y = after.y;
        double z = after.z;
        boolean bounced = false;

        if (this.horizontalCollision) {
            if (after.x == 0.0 && Math.abs(before.x) > MIN_BOUNCE_SPEED) {
                x = -before.x * BOUNCE;
                bounced = true;
            }
            if (after.z == 0.0 && Math.abs(before.z) > MIN_BOUNCE_SPEED) {
                z = -before.z * BOUNCE;
                bounced = true;
            }
        }

        if (this.verticalCollision && after.y == 0.0 && before.y < -MIN_BOUNCE_SPEED) {
            y = -before.y * BOUNCE;
            bounced = true;
        } else if (this.onGround()) {
            x *= GROUND_FRICTION;
            z *= GROUND_FRICTION;
        }

        this.setDeltaMovement(new Vec3(x, y, z).scale(this.getAirDrag()));

        if (bounced) {
            this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                    SoundEvents.WOOD_HIT, SoundSource.NEUTRAL, 0.4F, 1.6F);
        }
    }

    private void detonate(ServerLevel level) {
        double power = Config.GRENADE_EXPLOSION_POWER.getAsDouble();
        if (power > 0.0) {
            // Credited to whoever threw it, so a kill with one reads as theirs. The interaction
            // decides the terrain: MOB weighs mobGriefing, NONE leaves it standing regardless.
            level.explode(this, this.damageSources().explosion(this, this.getOwner()), null,
                    this.position(), (float) power, false,
                    Config.GRENADE_BREAKS_BLOCKS.get()
                            ? Level.ExplosionInteraction.MOB
                            : Level.ExplosionInteraction.NONE);
        }

        this.discard();
    }

    @Override
    public @Nullable LivingEntity getOwner() {
        return EntityReference.getLivingEntity(this.owner, this.level());
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
    protected float getAirDrag() {
        return 0.98F;
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
        // Like TNT: once it is thrown, it is thrown. Hitting the grenade is not a way of stopping it.
        return false;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        output.putInt("Fuse", this.fuse);
        EntityReference.store(this.owner, output, "Owner");
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        this.fuse = input.getIntOr("Fuse", 40);
        this.owner = EntityReference.read(input, "Owner");
    }
}
