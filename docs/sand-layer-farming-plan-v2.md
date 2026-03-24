# Sand Layer Farming Plan V2 (Grate + Pyramid Wind Capture + Generation Avalanche)

## Goal

Define an intentional, farmable sand-layer generation system centered on exposed copper grates and pyramid-assisted wind capture, with avalanche pressure at larger scales.

## Design Principle

- Natural storms provide the input signal.
- Players create output by building specific structures in valid, exposed locations.
- Yield scales by design quality (layout, chaining, wind orientation, and stability control), not passive world drift.

## Placement Validity Rule (Applies Everywhere)

For all rules below where a sand layer is created/placed:

- The target must be a valid placement location.
- Valid means the block is air or `darude:sand_layer`.
- The block below must allow `darude:sand_layer` to exist.
- If target already contains `darude:sand_layer`, increment layer count by one instead of replacing.

## Pyramid Erosion Rule (New in V2)

- Erosion rolls only happen when pyramid-backed generation successfully creates/increments a `darude:sand_layer`.
- If support block is `darude:full_pyramid`, roll a small chance to downgrade it to `darude:pyramid`.
- If support block is `darude:pyramid`, roll a small chance to destroy it (replace with air).
- This makes high-output farms require periodic maintenance.

## Generation-Only Avalanche Trigger Rule (New in V2)

- Apply sandpile instability checks only when layer count is increased through generation.
- Player placement does not trigger avalanche processing.
- In practice: a generated increment can push a pile into unstable state and topple into neighbors.
- This keeps avalanche as a farming-scale balancing mechanic, not a punishment for manual building.

## Core Loop (Farming Version)

1. **Exposed Grate Intake**
   - A `minecraft:copper_grate` that is outdoors, in a sandstorm, and surrounded by air on all sides can attempt generation.
   - Base behavior: low chance to create exactly one `darude:sand_layer` directly beneath the grate.

2. **Pyramid-Boosted Distribution**
   - If a pyramid block is directly below that grate, generation target changes.
   - The grate attempts to place one `darude:sand_layer` on each horizontal side adjacent to the pyramid (N/E/S/W).
   - The side wind is blowing against (windward side) gets a slightly increased placement chance.

3. **Vertical Fall-Through Chaining**
   - If a selected generation target is another valid grate cell, sand does not place there.
   - Instead, treat that as a fall-through event and run generation logic for the lower grate instance.
   - This enables stacked grate chains to pass sand downward until a non-grate valid target is found (or generation fails).

4. **Avalanche Pressure from Generated Growth**
   - After each successful generated increment, run local instability check.
   - If local slope/height delta exceeds threshold, redistribute layers to neighbors under budget caps.
   - Collection lanes with retaining design keep yield; bad layouts spill output into low-value cells.

## Avalanche Gameplay (Intentional Pressure)

- Keep avalanche logic as a balancing force, not a passive producer.
- When local pile differences exceed slope limits, layers redistribute and can spill away from collection lanes.
- Well-designed channels, retaining walls, and grate drains preserve throughput.
- Poor design bleeds production into useless redistribution.

## Structure Rules

### Exposed Grate Qualification

- Grate must be outside (sky/weather exposed by existing storm checks).
- Grate must be surrounded by air horizontally and above.
- For base mode, block below can be any valid support context for placing below-target layers.
- For pyramid mode, block directly below grate must be a pyramid block.

### Pyramid Mode

- A grate with `darude:pyramid` or `darude:full_pyramid` directly below enters side-placement mode.
- Candidate cells are the four cells adjacent to the pyramid at the same Y as the pyramid top target layer.
- Each candidate is rolled independently.
- Windward candidate receives a configurable multiplier.
- `darude:full_pyramid` uses a slightly higher base side-generation chance than `darude:pyramid`.

### Fall-Through Rule

