# VelocityGuard Plugin
**VelocityGuard** is a lightweight, lenient Minecraft Paper 1.21.8 plugin focused on preventing extreme movement (excessive speed and flight). It uses direct packet interception to immediately stop illegal movement spikes that cause chunk-loading lag. It is intentionally lenient: it will not block most cheats, but it will reliably curb the most extreme movements that harm server performance.

## Features
- **Direct Detection**: Detects cheating in real-time at the packet level.
- **Movement Blocking**: Temporarily blocks movement when violations are detected.
- **Pattern Detection**: Identifies suspicious movement patterns.
- **Adaptive System**: Handles knockback, boats, horses, potions, trident riptide, and special movement states (swimming, flying, elytra gliding).
- **Optional Flight Checks**: Toggle whether to enforce anti-flight checks; keep only speed limiting if you prefer.
- **Happy Ghast Compatible**: Fully supports players riding Happy Ghasts without triggering false flight violations.
- **Latency Compensation**: Automatically adjusts speed checks based on player ping to prevent false positives on laggy connections.

## Installation
1. Download the latest release [here](https://github.com/Jelly-Pudding/velocityguard/releases/latest).
2. Place the `.jar` file in your Minecraft server's `plugins` folder.
3. Restart your server.

## Configuration
In `config.yml`, you can configure:
```yaml
# Configuration for detecting violations.
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

    # Latency compensation settings
    latency-compensation:
      # Whether to enable latency compensation
      enabled: true
      # Compensation factors for different ping ranges
      # 1.0 means no compensation. Higher values allow more speed
      very-low-ping: 1.1      # 21-50ms ping
      low-ping: 2.1           # 51-100ms ping
      medium-ping: 2.9        # 101-200ms ping
      high-ping: 3.6          # 201-300ms ping
      very-high-ping: 4.6     # 301-500ms ping
      extreme-ping: 5.7       # 501-750ms ping
      ultra-ping: 6.6         # 751-1000ms ping
      insane-ping: 7.5        # 1000+ms ping

    # Burst tolerance settings - number of consecutive violations allowed before punishment
    # Higher ping players get more tolerance due to network inconsistency
    burst-tolerance:
      default: 15             # ≤20ms ping (no compensation)
      very-low-ping: 15       # 21-50ms ping
      low-ping: 20            # 51-100ms ping
      medium-ping: 22         # 101-200ms ping
      high-ping: 24           # 201-300ms ping
      very-high-ping: 27      # 301-500ms ping
      extreme-ping: 30        # 501-750ms ping
      ultra-ping: 33          # 751-1000ms ping
      insane-ping: 35         # 1000+ms ping

    # Knockback adjustment settings
    knockback:
      # Multiplier for speed threshold after taking damage.
      multiplier: 6.0
      # Duration in milliseconds that knockback effect lasts.
      duration: 1000

    # Trident riptide handling.
    riptide:
      # Multiplier for speed threshold after using a trident with riptide enchantment.
      multiplier: 8.0
      # Duration in milliseconds that the riptide effect lasts.
      duration: 3000

    # Elytra movement handling
    elytra:
      # Multiplier for speed threshold while gliding with elytra
      gliding-multiplier: 3.0
      # Duration in milliseconds that landing adjustment lasts after stopping gliding
      landing-duration: 1500

    # Vehicle speed multipliers.
    # Regular vehicle speed multiplier.
    vehicle-speed-multiplier: 1.9

    # Ice vehicle speed multiplier - only applies when vehicles are on ice.
    # Boats on ice can move especially fast.
    vehicle-ice-speed-multiplier: 4.3

    # Extra buffer multiplier applied to all speed checks.
    # This provides some leeway to prevent false positives.
    # Lower values = stricter checks, higher values = more lenient.
    buffer-multiplier: 1.1

  flight:
    # Whether to run flight checks (hovering/ascending while not gliding/flying)
    # Disable this if you only want speed limiting and no flight enforcement.
    enabled: true

# General settings.
settings:
  # Only enable if you are developing or testing the plugin
  # as this results in verbose logging.
  debug-mode: false
```

## How It Works
1. The plugin intercepts player movement packets before they're processed.
2. Each movement is checked against configured speed limits and (optionally) flight rules.
3. The plugin considers various factors like knockback, potion effects, special movement states, and vehicle types (Happy Ghasts are exempt from flight checks).
4. Sophisticated pattern detection identifies potential speed cheats that stay just under the defined thresholds.
5. Invalid movements are rejected, and player movement is temporarily blocked.
6. Players receive notification when cheating is detected.
7. After the block duration ends, players can immediately move normally again.

## Commands
- `/velocityguard reload`: Reloads the plugin configuration (requires the `velocityguard.admin` permission).

## Permissions
`velocityguard.admin`: Allows reloading the plugin configuration (default: op).

## Support Me
[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/K3K715TC1R)