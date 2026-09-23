package ch.bbcag.combatupdate;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;

import ch.bbcag.combatupdate.entity.ZenithBlade;

// The Zenith, after Terraria's own endgame sword: the one forged out of every other sword, which then
// swings none of them. What a swing sends out instead is a phantom of one of the swords it was made
// from (see ZenithBlade), looping out to wherever the player is aiming and back to their hand, and
// cutting through everything on the way there and on the way back.
//
// As in Terraria it is an auto-reuse weapon: holding the attack key keeps it swinging (see
// ExobladeAutoSwing), and every one of those swings is a full-strength one, so a held key keeps a
// stream of phantoms in the air. A swing thrown before the strength meter fills throws nothing, the
// same bargain the Exoblade strikes with its beam.
//
// It has no right-click. The Zenith never needed one.
public final class Zenith {
    // Netherite swords pass 3.0 here and land at 8 damage; 7.0 puts this at 12. Less than the
    // Exoblade: the phantoms are where the Zenith's damage is.
    private static final float ATTACK_DAMAGE_BASELINE = 7.0F;

    // -2.4 is a netherite sword's 1.6 swings a second, the Exoblade's -2.0 is 2.0; -1.6 is 2.4. The
    // fastest sword here, since every swing is phantoms and phantoms are the point.
    private static final float ATTACK_SPEED_BASELINE = -1.6F;

    // How full the strength meter has to be for a swing to throw phantoms. Not quite 1.0, for the
    // reason Exoblade#FULL_SWING gives.
    private static final float FULL_SWING = 0.9F;

    // The shortest a loop goes, in blocks, so a swing at the player's own feet still throws its
    // phantoms somewhere rather than tangling them up in the hand.
    private static final double MIN_REACH = 4.0;

    // How far, in blocks, each phantom's far end is scattered about the aim point, so a swing's
    // phantoms fan out over what was aimed at instead of all passing through one spot.
    private static final double AIM_SCATTER = 0.8;

    // How wide a loop is either side of the line out and back, as a share of its length. Each phantom
    // picks its own, and its own tilt round that line, so no two trace the same path.
    private static final double MIN_LOOP_WIDTH = 0.15;
    private static final double MAX_LOOP_WIDTH = 0.4;

    private Zenith() {
    }

    public static Item.Properties properties(Item.Properties properties) {
        return properties.sword(ToolMaterial.NETHERITE, ATTACK_DAMAGE_BASELINE, ATTACK_SPEED_BASELINE)
                .fireResistant()
                .rarity(Rarity.EPIC);
    }

    public static boolean wielding(Player player) {
        return Config.on(Config.ENABLE_ZENITH) && player.getMainHandItem().is(CombatUpdate.ZENITH.get());
    }

    // A swing at the air or at a block, heard through ExobladeSwingMixin for the reason given there.
    public static void onSwing(ServerPlayer player) {
        if (player.getAttackStrengthScale(0.5F) >= FULL_SWING) {
            throwPhantoms(player);
        }
    }

    // A swing that connects. As with the Exoblade, the server resets the meter before the swing packet
    // that follows arrives, so onSwing reads an empty meter for these and never throws twice.
    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.getAttackStrengthScale(0.5F) >= FULL_SWING) {
            throwPhantoms(player);
        }
    }

    private static void throwPhantoms(ServerPlayer player) {
        if (!wielding(player)) {
            return;
        }

        ServerLevel level = player.level();
        RandomSource random = player.getRandom();
        Entity aimed = null;
        Vec3 aimPoint;
        EntityHitResult entityHit = aimedEntity(player);
        if (entityHit != null) {
            aimed = entityHit.getEntity();
            aimPoint = aimed.getBoundingBox().getCenter();
        } else {
            aimPoint = aimedPoint(player);
        }

        Vec3 anchor = ZenithBlade.anchor(player);
        for (int i = 0; i < Config.ZENITH_BLADES_PER_SWING.getAsInt(); i++) {
            Vec3 far = aimPoint.add(random.nextGaussian() * AIM_SCATTER, random.nextGaussian() * AIM_SCATTER,
                    random.nextGaussian() * AIM_SCATTER);
            Vec3 reach = far.subtract(anchor);
            if (reach.length() < MIN_REACH) {
                Vec3 heading = reach.lengthSqr() > 1.0E-6 ? reach.normalize() : player.getLookAngle();
                reach = heading.scale(MIN_REACH);
            }

            // Somewhere at right angles to the line out, at a random tilt round it.
            Vec3 across = ParticleStreaks.perpendicular(reach);
            Vec3 up = reach.normalize().cross(across);
            double tilt = random.nextDouble() * Math.PI * 2.0;
            double width = reach.length() * (MIN_LOOP_WIDTH + random.nextDouble() * (MAX_LOOP_WIDTH - MIN_LOOP_WIDTH));
            Vec3 side = across.scale(Math.cos(tilt) * width).add(up.scale(Math.sin(tilt) * width));

            ZenithBlade blade = new ZenithBlade(level, player, reach, side, random.nextInt(ZenithBlade.SWORD_COUNT), aimed);
            blade.setPos(anchor.x, anchor.y, anchor.z);
            level.addFreshEntity(blade);
        }

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.8F, 1.3F + random.nextFloat() * 0.3F);
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS, 0.6F, 1.8F + random.nextFloat() * 0.2F);
    }

    // Whatever living thing the player is pointing at within reach, with nothing solid in between.
    private static @Nullable EntityHitResult aimedEntity(ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = aimedPoint(player);
        return ProjectileUtil.getEntityHitResult(player, eye, end,
                player.getBoundingBox().expandTowards(end.subtract(eye)).inflate(1.0),
                candidate -> candidate instanceof LivingEntity && candidate.isPickable() && !candidate.isSpectator(),
                eye.distanceToSqr(end));
    }

    // Where the player's look first meets a block, or where it runs out of reach if it meets none.
    private static Vec3 aimedPoint(ServerPlayer player) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(Config.ZENITH_REACH.getAsDouble()));
        HitResult block = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return block.getType() == HitResult.Type.MISS ? end : block.getLocation();
    }
}
