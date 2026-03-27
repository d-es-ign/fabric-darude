# Darude release checklist (Modrinth + CurseForge)

Use this checklist every time you publish a new release.

## 0) One-time CI setup

Add repository secrets for the publish workflow:

- `MODRINTH_PROJECT_ID`
- `MODRINTH_TOKEN`
- `CURSEFORGE_PROJECT_ID`
- `CURSEFORGE_TOKEN`

Workflow: `.github/workflows/publish.yml`

- Manual publish: **Actions -> publish -> Run workflow**
- Safe build-only test: run with `dry_run=true`
- Automatic publish: publishing a GitHub Release also triggers this workflow

## 1) Build both version-band jars

```bash
./gradlew -p builds/mc121 build
./gradlew -p builds/mc261 build
```

Artifacts are generated in:

- `builds/mc121/build/libs/`
- `builds/mc261/build/libs/`

## 2) Pick the correct jar files

Upload the runtime/remapped jar for each band.

Do **not** upload:

- `*-dev.jar`
- `*-sources.jar`

Expected outputs should look like:

- `...-mc121-<version>.jar`
- `...-mc261-<version>.jar`

## 3) Required metadata per uploaded file

For both Modrinth and CurseForge, each uploaded file should declare:

- **Mod loader:** Fabric
- **Dependency:** Fabric API (required)

Band-specific Minecraft versions:

- `mc121` jar -> `1.21.11`
- `mc261` jar -> `26.1`

## 4) Modrinth publish steps

1. Open project -> **Versions** -> **Create version**.
2. Upload `mc121` jar.
3. Set:
   - Game version: `1.21.11`
   - Loaders: `Fabric`
   - Dependencies: `Fabric API` as required
4. Save/publish version.
5. Repeat with `mc261` jar:
   - Game version: `26.1`
   - Loaders: `Fabric`
   - Dependencies: `Fabric API` as required

Tip: Create two separate Modrinth versions (one per Minecraft line) even if changelog text is the same.

## 5) CurseForge publish steps

1. Open project -> **Files** -> **Upload File**.
2. Upload `mc121` jar.
3. Set:
   - Game version: `1.21.11`
   - Mod loader: `Fabric`
   - Relations/dependencies: `Fabric API` required
4. Save/publish file.
5. Repeat with `mc261` jar:
   - Game version: `26.1`
   - Mod loader: `Fabric`
   - Relations/dependencies: `Fabric API` required

## 6) Pre-publish sanity checks

- Both CI jobs green for latest commit (`mc121` + `mc261`).
- `fabric.mod.json` contains correct dependency range for each band.
- Version/changelog text matches actual changes.
- Tested both jars once in client startup (no immediate crash on load).

## 7) Recommended release notes template

```text
Darude update:
- Multi-version support maintained for 1.21.11 (mc121) and 26.1 (mc261)
- Sandstorm visual + debug HUD parity updates
- Sand layer behavior fixes and build wiring cleanup

Requires Fabric Loader and Fabric API.
Use the file matching your Minecraft version.
```
