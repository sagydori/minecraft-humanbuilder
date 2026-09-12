package com.humanbuilder.aim;

import com.humanbuilder.config.BuilderConfig;
import com.humanbuilder.stochastic.StochasticEngine;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * Module A — Kinematics & Aim Engine.
 *
 * Implements, exactly as specified:
 * <ul>
 *   <li><b>Fitts's Law duration</b>: {@code 85 + 110 * log2(1 + dist/8.0)} ms.</li>
 *   <li><b>Quintic Bézier / smootherstep</b> easing: {@code 6t^5 - 15t^4 + 10t^3}.</li>
 *   <li><b>Micro-tremor</b>: {@code sin(t*0.012)*0.03 + cos(t*0.019)*0.04}, added
 *       to yaw and pitch BEFORE quantization.</li>
 *   <li><b>Overshoot–correction</b>: with configured probability, aim 0.5–1.5°
 *       past the target, then run a short secondary interpolation back.</li>
 *   <li><b>Mouse sensitivity quantization</b>: per-tick delta snapped to the
 *       client's cursor GCD {@code (f^3 * 8 * 0.15)}, {@code f = sens*0.6 + 0.2}.</li>
 * </ul>
 */
public final class HumanAimController {

    public static final HumanAimController INSTANCE = new HumanAimController();

    private enum Phase { IDLE, PRIMARY, CORRECTION }

    private Phase phase = Phase.IDLE;

    // Interpolation endpoints for the active phase.
    private float startYaw, startPitch;
    private float aimYaw, aimPitch;      // endpoint of the current phase
    private float trueYaw, truePitch;    // final target (may differ during overshoot)

    private long phaseStartMs;
    private long phaseDurationMs;
    private boolean overshootPending;

    // Continuous clock for the tremor term (ms since first aim).
    private long tremorClock;

    /** Progress [0..1] of the current phase, exposed for the misclick model. */
    private double lastT;

    private HumanAimController() {}

    /** Progress of the current phase, 0..1. */
    public double currentProgress() {
        return lastT;
    }

    /** True while the primary (pre-correction) phase is running. */
    public boolean inPrimaryPhase() {
        return phase == Phase.PRIMARY;
    }

    // ---------------------------------------------------------------------
    //  Geometry
    // ---------------------------------------------------------------------

