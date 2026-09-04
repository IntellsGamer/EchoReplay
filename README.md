# EchoReplay

Server-side region replay plugin for **Paper 1.21+** (Java 21+). No client mods required — every viewer sees the replay through packets sent by PacketEvents.

Record any cuboid region: blocks, players, mobs, projectiles, items, particles, sounds, explosions, chat. Play it back in-world (world mode) or as viewer-only packets (virtual mode). Pause, seek, scrub, slow-mo, fast-forward — VCR-style.

> **v1.1.0** fixes the security, data-loss, and correctness issues identified in the v2.0 audit. See **CHANGELOG** below.

---

## Features

- **Full cuboid capture:** blocks (with tile-entity NBT for chests/signs/skulls/beacons/spawners/banners/etc.), players (skin + equipment + name), mobs, projectiles, items, particles, sounds, explosions, chat, block-break animations
- **Two replay modes:**
  - **world mode** — the recorded snapshot is applied over the live region; the live region is captured first and restored on stop. Locks the cuboid (cancels breaks/places/flows/pistons) for the duration
  - **virtual mode** — entities + state packets only; the live world is not touched. (Block overlay is a planned feature; today virtual mode is "ghost theater" over live terrain.)
- **VCR controls:** pause, resume, seek (multiple time formats), rewind, fast-forward, speed (0.125×–16×), stop
- **Per-tick budgets:** snapshot, region diff, and replay phases each have millisecond budgets so even huge regions don't freeze the tick loop
- **Background RegionDiff scanner:** catches every block change in the region — including ones that never fire Bukkit events (cancelled breaks, plugin writes, obsidian/portal formation, crystal explosions)
- **Playback border:** per-viewer particle wireframe around the replay cuboid (toggle with `/er border`)
- **Permission gate:** every subcommand has a centralized permission check — see the Permissions table below
- **Catch-up for late viewers:** joining a replay mid-stream re-sends the full current fake-entity state to the new viewer
- **Tab-list hygiene:** fake players are properly removed from viewers' tab lists when destroyed
- **Seek side-effect suppression:** seeks don't dump 10 minutes of chat + sound into a single tick
- **Speed clamp:** `/er speed NaN` / `/er speed -5` / `/er speed 1e9` are rejected; the run loop can't be deadlocked
- **Play-race guard:** double `/er play` no longer orphans a session and locks a region forever
- **Shutdown-safe:** recordings are guaranteed to flush before the JVM exits

---

## Requirements

- Java 21+
- Paper 1.21.5 or 1.21.11 (use the matching jar)
- Maven 3.8+

## Build

```bash
mvn clean package -DskipTests
```

This produces one jar per Minecraft version:

- `1.21.5/target/echoreplay-1.21.5+1.1.0.jar`
- `1.21.11/target/echoreplay-1.21.11+1.1.0.jar`

Pick the jar that matches your server's Minecraft version and drop it into `plugins/`.

---

## Quick Start

