package com.humanbuilder.nav;

import com.humanbuilder.config.BuilderConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Lightweight A* grid pathfinder over client-side world block states.
 *
 * <p>Nodes are "feet" block positions the player can legally stand in: solid,
 * non-hazard floor below; two passable, non-hazard cells for the body. Moves are
 * 4-directional with 1-block step-up (jump) and multi-block step-down (fall up to
 * {@link BuilderConfig#maxFallDistance}). Diagonals are intentionally omitted to
 * avoid corner-clipping. Hazards (lava, fire, magma, cactus, sweet berries,
 * powder snow, wither rose) are never stood on or walked through.</p>
 */
public final class Pathfinder {

    private Pathfinder() {}

    private static final double EYE_HEIGHT = 1.62;

    private static final Direction[] H = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    // ---------------------------------------------------------------------
    //  Walkability
    // ---------------------------------------------------------------------

    /** No collision at this cell (air, plants, water, etc.). */
    private static boolean passable(World w, BlockPos p) {
        return w.getBlockState(p).getCollisionShape(w, p).isEmpty();
    }

    /** Something solid to stand on at this cell. */
    private static boolean hasFloor(World w, BlockPos p) {
        return !w.getBlockState(p).getCollisionShape(w, p).isEmpty();
    }

    private static boolean isHazard(World w, BlockPos p) {
        BlockState s = w.getBlockState(p);
        if (!s.getFluidState().isEmpty() && s.getFluidState().isIn(net.minecraft.registry.tag.FluidTags.LAVA)) {
            return true;
        }
        return s.isOf(Blocks.FIRE) || s.isOf(Blocks.SOUL_FIRE) || s.isOf(Blocks.LAVA)
                || s.isOf(Blocks.MAGMA_BLOCK) || s.isOf(Blocks.CACTUS)
                || s.isOf(Blocks.SWEET_BERRY_BUSH) || s.isOf(Blocks.POWDER_SNOW)
                || s.isOf(Blocks.WITHER_ROSE) || s.isOf(Blocks.CAMPFIRE) || s.isOf(Blocks.SOUL_CAMPFIRE);
    }

    /** Can the player's feet legally occupy this cell? */
    public static boolean standable(World w, BlockPos feet) {
        BlockPos below = feet.down();
        if (!hasFloor(w, below)) return false;
        if (isHazard(w, below)) return false;
        if (!passable(w, feet) || !passable(w, feet.up())) return false;
        if (isHazard(w, feet) || isHazard(w, feet.up())) return false;
        return true;
    }

    // ---------------------------------------------------------------------
    //  A*
    // ---------------------------------------------------------------------

    private static final class Node {
        final BlockPos pos;
        double g, f;
        Node parent;
        Node(BlockPos pos) { this.pos = pos; }
    }

    /**
     * @return waypoints from just after {@code start} through {@code goal}, or an
     *         empty list if no path was found within the iteration budget.
     */
    public static List<BlockPos> findPath(World world, BlockPos start, BlockPos goal) {
        if (start.equals(goal)) return List.of();
        BuilderConfig cfg = BuilderConfig.INSTANCE;

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Map<Long, Node> all = new HashMap<>();
        Set<Long> closed = new HashSet<>();

        Node s = new Node(start);
        s.g = 0;
        s.f = heuristic(start, goal);
        open.add(s);
        all.put(start.asLong(), s);

        int iterations = 0;
        Node best = s;

        while (!open.isEmpty()) {
            if (++iterations > cfg.maxPathIterations) break;
            Node cur = open.poll();
            if (closed.contains(cur.pos.asLong())) continue;
            closed.add(cur.pos.asLong());

            if (cur.pos.equals(goal)) return reconstruct(cur);
            if (heuristic(cur.pos, goal) < heuristic(best.pos, goal)) best = cur;

            for (Move m : neighbors(world, cur.pos)) {
                if (closed.contains(m.pos.asLong())) continue;
                double ng = cur.g + m.cost;
                Node nb = all.get(m.pos.asLong());
                if (nb == null) {
                    nb = new Node(m.pos);
                    all.put(m.pos.asLong(), nb);
                } else if (ng >= nb.g) {
                    continue;
                }
                nb.parent = cur;
                nb.g = ng;
                nb.f = ng + heuristic(m.pos, goal);
                open.add(nb);
            }
        }

        // No exact path: return a partial path toward the closest reached node,
        // which is often good enough to make progress (re-planned on arrival).
        if (best != s) return reconstruct(best);
        return List.of();
    }

    private record Move(BlockPos pos, double cost) {}

    private static List<Move> neighbors(World w, BlockPos p) {
        BuilderConfig cfg = BuilderConfig.INSTANCE;
        List<Move> out = new ArrayList<>(6);
        for (Direction d : H) {
            BlockPos h = p.offset(d);
            if (standable(w, h)) {
                out.add(new Move(h, 1.0));
                continue;
            }
            // Step up one block (jump), needs headroom above the current cell.
            BlockPos up = h.up();
            if (standable(w, up) && passable(w, p.up(2))) {
                out.add(new Move(up, 1.8));
                continue;
            }
            // Step down (fall) — only if the horizontal cell itself is clear.
            if (passable(w, h) && passable(w, h.up())) {
                for (int k = 1; k <= cfg.maxFallDistance; k++) {
                    BlockPos dn = h.down(k);
                    if (standable(w, dn)) {
                        out.add(new Move(dn, 1.0 + k * 0.4));
                        break;
                    }
                    if (!passable(w, dn)) break; // hit something non-standable
                }
            }
        }
        return out;
    }

    private static double heuristic(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ())
                + Math.abs(a.getY() - b.getY());
    }

    private static List<BlockPos> reconstruct(Node goal) {
        List<BlockPos> raw = new ArrayList<>();
        for (Node n = goal; n != null; n = n.parent) raw.add(n.pos);
        Collections.reverse(raw);
        if (!raw.isEmpty()) raw.remove(0); // drop the start cell
        return simplify(raw);
    }

    /** Drop intermediate nodes on straight, same-height runs to reduce waypoints. */
    private static List<BlockPos> simplify(List<BlockPos> pts) {
        if (pts.size() < 3) return pts;
        List<BlockPos> out = new ArrayList<>();
        out.add(pts.get(0));
        for (int i = 1; i < pts.size() - 1; i++) {
            BlockPos a = pts.get(i - 1), b = pts.get(i), c = pts.get(i + 1);
            int dx1 = b.getX() - a.getX(), dz1 = b.getZ() - a.getZ(), dy1 = b.getY() - a.getY();
            int dx2 = c.getX() - b.getX(), dz2 = c.getZ() - b.getZ(), dy2 = c.getY() - b.getY();
            if (!(dx1 == dx2 && dz1 == dz2 && dy1 == dy2)) out.add(b); // keep turns/elevation changes
        }
        out.add(pts.get(pts.size() - 1));
        return out;
    }

    // ---------------------------------------------------------------------
    //  Standing position calculator
    // ---------------------------------------------------------------------

    /**
     * Find a legal standing cell adjacent-ish to {@code target}: solid ground,
     * within {@code reach} of the target (eye→target center), with clear line of
     * sight. Prefers the closest such cell, tie-broken by proximity to {@code from}.
     */
    public static BlockPos findStandingPosition(World world, BlockPos target, BlockPos from, double reach) {
        BuilderConfig cfg = BuilderConfig.INSTANCE;
        int r = (int) Math.ceil(reach);
        Vec3d tc = Vec3d.ofCenter(target);

        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                for (int dy = -cfg.maxFallDistance; dy <= 2; dy++) {
                    BlockPos cand = target.add(dx, dy, dz);
                    if (cand.equals(target)) continue;
                    if (!standable(world, cand)) continue;

                    Vec3d eye = Vec3d.ofCenter(cand).add(0, EYE_HEIGHT - 0.5, 0);
                    double dist = eye.distanceTo(tc);
                    if (dist > reach) continue;
                    if (!lineOfSight(world, eye, tc, target)) continue;

                    double score = dist + cand.getSquaredDistance(from) * 0.01;
                    if (score < bestScore) {
                        bestScore = score;
                        best = cand;
                    }
                }
            }
        }
        return best;
    }

    private static boolean lineOfSight(World world, Vec3d eye, Vec3d target, BlockPos targetPos) {
        BlockHitResult hit = world.raycast(new RaycastContext(
                eye, target, RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE, null));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(targetPos);
    }
}
