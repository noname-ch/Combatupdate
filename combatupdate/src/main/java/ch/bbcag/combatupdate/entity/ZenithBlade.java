package ch.bbcag.combatupdate.entity;

import java.util.HashSet;
import java.util.Set;

import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.Config;
import ch.bbcag.combatupdate.Exoblade;

// One of the phantom swords a Zenith swing sends out: a ghost of one of the swords the Zenith was
// forged from, looping out from the player's hand to where they were aiming and back again, and
// cutting through everything it passes on the way. Terraria's phantoms pass through walls, and so does
// this one: it is a ghost.
//
// The loop is an ellipse drawn in the player's frame, so it follows them as they move: its long axis
// runs from the hand to the aim point, and its short one sticks out to one side at whatever tilt the
// swing picked. Nothing about the flight is left to physics, so the client flies its own copy along
// the same curve from the two synced axes, rather than being dragged along a tick behind by position
// updates.
//
// It hits monsters and other players, and whatever it was aimed at directly - but not the cows the
// loop happens to swing through on the way, for the reason Exobeam gives.
public final class ZenithBlade extends Projectile {
    // The swords a phantom can be a ghost of: every one that goes into the Zenith, and the Zenith.
    public static final int SWORD_COUNT = 9;

    // The colour each one's trail is drawn in, in the order of #swordItem.
    private static final int[] COLORS = {
            0xB08850, 0xA0A0A0, 0xE8845A, 0xEEEEEE, 0xFFD83D, 0x4AEDD9, 0x9A7AAA, 0xD0D8E0, 0x7FF5C8,
    };

