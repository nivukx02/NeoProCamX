# Vulkan Bridge Native Runtime (`vulkan_bridge.cpp`) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Global contract applies; not restated.

| Field | Value |
|---|---|
| **Subsystem** | 7 — AI/ML + Vulkan rendering + Startup |
| **Prompt ID** | s7-f11 |
| **Maps to checkpoint(s)** | CP73 (Vulkan 1.1 + dedicated transfer queue + remove per-frame memcpy) |
| **Current working level** | 🟢 live-default-on |
| **Feature rating (potential)** | 9/10 |
| **Code-quality rating (now)** | 9/10 |
| **Risk · Size** | H · L |
| **Depends on** | s7-f12 (runtime manager), s6 NPV10 export (the memcpy consumer) |

## 1. What this feature is
`vulkan_bridge.cpp` (~7.8k SLOC C++) is the native Vulkan runtime: real `VkInstance`/device/global
init, `AHardwareBuffer` external-memory import, the cinema RAW debayer + preview/encoder render
compute passes, timeline-semaphore sync, a graphics pipeline cache, and a deferred background
pipeline-compile path. It is the strongest, most complete part of the codebase. Elite work here is
**hardening to IMAX-grade real-time**: finish the zero-copy story (CP73 — dedicated transfer queue,
kill per-frame `memcpy`) without regressing the live render.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
GPU-accelerated cinema pipelines (RED IPP2, Blackmagic) move frames with zero app-visible copies
and overlap transfer with compute on dedicated hardware queues. The Vulkan best-practice elite bar:
- **Dedicated transfer queue:** uploads/downloads run on a transfer-capable queue family separate
  from graphics/compute, so DMA overlaps rendering instead of serializing on one queue
  (CP73). Ownership transfers use queue-family-acquire/release barriers.
- **Timeline semaphores** for fine-grained cross-queue sync (already present — keep) instead of
  coarse fences/`vkQueueWaitIdle`.
- **Zero per-frame `memcpy`:** the NPV10 export path's per-frame host copy is removed; frames stay
  in `AHardwareBuffer`-backed images imported with `VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO`
  (avoids ~1.5 GB/s at 4K60 — see tech doc 13).
- **Pipeline cache** persisted (warm starts — see s7-f13) and primed off the cold-start path.
- **Validation-clean:** zero validation-layer errors in debug; no UB, no UNDEFINED-layout reads.

## 3. Current state in NeoProCamX (verified)
- `app/src/main/cpp/vulkan_bridge.cpp` (7781 lines). Already on `VK_API_VERSION_1_1`
  (`vulkan_bridge.cpp:2421`). Timeline semaphores supported/enabled + used
  (`timelineSemaphore`/`timelineSemaphoresSupported` `385, 513-514`; `vkWaitSemaphores(timeline)`
  `2183`). Graphics `VkPipelineCache` present (`graphicsPipelineCache` `463`).
- **Single queue family for everything** — `queueFamilyIndex` (`452`), one `graphicsQueue`
  (`453`, fetched `2577`); single-use submits + `vkQueueWaitIdle(graphicsQueue)` (`981, 985`); the
  per-frame render submits on `graphicsQueue` (`6789`). **No dedicated transfer queue exists** —
  this is the CP73 gap.
