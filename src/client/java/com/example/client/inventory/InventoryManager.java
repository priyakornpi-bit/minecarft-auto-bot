package com.example.client.inventory;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;

/** เครื่องมืออ่าน inventory และตรวจว่าของใหม่สามารถเก็บเข้ากระเป๋าได้หรือไม่ */
public final class InventoryManager {
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
}
