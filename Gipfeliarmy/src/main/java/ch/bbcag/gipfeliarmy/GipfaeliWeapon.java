package ch.bbcag.gipfeliarmy;

import java.util.Locale;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.gipfeliarmy.GipfaeliRecoil.Recoil;

import ch.bbcag.gipfeliarmy.entity.GipfaeliBullet;
import ch.bbcag.gipfeliarmy.entity.GipfaeliRocket;

// The kit a Gipfaeli soldier is issued with, and so what kind of soldier it is: the gun in its hand
// is the one record of its role, and everything about that role - how it shoots, how much it can
// take, how fast it walks - lives here against the weapon.
//
// A soldier fires through its gunfight goal and a player fires by right-clicking, and both come
// through fire() below, so a weapon behaves the same in either pair of hands and there is one set
// of numbers to balance. The aim, though, is the soldier's own (see lead()): a soldier shoots where
// the target is going to be, which a player has to work out for themselves.
public enum GipfaeliWeapon {
    // The rifleman, and the squad's default. A short, tight burst and a cooldown short enough that
    // three of them between them keep a target pinned.
    RIFLEMAN(() -> GipfeliArmyMod.LETONY_MATE_AK47.get(),
            Volley.crumbs(3, 4.5F, 1.4, 5.0), 15, 32.0, 12.0, 0.0,
            new Body(20.0, 4.0, 1.0, 0.0),
            new Recoil(0.07, 1.6, 0.7),
            SoundEvents.CROSSBOW_SHOOT, 1.7F),

    // The assault trooper: a shotgun, more health, and a habit of walking straight in. Past a dozen
    // blocks a fistful of crumbs is a fistful of crumbs going nowhere, so it closes until it counts.
    ASSAULT(() -> GipfeliArmyMod.GIPFAELI_SHOTGUN.get(),
            Volley.crumbs(10, 3.0F, 8.0, 3.2), 30, 14.0, 4.0, 0.0,
            new Body(26.0, 6.0, 1.1, 0.2),
            new Recoil(0.24, 4.5, 1.2),
            SoundEvents.CROSSBOW_SHOOT, 0.7F),

    // The marksman: one crumb, hard, from further off than anything else here, and the patience to
    // reload. Hangs back at the edge of its reach, and is not built to be caught out there.
    MARKSMAN(() -> GipfeliArmyMod.GIPFAELI_MARKSMAN.get(),
            Volley.crumbs(1, 18.0F, 0.0, 8.0), 45, 72.0, 32.0, 0.0,
            new Body(16.0, 2.0, 1.0, 0.0),
            new Recoil(0.16, 5.5, 0.3),
            SoundEvents.FIREWORK_ROCKET_BLAST, 1.6F),

    // The bomber, with the launcher the mod already had. It fires the same Gipfaeli a player's
    // launcher does, locked on to whatever the squad was pointed at, so the shot bends after a
    // target that breaks for cover - and it will not fire at anything close enough to take the
    // squad with it.
    BOMBER(() -> GipfeliArmyMod.GIPFAELI_LAUNCHER.get(),
            Volley.rocket(), 80, 48.0, 16.0, 8.0,
            new Body(20.0, 4.0, 0.9, 0.0),
            GipfaeliRecoil.LAUNCHER,
            SoundEvents.FIREWORK_ROCKET_LAUNCH, 0.6F),

    // The Panzer soldier: a heavy machine gun off a tank mount, and the frame to carry it. Twice
    // the rounds of anything else, twice the health, and slow - it does not chase, it holds ground
    // and hoses down whatever is on it.
    PANZER(() -> GipfeliArmyMod.GIPFAELI_HEAVY_MG.get(),
            Volley.crumbs(2, 6.0F, 2.2, 6.0), 5, 40.0, 18.0, 0.0,
            new Body(44.0, 12.0, 0.75, 0.8),
            new Recoil(0.11, 1.3, 1.0),
            SoundEvents.GENERIC_EXPLODE.value(), 1.9F),

    // The marcher, who carries the banner rather than a gun. It does not shoot; what it does is
    // stand in the middle of the squad and make the rest of it faster and harder (see
    // GipfaeliSoldier#rally), and stand its ground when things get close.
    MARCHER(() -> GipfeliArmyMod.GIPFAELI_WAR_BANNER.get(),
            Volley.none(), 0, 0.0, 6.0, 0.0,
            new Body(30.0, 8.0, 1.15, 0.4),
            Recoil.NONE,
            SoundEvents.CROSSBOW_SHOOT, 1.0F);

    // One pull of the trigger: how many rounds, what each costs its target, how far they scatter,
    // and how fast they fly. A rocket volley is the launcher's pastry instead; a volley of nothing
    // is a weapon that is not a gun.
    private record Volley(int rounds, float damage, double spreadDegrees, double speed, boolean homing) {
        static Volley crumbs(int rounds, float damage, double spreadDegrees, double speed) {
            return new Volley(rounds, damage, spreadDegrees, speed, false);
        }

