# Sand Layer Farming Plan V1 (Implemented Behavior)

This document reflects the current V1 farming runtime as implemented.

## Goal

Provide intentional, build-driven `darude:sand_layer` farming during sandstorms.

## Core Constraints

- Farming generation is allowed only during rain/sandstorm conditions.
- Farming generation is allowed only in sandstorm-origin biomes (`darude:sandstorm_biomes`, e.g. desert).
- Emitter blocks are data-driven via block tag `darude:farming_emitters`.
- Default emitter set includes all copper grate variants (normal/exposed/weathered/oxidized + waxed variants).

## Runtime Scan Model (Current)

- Uses loaded-chunk approximation around players (chunk radius per player).
- For each scanned X/Z column, emitter checks run vertically from:
  - `max(world bottom, sea level)` up to
  - column top (`WORLD_SURFACE - 1`).
- Biome gating is evaluated per column (cached), sampled at surface-oriented Y.

## Emitter Qualification (Current)

An emitter attempt is valid only when all are true:

1. World is raining.
2. Emitter column is in `darude:sandstorm_biomes`.
3. Sky is visible above the emitter.
4. Horizontal neighbors (N/E/S/W) and above are air.
5. Block below emitter is one of:
   - `air`
   - `darude:sand_layer`
   - `darude:pyramid`
   - `darude:full_pyramid`

## Generation Modes (Current)

### A) Base Under-Grate Mode

- If no pyramid directly below emitter:
  - Roll `base_under_grate_chance`.
  - On success, attempt one placement at `emitterPos.down()`.

### B) Pyramid Side Mode

- If block directly below emitter is pyramid/full_pyramid:
  - For each cardinal side around the support block, roll:
    - `base_pyramid_side_chance` for `darude:pyramid`
    - `full_pyramid_side_chance` for `darude:full_pyramid`
  - Windward side uses `chance * windward_side_multiplier`.
  - Side targets are at the support block Y-level (same level as pyramid block).

## Wind Handling (Current)

- Server-side wind direction is cardinal and world-local.
- Direction shifts at fixed cadence (`WIND_SHIFT_TICKS`).
- Windward side is the opposite of wind direction (the side facing incoming wind).

## Fall-Through (Current)

- If a target cell is another emitter (`darude:farming_emitters`), do not place there.
- Evaluate that emitter as the next source (re-qualification required).
- Iterative depth cap enforced by `max_fallthrough_depth`.

## Placement / Increment Rules (Current)

- If target is `air` and layer can survive: place `darude:sand_layer` with 1 layer.
- If target is existing `darude:sand_layer`: increment layers.
- Layer overflow converts to `minecraft:sand` at threshold.

## Pyramid Erosion (Current)

After successful pyramid-backed generation:

- `darude:full_pyramid` may downgrade to `darude:pyramid` via `full_pyramid_erode_to_pyramid_chance`.
- `darude:pyramid` may break to `air` via `pyramid_break_chance`.

## Budgets / Safety (Current)

- Global farming operation cap per tick: `max_farming_operations_per_tick`.
- Farming evaluation interval: `farming_tick_interval_ticks`.
- Fall-through depth cap: `max_fallthrough_depth`.

## Config Knobs (V1)

- `farming_tick_interval_ticks`
- `base_under_grate_chance`
- `base_pyramid_side_chance`
- `full_pyramid_side_chance`
- `windward_side_multiplier`
- `full_pyramid_erode_to_pyramid_chance`
- `pyramid_break_chance`
- `max_fallthrough_depth`
- `max_farming_operations_per_tick`
- `farming_emitters` (block tag)

## Current Defaults

- `farming_tick_interval_ticks = 20`
- `base_under_grate_chance = 0.03`
- `base_pyramid_side_chance = 0.02`
- `full_pyramid_side_chance = 0.024`
- `windward_side_multiplier = 1.25`
- `full_pyramid_erode_to_pyramid_chance = 0.005`
- `pyramid_break_chance = 0.002`
- `max_fallthrough_depth = 12`
- `max_farming_operations_per_tick = 512`

## Note on V2

V2 adds generation-triggered avalanche redistribution on top of this baseline.
See `docs/sand-layer-farming-plan-v2.md`.
