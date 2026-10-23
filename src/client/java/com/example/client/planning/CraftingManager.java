package com.example.client.planning;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractCraftingMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.block.state.BlockState;

/**
 * ทำงานคราฟต์ผ่านเมนูจริงของ Minecraft เพื่อให้ client ส่งการเปลี่ยน slot ไปยัง server
 * รองรับการเปิด crafting table ที่อยู่ใกล้ และวางวัตถุดิบสำหรับขวาน/พลั่ว/พลั่วขุด
 */
public final class CraftingManager {
	private static final int MAX_OPEN_WAIT_TICKS = 40;
	private static String requestedTool;
	private static CraftingPhase phase = CraftingPhase.IDLE;
	private static ToolRecipe recipe;
	private static PrepJob prepJob;
	private static PrepPhase prepPhase = PrepPhase.IDLE;
	private static BlockPos tablePlacement;
	private static int tablePlacementWait;
	private static int previousSelectedSlot = -1;
	private static int waitTicks;

	private CraftingManager() {
	}

	/** ค้นหาโต๊ะคราฟต์ใกล้ผู้เล่น โดยไม่อ่านหรือแก้ inventory */
	public static BlockPos findNearbyCraftingTable(ClientLevel level, BlockPos origin, int radius) {
		BlockPos nearest = null;
		double bestDistance = Double.MAX_VALUE;
		for (BlockPos pos : BlockPos.betweenClosed(
				origin.offset(-radius, -Math.min(radius, 6), -radius),
				origin.offset(radius, Math.min(radius, 6), radius))) {
			BlockState state = level.getBlockState(pos);
			String path = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
			if (!path.equals("crafting_table")) continue;
			double distance = pos.distSqr(origin);
			if (distance < bestDistance) {
				bestDistance = distance;
				nearest = pos.immutable();
			}
		}
		return nearest;
	}

	/** ขอสั่งทำเครื่องมือ หากไม่มีวัสดุหรือโต๊ะจะคืนสถานะโดยไม่แก้ inventory */
	public static CraftingStatus ensureTool(Minecraft client, String toolType, BlockPos table) {
		if (client.player == null || client.gameMode == null) return CraftingStatus.UNAVAILABLE;
		if ((phase != CraftingPhase.IDLE || prepJob != null || tablePlacement != null)
				&& !toolType.equals(requestedTool)) reset();
		if (requestedTool == null) requestedTool = toolType;

		if (prepJob != null) return tickPreparation(client);
		if (tablePlacement != null) {
			if (isCraftingTable(client, tablePlacement)) {
				if (previousSelectedSlot >= 0) client.player.getInventory().setSelectedSlot(previousSelectedSlot);
				tablePlacement = null;
				previousSelectedSlot = -1;
				tablePlacementWait = 0;
				return CraftingStatus.WORKING;
			} else if (++tablePlacementWait > MAX_OPEN_WAIT_TICKS) {
				if (previousSelectedSlot >= 0) client.player.getInventory().setSelectedSlot(previousSelectedSlot);
				tablePlacement = null;
				previousSelectedSlot = -1;
				return CraftingStatus.NO_TABLE;
			} else return CraftingStatus.WORKING;
		}

		if (phase == CraftingPhase.IDLE) {
			if (table == null) {
				if (countCraftingTables(client.player.getInventory()) > 0) {
					return placeOrPrepareTable(client);
				}
				int planks = countTag(client.player.getInventory(), ItemTags.PLANKS);
				if (planks >= 4) return startPreparation(client, PrepTask.CRAFT_TABLE);
				if (findLogInventoryIndex(client.player.getInventory()) >= 0) {
					return startPreparation(client, PrepTask.CRAFT_PLANKS);
				}
				return CraftingStatus.NO_MATERIALS;
			}
			if (countItem(client.player.getInventory(), "stick") < 2) {
				if (countTag(client.player.getInventory(), ItemTags.PLANKS) >= 2) {
					return startPreparation(client, PrepTask.CRAFT_STICKS);
				}
				if (findLogInventoryIndex(client.player.getInventory()) >= 0) {
					return startPreparation(client, PrepTask.CRAFT_PLANKS);
				}
				return CraftingStatus.NO_MATERIALS;
			}
			recipe = chooseRecipe(client.player.getInventory(), toolType);
			if (recipe == null) {
				if (findLogInventoryIndex(client.player.getInventory()) >= 0) {
					return startPreparation(client, PrepTask.CRAFT_PLANKS);
				}
				return CraftingStatus.NO_MATERIALS;
			}
			requestedTool = toolType;
			BlockHitResult hit = new BlockHitResult(
					new Vec3(table.getX() + 0.5, table.getY() + 1.0, table.getZ() + 0.5), Direction.UP, table, false);
			client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
			phase = CraftingPhase.WAIT_FOR_SCREEN;
			waitTicks = 0;
			return CraftingStatus.WORKING;
		}

		if (!(client.player.containerMenu instanceof CraftingMenu menu)) {
			if (++waitTicks > MAX_OPEN_WAIT_TICKS) reset();
			return CraftingStatus.WORKING;
		}
		waitTicks = 0;

		switch (phase) {
			case WAIT_FOR_SCREEN -> phase = CraftingPhase.PLACE_MATERIAL;
			case PLACE_MATERIAL -> {
				if (!placeOneOrMore(client, menu, recipe.materialInventoryIndex(), recipe.materialGridCells())) {
					reset();
					return CraftingStatus.NO_MATERIALS;
				}
				phase = CraftingPhase.PLACE_STICKS;
			}
			case PLACE_STICKS -> {
				if (!placeOneOrMore(client, menu, recipe.stickInventoryIndex(), recipe.stickGridCells())) {
					reset();
					return CraftingStatus.NO_MATERIALS;
				}
				phase = CraftingPhase.TAKE_RESULT;
			}
			case TAKE_RESULT -> {
				Slot result = menu.getResultSlot();
				if (result.hasItem() && result.getItem().is(recipe.output())) {
					client.gameMode.handleContainerInput(menu.containerId, result.index, 0,
							ContainerInput.QUICK_MOVE, client.player);
					phase = CraftingPhase.WAIT_FOR_OUTPUT;
				} else if (++waitTicks > MAX_OPEN_WAIT_TICKS) {
					reset();
					return CraftingStatus.NO_MATERIALS;
				}
			}
			case WAIT_FOR_OUTPUT -> {
				int toolInventoryIndex = findTool(client.player.getInventory(), toolType);
				if (toolInventoryIndex >= 0) {
					putToolInHotbar(client, menu, toolInventoryIndex, toolType);
					client.setScreenAndShow(null);
					reset();
					return CraftingStatus.WORKING;
				}
				if (++waitTicks > MAX_OPEN_WAIT_TICKS) {
					reset();
					return CraftingStatus.NO_MATERIALS;
				}
			}
			case IDLE -> { }
		}
		return CraftingStatus.WORKING;
	}

