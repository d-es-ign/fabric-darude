# Sand Layer Farming Plan V1 (Grate + Pyramid Wind Capture)

## Goal

Define an intentional, farmable sand-layer generation system centered on exposed copper grates and pyramid-assisted wind capture.

## Design Principle

- Natural storms provide the input signal.
- Players create output by building specific structures in valid, exposed locations.
- Yield scales by design quality (layout, chaining, wind orientation), not passive world drift.

## Placement Validity Rule (Applies Everywhere)

For all rules below where a sand layer is created/placed:

- The target must be a valid placement location.
- Valid means the block is air or `darude:sand_layer`.
- The block below must allow `darude:sand_layer` to exist.
- If target already contains `darude:sand_layer`, increment layer count by one instead of replacing.

## Pyramid Erosion Rule (New)

- Erosion rolls only happen when pyramid-backed generation successfully creates/increments a `darude:sand_layer`.
- If support block is `darude:full_pyramid`, roll a small chance to downgrade it to `darude:pyramid`.
- If support block is `darude:pyramid`, roll a small chance to destroy it (replace with air).
- This makes high-output farms require periodic maintenance.

## Core Loop (Farming Version)

1. **Exposed Grate Intake**
   - A configured emitter block (default: all copper grate variants, including waxed/oxidized) that is outdoors, in a sandstorm, and surrounded by air on all sides, can attempt generation.
   - Farming is only valid in sandstorm-origin biomes (`darude:sandstorm_biomes`, e.g. desert).
   - Base behavior: low chance to create exactly one `darude:sand_layer` directly beneath the grate.

2. **Pyramid-Boosted Distribution**
   - If a pyramid block is directly below that grate, generation target changes.
   - The grate attempts to place one `darude:sand_layer` on each horizontal side adjacent to the pyramid (N/E/S/W).
   - The side wind is blowing against (windward side) gets a slightly increased placement chance.

3. **Vertical Fall-Through Chaining**
   - If a selected generation target is another valid grate cell, sand does not place there.
   - Instead, treat that as a fall-through event and run generation logic for the lower grate instance.
   - This enables stacked grate chains to pass sand downward until a non-grate valid target is found (or generation fails).

## Structure Rules

### Exposed Grate Qualification

- Grate must be outside (sky/weather exposed by existing storm checks).
- Grate must be in a `darude:sandstorm_biomes` biome.
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

## Hard Anti-Passive Rules (Recommended)

- No autonomous terrain-wide deposition from this farming system.
- Generation only occurs from qualified grates during active sandstorm conditions.
- Generation only occurs in `darude:sandstorm_biomes` (desert-origin rule).
- Pyramid bonus requires explicit block placement directly under grate.
- Wind bonus is modest, so orientation helps but does not dominate structure quality.

## Proposed V1 Rules (Draft)

### Runtime Rule Set (Concrete V1)

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
- After each successful pyramid-backed generation:
  - If support is `darude:full_pyramid`, roll `full_pyramid_erode_to_pyramid_chance`.
  - If support is `darude:pyramid`, roll `pyramid_break_chance`.

### Wind Handling

- Windward side is derived from active storm wind vector.
- If wind is diagonal, pick the dominant cardinal component.
- If no reliable wind direction is available, skip windward bonus and use base chances.

### Safety / Performance

- Use iterative fall-through (not deep recursion).
- Cap per-source fall-through depth.
- Cap total farming operations per tick globally.
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
- `farming_emitters` (block tag)

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

## Validation Checklist (V1)

- Single exposed grate in storm occasionally creates one layer directly below.
- Same grate with pyramid below creates side layers around pyramid, with measurable windward bias.
- Same setup using `darude:full_pyramid` produces slightly higher side-generation than `darude:pyramid`.
- Targeting another qualifying grate triggers fall-through instead of direct placement on grate block.
- Chained grates stop safely at depth cap and never loop infinitely.
- Invalid targets (blocked, unsupported) correctly reject placement.
- Repeated successful generation erodes supports (`full_pyramid -> pyramid -> air`) at configured low rates.
- Tick cost stays within configured operation budget under dense farm setups.

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

Phase 4: Verification and telemetry

- [ ] Add tests/sim scenarios for base grate, pyramid mode, wind bias, and grate-chain fall-through.
- [ ] Add erosion progression tests for `full_pyramid -> pyramid -> air`.
- [ ] Add debug counters:
  - attempts
  - successful placements
  - fall-through traversals
  - rejected invalid targets
  - depth-cap terminations
  - full-pyramid downgrades
  - pyramid breaks

## Success Criteria

- Players can intentionally farm `darude:sand_layer` using visible structure logic.
- Pyramid-assisted setups outperform bare grates in a predictable way.
- `darude:full_pyramid` supports outperform `darude:pyramid` but erode over time, adding maintenance cost.
- Wind direction provides a small but consistent optimization lever.
- Vertical grate chains behave deterministically and stay performance-safe.

## Next Step When Resuming

Implement Phase 1-2 first (config + qualification helpers), then ship a test build with Phase 3 runtime and telemetry enabled for tuning.
