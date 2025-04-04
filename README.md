# VelocityGuard Plugin
**VelocityGuard** is a lightweight Minecraft Paper 1.21.4 plugin designed to prevent extreme movement-based cheats like speed and flight. It uses direct packet interception for immediate detection and prevention of illegal movement.

## Features
- **Simple Design**: Straightforward code with minimal complexity
- **Direct Detection**: Detects cheating in real-time at the packet level
- **Immediate Prevention**: Immediately teleports players back when cheats are detected
- **No Punishment System**: Players who stop cheating can immediately move normally
- **Game-Aware**: Handles different movement states (swimming, flying, elytra gliding)

## Installation
1. Download the latest release.
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

# General settings
settings:
  # Enable debug mode for more detailed logging
  debug-mode: false
```

## How It Works
1. The plugin intercepts player movement packets before they're processed
2. Each movement is checked against configured speed limits and flight rules
3. Invalid movements are rejected at the packet level, preventing cheating
4. Players receive notification when cheating is detected
5. As soon as players stop cheating, they can move freely again

## Support Me
Donations will help me with the development of this project.

One-off donation: https://ko-fi.com/lolwhatyesme

Patreon: https://www.patreon.com/lolwhatyesme
