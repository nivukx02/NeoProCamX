# Offscreen-EGL 10-bit GPU Encoder — On-Device Re-Probe Protocol (CP-DEVICE1)

> **Why this exists.** The offscreen AHB→EGL→MediaCodec 10-bit GPU encoder path was marked
> *proven-unavailable* on the vivo I2405 and permanently fell back to the CPU-copy P010 path.
> Investigation found the "hard wall" was **never a captured EGL error** — the vivo I2405 is a
> `LEVEL_3` device that suppresses native/app `android.util.Log`, so the real reason was invisible,
> and there were **two code defects sitting in front of an unproven driver limitation**:
>
> 1. **Token-parity** — a *successful* HLG bind reported `deliveryMode=HDR_HLG`, which the Kotlin
>    acceptance gate rejected (it only accepts canonical `HLG`/`PQ`/`SDR`). Fixed: the native bridge
>    now emits canonical tokens (`HLG`/`HDR10_HLG`, …). (CP-EGL2)
> 2. **Single-config exact-veto** — `eglChooseConfig` was asked for one config and hard-vetoed; it
>    never enumerated. Fixed: it now enumerates every config (`eglGetConfigs`) for a genuine
>    `RGB10_A2 + RECORDABLE` window/ES3 config before concluding a wall. (CP-EGL3)
> 3. **Diagnostics + honest sticky** — the native reason is now surfaced through `NeoLogger`
>    (CP-EGL1), and the "unavailable" recall is persisted **only for a genuine driver wall** and is
>    **scoped to a build+OS fingerprint**, so this fixed build automatically re-probes. (CP-STICKY1)
>
> This build therefore **re-probes automatically** (the old persisted flag lacks the new fingerprint,
> so it lapses). This document is how to capture the definitive verdict on the physical device.

---

## What "success" vs "honest wall" looks like

The path attempts an offscreen bind twice: once at startup (diagnostic probes) and once per HDR/NDR
recording start. Both log the **native summary** — including the raw `status=` string that was
previously lost to suppressed logcat.

| Outcome | Log signature | Meaning |
|---|---|---|
| **GPU 10-bit works** | `GPU_10BIT_REALSURFACE_PROBE ... bound=true ... encoderSurfaceDeliveryMode=HLG encoderSurfaceColorSpace=HDR10_HLG encoderSurfaceBitDepth=10 ... status=egl context ready` | NDR now records via the GPU zero-copy 10-bit surface. |
| **Genuine config wall** | `... NOT PROVEN ... hardwareWall=true nativeSummary=[... status=recordable RGB10_A2 config unavailable (enumerated N configs)]` | Driver truly has no 10-bit recordable EGL config. Honest fallback to the P010 hybrid; persisted for this build only. |
| **Genuine surface wall** | `... hardwareWall=true nativeSummary=[... status=eglCreateWindowSurface failed for all colorspaces (HLG/PQ/default/sRGB)]` | Config exists but the codec input surface refused every colorspace. Honest P010 fallback. |
| **Transient/other** | `... hardwareWall=false nativeSummary=[... status=<other>]` | Not persisted — re-probed next launch. |

NDR/HDR is the mode that exercises this path (SDR/LOG intentionally ride the CPU-via-GPU P010 hybrid).

---

## Procedure

Run these from the project root. The `!`-prefix (type `! <cmd>` in the session) runs them inline so
the output lands here. A physical vivo I2405 (or target device) must be connected with USB debugging.

### 1. Build + install this build

```bash
# JBR 21 is required (PATH java is 26 and will not configure the NDK build).
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 2. (Optional) start from a clean recall

The fingerprint scoping already forces a re-probe, but to be 100% certain the stale flag is gone:

```bash
adb shell pm clear com.neoproc.neoproccamx    # wipes prefs → guaranteed fresh probe
```

### 3. Exercise the path

1. Launch the app, grant camera/mic permissions.
2. Switch to **NDR / HDR video** mode (this is what attempts the offscreen GPU path).
3. Record a short clip (~5 s), stop, confirm it saves.

### 4. Pull the on-device diagnostics log

`NeoLogger` writes to the app's files dir (survives logcat suppression):

```bash
# Newest log file:
adb shell 'ls -t /storage/emulated/0/Android/data/com.neoproc.neoproccamx/files/neoproc_*.log | head -1'
# Pull it (substitute the name printed above):
adb pull /storage/emulated/0/Android/data/com.neoproc.neoproccamx/files/<neoproc_XXXX.log> ./_diag/
```

### 5. Read the verdict

```bash
grep -E 'GPU_10BIT_EGL_PROBE|GPU_10BIT_REALSURFACE_PROBE|NOT PROVEN|encoderSurfaceDeliveryMode|hardwareWall|status=' ./_diag/<neoproc_XXXX.log>
```

Match the line against the table above.

- **`bound=true` + `deliveryMode=HLG`** → the offscreen GPU 10-bit encoder now works; NDR records
  via the zero-copy GPU surface. Report this back — the "unbindable 10-bit EGL" belief is retired.
- **`hardwareWall=true` + `config unavailable (enumerated N)`** → the driver genuinely lacks a 10-bit
  recordable EGL config. This is now **proven** (not inferred). NDR keeps the honest P010 hybrid; the
  `enumerated N` count is the evidence. No further software fix is possible for the EGL RGB10_A2 route
  on this device — the remaining lever would be a Vulkan external-memory/`ImageWriter` P010 path.

---

## Notes

- 10-bit SDR/LOG/NDR recording itself does **not** depend on this probe: those already reach real
  10-bit P010 today (`FORCE_UNIVERSAL_TEN_BIT_PROCESSED`, gated on `supports10Bit()`), proven on
  device (`_diag/camera_log_hdr.txt` — `format=54`/P010, `finalize DONE ... validated=true`). The
  offscreen path is the GPU **zero-copy** optimization for NDR that removes the ~331 ms/frame CPU
  copy; its absence lowers fps at 4K, never bit depth.
- The ~331 ms/frame P010 copy at 4K DCI is a genuine copy-bound hardware wall; forced 10-bit at 4K is
  an explicitly-chosen low-fps "cinema" mode, and the recording evidence honestly labels the measured
  sustainable fps (`RecordingThroughputBudgetPolicy`, e.g. "Capped to ~3fps … copy-bound at
  331ms/frame") — no fake specs.
- RAW is a separate DNG/NPRAW pipeline and is unaffected by this path; on a >12-bit sensor under
  strict high-end it now preserves native depth (CP-RAW1).
