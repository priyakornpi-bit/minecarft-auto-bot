package com.example.client;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.example.client.inventory.InventoryManager;
import com.example.client.navigation.GridPathfinder;
import com.example.client.perception.PerceptionSnapshot;
import com.example.client.planning.CraftingManager;
import com.example.client.planning.ResourcePlanner;
import com.example.client.rl.QLearningAgent;
import com.example.client.rl.RewardCalculator;
import com.example.client.rl.RewardConfig;
import com.example.client.rl.RlAction;
import com.example.client.rl.RlCommand;
import com.example.client.rl.RlEpisode;
import com.example.client.rl.RlState;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.SwingAnimation;
import net.minecraft.world.level.block.state.BlockState;

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
	private static boolean autoEating;
	private static int previousFoodSlot = -1;
	private static int foodNoticeCooldown;
	private static int toolNoticeCooldown;
	private static int durabilityNoticeCooldown;
	private static int respawnCooldown;
	private static boolean deathRecoveryPending;
	private static int criticalJumpTicks;
	private static int rlActionTicks;
	private static RlAction activeRlAction;
	private static BlockPos targetOre;
	private static BlockPos targetWood;
	private static LivingEntity targetMob;
	private static ItemEntity targetItem;
	private static ItemStack targetItemStack;
	private static int targetItemCountBefore;
	private static ItemCollectionStatus itemCollectionStatus = ItemCollectionStatus.NONE;
	private static PerceptionSnapshot latestPerception;
	private static List<BlockPos> activePath = List.of();
	private static BlockPos pathTarget;
	private static int pathIndex;
	private static int pathRefreshCooldown;
	private static final GridPathfinder PATHFINDER = new GridPathfinder();
	private static final QLearningAgent RL_AGENT = new QLearningAgent();
	private static final RlEpisode RL_EPISODE = new RlEpisode(RL_AGENT);
	private static final RewardCalculator REWARD_CALCULATOR = new RewardCalculator(RewardConfig.defaults());
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
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				LiteralArgumentBuilder.<FabricClientCommandSource>literal("autobot")
						.then(LiteralArgumentBuilder.<FabricClientCommandSource>literal("wood")
								.then(RequiredArgumentBuilder.<FabricClientCommandSource, Integer>argument(
										"amount", IntegerArgumentType.integer(0, 2304))
										.executes(context -> {
											ResourcePlanner.setWoodTarget(
													IntegerArgumentType.getInteger(context, "amount"));
											context.getSource().sendFeedback(Component.literal(
													"[Auto Bot] Wood target set to "
															+ ResourcePlanner.woodTarget() + " logs."));
											return 1;
										})))));
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
				targetItem = null;
				targetItemStack = null;
				targetItemCountBefore = 0;
				itemCollectionStatus = ItemCollectionStatus.NONE;
				autoEating = false;
				previousFoodSlot = -1;
				foodNoticeCooldown = 0;
				toolNoticeCooldown = 0;
				respawnCooldown = 0;
				deathRecoveryPending = false;
				criticalJumpTicks = 0;
				rlActionTicks = 0;
				activeRlAction = null;
				clearPath();
				RL_EPISODE.reset();
				try {
					RL_AGENT.load(Path.of("run", "auto-bot-q-table.tsv"));
				} catch (IOException exception) {
					chatMessage(client, "Could not load RL Q-table: " + exception.getMessage());
				}
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
			targetItem = null;
			targetItemStack = null;
			targetItemCountBefore = 0;
			itemCollectionStatus = ItemCollectionStatus.NONE;
			stopEating(client, false);
			clearPath();
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
		if (!client.player.isAlive()) {
			handleDeathAndRespawn(client);
			return;
		}
		deathRecoveryPending = false;
		latestPerception = PerceptionSnapshot.capture(client);
		runTicks++;
		checkToolDurability(client);
		if (runTicks % 20 == 0) {
			chatMessage(client, "The system is running: " + elapsedSeconds() + " seconds.");
		}
		if (autoEating && client.player.getFoodData().getFoodLevel() >= 20) stopEating(client, true);
		if (targetMob != null && !targetMob.isAlive()) {
			defeatedMobs++;
			targetMob = null;
			criticalJumpTicks = 0;
		}
		LivingEntity nearbyHostile = findNearbyHostile(client);
		if (nearbyHostile != null && nearbyHostile != targetMob) {
			engagedMobs++;
		}
		targetMob = nearbyHostile;
		// Creeper และ Warden อันตรายแม้ยังไม่ถึงระยะโจมตี จึงไม่ปล่อยให้ RL เลือกเข้าไปสู้
		if (mustKeepDistanceFrom(targetMob, client.player)) {
			handleFlee(client);
			return;
		}
		RlAction action = chooseRlAction(client);
		if (action == RlAction.EAT && handleAutoEating(client)) return;
		if (action == RlAction.FLEE && targetMob != null) {
			handleFlee(client);
			return;
		}
		if (action == RlAction.DEFEND && targetMob != null) {
			handleMob(client);
			return;
		}
		if (targetMob != null) {
			// ถ้า policy ไม่ส่งคำสั่งต่อสู้ ให้เลือกหนีเป็น fallback เพื่อไม่เดินเข้าหาศัตรู
			handleFlee(client);
			return;
		}
		if (action == RlAction.FOLLOW_COMMAND) {
			if (targetItem == null || !targetItem.isAlive()) {
				targetItem = findNearbyItem(client);
				if (targetItem != null) {
					targetItemStack = targetItem.getItem().copy();
					targetItemCountBefore = countMatchingItems(client, targetItemStack);
					itemCollectionStatus = ItemCollectionStatus.APPROACHING;
				}
			}
			if (targetItem != null) {
				handleItem(client);
				return;
			}
		}
		if (action == RlAction.FOLLOW_COMMAND || action == RlAction.GATHER) {
			if (runGatherStep(client)) return;
		}
		if (action == RlAction.FOLLOW_COMMAND || action == RlAction.MINE) {
			runMiningStep(client);
			return;
		}
		stopInputs(client);
	}

	/** เลือก action ที่อนุญาตในสถานะปัจจุบัน แล้วฝึกจากรางวัลของช่วงก่อนหน้า */
	private static RlAction chooseRlAction(Minecraft client) {
		var player = client.player;
		boolean needsWood = ResourcePlanner.needsMoreWood(countLogs(client));
		List<RlAction> legal = new ArrayList<>();
		int food = player.getFoodData().getFoodLevel();
		int foodSlot = findBestFoodSlot(player);
		if (food <= 6 && foodSlot >= 0) {
			legal.add(RlAction.EAT);
		} else {
			legal.add(RlAction.FOLLOW_COMMAND);
			if (needsWood) legal.add(RlAction.GATHER);
			else legal.add(RlAction.MINE);
			if (food <= 14 && foodSlot >= 0) legal.add(RlAction.EAT);
			if (targetMob != null) {
				legal.clear();
				legal.add(RlAction.DEFEND);
				if (player.getHealth() <= 12.0F) legal.add(RlAction.FLEE);
			}
		}

		boolean mustChoose = activeRlAction == null || rlActionTicks <= 0 || !legal.contains(activeRlAction);
		if (mustChoose) {
			if (activeRlAction != null) {
				boolean completedWoodGoal = activeRlAction == RlAction.GATHER
						&& !ResourcePlanner.needsMoreWood(countLogs(client));
				double reward = REWARD_CALCULATOR.score(player.isAlive(), player.getHealth(), food,
						player.getFoodData().getSaturationLevel(), completedWoodGoal);
				RL_EPISODE.addReward(reward);
			}
			RlCommand command = needsWood ? RlCommand.GATHER_WOOD : RlCommand.FOLLOW_USER_COMMAND;
			String context = "wood-target=" + ResourcePlanner.woodTarget();
			RlState state = RlState.observe(command, latestPerception, player, context);
			activeRlAction = RL_EPISODE.chooseAction(state, legal);
			rlActionTicks = 20;
		}
		rlActionTicks--;
		return activeRlAction;
	}

	private static boolean runGatherStep(Minecraft client) {
		if (!ResourcePlanner.needsMoreWood(countLogs(client))) return false;
		if (targetWood == null || !isLog(client.level, targetWood)) {
			targetWood = findNearbyLog(client, woodSearchRadius);
		}
		if (targetWood != null) {
			woodSearchRadius = 16;
			handleWood(client);
			return true;
		}
		if (woodScanCooldown-- <= 0) {
			woodScanCooldown = 20;
			woodSearchRadius = Math.min(96, woodSearchRadius + 16);
			chatMessage(client, "No tree nearby. Searching within " + woodSearchRadius + " blocks.");
		}
		lookDownWhileSearchingForTree(client);
		BlockPos ahead = client.player.blockPosition().relative(client.player.getDirection());
		if (canStandAt(client.level, ahead)) startSprintForward(client, false);
		else stopInputs(client);
		return true;
	}

	private static void runMiningStep(Minecraft client) {
		if (targetWood != null) {
			targetWood = null;
			chatMessage(client, "Wood target reached: " + countLogs(client) + "/"
					+ ResourcePlanner.woodTarget() + " logs.");
		}
		if (scanCooldown-- <= 0 || targetOre == null) {
			scanCooldown = 10;
			targetOre = findNearbyOre(client);
		}
		if (targetOre == null) {
			BlockPos ahead = client.player.blockPosition().relative(client.player.getDirection());
			if (canStandAt(client.level, ahead)) {
				startSprintForward(client, false);
				client.options.keyAttack.setDown(false);
			} else stopInputs(client);
			return;
		}
		if (client.level.getBlockState(targetOre).isAir()) {
			targetOre = null;
			client.options.keyAttack.setDown(false);
			clearPath();
			return;
		}

		var player = client.player;
		double dx = targetOre.getX() + 0.5 - player.getX();
		double dy = targetOre.getY() + 0.5 - player.getEyeY();
		double dz = targetOre.getZ() + 0.5 - player.getZ();
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (distance > 4.3) {
			NavigationResult result = navigateToInteraction(client, targetOre, 4.0);
			if (result == NavigationResult.MOVING) return;
		}
		facePosition(player, dx, dy, dz);
		if (!selectTool(client, "pickaxe")) return;
		client.options.keyUp.setDown(false);
		client.options.keySprint.setDown(false);
		client.options.keyAttack.setDown(distance <= 4.5);
	}

	/** ถอยไปยังระยะปลอดภัยของศัตรู โดย Creeper และ Warden จะได้ระยะมากเป็นพิเศษ */
	private static void handleFlee(Minecraft client) {
		if (client.player == null || targetMob == null) {
			stopInputs(client);
			return;
		}
		var player = client.player;
		double dx = player.getX() - targetMob.getX();
		double dz = player.getZ() - targetMob.getZ();
		double length = Math.max(0.001, Math.sqrt(dx * dx + dz * dz));
		double escapeDistance = safeDistance(targetMob) + 4.0;
		BlockPos escapeTarget = BlockPos.containing(
				player.getX() + dx / length * escapeDistance,
				player.getY(),
				player.getZ() + dz / length * escapeDistance);
		NavigationResult result = navigateToInteraction(client, escapeTarget, 0.6);
		if (result == NavigationResult.NO_PATH) {
			Direction left = player.getDirection().getCounterClockWise();
			BlockPos detour = player.blockPosition().relative(left);
			if (!canStandAt(client.level, detour)) detour = player.blockPosition().relative(left.getOpposite());
			if (canStandAt(client.level, detour)) {
				player.setYRot(left.toYRot());
				startSprintForward(client, player.onGround());
			} else stopInputs(client);
		}
	}

	// [1] กำหนดชุดสถานะการนำทาง
    private enum NavigationResult { ARRIVED, MOVING, NO_PATH }

    /**
     * เดินตามเส้นทาง A* และคำนวณเส้นทางใหม่เมื่อเป้าหมายเปลี่ยน หรือบล็อกบนเส้นทางถูกขวาง
     * 
     * @param client ออบเจกต์ตัวควบคุมเกม Minecraft
     * @param target พิกัดเป้าหมาย (BlockPos)
     * @param range ระยะโต้ตอบที่อนุญาต
     * @return NavigationResult ผลลัพธ์การนำทาง (ARRIVED, MOVING, หรือ NO_PATH)
     */
    private static NavigationResult navigateToInteraction(Minecraft client, BlockPos target, double range) {
        var player = client.player;
        
        // [2] Guard Clause: ตรวจสอบความปลอดภัยเบื้องต้น ป้องกัน NullPointerException (NPE)
        if (player == null || client.level == null) return NavigationResult.NO_PATH;

        // [3] คำนวณระยะห่างแกน X, Y, Z ระหว่างผู้เล่นกับเป้าหมาย (อ้างอิงจากจุดศูนย์กลางบล็อก +0.5)
        double tx = target.getX() + 0.5 - player.getX();
        double ty = target.getY() + 0.5 - player.getEyeY();
        double tz = target.getZ() + 0.5 - player.getZ();

        // [4] หากระยะห่างปัจจุบันน้อยกว่าหรือเท่ากับระยะ range ที่กำหนด คืนค่า ARRIVED (ถึงจุดหมายแล้ว)
        if (tx * tx + ty * ty + tz * tz <= range * range) return NavigationResult.ARRIVED;

        // [5] เช็กเงื่อนไขการคำนวณเส้นทางใหม่: เป้าหมายเปลี่ยน หรือโหนดถัดไปถูกบล็อกขวาง
        boolean targetChanged = pathTarget == null || !pathTarget.equals(target);
        boolean nextBlocked = !activePath.isEmpty() && pathIndex < activePath.size()
                && !canStandAt(client.level, activePath.get(pathIndex));

        // [6] สั่งประมวลผล A* ใหม่เมื่อเข้าเงื่อนไข หรือ Cooldown หมดลง
        if (targetChanged || activePath.isEmpty() || nextBlocked || pathRefreshCooldown-- <= 0) {
            List<BlockPos> goals = new ArrayList<>();
            int radius = (int) Math.ceil(range) + 1;

            // สแกนหาโหนดเป้าหมายที่ยืนได้อย่างปลอดภัยรอบๆ รัศมี range
            for (BlockPos candidate : BlockPos.betweenClosed(
                    target.offset(-radius, -radius, -radius), target.offset(radius, radius, radius))) {
                if (!canStandAt(client.level, candidate)) continue;
                double gx = candidate.getX() - target.getX();
                double gy = candidate.getY() - target.getY();
                double gz = candidate.getZ() - target.getZ();
                if (gx * gx + gy * gy + gz * gz <= range * range) goals.add(candidate.immutable());
            }

            // นิยามเงื่อนไข Passability สำหรับสแกนพื้นที่ผ่านได้ (ต้องไม่เป็นลาวา และมีพื้นยืน)
            GridPathfinder.Passability passability = new GridPathfinder.Passability() {
                @Override
                public boolean canStandAt(BlockPos feet) {
                    return TemplateModClient.canStandAt(client.level, feet);
                }

                @Override
                public boolean hasFloorAt(BlockPos floor) {
                    return !isLava(client.level, floor) && !isEmptyCollision(client.level, floor);
                }
            };

            // เรียกใช้อัลกอริทึม A* เพื่อค้นหาเส้นทาง
            activePath = PATHFINDER.findPathToAny(player.blockPosition(), goals, passability, 1800);
            pathTarget = target.immutable();
            pathIndex = activePath.size() > 1 ? 1 : 0;
            pathRefreshCooldown = 10; // รีเซ็ต Cooldown การสแกน A* ใหม่เป็น 10 Ticks

            if (activePath.isEmpty()) return NavigationResult.NO_PATH;
        }

        // [7] ขยับพิกัด pathIndex ไปยังโหนดถัดไป หากบอทยืนทับพิกัดเดิมแล้ว
        while (pathIndex < activePath.size() && player.blockPosition().equals(activePath.get(pathIndex))) pathIndex++;
        
        // [8] หากเดินครบทุกโหนดในเส้นทางแล้ว ให้ล้างเส้นทางแล้ว คืนค่า ARRIVED
        if (pathIndex >= activePath.size()) {
            clearPath();
            return NavigationResult.ARRIVED;
        }

        // [9] ดึงพิกัดถัดไปจากเส้นทาง หากยืนไม่ได้ให้ยกเลิกการเดิน
        BlockPos next = activePath.get(pathIndex);
        if (!canStandAt(client.level, next)) {
            clearPath();
            stopInputs(client);
            return NavigationResult.MOVING;
        }

        // [10] คำนวณองศาหมุนหน้า และสั่งกดปุ่มเดินหน้า/กระโดดไปยังโหนดถัดไป
        double mx = next.getX() + 0.5 - player.getX();
        double mz = next.getZ() + 0.5 - player.getZ();
        facePosition(player, mx, 0.0, mz);
        startSprintForward(client, shouldJumpTowardNext(client, next));
        client.options.keyAttack.setDown(false);

        return NavigationResult.MOVING;
    }

	private static void clearPath() {
		activePath = List.of();
		pathTarget = null;
		pathIndex = 0;
		pathRefreshCooldown = 0;
	}

	/** กระโดดเมื่อ A* ต้องขึ้นหนึ่งบล็อก หรือมีบล็อกเตี้ยขวางและมีจุดลงที่ยืนได้ */
	private static boolean shouldJumpTowardNext(Minecraft client, BlockPos next) {
		var player = client.player;
		if (player == null || !player.onGround()) return false;
		BlockPos feet = player.blockPosition();
		if (next.getY() > feet.getY()) return true;
		BlockPos ahead = feet.relative(player.getDirection());
		return !isEmptyCollision(client.level, ahead)
				&& isEmptyCollision(client.level, ahead.above())
				&& canStandAt(client.level, ahead.above());
	}

	private static LivingEntity findNearbyHostile(Minecraft client) {
		var player = client.player;
		if (player == null || client.level == null) {
			return null;
		}
		// Slime ไม่ได้สืบทอด Monster ใน Minecraft เวอร์ชันนี้ แต่เป็น Enemy เช่นเดียวกับศัตรูทั่วไป
		return client.level.getEntitiesOfClass(
				LivingEntity.class,
				player.getBoundingBox().inflate(24.0),
				mob -> mob instanceof Enemy && mob.isAlive()
						&& mob.distanceToSqr(player) <= safeDistance(mob) * safeDistance(mob)
		).stream()
				.min((first, second) -> Double.compare(
						first.distanceToSqr(player), second.distanceToSqr(player)))
				.orElse(null);
	}

	/** ระยะนี้กัน Creeper ระเบิด และกัน Warden ที่โจมตีแรง/ไกลกว่ามอนสเตอร์ทั่วไป */
	private static double safeDistance(LivingEntity mob) {
		if (mob instanceof Warden) return 24.0;
		if (mob instanceof Creeper) return 12.0;
		return 12.0;
	}

	private static boolean mustKeepDistanceFrom(LivingEntity mob, net.minecraft.client.player.LocalPlayer player) {
		return (mob instanceof Creeper || mob instanceof Warden)
				&& player.distanceToSqr(mob) <= safeDistance(mob) * safeDistance(mob);
	}

	/** เริ่มกินเมื่อระดับอาหารต่ำ แล้วถือปุ่มใช้ไอเทมจนอาหารกลับมาเต็ม */
	private static boolean handleAutoEating(Minecraft client) {
		var player = client.player;
		if (player == null) return false;
		int food = player.getFoodData().getFoodLevel();
		if (!autoEating && food > 14) return false;
		if (autoEating && food >= 20) {
			stopEating(client, true);
			return false;
		}

		int foodSlot = findBestFoodSlot(player);
		if (foodSlot < 0) {
			stopEating(client, true);
			if (food <= 6) {
				if (foodNoticeCooldown-- <= 0) {
					foodNoticeCooldown = 100;
					chatMessage(client, "Food is critical but no safe food is in the hotbar; continuing until you stop it.");
				}
				return false;
			}
			if (foodNoticeCooldown-- <= 0) {
				foodNoticeCooldown = 100;
				chatMessage(client, "Food is low. Put edible food in the hotbar so I can eat.");
			}
			return false;
		}

		if (!autoEating) {
			autoEating = true;
			previousFoodSlot = player.getInventory().getSelectedSlot();
		}
		player.getInventory().setSelectedSlot(foodSlot);
		client.options.keyUp.setDown(false);
		client.options.keyAttack.setDown(false);
		client.options.keyUse.setDown(true);
		return true;
	}

	private static int findBestFoodSlot(net.minecraft.client.player.LocalPlayer player) {
		int bestSlot = -1;
		int bestScore = Integer.MIN_VALUE;
		var inventory = player.getInventory();
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty() || stack.get(DataComponents.FOOD) == null) continue;
			String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
			if (path.equals("rotten_flesh") || path.equals("spider_eye") || path.equals("poisonous_potato")
					|| path.equals("pufferfish") || path.equals("raw_chicken")) continue;
			int score = path.startsWith("cooked_") || path.equals("bread") || path.equals("baked_potato") ? 100 : 50;
			if (stack.getCount() > 1) score += 1;
			if (score > bestScore) {
				bestScore = score;
				bestSlot = slot;
			}
		}
		return bestSlot;
	}

	private static void stopEating(Minecraft client, boolean restoreSlot) {
		client.options.keyUse.setDown(false);
		if (restoreSlot && autoEating && previousFoodSlot >= 0 && client.player != null) {
			client.player.getInventory().setSelectedSlot(previousFoodSlot);
		}
		autoEating = false;
		previousFoodSlot = -1;
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
		client.options.keySprint.setDown(distance > 3.5);
		client.options.keyJump.setDown(false);
		client.options.keyAttack.setDown(false);
		if (mobAttackCooldown > 0) mobAttackCooldown--;
		if (distance > 3.5 || mobAttackCooldown > 0 || player.getAttackStrengthScale(0.5F) < 0.9F) {
			criticalJumpTicks = 0;
			return;
		}

		// Critical hit ต้องโจมตีขณะกำลังตก และห้าม sprint จึงกระโดดหนึ่งครั้งก่อนแล้วรอจังหวะตก
		if (canAttemptCritical(player)) {
			client.options.keyUp.setDown(false);
			client.options.keySprint.setDown(false);
			if (player.onGround()) {
				criticalJumpTicks = 1;
				client.options.keyJump.setDown(true);
				return;
			}
			criticalJumpTicks++;
			if (player.getDeltaMovement().y >= 0.0 && criticalJumpTicks < 12) return;
		}

		if (distance <= 3.5) {
			client.gameMode.attack(player, targetMob);
			player.swing(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT, true);
			mobAttackCooldown = 4;
			criticalJumpTicks = 0;
		}
	}

	private static boolean canAttemptCritical(net.minecraft.client.player.LocalPlayer player) {
		return !player.isInWater() && !player.onClimbable() && !player.isPassenger();
	}

	private static ItemEntity findNearbyItem(Minecraft client) {
		var player = client.player;
		if (player == null || client.level == null) return null;
		return client.level.getEntitiesOfClass(
				ItemEntity.class,
				player.getBoundingBox().inflate(16.0),
				item -> item.isAlive() && ResourcePlanner.isWantedItem(item)
						&& InventoryManager.canStore(player, item.getItem())
		).stream()
				.min((first, second) -> Double.compare(
						first.distanceToSqr(player), second.distanceToSqr(player)))
				.orElse(null);
	}

	private static void handleItem(Minecraft client) {
		var player = client.player;
		if (player == null || targetItem == null || !targetItem.isAlive()) {
			if (player != null && targetItemStack != null) {
				int countAfter = countMatchingItems(client, targetItemStack);
				if (countAfter > targetItemCountBefore) {
					itemCollectionStatus = ItemCollectionStatus.COLLECTED;
					chatMessage(client, "Item collected: "
							+ BuiltInRegistries.ITEM.getKey(targetItemStack.getItem()).getPath());
				} else if (!InventoryManager.canStore(player, targetItemStack)) {
					itemCollectionStatus = ItemCollectionStatus.INVENTORY_FULL;
					chatMessage(client, "Item not collected: inventory is full.");
				} else {
					itemCollectionStatus = ItemCollectionStatus.DESPAWNED;
				}
			}
			targetItem = null;
			targetItemStack = null;
			targetItemCountBefore = 0;
			stopInputs(client);
			return;
		}
		double dx = targetItem.getX() - player.getX();
		double dy = targetItem.getY() - player.getEyeY();
		double dz = targetItem.getZ() - player.getZ();
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (distance <= 1.5) {
			stopInputs(client);
			return;
		}
		NavigationResult route = navigateToInteraction(client, targetItem.blockPosition(), 1.5);
		if (route == NavigationResult.MOVING) return;
		facePosition(player, dx, dy, dz);
		if (blockedAhead(client, player)) {
			itemCollectionStatus = ItemCollectionStatus.INACCESSIBLE;
			handleDetour(client, player);
			return;
		}
		startSprintForward(client, false);
		client.options.keyAttack.setDown(false);
	}

	private static int countMatchingItems(Minecraft client, ItemStack target) {
		return client.player == null ? 0 : InventoryManager.count(client.player, target);
	}

	private static boolean blockedAhead(Minecraft client, net.minecraft.client.player.LocalPlayer player) {
		BlockPos ahead = player.blockPosition().relative(player.getDirection());
		return !isEmptyCollision(client.level, ahead) || !isEmptyCollision(client.level, ahead.above());
	}

	/**
     * [Refactored 3D Detour] ฟังก์ชันหลบสิ่งกีดขวางฉุกเฉินแบบ 3 มิติ
     * ตำแหน่งในไฟล์จริง: บรรทัดที่ 684 (ใน TemplateModClient_2.java)
     */
    private static void handleDetour(Minecraft client, net.minecraft.client.player.LocalPlayer player) {
        // [1] Guard Clause: ตรวจสอบความพร้อมของตัวละครและแมปเกม ป้องกันเกม Crash
        if (player == null || client.level == null) {
            stopInputs(client);
            return;
        }

        Direction direction = player.getDirection();
        Direction left = direction.getCounterClockWise();
        Direction right = direction.getClockWise();
        BlockPos feet = player.blockPosition();

        // [2] สแกนหาพิกัดทางเบี่ยงที่ยืนได้ปลอดภัย ทั้งฝั่งซ้ายและขวา แบบ 3 มิติ (Y+1, Y, Y-1)
        BlockPos detourPos = find3DDetourPos(client.level, feet.relative(left));
        Direction targetDirection = left;

        if (detourPos == null) {
            detourPos = find3DDetourPos(client.level, feet.relative(right));
            targetDirection = right;
        }

        // [3] หากสแกนทั้งซ้ายและขวาแล้วไม่เจอจุดยืนที่ปลอดภัยเลย ให้สั่งหยุดเดิน
        if (detourPos == null) {
            stopInputs(client);
            return;
        }

        // [4] หมุนมุมมอง (Yaw) ไปยังทิศทางเบี่ยงที่พบ
        player.setYRot(targetDirection.toYRot());
        player.setXRot(0.0F);

        // [5] สั่งเดินหน้า และสั่งกระโดดอัตโนมัติถ้าจุดเบี่ยงอยู่สูงกว่าระดับเท้าเดิม (Y+1)
        boolean needJump = detourPos.getY() > feet.getY();
        startSprintForward(client, needJump);
        client.options.keyAttack.setDown(false);
    }

    /**
     * [ Helper Function เพิ่มใหม่ ] วางต่อท้ายฟังก์ชัน handleDetour (ประมาณบรรทัด 705)
     * สแกนพิกัดยืนปลอดภัยในแนวดิ่ง (+1, 0, -1 บล็อก)
     */
    private static BlockPos find3DDetourPos(net.minecraft.client.multiplayer.ClientLevel level, BlockPos sidePos) {
        if (canStandAt(level, sidePos)) return sidePos;         // ระดับความสูงเดิม (Y)
        if (canStandAt(level, sidePos.above())) return sidePos.above(); // ขึ้นเนิน 1 บล็อก (Y+1)
        if (canStandAt(level, sidePos.below())) return sidePos.below(); // ลงเนิน 1 บล็อก (Y-1)
        return null;
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
		if (distance > 4.5) {
			NavigationResult route = navigateToInteraction(client, targetWood, 4.0);
			if (route == NavigationResult.MOVING) return;
		}
		if (avoidanceTicks > 0) {
			avoidanceTicks--;
			player.setYRot(player.getYRot());
			player.setXRot(0.0F);
			startSprintForward(client, false);
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
			startSprintForward(client, false);
			client.options.keyAttack.setDown(false);
			return;
		}
		blockedTicks = 0;
		facePosition(player, dx, dy, dz);
		if (!selectTool(client, "axe")) return;
		client.options.keyUp.setDown(distance > 4.0);
		client.options.keySprint.setDown(distance > 4.0);
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

	private static boolean selectTool(Minecraft client, String toolType) {
		if (client.player == null) return false;
		var inventory = client.player.getInventory();
		int usableSlot = InventoryManager.findUsableToolSlot(client.player, toolType);
		if (usableSlot >= 0) {
			inventory.setSelectedSlot(usableSlot);
			CraftingManager.cancel(client);
			return true;
		}

		BlockPos table = CraftingManager.isWorkingOn(toolType)
				? null
				: CraftingManager.findNearbyCraftingTable(client.level, client.player.blockPosition(), 16);
		if (table != null && table.distToCenterSqr(client.player.position()) > 16.0) {
			NavigationResult route = navigateToInteraction(client, table, 3.5);
			if (route == NavigationResult.MOVING) return false;
		}
		CraftingManager.CraftingStatus crafting = CraftingManager.ensureTool(client, toolType, table);
		if (crafting == CraftingManager.CraftingStatus.WORKING) {
			stopInputs(client);
			return false;
		}
		if (crafting == CraftingManager.CraftingStatus.NO_TABLE) {
			BlockPos placementStand = CraftingManager.findNearbyTablePlacementStand(client, 12);
			if (placementStand != null) {
				NavigationResult route = navigateToInteraction(client, placementStand, 0.8);
				if (route == NavigationResult.MOVING) return false;
			}
		}
		int fallbackSlot = InventoryManager.findBestToolSlot(client.player, toolType);
		if (fallbackSlot >= 0) {
			if (crafting == CraftingManager.CraftingStatus.NO_MATERIALS
					&& InventoryManager.remainingDurability(inventory.getItem(fallbackSlot)) < 0.12
					&& toolNoticeCooldown-- <= 0) {
				toolNoticeCooldown = 100;
				chatMessage(client, "Tool is worn; missing materials or a nearby crafting table for replacement.");
			}
			inventory.setSelectedSlot(fallbackSlot);
			return true;
		}
		if (toolNoticeCooldown-- <= 0) {
			toolNoticeCooldown = 100;
			chatMessage(client, "No " + toolType + " is available; place a crafting table nearby and gather materials.");
		}
		return false;
	}

	/** แจ้งเตือนเมื่อขวานหรือพลั่วใน hotbar ใกล้พัง; ค่าเหลือก็มาจาก PerceptionSnapshot */
	private static void checkToolDurability(Minecraft client) {
		if (latestPerception == null || !latestPerception.worldAvailable()) return;
		if (durabilityNoticeCooldown > 0) {
			durabilityNoticeCooldown--;
			return;
		}
		int axe = latestPerception.axeDurabilityPercent();
		int shovel = latestPerception.shovelDurabilityPercent();
		if ((axe >= 0 && axe <= 20) || (shovel >= 0 && shovel <= 20)) {
			durabilityNoticeCooldown = 200;
			if (axe >= 0 && axe <= 20 && shovel >= 0 && shovel <= 20) {
				chatMessage(client, "Axe and shovel durability are low (20% or less).");
			} else if (axe >= 0 && axe <= 20) {
				chatMessage(client, "Axe durability is low (20% or less).");
			} else {
				chatMessage(client, "Shovel durability is low (20% or less).");
			}
		}
	}

	/** ตายแล้วล้างเฉพาะเป้าหมายชั่วคราว ส่งคำสั่งเกิดใหม่ และเก็บเป้าหมายหลักของผู้ใช้ไว้ */
	private static void handleDeathAndRespawn(Minecraft client) {
		stopInputs(client);
		stopEating(client, false);
		clearPath();
		targetOre = null;
		targetWood = null;
		targetMob = null;
		targetItem = null;
		targetItemStack = null;
		activeRlAction = null;
		rlActionTicks = 0;
		criticalJumpTicks = 0;
		CraftingManager.cancel(client);
		if (!deathRecoveryPending) {
			RL_EPISODE.addReward(RewardConfig.defaults().deathPenalty());
			deathRecoveryPending = true;
			chatMessage(client, "You died. Respawning and resuming the current task.");
		}
		if (respawnCooldown-- <= 0) {
			respawnCooldown = 20;
			client.player.respawn();
		}
	}

	private static boolean isLava(net.minecraft.client.multiplayer.ClientLevel level, BlockPos pos) {
		return level.getFluidState(pos).is(FluidTags.LAVA);
	}

	private static boolean isEmptyCollision(net.minecraft.client.multiplayer.ClientLevel level, BlockPos pos) {
		return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
	}

	private static void finishRun(Minecraft client, String reason) {
		CraftingManager.cancel(client);
		if (!reportActive) return;
		stopEating(client, true);
		clearPath();
		long seconds = elapsedSeconds();
		int logsGained = client.player == null ? 0 : Math.max(0, countLogs(client) - startLogs);
		int oreItemsGained = client.player == null ? 0 : Math.max(0, countOreItems(client) - startOreItems);
		String report = "[" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")) + "]"
				+ " duration=" + seconds + "s, logs_gained=" + logsGained
				+ ", ore_items_gained=" + oreItemsGained + ", hostile_mobs_engaged=" + engagedMobs
				+ ", hostile_mobs_defeated=" + defeatedMobs
				+ ", stop_reason=" + reason + System.lineSeparator();
		try {
			RL_EPISODE.finish(client.player != null && !client.player.isAlive()
					? RewardConfig.defaults().deathPenalty() : 0.0);
			Path qTablePath = Path.of("run", "auto-bot-q-table.tsv");
			RL_AGENT.save(qTablePath);
			chatMessage(client, "RL Q-table saved (" + RL_AGENT.getKnownStateCount() + " states).");
		} catch (IOException exception) {
			chatMessage(client, "Could not save RL Q-table: " + exception.getMessage());
		}
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
		client.options.keySprint.setDown(false);
		client.options.keyAttack.setDown(false);
		client.options.keyUse.setDown(false);
		client.options.keyJump.setDown(false);
	}

	/** กดเดินและวิ่งพร้อมกัน; ใช้ jump เฉพาะก้าวขึ้นบล็อกเพื่อลดการกระโดดสะดุด */
	private static void startSprintForward(Minecraft client, boolean jump) {
		client.options.keyUp.setDown(true);
		client.options.keySprint.setDown(true);
		client.options.keyJump.setDown(jump);
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