- **Per-frame `memcpy` present:** host copies at `1579, 1747, 1864` (upload staging); the NPV10
  export per-frame host copy is the CP73 target (roadmap CP73: "remove per-frame memcpy in NPV10
  export"). AHB import uses `MEMORY_DEDICATED_ALLOCATE_INFO` (`6914-6917`) — the zero-copy
  candidate exists; the export path still stages through host memory.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Dedicated transfer queue (CP73):** if the device exposes a transfer-capable queue family,
   acquire it and run NPV10 export uploads/downloads there with proper queue-family ownership
   barriers; fall back to the graphics queue when no separate family exists (no-downgrade).
2. **Zero per-frame export `memcpy`:** the NPV10 export per-frame host copy is eliminated when the
   AHB zero-copy path is available; an import/copy counter proves zero host copies on the device
   path (the existing import-stats telemetry / `isTrulyZeroCopy` validation, tech doc 13).
3. **Validation-clean:** debug validation-layer run reports zero errors / zero UNDEFINED-layout
   reads across init, import, transfer, and render.
4. **Sync correctness:** cross-queue ordering via timeline semaphores + ownership barriers; no
   data race (validation-layer sync checks clean).
5. **No-downgrade default:** transfer-queue/zero-copy path behind a runtime capability check +
   flag; when absent OR flag off, the current single-queue + staged path runs bit-for-bit. Live
   preview/encoder render output is unchanged.
6. **Budget:** removing the copy reduces frame-import latency (target frame-import ≪ render time);
   pipeline-cache priming stays off the cold-start path.

## 5. Implementation prompt (hand this to the agent)
**Scope:** CP73 — add an optional dedicated transfer queue + remove the NPV10-export per-frame
host `memcpy` via the AHB zero-copy path, capability-gated. **Non-goals:** do NOT change the live
preview/encoder render math or shaders; do not touch the debayer compute correctness; no new
extensions beyond what Vulkan 1.1 + the already-used AHB import provide (no-vendor).

**Plan:**
1. **Golden/parity-lock FIRST:** capture preview=record=export signature parity (subsystem 6
   tri-surface lock) so the transfer-queue change is proven pixel-identical.
2. **Queue selection:** at device init, query queue families; pick a dedicated TRANSFER family if
   present (else reuse graphics). Store its index + queue. No-downgrade: absent ⇒ current path.
3. **Ownership barriers:** for AHB-imported export images, add queue-family
   acquire/release barriers + timeline-semaphore waits between transfer and render/encode.
4. **Kill the copy:** replace the NPV10-export per-frame host `memcpy` with the dedicated-import
   path; keep the staged copy as the fallback branch.
5. **Counters:** wire the import/zero-copy counter so the gate can PROVE zero copies (no synth).

**Files:** `app/src/main/cpp/vulkan_bridge.cpp` (queue init, barriers, export import path),
`cpp/native_buffer_manager.cpp/.h`, the NPV10 export native entry (subsystem 6).

**ABI:** no push-constant/UBO change expected (transport-only). If any struct crosses JNI, keep
the layout + its test pinned. Re-pin no shader-manifest hash (shaders unchanged).

**Gating:** transfer-queue + zero-copy export behind a capability check + flag defaulting to the
current staged single-queue path.

## 6. Acceptance & verification
- **Native:** `:app:externalNativeBuildDebug` green; debug validation-layer run — zero errors,
  zero UNDEFINED-layout reads, sync-validation clean on init/import/transfer/render.
- **JVM:** tri-surface parity lock (preview=record=export) green; import/zero-copy counter test;
  `OmegaCapabilityGate` zero-copy claim proves from the real counter (never synthesized).
- **Device (vivo I2405 / Adreno):** NPV10 export — import counter shows zero per-frame host copies
  on the zero-copy path; export output pixel-identical to the staged path; frame-import latency
  drops; thermal soak — no transfer-queue stall, no dropped frame; device without a separate
  transfer family falls back cleanly.

## 7. Risks & no-downgrade guardrails
- **Queue-ownership data race / corruption:** acquire/release barriers + timeline-semaphore order
  + sync-validation gate → impossible-by-construction race.
- **Black/garbage export frame:** parity-lock proves pixel-identical before trusting; staged
  fallback always available.
- **Device without transfer family:** capability check ⇒ current single-queue path.
- **Fake zero-copy claim:** real import counter + OmegaCapabilityGate, never synthesized.
- **Thermal/lag:** transfer overlaps render (faster, not slower); pipeline-cache priming off
  cold-start (s7-f13).

## 8. References
- Roadmap: CP73 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/13-hardwarebuffer-zero-copy.md`](../technology_explanations/markdown/13-hardwarebuffer-zero-copy.md), [`../technology_explanations/markdown/10-vulkan-cinema-debayer.md`](../technology_explanations/markdown/10-vulkan-cinema-debayer.md)
- Source: `app/src/main/cpp/vulkan_bridge.cpp:385, 452-453, 463, 981, 1579, 2421, 2577, 6789, 6914`
- Related prompts: s7-f12, s7-f13, s6 NPV10 export
