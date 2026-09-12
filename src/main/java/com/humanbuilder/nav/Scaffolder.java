package com.humanbuilder.nav;

import com.humanbuilder.config.BuilderConfig;
import com.humanbuilder.scanner.Target;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;

/**
 * Builds a temporary staircase so the builder can climb to upper layers instead
 * of getting stuck at ground level. The staircase rises one block per step in a
 * chosen horizontal direction (preferably outward, away from the build), each
 * step face-anchored to the previous one so it's always placeable and climbable.
 *
 * <p>It reuses the normal place/navigate pipeline: {@link #next} just returns the
 * next scaffold block to place as a synthetic {@link Target}; the state machine
 * solves, walks to, and places it like any other block.</p>
 */
public final class Scaffolder {

    public static final Scaffolder INSTANCE = new Scaffolder();

    private boolean active;
    private BlockPos base;
    private Direction dir;
    private Item item;
    private int goalY;
    private int placed;

    private Scaffolder() {}

    public void reset() {
        active = false;
        base = null;
        dir = null;
        item = null;
        placed = 0;
    }

    public void notePlaced() {
        placed++;
    }

    public BlockState blockState() {
        if (item instanceof BlockItem bi) return bi.getBlock().getDefaultState();
        return null;
    }

    public Item item() {
        return item;
    }

    /** Next scaffold block to place to reach {@code goal}, or null if it can't help. */
    public Target next(MinecraftClient client, ClientPlayerEntity player, BlockPos goal) {
        BuilderConfig cfg = BuilderConfig.INSTANCE;
        if (!cfg.enableScaffolding) return null;
        ClientWorld real = client.world;
        if (real == null) return null;
        WorldSchematic schem = SchematicWorldHandler.getSchematicWorld();

        if (!active) {
            item = findScaffoldItem(player);
            if (item == null) return null;
            dir = pickDir(real, schem, player, goal);
            if (dir == null) return null;
            base = player.getBlockPos().down(); // the block the player stands on
            goalY = goal.getY();
            placed = 0;
            active = true;
        }
        if (placed >= cfg.maxScaffoldBlocks) return null;

        BlockState scaf = blockState();
        if (scaf == null) return null;

        int maxI = Math.max(1, goalY - base.getY() + 1);
        for (int i = 1; i <= maxI; i++) {
            BlockPos top = base.add(dir.getOffsetX() * i, i, dir.getOffsetZ() * i);
            BlockPos support = top.down();
            for (BlockPos p : new BlockPos[]{support, top}) {
                BlockState rs = real.getBlockState(p);
                if (!rs.isAir() && !rs.isReplaceable()) continue; // already solid
                if (schem != null && !schem.getBlockState(p).isAir()) return null; // would hit the build
                return new Target(p, scaf);
            }
        }
        return null; // staircase reaches the goal height
    }

    // ---------------------------------------------------------------------

    private static Direction pickDir(ClientWorld real, WorldSchematic schem,
                                     ClientPlayerEntity player, BlockPos goal) {
        Box b = BuilderConfig.INSTANCE.buildBounds;
        Direction preferred = null;
        if (b != null) {
            double cx = (b.minX + b.maxX) / 2.0, cz = (b.minZ + b.maxZ) / 2.0;
            double dx = player.getX() - cx, dz = player.getZ() - cz;
            preferred = Math.abs(dx) >= Math.abs(dz)
                    ? (dx >= 0 ? Direction.EAST : Direction.WEST)
                    : (dz >= 0 ? Direction.SOUTH : Direction.NORTH);
        }
        if (preferred != null && dirClear(real, schem, player, preferred)) return preferred;
        for (Direction d : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            if (dirClear(real, schem, player, d)) return d;
        }
        return null;
    }

    /** The first two steps in this direction must be free (real air + not part of the build). */
    private static boolean dirClear(ClientWorld real, WorldSchematic schem, ClientPlayerEntity player, Direction d) {
        BlockPos base = player.getBlockPos().down();
        for (int i = 1; i <= 2; i++) {
            BlockPos top = base.add(d.getOffsetX() * i, i, d.getOffsetZ() * i);
            for (BlockPos p : new BlockPos[]{top, top.down()}) {
                BlockState rs = real.getBlockState(p);
                if (!rs.isAir() && !rs.isReplaceable()) return false;
                if (schem != null && !schem.getBlockState(p).isAir()) return false;
            }
        }
        return true;
    }

    private static Item findScaffoldItem(ClientPlayerEntity player) {
        if (player.getAbilities().creativeMode) return Items.COBBLESTONE;
        Item best = null;
        int bestCount = 0;
        var inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty() || !(s.getItem() instanceof BlockItem bi)) continue;
            if (!(bi.getBlock() instanceof Block)) continue;
            if (s.getCount() > bestCount && s.getCount() >= 4) {
                bestCount = s.getCount();
                best = s.getItem();
            }
        }
        return best;
    }
}
