package com.humanbuilder.scanner;

import com.humanbuilder.config.BuilderConfig;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Module F — The Litematica Bridge. Scans the WHOLE selected schematic (not just
 * a small radius) bottom-up, so the builder can find the next placeable block
 * anywhere and walk to it. The scan is capped and collected lowest-Y first, so it
 * naturally focuses on the lowest unfinished layer and stays cheap.
 */
public final class SchematicBridge {

    public static final SchematicBridge INSTANCE = new SchematicBridge();

    private SchematicBridge() {}

    public static final class ScanStats {
        public boolean schemNull;
        public int seen, alreadyOk, occupied, noItem, noAnchor, selfBody, doubleHalf, candidates;

        public String summary() {
            if (schemNull) return "schem=NULL (no Litematica schematic world)";
            if (seen == 0) return "no schematic blocks found";
            return "seen " + seen + " | ok " + alreadyOk + " | occ " + occupied
                    + " | noItem " + noItem + " | noAnchor " + noAnchor + " | cand " + candidates;
        }
    }

    private volatile ScanStats lastStats = new ScanStats();

    public ScanStats getLastStats() {
        return lastStats;
    }

    public List<Target> scan(MinecraftClient client) {
        ScanStats st = new ScanStats();
        ClientPlayerEntity player = client.player;
        ClientWorld real = client.world;
        WorldSchematic schem = SchematicWorldHandler.getSchematicWorld();
        if (player == null || real == null || schem == null) {
            st.schemNull = true;
            lastStats = st;
            return List.of();
        }

        BuilderConfig cfg = BuilderConfig.INSTANCE;
        boolean creative = player.getAbilities().creativeMode;
        Vec3d eye = player.getEyePos();
        BlockPos feet = player.getBlockPos();

        int[] region = region(player);
        int x0 = region[0], y0 = region[1], z0 = region[2];
        int x1 = region[3], y1 = region[4], z1 = region[5];

        var chunkManager = schem.getChunkManager();
        List<Target> out = new ArrayList<>();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        long iterations = 0;

        // Bottom-up: iterate Y ascending and stop once we have enough candidates,
        // which keeps us on the lowest unfinished layers.
        outer:
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (++iterations > cfg.maxScanIterations) break outer;
                    pos.set(x, y, z);
                    if (!chunkManager.isChunkLoaded(x >> 4, z >> 4)) continue;

                    BlockState want = schem.getBlockState(pos);
                    if (want.isAir()) continue;
                    st.seen++;

                    if (pos.equals(feet) || pos.equals(feet.up())) { st.selfBody++; continue; }

                    if ((want.contains(Properties.DOUBLE_BLOCK_HALF)
                            && want.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
                            || (want.contains(Properties.BED_PART)
                            && want.get(Properties.BED_PART) == BedPart.HEAD)) {
                        st.doubleHalf++;
                        continue;
                    }

                    BlockState have = real.getBlockState(pos);
                    if (have == want) { st.alreadyOk++; continue; }
                    if (!have.isAir() && !have.isReplaceable()) { st.occupied++; continue; }

                    Item item = want.getBlock().asItem();
                    if (item == Items.AIR) { st.noItem++; continue; }
                    if (!creative && !hasItem(player.getInventory(), item)) { st.noItem++; continue; }
                    if (!hasAnchor(real, pos)) { st.noAnchor++; continue; }

                    st.candidates++;
                    out.add(new Target(pos.toImmutable(), want));
                    if (out.size() >= cfg.maxCandidates) break outer;
                }
            }
        }

        out.sort(Comparator
                .comparingInt((Target t) -> t.pos().getY())
                .thenComparingDouble(t -> t.pos().getSquaredDistance(eye.x, eye.y, eye.z)));
        lastStats = st;
        return out;
    }

    /** Region [x0,y0,z0,x1,y1,z1] to scan: chosen bounds, else all placements, else a box near the player. */
    private static int[] region(ClientPlayerEntity player) {
        Box b = BuilderConfig.INSTANCE.buildBounds;
        if (b != null) {
            return new int[]{
                    MathHelper.floor(b.minX), MathHelper.floor(b.minY), MathHelper.floor(b.minZ),
                    MathHelper.ceil(b.maxX) - 1, MathHelper.ceil(b.maxY) - 1, MathHelper.ceil(b.maxZ) - 1};
        }
        int[] pb = allPlacementsBox();
        if (pb != null) return pb;
        // Last resort: a modest box around the player (small enough to fit the
        // scan budget so we don't exhaust it in empty layers below the build).
        BlockPos f = player.getBlockPos();
        int rh = 24;
        return new int[]{f.getX() - rh, f.getY() - 16, f.getZ() - rh,
                f.getX() + rh, f.getY() + 48, f.getZ() + rh};
    }

    /** Union of every loaded Litematica placement's enclosing box (whole schematic). */
    private static int[] allPlacementsBox() {
        try {
            var mgr = DataManager.getSchematicPlacementManager();
            if (mgr == null) return null;
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
            boolean any = false;
            for (SchematicPlacement p : mgr.getAllSchematicsPlacements()) {
                if (p == null) continue;
                fi.dy.masa.litematica.selection.Box box = p.getEclosingBox();
                if (box == null) continue;
                BlockPos c1 = box.getPos1(), c2 = box.getPos2();
                if (c1 == null || c2 == null) continue;
                minX = Math.min(minX, Math.min(c1.getX(), c2.getX()));
                minY = Math.min(minY, Math.min(c1.getY(), c2.getY()));
                minZ = Math.min(minZ, Math.min(c1.getZ(), c2.getZ()));
                maxX = Math.max(maxX, Math.max(c1.getX(), c2.getX()));
                maxY = Math.max(maxY, Math.max(c1.getY(), c2.getY()));
                maxZ = Math.max(maxZ, Math.max(c1.getZ(), c2.getZ()));
                any = true;
            }
            return any ? new int[]{minX, minY, minZ, maxX, maxY, maxZ} : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean hasItem(PlayerInventory inv, Item item) {
        for (int i = 0; i < 36; i++) {
            var s = inv.getStack(i);
            if (!s.isEmpty() && s.getItem() == item) return true;
        }
        return false;
    }

    private static boolean hasAnchor(ClientWorld world, BlockPos pos) {
        for (Direction d : Direction.values()) {
            BlockPos n = pos.offset(d);
            BlockState ns = world.getBlockState(n);
            if (!ns.isAir() && ns.getFluidState().isEmpty()
                    && ns.isSideSolidFullSquare(world, n, d.getOpposite())) {
                return true;
            }
        }
        return false;
    }
}
