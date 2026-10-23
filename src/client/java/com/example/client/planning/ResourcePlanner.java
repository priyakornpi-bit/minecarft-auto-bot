package com.example.client.planning;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;

/** กำหนดว่าทรัพยากรใดเกี่ยวข้องกับงานเก็บไม้และขุดแร่ในเวอร์ชันปัจจุบัน */
public final class ResourcePlanner {
	private static int woodTarget = 256;

	private ResourcePlanner() {
	}

	public static void setWoodTarget(int target) {
		if (target < 0) throw new IllegalArgumentException("Wood target cannot be negative");
		woodTarget = target;
	}

	public static int woodTarget() {
		return woodTarget;
	}

	public static boolean needsMoreWood(int currentCount) {
		return currentCount < woodTarget;
	}

	public static boolean isWantedItem(ItemEntity item) {
		String path = BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).getPath();
		return (woodTarget > 0 && item.getItem().is(ItemTags.LOGS))
				|| path.endsWith("_ore")
				|| path.startsWith("raw_")
				|| path.equals("coal")
				|| path.equals("diamond")
				|| path.equals("emerald")
				|| path.equals("lapis_lazuli")
				|| path.equals("redstone")
				|| path.equals("quartz")
				|| path.equals("amethyst_shard");
	}
}
