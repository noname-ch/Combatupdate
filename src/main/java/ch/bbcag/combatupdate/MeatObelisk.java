package ch.bbcag.combatupdate;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

// Nine porkchops, emulsified, liquefied, strained and joined into one block of deli ham. Setting it
// down has a calm voice explain to everyone in earshot what it is they have just made.
//
// Hooked on setPlacedBy rather than onPlace, so the reading is for a block someone put there by
// hand: a /setblock, a structure or a piston shuffling one about stays quiet.
public final class MeatObelisk extends Block {
    public MeatObelisk(Properties properties) {
        super(properties);
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        // Played from the server with no player to leave out, so the one who placed it hears it too.
        if (!level.isClientSide()) {
            level.playSound(null, pos, CombatUpdate.MEAT_OBELISK_SPEECH.get(), SoundSource.BLOCKS, 1.0F, 1.0F);
        }
    }
}
