package com.example.client.planning;

import java.util.Map;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * ตรวจวัตถุดิบสำหรับงานคราฟต์พื้นฐาน
 *
 * การกดคราฟต์จริงต้องทำผ่าน screen/menu ของ Minecraft เพื่อให้สถานะ client และ
 * server ตรงกัน คลาสนี้จึงยังไม่แก้ inventory โดยตรง แต่สร้างแผนและตรวจของให้ก่อน
 */
public final class CraftingManager {
	private CraftingManager() {
	}

	public static CraftingPlan plan(LocalPlayer player, Map<ItemStack, Integer> requirements) {
		for (Map.Entry<ItemStack, Integer> requirement : requirements.entrySet()) {
			int available = count(player, requirement.getKey());
			if (available < requirement.getValue()) {
				return new CraftingPlan(false, requirement.getKey().copy(), requirement.getValue() - available);
			}
		}
		return new CraftingPlan(true, ItemStack.EMPTY, 0);
	}

	private static int count(LocalPlayer player, ItemStack target) {
		int total = 0;
		for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
			ItemStack stored = player.getInventory().getItem(slot);
			if (ItemStack.isSameItemSameComponents(stored, target)) total += stored.getCount();
		}
		return total;
	}

	public record CraftingPlan(boolean ready, ItemStack missingItem, int missingCount) {
	}
}
