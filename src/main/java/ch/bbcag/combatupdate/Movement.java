package ch.bbcag.combatupdate;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

// Two ways to move faster on foot: the dash and the slide.
//
// The dash is a key (see client/MovementClient): a burst of a few blocks along whichever way the
// player is walking - or looking, if they are standing still - held flat for a handful of ticks, so
// it covers the same ground in the air as on it. Once on the ground, and as many times in the air
// as the config allows before landing again.
//
// The slide is sneak pressed while sprinting on the ground. The player drops flat, which is the
// swimming pose's hitbox - low enough to pass under a one-block gap - and keeps going along the way
// they were running, easing off until they are back to walking pace, sneak is let go, or they jump.
// A jump out of a slide keeps its speed.
//
// Both are moved on the client, for the reason ElytraBoost gives: a player's own movement is the
// client's to predict, and pushing it from the server only arrives as a correction. The server is
// told when a dash or slide starts and ends, so it can hold the same pose (without which a slide
// under a gap is a player standing inside a block, as far as the server can tell) and so everyone
// else nearby hears and sees it.
public final class Movement {
    private static final String VERSION = "1";

    // How many ticks a dash lasts - four at full speed and a last one to slow down in - and what it
    // leaves behind when it stops. Letting it run out on its own would carry a player in the air ten
    // times as far as on the ground, since air has next to no friction; this puts them back at a
    // sprint instead.
    private static final int DASH_TICKS = 5;
    private static final double DASH_EXIT_SPEED = 0.3;

    // A slide ends once it has slowed to this, which is a little over walking pace.
    private static final double SLIDE_END_SPEED = 0.2;
    // How strongly a slide bends towards where the player is looking, per tick: enough to follow a
    // gentle curve, not enough to turn a corner.
    private static final double SLIDE_STEER = 0.08;

    // How much hunger each costs, in vanilla's exhaustion units; a sprint-jump is 0.2.
    private static final float DASH_EXHAUSTION = 0.4F;
    private static final float SLIDE_EXHAUSTION = 0.2F;

    // Set by the client to hand packets to the server, since this class is loaded on a dedicated
    // server too and cannot name the client's network classes itself.
    public static @Nullable Consumer<CustomPacketPayload> toServer;

    // Per side, for the reason ElytraBoost gives, and keyed on the player object rather than its UUID
    // so a player who dies or changes dimension mid-slide starts afresh instead of inheriting it.
    // Each map is only ever touched from its own side's thread.
    private static final Map<Player, State> CLIENT = new WeakHashMap<>();
    private static final Map<Player, State> SERVER = new WeakHashMap<>();

    private static final class State {
        int dashTicks;
        Vec3 dashDirection = Vec3.ZERO;
        int dashCooldown;
        int airDashesUsed;
        // A dash the key asked for, waiting for the next player tick to start it.
        @Nullable Vec3 requestedDash;

        boolean sliding;
        int slideTicks;
        Vec3 slideDirection = Vec3.ZERO;
        double slideSpeed;
        double slideDecay;
        int slideCooldown;

        boolean wasShiftDown;
        boolean wasSprinting;
    }

    private Movement() {
    }

    // --- Client ---

    // Called by the dash key with the movement keys held at the time: strafe is left positive,
    // forward is forward positive, as vanilla's input reports them.
    public static void requestDash(Player player, float strafe, float forward) {
        Vec3 direction;
        if (strafe == 0.0F && forward == 0.0F) {
            direction = horizontal(player.getLookAngle());
        } else {
            float yaw = player.getYRot() * Mth.DEG_TO_RAD;
            float sin = Mth.sin(yaw);
            float cos = Mth.cos(yaw);
            direction = new Vec3(strafe * cos - forward * sin, 0.0, forward * cos + strafe * sin);
        }

        if (direction.lengthSqr() > 1.0E-6) {
            CLIENT.computeIfAbsent(player, p -> new State()).requestedDash = direction.normalize();
        }
    }

    // Once per player tick on both sides, before the player moves.
    public static void tick(Player player) {
        if (player.level().isClientSide()) {
            if (player.isLocalPlayer()) {
                tickClient(player);
            }
        } else if (player instanceof ServerPlayer serverPlayer) {
            tickServer(serverPlayer);
        }
    }

