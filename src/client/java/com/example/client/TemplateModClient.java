package com.example.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class TemplateModClient implements ClientModInitializer {
	private static KeyMapping toggleKey;
	private static KeyMapping emergencyStopKey;
	private static boolean autoMode;
	private static int scanCooldown;
	private static int woodScanCooldown;
	private static int woodSearchRadius;
	private static int avoidanceTicks;
	private static int blockedTicks;
	private static int mobAttackCooldown;
	private static long runTicks;
	private static int startLogs;
	private static int startOreItems;
	private static int engagedMobs;
	private static int defeatedMobs;
	private static boolean reportActive;
	private static BlockPos targetOre;
	private static BlockPos targetWood;
	private static LivingEntity targetMob;
	private static final KeyMapping.Category KEY_CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("minecraft-auto-bot", "controls"));

	@Override
	public void onInitializeClient() {
		toggleKey = new KeyMapping(
				"key.minecraft-auto-bot.toggle",
				InputConstants.getKey("key.keyboard.comma").getValue(),
				KEY_CATEGORY);
		emergencyStopKey = new KeyMapping(
				"key.minecraft-auto-bot.emergency_stop",
				InputConstants.getKey("key.keyboard.period").getValue(),
				KEY_CATEGORY);
		ClientTickEvents.END_CLIENT_TICK.register(TemplateModClient::onClientTick);
	}

	private static void onClientTick(Minecraft client) {
		while (toggleKey.consumeClick()) {
			if (client.getSingleplayerServer() == null || client.player == null) {
				message(client, "Automatic mode is available in Singleplayer only.");
				continue;
			}
			autoMode = !autoMode;
			if (autoMode) {
				runTicks = 0;
				startLogs = countLogs(client);
				startOreItems = countOreItems(client);
				engagedMobs = 0;
				defeatedMobs = 0;
				reportActive = true;
				woodScanCooldown = 0;
				woodSearchRadius = 16;
				avoidanceTicks = 0;
				blockedTicks = 0;
				targetOre = null;
				targetWood = null;
				targetMob = null;
				chatMessage(client, "The system is running.");
				message(client, "Automatic mode enabled: moving, gathering wood, defending, and mining.");
			} else {
				stopInputs(client);
				finishRun(client, "Stopped by user.");
				message(client, "Automatic mode disabled.");
			}
		}
		while (emergencyStopKey.consumeClick()) {
			autoMode = false;
			targetOre = null;
			targetWood = null;
			targetMob = null;
			stopInputs(client);
			finishRun(client, "Emergency stop.");
			message(client, "Emergency stop: automatic control disabled.");
		}

		if (autoMode && reportActive && (client.player == null || client.level == null || client.getSingleplayerServer() == null)) {
			autoMode = false;
			stopInputs(client);
			finishRun(client, "World closed or Singleplayer session ended.");
			return;
		}
		if (!autoMode || client.player == null || client.level == null || client.getSingleplayerServer() == null) {
			return;
		}
		runTicks++;
		if (runTicks % 20 == 0) {
			chatMessage(client, "The system is running: " + elapsedSeconds() + " seconds.");
		}
		String safetyReason = getSafetyStopReason(client);
		if (safetyReason != null) {
			stopSafely(client, safetyReason);
			return;
		}
		if (targetMob != null && !targetMob.isAlive()) {
			defeatedMobs++;
			targetMob = null;
		}
		LivingEntity nearbyHostile = findNearbyHostile(client);
		if (nearbyHostile != null && nearbyHostile != targetMob) {
			engagedMobs++;
		}
		targetMob = nearbyHostile;
		if (targetMob != null) {
			handleMob(client);
			return;
		}
		if (countLogs(client) < 256) {
			if (targetWood == null || !isLog(client.level, targetWood)) {
				targetWood = findNearbyLog(client, woodSearchRadius);
			}
			if (targetWood != null) {
				woodSearchRadius = 16;
				handleWood(client);
				return;
			}
			if (woodScanCooldown-- <= 0) {
				woodScanCooldown = 20;
				woodSearchRadius = Math.min(96, woodSearchRadius + 16);
				chatMessage(client, "No tree nearby. Searching within " + woodSearchRadius + " blocks.");
			}
			lookDownWhileSearchingForTree(client);
			stopInputs(client);
			return;
		}
		if (targetWood != null) {
			targetWood = null;
			chatMessage(client, "Wood target reached: " + countLogs(client) + " logs.");
		}
		if (scanCooldown-- <= 0) {
			scanCooldown = 10;
			targetOre = findNearbyOre(client);
		}
		if (targetOre == null) {
			client.options.keyUp.setDown(true);
			client.options.keyAttack.setDown(false);
			return;
		}
		client.options.keyUp.setDown(false);
		BlockPos target = targetOre;
		if (client.level.getBlockState(target).isAir()) {
			targetOre = null;
			client.options.keyAttack.setDown(false);
			return;
		}
		var player = client.player;
		double dx = target.getX() + 0.5 - player.getX();
		double dy = target.getY() + 0.5 - player.getEyeY();
		double dz = target.getZ() + 0.5 - player.getZ();
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		player.setYRot(yaw);
		player.setXRot(pitch);
		client.options.keyAttack.setDown(true);
	}

	private static LivingEntity findNearbyHostile(Minecraft client) {
		var player = client.player;
		if (player == null || client.level == null) {
			return null;
		}
		return client.level.getEntitiesOfClass(
				Monster.class,
				player.getBoundingBox().inflate(12.0),
				LivingEntity::isAlive
		).stream()
				.min((first, second) -> Double.compare(
						first.distanceToSqr(player), second.distanceToSqr(player)))
				.orElse(null);
	}

	private static void handleMob(Minecraft client) {
		var player = client.player;
		if (player == null || targetMob == null || !targetMob.isAlive() || client.gameMode == null) {
			stopInputs(client);
			return;
		}
		faceEntity(player, targetMob);
		double distance = player.distanceTo(targetMob);
		client.options.keyUp.setDown(distance > 2.8);
		client.options.keyAttack.setDown(false);
		if (mobAttackCooldown > 0) mobAttackCooldown--;
		if (distance <= 3.5 && mobAttackCooldown == 0 && player.getAttackStrengthScale(0.5F) >= 0.9F) {
			client.gameMode.attack(player, targetMob);
			player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
			mobAttackCooldown = 4;
		}
	}

	private static BlockPos findNearbyLog(Minecraft client, int radius) {
		var player = client.player;
		if (player == null || client.level == null) {
			return null;
		}
		BlockPos origin = player.blockPosition();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		int verticalRadius = Math.min(12, Math.max(5, radius / 4));
		for (BlockPos pos : BlockPos.betweenClosed(
				origin.offset(-radius, -verticalRadius, -radius),
				origin.offset(radius, verticalRadius, radius))) {
			if (!isLog(client.level, pos)) {
				continue;
			}
			double distance = pos.distToCenterSqr(player.position());
			if (distance < bestDistance && distance <= (double) radius * radius) {
				best = pos.immutable();
				bestDistance = distance;
			}
		}
		return best;
	}

	private static int countLogs(Minecraft client) {
		if (client.player == null) {
			return 0;
		}
		int total = 0;
		var inventory = client.player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			var stack = inventory.getItem(slot);
			if (stack.is(ItemTags.LOGS)) {
				total += stack.count();
			}
		}
		return total;
	}

	private static int countOreItems(Minecraft client) {
		if (client.player == null) return 0;
		int total = 0;
		var inventory = client.player.getInventory();
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			var stack = inventory.getItem(slot);
			String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
			if (path.endsWith("_ore") || path.startsWith("raw_") || path.equals("coal")
					|| path.equals("diamond") || path.equals("emerald") || path.equals("lapis_lazuli")
					|| path.equals("redstone") || path.equals("quartz") || path.equals("amethyst_shard")) {
				total += stack.getCount();
			}
		}
		return total;
	}

	private static boolean isLog(net.minecraft.client.multiplayer.ClientLevel level, BlockPos pos) {
		return level.getBlockState(pos).is(BlockTags.LOGS);
	}

	private static void handleWood(Minecraft client) {
		var player = client.player;
		if (player == null || targetWood == null || client.level == null) {
			stopInputs(client);
			return;
		}
		if (!isLog(client.level, targetWood)) {
			targetWood = null;
			stopInputs(client);
			return;
		}
		double dx = targetWood.getX() + 0.5 - player.getX();
		double dy = targetWood.getY() + 0.5 - player.getEyeY();
		double dz = targetWood.getZ() + 0.5 - player.getZ();
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (avoidanceTicks > 0) {
			avoidanceTicks--;
			player.setYRot(player.getYRot());
			player.setXRot(0.0F);
			client.options.keyUp.setDown(true);
			client.options.keyAttack.setDown(false);
			return;
		}
		Direction direction = player.getDirection();
		BlockPos ahead = player.blockPosition().relative(direction);
		if (!canStandAt(client.level, ahead)) {
			Direction left = direction.getCounterClockWise();
			Direction right = direction.getClockWise();
			Direction detour = canStandAt(client.level, player.blockPosition().relative(left))
					? left
					: canStandAt(client.level, player.blockPosition().relative(right)) ? right : null;
			if (detour == null) {
				blockedTicks++;
				stopInputs(client);
				if (blockedTicks == 1) {
					chatMessage(client, "Path blocked. Looking for a safe route.");
				}
				if (blockedTicks >= 40) {
					targetWood = null;
					blockedTicks = 0;
					chatMessage(client, "Cannot reach the tree safely. Searching for another tree.");
				}
				return;
			}
			blockedTicks = 0;
			avoidanceTicks = 20;
			player.setYRot(detour.toYRot());
			player.setXRot(0.0F);
			client.options.keyUp.setDown(true);
			client.options.keyAttack.setDown(false);
			return;
		}
		blockedTicks = 0;
		facePosition(player, dx, dy, dz);
		client.options.keyUp.setDown(distance > 4.0);
		client.options.keyAttack.setDown(distance <= 4.5);
	}

	private static boolean canStandAt(net.minecraft.client.multiplayer.ClientLevel level, BlockPos feet) {
		BlockPos head = feet.above();
		BlockPos floor = feet.below();
		return !isLava(level, feet)
				&& !isLava(level, floor)
				&& isEmptyCollision(level, feet)
				&& isEmptyCollision(level, head)
				&& !isEmptyCollision(level, floor);
	}

	private static void faceEntity(net.minecraft.client.player.LocalPlayer player, LivingEntity entity) {
		double dx = entity.getX() - player.getX();
		double dy = entity.getEyeY() - player.getEyeY();
		double dz = entity.getZ() - player.getZ();
		facePosition(player, dx, dy, dz);
	}

	private static void facePosition(net.minecraft.client.player.LocalPlayer player, double dx, double dy, double dz) {
		float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
		float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
		player.setYRot(yaw);
		player.setXRot(pitch);
	}

	private static BlockPos findNearbyOre(Minecraft client) {
		var player = client.player;
		BlockPos origin = player.blockPosition();
		BlockPos best = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-6, -4, -6), origin.offset(6, 4, 6))) {
			BlockState state = client.level.getBlockState(pos);
			Identifier id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
			if (id == null || !(id.getPath().endsWith("_ore") || id.getPath().equals("ancient_debris"))) continue;
			double distance = pos.distToCenterSqr(player.position());
			if (distance < bestDistance && distance <= 36.0 && client.level.getBlockState(pos.above()).isAir()) {
				best = pos.immutable();
				bestDistance = distance;
			}
		}
		return best;
	}

	private static String getSafetyStopReason(Minecraft client) {
		var player = client.player;
		var level = client.level;
		if (player == null || level == null) {
			return "World or player unavailable. Stopping.";
		}
		if (player.getHealth() <= 6.0F) {
			return "Health is low. Stopping for safety.";
		}
		if (player.getFoodData().getFoodLevel() <= 6) {
			return "Food level is low. Stopping for safety.";
		}
		if (player.onClimbable() || player.isFallFlying()) {
			return "Player is in an unsafe state. Stopping.";
		}

		BlockPos feet = player.blockPosition();
		Direction direction = player.getDirection();
		BlockPos ahead = feet.relative(direction);
		BlockPos aheadHead = ahead.above();
		BlockPos aheadFloor = ahead.below();
		if (isLava(level, feet) || isLava(level, ahead) || isLava(level, aheadFloor)) {
			return "Lava detected nearby. Stopping for safety.";
		}
		if (isEmptyCollision(level, aheadFloor)) {
			return "Missing floor detected ahead. Stopping to prevent a fall.";
		}
		return null;
	}

	private static boolean isLava(net.minecraft.client.multiplayer.ClientLevel level, BlockPos pos) {
		return level.getFluidState(pos).is(FluidTags.LAVA);
	}

	private static boolean isEmptyCollision(net.minecraft.client.multiplayer.ClientLevel level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	private static void stopSafely(Minecraft client, String reason) {
		autoMode = false;
		targetOre = null;
		targetWood = null;
		targetMob = null;
		scanCooldown = 0;
		woodScanCooldown = 0;
		woodSearchRadius = 16;
		avoidanceTicks = 0;
		blockedTicks = 0;
		stopInputs(client);
		finishRun(client, reason);
		message(client, reason);
	}

	private static void finishRun(Minecraft client, String reason) {
		if (!reportActive) return;
		long seconds = elapsedSeconds();
		int logsGained = client.player == null ? 0 : Math.max(0, countLogs(client) - startLogs);
		int oreItemsGained = client.player == null ? 0 : Math.max(0, countOreItems(client) - startOreItems);
		String report = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "]"
				+ " duration=" + seconds + "s, logs_gained=" + logsGained
				+ ", ore_items_gained=" + oreItemsGained + ", hostile_mobs_engaged=" + engagedMobs
				+ ", hostile_mobs_defeated=" + defeatedMobs
				+ ", stop_reason=" + reason + System.lineSeparator();
		try {
			Path reportPath = Path.of("run", "auto-bot-report.txt");
			Files.createDirectories(reportPath.getParent());
			Files.writeString(reportPath, report, StandardCharsets.UTF_8,
					StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
			chatMessage(client, "Run report saved to run/auto-bot-report.txt (" + seconds + " seconds).");
		} catch (IOException exception) {
			chatMessage(client, "Could not save run report: " + exception.getMessage());
		}
		reportActive = false;
		runTicks = 0;
	}

	private static void lookDownWhileSearchingForTree(Minecraft client) {
		if (client.player == null) {
			return;
		}
		client.player.setXRot(Math.max(client.player.getXRot(), 25.0F));
	}

	private static void stopInputs(Minecraft client) {
		client.options.keyUp.setDown(false);
		client.options.keyAttack.setDown(false);
	}

	private static void message(Minecraft client, String text) {
		if (client.player != null) client.player.sendOverlayMessage(Component.literal("[Auto Bot] " + text));
	}

	private static void chatMessage(Minecraft client, String text) {
		if (client.player != null) {
			client.player.sendSystemMessage(Component.literal("[Auto Bot] " + text));
		}
	}

	private static long elapsedSeconds() {
		return runTicks / 20;
	}
}
