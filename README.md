# Minecraft Auto Bot

Fabric client mod for singleplayer automation experiments.

## Controls

- **,** toggles automatic mode. The player gathers nearby logs, defends against nearby hostile mobs, then scans nearby blocks for ore.
- **.** immediately disables automation and releases the movement and attack controls.

Automatic mode only runs in a local singleplayer world. When a run ends, a report is appended to `run/auto-bot-report.txt` with duration, log and ore item gains, defeated hostile mobs, and the stop reason. The ore scan checks a small area around the player and mines the nearest exposed ore block. This is an early example, so it does not manage tools or inventory.

## Reinforcement learning scaffold

The client RL package provides a tabular Q-learning core for mapping commands and observations to high-level actions. It includes discretized health, food, saturation, nearby-hostile and command-context state; epsilon-greedy action selection; transition updates; configurable reward weights; and Q-table persistence. The default reward is `+0.05` for each sampled alive step, `+0.20` when food is at least 18, another `+0.10` when saturation is at least 6, `-0.50` for food at most 6 or health at most 6, `-100` on death, and `+5` when a command completes.

The existing automation is still heuristic. To train and control it with RL, connect the selected `RlAction` to the matching controller behavior in `TemplateModClient.onClientTick`, and call `RlEpisode.addReward(...)` once per decision interval. Start by loading `run/auto-bot-q-table.tsv`, then save the agent when an episode ends. `RlEpisode` performs the Q update between consecutive decisions; use `finish(...)` for terminal outcomes such as death or a completed run. Sample rewards at the same cadence as decisions rather than every game tick.

```java
QLearningAgent agent = new QLearningAgent();
Path qTable = Path.of("run", "auto-bot-q-table.tsv");
agent.load(qTable);
RlEpisode episode = new RlEpisode(agent);
RewardCalculator rewards = new RewardCalculator(RewardConfig.defaults());

RlState state = RlState.observe(command, player, hostileNearby, "wood-gathering");
RlAction action = episode.chooseAction(state, List.of(RlAction.GATHER, RlAction.EAT, RlAction.FLEE));
// Execute the selected action, then add one reward sample per decision interval.
episode.addReward(rewards.score(player.isAlive(), player.getHealth(),
    player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel(), commandCompleted));
// At episode end:
episode.finish(terminalReward);
agent.save(qTable);
```

The main extension points are in `src/client/java/com/example/client/rl`: add command goals in `RlCommand`, add controller actions in `RlAction`, adjust discretization in `RlState`, and tune reward weights in `RewardConfig`.

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
