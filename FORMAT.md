# EchoReplay Binary Format v1

> **Status:** this document is regenerated from source for v1.1.0. The previous FORMAT.md described a format that did not exist in the code (wrong magic, wrong section names, varint encoding that was never used, event types `PISTON`/`BLOCK_ACTION`/`META_*` that do not exist). Every field below matches the actual `RecordingFormat`, `TimelineCodec`, `GzipRecordingWriter`, and `GzipRecordingReader` source.

---

## File Layout

```
File:  *.echoreplay.gz
Body:  ONE gzip stream. All values little-endian unless noted.
```

### Header (12 bytes)

```
u8[4]  magic   = "ECHO"   (0x45 0x43 0x48 0x4F)
u16    format  = 1        (u16 LE)
u16    flags   = 0        (reserved, u16 LE)
```

The reader rejects `format > 1`. Unknown bits in `flags` are ignored.

### Sections

After the header, the stream is a sequence of sections until EOF:

```
i32    sectionType   (LE)   — see Section Types table
i32    bodyLength    (LE)   — payload length in bytes
u8[bodyLength] body
```

Unknown section types are silently skipped (forward compatibility for format v2).

### Section Types

| ID | Name        | Notes |
|----|-------------|-------|
| 1  | `META`      | Recording metadata. Written first by `writeMeta`. |
| 2  | `PALETTE`   | Block-state string palette (indexes used by BLOCKS and BlockSet events). |
| 3  | `BLOCKS`    | Packed palette indices for the initial region snapshot. |
| 4  | `BLOCK_NBT` | Packed tile-entity NBT for the initial snapshot. |
| 5  | `ENTITIES`  | Reserved — always empty in v1 (entities come via PlayerSpawn / EntitySpawn timeline events). |
| 6  | `TIMELINE`  | One event per section. The reader accumulates these into a time-sorted list. |
| 7  | `MARKERS`   | Reserved — never written in v1 (markers are emitted as timeline events). |

### META body (section type 1)

Written by `GzipRecordingWriter.writeMeta`. Field order:

```
utf   serverVersion    (utf = i32 length-prefix LE, then UTF-8 bytes)
utf   worldUuid        (UUID.toString(), 36 chars typical)
utf   worldName
i32   minX  (LE)
i32   minY  (LE)
i32   minZ  (LE)
i32   maxX  (LE)
i32   maxY  (LE)
i32   maxZ  (LE)
i64   epochMillis      (LE) — wall-clock capture time
utf   recorderUuid
utf   recorderName
i64   durationMillis   (LE) — recorded media duration
utf   name              — recording name (matches filename minus extension)
```

### PALETTE body (section type 2)

```
i32   count            (LE)
count × {
  utf  stateString      — e.g. "minecraft:chest[facing=north,waterlogged=false]"
}
```

`stateString` is what `BlockData.getAsString(true)` returns in Bukkit. Index 0 is conventionally `"minecraft:air"`.

### BLOCKS body (section type 3)

```
i32   sizeX            (LE) — cuboid X span
i32   sizeY            (LE) — cuboid Y span
i32   sizeZ            (LE) — cuboid Z span
u8    bitsPerEntry     — bits used per palette index (1–64)
i32   longCount        (LE) — number of packed u64s that follow
longCount × u64         (LE) — packed entries
```

Packing: `perLong = min(64 / bitsPerEntry, 32)` entries per long, row-major `x → z → y` (matches `RegionDiffRecorder` / `RecordingManager.tickSnapshot` cursor math). The last long is zero-padded.

### BLOCK_NBT body (section type 4)

```
i32   sizeX             (LE) — same dims as BLOCKS
i32   sizeY             (LE)
i32   sizeZ             (LE)
i32   entryCount        (LE)
entryCount × {
  i32  relX             (LE) — relative to cuboid min
  i32  relY             (LE)
  i32  relZ             (LE)
  i32  nbtLen           (LE)
  u8[nbtLen] nbt        — Java-serialized Bukkit BlockState blob (see S-2 caveat)
}
```

⚠ **S-2 caveat:** the NBT payload in v1 is a Java `BukkitObjectOutputStream` blob, not true NBT. This makes recordings version-fragile across Paper builds and is an RCE vector if recordings are shared. v1.2 will replace this with a custom NBT codec.

### ENTITIES body (section type 5)

