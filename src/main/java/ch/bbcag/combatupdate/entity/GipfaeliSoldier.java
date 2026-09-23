package ch.bbcag.combatupdate.entity;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Prediction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.FollowOwnerGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.OwnerHurtTargetGoal;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import ch.bbcag.combatupdate.CombatUpdate;
import ch.bbcag.combatupdate.Config;
import ch.bbcag.combatupdate.GipfaeliArmy;
import ch.bbcag.combatupdate.GipfaeliFormation;
import ch.bbcag.combatupdate.GipfaeliGuardPost;
import ch.bbcag.combatupdate.GipfaeliWeapon;

// A soldier of the Gipfaeli army: recruited with the command flag (see GipfaeliArmy), armed with
// whatever kit it was handed, and pointed at things by whoever recruited it.
//
// It is a TamableAnimal because almost everything an army has to do, a tamed wolf already does:
// know whose it is, follow that person about, teleport to them when it falls behind, sit still when
// told, and take on whatever hurt them. What is added here is the part a wolf has no use for - a
// target its owner picked out for it, a place in a formation, a gun to answer with and the aim to
// answer well, and a uniform in the squad's colour.
//
// The client is told two things beyond what it is told about any mob: the item in the soldier's
// hand, which is also the only record of its role, and the colour of its uniform, which is only
// there to be drawn.
public final class GipfaeliSoldier extends TamableAnimal implements RangedAttackMob {
    // Which uniform is drawn: a dye's id, or CAMO for the field uniform every recruit starts in.
    private static final EntityDataAccessor<Integer> DATA_UNIFORM =
            SynchedEntityData.defineId(GipfaeliSoldier.class, EntityDataSerializers.INT);
    private static final int CAMO = -1;

    // Attack or stand: whether the soldier is fighting or on parade. On the client only so the
    // renderer knows to put its hands behind its back.
    private static final EntityDataAccessor<Byte> DATA_STANCE =
            SynchedEntityData.defineId(GipfaeliSoldier.class, EntityDataSerializers.BYTE);

    // Whether this is the squad's commander: the one soldier of its colour who stands out in
    // front, wears black with the squad's stripe, and can be clicked for the whole squad's orders.
    private static final EntityDataAccessor<Boolean> DATA_COMMANDER =
            SynchedEntityData.defineId(GipfaeliSoldier.class, EntityDataSerializers.BOOLEAN);

    // A commander is built a little sturdier than the soldiers it leads.
    private static final double COMMANDER_TOUGHNESS = 1.5;

    // What the squad is doing as a whole. ATTACK is a squad in the field - it engages, it follows
    // orders, it keeps whatever shape it was given. STAND is a squad on parade: in ranks, at
    // attention, shooting nothing unless shot at.
    public enum Stance {
        ATTACK, STAND;

        private static final Stance[] ALL = values();

        public static Stance byOrdinal(int ordinal) {
            return ordinal >= 0 && ordinal < ALL.length ? ALL[ordinal] : ATTACK;
        }

        public String key() {
            return "combatupdate.army.stance." + this.name().toLowerCase(java.util.Locale.ROOT);
        }
    }

    // Every soldier's walking pace before its role scales it; a player sprints at about 0.28.
    private static final double BASE_SPEED = 0.31;
    private static final GipfaeliWeapon.Body UNARMED = new GipfaeliWeapon.Body(20.0, 4.0, 1.0, 0.0);

    // How long a soldier that has lost sight of its mark keeps hunting before it gives up and falls
    // back in with the commander.
    private static final int GIVE_UP_TICKS = 200;

    // Ordered about, a soldier will walk away from its commander; this is how far it strays looking
    // for one step of the way there before the path is worked out again.
    private static final int MARCH_STEP = 12;

    private static final int REPATH_INTERVAL_TICKS = 10;
    private static final int MELEE_COOLDOWN_TICKS = 20;

    // How far past its post's radius a guard will go after something before it turns back, and
    // how far from the post it may drift on its beat before it walks in again.
    private static final double LEASH_SLACK = 6.0;
    private static final double BEAT_SLACK = 2.0;

    // How far off its spot a soldier can be before it walks back to it, and how far off before it
    // hurries. Wider than a step, so a squad that has arrived stands still rather than shuffling.
    private static final double SLOT_SLACK = 1.2;
    private static final double SLOT_HURRY = 6.0;
    private static final int SLOT_REPATH_TICKS = 5;

    // The banner's reach and its beat: every two seconds it renews a buff that lasts three, so the
    // squad never sees it lapse and the server never sees it stack.
    private static final double RALLY_RADIUS = 10.0;
    private static final int RALLY_INTERVAL_TICKS = 40;
    private static final int RALLY_DURATION_TICKS = 70;

    // Field dressing: a soldier with nothing to shoot at patches itself up, slowly.
    private static final int REGEN_INTERVAL_TICKS = 60;
    private static final float REGEN_AMOUNT = 1.0F;

    // What the commander last pointed this one at, held as a UUID so it survives being written out
    // and read back. It outlives the mob's own target: a soldier that loses sight of its mark still
    // knows what it was sent after.
    private @Nullable UUID orderedTargetId;

    // Its place in the squad's shape: which formation, and which number in it out of how many.
    // Handed out by the army whenever the shape or the squad changes (see GipfaeliArmy#reform).
    private GipfaeliFormation formation = GipfaeliFormation.LOOSE;
    private int slot;
    private int slots = 1;

    // On parade, which block (which squad) this one stands in, out of how many are standing.
    private int block;
    private int blocks = 1;

    // Where the parade is drawn up around, when it is drawn up somewhere rather than around the
    // commander: the spot and the facing at the moment they said "stand". Null means the shape
    // walks with the commander instead.
    private @Nullable Vec3 paradeAnchor;
    private float paradeYaw;

    // Somewhere it has been sent to stand - a chunk off the territory map, as a rule - and is
    // still on its way to. Cleared on arrival, where holding position takes over.
    private @Nullable Vec3 station;

    // The guard post it is stationed at (see GipfaeliGuardPost), if any. The post block is what
    // says how wide its watch is and how it treats what comes near; the soldier only remembers
    // where it is, and lets go of it the moment the block is gone.
    private @Nullable BlockPos post;