- If candidate target block is `minecraft:copper_grate`, do not place a layer there.
- If that target grate is also qualified (outdoor + air-surrounded, and either no special block below or pyramid below), recursively/iteratively evaluate it as the next emitter.
- Stop when:
  - a non-grate valid target is placed,
  - qualification fails,
  - max fall-through depth is reached (safety cap).

### Avalanche Rule Set

- Trigger check only on generation-created increments.
- Use configurable `slope_threshold` and/or height-delta rule.
- Topple one layer at a time from unstable source to preferred valid neighbors.
- Neighbor preference order:
  1. marked collection channels
  2. stable collector lanes
  3. fallback valid cells
- Never create net new layers through avalanche (conservation only).

## Hard Anti-Passive Rules (Recommended)

- No autonomous terrain-wide deposition from this farming system.
- Generation only occurs from qualified grates during active sandstorm conditions.
- Pyramid bonus requires explicit block placement directly under grate.
- Wind bonus is modest, so orientation helps but does not dominate structure quality.
- Avalanche is redistribution-only and generation-triggered only.

## Proposed V2 Rules (Draft)

### Runtime Rule Set (Concrete V2)

- Tick each qualified grate at configured interval.
- If no pyramid below:
  - Roll `base_under_grate_chance`.
  - On success, attempt one placement directly below.
- If pyramid below:
  - For each side in N/E/S/W:
    - Roll `base_pyramid_side_chance` when support is `darude:pyramid`.
    - Roll `full_pyramid_side_chance` when support is `darude:full_pyramid`.
    - If side is windward, multiply by `windward_side_multiplier`.
    - On success, attempt one placement on that side target.
- Every placement attempt uses validity checks and fall-through handling.
- Every successful generated increment enqueues local avalanche evaluation.
- After each successful pyramid-backed generation:
  - If support is `darude:full_pyramid`, roll `full_pyramid_erode_to_pyramid_chance`.
  - If support is `darude:pyramid`, roll `pyramid_break_chance`.

### Wind Handling

- Windward side is derived from active storm wind vector.
- If wind is diagonal, pick the dominant cardinal component.
- If no reliable wind direction is available, skip windward bonus and use base chances.

### Avalanche Handling (Generation-Only)

- Instability evaluation runs only for cells changed by generation.
- Player-placed layer changes do not enqueue avalanche work.
- Process unstable cells via queue with per-tick budget.
- Apply topples until local gradients return within threshold or budget is exhausted.

### Safety / Performance

- Use iterative fall-through (not deep recursion).
- Cap per-source fall-through depth.
- Cap total farming operations per tick globally.
- Cap total avalanche topples per tick globally.
- Process only loaded chunks and active storms.

## Tuning Knobs

- `farming_tick_interval_ticks`
- `base_under_grate_chance`
- `base_pyramid_side_chance`
- `full_pyramid_side_chance`
- `windward_side_multiplier`
- `full_pyramid_erode_to_pyramid_chance`
- `pyramid_break_chance`
- `max_fallthrough_depth`
- `max_farming_operations_per_tick`
- `slope_threshold`
- `max_topples_per_tick`
- `avalanche_neighbor_scan_radius`

## Initial Defaults (Recommended)

- `farming_tick_interval_ticks = 20`
- `base_under_grate_chance = 0.03`
- `base_pyramid_side_chance = 0.02`
- `full_pyramid_side_chance = 0.024`
- `windward_side_multiplier = 1.25`
- `full_pyramid_erode_to_pyramid_chance = 0.005`
- `pyramid_break_chance = 0.002`
- `max_fallthrough_depth = 12`
- `max_farming_operations_per_tick = 512`
- `slope_threshold = 3`
- `max_topples_per_tick = 256`
- `avalanche_neighbor_scan_radius = 1`

## Validation Checklist (V2)

