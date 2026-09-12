package com.humanbuilder.physics;

import net.minecraft.block.BlockState;
import net.minecraft.block.BlockWithEntity;
import net.minecraft.block.ButtonBlock;
import net.minecraft.block.CraftingTableBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.block.LeverBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.state.property.Properties;
import net.minecraft.state.property.Property;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Module B — Block-state physics & raycasting.
 *
 * <p>Rather than hand-coding "top 50% of the face for an upper slab, this face
 * for that log axis" (which is fragile and wrong for many blocks), placement is
 * validated against Minecraft's own authority: {@link
 * net.minecraft.block.Block#getPlacementState(ItemPlacementContext)}. For each
 * candidate anchor face and sub-pixel hit vector we build a real
 * {@link ItemPlacementContext} and accept the first candidate whose resulting
 * state matches the schematic state. This is correct for slabs, stairs, pillars,
 * directional blocks, etc.</p>
 *
 * <p><b>Facing note:</b> for blocks whose facing derives from where the player
 * looks (chests, furnaces, stairs...), the anchor search ignores the FACING
 * property and instead returns a {@code requiredYaw} hint (front-toward-player
 * convention); the state machine orients to it and then re-validates strictly.</p>
 *
 * <p>All planar hit coordinates are kept within [0.15, 0.85] so clicks land
 * comfortably inside the face (blueprint: avoid edge/corner ambiguity).</p>
 */
public final class BlockPlacementMath {

    private BlockPlacementMath() {}

    public record PlacementSolution(BlockPos anchorPos, Direction side, Vec3d hitVec,
                                    boolean requiresSneak, Float requiredYaw, Float requiredPitch) {}

    /** Candidate look directions searched to reproduce a directional block. */
    private static final Direction[] LOOKS = {
            Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST,
            Direction.UP, Direction.DOWN
    };

