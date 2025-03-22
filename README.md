# VelocityGuard

A lightweight but effective anti-cheat plugin that focuses specifically on preventing speed hacks and flight hacks in Minecraft servers.

## Features

- **Speed Hack Detection**: Monitors player movement speeds and detects when players move faster than possible in vanilla gameplay
- **Flight Hack Detection**: Detects when players are flying without permission
- **Elytra Support**: Properly handles legitimate elytra flight mechanics
- **Two-Way Flight Detection**: Checks both upward and downward vertical movement for suspicious activity
- **Fully Asynchronous Design**: All checks run off the main server thread to prevent server lag
- **Packet-Level Interception**: Uses ProtocolLib to intercept movement packets for faster detection
- **Latency Compensation**: Accounts for player ping to reduce false positives
- **Configurable**: Easily customize detection thresholds
- **Violation Tracking**: Records violations for monitoring purposes

## Installation

1. Download the latest release from [GitHub Releases](#)
2. Install [ProtocolLib](https://www.spigotmc.org/resources/protocollib.1997/) on your server
3. Place the VelocityGuard JAR file in your server's `plugins` folder
4. Restart your server
5. Edit the configuration in `plugins/VelocityGuard/config.yml` if needed

## Requirements

- Bukkit/Spigot/Paper server
- Java 8 or higher
- ProtocolLib

## Configuration

The plugin's configuration file contains the following options:

```yaml
# Check settings
checks:
  speed:
    # Maximum horizontal speed in blocks per tick
    max-horizontal-speed: 0.6
  
  flight:
    # Maximum vertical speed in blocks per tick
    max-vertical-speed: 0.7

# General settings
settings:
  # Whether to compensate for player latency in movement checks
  lag-compensation: true
```

## Technical Design

VelocityGuard uses a high-performance architecture:

- **Packet Interception**: Movement data is captured at the packet level before normal event processing
- **Asynchronous Processing**: All movement checks run off the main thread using a dedicated thread pool
- **Thread Safety**: Uses concurrent collections to ensure thread safety in data access
- **Event Queuing**: Movement checks are queued and batch processed to reduce CPU impact
- **Minimal Teleports**: Only teleports players when a violation is detected to minimize lag

## Support

If you encounter any issues or have suggestions for improvements, please open an issue on the [GitHub repository](#). 