	public static boolean isWorkingOn(String toolType) {
		return toolType.equals(requestedTool)
				&& (phase != CraftingPhase.IDLE || prepJob != null || tablePlacement != null);
	}

	/** ยกเลิกงานค้างเมื่อหยุดบอท และปิดเมนูที่ระบบเปิดไว้ */
	public static void cancel(Minecraft client) {
		boolean menuWasOpened = phase != CraftingPhase.IDLE || prepJob != null;
		if (client.player != null && previousSelectedSlot >= 0) {
			client.player.getInventory().setSelectedSlot(previousSelectedSlot);
		}
		if (menuWasOpened) client.setScreenAndShow(null);
		reset();
	}

	/** เตรียมวัตถุดิบในหน้าต่าง inventory แล้วทำงานต่อทีละ tick */
	private static CraftingStatus startPreparation(Minecraft client, PrepTask task) {
		Inventory inventory = client.player.getInventory();
		int sourceIndex = switch (task) {
			case CRAFT_PLANKS -> findLogInventoryIndex(inventory);
			case CRAFT_STICKS -> findTagInventoryIndex(inventory, ItemTags.PLANKS, 2);
			case CRAFT_TABLE -> findTagInventoryIndex(inventory, ItemTags.PLANKS, 4);
			case MOVE_TABLE -> findItemInventoryIndex(inventory, "crafting_table", 1);
		};
		if (sourceIndex < 0) return CraftingStatus.NO_MATERIALS;

		int baseline = switch (task) {
			case CRAFT_PLANKS -> countTag(inventory, ItemTags.PLANKS);
			case CRAFT_STICKS -> countItem(inventory, "stick");
			case CRAFT_TABLE -> countCraftingTables(inventory);
			case MOVE_TABLE -> 0;
		};
		prepJob = new PrepJob(task, sourceIndex, baseline);
		prepPhase = PrepPhase.OPEN_SCREEN;
		waitTicks = 0;
		client.setScreenAndShow(new InventoryScreen(client.player));
		return CraftingStatus.WORKING;
	}

