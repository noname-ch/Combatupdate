package ch.bbcag.combatupdate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.mojang.math.Transformation;

import net.minecraft.world.entity.Display;

// A display entity is normally only ever configured from the NBT it was summoned with, so everything
// that shapes one is private. DamageNumbers builds them in code instead, and needs these two.
@Mixin(Display.class)
public interface DisplayAccessor {
    @Invoker("setBillboardConstraints")
    void invokeSetBillboardConstraints(Display.BillboardConstraints constraints);

    @Invoker("setTransformation")
    void invokeSetTransformation(Transformation transformation);
}
