# Darude Mod Improvement Plan (Post-Hourglass)

This plan captures high-impact improvements across runtime behavior, maintainability, release readiness, and developer workflow.

## 1) Prioritized roadmap

### P0 — Stability/Correctness (do first)[^p0-agent]

1. **Avalanche queue loss prevention when chunk windows are unavailable**
   - Scope: `shared-mc-121-/.../SandLayerAvalancheService.java`, `shared-mc-261+/.../SandLayerAvalancheService.java`
   - Goal: never silently drop pending avalanche work when required chunks are not loaded.
   - Acceptance criteria:
     - queued work is retried/deferred, not discarded.
     - queue size and deferred count observable in logs/metrics.
   - Recommended Agent: `oracle`

2. **Avalanche conservation invariants under placement failures/world height edges**
   - Scope: both `SandLayerAvalancheService` band implementations
   - Goal: prove no hidden layer loss except explicitly allowed dispersal.
   - Acceptance criteria:
     - invariant tests cover placement failure above build limit and blocked placements.
     - deterministic behavior under capped budgets.
   - Recommended Agent: `explorer`

3. **Farming budget fairness under deterministic ordering**
   - Scope: both `SandLayerFarmingService` band implementations
   - Goal: avoid starvation where some chunks/emitters never get processed under sustained load.
   - Acceptance criteria:
     - bounded scan cost + rotating chunk start index or equivalent fairness strategy.
     - benchmark scenario with multiple players and emitter clusters stays stable.
   - Recommended Agent: `oracle`

---

### P1 — Performance & Runtime observability[^p1-agent]

4. **Runtime counters and debug telemetry**
   - Add per-tick counters for:
     - farming vertical checks
     - farming operations used
     - avalanche queue size
     - topples processed and deferred work
   - Acceptance criteria:
     - toggleable debug logging
     - enough signal to tune config defaults safely
   - Recommended Agent: `fixer`

5. **Avalanche processing coalescing**
   - Goal: reduce repeated 3x3 window reads for nearby queued centers.
   - Acceptance criteria:
     - centers in same/neighbor windows can be coalesced per tick pass.
     - measurable reduction in block-state reads in stress profile.
   - Recommended Agent: `oracle`

6. **Storm-time farming scan optimization without breaking stacked-farm behavior**
   - Goal: retain vertical stack support while reducing unnecessary full-column scans.
   - Acceptance criteria:
     - scan cost scales with candidate density more than world height.
     - preserve V1 semantics in `docs/sand-layer-farming-plan.md`.
   - Recommended Agent: `explorer`

---

### P1/P2 — DRY and maintenance risk reduction[^dry-agent]

7. **Extract duplicated farming/avalanche logic into shared core utilities**
   - Current duplication exists between `shared-mc-121-` and `shared-mc-261+`.
   - Goal: one behavior implementation, thin version adapters.
   - Acceptance criteria:
     - algorithmic logic centralized (in `common` or shared helper package)
     - only API/mapping-specific shims remain per band
     - no behavior divergence between bands for same scenarios
   - Recommended Agent: `oracle`

8. **DRY worldgen parity pass**
   - Scope: duplicated `SandLayerChunkGeneration` logic across bands.
   - Acceptance criteria:
     - shared decision logic extracted or generated parity checks added.
   - Recommended Agent: `explorer`

---

### P1/P2 — Release readiness and mod-page quality[^release-agent]

9. **Modrinth/CurseForge listing completeness pass**
   - Add/verify:
     - icon (high-res), gallery screenshots/gifs
     - concise feature summary and config section
     - compatibility matrix (MC version + loader)
     - clear license/permissions text
     - changelog structure template
   - Acceptance criteria:
     - checklist in docs maps 1:1 to release workflow inputs.
   - Recommended Agent: `librarian`

10. **User + server-admin docs split**
    - Add sections/pages for:
      - user gameplay mechanics (farming and avalanche)
      - server/admin tuning guide for config knobs
      - performance tuning defaults for low/mid/high server capacity
    - Acceptance criteria:
      - docs are enough to deploy mod on server without reading source.
    - Recommended Agent: `librarian`

---

### P1/P2 — Workflow hardening[^workflow-agent]

11. **CI efficiency and safety improvements**
    - Add:
      - Gradle cache strategy hardening
      - workflow concurrency cancellation for stale runs
      - early publish preflight (secret/input validation)
    - Acceptance criteria:
      - faster average CI time
      - no accidental publish with missing metadata/secrets.
    - Recommended Agent: `fixer`

12. **Release artifact verification**
    - Add checksum generation + optional signing verification for jars in `release-jars`/publish pipeline.
    - Acceptance criteria:
      - artifact hash published with release notes.
    - Recommended Agent: `fixer`

---

### P0/P1 — Test strategy expansion[^test-agent]

13. **Convert harness coverage into executable test suites**
    - `common/src/test/java/com/darude/renewal/AvalancheRedistributorHarness.java` currently acts more like a manual harness.
    - Goal: move scenarios into JUnit/parameterized tests run by CI.
    - Acceptance criteria:
      - conservation, slope threshold, and budget tests are automated.
    - Recommended Agent: `explorer`

14. **Band-level integration tests for farming/avalanche edge cases**
    - Add tests for:
      - emitter chain depth and fall-through
      - chunk-boundary avalanche transfers
      - build-limit and blocked-placement behavior
      - desert-biome gating vs spill-outside-biome avalanche behavior
    - Acceptance criteria:
      - reproducible fixtures for 121 and 261 paths.
    - Recommended Agent: `fixer`

15. **GameTest/integration lane (optional but recommended)**
    - Add a lightweight GameTest suite for critical runtime behavior where plain unit tests cannot model world state sufficiently.
    - Acceptance criteria:
      - at least one automated runtime scenario in CI for emitter->avalanche chain.
    - Recommended Agent: `librarian`

---

## 2) 30/60/90 execution schedule[^schedule-agent]

### First 30 days
- Finish P0 items (queue-loss prevention, conservation invariants, fairness baseline).
- Add telemetry counters and logging toggles.
- Convert avalanche harness into CI-executed tests.

### 31–60 days
- Coalesce avalanche processing windows and optimize farming scan strategy.
- Complete CI hardening and publish preflight checks.
- Ship user/admin docs split + listing assets.

### 61–90 days
- Extract duplicated algorithmic logic from band modules.
- Add integration/GameTest lane and parity checks across bands.
- Formalize release checklist gates based on runtime metrics and tests.

## 3) Risk register[^risk-agent]

- **High risk**: silent behavior drift due to per-band duplication.
- **High risk**: unobserved tick spikes under dense emitter/queue scenarios.
- **Medium risk**: release quality perception (missing media/docs despite technical completeness).
- **Medium risk**: CI drift where build success does not guarantee runtime behavior correctness.

## 4) Definition of done (overall)[^dod-agent]

- All critical runtime invariants are tested and passing in CI.
- No known silent loss paths in avalanche/farming flows.
- Band behavior parity is enforced by tests or shared implementation.
- Release workflow is preflight-validated and artifact-verified.
- Mod pages and docs are complete for both players and server admins.

[^p0-agent]: Best-fit agent: `oracle`
[^p1-agent]: Best-fit agent: `implementer`
[^dry-agent]: Best-fit agent: `planner`
[^release-agent]: Best-fit agent: `documenter`
[^workflow-agent]: Best-fit agent: `implementer`
[^test-agent]: Best-fit agent: `reviewer`
[^schedule-agent]: Best-fit agent: `planner`
[^risk-agent]: Best-fit agent: `reviewer`
[^dod-agent]: Best-fit agent: `refiner`
