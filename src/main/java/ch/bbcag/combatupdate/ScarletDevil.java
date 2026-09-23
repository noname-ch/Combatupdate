package ch.bbcag.combatupdate;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.DustParticleOptions;
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

    // The chime that says a Gungnir is ready, on the one tick the hold crosses the line.
    @Override
    public void onUseTick(Level level, LivingEntity user, ItemStack stack, int ticksRemaining) {
        if (level instanceof ServerLevel serverLevel
                && Config.on(Config.ENABLE_SCARLET_DEVIL)
                && heldFor(stack, user, ticksRemaining) == Config.GUNGNIR_CHARGE_TICKS.getAsInt()) {
            serverLevel.playSound(null, user.getX(), user.getY(), user.getZ(),
                    SoundEvents.TRIDENT_RETURN, SoundSource.PLAYERS, 1.0F, 0.6F);
            Vec3 hand = user.getEyePosition().add(user.getLookAngle().scale(RELEASE_OFFSET));
            serverLevel.sendParticles(new DustParticleOptions(ScarletSpear.SCARLET, 1.5F),
                    hand.x, hand.y, hand.z, 20, 0.3, 0.3, 0.3, 0.0);
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
            if (gungnir) {
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
