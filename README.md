# darude

Fabric mod focused on sandstorms, renewable sand and sand layers.

## Version bands

- `mc121`: Minecraft `1.21.x`
- `mc261`: Minecraft `26.1`

Shared logic lives in `common`. Version-banded shared MC baselines live in:

- `shared-mc-121` for `<= 1.21.x`
- `shared-mc-261` for `>= 26.1`

Version-specific bootstrap/adapters live per band module.

## Requirements

- Java 21
- Minecraft 1.21.x (mc121) or 26.1 (mc261)
- Fabric Loader 0.18.2+

## Development

- Run 1.21 client: `./gradlew :mc121:runClient`
- Run 26.1 client: `./gradlew :mc261:runClient`
- Build all jars: `./gradlew build`
- Run avalanche harness: `./gradlew runAvalancheHarness`

## Onboarding new version bands

Create a new band module from the existing template with:

```
./scripts/scaffold-version-band.sh <moduleName> <minecraftVersion> <yarnMappings> <fabricVersion> <minecraftDepRange>
```

The script adds the new module structure, links it to the shared MC sources, and appends version coordinates to `gradle.properties`. After running it you still need to add the module to `settings.gradle` and the build matrix in `.github/workflows/build.yml` so CI knows to build the new band.

CI enforces this with:

```
./scripts/validate-version-band-registration.sh
```

It fails if any `mc*` module directory is not listed in `settings.gradle` or the workflow matrix.

## Notes

Minecraft/Fabric prerelease artifacts can lag. If dependency resolution fails for `26.1`, use the exact published Fabric/Yarn coordinates for that prerelease snapshot.
