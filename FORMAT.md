# EchoReplay Binary Format

Recordings are saved as gzip-compressed little-endian binary files with the `.echoreplay.gz` extension.

## Top-level Structure

```
magic:          u32 LE  = 0x45524550 ("EREP")
version:        u32 LE  = 1
section_count:  u32 LE
[sections...]
```

## Sections

Each section:

```
type:  u32 LE   (see Section Types)
length: u32 LE  (payload bytes, excluding this 8-byte header)
payload: u8[length]
```

### Section Types

| ID | Name | Description |
|---|---|---|
| 1 | HEADER | Recording header (world, bounds, ticks, tick rate) |
| 2 | SNAPSHOT | Full palette + block snapshot |
| 3 | INDEX | Per-tick seek index (tick → byte offset) |
| 4 | TIMELINE | Encoded timeline events |
| 5 | META | Name, description, markers (NBT-like key-value) |

---

### HEADER Section (type=1)

```
world_uid:  u128 LE   (UUID most+least)
min_x:      i32 LE
min_y:      i32 LE
min_z:      i32 LE
max_x:      i32 LE
max_y:      i32 LE
max_z:      i32 LE
total_ticks: u32 LE
tick_rate:   u32 LE   (ticks per second, default 20)
timestamp:  u64 LE   (epoch millis)
```

### SNAPSHOT Section (type=2)

```
palette_length: u16 LE
[palette entries: u32 LE block state ID each]
block_count: u32 LE
[block data: varint-encoded packed indices]
```

Blocks are packed at 4 bits per block into a byte stream, using paletted storage similar to vanilla.

### INDEX Section (type=3)

```
entry_count: u32 LE
[entries: tick_index u32 LE | byte_offset u32 LE]
```

### TIMELINE Section (type=4)

```
entry_count: u32 LE
[entries]
```

Each timeline entry:

```
type:     u8       (event type id)
tick:     u32 LE   (tick number from start)
length:   u16 LE   (payload bytes)
payload:  u8[length]
```

#### Timeline Event Types

| ID | Record Type | Payload |
|---|---|---|
| 0 | BLOCK_SET | varint x, varint y, varint z, u32 block_state |
| 1 | MULTI_BLOCK | varint count, [(varint x, varint y, varint z, u32 block_state)...] |
| 2 | PLAYER_SPAWN | varint npc_id, uuid, string name, skin, vec3d, rotation, equipment list, metadata |
| 3 | PLAYER_LEAVE | varint npc_id, u8 reason |
| 4 | ENTITY_SPAWN | varint npc_id, u8 entity_type, vec3d, metadata |
| 5 | ENTITY_LEAVE | varint npc_id |
| 6 | MOVE | varint npc_id, vec3d, rotation, u8 flags |
| 7 | TELEPORT | varint npc_id, vec3d, rotation |
| 8 | VELOCITY | varint npc_id, vec3d, u16 duration |
| 9 | DEATH | varint npc_id |
| 10 | CHAT | varint npc_id, string json_text |
| 11 | EQUIPMENT | varint npc_id, u8 slot, bytes serialized item |
| 12 | SNEAK_SPRINT | varint npc_id, u8 flags |
| 13 | SOUND | string id, u8 source, vec3d, f32 volume, f32 pitch, u32 seed |
| 14 | PARTICLE | string id, u8 mode, vec3d, f32 count, ... |
| 15 | EXPLOSION | vec3d center, f32 radius, [affected blocks] |
| 16 | PISTON | varint x, varint y, varint z, u8 direction, u8 extending |
| 17 | BLOCK_ANIMATION | varint x, varint y, varint z, varint block, varint data |
| 18 | BLOCK_ACTION | varint x, varint y, varint z, varint type, [varints] |
| 19 | PLAYER_ANIMATION | varint npc_id, u8 anim_id |
| 20 | DAMAGE | varint npc_id, f32 hearts |
| 21 | META_STRING | string key, string value |
| 22 | META_INT | string key, varint value |
| 23 | META_FLOAT | string key, f32 value |
| 24 | META_BOOL | string key, u8 value |
| 25 | META_LONG | string key, i64 LE value |
| 26 | META_DOUBLE | string key, f64 LE value |
| 27 | META_COMPONENT | string key, string json value |

### META Section (type=5)

Key-value pairs serialized as NBT-like string→byte arrays:

```
entry_count: u32 LE
[entries: string key, u32 LE byte_length, bytes value]
```