    private static final EntityDataAccessor<Vector3fc> DATA_REACH =
            SynchedEntityData.defineId(ZenithBlade.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Vector3fc> DATA_SIDE =
            SynchedEntityData.defineId(ZenithBlade.class, EntityDataSerializers.VECTOR3);
    private static final EntityDataAccessor<Integer> DATA_SWORD =
            SynchedEntityData.defineId(ZenithBlade.class, EntityDataSerializers.INT);

    // How long one loop out and back takes, in ticks, however long the loop is.
    private static final int LIFETIME = 18;

    // How far from the line the blade travels along it still cuts, in blocks: it is drawn a block and
    // a half long, and a blade that visibly passes through something should hurt it.
    private static final double HIT_RADIUS = 0.6;

    // How hard it pushes what it cuts away from the player. Light, since phantoms come in from every
    // side and a heavy push would only bat things out of the next one's path.
    private static final double KNOCKBACK = 0.2;

    // How far up the player the loops start and end: about where the sword is held.
    private static final double ANCHOR_HEIGHT = 0.6;

    // Everything it has already cut, so each phantom cuts each creature once, on its way out or its
    // way back. Not saved: a phantom lives under a second.
    private final Set<Integer> struck = new HashSet<>();

    // The entity id of whatever the swing was aimed at, if anything. Server only.
    private int aimedId = -1;

    public ZenithBlade(EntityType<? extends ZenithBlade> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public ZenithBlade(Level level, LivingEntity owner, Vec3 reach, Vec3 side, int sword, @Nullable Entity aimed) {
        this(CombatUpdate.ZENITH_BLADE.get(), level);
        this.setOwner(owner);
        this.entityData.set(DATA_REACH, reach.toVector3f());
        this.entityData.set(DATA_SIDE, side.toVector3f());
        this.entityData.set(DATA_SWORD, sword);
        this.aimedId = aimed == null ? -1 : aimed.getId();
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_REACH, new Vector3f());
        builder.define(DATA_SIDE, new Vector3f());
        builder.define(DATA_SWORD, 0);
    }

    public int sword() {
        return Math.floorMod(this.entityData.get(DATA_SWORD), SWORD_COUNT);
    }

    // The item each sword number is drawn as: wood up through netherite, the iron shortsword that
    // stands in for Terraria's copper one, and the Zenith itself.
    public static Item swordItem(int sword) {
        return switch (sword) {
            case 0 -> Items.WOODEN_SWORD;
            case 1 -> Items.STONE_SWORD;
            case 2 -> Items.COPPER_SWORD;
            case 3 -> Items.IRON_SWORD;
            case 4 -> Items.GOLDEN_SWORD;
            case 5 -> Items.DIAMOND_SWORD;
            case 6 -> Items.NETHERITE_SWORD;
            case 7 -> CombatUpdate.IRON_SHORTSWORD.get();
            default -> CombatUpdate.ZENITH.get();
        };
    }

    // Where a player's loops start and end.
    public static Vec3 anchor(Entity owner) {
        return new Vec3(owner.getX(), owner.getY() + owner.getBbHeight() * ANCHOR_HEIGHT, owner.getZ());
    }

    // Where along its loop the blade is, t running from 0 in the hand, through 0.5 at the far end, to
    // 1 back in the hand.
    private Vec3 pathAt(Entity owner, double t) {
        double angle = t * Math.PI * 2.0;
        Vec3 reach = new Vec3(this.entityData.get(DATA_REACH));
        Vec3 side = new Vec3(this.entityData.get(DATA_SIDE));
        return anchor(owner).add(reach.scale((1.0 - Math.cos(angle)) / 2.0)).add(side.scale(Math.sin(angle)));
    }

    @Override
    public void tick() {
        super.tick();
        Entity owner = this.getOwner();
        if (this.tickCount > LIFETIME || owner == null || !owner.isAlive() || owner.level() != this.level()) {
            // The client waits to be told: its copy is gone a moment later either way.
            if (!this.level().isClientSide()) {
                this.discard();
            }
            return;
        }

        Vec3 from = this.position();
        Vec3 to = this.pathAt(owner, this.tickCount / (double) LIFETIME);
        // Not moved by it, but it is what the renderer points the blade along.
        this.setDeltaMovement(to.subtract(from));
        this.setPos(to);

        if (this.level() instanceof ServerLevel serverLevel) {
            this.cut(serverLevel, owner, from, to);
        } else {
            this.trail(from, to);
        }
    }

    // Everything the blade passed through this tick. Like the Exo slashes it ignores the moment of
    // invulnerability the last hit left: several phantoms are meant to pass through one target in
    // quick succession, and each only ever cuts it once.
    private void cut(ServerLevel level, Entity owner, Vec3 from, Vec3 to) {
        float damage = (float) Config.ZENITH_BLADE_DAMAGE.getAsDouble();
        if (damage <= 0.0F) {
            return;
        }

        LivingEntity attacker = owner instanceof LivingEntity living ? living : null;
        for (LivingEntity caught : level.getEntitiesOfClass(LivingEntity.class, new AABB(from, to).inflate(HIT_RADIUS),
                candidate -> this.canCut(owner, candidate))) {
            AABB box = caught.getBoundingBox().inflate(HIT_RADIUS);
            if (!box.contains(from) && box.clip(from, to).isEmpty()) {
                continue;
            }

            this.struck.add(caught.getId());
            DamageSource source = this.damageSources().mobProjectile(this, attacker);
            caught.setInvulnerableTime(0);
            if (caught.hurtServer(level, source, damage)) {
                Vec3 away = caught.position().subtract(owner.position());
                caught.knockback(KNOCKBACK, -away.x, -away.z, source, damage);
                EnchantmentHelper.doPostAttackEffects(level, caught, source);
            }

            Vec3 center = caught.getBoundingBox().getCenter();
            level.sendParticles(new DustParticleOptions(COLORS[this.sword()], 1.5F), center.x, center.y, center.z,
                    6, 0.25, 0.35, 0.25, 0.0);
            level.sendParticles(ParticleTypes.ELECTRIC_SPARK, center.x, center.y, center.z, 3, 0.2, 0.2, 0.2, 0.2);
            level.playSound(null, center.x, center.y, center.z, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS,
                    0.4F, 1.6F + this.random.nextFloat() * 0.3F);
        }
    }

    private boolean canCut(Entity owner, LivingEntity candidate) {
        if (this.struck.contains(candidate.getId())) {
            return false;
        }

        if (candidate.getId() == this.aimedId) {
            return candidate.isAlive() && candidate != owner && !candidate.isSpectator() && !owner.isAlliedTo(candidate);
        }

        return Exoblade.isQuarry(owner, candidate);
    }

    // A streak of the sword's own colour along the stretch just flown: the afterimage Terraria's
    // phantoms leave. At the far end of a long loop it covers a couple of blocks a tick, so it is laid
    // down densely enough to read as a smear rather than a row of dots.
    private void trail(Vec3 from, Vec3 to) {
        Vec3 step = to.subtract(from);
        int points = Math.max(2, (int) Math.ceil(step.length() * 4.0));
        DustParticleOptions dust = new DustParticleOptions(COLORS[this.sword()], 1.2F);
        for (int i = 0; i < points; i++) {
            Vec3 at = from.add(step.scale(i / (double) points));
            this.level().addParticle(dust, at.x, at.y, at.z, 0.0, 0.0, 0.0);
        }

        if (this.tickCount % 2 == 0) {
            this.level().addParticle(ParticleTypes.END_ROD, to.x, to.y, to.z, 0.0, 0.0, 0.0);
        }
    }

    @Override
    public boolean shouldBeSaved() {
        // A phantom lives under a second, and there is no loop to come back to after a reload.
        return false;
    }
}
