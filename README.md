# EchoReplay

Server-side region replay plugin for Paper 1.21.4+ (Java 21+). No client mods required. Uses PacketEvents 2.13.0 — all packets are sent server-side to make viewers see the replay.

## Features

- Record all activity in a cuboid region: blocks, players, mobs, projectiles, items, particles, sounds, pistons, block entities, explosions, chat
- All recorded players appear as fake players (including their skin, equipment, and animation) to replay viewers
- VCR-style playback controls: pause, resume, seek, rewind, fast-forward, speed adjustment
- Timeline search for efficient random access
- World mode: live world is rolled back via snapshot
- Virtual mode: viewer-only packets, no world changes
- Per-tick budgeted snapshot to prevent main-thread lag

## Requirements

- Java 21+
- Paper 1.21.4+
- Maven 3.8+

## Build

```bash
mvn clean package -DskipTests
```

JAR output: `target/echoreplay-1.0.0-SNAPSHOT.jar`

## Quick Start

1. Copy the JAR to your Paper `plugins/` directory
2. Restart or `/reload`
3. Set a region:
   - `/er wand` — give yourself the selection tool (golden hoe)
   - Left-click a corner block, right-click the opposite corner block
4. Record:
   - `/er record start` — starts recording everything in the region
   - `/er stop` — stops recording, saves the file
5. Replay:
   - `/er replay start` — enters the replay world with viewers
   - `/er pause` / `/er resume` — pause/resume
   - `/er seek 10s` / `/er seek 5m30s` — jump to time
   - `/er speed 2.0` — set playback speed (0.5–5.0)

## Commands

| Command | Description |
|---|---|
| `/er wand` | Give selection tool |
| `/er pos1` | Set first corner |
| `/er pos2` | Set second corner |
| `/er record start` | Start recording |
| `/er stop` | Stop recording / replay |
| `/er replay start` | Start replay (world mode) |
| `/er pause` | Pause replay |
| `/er resume` | Resume replay |
| `/er seek <time>` | Seek to time |
| `/er speed <value>` | Set playback speed |
| `/er cam` | Toggle free camera |
| `/er marker <name>` | Set marker at current time |
| `/er list` | List recordings |
| `/er info` | Info about active recording |
| `/er delete` | Delete a recording |

## Configuration

See `config.yml` for all options. Key settings:

- `virtual-packets-only: false` — when true, replay uses viewer-only packets instead of restoring the world
- `snapshot.blocks-per-tick: 8000` — max blocks restored per tick during world mode
- `recording.capture-particles: true` — record particle effects
- `recording.capture-sounds: true` — record sound effects
- `recording.capture-chat: true` — record chat messages

## File Format

Recordings are saved as `.echoreplay.gz` files in the `plugins/EchoReplay/recordings/` directory.

See `FORMAT.md` for the complete binary specification.