	private static CraftingStatus tickPreparation(Minecraft client) {
		if (!(client.player.containerMenu instanceof InventoryMenu menu)) {
			if (prepPhase == PrepPhase.OPEN_SCREEN) {
				prepPhase = PrepPhase.PLACE_INPUTS;
				return CraftingStatus.WORKING;
			}
			if (++waitTicks > MAX_OPEN_WAIT_TICKS) {
				reset();
				return CraftingStatus.NO_MATERIALS;
			}
			return CraftingStatus.WORKING;
		}
		waitTicks = 0;
		if (prepPhase == PrepPhase.OPEN_SCREEN) {
			prepPhase = PrepPhase.PLACE_INPUTS;
			return CraftingStatus.WORKING;
		}

		if (prepJob.task() == PrepTask.MOVE_TABLE) {
			if (prepPhase == PrepPhase.PLACE_INPUTS) {
				int sourceSlot = findInventoryMenuSlot(menu.slots, client.player.getInventory(), prepJob.sourceInventoryIndex());
				if (sourceSlot < 0) {
					reset();
					return CraftingStatus.NO_MATERIALS;
				}
				client.gameMode.handleContainerInput(menu.containerId, sourceSlot,
						client.player.getInventory().getSelectedSlot(), ContainerInput.SWAP, client.player);
				client.setScreenAndShow(null);
				prepJob = null;
				prepPhase = PrepPhase.IDLE;
				return CraftingStatus.WORKING;
			}
			return CraftingStatus.WORKING;
		}

		if (prepPhase == PrepPhase.PLACE_INPUTS) {
			int[] cells = switch (prepJob.task()) {
				case CRAFT_PLANKS -> new int[]{0};
				case CRAFT_STICKS -> new int[]{0, 2};
				case CRAFT_TABLE -> new int[]{0, 1, 2, 3};
				case MOVE_TABLE -> new int[0];
			};
			if (!placeOneOrMore(client, menu, prepJob.sourceInventoryIndex(), cells)) {
				reset();
				return CraftingStatus.NO_MATERIALS;
			}
			prepPhase = PrepPhase.TAKE_RESULT;
			return CraftingStatus.WORKING;
		}

		if (prepPhase == PrepPhase.TAKE_RESULT) {
			Slot result = menu.getResultSlot();
			if (result.hasItem()) {
				client.gameMode.handleContainerInput(menu.containerId, result.index, 0,
						ContainerInput.QUICK_MOVE, client.player);
				prepPhase = PrepPhase.WAIT_FOR_OUTPUT;
				return CraftingStatus.WORKING;
			}
			if (++waitTicks > MAX_OPEN_WAIT_TICKS) {
				reset();
				return CraftingStatus.NO_MATERIALS;
			}
			return CraftingStatus.WORKING;
		}

		if (prepPhase == PrepPhase.WAIT_FOR_OUTPUT) {
			Inventory inventory = client.player.getInventory();
			int outputCount = switch (prepJob.task()) {
				case CRAFT_PLANKS -> countTag(inventory, ItemTags.PLANKS);
				case CRAFT_STICKS -> countItem(inventory, "stick");
				case CRAFT_TABLE -> countCraftingTables(inventory);
				case MOVE_TABLE -> 0;
			};
			int expectedIncrease = prepJob.task() == PrepTask.CRAFT_TABLE ? 1 : 4;
			if (outputCount >= prepJob.baselineOutputCount() + expectedIncrease) {
				client.setScreenAndShow(null);
				prepJob = null;
				prepPhase = PrepPhase.IDLE;
				return CraftingStatus.WORKING;
			}
			if (++waitTicks > MAX_OPEN_WAIT_TICKS) {
				reset();
				return CraftingStatus.NO_MATERIALS;
			}
		}
		return CraftingStatus.WORKING;
	}