- Single exposed grate in storm occasionally creates one layer directly below.
- Same grate with pyramid below creates side layers around pyramid, with measurable windward bias.
- Same setup using `darude:full_pyramid` produces slightly higher side-generation than `darude:pyramid`.
- Targeting another qualifying grate triggers fall-through instead of direct placement on grate block.
- Chained grates stop safely at depth cap and never loop infinitely.
- Invalid targets (blocked, unsupported) correctly reject placement.
- Repeated successful generation erodes supports (`full_pyramid -> pyramid -> air`) at configured low rates.
- Generated increments on tall/steep piles can trigger controlled redistribution.
- Player placement on steep piles does not trigger avalanche processing.
- Tick cost stays within farming + avalanche operation budgets under dense farm setups.

## Implementation Checklist (Code Touchpoints)

Phase 1: Config and schema

- [ ] Add farming config fields to `src/main/resources/data/darude/worldgen/sand_layer_generation.json`:
  - `farming_tick_interval_ticks`
  - `base_under_grate_chance`
  - `base_pyramid_side_chance`
  - `full_pyramid_side_chance`
  - `windward_side_multiplier`
  - `full_pyramid_erode_to_pyramid_chance`
  - `pyramid_break_chance`
  - `max_fallthrough_depth`
  - `max_farming_operations_per_tick`
  - `slope_threshold`
  - `max_topples_per_tick`
  - `avalanche_neighbor_scan_radius`
- [ ] Parse and clamp fields in `src/main/java/com/darude/worldgen/SandLayerGenerationConfig.java`.

Phase 2: Qualification helpers

- [ ] Add grate qualification helpers in `src/main/java/com/darude/renewal/` (or existing renewal runtime package):
  - `isQualifiedFarmingGrate(...)`
  - `isAirSurroundedHorizontally(...)`
  - `hasPyramidBelow(...)`
  - `getPyramidSupportTypeBelow(...)`
  - `isValidSandLayerTarget(...)`

Phase 3: Farming runtime

- [ ] Add/extend server tick handler for grate farming evaluation.
- [ ] Implement base under-grate mode.
- [ ] Implement pyramid side mode with windward weighting.
- [ ] Apply support-specific rates (`pyramid` vs `full_pyramid`) for side generation.
- [ ] Implement fall-through chain resolution with depth cap.
- [ ] Apply erosion rolls on successful pyramid-backed generation (`full_pyramid -> pyramid`, `pyramid -> air`).
- [ ] Register runtime from `src/main/java/com/darude/DarudeMod.java`.

Phase 4: Avalanche runtime (generation-only trigger)

- [ ] Add active-cell queue for instability checks in `src/main/java/com/darude/renewal/`.
- [ ] Enqueue avalanche checks only from generation success path.
- [ ] Exclude player placement/update events from avalanche enqueue path.
- [ ] Implement bounded topple loop with neighbor preference order.

Phase 5: Verification and telemetry

- [ ] Add tests/sim scenarios for base grate, pyramid mode, wind bias, and grate-chain fall-through.
- [ ] Add erosion progression tests for `full_pyramid -> pyramid -> air`.
- [ ] Add tests proving avalanche trigger happens on generation increments and not player placement.
- [ ] Add debug counters:
  - attempts
  - successful placements
  - fall-through traversals
  - rejected invalid targets
  - depth-cap terminations
  - avalanche enqueues
  - avalanche topples
  - full-pyramid downgrades
  - pyramid breaks

## Success Criteria

- Players can intentionally farm `darude:sand_layer` using visible structure logic.
- Pyramid-assisted setups outperform bare grates in a predictable way.
- `darude:full_pyramid` supports outperform `darude:pyramid` but erode over time, adding maintenance cost.
- Wind direction provides a small but consistent optimization lever.
- Generated high-throughput farms need stability engineering to avoid spill losses.
- Vertical grate chains and avalanche processing remain deterministic and performance-safe.

## Next Step When Resuming

Implement Phase 1-3 first (config + qualification + farming runtime), then add Phase 4 generation-only avalanche queue and tune with telemetry.
