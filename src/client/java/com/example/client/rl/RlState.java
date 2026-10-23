package com.example.client.rl;

import net.minecraft.world.entity.player.Player;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** A compact, discretized observation suitable for a tabular Q-table. */
public record RlState(
		RlCommand command,
		int healthBand,
		int foodBand,
		int saturationBand,
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
				hostileNearby,
				context);
	}

	/** Stable, delimiter-safe key used to index and persist the Q-table. */
	public String key() {
		String raw = command.name() + "\n" + healthBand + "\n" + foodBand + "\n"
				+ saturationBand + "\n" + hostileNearby + "\n" + context;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	private static int band(float value, float first, float second, float third) {
		if (value <= first) return 0;
		if (value <= second) return 1;
		if (value <= third) return 2;
		return 3;
	}
}