1. Drop the jar into your Paper `plugins/` directory
2. **Restart the server.** (Do NOT use `/reload` — it breaks PacketEvents' listener lifecycle.)
3. Give yourself the wand: `/er wand` (you need `echoreplay.wand`)
4. **Left-click** a block to set pos1, **right-click** to set pos2. The wand is a golden axe by default (configurable via `selection.wand-material`)
5. `/er selinfo` — verify the selection (volume, world, span)
6. `/er record my-recording` — starts snapshotting the region, then recording. (Name is required.)
7. Do stuff in the region
8. `/er stop` — finalizes and saves the recording
9. `/er list` — verify it shows up
10. `/er play my-recording` — replays in **virtual mode by default** (if `replay.virtual-packets-only: false`, defaults to **world mode** which modifies the live region — be careful)
11. `/er play my-recording virtual` — explicitly virtual
12. `/er pause`, `/er seek 5m30s`, `/er speed 2`, `/er ff 10`, `/er rewind 30`
13. `/er stopplay` — stop the replay (world mode restores the live region)

---

## Commands

Run `/er` with no arguments for an in-game help summary. All commands route through a centralized permission table; unknown subs fail closed (denied).

### Selection

| Command | Description | Permission |
|---|---|---|
| `/er wand` | Give yourself the selection wand | `echoreplay.wand` |
| `/er pos1 [x y z]` | Set first corner (defaults to your feet) | `echoreplay.select` |
| `/er pos2 [x y z]` | Set second corner | `echoreplay.select` |
| `/er select <x1 y1 z1 x2 y2 z2>` | Set both corners from args | `echoreplay.select` |
| `/er expand <amount> [dir]` | Grow the selection (dir: all/horiz/vert/up/down/n/s/e/w) | `echoreplay.select` |
| `/er contract <amount> [dir]` | Shrink the selection | `echoreplay.select` |
| `/er shift <amount> <dir>` | Move the selection | `echoreplay.select` |
| `/er selinfo` | Show selection volume + limits check | `echoreplay.select` |
| `/er clear` | Clear your selection | `echoreplay.select` |

### Recording

| Command | Description | Permission |
|---|---|---|
| `/er record <name>` | Start recording (snapshot then live events) | `echoreplay.record` |
| `/er stop` | Stop + save | `echoreplay.record` |
| `/er cancel` | Discard without saving | `echoreplay.record` |
| `/er save` | Alias of `/er stop` | `echoreplay.record` |
| `/er status` | Active recording + replay summary | `echoreplay.use` |
| `/er marker <name>` | Place a named marker at the current recording time | `echoreplay.record` |

### Playback

| Command | Description | Permission |
|---|---|---|
| `/er play <name> [virtual\|world]` | Start a replay (default mode from config) | `echoreplay.play` |
| `/er stopplay` | Stop the active replay (world mode restores region) | `echoreplay.play` |
| `/er pause` / `/er resume` | Pause/resume playback | `echoreplay.control` |
| `/er speed <0.125–16>` | Set playback speed (presets: 0.25, 0.5, 1, 2, 4, 8, 16) | `echoreplay.control` |
| `/er seek <time>` | Seek — see Time Formats below | `echoreplay.control` |
| `/er ff [seconds]` | Fast-forward (default 10s) | `echoreplay.control` |
| `/er rewind [seconds]` | Rewind (default 10s) | `echoreplay.control` |
| `/er watch` | Join the active replay as a viewer (catch-up is automatic) | `echoreplay.watch` |
| `/er leave` | Stop being a viewer | `echoreplay.watch` |
| `/er cam` | Camera hint (use `/spectate <player>` while watching) | `echoreplay.control` |
| `/er border [on\|off\|toggle\|status]` | Per-viewer playback border particles | `echoreplay.use` |

### Manage

| Command | Description | Permission |
|---|---|---|
| `/er list` | List all recordings (name, duration, size, world) | `echoreplay.use` |
| `/er info <name>` | Detailed info on a recording (bounds, size, world) | `echoreplay.use` |
| `/er delete <name>` | Stage a recording for deletion (run `/er confirm` within 30s) | `echoreplay.delete` |
| `/er confirm` | Confirm pending deletions | `echoreplay.use` |
| `/er rename <old> <new>` | Rename a recording | `echoreplay.delete` |

### System

| Command | Description | Permission |
|---|---|---|
| `/er stats` | Show resolved config, active sessions, IO thread state | `echoreplay.admin` |
| `/er version` | Plugin version + API version | `echoreplay.use` |
| `/er reload` | Reload `config.yml` (does NOT reload listeners) | `echoreplay.admin` |

### Time formats

`/er seek` and `/er ff` / `/er rewind` accept all of the following (case-insensitive):

| Format | Meaning | Example |
|---|---|---|
| `10` or `10.5` | plain seconds | `seek 30` |
| `10s` | seconds | `seek 10s` |
| `5m30s` | compound | `seek 5m30s` |
| `1h2m3s` | full H/M/S | `seek 1h2m3s` |
| `12:30` | mm:ss | `seek 12:30` |
| `1:02:30` | hh:mm:ss | `seek 1:02:30` |
| `50%` | percent of recording duration | `seek 50%` |
| `tick:600` | server ticks (50ms each) | `seek tick:600` |
| `<marker-name>` | seek to a named marker | `seek ambush` |

---

## Permissions

Default is `op` for sensitive commands; `true` for read-only/watch.

| Permission | Default | Grants |
|---|---|---|
| `echoreplay.use` | true | list, info, status, border, watch-related |
| `echoreplay.wand` | op | `/er wand` |
| `echoreplay.select` | op | all selection verbs |
| `echoreplay.record` | op | `/er record`, `/er stop`, `/er cancel`, `/er save`, `/er marker` |
| `echoreplay.play` | op | `/er play`, `/er stopplay` |
| `echoreplay.control` | op | `/er pause`, `/er resume`, `/er speed`, `/er seek`, `/er ff`, `/er rewind`, `/er cam` |
| `echoreplay.watch` | true | `/er watch`, `/er leave` |
| `echoreplay.border` | true | `/er border` |
| `echoreplay.delete` | op | `/er delete`, `/er rename` |
| `echoreplay.bypass-limits` | op | Ignore `max-volume` / `max-horizontal-span` |
| `echoreplay.admin` | op | `/er stats`, `/er reload`, `/er version` |

---

## Configuration

See `config.yml` for all options (every key is documented and verified at startup — unknown keys are logged). Highlights:

- `selection.wand-material` — Material name (default `GOLDEN_AXE`). Honored at `/er wand`. The wand's persistent-data-container tag wins over material — an existing wand still works.
- `selection.max-volume` / `selection.max-horizontal-span` — block / span limits. Bypass with `echoreplay.bypass-limits`.
- `recording.scan-ms-per-pass` — background RegionDiff budget per pass (default 12ms). Lower = less main-thread pressure, slower coverage.
- `recording.max-duration-minutes` — auto-stop after this many minutes (0 = unlimited).
- `replay.virtual-packets-only` — if `true`, `/er play` defaults to virtual mode.
- `replay.phase-budget-ms-per-tick` — per-tick budget for streaming phases (default 8ms).
- `replay.min-speed` / `replay.max-speed` — hard speed bounds (default 0.125 / 16).
- `replay.snapshot.blocks-per-tick` — max blocks applied per tick during snapshot/restore.
- `replay.border.*` — playback border particle settings.

---

## File Format

Recordings are saved as `.echoreplay.gz` files in `plugins/EchoReplay/recordings/`. See `FORMAT.md` for the complete binary specification (regenerated from source for v1.1.0 — every section type, event id, and field order is documented to match the implementation).

---

## CHANGELOG (v1.0.18 → v1.1.0)

This release implements the v2.0 audit roadmap. Highlights:

### Security
- **S-1** Centralized permission router — every subcommand now requires its declared permission. v1 left 15 of 22 subcommands unpermissioned (any default-rank player could `/er delete` recordings or `/er play ... world` to wipe a region).
- **S-7** Speed validation — `/er speed NaN` / `-5` / `1e9` no longer deadlocks the run loop. Hard bounds `[0.125, 16]`.

### Data integrity
- **S-3** Play-race guard — two rapid `/er play` commands no longer orphan a session and leave a region permanently snapshot-wiped with ghost entities.
- **S-4** Event-length guard — events > 64 KB are skipped individually instead of truncating the u16 length and silently corrupting the file (a player with a shulker-of-shulkers no longer vanishes from the recording).
- **S-6** `awaitTermination` on disable + non-daemon IO thread — the final gzip write now actually reaches disk before the JVM exits. v1's daemon thread was killed mid-write on shutdown, corrupting the just-saved file.

### UX & correctness
- **S-8** Viewer catch-up — late viewers (`/er watch` or auto-watch-radius join) now receive the full current fake-entity state on the next tick. v1 silently sent move packets for entity IDs the client had never seen spawned → invisible replay.
- **S-9** Tab-list cleanup — fake players are properly removed via `PlayerInfoRemove` when destroyed. v1 left fake tab entries accumulating forever (30+ entries per viewer after a 10-min recording).
- **D-7** Seek side-effect suppression — chat / sound / particle / damage events are suppressed during catch-up, with a "… N chat lines skipped" summary at the end. v1 dumped all events from the skipped range into one tick.
- **D-6** Equipment content hash — durability damage, renames, enchantments, and other meta changes are now detected. v1 only compared `type:amount`, so a sword losing durability mid-recording replayed with the old durability bar.
- **D-8** Many small correctness fixes — real `/er confirm` flow, selection world re-binding, double-swing dedup, removed double `EntityLeave` emission, removed startup broadcast to all players, fixed RegionDiff coordinate key overflow for far-flung bases, cleaned up dead `PlaybackBorderPrefs` legacy loop.

### Fidelity
- **D-5** Tile-entity NBT capture now uses the `TileState` interface check, catching BEACON, SPAWNER, BANNER, BEEHIVE, CONDUIT, ITEM_FRAME contents, SCULK family, COMMAND_BLOCK family, END_GATEWAY, CHISELED_BOOKSHELF, and more — all of which v1 silently restored empty.

### Documentation & config
- **D-1** README and FORMAT.md rewritten from source. v1's docs described commands and a binary format that did not exist (golden hoe vs golden axe, `/er record start` vs `/er record <name>`, `ECHO` magic vs `EREP`, etc.).
- **D-2** Config drift cleanup — every key in `config.yml` is now actually read by the plugin. Unknown keys are logged at startup. `config-version: 2` for migration.
- Fixed bogus `<mainClass>com.echoreplay.EchoReplayPlugin</mainClass>` ManifestResourceTransformer (Bukkit uses `plugin.yml`, not the jar manifest).
- Fixed `plugin.yml` `website:` to point to the real repo URL.
- Bumped version `1.0.18` → `1.1.0`.

### Observability
- **P-9** `/er stats` command — show resolved config, active sessions, IO thread state.
- `/er version` — plugin version + API version.

### What's NOT in this release (deferred to v1.2+)
- **S-2** NBT instead of Java serialization for items/tile-entities (RCE vector) — scheduled for v1.2.
- **S-5** Streaming journal + crash recovery — scheduled for v1.2.
- **P-1** Streaming writes during recording — scheduled for v1.2.
- **P-7** Format v2 (index, keyframes, zstd, checksums) — scheduled for v1.4+.
- **U-6** Virtual mode block overlay — scheduled for v2.0.
- **F-1** Multi-session support — scheduled for v2.0.

---

## License

See the source distribution. Issues and PRs welcome at the repo URL.
