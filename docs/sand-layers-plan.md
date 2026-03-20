# Sand Layers Implementation Plan

## Goal

Implement placeable sand layers using snow-like stacking behavior with 1/16th block increments, where placing on 15 layers converts the block into a full vanilla sand block.

## Rules

1. One placed `darude:sand_layer` starts at 1 layer (1/16 block height).
2. Replacing with the same block item increments up to 15 layers.
3. Placing on 15 layers converts that position into `minecraft:sand`.
4. Block is best mined with a shovel (`minecraft:mineable/shovel` tag).
5. Top and bottom textures match vanilla sand; sides are cropped based on current layer height.

## Scaffolding Added

- `com.darude.block.SandLayerBlock`: custom layered block with `layers` property (1-15), placement stacking, and conversion to full sand.
- `com.darude.DarudeBlocks`: registry + block item + creative tab insertion.
- Assets/data for blockstate, models, block/item tags, and language.

## Follow-up Implementation Tasks

1. Validate compile/runtime behavior on `26.1` toolchain and adjust method signatures if mappings changed.
2. Add layer-aware loot table so breaking N layers drops N items (currently scaffolded as single-item drop).
3. Add recipe loop for renewable sand + layer crafting/un-crafting.
4. Add interaction polish:
   - Right-click with shovel to remove one layer and drop item.
   - Optional shift-right-click behavior for quick flattening.
5. Add game tests for:
   - placement increments 1 -> 15
   - placement at 15 -> `minecraft:sand`
   - support checks and break behavior
   - tag/tool correctness

## Risks / Version Notes

- Fabric/Mojang mapping signatures can shift in prereleases; this scaffold may require minor method signature updates after first compile.
- If vanilla introduces its own layered sand-like block behavior in 26.x, we should reuse that abstraction instead of maintaining a custom block class.
