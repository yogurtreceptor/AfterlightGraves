# AfterlightGraves

AfterlightGraves is a small Paper plugin that keeps a player's items and experience
safe when they die. Each death creates a visible grave near the death location.
Right-clicking the grave recovers as much as the player can carry; if their
inventory is full, the remainder stays in the grave.

It was built for a private family server where graves should be reliable but
not private. Shared recovery is enabled by default, so one player can rescue
another player's items.

## Features

- Stores the complete death inventory and all pre-death XP.
- Persists grave contents in plugin-owned files before suppressing normal drops.
- Uses a compound blackstone headstone, soul lantern, label, and a generous
  interaction hitbox without placing a real container block.
- Keeps grave contents independent of the visual entity, so fire, lava,
  explosions, and item despawn cannot destroy them.
- Relocates the marker to nearby solid ground when the death position is unsafe.
- Allows unlimited graves with no automatic expiry.
- Provides `/graves` to list only the caller's graves, including dimension,
  coordinates, and straight-line distance in the same dimension.
- Requires no client mod or resource pack.

## Requirements

- Paper 26.3
- Java 25

Version 1.0.0 was built against and tested on Paper 26.3 build 8.

## Installation

1. Download `AfterlightGraves-1.0.0.jar` from the GitHub release.
2. Put it in the server's `plugins` directory.
3. Start the server.

The default configuration is:

```yaml
shared-recovery: true
```

Set `shared-recovery` to `false` if only a grave's owner should be allowed to
recover it, then restart the server.

## Commands and permissions

| Command | Permission | Default | Purpose |
| --- | --- | --- | --- |
| `/graves` | `afterlightgraves.graves` | Everyone | Lists only the caller's graves |

`/graves` never exposes another player's grave list. Shared recovery only
affects right-clicking a grave in the world.

## Storage and failure behaviour

Graves are stored in `plugins/AfterlightGraves/graves/`, one YAML file per grave.
The item payload uses Paper's lossless item serialization so enchantments,
names, components, and container contents are retained.

The grave file is written before normal death drops are cleared. If that write
fails, AfterlightGraves leaves Minecraft's normal drops untouched and tells the
player that grave storage failed.

## Building

```sh
./gradlew build
```

The JAR is written to `build/libs/`.

## License

[MIT](LICENSE)
