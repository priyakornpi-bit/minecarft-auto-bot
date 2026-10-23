package com.example.client.container;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.state.BlockState;

/** ค้นหา container ใกล้ผู้เล่น โดยยังไม่แก้ inventory หรือบังคับเปิดหน้าต่างแทนผู้เล่น */
public final class ContainerManager {
	private ContainerManager() {
	}

	public static BlockPos findNearestContainer(ClientLevel level, BlockPos origin, int radius) {
		BlockPos nearest = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(
				origin.offset(-radius, -Math.min(radius, 6), -radius),
				origin.offset(radius, Math.min(radius, 6), radius))) {
			BlockState state = level.getBlockState(pos);
			String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
			if (!isContainer(path)) continue;
			double distance = pos.distSqr(origin);
			if (distance < bestDistance) {
				bestDistance = distance;
				nearest = pos.immutable();
			}
		}
		return nearest;
	}

	private static boolean isContainer(String path) {
		return path.equals("chest") || path.equals("trapped_chest")
				|| path.equals("barrel") || path.endsWith("_shulker_box");
	}
}
