package ch.bbcag.combatupdate.client;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.properties.select.SelectItemModelProperty;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import org.jspecify.annotations.Nullable;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.Config;

// Drives the enchanted book's texture (assets/minecraft/items/enchanted_book.json): a book that
// teaches one of this mod's enchantments shows that enchantment's own cover instead of vanilla's.
//
// A book holding several of ours shows the first one it lists. Nothing is reported when the
// textures are switched off, or for a book of vanilla enchantments only, and the model falls
// back to the vanilla book. Read every frame, so flipping the config switch shows straight away.
public record BookEnchantmentProperty() implements SelectItemModelProperty<ResourceKey<Enchantment>> {
    public static final Codec<ResourceKey<Enchantment>> VALUE_CODEC = ResourceKey.codec(Registries.ENCHANTMENT);
    public static final SelectItemModelProperty.Type<BookEnchantmentProperty, ResourceKey<Enchantment>> TYPE =
            SelectItemModelProperty.Type.create(MapCodec.unit(new BookEnchantmentProperty()), VALUE_CODEC);

    @Override
    public @Nullable ResourceKey<Enchantment> get(ItemStack itemStack, @Nullable ClientLevel level,
            @Nullable LivingEntity owner, int seed, ItemDisplayContext displayContext) {
        if (!Config.on(Config.ENABLE_BOOK_TEXTURES)) {
            return null;
        }

        ItemEnchantments stored = itemStack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        for (Holder<Enchantment> enchantment : stored.keySet()) {
            ResourceKey<Enchantment> key = enchantment.unwrapKey().orElse(null);
            if (key != null && key.identifier().getNamespace().equals(CombatUpdate.MODID)) {
                return key;
            }
        }

        return null;
    }

    @Override
    public SelectItemModelProperty.Type<BookEnchantmentProperty, ResourceKey<Enchantment>> type() {
        return TYPE;
    }

    @Override
    public Codec<ResourceKey<Enchantment>> valueCodec() {
        return VALUE_CODEC;
    }
}
