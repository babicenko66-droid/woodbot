package dev.woodbot;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Small grid A* over "feet positions". Moves: 4 cardinal directions, 1-block step-up (jump),
 * and drops of up to 3 blocks. Avoids water, lava, fire, cactus, etc.
 */
final class Pathfinder {
    interface Goal {
        boolean reached(BlockPos feet);
        double heuristic(BlockPos feet);
    }

    /** Standing position from which the eye is within {@code reach} of the target point. */
    record ReachGoal(Vec3 target, double reach) implements Goal {
        public boolean reached(BlockPos f) {
            return eye(f).distanceToSqr(target) <= reach * reach;
        }
        public double heuristic(BlockPos f) {
            return Math.max(0, eye(f).distanceTo(target) - reach);
        }
        private static Vec3 eye(BlockPos f) {
            return new Vec3(f.getX() + 0.5, f.getY() + 1.62, f.getZ() + 0.5);
        }
    }

    /** Standing position close enough (horizontally) to pick up an item at {@code target}. */
    record ItemGoal(Vec3 target) implements Goal {
        public boolean reached(BlockPos f) {
            return horiz(f) <= 0.8 && Math.abs(target.y - f.getY()) <= 1.5;
        }
        public double heuristic(BlockPos f) {
            return Math.max(0, horiz(f) - 0.8);
        }
        private double horiz(BlockPos f) {
            return Math.hypot(f.getX() + 0.5 - target.x, f.getZ() + 0.5 - target.z);
        }
    }

    /** Anywhere within {@code radius} blocks (horizontally) of {@code target}. */
    record AreaGoal(Vec3 target, double radius) implements Goal {
        public boolean reached(BlockPos f) {
            return horiz(f) <= radius;
        }
        public double heuristic(BlockPos f) {
            return Math.max(0, horiz(f) - radius);
        }
        private double horiz(BlockPos f) {
            return Math.hypot(f.getX() + 0.5 - target.x, f.getZ() + 0.5 - target.z);
        }
    }

    private static final int MAX_RANGE = 48;
    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private static final class Node {
        final BlockPos pos;
        final Node parent;
        final double g, f;
        boolean closed;
        Node(BlockPos pos, Node parent, double g, double f) {
            this.pos = pos; this.parent = parent; this.g = g; this.f = f;
        }
    }

    private final ClientLevel level;
    private final BlockPos start;
    private final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
    private PriorityQueue<Node> open;
    private Map<Long, Node> all;
    private Goal goal;

    private Pathfinder(ClientLevel level, BlockPos start) {
        this.level = level;
        this.start = start;
    }

    /** @return list of feet positions (excluding the start), empty if already at the goal, null if no path. */
    static List<BlockPos> find(ClientLevel level, BlockPos start, Goal goal, int maxNodes) {
        return new Pathfinder(level, start).search(goal, maxNodes);
    }

    private List<BlockPos> search(Goal goal, int maxNodes) {
        this.goal = goal;
        open = new PriorityQueue<>(Comparator.comparingDouble((Node n) -> n.f));
        all = new HashMap<>();
        Node s = new Node(start, null, 0, goal.heuristic(start));
        open.add(s);
        all.put(start.asLong(), s);

        int expanded = 0;
        while (!open.isEmpty() && expanded < maxNodes) {
            Node cur = open.poll();
            if (cur.closed || all.get(cur.pos.asLong()) != cur) continue;
            cur.closed = true;
            expanded++;

            if (goal.reached(cur.pos)) {
                LinkedList<BlockPos> out = new LinkedList<>();
                for (Node n = cur; n.parent != null; n = n.parent) out.addFirst(n.pos);
                return out;
            }

            int x = cur.pos.getX(), y = cur.pos.getY(), z = cur.pos.getZ();
            for (int[] d : DIRS) {
                int nx = x + d[0], nz = z + d[1];
                if (canStand(nx, y, nz)) {
                    relax(cur, nx, y, nz, 1.0);
                    continue;
                }
                // 1-block step up (jump): need headroom above current position
                if (passable(x, y + 2, z) && canStand(nx, y + 1, nz)) {
                    relax(cur, nx, y + 1, nz, 1.6);
                    continue;
                }
                // drop down up to 3 blocks
                if (passable(nx, y, nz) && passable(nx, y + 1, nz)) {
                    for (int dd = 1; dd <= 3; dd++) {
                        if (canStand(nx, y - dd, nz)) {
                            relax(cur, nx, y - dd, nz, 1.0 + 0.5 * dd);
                            break;
                        }
                        if (!passable(nx, y - dd, nz)) break;
                    }
                }
            }
        }
        return null;
    }

    private void relax(Node cur, int nx, int ny, int nz, double cost) {
        if (Math.abs(nx - start.getX()) > MAX_RANGE || Math.abs(nz - start.getZ()) > MAX_RANGE) return;
        long key = BlockPos.asLong(nx, ny, nz);
        double ng = cur.g + cost;
        Node ex = all.get(key);
        if (ex != null && ex.g <= ng) return;
        BlockPos p = new BlockPos(nx, ny, nz);
        Node n = new Node(p, cur, ng, ng + goal.heuristic(p));
        all.put(key, n);
        open.add(n);
    }

    // ---- world queries -------------------------------------------------------------------

    private boolean canStand(int x, int y, int z) {
        return passable(x, y, z) && passable(x, y + 1, z) && solidFloor(x, y - 1, z);
    }

    private boolean passable(int x, int y, int z) {
        if (y < level.getMinY() || y > level.getMaxY()) return false;
        m.set(x, y, z);
        if (!level.hasChunkAt(m)) return false;
        BlockState st = level.getBlockState(m);
        if (!st.getCollisionShape(level, m).isEmpty()) return false;
        if (!st.getFluidState().isEmpty()) return false;
        return !hazard(st);
    }

    private boolean solidFloor(int x, int y, int z) {
        if (y < level.getMinY()) return false;
        m.set(x, y, z);
        if (!level.hasChunkAt(m)) return false;
        BlockState st = level.getBlockState(m);
        VoxelShape shape = st.getCollisionShape(level, m);
        if (shape.isEmpty()) return false;
        double top = shape.max(Direction.Axis.Y);
        if (top < 0.9 || top > 1.0001) return false;
        return !hazard(st);
    }

    private static boolean hazard(BlockState st) {
        return st.is(BlockTags.FIRE)
                || st.is(Blocks.COBWEB)
                || st.is(Blocks.SWEET_BERRY_BUSH)
                || st.is(Blocks.POWDER_SNOW)
                || st.is(Blocks.WITHER_ROSE)
                || st.is(Blocks.CACTUS)
                || st.is(Blocks.MAGMA_BLOCK)
                || st.is(Blocks.CAMPFIRE)
                || st.is(Blocks.SOUL_CAMPFIRE)
                || st.is(Blocks.NETHER_PORTAL)
                || st.is(Blocks.END_PORTAL);
    }
}
