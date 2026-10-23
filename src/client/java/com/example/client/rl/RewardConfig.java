package com.example.client.rl;

/** Reward weights, intentionally centralized so they are easy to tune. */
public record RewardConfig(
		double alive,
		double wellFed,
		double goodSaturation,
		double hungryPenalty,
		double lowHealthPenalty,
		double deathPenalty,
		double commandCompleted) {

	public static RewardConfig defaults() {
		return new RewardConfig(0.05, 0.20, 0.10, 0.50, 0.50, -100.0, 5.0);
	}
}