	/** นำโต๊ะคราฟต์ไป hotbar หากจำเป็น แล้ววางลงบนพื้นปลอดภัยข้างผู้เล่น */
	private static CraftingStatus placeOrPrepareTable(Minecraft client) {
		Inventory inventory = client.player.getInventory();
		int tableIndex = findItemInventoryIndex(inventory, "crafting_table", 1);
		if (tableIndex < 0) return CraftingStatus.NO_MATERIALS;
		if (tableIndex >= 9) return startPreparation(client, PrepTask.MOVE_TABLE);

		BlockPos ground = findTableGround(client);
		if (ground == null) return CraftingStatus.NO_TABLE;
		int oldSlot = inventory.getSelectedSlot();
		previousSelectedSlot = oldSlot;
		inventory.setSelectedSlot(tableIndex);
		BlockHitResult hit = new BlockHitResult(
				new Vec3(ground.getX() + 0.5, ground.getY() + 1.0, ground.getZ() + 0.5), Direction.UP, ground, false);
		client.gameMode.useItemOn(client.player, InteractionHand.MAIN_HAND, hit);
		tablePlacement = ground.above().immutable();
		tablePlacementWait = 0;
		return CraftingStatus.WORKING;
	}

	private static BlockPos findTableGround(Minecraft client) {
		BlockPos origin = client.player.blockPosition();
		for (Direction direction : List.of(client.player.getDirection(), client.player.getDirection().getClockWise(),
				client.player.getDirection().getCounterClockWise(), client.player.getDirection().getOpposite())) {
			BlockPos ground = origin.relative(direction).below();
			BlockPos placement = ground.above();
			BlockState groundState = client.level.getBlockState(ground);
			BlockState placementState = client.level.getBlockState(placement);
			if (!groundState.getCollisionShape(client.level, ground).isEmpty()
					&& placementState.getCollisionShape(client.level, placement).isEmpty()
					&& !client.level.getFluidState(placement).is(net.minecraft.tags.FluidTags.LAVA)) return ground;
		}
		return null;
	}

