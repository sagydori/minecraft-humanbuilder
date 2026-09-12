package com.humanbuilder.state;

import com.humanbuilder.aim.HumanAimController;
import com.humanbuilder.config.BuilderConfig;
import com.humanbuilder.inventory.InventoryManager;
import com.humanbuilder.inventory.MissingItemException;
import com.humanbuilder.nav.NavigationController;
import com.humanbuilder.nav.Pathfinder;
import com.humanbuilder.nav.Scaffolder;
import com.humanbuilder.network.TPSMonitor;
import com.humanbuilder.physics.BlockPlacementMath;
import com.humanbuilder.physics.BlockPlacementMath.PlacementSolution;
import com.humanbuilder.scanner.SchematicBridge;
import com.humanbuilder.scanner.Target;
import com.humanbuilder.gui.BuilderScreen;
import com.humanbuilder.stochastic.StochasticEngine;
import com.humanbuilder.util.InputSimulator;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Module G — Master State Machine. Runs from {@code MinecraftClient#tick} (TAIL)
 * via mixin. Coordinates scan → (navigate) → fetch → aim → verify → click →
 * cooldown, with TPS pausing, human-error injection, autonomous movement to
 * out-of-reach targets, and a blacklist that prevents infinite retry loops.
 */
public final class BuilderStateMachine {

    public static final BuilderStateMachine INSTANCE = new BuilderStateMachine();

    public enum State {
        IDLE, SCANNING, NAVIGATING, ARRIVED, STUCK_RECOVERY,
        FETCHING_ITEM, PATHING_TO_ANCHOR,
        AIMING, VERIFYING_PHYSICS, CLICKING, COOLDOWN, PAUSED_LAG
    }

    private static final Hand HAND = Hand.MAIN_HAND;

    private State state = State.IDLE;
    private boolean controlling = false;

    private Target target;
    private PlacementSolution solution;
    private boolean misclickPending;

    // Navigation.
    private BlockPos navTarget;
    private int recoveryTicks;
    private int recoveryAttempts;

    // Full-schematic target cache.
    private final java.util.List<Target> targetCache = new java.util.ArrayList<>();
    private long lastScanMs = 0L;

    // Scaffolding.
    private boolean buildingScaffold;
    private BlockPos scaffoldGoal;
    private int scaffoldAttempts;

    /** Temporary skip-lists keyed by BlockPos.asLong() → expiry epoch-ms / fail count. */
    private final Map<Long, Long> blacklistUntil = new HashMap<>();
    private final Map<Long, Integer> placeFails = new HashMap<>();

    private long waitUntilMs = 0L;
    private long fetchStartMs = 0L;
    /** Grace window after a placement during which a popped-up screen is auto-closed. */
    private long allowScreenCloseUntil = 0L;

    // Per-stage diagnostic counters (surfaced in the status line while placed==0).
    private int diagSolveNull, diagFetchFail, diagVerifyFail, diagPlaceTry, diagPlaceFail;

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

        // Screens: auto-close ones our own placement just opened (sign editor,
        // accidental container) so we don't hang; otherwise pause for screens the
        // user opened (chat, inventory, our menu).
        if (client.currentScreen != null) {
            boolean placementScreen = controlling
                    && !(client.currentScreen instanceof BuilderScreen)
                    && System.currentTimeMillis() < allowScreenCloseUntil;
            if (placementScreen) {
                client.currentScreen.close();
            } else {
                releaseAllInput(client);
                return;
            }
        }
        // Safety: stop entirely if the player died.
        if (player.isDead() || player.getHealth() <= 0.0f) {
            message(client, "§cHumanBuilder stopped — player died");
            cfg.enabled = false;
            shutdown(client);
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
            case NAVIGATING -> tickNavigating(client);
            case ARRIVED -> tickArrived(client, player);
            case STUCK_RECOVERY -> tickStuckRecovery(client, player);
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
    //  Scan / navigate
    // ---------------------------------------------------------------------

    private void tickScanning(MinecraftClient client, ClientPlayerEntity player) {
        BuilderConfig cfg = BuilderConfig.INSTANCE;
        long now = System.currentTimeMillis();

        // Full-schematic scan, cached: refresh when the cache is empty (current
        // layer finished) or gets stale (new blocks became placeable).
        if (targetCache.isEmpty() || now - lastScanMs > 3000L) {
            targetCache.clear();
            targetCache.addAll(SchematicBridge.INSTANCE.scan(client));
            lastScanMs = now;
        }

        // Best non-blacklisted target (list is lowest-Y first, then nearest).
        Target chosen = null;
        for (Target t : targetCache) {
            if (!blacklisted(t.pos())) { chosen = t; break; }
        }
        if (chosen == null) return; // nothing to do right now (done, or all far/blacklisted)

        PlacementSolution sol = BlockPlacementMath.solve(
                client.world, player, chosen.pos(), chosen.state(), HAND);
        if (sol == null) {
            diagSolveNull++;
            blacklist(chosen.pos(), 4_000);
            targetCache.remove(chosen);
            return;
        }

        boolean canBuildHere = BlockPlacementMath.reachable(client.world, player, sol)
                && BlockPlacementMath.facingOkFrom(player, HAND, chosen.state(), sol, player.getEyePos());
        if (canBuildHere) {
            beginBuild(chosen, sol, now, false);
            targetCache.remove(chosen);
            return;
        }

        // Not buildable from here — walk to it (even if far; partial paths make
        // progress, and we re-plan on arrival).
        if (cfg.enableNavigation && startNavigation(client, player, chosen, sol)) {
            Scaffolder.INSTANCE.reset();
            scaffoldGoal = null;
            navTarget = chosen.pos();
            recoveryAttempts = 0;
            state = State.NAVIGATING;
            return;
        }

        // Can't reach or path to it (usually too high) — build a staircase up.
        if (tryScaffold(client, player, chosen)) return;

        // Nothing we can do with it right now.
        blacklist(chosen.pos(), 15_000);
        targetCache.remove(chosen);
    }

    private void beginBuild(Target t, PlacementSolution sol, long now, boolean scaffold) {
        this.target = t;
        this.solution = sol;
        this.buildingScaffold = scaffold;
        this.misclickPending = scaffold ? false : StochasticEngine.INSTANCE.rollMisclick();
        InventoryManager.INSTANCE.begin(scaffold ? Scaffolder.INSTANCE.item() : t.state().getBlock().asItem());
        fetchStartMs = now;
        if (!scaffold) {
            Scaffolder.INSTANCE.reset();
            scaffoldGoal = null;
        }
        state = State.FETCHING_ITEM;
    }

    /** Build a staircase step toward an unreachable (too-high) target. */
    private boolean tryScaffold(MinecraftClient client, ClientPlayerEntity player, Target realTarget) {
        BuilderConfig cfg = BuilderConfig.INSTANCE;
        if (!cfg.enableScaffolding) return false;

        // New goal → fresh staircase.
        if (scaffoldGoal == null || !scaffoldGoal.equals(realTarget.pos())) {
            scaffoldGoal = realTarget.pos();
            scaffoldAttempts = 0;
            Scaffolder.INSTANCE.reset();
        }
        if (scaffoldAttempts > cfg.maxScaffoldBlocks * 2) {
            Scaffolder.INSTANCE.reset();
            scaffoldGoal = null;
            return false; // give up; caller blacklists the target
        }

        Target scaf = Scaffolder.INSTANCE.next(client, player, realTarget.pos());
        if (scaf == null) return false;

        PlacementSolution ssol = BlockPlacementMath.solve(client.world, player, scaf.pos(), scaf.state(), HAND);
        if (ssol == null) return false;

        if (BlockPlacementMath.reachable(client.world, player, ssol)) {
            scaffoldAttempts++;
            beginBuild(scaf, ssol, System.currentTimeMillis(), true);
            return true;
        }
        if (cfg.enableNavigation && startNavigation(client, player, scaf, ssol)) {
            navTarget = scaf.pos();
            recoveryAttempts = 0;
            state = State.NAVIGATING;
            return true;
        }
        return false;
    }

    private boolean startNavigation(MinecraftClient client, ClientPlayerEntity player,
                                    Target t, PlacementSolution sol) {
        // Require a standing spot from which looking at the target gives the right
        // facing (so directional blocks come out correct after we walk there).
        java.util.function.Predicate<Vec3d> eyeOk =
                eye -> BlockPlacementMath.facingOkFrom(player, HAND, t.state(), sol, eye);
        BlockPos stand = Pathfinder.findStandingPosition(
                client.world, t.pos(), player.getBlockPos(), BuilderConfig.INSTANCE.navReach, eyeOk);
        if (stand == null) return false;
        if (player.getBlockPos().equals(stand)) {
            NavigationController.INSTANCE.setPath(List.of());
            return true;
        }
        List<BlockPos> path = Pathfinder.findPath(client.world, player.getBlockPos(), stand);
        if (path.isEmpty()) return false;
        NavigationController.INSTANCE.setPath(path);
        return true;
    }

    private void tickNavigating(MinecraftClient client) {
        switch (NavigationController.INSTANCE.tick(client)) {
            case ARRIVED -> { NavigationController.INSTANCE.stop(client); state = State.ARRIVED; }
            case STUCK -> { recoveryTicks = 0; state = State.STUCK_RECOVERY; }
            case FAILED -> {
                NavigationController.INSTANCE.stop(client);
                blacklist(navTarget, 15_000);
                navTarget = null;
                state = State.SCANNING;
            }
            case NAVIGATING -> { /* keep walking */ }
        }
    }

    private void tickArrived(MinecraftClient client, ClientPlayerEntity player) {
        NavigationController.INSTANCE.stop(client);
        BlockPos nt = navTarget;
        navTarget = null;
        recoveryAttempts = 0;
        state = State.SCANNING;

        // If, after arriving, we still can't reach/build it, blacklist so we
        // don't walk back and forth to the same unreachable target forever.
        if (nt == null) return;
        var schem = SchematicWorldHandler.getSchematicWorld();
        if (schem == null) return;
        BlockState want = schem.getBlockState(nt);
        if (want.isAir()) return;
        PlacementSolution sol = BlockPlacementMath.solve(client.world, player, nt, want, HAND);
        if (sol == null) { blacklist(nt, 12_000); return; }

        boolean canBuild = BlockPlacementMath.reachable(client.world, player, sol)
                && BlockPlacementMath.facingOkFrom(player, HAND, want, sol, player.getEyePos());
        if (canBuild) return; // SCANNING will build it next tick

        // Only give up if we're actually near it; if it's still far, we only made
        // partial progress (long path) — keep approaching without blacklisting.
        double dist = player.getEyePos().distanceTo(sol.targetCenter());
        if (dist <= BuilderConfig.INSTANCE.navReach + 2.5) {
            blacklist(nt, 12_000);
        }
    }

    private void tickStuckRecovery(MinecraftClient client, ClientPlayerEntity player) {
        recoveryTicks++;
        NavigationController.INSTANCE.tickRecovery(client, recoveryTicks);
        if (recoveryTicks < 12) return;

        NavigationController.INSTANCE.stop(client);
        recoveryTicks = 0;
        recoveryAttempts++;

        if (navTarget == null || recoveryAttempts > 2 || !renavigate(client, player, navTarget)) {
            blacklist(navTarget, 20_000);
            navTarget = null;
            recoveryAttempts = 0;
            state = State.SCANNING;
        } else {
            state = State.NAVIGATING;
        }
    }

    /** Re-solve the schematic block at {@code nt} and start navigating to it. */
    private boolean renavigate(MinecraftClient client, ClientPlayerEntity player, BlockPos nt) {
        var schem = SchematicWorldHandler.getSchematicWorld();
        if (schem == null) return false;
        BlockState want = schem.getBlockState(nt);
        if (want.isAir()) return false;
        PlacementSolution sol = BlockPlacementMath.solve(client.world, player, nt, want, HAND);
        if (sol == null) return false;
        return startNavigation(client, player, new Target(nt, want), sol);
    }

    // ---------------------------------------------------------------------
    //  Build
    // ---------------------------------------------------------------------

    private void tickFetching(MinecraftClient client) {
        if (System.currentTimeMillis() - fetchStartMs > 3000L) {
            // Couldn't get the item in hand in time — skip so we don't hang here.
            diagFetchFail++;
            if (target != null) blacklist(target.pos(), 8_000);
            abortTarget();
            return;
        }
        try {
            if (InventoryManager.INSTANCE.tick(client) == InventoryManager.Status.DONE) {
                state = State.PATHING_TO_ANCHOR;
            }
        } catch (MissingItemException e) {
            diagFetchFail++;
            abortTarget();
        }
    }

    private void tickPathing(MinecraftClient client) {
        if (solution != null && solution.requiresSneak()) {
            InputSimulator.setSneak(client, true);
        }
        beginAimAtSolution(client);
        state = State.AIMING;
    }

    private void beginAimAtSolution(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || solution == null) return;
        // Always look at the block being placed (natural, and — because we only
        // build from a spot where looking at it yields the right facing — this
        // also produces the correct orientation for directional blocks).
        Vec3d eye = player.getEyePos();
        float[] yp = HumanAimController.lookAt(eye, solution.targetCenter());
        HumanAimController.INSTANCE.beginAim(player, yp[0], yp[1]);
    }

    private void tickAiming(MinecraftClient client) {
        HumanAimController aim = HumanAimController.INSTANCE;
        boolean settled = aim.tick(client);

        if (!settled && misclickPending && aim.inPrimaryPhase() && aim.currentProgress() >= 0.9) {
            misclickPending = false;
            aim.cancel();
            beginClicking(client);
            return;
        }
        if (settled) {
            state = State.VERIFYING_PHYSICS;
        }
    }

    private void tickVerifying(MinecraftClient client, ClientPlayerEntity player) {
        if (solution == null) { abortTarget(); return; }
        // Non-strict placement re-check only. The eye→hit raycast that used to
        // gate this rejected valid floor-level placements (the ray grazes the
        // surface), and it isn't needed since we place with the explicit hit
        // result rather than the crosshair.
        boolean ok = BlockPlacementMath.confirm(client.world, player, target.state(), HAND, solution);
        if (ok) {
            beginClicking(client);
        } else {
            diagVerifyFail++;
            abortTarget();
        }
    }

    private void beginClicking(MinecraftClient client) {
        long delay = 0L;
        if (StochasticEngine.INSTANCE.rollHesitation()) {
            delay = (long) StochasticEngine.INSTANCE.hesitationMs();
        }
        waitUntilMs = System.currentTimeMillis() + delay;
        state = State.CLICKING;
    }

    private void tickClicking(MinecraftClient client) {
        if (System.currentTimeMillis() < waitUntilMs) return;
        performPlacement(client);
        double factor = TPSMonitor.INSTANCE.getDelayFactor();
        waitUntilMs = System.currentTimeMillis()
                + (long) StochasticEngine.INSTANCE.placementCooldownMs(factor);
        state = State.COOLDOWN;
    }

    /**
     * Place the block via the interaction manager using the exact hit result we
     * computed. This is the game's own placement path (it does not construct a
     * packet on our behalf) and it does not depend on the crosshair happening to
     * land on the block, so it is far more reliable than driving the use key.
     */
    private void performPlacement(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || solution == null || client.interactionManager == null) return;
        diagPlaceTry++;
        BlockHitResult hit = new BlockHitResult(
                solution.hitVec(), solution.side(), solution.anchorPos(), false);
        client.interactionManager.interactBlock(player, HAND, hit);
        player.swingHand(HAND); // cosmetic arm swing (ActionResult is a sealed type now)
        // Some blocks (signs, etc.) open an edit screen on placement; allow the
        // screen guard to auto-close it for a short window so we don't hang.
        allowScreenCloseUntil = System.currentTimeMillis() + 800L;
    }

    private void tickCooldown(MinecraftClient client, ClientPlayerEntity player) {
        if (System.currentTimeMillis() < waitUntilMs) return;

        boolean placed = false;
        if (target != null && client.world != null) {
            var now = client.world.getBlockState(target.pos());
            placed = !now.isAir() && now.getBlock() == target.state().getBlock();
        }
        if (buildingScaffold) {
            // Scaffold block: track separately; a failure aborts the staircase so
            // we don't loop, and it isn't counted as schematic progress.
            if (placed) Scaffolder.INSTANCE.notePlaced();
            else { Scaffolder.INSTANCE.reset(); scaffoldGoal = null; }
        } else if (target != null) {
            long key = target.pos().asLong();
            if (placed) {
                placedCount++;
                placeFails.remove(key);
            } else {
                diagPlaceFail++;
                // Repeated placement failure → blacklist so we don't loop forever.
                int fails = placeFails.merge(key, 1, Integer::sum);
                if (fails >= 4) {
                    blacklist(target.pos(), 30_000);
                    placeFails.remove(key);
                }
            }
        }
        InputSimulator.setSneak(client, false);
        clearTarget();
        state = State.SCANNING;
    }

    private void enterPausedLag(MinecraftClient client) {
        releaseAllInput(client);
        state = State.PAUSED_LAG;
    }

    // ---------------------------------------------------------------------
    //  Blacklist
    // ---------------------------------------------------------------------

    private boolean blacklisted(BlockPos p) {
        Long until = blacklistUntil.get(p.asLong());
        if (until == null) return false;
        if (System.currentTimeMillis() >= until) {
            blacklistUntil.remove(p.asLong());
            return false;
        }
        return true;
    }

    private void blacklist(BlockPos p, long ms) {
        if (p != null) blacklistUntil.put(p.asLong(), System.currentTimeMillis() + ms);
    }

    // ---------------------------------------------------------------------
    //  Lifecycle
    // ---------------------------------------------------------------------

    private void startup() {
        controlling = true;
        sessionStartMs = System.currentTimeMillis();
        placedCount = 0;
        lastReportMs = 0L;
        blacklistUntil.clear();
        placeFails.clear();
        targetCache.clear();
        lastScanMs = 0L;
        Scaffolder.INSTANCE.reset();
        scaffoldGoal = null;
        scaffoldAttempts = 0;
        diagSolveNull = diagFetchFail = diagVerifyFail = diagPlaceTry = diagPlaceFail = 0;
        StochasticEngine.INSTANCE.onActivate();
        state = State.SCANNING;
    }

    private void shutdown(MinecraftClient client) {
        releaseAllInput(client);
        StochasticEngine.INSTANCE.onDeactivate();
        controlling = false;
        navTarget = null;
        clearTarget();
        state = State.IDLE;
    }

    /** External reset (toggle off, disconnect). */
    public void reset() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client != null) releaseAllInput(client);
        StochasticEngine.INSTANCE.onDeactivate();
        TPSMonitor.INSTANCE.reset();
        controlling = false;
        navTarget = null;
        blacklistUntil.clear();
        placeFails.clear();
        targetCache.clear();
        lastScanMs = 0L;
        Scaffolder.INSTANCE.reset();
        scaffoldGoal = null;
        scaffoldAttempts = 0;
        clearTarget();
        state = State.IDLE;
    }

    private void releaseAllInput(MinecraftClient client) {
        InputSimulator.releaseAll(client);
        NavigationController.INSTANCE.stop(client);
        HumanAimController.INSTANCE.cancel();
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
        buildingScaffold = false;
    }

    // ---------------------------------------------------------------------
    //  Build-time estimation reporting
    // ---------------------------------------------------------------------

    private void maybeReportEstimate(MinecraftClient client, ClientPlayerEntity player) {
        if (!BuilderConfig.INSTANCE.reportEstimate) return;
        long now = System.currentTimeMillis();
        if (now - lastReportMs < 5000L) return;
        lastReportMs = now;

        String scope = BuilderConfig.INSTANCE.buildBounds != null
                ? "§a" + BuilderConfig.INSTANCE.selectedSchematic
                : "§eall";
        // When nothing has been placed yet, show why (scan breakdown + per-stage
        // failure counters) for diagnosis.
        String extra = placedCount == 0
                ? " §7| §f" + SchematicBridge.INSTANCE.getLastStats().summary()
                  + String.format(" §7| §fsolveN §c%d §ffetchF §c%d §fvFail §c%d §fplace §a%d§7/§c%d",
                        diagSolveNull, diagFetchFail, diagVerifyFail, diagPlaceTry, diagPlaceFail)
                : "";
        String msg = String.format(
                "§b[HB] §f%s §7| placed §f%d §7| TPS §f%.1f §7| %s%s",
                state, placedCount, TPSMonitor.INSTANCE.getTps(), scope, extra);
        player.sendMessage(Text.literal(msg), true);
    }

    private static void message(MinecraftClient client, String msg) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal(msg), true);
        }
    }
}
