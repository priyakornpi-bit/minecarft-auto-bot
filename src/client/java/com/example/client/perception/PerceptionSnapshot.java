package com.example.client.perception;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.block.state.BlockState;

/**
 * ภาพรวมสถานะโลก ณ หนึ่งช่วงเวลา ใช้เป็นข้อมูลกลางให้ระบบวางแผนและกู้คืนการทำงาน
 * โดยคลาสนี้มีหน้าที่อ่านข้อมูลเท่านั้นและไม่สั่งให้บอทเคลื่อนที่หรือแก้ไขโลก
 */
public record PerceptionSnapshot(
		boolean worldAvailable,
		BlockPos playerPosition,
		float health,
		int food,
		int nearbyHostiles,
		BlockPos nearestHostile,
		int nearbyItemStacks,
		BlockPos nearestItem,
		BlockPos nearestLog,
		BlockPos nearestOre,
		boolean lavaNearby,
		boolean blockedAhead,
		boolean missingFloorAhead) {

	public static PerceptionSnapshot capture(Minecraft client) {
		LocalPlayer player = client.player;
		ClientLevel level = client.level;
		if (player == null || level == null) {
			return unavailable();
		}

		BlockPos origin = player.blockPosition();
		List<Monster> hostiles = level.getEntitiesOfClass(
				Monster.class, player.getBoundingBox().inflate(12.0), Monster::isAlive);
		List<ItemEntity> items = level.getEntitiesOfClass(
				ItemEntity.class, player.getBoundingBox().inflate(16.0), ItemEntity::isAlive);
		return new PerceptionSnapshot(
				true,
				origin,
				player.getHealth(),
				player.getFoodData().getFoodLevel(),
				hostiles.size(),
				nearestEntity(origin, hostiles),
				items.size(),
				nearestEntity(origin, items),
				findNearestBlock(level, origin, 16, true),
				findNearestBlock(level, origin, 6, false),
				hasNearbyLava(level, origin, 3),
				isBlockedAhead(level, player),
				isMissingFloorAhead(level, player));
	}

	private static PerceptionSnapshot unavailable() {
		return new PerceptionSnapshot(false, BlockPos.ZERO, 0.0F, 0, 0, null, 0, null, null, null,
				false, false, false);
	}

	private static BlockPos nearestEntity(BlockPos origin, List<? extends net.minecraft.world.entity.Entity> entities) {
		BlockPos nearest = null;
		double bestDistance = Double.MAX_VALUE;
		for (net.minecraft.world.entity.Entity entity : entities) {
			double distance = entity.blockPosition().distSqr(origin);
			if (distance < bestDistance) {
				bestDistance = distance;
				nearest = entity.blockPosition();
			}
		}
		return nearest;
	}

	private static BlockPos findNearestBlock(ClientLevel level, BlockPos origin, int radius, boolean logs) {
		BlockPos nearest = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(
				origin.offset(-radius, -Math.min(radius, 6), -radius),
				origin.offset(radius, Math.min(radius, 6), radius))) {
			BlockState state = level.getBlockState(pos);
			boolean matches = logs
					? state.is(BlockTags.LOGS)
					: isOre(state);
			if (matches && pos.distSqr(origin) < bestDistance) {
				bestDistance = pos.distSqr(origin);
				nearest = pos.immutable();
			}
		}
		return nearest;
	}

	private static boolean isOre(BlockState state) {
		String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
		return path.endsWith("_ore") || path.equals("ancient_debris");
	}

	private static boolean hasNearbyLava(ClientLevel level, BlockPos origin, int radius) {
		for (BlockPos pos : BlockPos.betweenClosed(
				origin.offset(-radius, -radius, -radius), origin.offset(radius, radius, radius))) {
			if (level.getFluidState(pos).is(FluidTags.LAVA)) return true;
		}
		return false;
	}

	private static boolean isBlockedAhead(ClientLevel level, LocalPlayer player) {
		BlockPos ahead = player.blockPosition().relative(player.getDirection());
		return !level.getBlockState(ahead).getCollisionShape(level, ahead).isEmpty()
				|| !level.getBlockState(ahead.above()).getCollisionShape(level, ahead.above()).isEmpty();
	}

	private static boolean isMissingFloorAhead(ClientLevel level, LocalPlayer player) {
		BlockPos floor = player.blockPosition().relative(player.getDirection()).below();
		return level.getBlockState(floor).getCollisionShape(level, floor).isEmpty();
	}
}