    private static void tickClient(Player player) {
        State state = CLIENT.computeIfAbsent(player, p -> new State());
        if (state.dashCooldown > 0) {
            state.dashCooldown--;
        }
        if (state.slideCooldown > 0) {
            state.slideCooldown--;
        }
        if (player.onGround()) {
            state.airDashesUsed = 0;
        }

        boolean shiftDown = player.isShiftKeyDown();
        boolean sprinting = player.isSprinting();
        boolean pressedSneak = shiftDown && !state.wasShiftDown;
        // Sprinting as of the tick before sneak went down: vanilla drops the sprint in the same tick
        // the player crouches, so by now it would already read as off.
        boolean wasSprinting = state.wasSprinting;
        state.wasShiftDown = shiftDown;
        state.wasSprinting = sprinting;

        Vec3 requested = state.requestedDash;
        state.requestedDash = null;
        if (requested != null && canDash(player, state)) {
            if (state.sliding) {
                endSlide(player, state);
            }

            state.dashTicks = DASH_TICKS;
            state.dashDirection = requested;
            state.dashCooldown = Config.DASH_COOLDOWN_TICKS.getAsInt();
            if (!player.onGround()) {
                state.airDashesUsed++;
            }
            send(new DashPayload());
        }

        if (state.dashTicks > 0) {
            state.dashTicks--;
            double speed = state.dashTicks > 0 ? Config.DASH_SPEED.getAsDouble() : DASH_EXIT_SPEED;
            player.setDeltaMovement(state.dashDirection.scale(speed));
            player.resetFallDistance();
            return;
        }

        if (!state.sliding && pressedSneak && wasSprinting && canSlide(player, state)) {
            Vec3 motion = horizontal(player.getDeltaMovement());
            double start = Math.max(Config.SLIDE_SPEED.getAsDouble(), motion.length());
            state.sliding = true;
            state.slideTicks = 0;
            state.slideDirection = motion.lengthSqr() > 1.0E-4 ? motion.normalize() : horizontal(player.getLookAngle());
            state.slideSpeed = start;
            // Eases from the starting speed down to walking pace over the configured duration.
            state.slideDecay = Math.pow(SLIDE_END_SPEED / start, 1.0 / Config.SLIDE_DURATION_TICKS.getAsInt());
            player.setForcedPose(Pose.SWIMMING);
            send(new SlidePayload(true));
        }

        if (state.sliding) {
            // Off the ground means a jump or a ledge, and either way the slide has done its job:
            // the speed it had is left on the player for the air.
            if (!Config.on(Config.ENABLE_SLIDE) || !player.isShiftKeyDown() || !player.onGround()
                    || state.slideSpeed < SLIDE_END_SPEED || player.isInWater() || player.getAbilities().flying) {
                endSlide(player, state);
                return;
            }

            state.slideTicks++;
            Vec3 look = horizontal(player.getLookAngle());
            if (look.lengthSqr() > 1.0E-4) {
                state.slideDirection = state.slideDirection.lerp(look.normalize(), SLIDE_STEER).normalize();
            }
            Vec3 motion = player.getDeltaMovement();
            Vec3 slide = state.slideDirection.scale(state.slideSpeed);
            player.setDeltaMovement(slide.x, motion.y, slide.z);
            state.slideSpeed *= state.slideDecay;
        }
    }

    private static boolean canDash(Player player, State state) {
        return Config.on(Config.ENABLE_DASH)
                && state.dashCooldown == 0
                && state.dashTicks == 0
                && (player.onGround() || state.airDashesUsed < Config.AIR_DASHES.getAsInt())
                && canMoveOnFoot(player);
    }

    private static boolean canSlide(Player player, State state) {
        return Config.on(Config.ENABLE_SLIDE)
                && state.slideCooldown == 0
                && player.onGround()
                && !player.isInWater()
                && canMoveOnFoot(player);
    }

    private static boolean canMoveOnFoot(Player player) {
        return !player.isSpectator() && !player.getAbilities().flying && !player.isFallFlying()
                && !player.isPassenger() && !player.isSwimming() && !player.onClimbable();
    }

