package com.humanbuilder.network;

import net.minecraft.client.world.ClientWorld;

import java.util.ArrayDeque;

/**
 * Module D — Server Lag / TPS compensator (corrected implementation).
 *
 * <p>The blueprint proposed reading {@code WorldTimeUpdateC2SPacket}; that packet
 * is <i>clientbound</i> (S2C) and Fabric cannot register receivers for vanilla
 * packets. Instead we measure TPS purely from observable state: the world's game
 * time ({@link ClientWorld#getTime()}) only advances when the server actually
 * ticks. Sampling that counter against wall-clock over a rolling 10-second window
 * yields a robust average TPS in both singleplayer (integrated server) and on a
 * remote/own server.</p>
 *
 * <p>Delays are then scaled by {@code 20 / tps}; below the configured floor the
 * state machine parks in PAUSED_LAG until recovery.</p>
 */
public final class TPSMonitor {

    public static final TPSMonitor INSTANCE = new TPSMonitor();

    private static final long WINDOW_MS = 10_000L;
    private static final double DEFAULT_TPS = 20.0;

    private record Sample(long wallMs, long gameTime) {}

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private double smoothedTps = DEFAULT_TPS;

    private TPSMonitor() {}

    /** Call once per client tick with the current client world (may be null). */
    public void update(ClientWorld world) {
        if (world == null) {
            reset();
            return;
        }
        long now = System.currentTimeMillis();
        long gameTime = world.getTime();
        samples.addLast(new Sample(now, gameTime));

        while (!samples.isEmpty() && now - samples.peekFirst().wallMs() > WINDOW_MS) {
            samples.pollFirst();
        }

        Sample first = samples.peekFirst();
        Sample last = samples.peekLast();
        if (first != null && last != null && last.wallMs() > first.wallMs()) {
            double seconds = (last.wallMs() - first.wallMs()) / 1000.0;
            long ticks = last.gameTime() - first.gameTime();
            if (seconds >= 1.0 && ticks >= 0) {
                double instantaneous = ticks / seconds;
                // Clamp: game time can appear to stall (paused SP) or, rarely, jump.
                instantaneous = Math.max(0.0, Math.min(20.0, instantaneous));
                // Light EMA to avoid single-window jitter.
                smoothedTps = smoothedTps * 0.7 + instantaneous * 0.3;
            }
        }
    }

    public double getTps() {
        return smoothedTps;
    }

    /** 20 / tps, clamped to at least 1.0 so delays never collapse to zero. */
    public double getDelayFactor() {
        double tps = Math.max(1.0, smoothedTps);
        return 20.0 / tps;
    }

    public void reset() {
        samples.clear();
        smoothedTps = DEFAULT_TPS;
    }
}
