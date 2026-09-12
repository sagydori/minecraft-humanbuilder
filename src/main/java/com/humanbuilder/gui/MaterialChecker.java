package com.humanbuilder.gui;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.block.BlockState;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Computes how many of each item a schematic placement still needs (blocks not
 * yet present in the real world) and compares that against the player's
 * inventory, so the menu can decide whether building may start.
 */
public final class MaterialChecker {

    private MaterialChecker() {}

    private static final long MAX_SCAN = 1_000_000L;

    public record Entry(Item item, String name, int need, int have, boolean ok) {}

    public record Result(List<Entry> entries, boolean allSatisfied, boolean creative,
                         long scanned, boolean truncated, Box bounds) {}

    /** All loaded Litematica placements (may be empty if none loaded). */
    public static List<SchematicPlacement> placements() {
        var mgr = DataManager.getSchematicPlacementManager();
        return mgr == null ? List.of() : mgr.getAllSchematicsPlacements();
    }

    public static SchematicPlacement selected() {
        var mgr = DataManager.getSchematicPlacementManager();
        return mgr == null ? null : mgr.getSelectedSchematicPlacement();
    }

    /** Tally remaining materials for a placement and check them against inventory. */
    public static Result compute(MinecraftClient mc, SchematicPlacement placement) {
        List<Entry> empty = List.of();
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null || placement == null) {
            return new Result(empty, false, false, 0, false, null);
        }
        Box box = placement.getEclosingBox(); // upstream typo in the API name
        WorldSchematic schem = SchematicWorldHandler.getSchematicWorld();
        if (box == null || schem == null) {
            return new Result(empty, false, false, 0, false, box);
        }

        boolean creative = player.getAbilities().creativeMode;

        Map<Item, Integer> have = inventoryCounts(player);
        Map<Item, Integer> need = new HashMap<>();

        int x0 = MathHelper.floor(box.minX), x1 = MathHelper.ceil(box.maxX) - 1;
        int y0 = MathHelper.floor(box.minY), y1 = MathHelper.ceil(box.maxY) - 1;
        int z0 = MathHelper.floor(box.minZ), z1 = MathHelper.ceil(box.maxZ) - 1;

        long scanned = 0;
        boolean truncated = false;
        var chunkManager = schem.getChunkManager();
        BlockPos.Mutable pos = new BlockPos.Mutable();

        outer:
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                for (int z = z0; z <= z1; z++) {
                    if (++scanned > MAX_SCAN) { truncated = true; break outer; }
                    pos.set(x, y, z);
                    if (!chunkManager.isChunkLoaded(x >> 4, z >> 4)) continue;

                    BlockState want = schem.getBlockState(pos);
                    if (want.isAir()) continue;

                    // Match the builder's skips so the count reflects real work.
                    if (want.contains(Properties.DOUBLE_BLOCK_HALF)
                            && want.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) continue;
                    if (want.contains(Properties.BED_PART)
                            && want.get(Properties.BED_PART) == BedPart.HEAD) continue;

                    BlockState real = mc.world.getBlockState(pos);
                    if (real == want) continue; // already placed

                    Item item = want.getBlock().asItem();
                    if (item == Items.AIR) continue;
                    need.merge(item, 1, Integer::sum);
                }
            }
        }

        List<Entry> entries = new ArrayList<>();
        boolean all = true;
        for (var e : need.entrySet()) {
            Item item = e.getKey();
            int n = e.getValue();
            int h = have.getOrDefault(item, 0);
            boolean ok = creative || h >= n;
            if (!ok) all = false;
            entries.add(new Entry(item, itemName(item), n, h, ok));
        }
        entries.sort(Comparator.comparing((Entry en) -> !en.ok()).thenComparing(Entry::name));

        return new Result(entries, all, creative, scanned, truncated, box);
    }

    private static Map<Item, Integer> inventoryCounts(ClientPlayerEntity player) {
        Map<Item, Integer> counts = new HashMap<>();
        var inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.getStack(i);
            if (!s.isEmpty()) counts.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        ItemStack off = player.getOffHandStack();
        if (!off.isEmpty()) counts.merge(off.getItem(), off.getCount(), Integer::sum);
        return counts;
    }

    private static String itemName(Item item) {
        try {
            return item.getName().getString();
        } catch (Throwable t) {
            return Registries.ITEM.getId(item).toString();
        }
    }
}
