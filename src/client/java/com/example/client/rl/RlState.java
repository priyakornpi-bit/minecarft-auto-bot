package com.example.client.rl;

import net.minecraft.world.entity.player.Player;

import com.example.client.perception.PerceptionSnapshot;
import com.example.client.inventory.InventoryManager;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** A compact, discretized observation suitable for a tabular Q-table. */
public record RlState(
		RlCommand command,
		int healthBand,
		int foodBand,
		int saturationBand,
		int axeDurabilityBand,
		int shovelDurabilityBand,
		boolean hostileNearby,
		String context) {

	public RlState {
		context = context == null ? "default" : context;
	}

	public static RlState observe(RlCommand command, Player player, boolean hostileNearby, String context) {
		float health = player.getHealth();
		int food = player.getFoodData().getFoodLevel();
		float saturation = player.getFoodData().getSaturationLevel();
		return new RlState(
				command,
				band(health, 6.0F, 12.0F, 16.0F),
				band(food, 6.0F, 12.0F, 18.0F),
				band(saturation, 2.0F, 6.0F, 12.0F),
				durabilityBand(InventoryManager.bestToolRemainingPercent(player, "axe")),
				durabilityBand(InventoryManager.bestToolRemainingPercent(player, "shovel")),
				hostileNearby,
				context);
	}

	/** แปลง snapshot จากระบบ perception เป็น state ที่ Q-table ใช้ตัดสินใจ */
	public static RlState observe(
			RlCommand command, PerceptionSnapshot perception, Player player, String context) {
		if (!perception.worldAvailable() || player == null) {
			throw new IllegalArgumentException("Cannot create an RL state without a live player and world");
		}
		return new RlState(
				command,
				band(perception.health(), 6.0F, 12.0F, 16.0F),
				band(perception.food(), 6.0F, 12.0F, 18.0F),
				band(player.getFoodData().getSaturationLevel(), 2.0F, 6.0F, 12.0F),
				durabilityBand(perception.axeDurabilityPercent()),
				durabilityBand(perception.shovelDurabilityPercent()),
				perception.nearbyHostiles() > 0,
				context);
	}

	/** Stable, delimiter-safe key used to index and persist the Q-table. */
	public String key() {
		String raw = command.name() + "\n" + healthBand + "\n" + foodBand + "\n"
				+ saturationBand + "\n" + axeDurabilityBand + "\n" + shovelDurabilityBand
				+ "\n" + hostileNearby + "\n" + context;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	private static int band(float value, float first, float second, float third) {
		if (value <= first) return 0;
		if (value <= second) return 1;
		if (value <= third) return 2;
		return 3;
	}

	/** แยกความทนทานเป็นช่วง; 0 หมายถึงไม่มีเครื่องมือ และ 1 หมายถึงใกล้พัง */
	private static int durabilityBand(int remainingPercent) {
		if (remainingPercent < 0) return 0;
		if (remainingPercent <= 12) return 1;
		if (remainingPercent <= 35) return 2;
		if (remainingPercent <= 70) return 3;
		return 4;
	}
}