    /** Yaw/pitch (degrees) that points the eye at {@code target}. */
    public static float[] lookAt(Vec3d eye, Vec3d target) {
        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) MathHelper.wrapDegrees(-Math.toDegrees(Math.atan2(dy, horiz)));
        pitch = MathHelper.clamp(pitch, -90.0f, 90.0f);
        return new float[]{yaw, pitch};
    }

    /** Combined angular distance (deg) between two yaw/pitch pairs, yaw wrapped. */
    private static double angularDistance(float yaw0, float pitch0, float yaw1, float pitch1) {
        double dYaw = MathHelper.wrapDegrees(yaw1 - yaw0);
        double dPitch = pitch1 - pitch0;
        return Math.sqrt(dYaw * dYaw + dPitch * dPitch);
    }

    // ---------------------------------------------------------------------
    //  Public control
    // ---------------------------------------------------------------------

    /** Begin an aim toward the given absolute yaw/pitch from the player's current view. */
    public void beginAim(ClientPlayerEntity player, float targetYaw, float targetPitch) {
        this.trueYaw = targetYaw;
        this.truePitch = MathHelper.clamp(targetPitch, -90.0f, 90.0f);

        BuilderConfig cfg = BuilderConfig.INSTANCE;
        this.overshootPending = cfg.enableOvershoot
                && StochasticEngine.INSTANCE.raw().nextDouble() < cfg.overshootChance;

        float endYaw = trueYaw;
        float endPitch = truePitch;

        if (overshootPending) {
            // Extend past the target by 0.5–1.5° along the aim direction.
            double dYaw = MathHelper.wrapDegrees(trueYaw - player.getYaw());
            double dPitch = truePitch - player.getPitch();
            double len = Math.sqrt(dYaw * dYaw + dPitch * dPitch);
            if (len > 1.0e-4) {
                double extra = 0.5 + StochasticEngine.INSTANCE.raw().nextDouble() * 1.0;
                endYaw = trueYaw + (float) (dYaw / len * extra);
                endPitch = MathHelper.clamp(truePitch + (float) (dPitch / len * extra), -90f, 90f);
            } else {
                overshootPending = false;
            }
        }

        startPhase(player, Phase.PRIMARY, endYaw, endPitch);
    }

    private void startPhase(ClientPlayerEntity player, Phase p, float endYaw, float endPitch) {
        this.phase = p;
        this.startYaw = player.getYaw();
        this.startPitch = player.getPitch();
        this.aimYaw = endYaw;
        this.aimPitch = endPitch;
        this.phaseStartMs = System.currentTimeMillis();

        double dist = angularDistance(startYaw, startPitch, endYaw, endPitch);
        if (p == Phase.CORRECTION) {
            // Short, snappy settle back onto the true target.
            this.phaseDurationMs = (long) Math.max(60.0, 40.0 + 12.0 * dist);
        } else {
            // Fitts's Law: 85 + 110 * log2(1 + dist/8).
            double fitts = 85.0 + 110.0 * log2(1.0 + dist / 8.0);
            this.phaseDurationMs = (long) Math.max(60.0, fitts);
        }
    }

    public boolean isAiming() {
        return phase != Phase.IDLE;
    }

    public void cancel() {
        phase = Phase.IDLE;
    }

    /**
     * Advance the aim by one client tick. Returns true when the view has settled
     * on the true target (aim complete this tick or already idle-on-target).
     */
    public boolean tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null) return false;
        if (phase == Phase.IDLE) return true;

        tremorClock += 50; // ~one client tick

        long elapsed = System.currentTimeMillis() - phaseStartMs;
        double t = MathHelper.clamp((double) elapsed / (double) phaseDurationMs, 0.0, 1.0);
        this.lastT = t;
        double eased = quinticEase(t);

        // Interpolated target this tick (shortest-path yaw).
        double dYaw = MathHelper.wrapDegrees(aimYaw - startYaw);
        float interpYaw = (float) (startYaw + dYaw * eased);
        float interpPitch = (float) (startPitch + (aimPitch - startPitch) * eased);

        BuilderConfig cfg = BuilderConfig.INSTANCE;
        if (cfg.enableTremor && t < 1.0) {
            float ty = (float) (Math.sin(tremorClock * 0.012) * 0.03);
            float tp = (float) (Math.cos(tremorClock * 0.019) * 0.04);
            interpYaw += ty;
            interpPitch += tp;
        }

        interpPitch = MathHelper.clamp(interpPitch, -90.0f, 90.0f);

        // Quantize the *delta* from the current view to the client's cursor GCD.
        float newYaw = quantizeToward(player.getYaw(), interpYaw, client);
        float newPitch = quantizeToward(player.getPitch(), interpPitch, client);
        newPitch = MathHelper.clamp(newPitch, -90.0f, 90.0f);

        applyRotation(player, newYaw, newPitch);

        if (t >= 1.0) {
            if (phase == Phase.PRIMARY && overshootPending) {
                overshootPending = false;
                startPhase(player, Phase.CORRECTION, trueYaw, truePitch);
                return false;
            }
            // Settled: lock exactly onto the true target.
            applyRotation(player, trueYaw, truePitch);
            phase = Phase.IDLE;
            return true;
        }
        return false;
    }

    private void applyRotation(ClientPlayerEntity player, float yaw, float pitch) {
        // Per-tick deltas are small; the vanilla interpolation system smooths the
        // camera on its own (the old prev-rotation fields were removed in 1.21.x).
        player.setYaw(yaw);
        player.setPitch(pitch);
    }

    // ---------------------------------------------------------------------
    //  Math primitives
    // ---------------------------------------------------------------------

    /** Quintic smootherstep: 6t^5 - 15t^4 + 10t^3. */
    public static double quinticEase(double t) {
        return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    private static double log2(double x) {
        return Math.log(x) / Math.log(2.0);
    }

    /**
     * Move from {@code current} toward {@code target} but snap the applied delta
     * to a whole multiple of the client's mouse-cursor GCD, so the camera steps
     * exactly as it would under real mouse input at the current sensitivity.
     */
    private float quantizeToward(float current, float target, MinecraftClient client) {
        if (!BuilderConfig.INSTANCE.enableQuantization) {
            return target;
        }
        double delta = target - current;
        double gcd = cursorGcd(client);
        if (gcd <= 1.0e-9) return target;
        double quantized = Math.round(delta / gcd) * gcd;
        return (float) (current + quantized);
    }

    /**
     * The smallest camera step for one cursor unit at the current sensitivity:
     * <pre>
     *   f   = sens * 0.6 + 0.2
     *   gcd = f * f * f * 8.0 * 0.15
     * </pre>
     */
    private double cursorGcd(MinecraftClient client) {
        double sens = client.options.getMouseSensitivity().getValue();
        double f = sens * 0.6 + 0.2;
        return f * f * f * 8.0 * 0.15;
    }
}
