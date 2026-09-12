package com.humanbuilder.scanner;

import com.humanbuilder.config.BuilderConfig;
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
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Module F — The Litematica Bridge. Scans a spherical radius from the eye and
 * returns placeable targets (lowest-Y first, then nearest). Also records a
 * breakdown of why blocks were skipped, surfaced in the status line for
 * diagnosis.
 */
public final class SchematicBridge {

    public static final SchematicBridge INSTANCE = new SchematicBridge();

    private SchematicBridge() {}

    /** Diagnostic counts from the most recent scan. */
    public static final class ScanStats {
        public boolean schemNull;
        public int seen, alreadyOk, occupied, noItem, noAnchor, outOfBounds, selfBody, doubleHalf, candidates;

        public String summary() {
            if (schemNull) return "schem=NULL (no Litematica schematic world)";
            if (seen == 0) return "no schematic blocks in range";
            return "seen " + seen + " | ok " + alreadyOk + " | occ " + occupied
                    + " | noItem " + noItem + " | noAnchor " + noAnchor
                    + " | oob " + outOfBounds + " | cand " + candidates;
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

        boolean creative = player.getAbilities().creativeMode;
        Vec3d eye = player.getEyePos();
        double radius = BuilderConfig.INSTANCE.scanRadius;
        double radiusSq = radius * radius;
        int ri = (int) Math.ceil(radius);

        int cx = (int) Math.floor(eye.x);
        int cy = (int) Math.floor(eye.y);
        int cz = (int) Math.floor(eye.z);

        var chunkManager = schem.getChunkManager();
        BlockPos feet = player.getBlockPos();
        List<Target> out = new ArrayList<>();
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int dx = -ri; dx <= ri; dx++) {
            for (int dy = -ri; dy <= ri; dy++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    pos.set(cx + dx, cy + dy, cz + dz);

                    double ex = pos.getX() + 0.5 - eye.x;
                    double ey = pos.getY() + 0.5 - eye.y;
                    double ez = pos.getZ() + 0.5 - eye.z;
                    if (ex * ex + ey * ey + ez * ez > radiusSq) continue;
                    if (!chunkManager.isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) continue;

                    BlockState want = schem.getBlockState(pos);
                    if (want.isAir()) continue;
                    st.seen++;

                    if (BuilderConfig.INSTANCE.buildBounds != null
                            && !BuilderConfig.INSTANCE.buildBounds.contains(
                                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
                        st.outOfBounds++;
                        continue;
                    }

                    // Never place into the player's own body (feet/head cells).
                    if (pos.equals(feet) || pos.equals(feet.up())) { st.selfBody++; continue; }

                    // Skip auto-generated second halves of double blocks.
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
                    // Survival needs the item in inventory; creative can grab it.
                    if (!creative && !hasItem(player.getInventory(), item)) { st.noItem++; continue; }
                    if (!hasAnchor(real, pos)) { st.noAnchor++; continue; }

                    st.candidates++;
                    out.add(new Target(pos.toImmutable(), want));
                }
            }
        }

        out.sort(Comparator
                .comparingInt((Target t) -> t.pos().getY())
                .thenComparingDouble(t -> horizontalDistSq(t.pos(), eye)));
        lastStats = st;
        return out;
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

    private static double horizontalDistSq(BlockPos pos, Vec3d eye) {
        double dx = pos.getX() + 0.5 - eye.x;
        double dz = pos.getZ() + 0.5 - eye.z;
        return dx * dx + dz * dz;
    }
}
