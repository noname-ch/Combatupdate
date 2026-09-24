package ch.bbcag.combatupdate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.item.PrimedTnt;

// PrimedTnt only ever sets its explosion power from its constructor or saved NBT, with no event or
// setter to hook into, so this exposes the private field for CombatUpdate#onEntityJoinLevel to write to.
@Mixin(PrimedTnt.class)
public interface PrimedTntAccessor {
    @Accessor("explosionPower")
    @Mutable
    void setExplosionPower(float explosionPower);
}
