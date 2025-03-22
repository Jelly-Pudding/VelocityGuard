# VelocityGuard Plugin
**VelocityGuard** is a Minecraft Paper 1.21.4 plugin designed to prevent movement-based cheats like speed and flight. It uses a fully asynchronous and multithreaded design to efficiently detect and prevent illegal movement without impacting server performance.

## Features
- **Fully Asynchronous**: Movement checks processed off the main thread
- **Multithreaded Design**: Uses thread pool for efficient processing
- **Smart Detection**: Accurately distinguishes between legitimate movements (sprint-jumping) and cheats
- **Teleport Recognition**: Automatically detects teleport commands to prevent false positives
- **Game-Aware**: Handles different movement states (swimming, flying, elytra gliding)
- **Instant Feedback**: Immediately teleports players back when speed and flight cheats are detected
- **No Lingering Punishment**: Players who stop cheating are not continually penalised
- **Low Performance Impact**: Designed for high-performance servers

## Installation
1. Download the latest release [here](https://github.com/Jelly-Pudding/velocityguard/releases/latest).
2. Place the `.jar` file in your Minecraft server's `plugins` folder.
3. Restart your server.

## Configuration
In `config.yml`, you can configure:
```yaml
# Check settings
checks:
  speed:
    # Maximum horizontal speed in blocks per SECOND (not per tick)
    # Default vanilla walking speed: ~4.3 blocks/s
    # Default vanilla sprinting speed: ~5.6 blocks/s
    # Sprint-jumping can reach speeds of ~9-10 blocks/s temporarily
    # Recommended setting: 10.0 to allow for normal sprint-jumping with a buffer
    max-horizontal-speed: 10.0
  
  flight:
    # Maximum vertical speed in blocks per SECOND (not per tick)
    # Normal jumping speed: ~9.0 blocks/s at initial jump
    # Normal falling speed: up to 20 blocks/s
    # Recommended setting: 9.0 for normal gameplay with jumps
    max-vertical-speed: 9.0

# General settings
settings:
  # Whether to compensate for player latency in movement checks
  # Strongly recommended to keep this enabled
  lag-compensation: true
```

## How It Works
1. Player movements are monitored and queued for processing
2. The plugin checks if movements exceed configured speed limits
3. Special conditions like sprint-jumping, swimming, or flying are handled with adjusted thresholds
4. Violation points are added when cheats are detected
5. Cheating players are immediately teleported back to prevent progress
6. Violation points decay rapidly when players stop cheating

## Commands
- None currently implemented

## Permissions
No specific permissions are currently implemented in the plugin.

## Support Me
Donations will help me with the development of this project.

One-off donation: https://ko-fi.com/lolwhatyesme

Patreon: https://www.patreon.com/lolwhatyesme
