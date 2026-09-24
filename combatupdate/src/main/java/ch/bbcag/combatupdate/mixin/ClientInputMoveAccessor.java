package ch.bbcag.combatupdate.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.player.ClientInput;
import net.minecraft.world.phys.Vec2;

// The movement keys reach the player as ClientInput's move vector, which only its subclasses can
// write. A slide has no use for them (see MovementClient#onMovementInput), so this lets it clear them.
@Mixin(ClientInput.class)
public interface ClientInputMoveAccessor {
    @Accessor("moveVector")
    void combatupdate$setMoveVector(Vec2 moveVector);
}