    /** Prefer building on the block below (bottom-up), then sides, then underside last. */
    private static final Direction[] APPROACH_ORDER = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH,
            Direction.EAST, Direction.WEST, Direction.UP
    };

    /** Planar sub-pixel offsets to try, all inside [0.15, 0.85]. */
    private static final double[] PLANAR_OFFSETS = {0.5, 0.3, 0.7, 0.2, 0.8};

    /** Properties that update from neighbors/environment and must not gate anchor choice. */
    private static final Set<Property<?>> IGNORED = Set.of(
            // cast pins the varargs element type to Property<?> (invariant target)
            (Property<?>) Properties.WATERLOGGED,
            Properties.NORTH, Properties.EAST, Properties.SOUTH, Properties.WEST,
            Properties.UP, Properties.DOWN,
            Properties.POWER, Properties.POWERED, Properties.LIT,
            Properties.STAIR_SHAPE,
            Properties.NORTH_WIRE_CONNECTION, Properties.EAST_WIRE_CONNECTION,
            Properties.SOUTH_WIRE_CONNECTION, Properties.WEST_WIRE_CONNECTION,
            Properties.DISTANCE_1_7, Properties.PERSISTENT,
            Properties.ROTATION // 16-step sign/banner rotation — can't hit with 6 looks
    );

    // ---------------------------------------------------------------------
    //  Solve
    // ---------------------------------------------------------------------

    /**
     * Find a placement solution that reproduces {@code schematic} at {@code target},
     * or null if none of the currently-present neighbours can anchor it.
     */
    public static PlacementSolution solve(World world, ClientPlayerEntity player,
                                          BlockPos target, BlockState schematic, Hand hand) {
        // Target must be free in the real world.
        BlockState existing = world.getBlockState(target);
        if (!existing.isAir() && !existing.isReplaceable()) return null;

        boolean directional = needsOrientation(schematic);
        Direction[] looks = directional ? LOOKS : new Direction[]{Direction.NORTH};
        ItemStack stack = new ItemStack(schematic.getBlock().asItem());

        for (Direction approach : APPROACH_ORDER) {
            BlockPos anchor = target.offset(approach);
            Direction side = approach.getOpposite(); // face of the anchor pointing at the target
            BlockState anchorState = world.getBlockState(anchor);

            if (anchorState.isAir()) continue;
            if (!anchorState.getFluidState().isEmpty()) continue;
            if (!anchorState.isSideSolidFullSquare(world, anchor, side)) continue;

            for (Vec3d hit : hitCandidates(anchor, side, schematic)) {
                BlockHitResult bhr = new BlockHitResult(hit, side, anchor, false);
                for (Direction look : looks) {
                    ItemPlacementContext ctx = new OrientedPlacementContext(player, hand, stack, bhr, look);
                    if (!ctx.canPlace()) continue;
                    BlockState placed = schematic.getBlock().getPlacementState(ctx);
                    // Strict: the exact facing/axis must match (rotation ignored).
                    if (matches(placed, schematic, true)) {
                        boolean sneak = isInteractable(anchorState);
                        Float yaw = directional ? yawFor(look) : null;
                        Float pitch = directional ? pitchFor(look) : null;
                        return new PlacementSolution(anchor, side, hit, sneak, yaw, pitch);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Strict re-validation used at the VERIFYING step, once the player is aimed:
     * confirms the placement state (including FACING) still matches.
     */
    public static boolean confirm(World world, ClientPlayerEntity player,
                                  BlockState schematic, Hand hand, PlacementSolution sol) {
        BlockHitResult bhr = new BlockHitResult(sol.hitVec(), sol.side(), sol.anchorPos(), false);
        ItemPlacementContext ctx = new ItemPlacementContext(new ItemUsageContext(player, hand, bhr));
        if (!ctx.canPlace()) return false;
        BlockState placed = schematic.getBlock().getPlacementState(ctx);
        // Strict: after the player has been aimed to the required orientation, the
        // exact facing/axis must match (rotation is ignored, see IGNORED).
        return matches(placed, schematic, true);
    }

    // ---------------------------------------------------------------------
    //  Raycast validation
    // ---------------------------------------------------------------------

    /**
     * Confirm the eye has clear line of sight to the intended face — i.e. an
     * OUTLINE raycast to the hit vector actually strikes the anchor's expected
     * side and nothing (e.g. an entity-shaped block or another block) is in the way.
     */
    public static boolean lineOfSightClear(World world, ClientPlayerEntity player, PlacementSolution sol) {
        Vec3d eye = player.getEyePos();
        BlockHitResult bhr = world.raycast(new RaycastContext(
                eye, sol.hitVec(),
                RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE,
                player));
        return bhr.getType() == HitResult.Type.BLOCK
                && bhr.getBlockPos().equals(sol.anchorPos())
                && bhr.getSide() == sol.side();
    }

    /**
     * Can the player actually place here from where they stand right now?
     * True only if the hit point is within block-interaction range AND nothing
     * obstructs the eye→hit line before the target. This is robust to the
     * floor-grazing false-negative: an obstruction only disqualifies the target
     * if it is hit <em>before</em> reaching the intended point (grazing a
     * coplanar neighbour at the very end is fine).
     */
    public static boolean reachable(World world, ClientPlayerEntity player, PlacementSolution sol) {
        // Never place into the player's own body: if the target block space
        // overlaps the player's bounding box, this isn't placeable from here.
        BlockPos targetPos = sol.anchorPos().offset(sol.side());
        Box targetBox = new Box(
                targetPos.getX(), targetPos.getY(), targetPos.getZ(),
                targetPos.getX() + 1, targetPos.getY() + 1, targetPos.getZ() + 1);
        if (player.getBoundingBox().intersects(targetBox)) return false;

        Vec3d eye = player.getEyePos();
        Vec3d aim = sol.hitVec();
        double reach = player.getBlockInteractionRange();
        double dAim = eye.squaredDistanceTo(aim);
        if (dAim > (reach - 0.05) * (reach - 0.05)) return false;

        BlockHitResult r = world.raycast(new RaycastContext(
                eye, aim, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, player));
        if (r.getType() == HitResult.Type.MISS) return true;
        if (r.getBlockPos().equals(sol.anchorPos())) return true;
        // An obstruction only counts if it is closer than the target itself.
        double dHit = r.getPos().squaredDistanceTo(eye);
        return dHit >= dAim - 0.06;
    }

    // ---------------------------------------------------------------------
    //  Helpers
    // ---------------------------------------------------------------------

    private static List<Vec3d> hitCandidates(BlockPos a, Direction side, BlockState schematic) {
        Direction.Axis sa = side.getAxis();
        double bx = a.getX(), by = a.getY(), bz = a.getZ();

        double[] xs = (sa == Direction.Axis.X) ? new double[]{side.getOffsetX() > 0 ? 1.0 : 0.0} : PLANAR_OFFSETS;
        double[] zs = (sa == Direction.Axis.Z) ? new double[]{side.getOffsetZ() > 0 ? 1.0 : 0.0} : PLANAR_OFFSETS;
        double[] ys = (sa == Direction.Axis.Y)
                ? new double[]{side.getOffsetY() > 0 ? 1.0 : 0.0}
                : verticalBias(schematic);

        List<Vec3d> out = new ArrayList<>();
        for (double ox : xs)
            for (double oy : ys)
                for (double oz : zs)
                    out.add(new Vec3d(bx + ox, by + oy, bz + oz));
        return out;
    }

    /**
     * Vertical hit offsets biased for slabs/stairs half selection. Upper-half
     * targets aim high on the face (0.8), lower-half aim low (0.2); the neutral
     * 0.5 is always tried as a fallback. All within [0.15, 0.85].
     */
    private static double[] verticalBias(BlockState s) {
        boolean upper = false, lower = false;
        if (s.contains(Properties.SLAB_TYPE)) {
            SlabType t = s.get(Properties.SLAB_TYPE);
            upper = t == SlabType.TOP;
            lower = t == SlabType.BOTTOM;
        }
        if (s.contains(Properties.BLOCK_HALF)) {
            BlockHalf h = s.get(Properties.BLOCK_HALF);
            upper |= h == BlockHalf.TOP;
            lower |= h == BlockHalf.BOTTOM;
        }
        if (upper) return new double[]{0.8, 0.5};
        if (lower) return new double[]{0.2, 0.5};
        return PLANAR_OFFSETS;
    }

    private static boolean matches(BlockState placed, BlockState schematic, boolean strict) {
        if (placed == null) return false;
        if (placed.getBlock() != schematic.getBlock()) return false;
        for (Property<?> p : schematic.getProperties()) {
            if (IGNORED.contains(p)) continue;
            if (!strict && (p == Properties.HORIZONTAL_FACING || p == Properties.FACING)) {
                continue; // resolved by orienting the player, then confirm() checks strictly
            }
            if (!placed.contains(p)) continue;
            if (!placed.get(p).equals(schematic.get(p))) return false;
        }
        return true;
    }

    /** Does this block's state depend on how it's placed (facing/axis)? */
    private static boolean needsOrientation(BlockState s) {
        return s.contains(Properties.FACING)
                || s.contains(Properties.HORIZONTAL_FACING)
                || s.contains(Properties.AXIS)
                || s.contains(Properties.HORIZONTAL_AXIS)
                || s.contains(Properties.HOPPER_FACING);
    }

    /** Player yaw that produces the given look direction (horizontal only; 0 for up/down). */
    private static Float yawFor(Direction look) {
        if (look.getAxis().isVertical()) return 0.0f;
        return (float) Math.toDegrees(Math.atan2(-look.getOffsetX(), look.getOffsetZ()));
    }

    /** Player pitch that produces the given look direction. */
    private static Float pitchFor(Direction look) {
        if (look == Direction.UP) return -90.0f;
        if (look == Direction.DOWN) return 90.0f;
        return 0.0f;
    }

    /** Heuristic: would clicking this block trigger a use action, requiring sneak to place? */
    private static boolean isInteractable(BlockState state) {
        var b = state.getBlock();
        return b instanceof BlockWithEntity      // chests, furnaces, barrels, hoppers, ...
                || b instanceof CraftingTableBlock
                || b instanceof DoorBlock
                || b instanceof TrapdoorBlock
                || b instanceof FenceGateBlock
                || b instanceof ButtonBlock
                || b instanceof LeverBlock;
    }
}
