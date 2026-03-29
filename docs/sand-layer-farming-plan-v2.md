# Sand Layer Farming Plan V2 (Avalanche Additions)

This document contains only the V2 additions/deltas on top of V1.

V1 baseline (emitters, pyramid split, windward bias, fall-through, erosion, desert-origin gating) is defined in:

- `docs/sand-layer-farming-plan.md`

## V2 Scope

- Keep V1 farming behavior.
- Add generation-triggered avalanche redistribution as balancing pressure.
- Preserve deterministic and budget-bounded behavior.

## New Rules Added in V2

### 1) Generation-Only Avalanche Trigger

- Avalanche work is enqueued only by successful generation increments from the farming runtime.
- Player/manual placement does not enqueue avalanche work.

### 2) Global Avalanche Budget (Per Tick)

- Avalanche uses a global per-world budget: `max_topples_per_tick`.
- Budget counts topple events, not individual moved layers.
- Legacy key `avalanche_max_topples_per_increment` is accepted as a compatibility fallback when `max_topples_per_tick` is absent.

### 3) Cross-Chunk Redistribution Window

- Avalanche processing is not limited to one chunk.
- Runtime evaluates a 3x3 chunk window around the enqueue center to allow spill across chunk boundaries.

### 4) Neighbor Resolution and Settling

- Candidate neighbors remain cardinal (N/E/S/W).
- When horizontal neighbor is air:
  - if block below neighbor is `air` or `darude:sand_layer`, transfer settles below that neighbor;
  - otherwise, transfer lands in the air neighbor cell if placement is valid.
- Neighbor outcomes still use `VALID` / `BLOCKED` / `UNPLACEABLE` semantics.

### 5) Conservative Transfer Conversion

- Transfer overflow is converted conservatively:
  - full 16-layer sets become `minecraft:sand`;
  - remaining layers become `darude:sand_layer` above.
- No silent truncation; loss only happens through explicit dispersal/unplaceable rules.

### 6) Biome Edge Spill Rule

- Avalanche/spread deposition may cross out of `darude:sandstorm_biomes` when redistribution carries material beyond biome borders.
- This does not relax V1 emitter-generation gating.

### 7) Storm End Behavior

- New emitter generation still stops when storm/rain gating fails.
- Already queued avalanche work continues until budget/queue is exhausted (no abrupt cancellation).

## V2 Config Additions

- `max_topples_per_tick` (default `256`)
- `avalanche_slope_threshold` (clamped safe range, default `3`)

## V2 Validation Checklist

- Generated increments enqueue avalanche; manual placement does not.
- Global per-tick topple cap is respected under dense farms.
- Redistribution can cross chunk borders and biome borders.
- Air-neighbor settling follows the two-branch rule (land-here vs settle-below).
- Transfer conversion to `minecraft:sand` + layer remainder is conservative and deterministic.
- Queued avalanche processing continues after storm ends, while new emitter generation remains blocked.
