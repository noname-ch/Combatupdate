package ch.bbcag.combatupdate;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;

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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.EquipmentSlotGroup;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.combatupdate.entity.ScarletSpear;

// The Scarlet Devil, after the Calamity mod's endgame Terraria rogue spear (itself Remilia Scarlet's
// Spear the Gungnir). Thrown the way a rogue weapon is: right-click throws at once, and holding it
// keeps throwing as fast as the cooldown allows. It is never used up: what leaves the hand is a spear
// of red light (see ScarletSpear), and the real one stays put.
//
// Calamity's stealth strike carries over as it works there: stealth builds while you are not
// attacking, and the first throw made at full stealth is the strike. Here that means going a while
// without throwing; the next throw then goes out as a Gungnir - bigger, harder-hitting, with a wider
// blast, and healing whoever threw it for everything it runs through. The spear draws light in while
// it builds, and chimes and keeps a halo once it is full, so there is no guessing.
//
// Its own Item rather than properties on a plain one, like the other weapons here, because the
// stealth effects hang off Item's inventory tick and there is no event to hang them off instead.
public final class ScarletDevil extends Item {
    // What it hits for in melee: a trident passes 8.0 for 9 damage, so 11.0 is 12.
    private static final double ATTACK_DAMAGE_BASELINE = 11.0;

    // Tridents stab at -2.9, a little over one swing a second; -2.6 is 1.4. A spear still, not a sword.
    private static final double ATTACK_SPEED_BASELINE = -2.6;

    // How close to the head the spear leaves the hand, in blocks along the look direction.
    private static final double RELEASE_OFFSET = 0.8;

    // How far out the red light the spear draws in while stealth builds starts from, in blocks.
    private static final double CHARGE_REACH = 1.8;

    // The game time each player last threw at. Server only. No entry counts as full stealth, the way
    // a fight opens with it in Calamity.
    private static final Map<UUID, Long> LAST_THROW = new ConcurrentHashMap<>();

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

    // How many ticks of stealth the player has built up, since their last throw.
    private static long stealth(ServerLevel level, Player player) {
        Long last = LAST_THROW.get(player.getUUID());
        return last == null ? Long.MAX_VALUE : level.getGameTime() - last;
    }

    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        // Vanilla already refuses the use while the item is cooling down, and repeats it while the key
        // is held, which is what makes holding it keep throwing.
        if (!Config.on(Config.ENABLE_SCARLET_DEVIL)) {
            return InteractionResult.PASS;
        }

        player.awardStat(Stats.ITEM_USED.get(this));
        if (level instanceof ServerLevel serverLevel) {
            boolean gungnir = stealth(serverLevel, player) >= Config.GUNGNIR_CHARGE_TICKS.getAsInt();
            LAST_THROW.put(player.getUUID(), serverLevel.getGameTime());
            throwSpear(serverLevel, player, gungnir);
            player.getCooldowns().addCooldown(player.getItemInHand(hand), Config.SCARLET_DEVIL_COOLDOWN_TICKS.getAsInt());
        }

        // Success swings the arm: the throw.
        return InteractionResult.SUCCESS;
    }

    private static void throwSpear(ServerLevel level, Player player, boolean gungnir) {
        Vec3 look = player.getLookAngle();
        Vec3 from = player.getEyePosition().add(look.scale(RELEASE_OFFSET)).subtract(0.0, 0.1, 0.0);
        ScarletSpear spear = new ScarletSpear(level, player, look, gungnir);
        spear.setPos(from.x, from.y, from.z);
        level.addFreshEntity(spear);

        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.TRIDENT_THROW, SoundSource.PLAYERS, 1.0F, gungnir ? 0.6F : 1.1F);
        // A ring of red thrown off square to the throw, like the air parting in front of it.
        ParticleStreaks.ring(level, from, look, gungnir ? 2.5 : 1.2, gungnir ? 24 : 12, 5,
                ParticleStreaks.solid(ScarletSpear.SCARLET));
        if (gungnir) {
            level.sendParticles(ColorParticleOption.create(ParticleTypes.FLASH, ScarletSpear.SCARLET),
                    from.x, from.y, from.z, 1, 0.0, 0.0, 0.0, 0.0);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.WITHER_SHOOT, SoundSource.PLAYERS, 0.6F, 1.4F);
        }
    }

    // While it is in hand, shows how much stealth is built up: red light drawn in to the spear, faster
    // the nearer it is to full; a chime and a flare the tick it fills; a pulsing halo after that.
    @Override
    public void inventoryTick(ItemStack stack, ServerLevel level, Entity owner, @Nullable EquipmentSlot slot) {
        if (!(owner instanceof Player player)
                || slot != EquipmentSlot.MAINHAND && slot != EquipmentSlot.OFFHAND
                || !Config.on(Config.ENABLE_SCARLET_DEVIL)) {
            return;
        }

        long stealth = stealth(level, player);
        int full = Config.GUNGNIR_CHARGE_TICKS.getAsInt();
        Vec3 look = player.getLookAngle();
        Vec3 side = ParticleStreaks.perpendicular(look).scale(slot == EquipmentSlot.MAINHAND ? 0.35 : -0.35);
        Vec3 tip = player.getEyePosition().add(look.scale(0.7)).add(side).subtract(0.0, 0.35, 0.0);
        if (stealth == full) {
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.TRIDENT_RETURN, SoundSource.PLAYERS, 1.0F, 0.6F);
            level.playSound(null, player.getX(), player.getY(), player.getZ(),
                    SoundEvents.RESPAWN_ANCHOR_CHARGE, SoundSource.PLAYERS, 0.8F, 1.3F);
            level.sendParticles(new DustParticleOptions(ScarletSpear.SCARLET, 1.5F),
                    tip.x, tip.y, tip.z, 20, 0.3, 0.3, 0.3, 0.0);
            ParticleStreaks.ring(level, tip, look, 1.6, 20, 5, ParticleStreaks.solid(ScarletSpear.SCARLET));
        } else if (stealth < full) {
            if (stealth % 2 == 0) {
                int streaks = 1 + (int) (3 * stealth / full);
                for (int i = 0; i < streaks; i++) {
                    Vec3 out = new Vec3(player.getRandom().nextGaussian(), player.getRandom().nextGaussian(),
                            player.getRandom().nextGaussian());
                    if (out.lengthSqr() > 1.0E-6) {
                        ParticleStreaks.send(level, tip.add(out.normalize().scale(CHARGE_REACH)), tip,
                                ScarletSpear.SCARLET, 6);
                    }
                }
            }
        } else if (level.getGameTime() % 6 == 0) {
            ParticleStreaks.ring(level, tip, look, 0.5, 10, 4, ParticleStreaks.solid(ScarletSpear.SCARLET));
            level.sendParticles(ParticleTypes.CRIMSON_SPORE, tip.x, tip.y, tip.z, 2, 0.15, 0.15, 0.15, 0.0);
        }
    }
}
