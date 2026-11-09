# NEO-QA HyperGrid

NEO-QA HyperGrid turns the canonical 220-checkpoint production ledger into a deterministic,
evidence-gated test system. It schedules independent host work in parallel, globally serializes
physical-device work, rejects quality downgrades, simulates deterministic failure contracts, and
keeps PASS/FAIL/BLOCKED/PENDING classifications attributable to a specific candidate.

It does not treat a Python simulation, source contract, successful build, or requested codec format
as proof of physical camera colour, image quality, encoded FPS, thermal performance, display
behaviour, or universal compatibility. Those claims require the exclusive-device lane and sealed
hardware evidence.

## Components

- `checkpoint_graph.py` validates CP-001 through CP-220, compiles every required field and five
  subchecks, checks cycles, and emits dependency-aware waves.
- `digital_twin.py` models lifecycle, RAW ownership, encoder cadence, controls, profile generations,
  conversion, playback, transactions, permissions, and pressure deterministically.
- `failure_injection.py` exercises 15 predictable failures and exact-once recovery contracts.
- `host_grid.py` runs only output-disjoint lanes concurrently and rejects shared Gradle caches or
  overlapping build outputs in the same wave.
- `device_orchestrator.py` enforces one canonical machine-global atomic ADB lease, complete
  device/build identity, thermal admission, explicit serials, and UI-tree-derived coordinates.
- `evidence_oracle.py` validates candidate/device identity, seals artifacts, parses ffprobe wire
  evidence, measures PTS/FPS/GOP structure, and audits runtime logs.
- `mov_timing_oracle.py` performs bounded ISO-BMFF/MOV atom parsing for CP-178, retaining every
  `stts` run, exact expanded sample/duration totals, final-sample duration, movie/track/media
  timescales and durations, and versioned `elst` evidence without reading media payloads.
- `change_impact.py` hashes changed files and expands directly/transitively invalidated checkpoints.
- `quality_guard.py` rejects reductions in resolution, FPS, bitrate, bit depth, codec profile, colour,
  RAW fidelity, metadata, audio, detail profiles, conversion quality, features, or security.
- `inventory.py` inventories tests, scripts, device requirements, and existing evidence.
- `reporting.py` emits the required per-checkpoint JSON/CSV report without inventing measurements for
  legacy ledger rows.

## Core commands

Run from the repository root:

```powershell
python -m tools.neo_qa_hypergrid compile-graph `
  --ledger artifacts/device-production-qa-2026-08-15/DEVICE_PRODUCTION_CHECKPOINT_LEDGER.csv `
  --output artifacts/neo-qa-hypergrid/current

python -m tools.neo_qa_hypergrid inventory `
  --workspace . `
  --output artifacts/neo-qa-hypergrid/current/repository_inventory.json

python -m tools.neo_qa_hypergrid digital-twin `
  --seed 20260819 `
  --output artifacts/neo-qa-hypergrid/current/digital_twin.json

python -m tools.neo_qa_hypergrid failure-injection `
  --seed 20260819 `
  --output artifacts/neo-qa-hypergrid/current/failure_injection.json

python -m tools.neo_qa_hypergrid self-test
```

Use `analyze-recording` with both ffprobe stream JSON and `-show_frames` JSON. Requested
`MediaFormat` values are not wire evidence. Use `device-preflight` before every functional or
performance batch; a performance rejection is BLOCKED/PAUSED evidence, not a test failure.

Inspect a retained conversion MOV with the CP-178 timing oracle:

```powershell
python -m tools.neo_qa_hypergrid.mov_timing_oracle converted.mov `
  --output artifacts/device-qa/current/CP-178-mov-timing.json
```

The parser proves container timing structure only. Pair its report with the matching physical-device
conversion record, APK/device identity, source/output hashes, raw instrumentation XML, logcat, and
decoded-frame PTS evidence before changing CP-178 to PASS.

## Device safety

Only one controller may hold `ExclusiveAdbLease`, and it must keep ownership for the complete batch.
Every ADB command includes `-s <serial>`. Camera flows never run concurrently. UI actions are derived
from a saved UI tree; screenshots are visual evidence, not coordinate sources. Performance recording
waits for strict thermal admission, while moderate conditions may admit functional-only tests.
Production controllers must construct `ExclusiveAdbLease` with its canonical global lock (pass
`None` as the lock path); arbitrary lock paths are accepted only by the explicit unit-test factory.

Inspect large, finalized recordings without another full host copy using
`python -m tools.neo_qa_hypergrid.adb_readonly_media_proxy --adb <SDK-adb-path> --serial <serial>
--source <absolute-CamX-media-file> --expected-size <bytes>`. Run it only after camera capture stops.
The helper holds the canonical exclusive ADB lease, exposes just that file on an unguessable loopback
URL, serializes every ADB command, rejects short/changed-source reads, and hashes the source before
and after inspection. Feed the emitted `url` to ffprobe/ffmpeg, then POST to its `finish_url` to close
the helper and complete the hash audit. It never creates, rewrites, pads, or deletes Android media.
HTTP readers may seek concurrently, but physical reads remain serialized. Keep failed helper logs;
only successful independent media-tool output plus the unchanged-source audit supports a result.
Decoded review JPEGs are not calibrated quality evidence, and a transport success is not a
production-checkpoint PASS. The monitor deadline helper likewise bounds host sleep only; actual
recorded duration remains subject to the unchanged checkpoint gate.

## Evidence and release

Failed and superseded evidence is retained. Every source change invalidates mapped checkpoints,
focused tests run first, shared-component integration tests follow, and the complete mandatory final
regression remains required. Production signing, hardware limits, missing multi-device labs, or
unavailable long-duration conditions stay BLOCKED rather than being converted to PASS.

Sealed evidence records contain a canonical record-body hash and an exact artifact manifest. Reports
reject missing, extra, escaped, changed, or hash-mismatched artifacts; a host/simulation record cannot
promote a device checkpoint to PASS. Legacy ledger rows remain labeled as legacy until migrated.