Reserved — always empty (`new byte[0]`) in v1. Entities come via `PlayerSpawn` / `EntitySpawn` events in the TIMELINE.

### TIMELINE body (section type 6)

**One event per section** (i.e. each TIMELINE section carries exactly one event). Body layout:

```
i64   tickMillis       (LE) — media time of the event, in milliseconds since recording start
u8    eventType        — event id (see table below)
u16   bodyLen          (LE) — event body length, capped at 65,535 bytes (see S-4)
u8[bodyLen] eventBody
```

⚠ **S-4 caveat:** the `u16` length cap means a single event cannot exceed 64 KB. v1 silently truncated oversized events, leaving ~2 MB of unreadable payload. v1.1 guards this at write time and skips the offending event with a warning. The most common culprit is a `PlayerSpawn` carrying a shulker-box-of-shulker-boxes (≈1–2 MB of NBT). Future format v2 will use varint/i32 lengths and split oversized `PlayerSpawn` equipment into follow-up `Equipment` events.

### MARKERS body (section type 7)

Reserved — never written in v1. Markers are emitted as `MARKER` events (event type 28) in the TIMELINE.

---

## Event Types (TIMELINE eventType field)

Event type IDs are stable and **must never be reordered** — that would break every existing recording. The order matches `TimelineCodec`'s switch statement:

| ID | Name              | Payload (post-u16 length prefix) |
|----|-------------------|----------------------------------|
| 0  | `KEEP_ALIVE`      | (none — padding) |
| 1  | `BLOCK_SET`       | i16 relX, i16 relY, i16 relZ, i32 paletteIndex, i32 nbtLen, u8[nbtLen] nbt (0 if no NBT) |
| 2  | `BLOCK_BREAK_ANIM`| i32 breakerNpcId, i16 relX, i16 relY, i16 relZ, u8 stage |
| 3  | `MULTI_BLOCK`     | i16 count, count × { i16 relX, i16 relY, i16 relZ, i32 paletteIndex, i32 nbtLen, u8[nbtLen] nbt } |
| 4  | `PLAYER_SPAWN`    | i32 npcId, utf uuid, utf name, skin (see below), f64 x, f64 y, f64 z, f32 pitch, f32 yaw, f32 headYaw, i32 equipCount, equipCount × { i32 itemLen, u8[itemLen] itemBytes } |
| 5  | `PLAYER_LEAVE`    | i32 npcId, u8 reason |
| 6  | `ENTITY_SPAWN`    | i32 npcId, utf uuid, utf typeKey (e.g. "minecraft:zombie"), f64 x, f64 y, f64 z, f32 pitch, f32 yaw, f32 headYaw, i32 metaCount, metaCount × { i32 index, u8 type, ... } |
| 7  | `ENTITY_LEAVE`    | i32 npcId |
| 8  | `MOVE`            | i32 npcId, f64 x, f64 y, f64 z, f32 pitch, f32 yaw, f32 headYaw, u8 onGround |
| 9  | `VELOCITY`        | i32 npcId, f64 vx, f64 vy, f64 vz |
| 10 | `ANIMATION`       | i32 npcId, u8 animId (0=main arm, 1=off hand) |
| 11 | `METADATA`        | i32 npcId, i32 entryCount, entries × { i32 index, u8 type, ... } — raw passthrough, currently unused by recorders |
| 12 | `EQUIPMENT`       | i32 npcId, u8 slot (0=main, 1=off, 2=boots, 3=legs, 4=chest, 5=helmet), i32 itemLen, u8[itemLen] itemBytes |
| 13 | `POSE`            | i32 npcId, u8 poseId (Bukkit EntityPose ordinal) |
| 14 | `DAMAGE`          | i32 npcId |
| 15 | `DEATH`           | i32 npcId |
| 16 | `SNEAK_SPRINT`    | i32 npcId, u8 flags (bit 1 = sneak, bit 3 = sprint, bit 4 = swim) |
| 17 | `MOUNT`           | i32 riderNpcId, i32 vehicleNpcId — **defined but never recorded in v1** |
| 18 | `SOUND`           | i32 npcId (source, 0 = none), utf key, utf category, f64 x, f64 y, f64 z, f32 volume, f32 pitch |
| 19 | `PARTICLE`        | utf particleKey, f64 x, f64 y, f64 z, f32 dx, f32 dy, f32 dz, f32 speed, i32 count |
| 20 | `CHAT`            | i32 npcId, utf json |
| 21 | `WORLD_TIME`      | i64 timeOfDay — **defined but never recorded in v1** (capture-time config is a placebo until v1.2) |
| 22 | `WEATHER`         | u8 raining, u8 thundering — **defined but never recorded in v1** |
| 23 | `EXPLOSION`       | f64 x, f64 y, f64 z, i32 affectedBlockCount, count × { i16 relX, i16 relY, i16 relZ, i32 paletteIndex } |
| 24 | `ITEM_USE`        | i32 npcId, u8 hand (0=main, 1=off), u8 start (1=start, 0=stop) — **recorded but never replayed in v1** (applyEvent default branch swallows it) |
| 25 | `TELEPORT`        | i32 npcId, f64 x, f64 y, f64 z, f32 pitch, f32 yaw, f32 headYaw |
| 26 | `EFFECT`          | i32 npcId, i32 effectId, i32 duration, i32 amplifier, u8 ambient, u8 showParticles — **defined but never recorded in v1** |
| 27 | `CUSTOM_NAME`     | i32 npcId, utf name — **defined but never recorded in v1** |
| 28 | `MARKER`          | utf name |
| 29 | `ENTITY_STATUS`   | i32 npcId, u8 status |

