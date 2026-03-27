# darude

`darude` is a Fabric mod centered on desert weather and sand mechanics.

## What the mod adds

- **Sandstorms** in configured biomes when weather conditions are met.
- **Client effects** for active storms (fog shaping/coloring, particles, debug HUD lines).
- **Sand-layer gameplay blocks** (`sand_layer`, `pyramid`, `full_pyramid`).
- **Renewable/redistribution logic** for unstable sand piles (see `common` avalanche harness).

## Multi-version architecture

This repository uses a version-band setup so one branch can support multiple Minecraft lines.

### Active bands

| Band | Minecraft | Mappings strategy | Shared baseline |
|---|---|---|---|
| `mc121` | `1.21.11` | Yarn (`1.21.11+build.2`) | `shared-mc-121` |
| `mc261` | `26.1` | no mappings / official namespace port | `shared-mc-261` |

### Module layout

- `common/`: version-agnostic Java logic (platform interface + avalanche redistribution/harness).
- `mc121/`, `mc261/`: band entry modules (Loom config + platform adapters).
- `shared-mc-121/`, `shared-mc-261/`: band-specific Minecraft implementation baselines (blocks, worldgen, mixins, client effects).
- `gradle/mc-band.gradle`: shared build wiring for all band modules.

## Toolchain and requirements

- Fabric Loader: `0.18.2+`
- Java:
  - `mc121` builds on Java `21`
  - `mc261` CI builds on Java `25`
  - using Java `25` locally is the safest option when working across all modules

## Development commands

- Run 1.21 client: `./gradlew :mc121:runClient`
- Run 26.1 client: `./gradlew :mc261:runClient`
- Build all modules: `./gradlew build`
- Build one band: `./gradlew -p mc121 build` / `./gradlew -p mc261 build`
- Run avalanche harness: `./gradlew runAvalancheHarness`
- Validate band registration: `./scripts/validate-version-band-registration.sh`

## Adding a new version band

Scaffold a new band module:

```
./scripts/scaffold-version-band.sh <moduleName> <minecraftVersion> <yarnMappings> <fabricVersion> <minecraftDepRange>
```

After scaffolding, still required:

1. Add module to root `settings.gradle`.
2. Add module to `.github/workflows/build.yml` matrix.
3. Confirm `scripts/validate-version-band-registration.sh` passes.

The validator fails if any `mc*` module directory is missing from settings or workflow matrix.

## Manual feature parity verification (`mc121` vs `mc261`)

Use this in-game checklist to confirm runtime parity (not just compile parity).

### 0) Setup

- Create a new creative world with cheats enabled.
- Keep weather cycle enabled.
- Set daytime and force rain:

```
/time set day
/weather rain
```

### 1) Blocks + creative tab

- Open the **Natural Blocks** creative tab.
- Confirm these entries exist in both bands:
  - `sand_layer`
  - `pyramid`
  - `full_pyramid`
- Place all three and verify shape/collision behavior matches.

### 2) Sand layer behavior

For `sand_layer`, verify:

- **Support behavior**: valid support stays; removing support triggers expected break/clear behavior.
- **Water/lava interaction**: waterlogging and lava behavior match between bands.
- **Stacking behavior**: repeated placement stacks as intended and transitions at the designed threshold.

### 3) Sandstorm visuals

In a biome tagged by `sandstorm_biomes`, during rain:

- Sandstorm activates only when sky is visible.
- Dust particles render and move with wind.
- Wind direction transitions over time.
- Fog distance clamps (start/end) animate with gusts.
- Fog color darkening applies during active sandstorm.

Also verify deactivation under roof/underground and reactivation when returning outside.

### 4) Debug HUD lines

With F3 open during an active sandstorm, confirm Darude lines appear and update:

- Active / biome / sky visibility
- Wind direction and transition progress
- Particle mode and budget
- Fog start/end values

### Pass criteria

Parity is complete when both bands match on:

- Creative tab entries
- Block behavior (placement/support/stacking/interaction)
- Sandstorm activation/deactivation conditions
- Particle/fog/wind visuals
- Debug HUD output

## Notes

- Minecraft/Fabric prerelease artifacts can lag behind releases.
- If dependency resolution for `26.1` fails, use exact published coordinates available for that snapshot line.
