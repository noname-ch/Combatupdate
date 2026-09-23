package ch.bbcag.combatupdate;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUseAnimation;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.combatupdate.entity.ScarletSpear;

// The Scarlet Devil, after the Calamity mod's endgame Terraria rogue spear (itself Remilia Scarlet's
// Spear the Gungnir). Held back and let go like a trident, but it is never used up: what leaves the
// hand is a spear of red light (see ScarletSpear), and the real one stays put.
//
// Calamity's stealth strike has no stealth to hang off here, so it becomes a charge instead. Hold the
// throw long enough and the spear chimes; let go after that and it goes out as a Gungnir - bigger,
// harder-hitting, with a wider blast, and healing whoever threw it for everything it runs through.
//
// Its own Item rather than properties on a plain one, like the other weapons here, because the
// hold-and-release throw lives in Item's use methods and there is no event to hang it off instead.
public final class ScarletDevil extends Item {
    // What it hits for in melee: a trident passes 8.0 for 9 damage, so 11.0 is 12.
    private static final double ATTACK_DAMAGE_BASELINE = 11.0;

    // Tridents stab at -2.9, a little over one swing a second; -2.6 is 1.4. A spear still, not a sword.
    private static final double ATTACK_SPEED_BASELINE = -2.6;

    // How close to the head the spear leaves the hand, in blocks along the look direction.
    private static final double RELEASE_OFFSET = 0.8;

    // How far out the red light the spear draws in while it charges starts from, in blocks.
    private static final double CHARGE_REACH = 1.8;

    public ScarletDevil(Item.Properties properties) {
        super(properties);
    }

    public static Item.Properties properties(Item.Properties properties) {
        return properties.attributes(ItemAttributeModifiers.builder()
                        .add(Attributes.ATTACK_DAMAGE, new AttributeModifier(BASE_ATTACK_DAMAGE_ID,
                                ATTACK_DAMAGE_BASELINE, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                        .add(Attributes.ATTACK_SPEED, new AttributeModifier(BASE_ATTACK_SPEED_ID,
                                ATTACK_SPEED_BASELINE, AttributeModifier.Operation.ADD_VALUE), EquipmentSlotGroup.MAINHAND)
                        .build())
                .component(DataComponents.TOOL, TridentItem.createToolProperties())
                .stacksTo(1)
                .fireResistant()
                .rarity(Rarity.EPIC);
    }

    @Override
    public ItemUseAnimation getUseAnimation(ItemStack stack) {
        return ItemUseAnimation.TRIDENT;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity user) {
        // As long as it is held, the way the trident's is; releaseUsing decides what the hold was worth.
        return 72000;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        // Vanilla already refuses the use while the item is cooling down.
        if (!Config.on(Config.ENABLE_SCARLET_DEVIL)) {
            return InteractionResult.PASS;
        }

        player.startUsingItem(hand);
        return InteractionResult.CONSUME;
    }

    // While it charges, red light is drawn in to the spear, faster the nearer it is to a Gungnir. The
    // tick the hold crosses the line it chimes and flares; after that it keeps a pulsing halo, so
    // there is no doubt what is about to be thrown.
    @Override
    public void onUseTick(Level level, LivingEntity user, ItemStack stack, int ticksRemaining) {
        if (!(level instanceof ServerLevel serverLevel) || !Config.on(Config.ENABLE_SCARLET_DEVIL)) {
            return;
        }

        int held = heldFor(stack, user, ticksRemaining);
        int charge = Config.GUNGNIR_CHARGE_TICKS.getAsInt();
        Vec3 look = user.getLookAngle();
        Vec3 tip = user.getEyePosition().add(look.scale(RELEASE_OFFSET));
        if (held == charge) {
            serverLevel.playSound(null, user.getX(), user.getY(), user.getZ(),
                    SoundEvents.TRIDENT_RETURN, SoundSource.PLAYERS, 1.0F, 0.6F);
            serverLevel.playSound(null, user.getX(), user.getY(), user.getZ(),
                    SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 0.8F, 1.3F);
            serverLevel.sendParticles(new DustParticleOptions(ScarletSpear.SCARLET, 1.5F),
                    tip.x, tip.y, tip.z, 20, 0.3, 0.3, 0.3, 0.0);
            ParticleStreaks.ring(serverLevel, tip, look, 1.6, 20, 5, ParticleStreaks.solid(ScarletSpear.SCARLET));
        } else if (held < charge) {
            if (held % 2 == 0) {
                int streaks = 1 + 3 * held / charge;
                for (int i = 0; i < streaks; i++) {
                    Vec3 out = new Vec3(user.getRandom().nextGaussian(), user.getRandom().nextGaussian(),
                            user.getRandom().nextGaussian());
                    if (out.lengthSqr() > 1.0E-6) {
                        ParticleStreaks.send(serverLevel, tip.add(out.normalize().scale(CHARGE_REACH)), tip,
                                ScarletSpear.SCARLET, 6);
                    }
                }
            }
        } else if ((held - charge) % 6 == 0) {
            ParticleStreaks.ring(serverLevel, tip, look, 0.7, 12, 4, ParticleStreaks.solid(ScarletSpear.SCARLET));
            serverLevel.sendParticles(ParticleTypes.CRIMSON_SPORE, tip.x, tip.y, tip.z, 3, 0.2, 0.2, 0.2, 0.0);
        }
    }

    @Override
    public boolean releaseUsing(ItemStack stack, Level level, LivingEntity user, int ticksRemaining) {
        int held = heldFor(stack, user, ticksRemaining);
        if (!(user instanceof Player player)
                || !Config.on(Config.ENABLE_SCARLET_DEVIL)
                || held < TridentItem.THROW_THRESHOLD_TIME) {
            return false;
        }

        player.awardStat(Stats.ITEM_USED.get(this));
        if (level instanceof ServerLevel serverLevel) {
            boolean gungnir = held >= Config.GUNGNIR_CHARGE_TICKS.getAsInt();
            Vec3 look = player.getLookAngle();
            Vec3 from = player.getEyePosition().add(look.scale(RELEASE_OFFSET)).subtract(0.0, 0.1, 0.0);
            ScarletSpear spear = new ScarletSpear(serverLevel, player, look, gungnir);
            spear.setPos(from.x, from.y, from.z);
            serverLevel.addFreshEntity(spear);

            serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.TRIDENT_THROW, SoundSource.PLAYERS, 1.0F, gungnir ? 0.6F : 1.1F);
            // A ring of red thrown off square to the throw, like the air parting in front of it.
            ParticleStreaks.ring(serverLevel, from, look, gungnir ? 2.5 : 1.2, gungnir ? 24 : 12, 5,
                    ParticleStreaks.solid(ScarletSpear.SCARLET));
            if (gungnir) {
                serverLevel.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, ScarletSpear.SCARLET),
                        from.x, from.y, from.z, 1, 0.0, 0.0, 0.0, 0.0);
                serverLevel.playSound(null, player.getX(), player.getY(), player.getZ(),
                        SoundEvents.WITHER_SHOOT, SoundSource.PLAYERS, 0.6F, 1.4F);
            }

            player.getCooldowns().addCooldown(stack, Config.SCARLET_DEVIL_COOLDOWN_TICKS.getAsInt());
        }

        return true;
    }

    private int heldFor(ItemStack stack, LivingEntity user, int ticksRemaining) {
        return this.getUseDuration(stack, user) - ticksRemaining;
    }
}
