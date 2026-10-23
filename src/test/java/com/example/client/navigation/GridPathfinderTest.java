package com.example.client.navigation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

class GridPathfinderTest {
	@Test
	void findsRouteAroundBlockedCell() {
		Set<BlockPos> blocked = Set.of(new BlockPos(1, 0, 0));
		GridPathfinder.Passability passability = position -> position.getY() == 0
				&& !blocked.contains(position);

		var path = new GridPathfinder().findPath(
				new BlockPos(0, 0, 0), new BlockPos(2, 0, 0), passability, 100);

		assertFalse(path.isEmpty());
		assertEquals(new BlockPos(0, 0, 0), path.getFirst());
		assertEquals(new BlockPos(2, 0, 0), path.getLast());
		assertTrue(path.stream().noneMatch(blocked::contains));
	}

	@Test
	void returnsEmptyWhenTargetCannotBeReached() {
		Set<BlockPos> blocked = new HashSet<>();
		for (int x = -1; x <= 1; x++) {
			for (int z = -1; z <= 1; z++) {
				if (x != 0 || z != 0) blocked.add(new BlockPos(x, 0, z));
			}
		}
		GridPathfinder.Passability passability = position -> position.getY() == 0
				&& !blocked.contains(position);

		assertTrue(new GridPathfinder().findPath(
				new BlockPos(0, 0, 0), new BlockPos(2, 0, 0), passability, 100).isEmpty());
	}
}
