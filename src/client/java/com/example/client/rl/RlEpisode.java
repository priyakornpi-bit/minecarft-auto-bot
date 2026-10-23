package com.example.client.rl;

import java.util.List;

/** Holds the pending transition and accumulates reward until the next decision. */
public final class RlEpisode {
	private final QLearningAgent agent;
	private String previousState;
	private RlAction previousAction;
	private double accumulatedReward;

	public RlEpisode(QLearningAgent agent) {
		this.agent = agent;
	}

	public RlAction chooseAction(RlState state, List<RlAction> legalActions) {
		String stateKey = state.key();
		if (previousAction != null) {
			agent.learn(previousState, previousAction, accumulatedReward, stateKey, legalActions, false);
		}
		accumulatedReward = 0.0;
		previousState = stateKey;
		previousAction = agent.chooseAction(stateKey, legalActions);
		return previousAction;
	}

	public void addReward(double reward) {
		if (previousAction != null) accumulatedReward += reward;
	}

	public void finish(double terminalReward) {
		if (previousAction != null) {
			agent.learn(previousState, previousAction, accumulatedReward + terminalReward,
					previousState, List.of(), true);
		}
		reset();
	}

	public void reset() {
		previousState = null;
		previousAction = null;
		accumulatedReward = 0.0;
	}
}
