package com.humanbuilder.nav;

import com.humanbuilder.config.BuilderConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Translates A* waypoints into smooth camera turning and native key-press
 * movement. Never sends movement packets — it only toggles the vanilla movement
 * KeyBindings, so Minecraft's own client tick applies physics.
 */
public final class NavigationController {

    public static final NavigationController INSTANCE = new NavigationController();

    public enum Status { NAVIGATING, ARRIVED, STUCK, FAILED }

    private List<BlockPos> path = new ArrayList<>();
    private int index;

    private Vec3d lastPos = Vec3d.ZERO;
    private int stationaryTicks;

    // Human-walk state: a slow weave + fine tremor so movement isn't robotically
    // straight. The PLAN (route/waypoints) is Baritone's; only the steering along
    // it is humanised.
    private double walkClock;
    private float wanderTargetDeg;
    private long wanderRetargetMs;
    private final java.util.Random rng = new java.util.Random();

    private NavigationController() {}

    public void setPath(List<BlockPos> path) {
        this.path = (path == null) ? new ArrayList<>() : new ArrayList<>(path);
        this.index = 0;
        this.stationaryTicks = 0;
        this.lastPos = Vec3d.ZERO;
    }

    public boolean hasPath() {
        return path != null && index < path.size();
    }

    // ---------------------------------------------------------------------
    //  Drive
    // ---------------------------------------------------------------------

    public Status tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return Status.FAILED;
        if (!hasPath()) { stop(client); return Status.ARRIVED; }

        BuilderConfig cfg = BuilderConfig.INSTANCE;
        BlockPos wp = path.get(index);
        Vec3d wpc = new Vec3d(wp.getX() + 0.5, wp.getY(), wp.getZ() + 0.5);

        double dx = wpc.x - player.getX();
        double dz = wpc.z - player.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        double dy = wp.getY() - player.getY();

        // Reached this waypoint?
        if (horiz < 0.6 && Math.abs(dy) < 1.2) {
            index++;
            if (!hasPath()) { stop(client); return Status.ARRIVED; }
            return Status.NAVIGATING;
        }

        // Aim toward the waypoint — but not roboticly straight. A person weaves a
        // little as they hold W, their view has fine tremor, and their steering
        // rate isn't uniform. The route is Baritone's; this is just how WE walk it.
        float targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float delta = MathHelper.wrapDegrees(targetYaw - player.getYaw());
        float aimYaw = targetYaw;
        float turnRate = (float) cfg.turnSpeedDegPerTick;

        if (cfg.humanizeWalk) {
            walkClock += 50;
            long nowMs = System.currentTimeMillis();
            if (nowMs >= wanderRetargetMs) {
                wanderTargetDeg = (float) ((rng.nextDouble() * 2.0 - 1.0) * cfg.walkWanderDeg);
                wanderRetargetMs = nowMs + 500 + rng.nextInt(900); // re-weave every 0.5–1.4s
            }
            // Slow weave + fine tremor; damp both as we close on the waypoint so we
            // still arrive cleanly and never weave off a ledge or clip a corner.
            float weave = (float) (wanderTargetDeg * (0.5 + 0.5 * Math.sin(walkClock * 0.004)));
            float tremor = (float) (Math.sin(walkClock * 0.021) * 0.7 + Math.cos(walkClock * 0.013) * 0.5);
            double precision = horiz < 1.5 ? 0.2 : 1.0;
            aimYaw = targetYaw + (float) ((weave + tremor) * precision);
            turnRate *= (float) (0.8 + 0.35 * (0.5 + 0.5 * Math.sin(walkClock * 0.006))); // non-uniform steering
        }

        turnToward(player, aimYaw, turnRate);
        if (cfg.humanizeWalk) {
            player.setPitch((float) (Math.sin(walkClock * 0.0025) * 2.5)); // subtle head bob
        }

        // Walk forward; jump for step-ups or obstacles.
        press(client.options.forwardKey, true);
        boolean stepUp = dy > 0.5;
        boolean obstacle = obstacleAhead(client, player);
        press(client.options.jumpKey, stepUp || obstacle);

        // Sneak at ledges so we don't walk off the build (unless we mean to drop).
        boolean descending = dy < -0.5;
        boolean edge = !descending && edgeAhead(client, player);
        press(client.options.sneakKey, edge);

