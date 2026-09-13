package com.humanbuilder.state;

import com.humanbuilder.HumanBuilderClient;
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
import net.minecraft.util.math.Direction;
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
    private int currentLayerY = Integer.MIN_VALUE;
    /** Last block successfully placed on the current layer — anchors a connected sweep. */
    private BlockPos lastPlacedPos;

    // Boustrophedon (serpentine) sweep state, recomputed when the layer changes.
    private boolean sweepRunAxisX;                       // rows run along X (indexed by Z)?
    private boolean sweepFlipRow, sweepFlipRun;          // start the sweep near the player
    private int sweepMinX, sweepMinZ, sweepMaxX, sweepMaxZ;
    /** Sweep-order key of the last placed block; the next pick advances past it. */
    private long lastPlacedKey = Long.MIN_VALUE;

    // Scaffolding.
    private boolean buildingScaffold;
    private BlockPos scaffoldGoal;
    private int scaffoldAttempts;

    /** Temporary skip-lists keyed by BlockPos.asLong() → expiry epoch-ms / fail count. */
    private final Map<Long, Long> blacklistUntil = new HashMap<>();
    private final Map<Long, Integer> placeFails = new HashMap<>();

    private long waitUntilMs = 0L;
    /** Earliest time the next placement click may fire (enforces the human cadence
     *  while the aim for that block overlaps the previous placement's cooldown). */
    private long nextPlaceAllowedMs = 0L;
    private long fetchStartMs = 0L;
    /** Grace window after a placement during which a popped-up screen is auto-closed. */
    private long allowScreenCloseUntil = 0L;

    // Per-stage diagnostic counters (surfaced in the status line while placed==0).
    private int diagSolveNull, diagFetchFail, diagVerifyFail, diagPlaceTry, diagPlaceFail;

    // Safety / watchdog.
    private int errorCount;
    private long lastProgressMs;
    private long unstickUntil;
    private int unstickTicks;
    private long lastLowHealthMsg;
    private boolean idleReported;
    private boolean paused;

    // Whole-build ETA.
    private int remainingEstimate = -1;
    private long lastCountMs;

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

    public boolean isActive() {
        return controlling && BuilderConfig.INSTANCE.enabled;
    }

    public boolean isPaused() {
        return paused;
    }

    /** Toggle a soft pause (keeps state; releases input). Returns the new state. */
    public boolean togglePause() {
        paused = !paused;
        if (paused) {
            MinecraftClient c = MinecraftClient.getInstance();
            if (c != null) releaseAllInput(c);
        }
        return paused;
    }

    /** World-space centre of the block currently being built or walked to (for the red line), or null. */
    public Vec3d getActiveTargetCenter() {
        if (solution != null) return solution.targetCenter();
        if (navTarget != null) {
            return new Vec3d(navTarget.getX() + 0.5, navTarget.getY() + 0.5, navTarget.getZ() + 0.5);
        }
        return null;
    }

    /** Session build progress [0..1], or -1 if unknown. */
    public double progressFraction() {
        if (remainingEstimate < 0) return -1;
        int total = placedCount + remainingEstimate;
        return total > 0 ? (double) placedCount / total : 1.0;
    }

    /** Lines for the on-screen HUD (state, throughput, remaining, ETA). */
    public java.util.List<String> statusLines() {
        java.util.List<String> out = new java.util.ArrayList<>(5);
        long now = System.currentTimeMillis();
        double elapsedSec = Math.max(0.001, (now - sessionStartMs) / 1000.0);
        double perMin = placedCount / (elapsedSec / 60.0);
        double secPer = placedCount > 0 ? elapsedSec / placedCount : 0.0;

        out.add("§b§lHumanBuilder §r" + (paused ? "§e[PAUSED] §7" : "§7") + state);
        out.add("§7placed §f" + placedCount + " §7(§f" + String.format("%.1f", perMin) + "§7/min)");

        String rem = remainingEstimate < 0 ? "…"
                : (SchematicBridge.INSTANCE.lastRemainingCapped ? "≥" : "~") + remainingEstimate;
        String eta = (placedCount >= 10 && remainingEstimate > 0)
                ? fmtDuration((long) (remainingEstimate * secPer)) : "—";
        out.add("§7remaining §f" + rem + "   §7ETA §f" + eta);

        if (placedCount == 0) {
            out.add("§8" + SchematicBridge.INSTANCE.getLastStats().summary());
        }
        out.add("§7TPS §f" + String.format("%.1f", TPSMonitor.INSTANCE.getTps())
                + (BuilderConfig.INSTANCE.buildBounds != null
                        ? "  §7| §a" + BuilderConfig.INSTANCE.selectedSchematic : ""));
        return out;
    }

    private static String fmtDuration(long secs) {
        if (secs < 0) secs = 0;
        long h = secs / 3600, m = (secs % 3600) / 60, s = secs % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }

    // ---------------------------------------------------------------------
    //  Main tick
    // ---------------------------------------------------------------------

    public void onClientTick(MinecraftClient client) {
        // Never let a bug crash the game's client tick: contain and recover.
        try {
            tickInternal(client);
        } catch (Throwable t) {
            errorCount++;
            if (errorCount <= 3) {
                HumanBuilderClient.LOGGER.error("HumanBuilder tick error (recovering)", t);
            }
            try { releaseAllInput(client); } catch (Throwable ignored) {}
            clearTarget();
            state = State.SCANNING;
            if (errorCount > 30) { // persistent failure — stop rather than spam
                BuilderConfig.INSTANCE.enabled = false;
                controlling = false;
                message(client, "§cHumanBuilder disabled after repeated errors (see log)");
            }
        }
    }

    private void tickInternal(MinecraftClient client) {
        BuilderConfig cfg = BuilderConfig.INSTANCE;

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

        // Soft pause: hold everything but keep state so we can resume.
        if (paused) {
            releaseAllInput(client);
            return;
        }

        long now = System.currentTimeMillis();

        // Survival safety: pause (don't die) while health is low; resume when healed.
        if (!player.getAbilities().creativeMode && player.getHealth() <= 6.0f) {
            releaseAllInput(client);
            if (now - lastLowHealthMsg > 5000L) {
                message(client, "§eHumanBuilder paused — low health");
                lastLowHealthMsg = now;
            }
            return;
        }

        // Brief physical un-stick nudge (from the watchdog): back up and hop.
        if (now < unstickUntil) {
            NavigationController.INSTANCE.tickRecovery(client, unstickTicks++);
            return;
        }

        TPSMonitor.INSTANCE.update(client.world);
        double tps = TPSMonitor.INSTANCE.getTps();
        if (tps < cfg.pauseBelowTps) {
            enterPausedLag(client);
            return;
        } else if (state == State.PAUSED_LAG) {
            state = State.SCANNING;
        }

        // Periodically estimate remaining blocks for the whole-build ETA.
        if (now - lastCountMs > 15_000L) {
            remainingEstimate = SchematicBridge.INSTANCE.countRemaining(client);
            lastCountMs = now;
        }

        // Watchdog: if there is work but no placement progress for a while, we are
        // wedged — clear skip-lists, drop caches, and physically nudge to recover.
        if (now - lastProgressMs > 25_000L
                && SchematicBridge.INSTANCE.getLastStats().candidates > 0) {
            blacklistUntil.clear();
            placeFails.clear();
            targetCache.clear();
            Scaffolder.INSTANCE.reset();
            scaffoldGoal = null;
            scaffoldAttempts = 0;
            lastProgressMs = now;
            unstickUntil = now + 700L;
            unstickTicks = 0;
            NavigationController.INSTANCE.stop(client);
            HumanAimController.INSTANCE.cancel();
            state = State.SCANNING;
            return;
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

        Vec3d eye = player.getEyePos();

        // STRICT layer-by-layer: the true lowest unfinished Y of the entire build
        // (not just the cached candidates), so a higher layer is never touched
        // until the one below it is finished. Scans from the last layer upward.
        int layerY = SchematicBridge.INSTANCE.lowestRemainingY(client, currentLayerY);
        if (layerY == Integer.MAX_VALUE) {
            layerY = SchematicBridge.INSTANCE.lowestRemainingY(client, Integer.MIN_VALUE);
        }
        if (layerY == Integer.MAX_VALUE) {
            currentLayerY = Integer.MIN_VALUE;
            if (!idleReported) {
                message(client, "§aHumanBuilder: nothing left to build ("
                        + SchematicBridge.INSTANCE.getLastStats().summary() + ")");
                idleReported = true;
            }
            return;
        }
        if (layerY != currentLayerY) {
            currentLayerY = layerY;
            lastPlacedPos = null;                 // new layer → sweep restarts from the player
            lastPlacedKey = Long.MIN_VALUE;
            if (cfg.serpentineSweep) configureSweep(client, player);
        }
        idleReported = false;

        // Structural-first: while any solid full-cube block remains on this layer,
        // restrict selection to those (walls before slabs/stairs/torches/rails),
        // so a block's support always exists before its detail — fewer failed
        // placements and no revisiting. Flips to details once the mass is done.
        boolean structuralPhase = cfg.structuralFirst && hasStructuralRemaining(layerY);

        // Pass A — group building: place everything reachable on THIS layer from
        // where we stand, before walking. Reachable blocks are chosen in sweep
        // order (the next block along the boustrophedon path) so consecutive
        // placements form a contiguous run, minimising aim travel; without the
        // sweep we fall back to nearest-to-last-placed.
        int checked = 0;
        boolean anyLayerInCache = false;
        Vec3d anchor = anchorPoint(player, layerY);
        Target bestReach = null;
        PlacementSolution bestReachSol = null;
        double bestReachD = Double.MAX_VALUE;
        long bestReachKey = Long.MAX_VALUE;
        boolean bestReachFwd = false;
        for (Target t : targetCache) {
            if (t.pos().getY() != layerY) continue;
            anyLayerInCache = true;
            if (blacklisted(t.pos())) continue;
            if (structuralPhase && !isStructural(client, t)) continue;
            if (checked++ >= 60) break;
            PlacementSolution s = BlockPlacementMath.solve(client.world, player, t.pos(), t.state(), HAND);
            if (s == null) continue;
            if (BlockPlacementMath.reachable(client.world, player, s)
                    && BlockPlacementMath.facingOkFrom(player, HAND, t.state(), s, eye)) {
                if (cfg.serpentineSweep) {
                    long k = sweepKey(t.pos());
                    boolean fwd = k > lastPlacedKey;
                    if (isBetterInSweep(fwd, k, bestReach != null, bestReachFwd, bestReachKey)) {
                        bestReachFwd = fwd; bestReachKey = k; bestReach = t; bestReachSol = s;
                    }
                } else {
                    double d = t.pos().getSquaredDistance(anchor.x, anchor.y, anchor.z);
                    if (d < bestReachD) { bestReachD = d; bestReach = t; bestReachSol = s; }
                }
            }
        }
        if (bestReach != null) {
            beginBuild(bestReach, bestReachSol, now, false);
            targetCache.remove(bestReach);
            return;
        }

        // Pass B — nothing in reach: walk to the next block along the sweep. In
        // serpentine mode that's the next boustrophedon cell after the last one we
        // placed (finish the row, then step to the next), which eliminates the
        // back-and-forth of a pure nearest-block hop. Without the sweep we fall
        // back to a greedy nearest-neighbour tour from the player's CURRENT spot,
        // biased toward blocks already touching built structure (grow a frontier).
        Target chosen = null;
        double bestD = Double.MAX_VALUE;
        long bestKey = Long.MAX_VALUE;
        boolean bestFwd = false;
        Vec3d from = new Vec3d(player.getX(), player.getY(), player.getZ());
        java.util.List<BlockPos> layerPositions = new java.util.ArrayList<>();
        for (Target t : targetCache) {
            if (t.pos().getY() != layerY || blacklisted(t.pos())) continue;
            if (structuralPhase && !isStructural(client, t)) continue;
            layerPositions.add(t.pos());
            if (cfg.serpentineSweep) {
                long k = sweepKey(t.pos());
                boolean fwd = k > lastPlacedKey;
                if (isBetterInSweep(fwd, k, chosen != null, bestFwd, bestKey)) {
                    bestFwd = fwd; bestKey = k; chosen = t;
                }
            } else {
                double d = t.pos().getSquaredDistance(from.x, from.y, from.z);
                if (touchesBuilt(client, t.pos())) d *= 0.5;
                if (d < bestD) { bestD = d; chosen = t; }
            }
        }
        if (chosen != null) {
            PlacementSolution sol = BlockPlacementMath.solve(
                    client.world, player, chosen.pos(), chosen.state(), HAND);
            if (sol == null) {
                diagSolveNull++;
                noteFailure(chosen.pos());
                targetCache.remove(chosen);
                return;
            }
            boolean overlaps = BlockPlacementMath.overlapsPlayer(player, sol);
            // Baritone-style: prefer walking to the vantage that reaches the most
            // remaining blocks (place a whole cluster per stop) over walking to
            // this single block's neighbour. Falls back to per-target navigation.
            if (cfg.enableNavigation && cfg.coverageStanding
                    && startCoverageNavigation(client, player, chosen, layerPositions)) {
                return;
            }
            if (cfg.enableNavigation && startNavigation(client, player, chosen, sol)) {
                Scaffolder.INSTANCE.reset();
                scaffoldGoal = null;
                navTarget = chosen.pos();
                recoveryAttempts = 0;
                state = State.NAVIGATING;
                return;
            }
            if (overlaps) { forceSidestep(client); return; }
            if (tryScaffold(client, player, chosen)) return;
            noteFailure(chosen.pos());
            targetCache.remove(chosen);
            return;
        }

        // No buildable candidate for this layer in the cache.
        if (anyLayerInCache) return; // some exist but are temporarily blacklisted — wait

        // Refresh once; if the layer still has no anchorable candidate, its
        // remaining blocks can't be placed (e.g. floating) — skip them so we
        // don't deadlock, and re-evaluate the layer from the bottom next tick.
        targetCache.clear();
        targetCache.addAll(SchematicBridge.INSTANCE.scan(client));
        lastScanMs = now;
        for (Target t : targetCache) {
            if (t.pos().getY() == layerY && !blacklisted(t.pos())) return; // found some now
        }
        int skipped = SchematicBridge.INSTANCE.skipRemainingAt(client, layerY);
        if (skipped > 0) {
            message(client, "§eHumanBuilder: skipped " + skipped + " unbuildable block(s) at Y=" + layerY);
        }
        currentLayerY = Integer.MIN_VALUE;
    }

    /**
     * The point a connected sweep grows from: the centre of the last block we
     * placed on this layer, or the player's position when the layer just started.
     */
    private Vec3d anchorPoint(ClientPlayerEntity player, int layerY) {
        if (lastPlacedPos != null && lastPlacedPos.getY() == layerY) {
            return new Vec3d(lastPlacedPos.getX() + 0.5, lastPlacedPos.getY() + 0.5,
                    lastPlacedPos.getZ() + 0.5);
        }
        return new Vec3d(player.getX(), player.getY(), player.getZ());
    }

    /** True if any horizontal neighbour is already a solid (built) block. */
    private static boolean touchesBuilt(MinecraftClient client, BlockPos p) {
        if (client.world == null) return false;
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockState ns = client.world.getBlockState(p.offset(d));
            if (!ns.isAir() && !ns.isReplaceable()) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------------
    //  Boustrophedon (serpentine) sweep ordering
    // ---------------------------------------------------------------------

    /**
     * Orient this layer's lawnmower sweep: run along the longer horizontal axis
     * (fewer rows → fewer turns) and start from the corner nearest the player, so
     * the sweep begins where they already stand rather than a fixed corner.
     */
    private void configureSweep(MinecraftClient client, ClientPlayerEntity player) {
        int[] r = SchematicBridge.INSTANCE.buildRegion(client);
        if (r != null) {
            sweepMinX = r[0]; sweepMinZ = r[2]; sweepMaxX = r[3]; sweepMaxZ = r[5];
        } else {
            sweepMinX = sweepMaxX = (int) Math.floor(player.getX());
            sweepMinZ = sweepMaxZ = (int) Math.floor(player.getZ());
        }
        double px = player.getX(), pz = player.getZ();
        sweepRunAxisX = (sweepMaxX - sweepMinX) >= (sweepMaxZ - sweepMinZ);
        if (sweepRunAxisX) {
            sweepFlipRow = Math.abs(pz - sweepMaxZ) < Math.abs(pz - sweepMinZ);
            sweepFlipRun = Math.abs(px - sweepMaxX) < Math.abs(px - sweepMinX);
        } else {
            sweepFlipRow = Math.abs(px - sweepMaxX) < Math.abs(px - sweepMinX);
            sweepFlipRun = Math.abs(pz - sweepMaxZ) < Math.abs(pz - sweepMinZ);
        }
    }

    /** Position of {@code p} along the serpentine path: earlier key = built sooner. */
    private long sweepKey(BlockPos p) {
        int row, run, width;
        if (sweepRunAxisX) {
            row = sweepFlipRow ? (sweepMaxZ - p.getZ()) : (p.getZ() - sweepMinZ);
            int x = sweepFlipRun ? (sweepMaxX - p.getX()) : (p.getX() - sweepMinX);
            width = sweepMaxX - sweepMinX + 1;
            run = (row & 1) == 0 ? x : (width - 1 - x); // reverse direction every other row
        } else {
            row = sweepFlipRow ? (sweepMaxX - p.getX()) : (p.getX() - sweepMinX);
            int z = sweepFlipRun ? (sweepMaxZ - p.getZ()) : (p.getZ() - sweepMinZ);
            width = sweepMaxZ - sweepMinZ + 1;
            run = (row & 1) == 0 ? z : (width - 1 - z);
        }
        return (long) row * (width + 1L) + run;
    }

    /**
     * Sweep comparator: a candidate strictly ahead of the last placement (key >
     * lastPlacedKey) always beats one that isn't; within the same "ahead" class
     * the smaller key wins. So we walk forward through the path and only wrap to
     * the earliest remaining block once nothing ahead is left.
     */
    private static boolean isBetterInSweep(boolean fwd, long key,
                                           boolean haveBest, boolean bestFwd, long bestKey) {
        if (!haveBest) return true;
        if (fwd != bestFwd) return fwd;
        return key < bestKey;
    }

    /** Solid full cube → structural mass; slabs/stairs/fences/attachables are not. */
    private static boolean isStructural(MinecraftClient client, Target t) {
        try {
            return t.state().isFullCube(client.world, t.pos());
        } catch (Throwable ignore) {
            return true; // if unsure, treat as structural (build it early)
        }
    }

    /** True while the current layer still has any buildable structural candidate. */
    private boolean hasStructuralRemaining(int layerY) {
        MinecraftClient client = MinecraftClient.getInstance();
        for (Target t : targetCache) {
            if (t.pos().getY() == layerY && isStructural(client, t)) return true;
        }
        return false;
    }

    /** Count a failure for a block; permanently skip it after repeated failures. */
    private void noteFailure(BlockPos p) {
        long k = p.asLong();
        int f = placeFails.merge(k, 1, Integer::sum);
        if (f >= 6) {
            SchematicBridge.INSTANCE.addSkip(p); // give up so the layer can complete
            placeFails.remove(k);
        } else {
            blacklist(p, 5_000);
        }
    }

    /** Physically back away for a moment (used to vacate a block we're standing in). */
    private void forceSidestep(MinecraftClient client) {
        unstickUntil = System.currentTimeMillis() + 500L;
        unstickTicks = 0;
        NavigationController.INSTANCE.stop(client);
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

    /**
     * Baritone-style destination planning. Rather than greedily walking to one
     * block's neighbour, it builds the GoalComposite — every standable cell from
     * which some remaining layer block is placeable — and A*'s to the nearest of
     * them by real movement cost, preferring productive vantages (reach ≥ 2
     * blocks) so a whole cluster is placed per stop. The block actually built is
     * emergent: whatever is placeable once we arrive. Falls back to a single
     * coverage vantage, then to per-target navigation. {@code navTarget} stays the
     * sweep block so arrival re-evaluation and blacklisting still work.
     */
    private boolean startCoverageNavigation(MinecraftClient client, ClientPlayerEntity player,
                                            Target chosen, java.util.List<BlockPos> layerPositions) {
        if (layerPositions.size() < 2) return false; // no cluster to batch — use per-target nav
        double reach = player.getBlockInteractionRange() - 0.3;
        BlockPos feet = player.getBlockPos();

        // Heuristic focus = the remaining block nearest the player.
        layerPositions.sort(java.util.Comparator.comparingDouble(p -> p.getSquaredDistance(feet)));
        BlockPos focus = layerPositions.get(0);

        // Prefer the nearest *productive* vantage; fall back to any placeable cell.
        java.util.Set<Long> rich = Pathfinder.placeableStands(client.world, layerPositions, feet, reach, 2);
        List<BlockPos> path = Pathfinder.pathToNearestStand(client.world, feet, rich, focus);
        if (path.isEmpty()) {
            java.util.Set<Long> any = Pathfinder.placeableStands(client.world, layerPositions, feet, reach, 1);
            path = Pathfinder.pathToNearestStand(client.world, feet, any, focus);
        }
        if (path.isEmpty()) {
            // Fallback: a single high-coverage vantage via a straight path.
            BlockPos stand = Pathfinder.findCoverageStand(client.world, layerPositions, feet, reach);
            if (stand == null || stand.equals(feet)) return false;
            path = Pathfinder.findPath(client.world, feet, stand);
            if (path.isEmpty()) return false;
        }
        NavigationController.INSTANCE.setPath(path);
        Scaffolder.INSTANCE.reset();
        scaffoldGoal = null;
        navTarget = chosen.pos();
        recoveryAttempts = 0;
        state = State.NAVIGATING;
        return true;
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
        lastProgressMs = System.currentTimeMillis(); // movement counts as progress
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
        long now = System.currentTimeMillis();
        long delay = 0L;
        if (StochasticEngine.INSTANCE.rollHesitation()) {
            delay = (long) StochasticEngine.INSTANCE.hesitationMs();
        }
        waitUntilMs = now + delay;
        // Pipelining: this block's aim already ran while the previous placement's
        // cadence was elapsing, so here we only wait out any remaining inter-click
        // interval (and the rare hesitation). This overlaps the slow aim with the
        // cooldown — how a person moves to the next spot while the last block
        // registers — rather than serialising wait-then-aim.
        if (BuilderConfig.INSTANCE.pipelineAim) {
            waitUntilMs = Math.max(waitUntilMs, nextPlaceAllowedMs);
        }
        state = State.CLICKING;
    }

    private void tickClicking(MinecraftClient client) {
        if (System.currentTimeMillis() < waitUntilMs) return;
        if (!performPlacement(client)) return; // skipped (e.g. would place into self) — state already set
        double factor = TPSMonitor.INSTANCE.getDelayFactor();
        long cd = (long) StochasticEngine.INSTANCE.placementCooldownMs(factor);
        if (BuilderConfig.INSTANCE.pipelineAim) {
            // Enforce the human click cadence at the NEXT click; wait only a short
            // settle here so the placement registers for the success check while
            // the next block's fetch+aim proceeds inside the cadence window.
            nextPlaceAllowedMs = System.currentTimeMillis() + cd;
            waitUntilMs = System.currentTimeMillis() + 120L;
        } else {
            waitUntilMs = System.currentTimeMillis() + cd;
        }
        state = State.COOLDOWN;
    }

    /**
     * Place the block via the interaction manager using the exact hit result we
     * computed. This is the game's own placement path (it does not construct a
     * packet on our behalf) and it does not depend on the crosshair happening to
     * land on the block, so it is far more reliable than driving the use key.
     */
    private boolean performPlacement(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || solution == null || client.interactionManager == null) return false;
        // Guard: the player may have drifted into the target during the pipeline —
        // never place a block into our own body; step aside and retry instead.
        if (!buildingScaffold && BlockPlacementMath.overlapsPlayer(player, solution)) {
            forceSidestep(client);
            clearTarget();
            state = State.SCANNING;
            return false;
        }
        diagPlaceTry++;
        BlockHitResult hit = new BlockHitResult(
                solution.hitVec(), solution.side(), solution.anchorPos(), false);
        client.interactionManager.interactBlock(player, HAND, hit);
        player.swingHand(HAND); // cosmetic arm swing (ActionResult is a sealed type now)
        // Some blocks (signs, etc.) open an edit screen on placement; allow the
        // screen guard to auto-close it for a short window so we don't hang.
        allowScreenCloseUntil = System.currentTimeMillis() + 800L;
        return true;
    }

    private void tickCooldown(MinecraftClient client, ClientPlayerEntity player) {
        if (System.currentTimeMillis() < waitUntilMs) return;

        boolean placed = false;
        if (target != null && client.world != null) {
            var now = client.world.getBlockState(target.pos());
            placed = !now.isAir() && now.getBlock() == target.state().getBlock();
        }
        if (placed) { lastProgressMs = System.currentTimeMillis(); errorCount = 0; } // progress
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
                lastPlacedPos = target.pos(); // anchor the next placement's sweep here
                if (target.pos().getY() == currentLayerY) lastPlacedKey = sweepKey(target.pos());
            } else {
                diagPlaceFail++;
                noteFailure(target.pos()); // blacklist, then permanently skip after repeats
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
        currentLayerY = Integer.MIN_VALUE;
        lastPlacedPos = null;
        lastPlacedKey = Long.MIN_VALUE;
        nextPlaceAllowedMs = 0L;
        SchematicBridge.INSTANCE.clearSkips();
        Scaffolder.INSTANCE.reset();
        scaffoldGoal = null;
        scaffoldAttempts = 0;
        diagSolveNull = diagFetchFail = diagVerifyFail = diagPlaceTry = diagPlaceFail = 0;
        errorCount = 0;
        lastProgressMs = System.currentTimeMillis();
        unstickUntil = 0L;
        remainingEstimate = -1;
        lastCountMs = 0L;
        paused = false;
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
        currentLayerY = Integer.MIN_VALUE;
        lastPlacedPos = null;
        lastPlacedKey = Long.MIN_VALUE;
        nextPlaceAllowedMs = 0L;
        SchematicBridge.INSTANCE.clearSkips();
        Scaffolder.INSTANCE.reset();
        scaffoldGoal = null;
        scaffoldAttempts = 0;
        paused = false;
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
        if (BuilderConfig.INSTANCE.showHud) return; // HUD shows this instead
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