    private static void endSlide(Player player, State state) {
        state.sliding = false;
        state.slideCooldown = Config.SLIDE_COOLDOWN_TICKS.getAsInt();
        player.setForcedPose(null);
        send(new SlidePayload(false));
    }

    private static void send(CustomPacketPayload payload) {
        if (toServer != null) {
            toServer.accept(payload);
        }
    }

    private static Vec3 horizontal(Vec3 vector) {
        return new Vec3(vector.x, 0.0, vector.z);
    }

    // --- Server ---

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(VERSION);
        registrar.playToServer(DashPayload.TYPE, DashPayload.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                onDash(player);
            }
        });
        registrar.playToServer(SlidePayload.TYPE, SlidePayload.CODEC, (payload, context) -> {
            if (context.player() instanceof ServerPlayer player) {
                onSlide(player, payload.start());
            }
        });
    }

    // The client has already moved; what is left is the part everyone else sees and hears. A dash
    // that arrives well before its cooldown could have run out is a client that is not playing by
    // the config, and gets no show.
    private static void onDash(ServerPlayer player) {
        State state = SERVER.computeIfAbsent(player, p -> new State());
        if (!Config.on(Config.ENABLE_DASH) || state.dashCooldown > Config.DASH_COOLDOWN_TICKS.getAsInt() / 2) {
            return;
        }

        state.dashCooldown = Config.DASH_COOLDOWN_TICKS.getAsInt();
        state.dashTicks = DASH_TICKS;
        player.causeFoodExhaustion(DASH_EXHAUSTION);
        player.resetFallDistance();
        ServerLevel level = player.level();
        level.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 0.8F, 1.4F);
        level.sendParticles(ParticleTypes.CLOUD, player.getX(), player.getY() + 0.2, player.getZ(),
                8, 0.2, 0.1, 0.2, 0.02);
    }

    private static void onSlide(ServerPlayer player, boolean start) {
        State state = SERVER.computeIfAbsent(player, p -> new State());
        if (start && Config.on(Config.ENABLE_SLIDE)) {
            state.sliding = true;
            state.slideTicks = 0;
            player.setForcedPose(Pose.SWIMMING);
            player.causeFoodExhaustion(SLIDE_EXHAUSTION);
            BlockState floor = player.getBlockStateOn();
            player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                    floor.getSoundType(player.level(), player.getOnPos(), player).getStepSound(), SoundSource.PLAYERS, 1.0F, 0.7F);
        } else if (state.sliding) {
            state.sliding = false;
            player.setForcedPose(null);
        }
    }

    private static void tickServer(ServerPlayer player) {
        State state = SERVER.get(player);
        if (state == null) {
            return;
        }

        if (state.dashCooldown > 0) {
            state.dashCooldown--;
        }
        // Held for as long as the dash is, so landing from an air dash does not bill the player for
        // height they covered sideways.
        if (state.dashTicks > 0) {
            state.dashTicks--;
            player.resetFallDistance();
        }

        if (!state.sliding) {
            return;
        }

        // A client that never says it stopped - it disconnected, say - is not left lying down: no
        // slide can outlast its configured length, so one well past it is over.
        if (++state.slideTicks > Config.SLIDE_DURATION_TICKS.getAsInt() + 20) {
            state.sliding = false;
            player.setForcedPose(null);
            return;
        }

        BlockState floor = player.getBlockStateOn();
        if (!floor.isAir()) {
            player.level().sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, floor),
                    player.getX(), player.getY() + 0.1, player.getZ(), 3, 0.2, 0.0, 0.2, 0.1);
        }
    }

    // --- Packets ---

    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> payloadType(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(CombatUpdate.MODID, path));
    }

    public record DashPayload() implements CustomPacketPayload {
        public static final Type<DashPayload> TYPE = payloadType("dash");
        public static final StreamCodec<FriendlyByteBuf, DashPayload> CODEC = StreamCodec.unit(new DashPayload());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record SlidePayload(boolean start) implements CustomPacketPayload {
        public static final Type<SlidePayload> TYPE = payloadType("slide");
        public static final StreamCodec<FriendlyByteBuf, SlidePayload> CODEC = StreamCodec.composite(
                ByteBufCodecs.BOOL, SlidePayload::start,
                SlidePayload::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
