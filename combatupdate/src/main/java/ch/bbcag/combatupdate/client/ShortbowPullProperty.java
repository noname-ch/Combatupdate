package ch.bbcag.combatupdate.client;

import com.mojang.serialization.MapCodec;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.numeric.RangeSelectItemModelProperty;
import net.minecraft.client.renderer.item.properties.numeric.UseDuration;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

// Drives the Shortbow item model's pull-stage texture (assets/minecraft/items/bow.json).
// Off cooldown the shot will insta-charge on release, so the bow should already look fully
// drawn the moment it's raised. On cooldown it draws normally, so this reports the real
// hold time instead, same as vanilla's "minecraft:use_duration" property.
public record ShortbowPullProperty() implements RangeSelectItemModelProperty {
    public static final MapCodec<ShortbowPullProperty> MAP_CODEC = MapCodec.unit(new ShortbowPullProperty());

    // Matches BowItem.MAX_DRAW_DURATION; combined with the model's 0.05 scale this reaches 1.0.
    private static final float FULLY_DRAWN_TICKS = 20.0F;

    @Override
    public float get(ItemStack itemStack, @Nullable ClientLevel level, @Nullable ItemOwner owner, int seed) {
        LivingEntity entity = owner == null ? null : owner.asLivingEntity();
        if (entity == null || entity.getUseItem() != itemStack) {
            return 0.0F;
        }

        if (entity instanceof Player player && player.getCooldowns().isOnCooldown(itemStack)) {
            return UseDuration.useDuration(itemStack, entity);
        }

        return FULLY_DRAWN_TICKS;
    }

    @Override
    public MapCodec<ShortbowPullProperty> type() {
        return MAP_CODEC;
    }
}