### Skin encoding (PLAYER_SPAWN payload)

```
u8 hasValue
if (hasValue):
  utf value      — base64 texture string
  u8 hasSignature
  if (hasSignature):
    utf signature
```

### EntitySpawn metadata entries

```
i32 entryCount
entryCount × {
  i32 index          — metadata index (e.g. 0 = flags, 6 = pose)
  u8 type            — 0=BYTE, 1=BOOLEAN, 2=INT, 3=ITEMSTACK
  if (type == BYTE):    i32 value
  if (type == BOOLEAN): i32 value (0 or 1)
  if (type == INT):     i32 value
  if (type == ITEMSTACK): i32 itemLen, u8[itemLen] itemBytes
}
```

---

## Known v1 Limitations

These will be addressed in v1.2+ (see CHANGELOG in README):

1. **No seek index** — the entire timeline is gunzipped, decoded, and sorted in RAM on every `/er play`. A 30-min recording can take seconds to load. (v1.4+ P-7: `SEC_INDEX` with `(tickMillis → offset)` entries for binary search.)
2. **No keyframes** — every seek rebuilds state from t=0 (via CAPTURE/SNAPSHOT/CATCHUP streaming phases, but conceptually from scratch). Backward seeks always reset. (v1.4+: keyframe events every 10s.)
3. **No checksums** — truncated or corrupt files fail with a generic "Failed to read recording" error. (v1.4+: xxhash64 per section.)
4. **u16 event length** — single events > 64 KB are dropped at write time (S-4). (v1.4+: varint/i32 length.)
5. **Java-serialized item/tile-entity NBT** — fragile across Paper builds and an RCE vector if recordings are shared. (v1.2: custom NBT codec.)
6. **Position relative to cuboid min as i16** — cuboid spans > 32,767 in any axis break (only reachable with `echoreplay.bypass-limits`). (v1.4+: varint deltas from last position.)
7. **Dormant event types** — `MOUNT`, `WORLD_TIME`, `WEATHER`, `EFFECT`, `CUSTOM_NAME`, `METADATA` (raw), `MULTI_BLOCK` (defined but unused), `ITEM_USE` (recorded but not replayed). (v1.5+: F-2 batch implements the recorders + playback paths for these.)

---

## Reader/Writer Reference

- `dev.idebugger.echoreplay.storage.GzipRecordingWriter` — streaming writer; call `writeMeta`/`writePalette`/`writeBlocks`/`writeBlockNbt`/`writeEntities` once each, then `appendTimelineEvent` repeatedly, then `close`.
- `dev.idebugger.echoreplay.storage.GzipRecordingReader` — single-shot reader; `read(InputStream)` parses the whole file into a `GzipRecordingReader` instance with `.meta()`, `.palette()`, `.blockData()`, `.blockSizeX/Y/Z()`, `.blockNbt()`, `.entities()`, and `.timeline()` (decodes all timeline fragments into a time-sorted list).
- `dev.idebugger.echoreplay.storage.TimelineCodec` — per-event-type `encodeBody` / `decode` switch.
- `dev.idebugger.echoreplay.storage.RecordingFormat` — section type constants.
