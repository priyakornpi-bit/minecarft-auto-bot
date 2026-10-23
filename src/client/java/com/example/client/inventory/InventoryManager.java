package com.example.client.inventory;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** เครื่องมืออ่าน inventory และตรวจว่าของใหม่สามารถเก็บเข้ากระเป๋าได้หรือไม่ */
public final class InventoryManager {
	private static final double MIN_TOOL_REMAINING = 0.12;

	private InventoryManager() {
	}

	public static boolean canStore(LocalPlayer player, ItemStack dropped) {
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stored = player.getInventory().getItem(slot);
			if (stored.isEmpty() || (ItemStack.isSameItemSameComponents(stored, dropped)
					&& stored.getCount() < stored.getMaxStackSize())) {
				return true;
			}
		}
		return false;
	}

	public static int count(LocalPlayer player, ItemStack target) {
		int total = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stored = player.getInventory().getItem(slot);
			if (ItemStack.isSameItemSameComponents(stored, target)) {
				total += stored.getCount();
			}
		}
		return total;
	}

	/** คืน hotbar slot ของเครื่องมือชนิดที่ยังเหลือความทนทานตามเกณฑ์ */
	public static int findUsableToolSlot(LocalPlayer player, String toolType) {
		int bestSlot = -1;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!isTool(stack, toolType)) continue;
			double remaining = remainingDurability(stack);
			if (remaining < MIN_TOOL_REMAINING) continue;
			double score = materialTier(stack) * 100.0 + remaining * 10.0;
			if (score > bestScore) {
				bestScore = score;
				bestSlot = slot;
			}
		}
		return bestSlot;
	}

	/** คืนเครื่องมือที่ทนที่สุด แม้จะใกล้พัง เพื่อใช้เป็น fallback ชั่วคราว */
	public static int findBestToolSlot(LocalPlayer player, String toolType) {
		int bestSlot = -1;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (!isTool(stack, toolType)) continue;
			double score = materialTier(stack) * 100.0 + remainingDurability(stack) * 10.0;
			if (score > bestScore) {
				bestScore = score;
				bestSlot = slot;
			}
		}
		return bestSlot;
	}

	public static double remainingDurability(ItemStack stack) {
		int maximum = stack.getMaxDamage();
		if (maximum <= 0) return 1.0;
		return Math.max(0.0, (maximum - stack.getDamageValue()) / (double) maximum);
	}

	/** ค่าความทนทานที่เหลือเป็นเปอร์เซ็นต์ของเครื่องมือชนิดนั้นใน hotbar (-1 ถ้าไม่มี) */
	public static int bestToolRemainingPercent(Player player, String toolType) {
		double bestRemaining = -1.0;
		for (int slot = 0; slot < 9; slot++) {
			ItemStack stack = player.getInventory().getItem(slot);
			if (isTool(stack, toolType)) bestRemaining = Math.max(bestRemaining, remainingDurability(stack));
		}
		return bestRemaining < 0.0 ? -1 : (int) Math.round(bestRemaining * 100.0);
	}

	private static boolean isTool(ItemStack stack, String toolType) {
		return !stack.isEmpty()
				&& BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().endsWith("_" + toolType)
				&& stack.getMaxDamage() > 0;
	}

	private static int materialTier(ItemStack stack) {
		String path = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
		if (path.startsWith("netherite_")) return 6;
		if (path.startsWith("diamond_")) return 5;
		if (path.startsWith("iron_")) return 4;
		if (path.startsWith("stone_")) return 3;
		if (path.startsWith("golden_")) return 2;
		return 1;
	}
}