        // Sprint only on long, clear, flat straightaways, and not mid-turn — never
        // near a waypoint, an edge, an obstacle or a step-up (safe and accurate).
        boolean canSprint = horiz > 3.0 && Math.abs(delta) < 25.0
                && !edge && !obstacle && !stepUp && Math.abs(dy) < 0.6;
        press(client.options.sprintKey, canSprint);

        // Stuck detection: barely moved while trying to walk.
        Vec3d pp = new Vec3d(player.getX(), player.getY(), player.getZ());
        double moved = pp.distanceTo(lastPos);
        lastPos = pp;
        if (moved < 0.02) {
            if (++stationaryTicks > 40) return Status.STUCK;
        } else {
            stationaryTicks = 0;
        }
        return Status.NAVIGATING;
    }

    private void turnToward(ClientPlayerEntity player, float targetYaw, float maxStep) {
        float cur = player.getYaw();
        float delta = MathHelper.wrapDegrees(targetYaw - cur);
        float step = MathHelper.clamp(delta, -maxStep, maxStep);
        player.setYaw(cur + step);
        // Ease pitch back toward level while walking.
        player.setPitch(player.getPitch() * 0.7f);
    }

    private boolean obstacleAhead(MinecraftClient client, ClientPlayerEntity player) {
        Direction facing = horizontalFromYaw(player.getYaw());
        BlockPos feet = player.getBlockPos();
        BlockPos front = feet.offset(facing);
        boolean blocked = !client.world.getBlockState(front).getCollisionShape(client.world, front).isEmpty();
        boolean clearAbove = client.world.getBlockState(front.up()).getCollisionShape(client.world, front.up()).isEmpty();
        return blocked && clearAbove;
    }

    /** Would stepping forward drop off a ledge (no floor in front)? */
    private boolean edgeAhead(MinecraftClient client, ClientPlayerEntity player) {
        Direction facing = horizontalFromYaw(player.getYaw());
        BlockPos front = player.getBlockPos().offset(facing);
        boolean frontClear = client.world.getBlockState(front).getCollisionShape(client.world, front).isEmpty()
                && client.world.getBlockState(front.up()).getCollisionShape(client.world, front.up()).isEmpty();
        boolean floorInFront = !client.world.getBlockState(front.down()).getCollisionShape(client.world, front.down()).isEmpty();
        return frontClear && !floorInFront;
    }

    /** Nearest cardinal direction for a yaw: S=0°, W=90°, N=180°, E=270°. */
    private static Direction horizontalFromYaw(float yaw) {
        int i = Math.floorMod(Math.round(yaw / 90.0f), 4);
        return switch (i) {
            case 0 -> Direction.SOUTH;
            case 1 -> Direction.WEST;
            case 2 -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    // ---------------------------------------------------------------------
    //  Recovery / key management
    // ---------------------------------------------------------------------

    /** One tick of stuck-recovery: back up and hop. */
    public void tickRecovery(MinecraftClient client, int recoveryTick) {
        press(client.options.forwardKey, false);
        press(client.options.sprintKey, false);
        press(client.options.backKey, true);
        press(client.options.jumpKey, recoveryTick % 8 < 3);
    }

    public void stop(MinecraftClient client) {
        press(client.options.forwardKey, false);
        press(client.options.backKey, false);
        press(client.options.leftKey, false);
        press(client.options.rightKey, false);
        press(client.options.jumpKey, false);
        press(client.options.sneakKey, false);
        press(client.options.sprintKey, false);
        stationaryTicks = 0;
    }

    private static void press(KeyBinding key, boolean pressed) {
        key.setPressed(pressed);
    }

    // ---------------------------------------------------------------------
    //  Rendering support
    // ---------------------------------------------------------------------

    /** Remaining path as world-space points (player → each waypoint center), for the red line. */
    public List<Vec3d> getRenderPath() {
        if (!hasPath()) return List.of();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return List.of();
        List<Vec3d> out = new ArrayList<>();
        out.add(new Vec3d(mc.player.getX(), mc.player.getY() + 0.5, mc.player.getZ()));
        for (int i = index; i < path.size(); i++) {
            BlockPos p = path.get(i);
            out.add(new Vec3d(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5));
        }
        return out;
    }
}