        static Volley rocket() {
            return new Volley(1, 0.0F, 0.0, 0.0, true);
        }

        static Volley none() {
            return new Volley(0, 0.0F, 0.0, 0.0, false);
        }

        boolean fires() {
            return this.rounds > 0;
        }
    }

    // What the soldier carrying this is built like. Speed is a multiple of the base walking pace;
    // knockback resistance runs 0 to 1 the way the attribute does.
    public record Body(double health, double armor, double speed, double knockbackResistance) {
    }

    private static final GipfaeliWeapon[] ALL = values();

    private final Supplier<Item> item;
    private final Volley volley;
    private final int cooldownTicks;
    // How far the weapon is worth firing, how close its carrier tries to stand, and how close is
    // too close to fire at all. The standoff is what tells a marksman to hang back and an assault
    // trooper to walk in; the minimum only matters to the launcher, whose shot goes off where it
    // lands.
    private final double range;
    private final double standoff;
    private final double minimum;
    private final Body body;
    // What firing it costs the person holding it; see GipfaeliRecoil. Only ever charged to a player -
    // a soldier shoved about by its own weapon would spend the fight walking back to where it stood.
    private final Recoil recoil;
    private final SoundEvent sound;
    private final float pitch;

    GipfaeliWeapon(Supplier<Item> item, Volley volley, int cooldownTicks, double range, double standoff,
            double minimum, Body body, Recoil recoil, SoundEvent sound, float pitch) {
        this.item = item;
        this.volley = volley;
        this.cooldownTicks = cooldownTicks;
        this.range = range;
        this.standoff = standoff;
        this.minimum = minimum;
        this.body = body;
        this.recoil = recoil;
        this.sound = sound;
        this.pitch = pitch;
    }

    // Which kit a held item is, or null if it is not one of ours. This is how a soldier knows what
    // it is: the thing in its hand is the only record of it.
    public static @Nullable GipfaeliWeapon of(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }

        for (GipfaeliWeapon weapon : ALL) {
            if (stack.is(weapon.item.get())) {
                return weapon;
            }
        }

