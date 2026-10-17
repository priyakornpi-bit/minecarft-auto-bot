# Minecraft Auto Bot

Fabric client mod for singleplayer automation experiments.

## Controls

- **,** toggles automatic mode. The player gathers nearby logs, defends against nearby hostile mobs, then scans nearby blocks for ore.
- **.** immediately disables automation and releases the movement and attack controls.

Automatic mode only runs in a local singleplayer world. When a run ends, a report is appended to `run/auto-bot-report.txt` with duration, log and ore item gains, defeated hostile mobs, and the stop reason. The ore scan checks a small area around the player and mines the nearest exposed ore block. This is an early example, so it does not manage tools or inventory.

## Setup

For setup instructions, please see the [Fabric Documentation page](https://docs.fabricmc.net/develop/getting-started/creating-a-project#setting-up) related to the IDE that you are using.

## License

This template is available under the CC0 license. Feel free to learn from it and incorporate it in your own projects.
