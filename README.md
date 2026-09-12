# HumanBuilder

An autonomous, human-input-simulating Litematica schematic builder for
**singleplayer / your own server**, built to *estimate how long a build takes*.
Client-side only — it drives vanilla KeyBindings and the client interaction
manager; it never constructs or sends a packet.

Target: **Minecraft 1.21.11 / Fabric**, Java 21.

---

## Straight talk before you build

Two things in the original spec were technically wrong and are **fixed**, not
implemented verbatim:

1. **TPS via `WorldTimeUpdateC2SPacket`** — that packet is *clientbound* and
   Fabric can't register receivers for vanilla packets. Real fix
   (`TPSMonitor`): sample `world.getTime()` (advances only when the server
   ticks) against wall-clock over a rolling 10 s window. Works in SP and on a
   server you own.
2. **Hand-coded face targeting** (top-50%-for-upper-slab, etc.) is fragile and
   wrong for most blocks. Real fix (`BlockPlacementMath`): try candidate faces +
   sub-pixel hit vectors and validate each against **vanilla's own**
   `Block.getPlacementState(...)`. Correct for slabs, stairs, pillars,
   directional blocks. The slab/stair *vertical bias* you asked for is kept as
   the hit-vector seed.

One honest note on your goal: the **delay** modeling (Gaussian cadence, fatigue,
hesitation, TPS scaling) is what drives the time estimate — all implemented. The
**aim** realism (tremor, overshoot, GCD quantization) is cosmetic for a time
estimate; since you're on singleplayer / your own server there's no anti-cheat
to evade, so it only affects how recordings look. It's all in, per your spec —
that's just the split so you know which knobs matter for the estimate.

### Known limitation (documented, not hidden)
Blocks whose facing depends on where you look (chests, furnaces, stairs facing)
use a best-effort **front-toward-player** yaw hint; the `VERIFYING_PHYSICS`
state then re-validates strictly and **skips** a target it can't reproduce
faithfully rather than placing it wrong. For heavily orientation-dependent
builds you may need to position yourself so the natural aim yields the right
facing.

---

## Build setup

1. **Install a JDK 21** and point your IDE / `JAVA_HOME` at it.

2. **Drop the dependency jars in `./libs/`** (see the note file there):
   - `litematica-fabric-1.21.11-*.jar` (sakura-ryoko fork, 0.26.11+)
   - `malilib-fabric-1.21.11-*.jar`
   Download from Modrinth (filter Minecraft **1.21.11**, loader **Fabric**).

3. **Verify the three moving version pins** in `gradle.properties` against
   <https://fabricmc.net/develop/> and the Fabric API page — they change often:
   - `yarn_mappings`  (default `1.21.11+build.1`)
   - `loader_version` (default `0.16.14`)
   - `fabric_version` (default `0.116.0+1.21.11`)

4. Build:
   ```
   ./gradlew build
   ```
   (No wrapper jar is committed; run `gradle wrapper` once, or just import the
   project into IntelliJ IDEA and let it provide Gradle.)

5. The built mod lands in `build/libs/`. Install it alongside **Fabric API,
   Litematica, and malilib** in `.minecraft/mods` (or uncomment
   `modLocalRuntime files(jar)` in `build.gradle` to launch the dev client with
   them). Cloth Config + Mod Menu are optional (config screen only).

---

## Usage

1. Load a schematic in Litematica and place it as usual.
2. Have the required blocks in your inventory.
3. Stand within ~4 blocks of the area to build.
4. Press **B** (rebindable) to toggle the builder on/off.
5. Watch the action-bar readout: blocks placed, blocks/min, seconds/block,
   live TPS, and current fatigue multiplier — that's your build-time estimate.
   Multiply seconds/block by the schematic's total block count (Litematica's
   material list) for a whole-build figure.

While active it swallows real mouse input so you don't fight the bot; the toggle
key still works. It pauses automatically below the configured TPS and resumes
when the server recovers.

---

## Layout

```
com.humanbuilder
├─ HumanBuilderClient        entry point, keybind, lifecycle
├─ aim/HumanAimController     Fitts's law, quintic Bézier, tremor, overshoot, GCD quantization
├─ aim/SmoothRotation        mixin-implemented rotation hook
├─ physics/BlockPlacementMath anchor search + getPlacementState validation + raycast
├─ inventory/InventoryManager hotbar/main-inventory resolution via SWAP window clicks
├─ inventory/MissingItemException
├─ network/TPSMonitor        rolling 10 s TPS from world.getTime()
├─ stochastic/StochasticEngine Box–Muller delays, fatigue, misclick/hesitation
├─ scanner/SchematicBridge   reads Litematica WorldSchematic, filters, priority-sorts
├─ scanner/Target
├─ state/BuilderStateMachine the FSM
├─ util/InputSimulator       hardware-equivalent KeyBinding driving
├─ config/BuilderConfig      tunables
└─ config/ModMenuIntegration optional Cloth config screen
mixin: MinecraftClientMixin (tick), MouseMixin (input block), ClientPlayerEntityMixin (rotation)
```

MIT. Personal use.