        return null;
    }

    public static @Nullable GipfaeliWeapon byName(String name) {
        for (GipfaeliWeapon weapon : ALL) {
            if (weapon.token().equalsIgnoreCase(name)) {
                return weapon;
            }
        }

        return null;
    }

    // The word the command and the menu's buttons use for the role.
    public String token() {
        return this.name().toLowerCase(Locale.ROOT);
    }

    public String key() {
        return "gipfeliarmy.army.role." + this.token();
    }

    public Component roleName() {
        return Component.translatable(this.key());
    }

    public Item item() {
        return this.item.get();
    }

    public ItemStack stack() {
        return new ItemStack(this.item.get());
    }

    public boolean fires() {
        return this.volley.fires();
    }

    public int cooldownTicks() {
        return this.cooldownTicks;
    }

    public double range() {
        return this.range;
    }

    public double standoff() {
        return this.standoff;
    }

    public double minimum() {
        return this.minimum;
    }

    public Body body() {
        return this.body;
    }

    public Recoil recoil() {
        return this.recoil;
    }

    // How fast what this throws travels, which is what an aim has to be led by. A rocket's pace is
    // the launcher's own setting; it homes anyway, so the lead only sets it off on the right foot.
    public double projectileSpeed() {
        return this.volley.homing ? Config.GIPFAELI_SPEED.getAsDouble() : this.volley.speed;
    }

    // Where to point so that a round leaving now meets a target that keeps moving as it is moving:
    // the aimbot. The time of flight depends on where the target will be, which depends on the
    // time of flight, so it is run round a few times from a first guess - three passes is already
    // closer than a bullet is wide, for anything a mob or a player can do.
    //
    // The target's pace is taken off where it was a tick ago rather than off its stated velocity,
    // because for a player on the server the stated velocity is whatever the client last sent, and
    // the position is the thing that is actually true.
    public static Vec3 lead(Vec3 muzzle, LivingEntity target, double projectileSpeed) {
        Vec3 now = target.getBoundingBox().getCenter();
        if (projectileSpeed <= 0.0 || !Config.ARMY_AIMBOT.get()) {
            return now.subtract(muzzle);
        }

        Vec3 pace = new Vec3(target.getX() - target.xOld, target.getY() - target.yOld, target.getZ() - target.zOld);
        // Something falling or being thrown is not going to keep doing that in a straight line;
        // its own gravity will see to it. Only the ground pace is worth leading.
        pace = new Vec3(pace.x, target.onGround() ? 0.0 : pace.y, pace.z);

        double flight = now.distanceTo(muzzle) / projectileSpeed;
        for (int pass = 0; pass < 3; pass++) {
            flight = now.add(pace.scale(flight)).distanceTo(muzzle) / projectileSpeed;
        }

        return now.add(pace.scale(flight)).subtract(muzzle);
    }

    // Fires one pull of the trigger from shooter towards direction, and says whether anything left
    // the muzzle. The target is only consulted by the launcher, which hands it to the rocket to
    // chase; every other weapon has already been aimed by whoever called this.
    public boolean fire(ServerLevel level, LivingEntity shooter, Vec3 direction, @Nullable LivingEntity target) {
        if (!this.volley.fires()) {
            return false;
        }

        Vec3 muzzle = shooter.getEyePosition().add(direction.normalize().scale(0.6));

        if (this.volley.homing) {
            GipfaeliRocket rocket = new GipfaeliRocket(level, shooter, direction, target);
            rocket.setPos(muzzle);
            level.addFreshEntity(rocket);
        } else {
            float damage = (float) (this.volley.damage * Config.ARMY_WEAPON_DAMAGE.getAsDouble());
            // Every round in the air at once rather than a round a tick: a burst that leaves the
            // muzzle together is one decision, and one decision is all the shooter made.
            for (int round = 0; round < this.volley.rounds; round++) {
                Vec3 aim = scatter(level, direction, this.volley.spreadDegrees);
                GipfaeliBullet bullet = new GipfaeliBullet(level, shooter, aim, this.volley.speed, damage, this.lifetimeTicks());
                bullet.setPos(muzzle);
                level.addFreshEntity(bullet);
            }
        }

        // A puff and a lick of flame at the muzzle, so a shot is seen as well as heard - and seen
        // from across a field, where the sound has long since stopped carrying.
        level.sendParticles(ParticleTypes.SMOKE, muzzle.x, muzzle.y, muzzle.z, 2, 0.05, 0.05, 0.05, 0.01);
        level.sendParticles(ParticleTypes.FLAME, muzzle.x, muzzle.y, muzzle.z, 1, 0.02, 0.02, 0.02, 0.01);

        level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(),
                this.sound, SoundSource.PLAYERS, this == PANZER ? 0.5F : 1.0F, this.pitch);
        return true;
    }

    // Firing one by hand. A Gipfaeli gun is a real weapon in a player's pack as much as it is the
    // thing that makes a soldier worth signing on, and both of them spend the same tin of pastry -
    // so the ammo, the cooldown and the shot itself are all the weapon's own.
    //
    // The launcher is not here: it has had its own trigger and its own sight since long before the
    // army did (see GipfaeliLauncher), and taking that over would cost it both. Neither is the
    // banner, which has nothing to fire.
    public static boolean use(Player player, ItemStack stack, Level level) {
        GipfaeliWeapon weapon = of(stack);
        if (weapon == null || weapon == BOMBER || !weapon.fires() || !Config.on(Config.ENABLE_GIPFAELI_ARMY)) {
            return false;
        }

        if (player.getCooldowns().isOnCooldown(stack)) {
            return false;
        }

        boolean free = player.getAbilities().instabuild || !Config.GIPFAELI_CONSUMES_AMMO.get();
        int ammoSlot = free ? GipfaeliLauncher.NO_AMMO : GipfaeliLauncher.findAmmoSlot(player);
        if (!free && ammoSlot == GipfaeliLauncher.NO_AMMO) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.DISPENSER_FAIL, SoundSource.PLAYERS, 0.8F, 1.0F);
            GipfaeliArmy.readout(player, Component.translatable("gipfeliarmy.gipfaeli.empty"));
            return true;
        }

        if (level instanceof ServerLevel serverLevel) {
            weapon.fire(serverLevel, player, player.getLookAngle(), null);
            if (!free) {
                player.getInventory().getItem(ammoSlot).shrink(1);
            }
        }

        // Outside the server check on purpose: the shove has to happen where the player's movement is
        // simulated, and the climb where their aim lives, and both of those are the client.
        GipfaeliRecoil.apply(player, weapon.recoil());

        player.getCooldowns().addCooldown(stack, weapon.cooldownTicks());
        return true;
    }

    // Long enough for a round to cross the weapon's range and no longer, so a stray pellet is not
    // still travelling half a chunk past where the shotgun could have hit anything.
    private int lifetimeTicks() {
        return Math.max(5, (int) Math.ceil(this.range / this.volley.speed) + 4);
    }

    // Nudges an aim off centre by up to the given angle, in a direction picked at random around it.
    private static Vec3 scatter(ServerLevel level, Vec3 direction, double degrees) {
        Vec3 aim = direction.normalize();
        if (degrees <= 0.0) {
            return aim;
        }

        // Built from two right-angled offsets rather than a rotation: a Gaussian on each spreads the
        // shots thickest around the middle, which is what a scattergun does and what a rifle that
        // pulls a little does too.
        Vec3 sideways = aim.cross(Math.abs(aim.y) < 0.99 ? new Vec3(0.0, 1.0, 0.0) : new Vec3(1.0, 0.0, 0.0)).normalize();
        Vec3 upwards = aim.cross(sideways).normalize();

        double spread = Math.tan(Math.toRadians(degrees));
        double x = level.getRandom().nextGaussian() * spread * 0.5;
        double y = level.getRandom().nextGaussian() * spread * 0.5;
        return aim.add(sideways.scale(x)).add(upwards.scale(y)).normalize();
    }
}
