package com.example.client.planning;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.item.ItemEntity;

/** กำหนดว่าทรัพยากรใดเกี่ยวข้องกับงานเก็บไม้และขุดแร่ในเวอร์ชันปัจจุบัน */
public final class ResourcePlanner {
	private ResourcePlanner() {
	}

	public static boolean isWantedItem(ItemEntity item) {
		String path = BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).getPath();
		return item.getItem().is(ItemTags.LOGS)
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
