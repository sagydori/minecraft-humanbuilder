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
 * Module F — The Litematica Bridge.
 *
 * <p>Reads the placed schematic via Litematica's public
 * {@link SchematicWorldHandler#getSchematicWorld()} (a {@link WorldSchematic}
 * that extends {@code net.minecraft.world.World}, so {@code getBlockState} is the
 * ordinary Minecraft accessor). Scans a spherical radius from the eye and returns
 * placeable targets, prioritised lowest-Y first (build bottom-up) then nearest.</p>
 *
 * <p>A target qualifies when: the schematic has a non-air block there that differs
 * from the real world; the real block is air/replaceable (we never break blocks);
 * a solid anchor exists in the real world; and the player holds the item.</p>
 */
public final class SchematicBridge {

    public static final SchematicBridge INSTANCE = new SchematicBridge();

    private SchematicBridge() {}

    public List<Target> scan(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        ClientWorld real = client.world;
        WorldSchematic schem = SchematicWorldHandler.getSchematicWorld();
        if (player == null || real == null || schem == null) return List.of();

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

                    // Restrict to the chosen placement's bounds, if the menu set one.
                    if (BuilderConfig.INSTANCE.buildBounds != null
                            && !BuilderConfig.INSTANCE.buildBounds.contains(
                                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5)) {
                        continue;
                    }

                    // Don't place a block into the player's own body.
                    if (pos.equals(feet) || pos.equals(feet.up())) continue;

                    // Skip the auto-generated secondary half of double blocks
                    // (door top, bed head, tall plant top) — placing the base
                    // spawns both halves; targeting the top would double-place.
                    if (want.contains(Properties.DOUBLE_BLOCK_HALF)
                            && want.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) continue;
                    if (want.contains(Properties.BED_PART)
                            && want.get(Properties.BED_PART) == BedPart.HEAD) continue;

                    BlockState have = real.getBlockState(pos);
                    if (have == want) continue;                       // already correct
                    if (!have.isAir() && !have.isReplaceable()) continue; // occupied by wrong block — skip

                    Item item = want.getBlock().asItem();
                    if (item == Items.AIR) continue;                  // no obtainable item (e.g. fluids)
                    if (!hasItem(player.getInventory(), item)) continue;
                    if (!hasAnchor(real, pos)) continue;

                    out.add(new Target(pos.toImmutable(), want));
                }
            }
        }

        out.sort(Comparator
                .comparingInt((Target t) -> t.pos().getY())          // 1) lowest Y first
                .thenComparingDouble(t -> horizontalDistSq(t.pos(), eye))); // 2) nearest horizontally
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
