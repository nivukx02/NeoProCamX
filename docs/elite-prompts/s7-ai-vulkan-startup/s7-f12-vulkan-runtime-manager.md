# VulkanRuntimeManager (frame-import orchestration) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Global contract applies; not restated.

| Field | Value |
|---|---|
| **Subsystem** | 7 — AI/ML + Vulkan rendering + Startup |
| **Prompt ID** | s7-f12 |
| **Maps to checkpoint(s)** | new — propose CP9B (runtime-manager hardening); pairs with CP73 |
| **Current working level** | 🟢 live-default-on |
| **Feature rating (potential)** | 8/10 |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · M |
| **Depends on** | s7-f11 (native bridge), s7-f13 (cold-start init) |

## 1. What this feature is
`VulkanRuntimeManager` is the Kotlin orchestration layer over `vulkan_bridge.cpp`: lazy
non-blocking init, frame-import lifecycle, cinema-debayer activation, preview/encoder surface
binding, and import-stats telemetry. It is the JVM boundary that decides when the native runtime
starts, which surfaces it renders to, and surfaces the zero-copy / import counters that the quality
gate consumes.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
The runtime-management bar is reliability, not pixels: the GPU runtime must come up lazily without
blocking the UI thread, bind surfaces deterministically, and report truthful telemetry (real import
counts, real zero-copy status) so the capability gate can't be fooled. Elite means:
- **Non-blocking lazy init** off the cold-start path (BootTrace milestones already mark it).
- **Deterministic surface binding** — no race between preview-surface-ready and first frame
  (the surfaceChanged-storm was already killed; keep it killed).
- **Truthful telemetry** — import-stats / zero-copy counters reflect what the native path actually
  did (feeds OmegaCapabilityGate; never synthesized).
- **Graceful degradation** — raw-passthrough when import fails, no black preview.

## 3. Current state in NeoProCamX (verified)
- `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt` — lazy non-blocking
  init; frame-import lifecycle; cinema-debayer activation; preview/encoder binding; import-stats
  telemetry (FEATURE_CATALOG §7). BootTrace marks `vk.initialize.start` / `.bridgeReady` (`269,
  271`) and `vk.setPreviewSurface.start/.end` (`432, 434`).
- Pairs directly with the CP73 native transfer-queue work (s7-f11): this layer must expose the
  zero-copy counter the gate reads, and bind the export surface for the dedicated-transfer path.
- Hardening gaps (verify in source): import-failure → raw-passthrough path coverage; telemetry
  truth under fallback; surface-binding determinism tests.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Truthful telemetry:** import-stats / zero-copy counters reflect the native result exactly;
   test asserts a forced fallback reports fallback (not zero-copy) and the gate reads the real
   value (no synthesized pass).
2. **Graceful import failure:** a simulated import failure degrades to raw-passthrough with no
   black preview and no crash; test asserts the fallback path is taken.
3. **Deterministic binding:** preview/encoder surface binding is race-free (no dependence on
   surfaceChanged ordering); test/contract asserts first-frame readiness.
4. **Non-blocking init:** init never runs on the cold-start-critical thread; BootTrace milestones
   present (regression guard).
5. **No-downgrade default:** all current binding/telemetry behavior unchanged; any new counter or
   export-binding path is additive and defaults to today's behavior.

## 5. Implementation prompt (hand this to the agent)
**Scope:** harden import-failure fallback + telemetry truth + binding determinism; expose the
CP73 zero-copy counter for the gate. **Non-goals:** do not change render output; do not move init
onto the cold-start path; no new dependency (no-vendor).

**Plan:**
1. **Golden-lock:** pin current import-stats output + binding behavior.
2. **Telemetry truth:** ensure the zero-copy / import counters are sourced from the native result
   (s7-f11) and never defaulted to "true"; add the gate-facing accessor.
3. **Fallback coverage:** add explicit raw-passthrough on import failure with a test simulating
   failure.
4. **Binding determinism:** assert first-frame readiness independent of surfaceChanged ordering.

**Files:** `rendering/VulkanRuntimeManager.kt`, the import-stats data class, the gate-facing
telemetry accessor.

**ABI:** none (JVM orchestration; counters cross JNI as primitives — keep types pinned).

**Gating:** additive counters/fallback default to current behavior.

## 6. Acceptance & verification
- **Unit (JVM):** telemetry-truth (fallback reports fallback), import-failure→passthrough,
  binding-determinism, golden-lock.
- **Device (vivo / Adreno):** preview comes up without blocking UI; forced import failure shows
  raw passthrough not black; zero-copy counter matches the CP73 native path; BootTrace milestones
  present in debug.

## 7. Risks & no-downgrade guardrails
- **Black preview on import failure:** raw-passthrough fallback covered by test.
- **Fake zero-copy telemetry:** counters sourced from native result; OmegaCapabilityGate truth.
- **Cold-start regression:** init stays off the critical path (s7-f13 guard).
- **Regression:** golden-lock on import-stats + binding.

## 8. References
- Roadmap: propose CP9B; pairs with CP73 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/13-hardwarebuffer-zero-copy.md`](../technology_explanations/markdown/13-hardwarebuffer-zero-copy.md)
- Source: `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt:269, 271, 432, 434`
- Related prompts: s7-f11, s7-f13, s8 (OmegaCapabilityGate)
