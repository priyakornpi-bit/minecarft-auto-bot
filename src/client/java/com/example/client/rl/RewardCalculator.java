package com.example.client.rl;

/** Converts survival, hunger, and command outcomes into one scalar RL reward. */
public final class RewardCalculator {
	private final RewardConfig config;

	public RewardCalculator(RewardConfig config) {
		this.config = config;
	}

	public double score(boolean alive, float health, int foodLevel, float saturation, boolean commandCompleted) {
		if (!alive) {
			return config.deathPenalty();
		}

		double reward = config.alive();
		if (foodLevel >= 18) {
			reward += config.wellFed();
			if (saturation >= 6.0F) reward += config.goodSaturation();
		} else if (foodLevel <= 6) {
			reward -= config.hungryPenalty();
		}
		if (health <= 6.0F) reward -= config.lowHealthPenalty();
		if (commandCompleted) reward += config.commandCompleted();
		return reward;
	}
}
