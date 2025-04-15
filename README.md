# VelocityGuard Plugin
**VelocityGuard** is a lightweight Minecraft Paper 1.21.4 plugin designed to prevent extreme movement-based cheats like speed and flight. It uses direct packet interception for immediate detection and prevention of illegal movement. While it does **not** outright prevent all speed and flight cheats, it effectively stops the most extreme cases.

## Features
- **Direct Detection**: Detects cheating in real-time at the packet level.
- **Movement Blocking**: Temporarily blocks movement when violations are detected.
- **Pattern Detection**: Identifies suspicious movement patterns.
- **Adaptive System**: Handles knockback, boats, horses, potions, and special movement states (swimming, flying, elytra gliding).

## Installation
1. Download the latest release.
2. Place the `.jar` file in your Minecraft server's `plugins` folder.
3. Restart your server.

## Configuration
In `config.yml`, you can configure:
```yaml
checks:
  speed:
    # Maximum horizontal speed in blocks per SECOND
    # Default vanilla walking speed: ~4.3 blocks/s
    # Default vanilla sprinting speed: ~5.6 blocks/s
    # Sprint-jumping can reach speeds of ~9-10 blocks/s temporarily
    # Recommended setting: 10.0 to allow for normal sprint-jumping with a buffer
    max-horizontal-speed: 10.0

    # How many seconds to cancel movement when a violation is detected.
    # This will just refuse all movement packets for this duration.
    # Has to be an integer.
    cancel-duration: 3

    # Knockback handling
    knockback:
      # Multiplier for speed threshold after taking damage.
      multiplier: 6.0
      # Duration in milliseconds that knockback effect lasts.
      duration: 1000

    # Vehicle speed multipliers.
    # Regular vehicle speed multiplier.
    vehicle-speed-multiplier: 1.1

    # Ice vehicle speed multiplier - only applies when vehicles are on ice.
    # Boats on ice can move especially fast.
    vehicle-ice-speed-multiplier: 3.6

    # Extra buffer multiplier applied to all speed checks.
    # This provides some leeway to prevent false positives.
    # Lower values = stricter checks, higher values = more lenient.
    buffer-multiplier: 1.35

# General settings.
settings:
  # Only enable if you are developing or testing the plugin
  # as this results in verbose logging.
  debug-mode: false
```

## How It Works
1. The plugin intercepts player movement packets before they're processed.
2. Each movement is checked against configured speed limits and flight rules.
3. The plugin considers various factors like knockback, potion effects, and special movement states.
4. Sophisticated pattern detection identifies potential speed cheats that stay just under the defined thresholds.
5. Invalid movements are rejected, and player movement is temporarily blocked.
6. Players receive notification when cheating is detected.
7. After the block duration ends, players can immediately move normally again.

## Commands
- `/velocityguard reload`: Reloads the plugin configuration (requires the `velocityguard.admin` permission).

## Permissions
`velocityguard.admin`: Allows reloading the plugin configuration (default: op).

## Support Me
Donations will help me with the development of this project.

One-off donation: https://ko-fi.com/lolwhatyesme

Patreon: https://www.patreon.com/lolwhatyesme
