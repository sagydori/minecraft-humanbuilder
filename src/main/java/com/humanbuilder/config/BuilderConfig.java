package com.humanbuilder.config;

/**
 * Central tunables. Kept as a plain singleton of primitive fields so the core
 * mod compiles and runs with zero dependency on Cloth Config; the optional
 * {@link ModMenuIntegration} screen simply reads/writes these fields.
 *
 * <p>All timing values are in milliseconds unless noted. The defaults reproduce
 * the models specified in the project blueprint (Section 3).</p>
 */
public final class BuilderConfig {

    public static final BuilderConfig INSTANCE = new BuilderConfig();

    private BuilderConfig() {}

    // --- General ---
    /** Master enable. Toggled by the keybind. (Runtime only — not persisted.) */
    public transient boolean enabled = false;
    /** Fallback scan radius around the player when no placement bounds are set. */
    public double scanRadius = 64.0;
    /** Max candidate targets collected per full scan (bottom-up). */
    public int maxCandidates = 128;
    /** Safety cap on blocks examined per full scan. */
    public int maxScanIterations = 400_000;

    // --- Placement ordering ---
    /** Cover each layer in a boustrophedon (serpentine "lawnmower") sweep instead
     *  of a greedy nearest-block hop — minimises walking, turns and backtracking.
     *  The optimal known pattern for covering a 2D grid; also looks deliberate. */
    public boolean serpentineSweep = true;
    /** Within a layer, finish the solid full-cube mass before slabs/stairs/fences
     *  and true attachables (torches, rails, carpets) — supports always exist
     *  first, exactly how a person rough-builds then details. */
    public boolean structuralFirst = true;
    /** When walking, go to the vantage that reaches the MOST remaining blocks and
     *  place the whole cluster from there (Baritone "do the most work per spot"),
     *  instead of walking to a single block's neighbour and hopping block-to-block.
     *  This is the biggest speed win: far fewer walk legs. */
    public boolean coverageStanding = true;
    /** Swallow real mouse input while the bot is active. */
    public boolean blockUserInput = true;

    // --- Aim realism (cosmetic; does not affect build-time estimate) ---
    public boolean enableTremor = true;
    public boolean enableOvershoot = true;
    public boolean enableQuantization = true;
    /** Probability [0..1] that a given aim overshoots before correcting. */
    public double overshootChance = 0.40;

    // --- Stochastic delay engine ---
    /** Mean of the Gaussian per-placement cooldown, ms. */
    public double clickDelayMeanMs = 240.0;
    /** Std-dev of the Gaussian per-placement cooldown, ms. */
    public double clickDelayStdMs = 55.0;
    /** Overlap the next block's aim with the previous placement's cooldown (a
     *  person moves their crosshair to the next spot while the last block
     *  registers) instead of serialising wait-then-aim. Keeps the click cadence
     *  as a floor, so it's both faster and more realistic. */
    public boolean pipelineAim = true;
    /** Inventory swap click cadence (blueprint: mean 220, std 40). */
    public double invClickMeanMs = 220.0;
    public double invClickStdMs = 40.0;
    /** Fatigue: multiplier grows this much per minute... */
    public double fatiguePerMinute = 0.01;
    /** ...capped here (blueprint: 1.4). */
    public double fatigueCap = 1.4;
    /** Human error rates (blueprint: 0.5% misclick, 1.5% hesitation). */
    public double misclickChance = 0.005;
    public double hesitationChance = 0.015;

    // --- TPS compensation ---
    /** Below this TPS the bot pauses entirely (blueprint: 10). */
    public double pauseBelowTps = 10.0;

    // --- Navigation / pathfinding ---
    /** Master enable for autonomous movement to out-of-reach targets. */
    public boolean enableNavigation = true;
    /** Draw the current path as a red line in the world. */
    public boolean showPath = true;
    /** Targets farther than this (blocks, eye→target) trigger NAVIGATING. */
    public double navReach = 3.5;
    /** Maximum drop height A* will path down in one step. Kept at 1 so every
     *  descent is reversible (a 1-block jump back up) — it never drops into a
     *  pit it can't climb out of. */
    public int maxFallDistance = 1;
    /** Safety cap on A* node expansions (keeps it "lightweight"). */
    public int maxPathIterations = 4000;
    /** Camera turn rate while navigating, degrees per tick. */
    public double turnSpeedDegPerTick = 22.0;
    /** Walk the Baritone-planned route like a person, not on rails: a slow weave,
     *  fine view tremor, non-uniform steering and a subtle head bob (damped near
     *  waypoints/ledges for safety). Cosmetic — does not change the route. */
    public boolean humanizeWalk = true;
    /** Peak weave (degrees) added to the walk heading when humanizeWalk is on. */
    public double walkWanderDeg = 5.0;

    // --- Scaffolding (temporary staircase to reach higher layers) ---
    /** Build a temporary staircase when an upper target can't be reached/pathed to. */
    public boolean enableScaffolding = true;
    /** Safety cap on scaffold blocks placed per activation. */
    public int maxScaffoldBlocks = 64;

    // --- Schematic selection (set by the in-game menu; runtime only) ---
    /** If non-null, only build blocks inside this world-space box (the chosen placement). */
    public transient net.minecraft.util.math.Box buildBounds = null;
    /** Name of the chosen schematic placement, for display. */
    public transient String selectedSchematic = null;

    // --- Safety / debug ---
    /** Show the on-screen HUD overlay (state, throughput, remaining, ETA). */
    public boolean showHud = true;
    /** If true (and HUD off), print status to the action bar periodically. */
    public boolean reportEstimate = true;

    // ---------------------------------------------------------------------
    //  Persistence (config/humanbuilder.json) — transient fields are skipped.
    // ---------------------------------------------------------------------

    private static java.nio.file.Path file() {
        return net.fabricmc.loader.api.FabricLoader.getInstance()
                .getConfigDir().resolve("humanbuilder.json");
    }

    public static void load() {
        try {
            java.nio.file.Path f = file();
            if (!java.nio.file.Files.exists(f)) { save(); return; }
            BuilderConfig loaded = new com.google.gson.Gson()
                    .fromJson(java.nio.file.Files.readString(f), BuilderConfig.class);
            if (loaded == null) return;
            for (java.lang.reflect.Field field : BuilderConfig.class.getDeclaredFields()) {
                int m = field.getModifiers();
                if (java.lang.reflect.Modifier.isStatic(m)
                        || java.lang.reflect.Modifier.isTransient(m)
                        || java.lang.reflect.Modifier.isFinal(m)) continue;
                field.setAccessible(true);
                field.set(INSTANCE, field.get(loaded));
            }
        } catch (Throwable t) {
            System.err.println("[HumanBuilder] Failed to load config: " + t);
        }
    }

    public static void save() {
        try {
            String json = new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(INSTANCE);
            java.nio.file.Files.writeString(file(), json);
        } catch (Throwable t) {
            System.err.println("[HumanBuilder] Failed to save config: " + t);
        }
    }
}
