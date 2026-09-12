package com.humanbuilder.state;

import com.humanbuilder.aim.HumanAimController;
import com.humanbuilder.config.BuilderConfig;
import com.humanbuilder.inventory.InventoryManager;
import com.humanbuilder.inventory.MissingItemException;
import com.humanbuilder.network.TPSMonitor;
import com.humanbuilder.physics.BlockPlacementMath;
import com.humanbuilder.physics.BlockPlacementMath.PlacementSolution;
import com.humanbuilder.scanner.SchematicBridge;
import com.humanbuilder.scanner.Target;
import com.humanbuilder.stochastic.StochasticEngine;
import com.humanbuilder.util.InputSimulator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.List;

/**
 * Module G — Master State Machine. Runs from {@code MinecraftClient#tick} (TAIL)
 * via mixin. Coordinates scan → fetch → aim → verify → click → cooldown, with TPS
 * pausing and human-error injection, and tracks throughput for build-time
 * estimation (the actual point of the tool).
 */
public final class BuilderStateMachine {

    public static final BuilderStateMachine INSTANCE = new BuilderStateMachine();

    public enum State {
        IDLE, SCANNING, FETCHING_ITEM, PATHING_TO_ANCHOR,
        AIMING, VERIFYING_PHYSICS, CLICKING, COOLDOWN, PAUSED_LAG
    }

    private static final Hand HAND = Hand.MAIN_HAND;

    private State state = State.IDLE;
    private boolean controlling = false;

    private Target target;
    private PlacementSolution solution;
    private boolean misclickPending;
    private int clickHoldTicks;

    /** Wall-clock gate; states that wait compare against this. */
    private long waitUntilMs = 0L;

    // --- Build-time estimation ---
    private long sessionStartMs = 0L;
    private int placedCount = 0;
    private long lastReportMs = 0L;

    private BuilderStateMachine() {}

    public boolean isControllingInput() {
        return controlling && BuilderConfig.INSTANCE.blockUserInput;
    }

    public State getState() {
        return state;
    }

    // ---------------------------------------------------------------------
    //  Main tick
    // ---------------------------------------------------------------------

    public void onClientTick(MinecraftClient client) {
        BuilderConfig cfg = BuilderConfig.INSTANCE;
        TPSMonitor.INSTANCE.update(client.world);

        if (!cfg.enabled) {
            if (controlling) shutdown(client);
            return;
        }
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || client.interactionManager == null) {
            return;
        }

        if (!controlling) startup();

        // Global TPS gate.
        double tps = TPSMonitor.INSTANCE.getTps();
        if (tps < cfg.pauseBelowTps) {
            enterPausedLag(client);
            return;
        } else if (state == State.PAUSED_LAG) {
            state = State.SCANNING;
        }

        switch (state) {
            case IDLE -> state = State.SCANNING;
            case SCANNING -> tickScanning(client, player);
            case FETCHING_ITEM -> tickFetching(client);
            case PATHING_TO_ANCHOR -> tickPathing(client);
            case AIMING -> tickAiming(client);
            case VERIFYING_PHYSICS -> tickVerifying(client, player);
            case CLICKING -> tickClicking(client);
            case COOLDOWN -> tickCooldown(client, player);
            case PAUSED_LAG -> { /* handled above */ }
        }