	private static boolean isCraftingTable(Minecraft client, BlockPos pos) {
		return BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(pos).getBlock()).getPath().equals("crafting_table");
	}

	private static int countCraftingTables(Inventory inventory) {
		return countItem(inventory, "crafting_table");
	}

	private static int countItem(Inventory inventory, String itemPath) {
		int total = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().equals(itemPath)) {
				total += stack.getCount();
			}
		}
		return total;
	}

	private static int countTag(Inventory inventory, net.minecraft.tags.TagKey<Item> tag) {
		int total = 0;
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(tag)) total += stack.getCount();
		}
		return total;
	}

	private static int findTagInventoryIndex(Inventory inventory, net.minecraft.tags.TagKey<Item> tag, int count) {
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.is(tag) && stack.getCount() >= count) return slot;
		}
		return -1;
	}

	private static int findLogInventoryIndex(Inventory inventory) {
		return findTagInventoryIndex(inventory, ItemTags.LOGS, 1);
	}

	private static int findItemInventoryIndex(Inventory inventory, String itemPath, int count) {
		return findInventoryIndex(inventory, itemPath, count);
	}

	private static boolean placeOneOrMore(Minecraft client, AbstractCraftingMenu menu, int inventoryIndex, int[] gridCells) {
		int sourceSlot = findInventoryMenuSlot(menu.slots, client.player.getInventory(), inventoryIndex);
		if (sourceSlot < 0) return false;
		for (int cell : gridCells) {
			List<Slot> grid = menu.getInputGridSlots();
			if (cell < 0 || cell >= grid.size()) return false;
		}
		client.gameMode.handleContainerInput(menu.containerId, sourceSlot, 0, ContainerInput.PICKUP, client.player);
		for (int cell : gridCells) {
			int gridSlot = menu.getInputGridSlots().get(cell).index;
			client.gameMode.handleContainerInput(menu.containerId, gridSlot, 1, ContainerInput.PICKUP, client.player);
		}
		client.gameMode.handleContainerInput(menu.containerId, sourceSlot, 0, ContainerInput.PICKUP, client.player);
		return true;
	}

	private static ToolRecipe chooseRecipe(Inventory inventory, String toolType) {
		String[] materialIds = {"diamond", "iron_ingot", "gold_ingot", "cobblestone"};
		int[] materialCounts = toolType.equals("shovel") ? new int[]{1} : new int[]{3};
		int requiredMaterial = materialCounts[0];
		int stickIndex = findInventoryIndex(inventory, "stick", 2);
		if (stickIndex < 0) return null;

		for (String materialId : materialIds) {
			int materialIndex = findInventoryIndex(inventory, materialId, requiredMaterial);
			if (materialIndex < 0) continue;
			String prefix = switch (materialId) {
				case "diamond" -> "diamond";
				case "iron_ingot" -> "iron";
				case "gold_ingot" -> "golden";
				default -> "stone";
			};
			Item output = BuiltInRegistries.ITEM.getOptional(
					Identifier.fromNamespaceAndPath("minecraft", prefix + "_" + toolType)).orElse(null);
			if (output == null) continue;
			int[] materialCells;
			int[] stickCells = {4, 7};
			if (toolType.equals("axe")) materialCells = new int[]{0, 1, 3};
			else if (toolType.equals("shovel")) materialCells = new int[]{1};
			else if (toolType.equals("pickaxe")) materialCells = new int[]{0, 1, 2};
			else return null;
			return new ToolRecipe(materialIndex, stickIndex, materialCells, stickCells, output);
		}

		// Planks are a universal fallback for a wooden tool.
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.is(net.minecraft.tags.ItemTags.PLANKS) || stack.getCount() < requiredMaterial) continue;
			Item output = BuiltInRegistries.ITEM.getOptional(
					Identifier.fromNamespaceAndPath("minecraft", "wooden_" + toolType)).orElse(null);
			if (output == null) return null;
			int[] materialCells;
			if (toolType.equals("axe")) materialCells = new int[]{0, 1, 3};
			else if (toolType.equals("shovel")) materialCells = new int[]{1};
			else if (toolType.equals("pickaxe")) materialCells = new int[]{0, 1, 2};
			else return null;
			return new ToolRecipe(slot, stickIndex, materialCells, new int[]{4, 7}, output);
		}
		return null;
	}

	private static int findInventoryIndex(Inventory inventory, String itemPath, int count) {
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (stack.isEmpty() || stack.getCount() < count) continue;
			if (BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().equals(itemPath)) return slot;
		}
		return -1;
	}

	private static int findTool(Inventory inventory, String toolType) {
		for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
			ItemStack stack = inventory.getItem(slot);
			if (!stack.isEmpty() && BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("_" + toolType)) {
				return slot;
			}
		}
		return -1;
	}

	private static int findInventoryMenuSlot(List<Slot> menuSlots, Inventory inventory, int inventoryIndex) {
		for (Slot slot : menuSlots) {
			if (slot.container == inventory && slot.getContainerSlot() == inventoryIndex) return slot.index;
		}
		return -1;
	}

	private static void putToolInHotbar(Minecraft client, CraftingMenu menu, int inventoryIndex, String toolType) {
		Inventory inventory = client.player.getInventory();
		int sourceSlot = findInventoryMenuSlot(menu.slots, inventory, inventoryIndex);
		if (sourceSlot < 0) return;
		int targetHotbar = -1;
		for (int slot = 0; slot < 9; slot++) {
			if (inventory.getItem(slot).isEmpty()) {
				targetHotbar = slot;
				break;
			}
		}
		if (targetHotbar < 0) {
			for (int slot = 0; slot < 9; slot++) {
				String path = BuiltInRegistries.ITEM.getKey(inventory.getItem(slot).getItem()).getPath();
				if (path.endsWith("_" + toolType) && inventory.getItem(slot).getMaxDamage() > 0
						&& inventory.getItem(slot).getDamageValue() >= inventory.getItem(slot).getMaxDamage() * 0.88) {
					targetHotbar = slot;
					break;
				}
			}
		}
		if (targetHotbar >= 0) {
			client.gameMode.handleContainerInput(menu.containerId, sourceSlot, targetHotbar,
					ContainerInput.SWAP, client.player);
		}
	}

	public static void reset() {
		requestedTool = null;
		phase = CraftingPhase.IDLE;
		recipe = null;
		prepJob = null;
		prepPhase = PrepPhase.IDLE;
		tablePlacement = null;
		tablePlacementWait = 0;
		previousSelectedSlot = -1;
		waitTicks = 0;
	}

	public enum CraftingStatus { WORKING, NO_TABLE, NO_MATERIALS, UNAVAILABLE }

	private enum CraftingPhase { IDLE, WAIT_FOR_SCREEN, PLACE_MATERIAL, PLACE_STICKS, TAKE_RESULT, WAIT_FOR_OUTPUT }

	private enum PrepPhase { IDLE, OPEN_SCREEN, PLACE_INPUTS, TAKE_RESULT, WAIT_FOR_OUTPUT }

	private enum PrepTask { CRAFT_PLANKS, CRAFT_STICKS, CRAFT_TABLE, MOVE_TABLE }

	private record PrepJob(PrepTask task, int sourceInventoryIndex, int baselineOutputCount) {
	}

	private record ToolRecipe(int materialInventoryIndex, int stickInventoryIndex,
			int[] materialGridCells, int[] stickGridCells, Item output) {
	}
}
