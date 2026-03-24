# Sand Layer Renewal Plan (Intentional Farming First)

## Goal

Make `darude:sand_layer` renewable through player-built systems, where intentional setup and tuning drive output.

## Design Principle

- Passive environmental shifting should create flavor and ambient movement.
- High-yield renewal should require deliberate farm construction and maintenance.
- The best rates should come from engineered collector/filter systems, not random terrain drift.

## Player-Control Philosophy

- Follow vanilla design philosophy: do not remove player control through unavoidable world-side effects.
- Sand systems must not grief or erode player builds by default.
- Renewable output should come from intentional setup, not from uncontrolled ambient behavior.
- Any movement/destruction logic should be constrained to opted-in contexts (for example dedicated farm layouts or explicitly enabled areas).

## Core Loop (Intentional Version)

1. **Storm Capture**
   - Sandstorms can deposit layers only onto valid "collector" surfaces.
   - Collector cells are player-prepared targets (open to sky/wind, valid support, non-blocked).
   - Optional: require nearby marker blocks (for example copper grate arrays) for full deposit rate.

2. **Filter Stage (Copper Grate)**
   - `sand_layer` above `copper_grate` converts to output over time.
   - Conversion runs only when output path exists (air shaft/hopper inventory space).
   - Full output inventory stalls conversion and causes pile buildup.

3. **Processing / Compression**
   - Filtered output is combined into placeable `sand_layer` items (or grains -> layers).
   - Optionally compress into `minecraft:sand` at a higher recipe ratio.

4. **Throughput Management**
   - Farm quality depends on collector footprint, filter count, storage handling, and slope control.
   - Neglected setups lose efficiency through overflow and avalanches.

## Avalanche Gameplay (Intentional Pressure)

- Keep avalanche logic as a balancing force, not a passive producer.
- When local pile differences exceed slope limits, layers redistribute and can spill away from collection lanes.
- Well-designed channels, retaining walls, and grate drains preserve throughput.
- Poor design bleeds production into useless redistribution.

## Hard Anti-Passive Rules (Recommended)

- No meaningful yield from natural shifting alone.
- Storm deposition is strongly reduced outside valid collector contexts.
- Grate conversion is the main renewable source and requires explicit farm plumbing.
- Cap ambient off-farm accumulation so random dunes do not become optimal farming.

## Proposed V1 Rules (Draft)

### Collection
- Base storm deposit chance per collector cell: low-to-medium.
- Bonus only when cell is part of a valid collection setup (for example adjacency to copper grate network).
- Non-farm terrain receives minimal deposit chance.

### Filtering
- Every interval, a grate can remove exactly one layer from the block above.
- If no valid output destination, no conversion occurs.
- Conversion creates deterministic item output (no RNG jackpots).

### Stability
- Cells above a configurable slope threshold become unstable.
- Unstable cells are processed via topples under a fixed per-tick budget.
- Topples preferentially move into designated collection channels if available.

### Performance
- Simulate only active cells/chunks.
- Use sparse queues and fixed tick budgets.
- Avoid per-grain entities; use integer layer heights.

## Tuning Knobs

- `stormDepositChanceCollector`
- `stormDepositChanceAmbient`
- `grateDrainIntervalTicks`
- `slopeThreshold`
- `maxTopplesPerTick`
- `maxLayerHeightPerCell`
- `collectorBonusMultiplier`

## Success Criteria

- Players can build a compact intentional farm with predictable output.
- Random terrain drift alone is clearly suboptimal for production.
- Larger farms scale by engineering effort (layout + logistics), not AFK randomness.
- Tick cost remains bounded and stable during large storms.

## Next Step When Resuming

Implement a minimal V1 prototype with:
- collector validation,
- grate draining,
- fixed-rate output,
- and capped avalanche processing.