        maybeReportEstimate(client, player);
    }

    // ---------------------------------------------------------------------
    //  States
    // ---------------------------------------------------------------------

    private void tickScanning(MinecraftClient client, ClientPlayerEntity player) {
        List<Target> targets = SchematicBridge.INSTANCE.scan(client);
        for (Target t : targets) {
            PlacementSolution sol = BlockPlacementMath.solve(
                    client.world, player, t.pos(), t.state(), HAND);
            if (sol != null) {
                this.target = t;
                this.solution = sol;
                this.misclickPending = StochasticEngine.INSTANCE.rollMisclick();
                InventoryManager.INSTANCE.begin(t.state().getBlock().asItem());
                state = State.FETCHING_ITEM;
                return;
            }
        }
        // Nothing placeable in range this tick; remain SCANNING (idle spin).
    }

    private void tickFetching(MinecraftClient client) {
        try {
            if (InventoryManager.INSTANCE.tick(client) == InventoryManager.Status.DONE) {
                state = State.PATHING_TO_ANCHOR;
            }
        } catch (MissingItemException e) {
            // Shouldn't normally happen (scan pre-filters by inventory), but stay safe.
            abortTarget();
        }
    }

    private void tickPathing(MinecraftClient client) {
        // Basic: if placing against an interactable block, hold sneak so the
        // click places instead of opening the block. No walking pathfinding.
        if (solution != null && solution.requiresSneak()) {
            InputSimulator.setSneak(client, true);
        }
        beginAimAtSolution(client);
        state = State.AIMING;
    }

    private void beginAimAtSolution(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || solution == null) return;
        Vec3d eye = player.getEyePos();
        float[] yp = HumanAimController.lookAt(eye, solution.hitVec());
        HumanAimController.INSTANCE.beginAim(player, yp[0], yp[1]);
    }

    private void tickAiming(MinecraftClient client) {
        HumanAimController aim = HumanAimController.INSTANCE;
        boolean settled = aim.tick(client);

        // Misclick model: fire 1–2 ticks early, before the crosshair fully settles.
        if (!settled && misclickPending && aim.inPrimaryPhase() && aim.currentProgress() >= 0.9) {
            misclickPending = false;
            aim.cancel();
            beginClicking(client, /*skipVerify=*/true);
            return;
        }
        if (settled) {
            state = State.VERIFYING_PHYSICS;
        }
    }

    private void tickVerifying(MinecraftClient client, ClientPlayerEntity player) {
        if (solution == null) { abortTarget(); return; }
        boolean ok = BlockPlacementMath.confirm(client.world, player, target.state(), HAND, solution)
                && BlockPlacementMath.lineOfSightClear(client.world, player, solution);
        if (ok) {
            beginClicking(client, /*skipVerify=*/false);
        } else {
            // Something moved into the way, or facing couldn't be satisfied — retry.
            abortTarget();
        }
    }

    private void beginClicking(MinecraftClient client, boolean skipVerify) {
        // Optional hesitation pause before committing.
        long delay = 0L;
        if (StochasticEngine.INSTANCE.rollHesitation()) {
            delay = (long) StochasticEngine.INSTANCE.hesitationMs();
        }
        waitUntilMs = System.currentTimeMillis() + delay;
        clickHoldTicks = 2; // hold the use key ~2 ticks
        state = State.CLICKING;
    }

    private void tickClicking(MinecraftClient client) {
        if (System.currentTimeMillis() < waitUntilMs) return; // hesitation

        if (clickHoldTicks == 2) {
            InputSimulator.setUse(client, true); // press (fires doItemUse next tick)
        }
        clickHoldTicks--;
        if (clickHoldTicks <= 0) {
            InputSimulator.setUse(client, false); // release
            double factor = TPSMonitor.INSTANCE.getDelayFactor();
            waitUntilMs = System.currentTimeMillis()
                    + (long) StochasticEngine.INSTANCE.placementCooldownMs(factor);
            state = State.COOLDOWN;
        }
    }

    private void tickCooldown(MinecraftClient client, ClientPlayerEntity player) {
        if (System.currentTimeMillis() < waitUntilMs) return;

        // Count it as placed if the real world now matches the schematic block.
        if (target != null && client.world != null) {
            var now = client.world.getBlockState(target.pos());
            if (!now.isAir() && now.getBlock() == target.state().getBlock()) {
                placedCount++;
            }
        }
        InputSimulator.setSneak(client, false);
        clearTarget();
        state = State.SCANNING;
    }

    private void enterPausedLag(MinecraftClient client) {
        InputSimulator.releaseAll(client);
        HumanAimController.INSTANCE.cancel();
        state = State.PAUSED_LAG;
    }

    // ---------------------------------------------------------------------
    //  Lifecycle
    // ---------------------------------------------------------------------

    private void startup() {
        controlling = true;
        sessionStartMs = System.currentTimeMillis();
        placedCount = 0;
        lastReportMs = 0L;
        StochasticEngine.INSTANCE.onActivate();
        state = State.SCANNING;
    }

    private void shutdown(MinecraftClient client) {
        InputSimulator.releaseAll(client);
        HumanAimController.INSTANCE.cancel();
        StochasticEngine.INSTANCE.onDeactivate();
        controlling = false;
        clearTarget();
        state = State.IDLE;
    }

    /** External reset (toggle off, disconnect). */
    public void reset() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) {
            InputSimulator.releaseAll(client);
        }
        HumanAimController.INSTANCE.cancel();
        StochasticEngine.INSTANCE.onDeactivate();
        TPSMonitor.INSTANCE.reset();
        controlling = false;
        clearTarget();
        state = State.IDLE;
    }

    private void abortTarget() {
        HumanAimController.INSTANCE.cancel();
        clearTarget();
        state = State.SCANNING;
    }

    private void clearTarget() {
        target = null;
        solution = null;
        misclickPending = false;
        clickHoldTicks = 0;
    }

    // ---------------------------------------------------------------------
    //  Build-time estimation reporting
    // ---------------------------------------------------------------------

    private void maybeReportEstimate(MinecraftClient client, ClientPlayerEntity player) {
        if (!BuilderConfig.INSTANCE.reportEstimate) return;
        long now = System.currentTimeMillis();
        if (now - lastReportMs < 5000L) return;
        lastReportMs = now;

        double elapsedSec = (now - sessionStartMs) / 1000.0;
        if (elapsedSec < 1.0 || placedCount == 0) return;

        double perMin = placedCount / (elapsedSec / 60.0);
        double secPer = elapsedSec / placedCount;
        String msg = String.format(
                "§bHumanBuilder §7| placed §f%d §7| §f%.1f§7/min | §f%.2fs§7/block | TPS §f%.1f§7 | fatigue §f%.2fx",
                placedCount, perMin, secPer, TPSMonitor.INSTANCE.getTps(),
                StochasticEngine.INSTANCE.fatigueMultiplier());
        player.sendMessage(Text.literal(msg), true);
    }
}
