package com.humanbuilder.stochastic;

import com.humanbuilder.config.BuilderConfig;

import java.util.Random;

/**
 * Module E — Stochastic Delay Engine.
 *
 * <ul>
 *   <li><b>Box–Muller transform</b> for normally distributed millisecond delays.</li>
 *   <li><b>Dynamic fatigue</b>: multiplier starts at 1.0 and grows
 *       {@code fatiguePerMinute} each minute of continuous activity, capped at
 *       {@code fatigueCap}.</li>
 *   <li><b>Human error</b>: misclick (fire 1–2 ticks early) and hesitation
 *       (pause 400–800 ms) rolled per placement.</li>
 * </ul>
 */
public final class StochasticEngine {

    public static final StochasticEngine INSTANCE = new StochasticEngine();

    private final Random random = new Random();

    /** Wall-clock ms at which the current activation started (for fatigue). */
    private long activationStartMs = 0L;
    private boolean active = false;

    /** Cached spare value from the Box–Muller pair. */
    private double spare;
    private boolean hasSpare = false;

    private StochasticEngine() {}

    public void onActivate() {
        if (!active) {
            active = true;
            activationStartMs = System.currentTimeMillis();
        }
    }

    public void onDeactivate() {
        active = false;
        hasSpare = false;
    }

    /**
     * Standard normal sample via the polar-free basic Box–Muller transform:
     * <pre>
     *   z0 = sqrt(-2 ln U1) * cos(2π U2)
     *   z1 = sqrt(-2 ln U1) * sin(2π U2)
     * </pre>
     * The second value of each pair is cached and returned on the next call.
     */
    public double nextGaussian() {
        if (hasSpare) {
            hasSpare = false;
            return spare;
        }
        double u1;
        do {
            u1 = random.nextDouble();
        } while (u1 <= 1e-12); // guard ln(0)
        double u2 = random.nextDouble();
        double mag = Math.sqrt(-2.0 * Math.log(u1));
        double z0 = mag * Math.cos(2.0 * Math.PI * u2);
        spare = mag * Math.sin(2.0 * Math.PI * u2);
        hasSpare = true;
        return z0;
    }

    /** A Gaussian delay in ms, clamped to be non-negative, before fatigue/TPS scaling. */
    public double gaussianMs(double meanMs, double stdMs) {
        return Math.max(0.0, meanMs + nextGaussian() * stdMs);
    }

    /**
     * Current fatigue multiplier in [1.0, fatigueCap]. Grows linearly with the
     * number of whole minutes the bot has been continuously active.
     */
    public double fatigueMultiplier() {
        if (!active) return 1.0;
        BuilderConfig cfg = BuilderConfig.INSTANCE;
        double minutes = (System.currentTimeMillis() - activationStartMs) / 60_000.0;
        double mult = 1.0 + cfg.fatiguePerMinute * minutes;
        return Math.min(cfg.fatigueCap, mult);
    }

    /**
     * Full per-placement cooldown in ms: Gaussian base, scaled by fatigue and by
     * the TPS factor supplied by the caller (20 / currentTps).
     */
    public double placementCooldownMs(double tpsFactor) {
        BuilderConfig cfg = BuilderConfig.INSTANCE;
        double base = gaussianMs(cfg.clickDelayMeanMs, cfg.clickDelayStdMs);
        return base * fatigueMultiplier() * tpsFactor;
    }

    /** Inventory swap click delay in ms (blueprint mean 220 / std 40), fatigue-scaled. */
    public double inventoryClickMs() {
        BuilderConfig cfg = BuilderConfig.INSTANCE;
        return gaussianMs(cfg.invClickMeanMs, cfg.invClickStdMs) * fatigueMultiplier();
    }

    public boolean rollMisclick() {
        return random.nextDouble() < BuilderConfig.INSTANCE.misclickChance;
    }

    public boolean rollHesitation() {
        return random.nextDouble() < BuilderConfig.INSTANCE.hesitationChance;
    }

    /** Hesitation pause length, 400–800 ms. */
    public double hesitationMs() {
        return 400.0 + random.nextDouble() * 400.0;
    }

    /** Misclick lead time: fire 1–2 ticks (50–100 ms) early. */
    public double misclickLeadMs() {
        return 50.0 + random.nextDouble() * 50.0;
    }

    public Random raw() {
        return random;
    }
}
