# CI Runtime Test Plan

## Objective

Add a runtime-oriented CI layer that tests the built mod jar in a real Fabric server environment instead of relying only on compile-time checks.

This is intended to catch issues like:

- broken datapack/tag loading
- worldgen paths that compile but do nothing at runtime
- command registration/runtime mapping mismatches
- server startup crashes caused by mixins, resources, or version-band-specific code

## Audience

Maintainers working on CI, worldgen, farming, and cross-band runtime stability.

## Scope

This plan is intentionally server-first.

It covers:

- headless Fabric dedicated server startup
- fixed-seed world creation
- scripted chunk generation
- log/assertion checks for expected runtime milestones

It does **not** initially cover:

- client-only rendering validation
- particle/overlay visual correctness
- full gameplay automation beyond simple server-side assertions

## Recommended rollout

Implement this in phases.

### Phase 1: Boot + chunkgen smoke test

Goal: prove the built jar loads and worldgen paths execute without crashing.

CI flow:

1. Build the target jar for the version band.
2. Create a temporary server directory.
3. Download/install the matching Fabric server launcher.
4. Place the built mod jar into `mods/`.
5. Write deterministic server config:
   - fixed level seed
   - known world name
   - `online-mode=false`
   - `eula=true`
   - short view/simulation distances
6. Start the server headlessly.
7. Wait for a successful “server started” log marker.
8. Force chunk generation around one or more known coordinates.
9. Stop the server cleanly.
10. Assert on logs and exit code.

Minimum assertions:

- server reaches ready state
- no uncaught exceptions / crash
- no missing-tag/missing-resource failures
- expected chunkgen startup log appears

### Phase 2: Command-driven integration test

Goal: prove key server-side debug/runtime hooks actually work in the built jar.

Add scripted commands after startup, for example:

- teleport to a known location
- set weather/time if needed
- run runtime debug commands when enabled
- capture returned output from the server console

Good first command targets:

- farming tag resolution diagnostics
- farming stats command
- chunkgen checkpoint logs

Assertions should be text-based and narrow.

Examples:

- command exists and executes
- emitter tag resolves nonzero in a known setup
- chunkgen checkpoint reached at least one expected milestone

### Phase 3: World-state assertions

Goal: verify that generated world state changed in the expected way.

Options:

- parse logs only
- inspect region/chunk data afterward
- use a small helper mod/plugin/test harness to query blocks at known coordinates

This phase is more expensive and should only be added once Phase 1/2 are stable.

## Seed strategy

Use a fixed seed for reproducibility.

Recommendation:

- keep one canonical seed for general smoke tests
- optionally add a second seed later if a feature depends on biome layout

The test should also use fixed coordinates for teleport/chunk generation so failures are easy to reproduce locally.

## Version-band strategy

Run the runtime smoke test independently per band:

- `mc121`
- `mc261`

Do not assume one passing band implies runtime correctness in the other. This repository has repeatedly hit mapping/API/resource mismatches that only show up in one band.

## Initial assertions to include

### Startup assertions

- Fabric server starts successfully
- Darude mod initializes
- no resource/tag loading exceptions
- no mixin application failures

### Chunkgen assertions

- chunk generation callback runs
- near-desert/worldgen path does not crash
- no budget/error logs indicating catastrophic failure

### Near-desert-specific assertions

Leverage the new one-off checkpoint logs.

Useful markers:

- `near-desert checkpoint: precheck passed`
- `near-desert checkpoint: probe succeeded`
- `near-desert checkpoint: support rejected`
- `near-desert checkpoint: placement succeeded`

At least one of these appearing is useful signal that the path is alive.

For a pure smoke test, do not require `placement succeeded` immediately unless the seed/coordinates are proven deterministic enough.

## Logging strategy

Prefer sparse, one-off log markers over continuous trace spam.

Use logs for:

- checkpoint reached
- fallback path activated
- startup success/failure

Avoid reintroducing per-chunk spam into CI because it makes failures harder to read and artifacts harder to inspect.

## Suggested CI implementation shape

### Workflow structure

Add a separate job after compile/build succeeds:

- `runtime-smoke (mc121)`
- `runtime-smoke (mc261)`

These jobs should depend on successful jar build for that band.

### Inputs/artifacts

Each job needs:

- built mod jar
- Fabric server launcher for that MC version
- generated server directory
- config files and optional command scripts

### Containerization

Containerization is optional but reasonable.

Two approaches:

1. **Run directly on GitHub runner**
   - simpler to start with
   - fewer moving pieces

2. **Use a containerized server harness**
   - better isolation
   - easier to reproduce locally with Docker
   - more setup overhead

Recommendation: start on the runner first, containerize only if the setup becomes messy or flaky.

## Concrete first milestone

Implement a single smoke job per band that:

1. starts a headless Fabric server
2. loads the built jar
3. creates a fixed-seed world
4. generates a few chunks near spawn
5. waits for one near-desert/worldgen checkpoint or a clean startup marker
6. fails on crash or obvious missing-resource/tag errors

That alone would have caught several issues already seen in this repository.

## Local reproduction workflow

Whatever CI script is added should also be runnable locally.

Target outcome:

- one shell script or Gradle task to reproduce the same smoke test locally

This matters because runtime CI failures will otherwise be slow to debug.

## Risks / caveats

- Minecraft/Fabric startup is slower than normal unit/integration tests
- log-based assertions can be brittle if message text changes often
- fixed seeds help, but chunkgen behavior can still be noisy if assertions are too strict
- world-state inspection is more reliable than logs, but more expensive to build

## Recommendation summary

Build this in order:

1. **Server boot smoke test**
2. **Fixed-seed chunk generation smoke test**
3. **Command/log assertions for runtime hooks**
4. **Optional block/state assertions later**

Start minimal and deterministic.

## Open questions

- Which exact seed/coordinates should be canonical for near-desert coverage?
- Should runtime smoke tests run on every PR, or only when worldgen/server files change?
- Do we want debug commands enabled in CI via JVM flag for command-driven assertions, or should CI rely purely on logs?
- Is a small dedicated test harness script preferred over embedding server orchestration directly in GitHub Actions YAML?
