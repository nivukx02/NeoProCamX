# Cold-Start Path (splash + BootTrace + deferred compile) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Global contract applies; not restated.

| Field | Value |
|---|---|
| **Subsystem** | 7 — AI/ML + Vulkan rendering + Startup |
| **Prompt ID** | s7-f13 |
| **Maps to checkpoint(s)** | new — propose CP9C (disk pipeline-cache for warm starts) |
| **Current working level** | 🟢 live-default-on |
| **Feature rating (potential)** | 7/10 |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · M |
| **Depends on** | s7-f11 (native pipeline cache), s7-f12 (lazy init) |

## 1. What this feature is
The cold-start path is the boot sequence from process launch to first live preview frame:
`installSplashScreen` (app logo on black) held until the first preview frame or a safety deadline,
a `BootTrace` milestone profiler (debug-only), and deferred heavy-pipeline SPIR-V compilation
(~2.5 s) moved off the critical path. Recent perf work already landed (parallel pipeline compile,
first-Surface camera open, deferred compute compile, cold-start splash). The elite delta is a
**disk pipeline-cache for warm starts** (deferred per memory) so the second-and-later launches skip
shader compilation.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
A pro camera is "ready to roll" near-instantly. The startup-engineering elite bar:
- **Bounded cold-start-to-first-preview** with a hard safety deadline (splash never hangs) — the
  splash is dismissed by a *real* signal (first preview frame), not a timer guess.
- **Persisted pipeline cache:** `vkGetPipelineCacheData()` is serialized to disk on a background
  thread after warm-up and fed back as `pInitialData` on next launch, so warm starts skip the
  ~2.5 s SPIR-V→ISA compile (standard Vulkan warm-start practice; tech-doc-13 latency ethos).
- **Heavy work strictly deferred:** nothing on the critical path that isn't required for the first
  frame; the adaptive governor never runs heavy compute during boot.
- **Measurable budgets:** BootTrace milestones give cold-start-to-first-preview ms and the
  per-stage breakdown so regressions are caught.

## 3. Current state in NeoProCamX (verified)
- Splash: `installSplashScreen()` (`ui/MainActivity.kt:301`), held until the first fps sample =
  live preview renders (`MainActivity.kt:877`), with `preview.surface.ready` BootTrace mark
  (`897`); `installSplashScreen()` must precede `super.onCreate` (`300`).
- BootTrace: `utils/BootTrace.kt` (debug-only milestone profiler, "intended to find where
  cold-start time goes, then to be removed" — `BootTrace.kt:8-11`); marks across
  `NeoProCamXApp.kt:63, 79`, `VulkanRuntimeManager.kt:269-434`, `CameraViewModel.kt:2940-2944`,
  `CameraController.kt:1283` (`markFirstFrameOnce()`).
- **The warm-start gap — verified:** `vkCreatePipelineCache` is called with an empty
  `VkPipelineCacheCreateInfo` (`vulkan_bridge.cpp:2631-2641`) — `pInitialData`/`initialDataSize`
  are NOT set, and there is no `vkGetPipelineCacheData` / disk read-back / persist anywhere in the
  bridge. The cache is **in-memory only**; every cold start re-compiles. This is the deferred CP9C
  work (per memory: focus-and-startup-upgrades).

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Disk pipeline-cache (CP9C):** after first warm-up, `vkGetPipelineCacheData()` is written to
   app-private storage on a background thread; next launch reads it and passes it as
   `pInitialData`/`initialDataSize`. A validity check (header magic / device UUID / driver version
   match) rejects a stale or wrong-device cache and falls back to fresh compile.
2. **Warm-start speedup (device):** second+ launch cold-start-to-first-preview is measurably faster
   than first launch (the ~2.5 s compile is skipped), measured via BootTrace milestones.
3. **Splash never hangs:** the safety-deadline dismiss is preserved; first-frame signal still
   drives the normal dismiss; test/contract asserts both.
4. **No critical-path regression:** cache write is background + post-first-frame; cache read adds
   only a bounded file read before pipeline create; nothing heavy added to the critical path.
5. **No-downgrade default:** cache read/write behind a flag; absent/invalid/disabled ⇒ today's
   fresh-compile path bit-for-bit. First-ever launch is unchanged.

## 5. Implementation prompt (hand this to the agent)
**Scope:** CP9C — persist + restore the Vulkan pipeline cache to disk for warm starts, validity-
checked, behind a no-downgrade flag. **Non-goals:** do not change the splash dismiss logic or the
deferred-compile ordering (already good); do not add a dependency (no-vendor); do not block boot
on the cache read.

**Plan:**
1. **Lock the budget:** capture current BootTrace cold-start milestones as the baseline to beat
   (and never regress).
2. **Persist:** after warm-up (post-first-frame, background thread), `vkGetPipelineCacheData()` →
   write to app-private file with a header (magic + `VkPhysicalDeviceProperties` pipelineCacheUUID
   + driver version + size).
3. **Restore:** on init, read the file, validate the header against the current device; if valid,
   seed `VkPipelineCacheCreateInfo.pInitialData`/`initialDataSize`; else fresh compile.
4. **Flag + fallback:** disabled/invalid/mismatched ⇒ current empty-cache path.
5. **Tests:** header validity (accept matching, reject mismatched/truncated); persist→restore
   round-trip on the JVM-testable wrapper; splash safety-deadline + first-frame dismiss.

**Files:** `app/src/main/cpp/vulkan_bridge.cpp` (cache create/get + file I/O + header validate),
new native cache-IO helper, `rendering/VulkanRuntimeManager.kt` (cache file path / flag), keep
`utils/BootTrace.kt` for measurement.

**ABI:** none (no push-constant/UBO change; the cache blob is opaque driver data — the header
struct is internal, add a test pinning its layout).

**Gating:** cache read/write behind a flag defaulting OFF ⇒ fresh-compile, identical to today.

## 6. Acceptance & verification
- **Unit (JVM):** header validity (accept/reject mismatch/truncation), persist→restore round-trip,
  splash dismiss (first-frame + safety-deadline).
- **Native:** `:app:externalNativeBuildDebug` green; validation-layer clean with seeded cache.
- **Device (vivo I2405 / Adreno):** first launch writes the cache; second launch reads it and
  cold-start-to-first-preview is faster (BootTrace delta); corrupting/replacing the file with a
  wrong-device blob falls back to fresh compile with no crash; flag off reproduces current timing.

## 7. Risks & no-downgrade guardrails
- **Stale/wrong-device cache → driver crash:** strict header validation (pipelineCacheUUID +
  driver version) + fresh-compile fallback → impossible to feed a bad blob.
- **Boot blocked on file read:** bounded read; write is background + post-first-frame.
- **Splash hang:** safety-deadline dismiss preserved.
- **Regression:** flag defaults OFF (fresh compile); BootTrace baseline guards the budget.

## 8. References
- Roadmap: propose CP9C in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Memory: focus-and-startup-upgrades (disk pipeline-cache deferred)
- Source: `app/src/main/cpp/vulkan_bridge.cpp:2631`, `ui/MainActivity.kt:300, 301, 877, 897`,
  `utils/BootTrace.kt:8`, `rendering/VulkanRuntimeManager.kt:269`
- Related prompts: s7-f11, s7-f12
