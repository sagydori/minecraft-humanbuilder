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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Module F — The Litematica Bridge. Scans the whole selected schematic and finds
 * placeable targets. Layer order is driven by {@link #lowestRemainingY} (the true
 * lowest unfinished Y across the whole build, not just the cached candidates), so
 * the builder finishes each layer before the next — never placing a higher block
 * whose support doesn't exist yet.
 */
public final class SchematicBridge {

    public static final SchematicBridge INSTANCE = new SchematicBridge();

    private SchematicBridge() {}

    /** Blocks we've given up on (unbuildable) so layers can still advance. */
    private final Set<Long> skip = new HashSet<>();

    public void addSkip(BlockPos p) { skip.add(p.asLong()); }
    public void clearSkips() { skip.clear(); }

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
    public ScanStats getLastStats() { return lastStats; }
    public volatile boolean lastRemainingCapped;

    // ---------------------------------------------------------------------
    //  Remaining / layer queries
    // ---------------------------------------------------------------------

    /** True if this cell still needs a block placed (schematic ≠ real, placeable). */
    private static boolean isRemaining(BlockState want, BlockState have) {
        if (want.isAir()) return false;
        if ((want.contains(Properties.DOUBLE_BLOCK_HALF)
                && want.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
                || (want.contains(Properties.BED_PART)
                && want.get(Properties.BED_PART) == BedPart.HEAD)) return false;
        if (have == want) return false;
        return have.isAir() || have.isReplaceable();
    }

    /**
     * The lowest Y (>= {@code startY}) that still has an unfinished, non-skipped
     * schematic block, or {@link Integer#MAX_VALUE} if none. Scanning starts at
     * {@code startY} (everything below is assumed done) to stay cheap.
     */
    public int lowestRemainingY(MinecraftClient client, int startY) {
        ClientPlayerEntity player = client.player;
        ClientWorld real = client.world;
        WorldSchematic schem = SchematicWorldHandler.getSchematicWorld();
        if (player == null || real == null || schem == null) return Integer.MAX_VALUE;

        int[] r = region(player);
        int y0 = Math.max(r[1], startY);
        var cm = schem.getChunkManager();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        long iter = 0, cap = 2_000_000L;

        for (int y = y0; y <= r[4]; y++) {
            for (int x = r[0]; x <= r[3]; x++) {
                for (int z = r[2]; z <= r[5]; z++) {
                    if (++iter > cap) return Integer.MAX_VALUE;
                    pos.set(x, y, z);
                    if (skip.contains(pos.asLong())) continue;
                    if (!cm.isChunkLoaded(x >> 4, z >> 4)) continue;
                    if (isRemaining(schem.getBlockState(pos), real.getBlockState(pos))) return y;
                }
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * True if this position still needs a block placed (schematic solid, world
     * empty/replaceable, not skipped) — Baritone's "incorrect + placeable" test,
     * used for the support-first filter (don't place a block while the one below
     * it is itself still to be placed).
     */
    public boolean needsPlacement(MinecraftClient client, BlockPos pos) {
        ClientWorld real = client.world;
        WorldSchematic schem = SchematicWorldHandler.getSchematicWorld();
        if (real == null || schem == null) return false;
        if (skip.contains(pos.asLong())) return false;
        return isRemaining(schem.getBlockState(pos), real.getBlockState(pos));
    }

    /** Give up on every remaining block at layer {@code y} (used to break deadlocks). */
    public int skipRemainingAt(MinecraftClient client, int y) {
        ClientPlayerEntity player = client.player;
        ClientWorld real = client.world;
        WorldSchematic schem = SchematicWorldHandler.getSchematicWorld();
        if (player == null || real == null || schem == null) return 0;
        int[] r = region(player);
        var cm = schem.getChunkManager();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int n = 0;
        for (int x = r[0]; x <= r[3]; x++) {
            for (int z = r[2]; z <= r[5]; z++) {
                pos.set(x, y, z);
                if (skip.contains(pos.asLong())) continue;
                if (!cm.isChunkLoaded(x >> 4, z >> 4)) continue;
                if (isRemaining(schem.getBlockState(pos), real.getBlockState(pos))) {
                    skip.add(pos.asLong());
                    n++;
                }
            }
        }
        return n;
    }

    public int countRemaining(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientWorld real = client.world;
        WorldSchematic schem = SchematicWorldHandler.getSchematicWorld();
        lastRemainingCapped = false;
        if (player == null || real == null || schem == null) return -1;

        int[] r = region(player);
        var cm = schem.getChunkManager();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        long iter = 0, cap = 600_000L;
        int count = 0;
        for (int y = r[1]; y <= r[4]; y++)
            for (int x = r[0]; x <= r[3]; x++)
                for (int z = r[2]; z <= r[5]; z++) {
                    if (++iter > cap) { lastRemainingCapped = true; return count; }
                    pos.set(x, y, z);
                    if (skip.contains(pos.asLong())) continue;
                    if (!cm.isChunkLoaded(x >> 4, z >> 4)) continue;
                    if (isRemaining(schem.getBlockState(pos), real.getBlockState(pos))) count++;
                }
        return count;
    }

    // ---------------------------------------------------------------------
    //  Candidate scan (only blocks that can be anchored + we have the item)
    // ---------------------------------------------------------------------

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

        int[] r = region(player);
        var chunkManager = schem.getChunkManager();
        List<Target> out = new ArrayList<>();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        long iterations = 0;

        outer:
        for (int y = r[1]; y <= r[4]; y++) {
            for (int x = r[0]; x <= r[3]; x++) {
                for (int z = r[2]; z <= r[5]; z++) {
                    if (++iterations > cfg.maxScanIterations) break outer;
                    pos.set(x, y, z);
                    if (skip.contains(pos.asLong())) continue;
                    if (!chunkManager.isChunkLoaded(x >> 4, z >> 4)) continue;

                    BlockState want = schem.getBlockState(pos);
                    if (want.isAir()) continue;
                    st.seen++;

                    if (pos.equals(feet) || pos.equals(feet.up())) { st.selfBody++; continue; }
                    if ((want.contains(Properties.DOUBLE_BLOCK_HALF)
                            && want.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER)
                            || (want.contains(Properties.BED_PART)
                            && want.get(Properties.BED_PART) == BedPart.HEAD)) { st.doubleHalf++; continue; }

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

    // ---------------------------------------------------------------------
    //  Region helpers
    // ---------------------------------------------------------------------

    /** Public build region {minX,minY,minZ,maxX,maxY,maxZ} (bounds/placements/fallback), or null. */
    public int[] buildRegion(MinecraftClient client) {
        return client == null || client.player == null ? null : region(client.player);
    }

    private static int[] region(ClientPlayerEntity player) {
        Box b = BuilderConfig.INSTANCE.buildBounds;
        if (b != null) {
            return new int[]{
                    MathHelper.floor(b.minX), MathHelper.floor(b.minY), MathHelper.floor(b.minZ),
                    MathHelper.ceil(b.maxX) - 1, MathHelper.ceil(b.maxY) - 1, MathHelper.ceil(b.maxZ) - 1};
        }
        int[] pb = allPlacementsBox();
        if (pb != null) return pb;
        BlockPos f = player.getBlockPos();
        int rh = 24;
        return new int[]{f.getX() - rh, f.getY() - 16, f.getZ() - rh,
                f.getX() + rh, f.getY() + 48, f.getZ() + rh};
    }

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
