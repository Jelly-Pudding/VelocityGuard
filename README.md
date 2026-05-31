# VelocityGuard Plugin
**VelocityGuard** is a Minecraft Paper 26.1.2 lenient anti-cheat plugin that prevents extreme speed and flight cheats. Although it was custom built for [minecraftoffline.net](https://www.minecraftoffline.net), any server can use it. The plugin intercepts movement packets and runs a server-side physics simulation to predict the maximum displacement a player could legitimately produce each tick. It treats every movement packet as exactly one game tick and polices the rate of those packets against a server-anchored clock which means detection is consistent regardless of a player's ping and is immune to "timer" cheats that speed up the client's game loop. A developer API is also provided for other plugins to enforce flight checks on specific players on demand (e.g. within a no-fly zone).

## Features
- **Full 3D Physics Simulation**: Speed and flight checks are based on Minecraft's actual movement equations for both horizontal and vertical axes.
- **Ping-Independent Timer Check**: Each movement packet represents one game tick (50 ms). VelocityGuard accumulates that claimed time and compares it against real time anchored to a server clock the client cannot speed up.
- **Transaction-Based Timing**: A ping/pong (transaction) packet is sent to each player every tick; the forced echo gives a server-anchored clock used to keep timing honest under network jitter.
- **Direct Detection**: Detects cheating at the packet level on the Netty thread before the server processes the move.
- **Violation Buffer**: Excess displacement accumulates in a buffer that decays on clean packets so a single lag spike should not trigger a false positive.
- **Setback Enforcement**: On a confirmed violation the player is rubber-banded to their last valid position.
- **Adaptive Exemptions**: Handles knockback, riptide, elytra landing, potions, boats, horses, swimming, levitation, and happy ghasts automatically.
- **Leniency Multiplier**: A single top-level knob to loosen all checks at once without touching individual parameters.
- **Toggleable Flight Check**: Physics-based Y simulation catches hover and ascent cheats.
- **Developer API**: Lets other plugins enforce no-fly zones on specific players on demand.

## Installation
1. Download the latest release [here](https://github.com/Jelly-Pudding/velocityguard/releases/latest).
2. Place the `.jar` file in your Minecraft server's `plugins` folder.
3. Restart your server.

## Configuration
In `config.yml`:
```yaml
# VelocityGuard Configuration
#
# Speed enforcement uses a physics simulation.
# On each movement packet VelocityGuard runs Minecraft's own
# horizontal movement equations to predict the maximum displacement the player
# could legitimately produce and then compares that against what the packet claims.

checks:
  # Global leniency multiplier applied to every max-allowed displacement value.
  # Raise to allow more movement before a flag fires; lower toward 1.0 for
  # stricter enforcement. Must be >= 1.0.
  # For example 1.10 = 10% headroom above the raw physics prediction.
  leniency-multiplier: 1.10

  speed:
    # Extra displacement slack (blocks) added per expected tick.
    # Absorbs sub-block floating-point variance between client and server.
    per-tick-tolerance: 0.08

    # Accumulated excess displacement (blocks) before a violation fires.
    # The buffer decays on clean packets so only *sustained* cheating reaches
    # this value.
    violation-threshold: 2.0

    # Blocks subtracted from the violation buffer per clean (normal) packet.
    violation-decay: 0.15

    # Knockback from being hit: the large initial velocity is modelled by
    # scaling the tracked speed; this controls how long and how strongly.
    knockback:
      multiplier: 6.0
      duration: 1000  # ms over which the allowance linearly fades to 0

    # Riptide trident boost.
    riptide:
      multiplier: 2.5
      duration: 3000

    # Elytra landing: horizontal momentum carries briefly after stopping glide.
    elytra:
      landing-duration: 1500  # ms of post-landing buffer

    # Vehicle speed multipliers (horse, boat, strider, pig, etc.).
    vehicle-speed-multiplier: 1.5

    # Boats on ice can reach much higher speeds; this multiplier is used instead.
    # Default allows up to ~4.0 b/t (80 b/s) to cover extreme ice-boat runs while
    # still catching blatant teleport-speed exploits.
    vehicle-ice-speed-multiplier: 4.3

  timer:
    # Each movement packet a client sends represents one game tick (50ms).
    # VelocityGuard accumulates that claimed time and compares it against real
    # time anchored to a server clock (transactions) the client cannot speed up.
    enabled: true

    # Network-jitter tolerance in milliseconds.
    drift-millis: 120

    # Consecutive too-fast packets before a setback fires. Higher is more lenient.
    max-violations: 5

  flight:
    # Physics-based vertical (Y-axis) flight simulation.
    # Simulates gravity each tick (vy = (vy - 0.08) * 0.98) and flags players
    # whose upward displacement exceeds what a legitimate jump trajectory allows.
    # Catches hover and ascent cheats.
    # Disable if you only want horizontal speed enforcement.
    enabled: true

settings:
  # Verbose per-packet logging.  Enable only for testing.
  debug-mode: false
```

## How It Works
1. The plugin intercepts `ServerboundMovePlayerPacket` on the Netty thread before the server processes it. Each position-changing packet is treated as exactly one game tick.
2. A transaction (ping) packet is sent to each player every server tick; the client must echo it back immediately, giving a server-anchored clock the client cannot speed up. Each movement packet adds 50 ms to a running balance and if that balance runs ahead of real time, the client is sending packets faster than 20/s and is flagged.
3. Minecraft's ground/air movement equations are run for the tick, applying block friction (normal, ice, blue ice, slime), air drag, sprint/jump boost, water drag, and Speed/Slowness potion modifiers. The result is the maximum a player could legitimately move in that single tick.
4. The player's Y velocity is simulated under gravity (`vy = (vy − 0.08) × 0.98` per tick), predicting the maximum upward displacement from the last known velocity. This catches both hover cheats and upward speed cheats.
5. Any excess in the speed/flight checks accumulates in a per-player violation buffer that decays on clean packets. When the buffer exceeds `violation-threshold` (or the timer check exceeds `max-violations`), the player is rubber-banded to their last valid position and the resulting teleport re-anchors their tracked state.

## Commands
- `/velocityguard reload`: Reloads the plugin configuration (requires the `velocityguard.admin` permission).

## Permissions
`velocityguard.admin`: Allows reloading the plugin configuration (default: op).

## Developer API

**Setup** - add to your `plugin.yml`:
```yaml
softdepend: [VelocityGuard]
```
Add the VelocityGuard jar to your compile classpath, then get the API instance:
```java
import com.jellypudding.velocityGuard.VelocityGuard;
import com.jellypudding.velocityGuard.api.VelocityGuardAPI;

VelocityGuard vg = (VelocityGuard) Bukkit.getPluginManager().getPlugin("VelocityGuard");
if (vg == null) return;
VelocityGuardAPI api = vg.getAPI();
```

**Methods:**
```java
// Enable flight enforcement for a player (e.g. on zone entry).
// Defaults: ground on violation, strict sensitivity (~1 s),
// and a periodic check every ~0.5 s for players who fly up and go stationary.
api.enableFlightEnforcement(player);

// Control what happens on violation:
//   true  = teleport to highest solid block (default)
//   false = standard VelocityGuard setback behaviour
api.enableFlightEnforcement(player, false);

// Also control sensitivity via air-tick threshold.
// One tick ≈ 50 ms at 20 TPS. A normal jump lands by ~tick 15,
// so values below 15 risk false positives. Named constants are provided:
//   STRICT_AIR_TICK_THRESHOLD  = 20  (~1 s, recommended for zones)
//   DEFAULT_AIR_TICK_THRESHOLD = 40  (~2 s, matches the global config default)
api.enableFlightEnforcement(player, true, VelocityGuardAPI.STRICT_AIR_TICK_THRESHOLD);

// fourth parameter disables the stationary check if unwanted.
// (groundOnViolation, airTickThreshold, groundWhenStationary)
api.enableFlightEnforcement(player, true, VelocityGuardAPI.STRICT_AIR_TICK_THRESHOLD, false);

// Remove enforcement (e.g. on zone exit).
api.disableFlightEnforcement(player);

// Query whether enforcement is currently active for a player.
api.isFlightEnforcementActive(player);
```

Player data is cleaned up automatically on disconnect, so you only need to call `disableFlightEnforcement` on zone exit, not on quit.

## Support Me
[![ko-fi](https://ko-fi.com/img/githubbutton_sm.svg)](https://ko-fi.com/K3K715TC1R)
