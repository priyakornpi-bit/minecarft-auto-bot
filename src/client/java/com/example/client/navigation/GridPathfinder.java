package com.example.client.navigation;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** A* แบบ grid สำหรับหาเส้นทางบนช่องที่ยืนได้ในข้อมูลโลกฝั่ง client */
public final class GridPathfinder {
	private static final Direction[] HORIZONTAL = {
			Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
	};

	/** หาเส้นทางไปยังเป้าหมายเดียว โดยคืน path ที่มีตำแหน่งเริ่มต้นรวมอยู่ด้วย */
	public List<BlockPos> findPath(BlockPos start, BlockPos goal, Passability passability, int maxNodes) {
		return findPathToAny(start, List.of(goal), passability, maxNodes);
	}

	/** หาเส้นทางไปยังจุดยืนใดจุดหนึ่งรอบเป้าหมาย เช่น จุดที่อยู่ใกล้ต้นไม้ */
	public List<BlockPos> findPathToAny(
			BlockPos start, Collection<BlockPos> goals, Passability passability, int maxNodes) {
		Set<BlockPos> goalSet = new HashSet<>();
		for (BlockPos goal : goals) {
			if (passability.canStandAt(goal)) goalSet.add(goal.immutable());
		}
		if (goalSet.isEmpty() || !passability.canStandAt(start) || maxNodes <= 0) return List.of();
		if (goalSet.contains(start)) return List.of(start.immutable());

		Map<BlockPos, BlockPos> cameFrom = new HashMap<>();
		Map<BlockPos, Integer> cost = new HashMap<>();
		Set<BlockPos> closed = new HashSet<>();
		PriorityQueue<Node> open = new PriorityQueue<>(Comparator
				.comparingInt(Node::estimatedTotal)
				.thenComparingInt(Node::cost));
		cost.put(start, 0);
		open.add(new Node(start.immutable(), 0, heuristic(start, goalSet)));

		for (int visited = 0; !open.isEmpty() && visited < maxNodes; visited++) {
			Node entry = open.remove();
			BlockPos current = entry.position();
			if (entry.cost() != cost.getOrDefault(current, Integer.MAX_VALUE)) continue;
			if (goalSet.contains(current)) return reconstruct(cameFrom, current);
			if (!closed.add(current)) continue;

			for (BlockPos next : neighbours(current, passability)) {
				if (closed.contains(next)) continue;
				int nextCost = entry.cost() + 1;
				if (nextCost < cost.getOrDefault(next, Integer.MAX_VALUE)) {
					BlockPos stableNext = next.immutable();
					cameFrom.put(stableNext, current);
					cost.put(stableNext, nextCost);
					open.add(new Node(stableNext, nextCost, heuristic(next, goalSet)));
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

	private static int heuristic(BlockPos position, Collection<BlockPos> goals) {
		int best = Integer.MAX_VALUE;
		for (BlockPos goal : goals) {
			best = Math.min(best,
					Math.abs(position.getX() - goal.getX())
							+ Math.abs(position.getY() - goal.getY())
							+ Math.abs(position.getZ() - goal.getZ()));
		}
		return best;
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

	private record Node(BlockPos position, int cost, int estimatedTotal) {
	}

	@FunctionalInterface
	public interface Passability {
		boolean canStandAt(BlockPos position);

		/** รับตำแหน่งของบล็อกที่รองรับเท้า ไม่ใช่ตำแหน่งเท้าของผู้เล่น */
		default boolean hasFloorAt(BlockPos floorPosition) {
			return canStandAt(floorPosition);
		}
	}
}