    // Told to stay put. Not vanilla's sitting flag on purpose: a sitting mob is refused the right
    // to move by the goal that sits it, and a soldier that cannot move cannot run its gunfight
    // goal either - it would stand there being shot at. Holding here means only "do not walk
    // after the commander"; shooting is unaffected, and so is standing its ground when hit.
    private boolean holding;

    private int idleTicks;

    public GipfaeliSoldier(EntityType<? extends GipfaeliSoldier> type, Level level) {
        super(type, level);
        // Recruited, not spawned. Nothing about a soldier should be cleaned up because the
        // commander walked far enough away from it.
        this.setPersistenceRequired();
        this.setDropChance(EquipmentSlot.MAINHAND, 0.0F);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return TamableAnimal.createMobAttributes()
                .add(Attributes.MAX_HEALTH, UNARMED.health())
                .add(Attributes.MOVEMENT_SPEED, BASE_SPEED)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.ARMOR, UNARMED.armor())
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.0)
                // What a soldier notices on its own, and the box it sweeps on a timer to do it.
                // Kept to a zombie's reach rather than a marksman's, because eight soldiers each
                // sweeping the ground a rifle can cover is the expensive way to stand guard. An
                // order reaches far past this; see OrderedTargetGoal.
                .add(Attributes.FOLLOW_RANGE, 32.0)
                .add(Attributes.STEP_HEIGHT, 1.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder entityData) {
        super.defineSynchedData(entityData);
        entityData.define(DATA_UNIFORM, CAMO);
        entityData.define(DATA_STANCE, (byte) Stance.ATTACK.ordinal());
        entityData.define(DATA_COMMANDER, false);
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new FloatGoal(this));
        this.goalSelector.addGoal(2, new GunfightGoal(this));
        // Unarmed - the gun taken off it, or handed back - or carrying the banner, a soldier is
        // still a soldier. The gunfight goal holds the movement flags whenever there is a gun, so
        // this only ever runs without one; and never while holding, because a marcher that chases
        // something off the chunk it was sent to hold is not holding it.
        this.goalSelector.addGoal(3, new MeleeAttackGoal(this, 1.2, true) {
            @Override
            public boolean canUse() {
                return !GipfaeliSoldier.this.holding && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return !GipfaeliSoldier.this.holding && super.canContinueToUse();
            }
        });
        // On its way somewhere it was sent. Above the two follow goals, so a soldier marching on a
        // chunk is not pulled back to heel - or teleported there - by the wolf in it.
        this.goalSelector.addGoal(4, new StationGoal(this));
        // Walking its beat around a guard post, once it has got there.
        this.goalSelector.addGoal(5, new GuardGoal(this));
        // Told to hold: takes the right to walk away from everything below it, and nothing else.
        // The fighting goals above it take that right back whenever there is something to shoot.
        this.goalSelector.addGoal(6, new HoldGoal(this));
        // Its spot in the squad's shape, when the squad has one; otherwise a wolf's plain heel.
        this.goalSelector.addGoal(7, new FormationGoal(this));
        this.goalSelector.addGoal(8, new FollowOwnerGoal(this, 1.15, 8.0F, 3.0F));
        // A soldier in a shape, at a post, held, or on parade has somewhere to be; only one with
        // none of those wanders off to look at the flowers.
        this.goalSelector.addGoal(9, new WaterAvoidingRandomStrollGoal(this, 0.8) {
            @Override
            public boolean canUse() {
                return GipfaeliSoldier.this.idle() && super.canUse();
            }
        });
        // On parade, eyes front; otherwise the ordinary head-turning every mob does.
        this.goalSelector.addGoal(10, new AttentionGoal(this));
        this.goalSelector.addGoal(11, new LookAtPlayerGoal(this, Player.class, 10.0F));
        this.goalSelector.addGoal(12, new RandomLookAroundGoal(this));

        // First, above everything reactive: an order is an order, and it outranks whatever the
        // soldier would have picked for itself.
        this.targetSelector.addGoal(1, new OrderedTargetGoal(this));
        this.targetSelector.addGoal(2, new OwnerHurtByTargetGoal(this));
        this.targetSelector.addGoal(3, new OwnerHurtTargetGoal(this));
        this.targetSelector.addGoal(4, new HurtByTargetGoal(this));
        // Standing guard. Off by config for anyone who wants a squad that only ever shoots what it
        // was told to; the predicate is read per candidate, so the switch takes effect at once.
        this.targetSelector.addGoal(5, new NearestAttackableTargetGoal<>(this, Monster.class, true,
                (target, level) -> Config.ARMY_GUARDS.get()
                        && this.stance() == Stance.ATTACK
                        && !GipfaeliArmy.sameSide(this, target)
                        && this.guardMode() != GipfaeliGuardPost.Mode.PASSIVE));
        // A post kept aggressively: strangers within its watch are targets too. Never the owner's
        // side, and never anyone on the owner's scoreboard team, so a fortress can have guests.
        this.targetSelector.addGoal(6, new NearestAttackableTargetGoal<>(this, Player.class, true,
                (target, level) -> this.stance() == Stance.ATTACK
                        && this.guardMode() == GipfaeliGuardPost.Mode.AGGRESSIVE
                        && this.withinWatch(target)
                        && !GipfaeliArmy.sameSide(this, target)
                        && !this.guest(target)));
    }

    // --- What it is ---

    // The kit in its hand, which is the only record of its role: hand it a different one and it is
    // a different kind of soldier from the next tick on.
    public @Nullable GipfaeliWeapon weapon() {
        return GipfaeliWeapon.of(this.getMainHandItem());
    }

    // Hands the soldier its kit, and with it the frame that goes with the role: a Panzer soldier is
    // built to carry a Panzer soldier's gun. Health is capped to the new maximum rather than reset,
    // so swapping guns mid-fight is not a free heal.
    public void arm(ItemStack weapon) {
        this.setItemSlot(EquipmentSlot.MAINHAND, weapon.copyWithCount(1));

        GipfaeliWeapon kit = this.weapon();
        GipfaeliWeapon.Body body = kit == null ? UNARMED : kit.body();
        double toughness = this.commander() ? COMMANDER_TOUGHNESS : 1.0;
        this.setBase(Attributes.MAX_HEALTH, body.health() * toughness * Config.ARMY_HEALTH_MULTIPLIER.getAsDouble());
        this.setBase(Attributes.ARMOR, body.armor() * toughness * Config.ARMY_ARMOR_MULTIPLIER.getAsDouble());
        this.setBase(Attributes.MOVEMENT_SPEED, BASE_SPEED * body.speed());
        this.setBase(Attributes.KNOCKBACK_RESISTANCE, body.knockbackResistance());

        if (this.getHealth() > this.getMaxHealth()) {
            this.setHealth(this.getMaxHealth());
        }
    }

    private void setBase(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute, double value) {
        AttributeInstance instance = this.getAttribute(attribute);
        if (instance != null) {
            instance.setBaseValue(value);
        }
    }

    // The dye the uniform is in, or null for the camouflage every recruit starts in.
    public @Nullable DyeColor uniform() {
        int id = this.entityData.get(DATA_UNIFORM);
        return id == CAMO ? null : DyeColor.byId(id);
    }

    public void setUniform(@Nullable DyeColor color) {
        this.entityData.set(DATA_UNIFORM, color == null ? CAMO : color.getId());
        // The armour changes with the coat: whatever it is wearing is re-liveried in the new colour.
        if (!this.level().isClientSide()) {
            for (EquipmentSlot slot : ch.bbcag.combatupdate.GipfaeliArmour.SLOTS) {
                ItemStack piece = this.getItemBySlot(slot);
                if (!piece.isEmpty()) {
                    this.setItemSlot(slot, this.livery(piece.copy()));
                }
            }
        }
    }

    // Puts the squad's colour on a piece of armour: dye, on anything that takes it; a trim in the
    // nearest material otherwise, so an iron squad in red and one in blue can be told apart at a
    // glance across a field. Camouflage takes the trim off and dyes leather field-green.
    private ItemStack livery(ItemStack piece) {
        DyeColor colour = this.uniform();
        // Leather is the one vanilla armour that takes a dye; what takes a dye is a data tag now,
        // and the suit is the plainer thing to ask.
        if (ch.bbcag.combatupdate.GipfaeliArmour.of(piece.getItem()) == ch.bbcag.combatupdate.GipfaeliArmour.LEATHER) {
            piece.set(DataComponents.DYED_COLOR, new net.minecraft.world.item.component.DyedItemColor(
                    colour == null ? CAMO_LEATHER : colour.getTextureDiffuseColor()));
            return piece;
        }

        if (piece.get(DataComponents.EQUIPPABLE) == null) {
            return piece;
        }

        if (colour == null) {
            piece.remove(DataComponents.TRIM);
            return piece;
        }

        var materials = this.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.TRIM_MATERIAL);
        var patterns = this.level().registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.TRIM_PATTERN);
        var material = materials.get(trimFor(colour)).orElse(null);
        var pattern = patterns.get(net.minecraft.world.item.equipment.trim.TrimPatterns.SENTRY).orElse(null);
        if (material != null && pattern != null) {
            piece.set(DataComponents.TRIM, new net.minecraft.world.item.equipment.trim.ArmorTrim(material, pattern));
        }

        return piece;
    }

    private static final int CAMO_LEATHER = 0x5E6A44;

    // The trim material nearest each dye, since a trim only comes in the colours the materials do.
    private static net.minecraft.resources.ResourceKey<net.minecraft.world.item.equipment.trim.TrimMaterial> trimFor(DyeColor colour) {
        return switch (colour) {
            case RED -> net.minecraft.world.item.equipment.trim.TrimMaterials.REDSTONE;
            case BLUE -> net.minecraft.world.item.equipment.trim.TrimMaterials.LAPIS;
            case LIGHT_BLUE, CYAN -> net.minecraft.world.item.equipment.trim.TrimMaterials.DIAMOND;
            case GREEN, LIME -> net.minecraft.world.item.equipment.trim.TrimMaterials.EMERALD;
            case YELLOW -> net.minecraft.world.item.equipment.trim.TrimMaterials.GOLD;
            case ORANGE, BROWN -> net.minecraft.world.item.equipment.trim.TrimMaterials.COPPER;
            case PURPLE, MAGENTA, PINK -> net.minecraft.world.item.equipment.trim.TrimMaterials.AMETHYST;
            case WHITE, LIGHT_GRAY -> net.minecraft.world.item.equipment.trim.TrimMaterials.QUARTZ;
            case GRAY -> net.minecraft.world.item.equipment.trim.TrimMaterials.IRON;
            case BLACK -> net.minecraft.world.item.equipment.trim.TrimMaterials.NETHERITE;
        };
    }

    public boolean commander() {
        return this.entityData.get(DATA_COMMANDER);
    }

    // Makes or unmakes a commander. The frame is rebuilt from the kit, because a commander stands
    // in a sturdier one; which squad it commands is simply its colour.
    public void setCommander(boolean commander) {
        this.entityData.set(DATA_COMMANDER, commander);
        this.arm(this.getMainHandItem());
    }

    public Stance stance() {
        return Stance.byOrdinal(this.entityData.get(DATA_STANCE));
    }

    public void setStance(Stance stance) {
        this.entityData.set(DATA_STANCE, (byte) stance.ordinal());
    }

    // Draws the parade up around a fixed spot and facing, or - with null - around the commander,
    // wherever they go.
    public void standAt(@Nullable Vec3 anchor, float yaw) {
        this.paradeAnchor = anchor;
        this.paradeYaw = yaw;
    }

    // Whether the parade is drawn up somewhere in particular rather than around the commander.
    public boolean paradeFixed() {
        return this.paradeAnchor != null;
    }

    // Nothing to do and nowhere to be: the only state a soldier wanders in.
    private boolean idle() {
        return !this.holding && this.station == null && this.post == null
                && this.formation == GipfaeliFormation.LOOSE && this.stance() == Stance.ATTACK;
    }

    // A soldier is named by what it carries: a rifleman, a marksman, a Panzer soldier - or, with
    // nothing in its hands yet, just a soldier. This is what the target lists, the death messages
    // and the nameplate all show.
    @Override
    protected Component getTypeName() {
        if (this.commander()) {
            return Component.translatable("combatupdate.army.soldier.commander");
        }

        GipfaeliWeapon kit = this.weapon();
        return kit == null ? super.getTypeName() : Component.translatable("combatupdate.army.soldier.named", kit.roleName());
    }

    // Puts a piece of armour on, and hands back whatever was in that slot before.
    public ItemStack equip(EquipmentSlot slot, ItemStack piece) {
        ItemStack previous = this.getItemBySlot(slot).copy();
        this.setItemSlot(slot, piece.isEmpty() ? ItemStack.EMPTY : this.livery(piece.copyWithCount(1)));
        return previous;
    }

    // --- What it has been told ---

    // Points this soldier at something, or lets it stand down when handed null.
    public void order(@Nullable LivingEntity target) {
        this.orderedTargetId = target == null ? null : target.getUUID();
        if (target == null && this.getTarget() != null) {
            this.setTarget(null);
        }
    }

    // What it was last pointed at, if that is still alive and still in this world. Deliberately not
    // range-checked: something that has run out of reach has not stopped being the order, and a
    // soldier that walks back into range takes it up again where it left off.
    public @Nullable LivingEntity orderedTarget() {
        if (this.orderedTargetId == null || !(this.level() instanceof ServerLevel level)) {
            return null;
        }

        return level.getEntity(this.orderedTargetId) instanceof LivingEntity target && target.isAlive()
                ? target
                : null;
    }

    public boolean holdingPosition() {
        return this.holding;
    }

    // Holding position and following are the same switch seen from either side. A held soldier
    // still shoots everything in reach and still hits back; what it gives up is walking anywhere.
    public void holdPosition(boolean hold) {
        this.holding = hold;
        if (hold) {
            this.getNavigation().stop();
        }
    }

    // Sends the soldier to stand somewhere, or lets it off with null. It walks there fighting
    // whatever gets in the way, and holds the spot once it arrives.
    public void station(@Nullable Vec3 post) {
        this.station = post;
        if (post != null) {
            // Held soldiers do not walk; one that was holding somewhere else has to be let go of
            // before it can be sent anywhere.
            this.holdPosition(false);
        }
    }

    public boolean marching() {
        return this.station != null;
    }

    // Stations the soldier at a guard post, or takes it off one with null. Getting there is the
    // station's job (see station()); what to do once there is the guard goal's.
    public void guard(@Nullable BlockPos post) {
        this.post = post;
    }

    public @Nullable BlockPos post() {
        return this.post;
    }

    // The post block itself, or null if the soldier has none or the block is no longer there.
    public GipfaeliGuardPost.@Nullable Post guardPost() {
        if (this.post == null) {
            return null;
        }

        return this.level().getBlockEntity(this.post) instanceof GipfaeliGuardPost.Post here ? here : null;
    }

    private GipfaeliGuardPost.@Nullable Mode guardMode() {
        GipfaeliGuardPost.Post here = this.guardPost();
        return here == null ? null : here.mode();
    }

    // Whether something is inside the post's watch: a little beyond the patrol radius, so a guard
    // at the edge of its beat still notices what is walking up to it.
    private boolean withinWatch(LivingEntity target) {
        GipfaeliGuardPost.Post here = this.guardPost();
        if (here == null || this.post == null) {
            return false;
        }

        double watch = here.radius() * 2.0 + 4.0;
        return target.distanceToSqr(Vec3.atCenterOf(this.post)) <= watch * watch;
    }

    private boolean guest(LivingEntity target) {
        LivingEntity owner = this.getOwner();
        return owner != null && target.getTeam() != null && owner.isAlliedTo(target.getTeam());
    }

    public GipfaeliFormation formation() {
        return this.formation;
    }

    public void formUp(GipfaeliFormation formation, int slot, int slots) {
        this.formUp(formation, slot, slots, 0, 1);
    }

    // Its number in the shape - and, on parade, which block that shape is and how many there are.
    public void formUp(GipfaeliFormation formation, int slot, int slots, int block, int blocks) {
        this.formation = formation;
        this.slot = slot;
        this.slots = Math.max(1, slots);
        this.block = block;
        this.blocks = Math.max(1, blocks);
    }

    // Where this soldier belongs in the squad's shape right now, or null if the squad has no shape
    // or nobody to shape itself around.
    private @Nullable Vec3 formationSpot() {
        Vec3 anchor = this.paradeAnchor;
        float yaw = this.paradeYaw;
        if (anchor == null) {
            LivingEntity owner = this.getOwner();
            if (owner == null) {
                return null;
            }

            anchor = owner.position();
            yaw = owner.getYRot();
        }

        return this.formation == GipfaeliFormation.PARADE
                ? GipfaeliFormation.parade(this.block, this.blocks, this.slot, anchor, yaw)
                : this.formation.slot(this.slot, this.slots, anchor, yaw);
    }

    // Which way the shape faces: the anchor's facing on a fixed parade, the commander's otherwise.
    private @Nullable Vec3 formationFront() {
        if (this.paradeAnchor != null) {
            return Vec3.directionFromRotation(0.0F, this.paradeYaw);
        }

        LivingEntity owner = this.getOwner();
        return owner == null ? null : Vec3.directionFromRotation(0.0F, owner.getYRot());
    }

    // --- What it does on its own ---

    @Override
    protected void customServerAiStep(ServerLevel level) {
        super.customServerAiStep(level);

        if (this.getTarget() == null) {
            this.idleTicks++;
        } else {
            this.idleTicks = 0;
        }

        // Out of the fight for a while, a soldier looks after its own scrapes.
        if (this.idleTicks > 0 && this.idleTicks % REGEN_INTERVAL_TICKS == 0 && this.getHealth() < this.getMaxHealth()) {
            this.heal(REGEN_AMOUNT);
        }

        if (this.tickCount % RALLY_INTERVAL_TICKS == 0 && (this.weapon() == GipfaeliWeapon.MARCHER || this.commander())) {
            this.rally(level);
        }

        if (this.post != null && this.tickCount % 20 == 0 && this.guardPost() == null) {
            this.post = null;
            this.station = null;
            this.holdPosition(true);
        }
    }

    // The banner's whole job. Everyone under the same flag within reach of it - the squad and the
    // commander alike - moves quicker, hits harder and takes less, for as long as the marcher is
    // still standing among them. That is what makes one soldier without a gun worth the slot.
    private void rally(ServerLevel level) {
        if (!Config.ARMY_BANNER_RALLY.get()) {
            return;
        }

        List<LivingEntity> allies = level.getEntitiesOfClass(LivingEntity.class,
                this.getBoundingBox().inflate(RALLY_RADIUS),
                ally -> ally != this && ally.isAlive() && GipfaeliArmy.sameSide(this, ally));

        for (LivingEntity ally : allies) {
            ally.addEffect(new MobEffectInstance(MobEffects.SPEED, RALLY_DURATION_TICKS, 0, true, false, false));
            ally.addEffect(new MobEffectInstance(MobEffects.STRENGTH, RALLY_DURATION_TICKS, 0, true, false, false));
            ally.addEffect(new MobEffectInstance(MobEffects.RESISTANCE, RALLY_DURATION_TICKS, 0, true, false, false));
        }
    }

    @Override
    public void performRangedAttack(LivingEntity target, float power) {
        GipfaeliWeapon weapon = this.weapon();
        if (weapon == null || !(this.level() instanceof ServerLevel level)) {
            return;
        }

        // Led rather than aimed: the round is sent to where the target will be when it arrives,
        // which is the difference between a squad that suppresses and one that hits.
        Vec3 muzzle = this.getEyePosition();
        Vec3 direction = GipfaeliWeapon.lead(muzzle, target, weapon.projectileSpeed());
        if (weapon.fire(level, this, direction, target)) {
            this.swingForAttack(InteractionHand.MAIN_HAND);
        }
    }

    // Right-clicking one of your own: a gun in your hand swaps its kit over, a dye recolours its
    // uniform, a Gipfaeli feeds it, and an empty hand tells it to hold or to fall back in.
    @Override
    public InteractionResult mobInteract(Player player, InteractionHand hand) {
        if (!this.isOwnedBy(player)) {
            return super.mobInteract(player, hand);
        }

        ItemStack held = player.getItemInHand(hand);
        DyeColor dye = dyeOf(held);
        EquipmentSlot armourSlot = armourSlotOf(held);
        if (this.level().isClientSide()) {
            boolean handled = GipfaeliWeapon.of(held) != null
                    || held.isEmpty()
                    || dye != null
                    || armourSlot != null
                    || held.is(CombatUpdate.GIPFAELI.get());
            return handled ? InteractionResult.SUCCESS : super.mobInteract(player, hand);
        }

        // A piece of armour held out goes straight on, and whatever it replaces comes back.
        if (armourSlot != null) {
            ItemStack previous = this.equip(armourSlot, held);
            held.shrink(1);
            if (!previous.isEmpty()) {
                player.getInventory().placeItemBackInInventory(previous, Prediction.SERVER_ONLY);
            }

            this.playSound(SoundEvents.ARMOR_EQUIP_IRON.value(), 1.0F, 1.0F);
            return InteractionResult.SUCCESS;
        }

        GipfaeliWeapon offered = GipfaeliWeapon.of(held);
        if (offered != null) {
            ItemStack previous = this.getMainHandItem().copy();
            this.arm(held);
            held.shrink(1);
            if (!previous.isEmpty()) {
                player.getInventory().placeItemBackInInventory(previous, Prediction.SERVER_ONLY);
            }

            this.playSound(SoundEvents.ARMOR_EQUIP_IRON.value(), 1.0F, 1.0F);
            GipfaeliArmy.readout(player, Component.translatable("combatupdate.army.rearmed", this.getMainHandItem().getHoverName()));
            return InteractionResult.SUCCESS;
        }

        if (dye != null) {
            this.setUniform(dye);
            held.consume(1, player);
            this.playSound(SoundEvents.DYE_USE, 1.0F, 1.0F);
            return InteractionResult.SUCCESS;
        }

        // Rations. A Gipfaeli is what the squad runs on, so it is also what patches one up.
        if (held.is(CombatUpdate.GIPFAELI.get()) && this.getHealth() < this.getMaxHealth()) {
            this.heal(4.0F);
            held.consume(1, player);
            this.playSound(SoundEvents.GENERIC_EAT.value(), 1.0F, 1.0F);
            return InteractionResult.SUCCESS;
        }

        // An empty hand opens the menu: the squad's, on a commander; the soldier's own otherwise.
        if (held.isEmpty() && player instanceof net.minecraft.server.level.ServerPlayer commander) {
            GipfaeliArmy.click(commander, this);
            return InteractionResult.SUCCESS;
        }

        return super.mobInteract(player, hand);
    }

    // A dye carries its colour as a component now rather than on the item, so any stack that has
    // one counts - a dye, or anything else somebody has managed to put a colour on.
    private static @Nullable DyeColor dyeOf(ItemStack stack) {
        return stack.getItem() instanceof net.minecraft.world.item.DyeItem ? stack.get(DataComponents.DYE) : null;
    }

    // The armour slot a held item is meant for, or null for anything that is not body armour.
    private static @Nullable EquipmentSlot armourSlotOf(ItemStack stack) {
        net.minecraft.world.item.equipment.Equippable equippable = stack.get(DataComponents.EQUIPPABLE);
        return equippable != null && equippable.slot().getType() == EquipmentSlot.Type.HUMANOID_ARMOR ? equippable.slot() : null;
    }

    // A soldier carries its kit out of the world with it unless something kills it, in which case
    // whatever is left of the kit is there on the ground to be picked up. Dismissing one hands it
    // back properly (see GipfaeliArmy), so this is only ever the death case.
    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean killedByPlayer) {
        super.dropCustomDeathLoot(level, source, killedByPlayer);
        ItemStack weapon = this.getMainHandItem();
        if (!weapon.isEmpty() && Config.ARMY_CONSUMES_SUPPLIES.get()) {
            this.spawnAtLocation(level, weapon.copy());
        }

        this.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);

        // And the armour it was dressed in, for the same reason: somebody paid for it.
        for (EquipmentSlot slot : ch.bbcag.combatupdate.GipfaeliArmour.SLOTS) {
            ItemStack piece = this.getItemBySlot(slot);
            if (!piece.isEmpty() && Config.ARMY_CONSUMES_SUPPLIES.get()) {
                this.spawnAtLocation(level, piece.copy());
            }

            this.setItemSlot(slot, ItemStack.EMPTY);
        }
    }

    // Never on its own side, whatever it was told. Without this an order aimed at a crowd takes the
    // squad's own soldiers with it, and OwnerHurtTargetGoal turns the squad on its commander the
    // first time they hit one of their own by accident.
    @Override
    public boolean canAttack(LivingEntity target) {
        return !GipfaeliArmy.sameSide(this, target) && super.canAttack(target);
    }

    @Override
    public boolean wantsToAttack(LivingEntity target, LivingEntity owner) {
        return !GipfaeliArmy.sameSide(this, target);
    }

    // A soldier is shaped like a person and takes a hit like one, so it says so. There is no
    // ambient sound on purpose: eight of anything muttering to itself behind you is a lot.
    @Override
    protected @Nullable SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.PLAYER_HURT;
    }

    @Override
    protected @Nullable SoundEvent getDeathSound() {
        return SoundEvents.PLAYER_DEATH;
    }

    @Override
    public boolean isFood(ItemStack itemStack) {
        // Gipfaeli are rations, handled in mobInteract above. Saying yes here would put the squad
        // in the mood to breed instead, which is not what an army is for.
        return false;
    }

    @Override
    public @Nullable AgeableMob getBreedOffspring(ServerLevel level, AgeableMob partner) {
        return null;
    }

    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);
        if (this.orderedTargetId != null) {
            output.store("OrderedTarget", UUIDUtil.CODEC, this.orderedTargetId);
        }

        if (this.station != null) {
            output.store("Station", Vec3.CODEC, this.station);
        }

        if (this.post != null) {
            output.store("Post", BlockPos.CODEC, this.post);
        }

        DyeColor uniform = this.uniform();
        output.putString("Uniform", uniform == null ? "camo" : uniform.getName());
        output.putInt("Stance", this.stance().ordinal());
        if (this.paradeAnchor != null) {
            output.store("ParadeAnchor", Vec3.CODEC, this.paradeAnchor);
            output.putFloat("ParadeYaw", this.paradeYaw);
        }

        output.putBoolean("Holding", this.holding);
        output.putBoolean("Commander", this.commander());
        output.putInt("Formation", this.formation.ordinal());
        output.putInt("Slot", this.slot);
        output.putInt("Slots", this.slots);
        output.putInt("Block", this.block);
        output.putInt("Blocks", this.blocks);
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        this.orderedTargetId = input.read("OrderedTarget", UUIDUtil.CODEC).orElse(null);
        this.station = input.read("Station", Vec3.CODEC).orElse(null);
        this.post = input.read("Post", BlockPos.CODEC).orElse(null);
        this.setUniform(DyeColor.byName(input.getStringOr("Uniform", "camo"), null));
        this.setStance(Stance.byOrdinal(input.getIntOr("Stance", 0)));
        this.paradeAnchor = input.read("ParadeAnchor", Vec3.CODEC).orElse(null);
        this.paradeYaw = input.getFloatOr("ParadeYaw", 0.0F);
        this.holding = input.getBooleanOr("Holding", false);
        this.entityData.set(DATA_COMMANDER, input.getBooleanOr("Commander", false));
        this.formation = GipfaeliFormation.byOrdinal(input.getIntOr("Formation", 0));
        this.slot = input.getIntOr("Slot", 0);
        this.slots = Math.max(1, input.getIntOr("Slots", 1));
        this.block = input.getIntOr("Block", 0);
        this.blocks = Math.max(1, input.getIntOr("Blocks", 1));
    }

    // --- The goals that are its own ---

    // Takes the target the commander picked and hands it to the mob as its own, for as long as it
    // is alive and within reach. Written out rather than built on TargetGoal because that one drops
    // anything past the follow range attribute, and the whole point of an order is that it can be
    // called on something across the field.
    private static final class OrderedTargetGoal extends Goal {
        private final GipfaeliSoldier soldier;

        OrderedTargetGoal(GipfaeliSoldier soldier) {
            this.soldier = soldier;
            this.setFlags(EnumSet.of(Goal.Flag.TARGET));
        }

        @Override
        public boolean canUse() {
            LivingEntity ordered = this.soldier.orderedTarget();
            return ordered != null
                    && ordered.distanceTo(this.soldier) <= Config.ARMY_MARCH_RANGE.getAsDouble()
                    && this.soldier.canAttack(ordered);
        }

        @Override
        public boolean canContinueToUse() {
            return this.canUse();
        }

        @Override
        public void start() {
            this.soldier.setTarget(this.soldier.orderedTarget());
        }

        @Override
        public void tick() {
            LivingEntity ordered = this.soldier.orderedTarget();
            if (ordered != null && this.soldier.getTarget() != ordered) {
                this.soldier.setTarget(ordered);
            }
        }

        @Override
        public void stop() {
            // The order is over rather than merely unreachable when the thing it named is gone for
            // good; a target only out of range keeps its order and gets marched on again later.
            if (this.soldier.orderedTargetId != null && this.soldier.orderedTarget() == null) {
                this.soldier.order(null);
            }
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }
    }

    // Marching on a station: walking to wherever the soldier was sent, a dozen blocks at a time
    // when it is further than a path can be found for, and holding the spot on arrival. A fight
    // on the way takes over (the gunfight goal outranks this) and the march resumes after it.
    private static final class StationGoal extends Goal {
        // Close enough to count as there. Wider than a block, because eight soldiers were sent to
        // the same chunk and cannot all stand on the one spot.
        private static final double ARRIVED = 1.5;

        private final GipfaeliSoldier soldier;
        private int repathCooldown;

        StationGoal(GipfaeliSoldier soldier) {
            this.soldier = soldier;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return this.soldier.station != null && this.soldier.getTarget() == null;
        }

        @Override
        public boolean canContinueToUse() {
            return this.canUse();
        }

        @Override
        public void start() {
            this.repathCooldown = 0;
        }

        @Override
        public void stop() {
            this.soldier.getNavigation().stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            Vec3 post = this.soldier.station;
            if (post == null) {
                return;
            }

            double distance = this.soldier.position().distanceTo(post);
            if (distance <= ARRIVED) {
                // There. Holding is what keeps it there: it plants the soldier, and stops both
                // follow goals from walking it home. A guard is planted by its beat instead.
                this.soldier.station = null;
                if (this.soldier.post == null) {
                    this.soldier.holdPosition(true);
                }
                return;
            }

            this.soldier.getLookControl().setLookAt(post.x, this.soldier.getEyeY(), post.z);

            if (this.repathCooldown-- > 0) {
                return;
            }

            this.repathCooldown = REPATH_INTERVAL_TICKS;
            if (distance > this.soldier.getAttributeValue(Attributes.FOLLOW_RANGE)) {
                Vec3 step = this.soldier.position().add(post.subtract(this.soldier.position()).normalize().scale(MARCH_STEP));
                this.soldier.getNavigation().moveTo(step.x, step.y, step.z, 1.2);
            } else {
                this.soldier.getNavigation().moveTo(post.x, post.y, post.z, 1.2);
            }
        }
    }

    // Walking a beat: a guard with nothing to shoot wanders about inside its post's radius, stops
    // to look around, and walks back in when it finds itself outside. The look goals still run
    // under it, so a guard on its beat turns its head like anything else does.
    private static final class GuardGoal extends Goal {
        private final GipfaeliSoldier soldier;
        private int cooldown;

        GuardGoal(GipfaeliSoldier soldier) {
            this.soldier = soldier;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE));
        }

        @Override
        public boolean canUse() {
            return this.soldier.post != null && this.soldier.station == null && this.soldier.getTarget() == null;
        }

        @Override
        public boolean canContinueToUse() {
            return this.canUse();
        }

        @Override
        public void start() {
            this.cooldown = 0;
        }

        @Override
        public void stop() {
            this.soldier.getNavigation().stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            GipfaeliGuardPost.Post here = this.soldier.guardPost();
            BlockPos post = this.soldier.post;
            if (here == null || post == null) {
                return;
            }

            if (this.cooldown-- > 0) {
                return;
            }

            Vec3 centre = Vec3.atCenterOf(post);
            double radius = here.radius();
            if (this.soldier.position().distanceTo(centre) > radius + BEAT_SLACK) {
                this.cooldown = REPATH_INTERVAL_TICKS;
                this.soldier.getNavigation().moveTo(centre.x, centre.y, centre.z, 1.0);
                return;
            }

            // Half the time it stands where it is for a few seconds; the other half it picks a
            // spot inside the radius and strolls there. Guards that never stop look like they are
            // looking for something, and guards that never move look like furniture.
            this.cooldown = 60 + this.soldier.getRandom().nextInt(80);
            if (this.soldier.getRandom().nextBoolean()) {
                this.soldier.getNavigation().stop();
                return;
            }

            double angle = this.soldier.getRandom().nextDouble() * Math.PI * 2.0;
            double reach = this.soldier.getRandom().nextDouble() * radius;
            Vec3 spot = centre.add(Math.cos(angle) * reach, 0.0, Math.sin(angle) * reach);
            this.soldier.getNavigation().moveTo(spot.x, spot.y, spot.z, 0.8);
        }
    }

    // Eyes front. On parade a soldier looks the way the ranks face, and keeps looking there,
    // instead of turning its head after whoever walks past. Owns only the right to look.
    private static final class AttentionGoal extends Goal {
        private final GipfaeliSoldier soldier;

        AttentionGoal(GipfaeliSoldier soldier) {
            this.soldier = soldier;
            this.setFlags(EnumSet.of(Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            return this.soldier.stance() == Stance.STAND && this.soldier.getTarget() == null;
        }

        @Override
        public boolean canContinueToUse() {
            return this.canUse();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            Vec3 front = this.soldier.formationFront();
            if (front != null) {
                Vec3 ahead = this.soldier.position().add(front.scale(10.0));
                this.soldier.getLookControl().setLookAt(ahead.x, this.soldier.getEyeY(), ahead.z);
            }
        }
    }

    // Staying put. All this goal does is own the right to move while the soldier is held and has
    // nothing to shoot, so that nothing below it - the formation, the heel, the wander - walks it
    // off its spot. It never moves the soldier itself.
    private static final class HoldGoal extends Goal {
        private final GipfaeliSoldier soldier;

        HoldGoal(GipfaeliSoldier soldier) {
            this.soldier = soldier;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.JUMP));
        }

        @Override
        public boolean canUse() {
            return this.soldier.holding && this.soldier.station == null;
        }

        @Override
        public boolean canContinueToUse() {
            return this.canUse();
        }

        @Override
        public void start() {
            this.soldier.getNavigation().stop();
        }
    }

    // Standing where the squad's shape says to stand, and facing the way the commander faces. Runs
    // only while there is a shape to keep and nothing to shoot; a fight breaks formation, and the
    // squad re-forms around the commander once it is over.
    private static final class FormationGoal extends Goal {
        private final GipfaeliSoldier soldier;
        private int repathCooldown;

        FormationGoal(GipfaeliSoldier soldier) {
            this.soldier = soldier;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            if (this.soldier.formation == GipfaeliFormation.LOOSE
                    || this.soldier.holding
                    || this.soldier.getTarget() != null) {
                return false;
            }

            Vec3 post = this.soldier.formationSpot();
            return post != null && this.soldier.position().distanceTo(post) > SLOT_SLACK;
        }

        @Override
        public boolean canContinueToUse() {
            if (this.soldier.formation == GipfaeliFormation.LOOSE
                    || this.soldier.holding
                    || this.soldier.getTarget() != null) {
                return false;
            }

            Vec3 post = this.soldier.formationSpot();
            return post != null && this.soldier.position().distanceTo(post) > SLOT_SLACK * 0.5;
        }

        @Override
        public void start() {
            this.repathCooldown = 0;
        }

        @Override
        public void stop() {
            this.soldier.getNavigation().stop();
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity owner = this.soldier.getOwner();
            Vec3 post = this.soldier.formationSpot();
            if (owner == null || post == null) {
                return;
            }

            // Left far enough behind, a soldier does what a wolf does and simply appears at heel;
            // a formation that is a chunk behind its commander is not a formation. Not on a fixed
            // parade, though, which is drawn up somewhere on purpose.
            if (this.soldier.paradeAnchor == null && this.soldier.shouldTryTeleportToOwner()
                    && this.soldier.distanceTo(owner) > 24.0) {
                this.soldier.tryToTeleportToOwner();
                return;
            }

            // Look where the shape faces, so a line faces the same way as the person leading it
            // rather than each soldier staring at whoever is nearest.
            Vec3 front = this.soldier.formationFront();
            if (front != null) {
                Vec3 ahead = this.soldier.position().add(front.scale(10.0));
                this.soldier.getLookControl().setLookAt(ahead.x, this.soldier.getEyeY(), ahead.z);
            }

            if (this.repathCooldown-- > 0) {
                return;
            }

            this.repathCooldown = SLOT_REPATH_TICKS;
            double gap = this.soldier.position().distanceTo(post);
            this.soldier.getNavigation().moveTo(post.x, post.y, post.z, gap > SLOT_HURRY ? 1.25 : 1.0);
        }
    }

    // Fighting with a gun: close to where the weapon is worth firing, keep the target in view, and
    // pull the trigger whenever the weapon is ready. One goal covers every gun, because what
    // separates them is entirely in their numbers - how far they reach, how close their carrier
    // wants to be, and how long between shots.
    private static final class GunfightGoal extends Goal {
        private final GipfaeliSoldier soldier;

        private int triggerCooldown;
        private int meleeCooldown;
        private int repathCooldown;
        private int unseenTicks;

        GunfightGoal(GipfaeliSoldier soldier) {
            this.soldier = soldier;
            this.setFlags(EnumSet.of(Goal.Flag.MOVE, Goal.Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity target = this.soldier.getTarget();
            GipfaeliWeapon weapon = this.soldier.weapon();
            return target != null && target.isAlive() && weapon != null && weapon.fires();
        }

        @Override
        public boolean canContinueToUse() {
            return this.canUse() && this.unseenTicks < GIVE_UP_TICKS;
        }

        @Override
        public void start() {
            this.unseenTicks = 0;
            this.repathCooldown = 0;
        }

        @Override
        public void stop() {
            this.soldier.getNavigation().stop();
            this.soldier.setTarget(null);
            this.unseenTicks = 0;
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void tick() {
            LivingEntity target = this.soldier.getTarget();
            GipfaeliWeapon weapon = this.soldier.weapon();
            if (target == null || weapon == null) {
                return;
            }

            this.soldier.getLookControl().setLookAt(target, 60.0F, 60.0F);

            double distance = this.soldier.distanceTo(target);
            boolean inSight = this.soldier.getSensing().hasLineOfSight(target);
            this.unseenTicks = inSight ? 0 : this.unseenTicks + 1;

            this.move(target, weapon, distance, inSight);

            if (this.triggerCooldown > 0) {
                this.triggerCooldown--;
            } else if (inSight && distance <= weapon.range() && distance >= weapon.minimum()) {
                this.soldier.performRangedAttack(target, 1.0F);
                this.triggerCooldown = weapon.cooldownTicks();
            }

            // A gun is no use with something already on top of you, so at arm's length the soldier
            // stops shooting past it and uses the butt of the thing.
            if (this.meleeCooldown > 0) {
                this.meleeCooldown--;
            } else if (distance <= 2.0 && this.soldier.level() instanceof ServerLevel level) {
                this.soldier.swingForAttack(InteractionHand.MAIN_HAND);
                this.soldier.doHurtTarget(level, target);
                this.meleeCooldown = MELEE_COOLDOWN_TICKS;
            }
        }

        // Walking is worked out a few times a second rather than every tick: a path is expensive,
        // and a squad of eight recalculating one apiece every tick is the expensive part of having
        // a squad at all.
        private void move(LivingEntity target, GipfaeliWeapon weapon, double distance, boolean inSight) {
            if (this.repathCooldown > 0) {
                this.repathCooldown--;
                return;
            }

            this.repathCooldown = REPATH_INTERVAL_TICKS;

            // Held, a soldier fights from where it stands. It still turns and shoots; it does not
            // chase, and it does not back off.
            if (this.soldier.holdingPosition()) {
                return;
            }

            // A guard chases only as far as its post's watch, then turns back to it. Otherwise one
            // creeper walking past the wall would draw the whole garrison off it, which is exactly
            // the trick a fortress is supposed to be proof against.
            GipfaeliGuardPost.Post here = this.soldier.guardPost();
            if (here != null && this.soldier.post != null) {
                Vec3 centre = Vec3.atCenterOf(this.soldier.post);
                if (this.soldier.position().distanceTo(centre) > here.radius() + LEASH_SLACK) {
                    this.soldier.getNavigation().moveTo(centre.x, centre.y, centre.z, 1.1);
                    return;
                }
            }

            // Too far to path to in one go: head a dozen blocks towards it and work the rest out
            // when we get there. This is what lets an order be called on something right across the
            // map rather than only on what the soldier could already walk to.
            if (distance > this.soldier.getAttributeValue(Attributes.FOLLOW_RANGE)) {
                Vec3 towards = target.position().subtract(this.soldier.position()).normalize().scale(MARCH_STEP);
                Vec3 step = this.soldier.position().add(towards);
                this.soldier.getNavigation().moveTo(step.x, step.y, step.z, 1.2);
                return;
            }

            if (distance > weapon.standoff() || !inSight) {
                this.soldier.getNavigation().moveTo(target, 1.1);
                return;
            }

            // Well inside the weapon's own reach with a clear shot: back off to where it shoots
            // best rather than crowding a target that is about to hit back.
            if (distance < weapon.standoff() * 0.5) {
                Vec3 away = DefaultRandomPos.getPosAway(this.soldier, 10, 5, target.position());
                if (away != null) {
                    this.soldier.getNavigation().moveTo(away.x, away.y, away.z, 1.0);
                    return;
                }
            }

            this.soldier.getNavigation().stop();
        }
    }
}
