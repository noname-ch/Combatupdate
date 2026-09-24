package ch.bbcag.combatupdate;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.combatupdate.entity.TrainingDummy;

// Sets a TrainingDummy down on the block clicked, facing whoever put it there, the way an armour
// stand is set down: turned to the nearest eighth of a circle, and only where it fits.
public final class TrainingDummyItem extends Item {
    public TrainingDummyItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!Config.on(Config.ENABLE_TRAINING_DUMMY) || context.getClickedFace() == Direction.DOWN) {
            return InteractionResult.FAIL;
        }

        Level level = context.getLevel();
        BlockPos pos = new BlockPlaceContext(context).getClickedPos();
        Vec3 feet = Vec3.atBottomCenterOf(pos);
        EntityType<TrainingDummy> type = CombatUpdate.TRAINING_DUMMY.get();
        AABB box = type.getDimensions().makeBoundingBox(feet.x(), feet.y(), feet.z());
        if (!level.noCollision(null, box) || !level.getEntities(null, box).isEmpty()) {
            return InteractionResult.FAIL;
        }

        ItemStack stack = context.getItemInHand();
        if (level instanceof ServerLevel serverLevel) {
            TrainingDummy dummy = type.create(serverLevel, EntityType.createDefaultStackConfig(serverLevel, stack, context.getPlayer()),
                    pos, EntitySpawnReason.SPAWN_ITEM_USE, true, true);
            if (dummy == null) {
                return InteractionResult.FAIL;
            }

            float yaw = Mth.floor((Mth.wrapDegrees(context.getRotation() - 180.0F) + 22.5F) / 45.0F) * 45.0F;
            dummy.snapTo(dummy.getX(), dummy.getY(), dummy.getZ(), yaw, 0.0F);
            dummy.setYHeadRot(yaw);
            dummy.setYBodyRot(yaw);
            serverLevel.addFreshEntity(dummy);
            level.playSound(null, dummy.getX(), dummy.getY(), dummy.getZ(), SoundEvents.ARMOR_STAND_PLACE, SoundSource.BLOCKS, 0.75F, 0.8F);
            dummy.gameEvent(GameEvent.ENTITY_PLACE, context.getPlayer());
        }

        stack.shrink(1);
        return InteractionResult.SUCCESS;
    }
}
