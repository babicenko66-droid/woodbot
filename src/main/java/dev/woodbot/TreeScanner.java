package dev.woodbot;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/** Finds the nearest tree (a connected cluster of log blocks that touches natural leaves). */
final class TreeScanner {
    static final class Tree {
        final Set<BlockPos> logs = new HashSet<>();
        BlockPos origin;
    }

    private static final int MAX_TREE_SIZE = 600;

    static Tree findNearest(ClientLevel level, LocalPlayer player, int radius, Set<Long> ignored) {
        BlockPos c = player.blockPosition();
        int yMin = Math.max(level.getMinY(), c.getY() - 10);
        int yMax = Math.min(level.getMaxY(), c.getY() + 16);
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        List<BlockPos> candidates = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                for (int y = yMin; y <= yMax; y++) {
                    m.set(c.getX() + dx, y, c.getZ() + dz);
                    if (level.getBlockState(m).is(BlockTags.LOGS) && !ignored.contains(m.asLong())) {
                        candidates.add(m.immutable());
                    }
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> p.distSqr(c)));

        Set<Long> visited = new HashSet<>();
        for (BlockPos start : candidates) {
            if (visited.contains(start.asLong())) continue;
            Tree tree = flood(level, start, ignored, visited);
            if (looksLikeTree(level, tree)) return tree;
        }
        return null;
    }

    private static Tree flood(ClientLevel level, BlockPos start, Set<Long> ignored, Set<Long> visited) {
        Tree tree = new Tree();
        tree.origin = start;
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        queue.add(start);
        visited.add(start.asLong());
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        while (!queue.isEmpty() && tree.logs.size() < MAX_TREE_SIZE) {
            BlockPos cur = queue.poll();
            tree.logs.add(cur);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        m.set(cur.getX() + dx, cur.getY() + dy, cur.getZ() + dz);
                        long key = m.asLong();
                        if (visited.contains(key) || ignored.contains(key)) continue;
                        if (level.getBlockState(m).is(BlockTags.LOGS)) {
                            visited.add(key);
                            queue.add(m.immutable());
                        }
                    }
                }
            }
        }
        return tree;
    }

    /** Natural trees have non-persistent leaves (or wart blocks for huge fungi) next to their logs. */
    private static boolean looksLikeTree(ClientLevel level, Tree tree) {
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (BlockPos log : tree.logs) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        m.set(log.getX() + dx, log.getY() + dy, log.getZ() + dz);
                        BlockState st = level.getBlockState(m);
                        if (st.is(BlockTags.LEAVES)
                                && !(st.hasProperty(BlockStateProperties.PERSISTENT)
                                && st.getValue(BlockStateProperties.PERSISTENT))) {
                            return true;
                        }
                        if (st.is(BlockTags.WART_BLOCKS)) return true;
                    }
                }
            }
        }
        return false;
    }
}
