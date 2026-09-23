package ch.bbcag.combatupdate;

import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

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

import ch.bbcag.combatupdate.entity.GipfaeliBullet;
import ch.bbcag.combatupdate.entity.GipfaeliRocket;

// The guns the Gipfaeli army is issued with, and the one place that says what pulling any of their
// triggers does.
//
// A soldier fires them out of its combat goal and a player fires them by right-clicking, and both
// come through fire() below - so a weapon behaves the same in either pair of hands, and there is
// only the one set of numbers to balance.
//
// Three of them throw crumbs in a spread (see GipfaeliBullet); the fourth is the launcher the mod
// already had, which throws the homing pastry instead. That one is why a squad is worth arming
// rather than just outnumbering someone: it is the only weapon here that follows its target.
public enum GipfaeliWeapon {
    // The squad's rifle. A short burst with a little scatter on it and a cooldown short enough that
    // three of them between them keep a target pinned.
    AK47(() -> CombatUpdate.LETONY_MATE_AK47.get(),
            3, 3.0F, 3.5, 2.2, 20, 28.0, 12.0, 0.0, SoundEvents.CROSSBOW_SHOOT, 1.7F),

    // Close work. A fistful of crumbs at once, which past a dozen blocks is a fistful of crumbs
    // going nowhere near anything - so a soldier carrying one walks in until it is worth firing.
    SHOTGUN(() -> CombatUpdate.GIPFAELI_SHOTGUN.get(),
            8, 2.0F, 9.0, 1.8, 35, 14.0, 5.0, 0.0, SoundEvents.CROSSBOW_SHOOT, 0.7F),

    // The long shot: one crumb, hard, and the patience to reload. Soldiers with these hang back at
    // the edge of their range, which is the whole point of handing one out.
    MARKSMAN(() -> CombatUpdate.GIPFAELI_MARKSMAN.get(),
            1, 11.0F, 0.6, 3.5, 55, 64.0, 30.0, 0.0, SoundEvents.FIREWORK_ROCKET_BLAST, 1.6F),

    // The launcher, in a soldier's hands. It fires the same Gipfaeli a player's launcher does,
    // locked on to whatever the squad was pointed at, so this one shot bends after a target that
    // breaks for cover.
    LAUNCHER(() -> CombatUpdate.GIPFAELI_LAUNCHER.get(),
            1, 0.0F, 1.0, 0.0, 90, 40.0, 16.0, 8.0, SoundEvents.FIREWORK_ROCKET_LAUNCH, 0.6F);

    private final Supplier<Item> item;
    // How many projectiles one pull of the trigger throws, what each one costs its target, and how
    // far off the aim they scatter.
    private final int rounds;
    private final float damage;
    private final double spreadDegrees;
    // Blocks per tick, and with it - against the range below - how long a round stays in the air.
    private final double speed;
    private final int cooldownTicks;
    // How far the weapon is worth firing, and how close its carrier tries to stand. The standoff is
    // what tells a marksman to hang back and a shotgun to walk in.
    private final double range;
    private final double standoff;
    // And how close is too close to fire at all. Only the launcher has one, and only because what
    // it throws goes off where it lands: a rocket loosed at something already on top of you takes
    // you and whoever is stood beside you with it.
    private final double minimum;
    private final SoundEvent sound;
    private final float pitch;

    GipfaeliWeapon(Supplier<Item> item, int rounds, float damage, double spreadDegrees, double speed,
            int cooldownTicks, double range, double standoff, double minimum, SoundEvent sound, float pitch) {
        this.item = item;
        this.rounds = rounds;
        this.damage = damage;
        this.spreadDegrees = spreadDegrees;
        this.speed = speed;
        this.cooldownTicks = cooldownTicks;
        this.range = range;
        this.standoff = standoff;
        this.minimum = minimum;
        this.sound = sound;
        this.pitch = pitch;
    }

    // Which weapon a held item is, or null if it is not one of ours. This is how a soldier knows
    // what it is carrying: the gun in its hand is the only record of it.
    public static @Nullable GipfaeliWeapon of(ItemStack stack) {
        if (stack.isEmpty()) {
            return null;
        }

        for (GipfaeliWeapon weapon : values()) {
            if (stack.is(weapon.item.get())) {
                return weapon;
            }
        }

        return null;
    }

    // Firing one by hand. A Gipfaeli gun is a real weapon in a player's pack as much as it is the
    // thing that makes a soldier worth signing on, and both of them spend the same tin of pastry -
    // so the ammo, the cooldown and the shot itself are all the weapon's own.
    //
    // The launcher is not here: it has had its own trigger and its own sight since long before the
    // army did (see GipfaeliLauncher), and taking that over would cost it both.
    public static boolean use(Player player, ItemStack stack, Level level) {
        GipfaeliWeapon weapon = of(stack);
        if (weapon == null || weapon == LAUNCHER || !Config.on(Config.ENABLE_GIPFAELI_ARMY)) {
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
            GipfaeliArmy.readout(player, Component.translatable("combatupdate.gipfaeli.empty"));
            return true;
        }

        if (level instanceof ServerLevel serverLevel) {
            weapon.fire(serverLevel, player, player.getLookAngle(), null);
            if (!free) {
                player.getInventory().getItem(ammoSlot).shrink(1);
            }
        }

        player.getCooldowns().addCooldown(stack, weapon.cooldownTicks());
        return true;
    }

    public Item item() {
        return this.item.get();
    }

    public ItemStack stack() {
        return new ItemStack(this.item.get());
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

    // Fires one pull of the trigger from shooter towards direction. The target is only consulted by
    // the launcher, which hands it to the rocket to chase; every other weapon has already been
    // aimed by whoever called this.
    public void fire(ServerLevel level, LivingEntity shooter, Vec3 direction, @Nullable LivingEntity target) {
        Vec3 muzzle = shooter.getEyePosition().add(direction.normalize().scale(0.6));

        if (this == LAUNCHER) {
            GipfaeliRocket rocket = new GipfaeliRocket(level, shooter, direction, target);
            rocket.setPos(muzzle);
            level.addFreshEntity(rocket);
        } else {
            float damage = (float) (this.damage * Config.ARMY_WEAPON_DAMAGE.getAsDouble());
            // Every round in the air at once rather than a round a tick: a burst that leaves the
            // muzzle together is one decision, and one decision is all the shooter made.
            for (int round = 0; round < this.rounds; round++) {
                Vec3 aim = scatter(level, direction, this.spreadDegrees);
                GipfaeliBullet bullet = new GipfaeliBullet(level, shooter, aim, this.speed, damage, this.lifetimeTicks());
                bullet.setPos(muzzle);
                level.addFreshEntity(bullet);
            }
        }

        level.playSound(null, shooter.getX(), shooter.getY(), shooter.getZ(),
                this.sound, SoundSource.PLAYERS, 1.0F, this.pitch);
    }

    // Long enough for a round to cross the weapon's range and no longer, so a stray pellet is not
    // still travelling half a chunk past where the shotgun could have hit anything.
    private int lifetimeTicks() {
        return Math.max(5, (int) Math.ceil(this.range / this.speed) + 4);
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
