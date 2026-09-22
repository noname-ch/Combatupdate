package ch.bbcag.combatupdate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;

// The text-specific half of the same problem; see DisplayAccessor.
@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccessor {
    @Invoker("setText")
    void invokeSetText(Component text);

    @Invoker("setBackgroundColor")
    void invokeSetBackgroundColor(int color);

    @Invoker("setFlags")
    void invokeSetFlags(byte flags);
}
