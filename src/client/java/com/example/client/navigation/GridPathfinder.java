package com.example.client.navigation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** A* แบบ grid สำหรับค้นหาเส้นทางสั้นในพื้นที่ที่บอทรู้ข้อมูลแล้ว */
public final class GridPathfinder {
	private static final Direction[] HORIZONTAL = {
			Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
	};

	public List<BlockPos> findPath(BlockPos start, BlockPos goal, Passability passability, int maxNodes) {
		if (!passability.canStandAt(start) || !passability.canStandAt(goal)) return List.of();
		Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
		Map<BlockPos, Integer> cost = new HashMap<>();
		Set<BlockPos> closed = new HashSet<>();
		ArrayDeque<BlockPos> open = new ArrayDeque<>();
		open.add(start);
		cost.put(start, 0);

		for (int visited = 0; !open.isEmpty() && visited < maxNodes; visited++) {
			BlockPos current = open.stream()
					.min(Comparator.comparingInt(pos -> cost.get(pos) + heuristic(pos, goal)))
					.orElseThrow();
			open.remove(current);
			if (current.equals(goal)) return reconstruct(cameFrom, current);
			if (!closed.add(current)) continue;
			for (BlockPos next : neighbours(current, passability)) {
				if (closed.contains(next)) continue;
				int nextCost = cost.get(current) + 1;
				if (nextCost < cost.getOrDefault(next, Integer.MAX_VALUE)) {
					cameFrom.put(next, current);
					cost.put(next, nextCost);
					if (!open.contains(next)) open.add(next);
				}
			}
		}
		return List.of();
	}

	private static List<BlockPos> neighbours(BlockPos current, Passability passability) {
		List<BlockPos> result = new ArrayList<>();
		for (Direction direction : HORIZONTAL) {
			BlockPos sameLevel = current.relative(direction);
			if (passability.canStandAt(sameLevel)) result.add(sameLevel);
			BlockPos up = sameLevel.above();
			if (passability.canStandAt(up) && passability.hasFloorAt(sameLevel)) result.add(up);
			BlockPos down = sameLevel.below();
			if (passability.canStandAt(down) && passability.hasFloorAt(down.below())) result.add(down);
		}
		return result;
	}

	private static int heuristic(BlockPos first, BlockPos second) {
		return Math.abs(first.getX() - second.getX())
				+ Math.abs(first.getY() - second.getY())
				+ Math.abs(first.getZ() - second.getZ());
	}

	private static List<BlockPos> reconstruct(Map<BlockPos, BlockPos> cameFrom, BlockPos current) {
		ArrayDeque<BlockPos> path = new ArrayDeque<>();
		path.addFirst(current);
		while (cameFrom.containsKey(current)) {
			current = cameFrom.get(current);
			path.addFirst(current);
		}
		return List.copyOf(path);
	}

	@FunctionalInterface
	public interface Passability {
		boolean canStandAt(BlockPos position);

		default boolean hasFloorAt(BlockPos position) {
			return canStandAt(position);
		}
	}
}
