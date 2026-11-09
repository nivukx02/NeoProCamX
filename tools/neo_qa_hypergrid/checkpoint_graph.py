"""Canonical-ledger compiler for the NEO-QA HyperGrid dependency graph."""

from __future__ import annotations

import csv
import hashlib
import io
import json
import os
from dataclasses import dataclass
from pathlib import Path
import re
import tempfile
from typing import Iterable

from .schema import (
    CheckpointGraph,
    CheckpointNode,
    CheckpointSubchecks,
    GraphEdge,
    LedgerRecord,
    SCHEMA_VERSION,
    SUBCHECK_PHASES,
    Subcheck,
    VALID_EXECUTION_TARGETS,
    VALID_STATUSES,
)


LEDGER_COLUMNS = (
    "id",
    "category",
    "checkpoint",
    "status",
    "evidence",
    "root_cause_or_note",
)
EXPECTED_CHECKPOINT_IDS = tuple(f"CP-{value:03d}" for value in range(1, 221))
CHECKPOINT_ID_PATTERN = re.compile(r"^CP-(\d{3})$")


class LedgerValidationError(ValueError):
    """Raised when the canonical CSV is incomplete, malformed, or ambiguous."""


class GraphValidationError(ValueError):
    """Raised when compiled graph invariants do not hold."""


class GraphCycleError(GraphValidationError):
    """Raised when checkpoint prerequisites contain a dependency cycle."""


@dataclass(frozen=True, slots=True)
class DependencyRangeRule:
    """Auditable default dependency rule for an inclusive ordinal range."""

    first: int
    last: int
    prerequisites: tuple[int, ...]

    def matches(self, ordinal: int) -> bool:
        return self.first <= ordinal <= self.last


# Defaults keep each major work area connected to its admission checkpoint.
# Overrides below describe the finer-grained ordering within each area.  Every
# prerequisite is intentionally lower-numbered, making the rules deterministic
# and straightforward to review.
DEPENDENCY_RANGE_RULES = (
    DependencyRangeRule(2, 8, (1,)),
    DependencyRangeRule(9, 20, (4,)),
    DependencyRangeRule(21, 21, (20,)),
    DependencyRangeRule(22, 40, (21,)),
    DependencyRangeRule(41, 44, (20, 21)),
    DependencyRangeRule(45, 60, (43,)),
    DependencyRangeRule(61, 80, (43,)),
    DependencyRangeRule(81, 110, (57, 62)),
    DependencyRangeRule(111, 140, (81, 98)),
    DependencyRangeRule(141, 170, (81,)),
    DependencyRangeRule(171, 200, (153,)),
    DependencyRangeRule(201, 216, (151,)),
)


DEPENDENCY_OVERRIDES: dict[int, tuple[int, ...]] = {
    1: (),
    2: (1,),
    3: (1,),
    4: (1,),
    5: (1,),
    6: (1,),
    7: (1,),
    8: (1, 7),
    9: (7, 8),
    10: (4, 5),
    11: (4, 5),
    12: (11,),
    13: (10,),
    14: (5, 6),
    15: (5, 6),
    16: (14, 15),
    17: (11, 15),
    18: (11, 12),
    19: (11, 12, 16, 17, 18),
    20: (10, 13),
    21: (20,),
    22: (21,),
    23: (22,),
    24: (22,),
    25: (22,),
    26: (21,),
    27: (22,),
    28: (27,),
    29: (22,),
    30: (22,),
    31: (30,),
    32: (22,),
    33: (32,),
    34: (21,),
    35: (34,),
    36: (35,),
    37: (35,),
    38: (21,),
    39: (22,),
    40: (22,),
    41: (20, 21),
    42: (41,),
    43: (20, 21),
    44: (43,),
    45: (43, 44),
    46: (45,),
    47: (43,),
    48: (45,),
    49: (48,),
    50: (49,),
    51: (50,),
    52: (50,),
    53: (50,),
    54: (50,),
    55: (50,),
    56: (43,),
    57: (56,),
    58: (57,),
    59: (57,),
    60: (59,),
    61: (20, 43),
    62: (56, 61),
    63: (43, 61),
    64: (63,),
    65: (64,),
    66: (43, 61),
    67: (43, 61),
    68: (63, 66, 67),
    69: (68,),
    70: (43,),
    71: (62,),
    72: (71,),
    73: (63,),
    74: (42,),
    75: (74,),
    76: (50, 61),
    77: (50, 61),
    78: (50, 61),
    79: (50, 61),
    80: (50, 61),
    81: (57, 62),
    82: (57, 62),
    83: (57, 62),
    84: (50, 57),
    85: (81, 82, 83),
    86: (85,),
    87: (57, 81),
    88: (81,),
    89: (71, 85),
    90: (85,),
    91: (85,),
    92: (85,),
    93: (85,),
    94: (81,),
    95: (81,),
    96: (50,),
    97: (96,),
    98: (94, 97),
    99: (98,),
    100: (98,),
    101: (98,),
    102: (98,),
    103: (100, 101, 102),
    104: (82,),
    105: (104,),
    106: (104,),
    107: (98,),
    108: (107,),
    109: (98,),
    110: (98,),
    111: (81, 98),
    112: (111,),
    113: (111,),
    114: (111,),
    115: (81, 98),
    116: (115,),
    117: (116,),
    118: (115,),
    119: (112, 116, 117, 118),
    120: (81,),
    121: (120,),
    122: (120,),
    123: (120,),
    124: (119,),
    125: (119,),
    126: (125,),
    127: (117,),
    128: (81,),
    129: (81,),
    130: (81,),
    131: (130,),
    132: (130, 131),
    133: (132,),
    134: (81,),
    135: (82,),
    136: (83,),
    137: (83,),
    138: (81, 107),
    139: (82, 107),
    140: (83, 107),
    141: (81,),
    142: (82,),
    143: (83,),
    144: (141, 142, 143),
    145: (141,),
    146: (141,),
    147: (141,),
    148: (107, 141),
    149: (141,),
    150: (141,),
    151: (81,),
    152: (82,),
    153: (83,),
    154: (108, 153),
    155: (108, 153),
    156: (37, 151),
    157: (156,),
    158: (33, 37, 151),
    159: (33, 37, 152),
    160: (33, 35, 153),
    161: (33, 37, 39, 154, 156, 157),
    162: (66, 151),
    163: (151, 152, 153, 154, 155),
    164: (37, 109, 151),
    165: (151, 152, 153, 154, 155),
    166: (151,),
    167: (141, 151),
    168: (33, 153),
    169: (30, 31, 33, 35, 153),
    170: (33, 151),
    171: (153,),
    172: (153,),
    173: (108, 153),
    174: (108, 153),
    175: (151,),
    176: (37, 173),
    177: (171, 172, 173, 174, 175),
    178: (177,),
    179: (173, 174),
    180: (171, 172, 173, 174, 175),
    181: (171, 172, 173, 174, 175),
    182: (171, 172, 173, 174, 175),
    183: (156, 157, 171, 172, 173, 174, 175),
    184: (171,),
    185: (30, 184),
    186: (171,),
    187: (186,),
    188: (153,),
    189: (171,),
    190: (188, 189),
    191: (188,),
    192: (191,),
    193: (188,),
    194: (188,),
    195: (188,),
    196: (188,),
    197: (153,),
    198: (188,),
    199: (171,),
    200: (199,),
    201: (57,),
    202: (201,),
    203: (201,),
    204: (151,),
    205: (30, 151),
    206: (33, 151),
    207: (85, 151, 190),
    208: (207,),
    209: (85, 190),
    210: (45, 85, 151),
    211: (210,),
    212: (170, 193),
    213: (85, 151),
    214: (213,),
    215: (44, 61),
    216: (44, 215),
    217: (20, 61),
    218: (2, 168, 169, 177, 178, 217),
    219: (170, 193, 214, 216, 218),
    220: tuple(range(1, 220)),
}


def _checkpoint_id(ordinal: int) -> str:
    return f"CP-{ordinal:03d}"


def _portable_path(path: Path) -> str:
    try:
        value = path.resolve().relative_to(Path.cwd().resolve())
    except ValueError:
        value = path.resolve()
    return value.as_posix()


def read_canonical_ledger(ledger_path: str | os.PathLike[str]) -> tuple[LedgerRecord, ...]:
    """Read and strictly validate CP-001 through CP-220 from the CSV ledger."""

    path = Path(ledger_path)
    if not path.is_file():
        raise LedgerValidationError(f"Canonical ledger does not exist: {path}")
    try:
        handle = path.open("r", encoding="utf-8-sig", newline="")
    except OSError as error:
        raise LedgerValidationError(f"Cannot read canonical ledger: {path}") from error

    records: list[LedgerRecord] = []
    with handle:
        reader = csv.DictReader(handle)
        actual_columns = tuple(reader.fieldnames or ())
        if actual_columns != LEDGER_COLUMNS:
            raise LedgerValidationError(
                f"Ledger columns must be {LEDGER_COLUMNS}; got {actual_columns}"
            )
        for line_number, row in enumerate(reader, start=2):
            if None in row:
                raise LedgerValidationError(
                    f"Line {line_number} contains unexpected extra columns"
                )
            if any(value is None for value in row.values()):
                raise LedgerValidationError(
                    f"Line {line_number} contains a missing column value"
                )
            checkpoint_id = row["id"]
            match = CHECKPOINT_ID_PATTERN.fullmatch(checkpoint_id)
            if match is None:
                raise LedgerValidationError(
                    f"Line {line_number} has malformed checkpoint ID: {checkpoint_id!r}"
                )
            ordinal = int(match.group(1))
            if not row["category"].strip():
                raise LedgerValidationError(
                    f"Line {line_number} has an empty category"
                )
            if not row["checkpoint"].strip():
                raise LedgerValidationError(
                    f"Line {line_number} has an empty checkpoint description"
                )
            if row["status"] not in VALID_STATUSES:
                raise LedgerValidationError(
                    f"Line {line_number} has unsupported status: {row['status']!r}"
                )
            records.append(
                LedgerRecord(
                    checkpoint_id=checkpoint_id,
                    ordinal=ordinal,
                    category=row["category"],
                    checkpoint=row["checkpoint"],
                    status=row["status"],
                    evidence=row["evidence"],
                    root_cause_or_note=row["root_cause_or_note"],
                )
            )

    actual_ids = [record.checkpoint_id for record in records]
    duplicate_ids = sorted(
        checkpoint_id
        for checkpoint_id in set(actual_ids)
        if actual_ids.count(checkpoint_id) > 1
    )
    if duplicate_ids:
        raise LedgerValidationError(
            "Duplicate checkpoint IDs: " + ", ".join(duplicate_ids)
        )
    if len(records) != len(EXPECTED_CHECKPOINT_IDS):
        raise LedgerValidationError(
            f"Ledger must contain exactly 220 rows; got {len(records)}"
        )
    if tuple(actual_ids) != EXPECTED_CHECKPOINT_IDS:
        missing = sorted(set(EXPECTED_CHECKPOINT_IDS) - set(actual_ids))
        unexpected = sorted(set(actual_ids) - set(EXPECTED_CHECKPOINT_IDS))
        raise LedgerValidationError(
            "Ledger must be ordered and gap-free from CP-001 through CP-220; "
            f"missing={missing}, unexpected={unexpected}"
        )
    return tuple(records)


def _subcategory_for(ordinal: int) -> str:
    ranges = (
        (1, 8, "Repository and toolchains"),
        (9, 18, "Automated host gates"),
        (19, 20, "Release ownership and test packaging"),
        (21, 26, "Connection and device identity"),
        (27, 33, "Graphics, memory, storage and power"),
        (34, 40, "Camera, codec, display and sensor capabilities"),
        (41, 44, "Install, upgrade and package identity"),
        (45, 50, "Launch timing and continuity"),
        (51, 55, "Home and settings presentation"),
        (56, 60, "Live-preview startup health"),
        (61, 75, "Permissions and state recovery"),
        (76, 80, "Accessibility and adaptive layout"),
        (81, 95, "Modes, sessions and state persistence"),
        (96, 110, "Settings, detail and colour policy"),
        (111, 129, "Exposure, colour, focus and stabilization controls"),
        (130, 140, "Preview geometry, cadence and colour"),
        (141, 150, "Still capture and publication"),
        (151, 170, "Recording transport, fidelity and endurance"),
        (171, 187, "Conversion fidelity and transactions"),
        (188, 200, "Playback correctness and throughput"),
        (201, 206, "Lifecycle and failure recovery"),
        (207, 214, "Performance, power and stress"),
        (215, 216, "Security and integrity"),
        (217, 220, "Aggregate verification and release verdict"),
    )
    for first, last, value in ranges:
        if first <= ordinal <= last:
            return value
    raise GraphValidationError(f"No subcategory rule for CP-{ordinal:03d}")


def _execution_target_for(ordinal: int) -> str:
    if ordinal == 19:
        return "external"
    if ordinal <= 20:
        return "host"
    hybrid_ranges = (
        range(158, 166),
        range(169, 170),
        range(171, 201),
        range(207, 215),
        range(216, 217),
        range(220, 221),
    )
    if any(ordinal in values for values in hybrid_ranges):
        return "host+device"
    return "device"


def _build_variants_for(ordinal: int) -> tuple[str, ...]:
    exact: dict[int, tuple[str, ...]] = {
        1: ("not-applicable",),
        2: ("not-applicable",),
        3: ("not-applicable",),
        4: ("not-applicable",),
        5: ("not-applicable",),
        6: ("not-applicable",),
        7: ("not-applicable",),
        8: ("not-applicable",),
        9: ("host-python",),
        10: ("debugUnitTest",),
        11: ("release",),
        12: ("release",),
        13: ("debug",),
        14: ("debug-arm64-v8a",),
        15: ("release-arm64-v8a",),
        16: ("debug-arm64-v8a", "release-arm64-v8a"),
        17: ("debug-apk",),
        18: ("release-minified",),
        19: ("release-aab-production-signed",),
        20: ("debugAndroidTest",),
        216: ("debug-apk", "debugAndroidTest", "release-minified"),
        220: ("debug-apk", "debugAndroidTest", "release-aab-production-signed"),
    }
    if ordinal in exact:
        return exact[ordinal]
    if 21 <= ordinal <= 44:
        return ("debug-apk", "debugAndroidTest")
    return ("debug-apk", "debugAndroidTest")


def _source_areas_for(ordinal: int, checkpoint: str) -> tuple[str, ...]:
    if ordinal <= 3:
        areas = ["repository metadata", "canonical checkpoint ledger"]
    elif ordinal <= 9:
        areas = ["build toolchains", "tools/**/*.py", "app/src/main/assets/**"]
    elif ordinal <= 20:
        areas = [
            "build.gradle",
            "app/build.gradle",
            "gradle/**",
            "app/src/test/**",
            "app/src/main/cpp/**",
        ]
    elif ordinal <= 44:
        areas = [
            "candidate APK and package metadata",
            "app/src/androidTest/**",
            "Android device capabilities",
        ]
    elif ordinal <= 110:
        areas = [
            "app/src/main/java/com/neoproc/neoproccamx/ui/**",
            "app/src/main/java/com/neoproc/neoproccamx/camera/**",
            "app/src/androidTest/**",
        ]
    elif ordinal <= 140:
        areas = [
            "app/src/main/java/com/neoproc/neoproccamx/camera/**",
            "app/src/main/java/com/neoproc/neoproccamx/pipeline/**",
            "app/src/main/java/com/neoproc/neoproccamx/rendering/**",
            "app/src/androidTest/**",
        ]
    elif ordinal <= 170:
        areas = [
            "app/src/main/java/com/neoproc/neoproccamx/capture/**",
            "app/src/main/java/com/neoproc/neoproccamx/recording/**",
            "app/src/main/java/com/neoproc/neoproccamx/pipeline/**",
            "app/src/androidTest/**",
        ]
    elif ordinal <= 200:
        areas = [
            "app/src/main/java/com/neoproc/neoproccamx/recording/**",
            "app/src/main/java/com/neoproc/neoproccamx/tessera/**",
            "app/src/main/java/com/neoproc/neoproccamx/ui/*Playback*",
            "app/src/androidTest/**",
        ]
    else:
        areas = [
            "app/src/main/java/com/neoproc/neoproccamx/**",
            "app/src/androidTest/**",
            "release and runtime evidence",
        ]

    lowered = checkpoint.lower()
    additions = (
        (("security", "keystore", "tamper", "integrity", "signing"),
         "app/src/main/java/com/neoproc/neoproccamx/security/**"),
        (("color", "colour", "white-balance", "hdr", "xdr", "ndr"),
         "app/src/main/java/com/neoproc/neoproccamx/color/**"),
        (("shader", "gpu", "vulkan", "p010"),
         "app/src/main/assets/shaders/** and app/src/main/cpp/shaders/**"),
        (("playback", "player", "decoder"),
         "app/src/main/java/com/neoproc/neoproccamx/ui/*Playback*"),
    )
    for keywords, area in additions:
        if any(keyword in lowered for keyword in keywords):
            areas.append(area)
    return tuple(dict.fromkeys(areas))


def _prerequisites_for(ordinal: int) -> tuple[str, ...]:
    if ordinal in DEPENDENCY_OVERRIDES:
        prerequisites = DEPENDENCY_OVERRIDES[ordinal]
    else:
        matches = [
            rule.prerequisites
            for rule in DEPENDENCY_RANGE_RULES
            if rule.matches(ordinal)
        ]
        if len(matches) != 1:
            raise GraphValidationError(
                f"CP-{ordinal:03d} must match exactly one dependency range rule"
            )
        prerequisites = matches[0]
    if len(prerequisites) != len(set(prerequisites)):
        raise GraphValidationError(
            f"CP-{ordinal:03d} has duplicate prerequisites"
        )
    return tuple(_checkpoint_id(value) for value in sorted(prerequisites))


def _test_procedure_for(record: LedgerRecord, execution_target: str) -> str:
    if execution_target == "host":
        prefix = "On the frozen source candidate, execute the deterministic host procedure"
    elif execution_target == "external":
        prefix = "Using owner-supplied production credentials and legal inputs, execute the release procedure"
    elif execution_target == "host+device":
        prefix = "With exclusive ADB ownership, execute the physical-device flow and analyze its sealed artifacts on the host"
    else:
        prefix = "With exclusive ADB ownership and admitted thermal/storage state, execute the physical-device flow"
    return f'{prefix} for "{record.checkpoint}"; capture the declared oracle evidence and restore test state.'


def _expected_result_for(record: LedgerRecord) -> str:
    return (
        f'{record.checkpoint} satisfies its declared behavior on the identified '
        "candidate without an unapproved reduction in resolution, bitrate, bit "
        "depth, colour metadata, RAW fidelity, features, or security."
    )


def _thresholds_for(record: LedgerRecord) -> tuple[str, ...]:
    ordinal = record.ordinal
    values = [
        "All mandatory assertions pass and all required evidence is present, readable, and attributable to the same candidate.",
        "Any missing, ambiguous, stale, or contradictory evidence prevents PASS.",
    ]
    if ordinal in (12, 13):
        values.append("Lint exits successfully with zero genuine errors or warnings in the governed scope.")
    if ordinal in (45, 46):
        values.append("Every measured launch completes without ANR/crash and remains within the predeclared startup budget.")
    if ordinal in (55, 132):
        values.append("No displacement, stale transform, stretch, wavy sampling, or orientation/crop mismatch is observed.")
    if ordinal == 60:
        values.append("Crash and ANR buffers contain no event attributable to the candidate run.")
    if ordinal in (93, 94):
        values.append("Requested state is applied and visibly effective within 1,000 ms.")
    if ordinal in (124, 125):
        values.append("No full-frame whiteout or uncontrolled highlight clipping occurs during the declared motion/exposure sweep.")
    if ordinal in (134, 135, 136, 137):
        values.append("Final displayed-frame cadence sustains the requested 25 FPS target; sensor request cadence alone is not sufficient evidence.")
    if ordinal == 156:
        values.append("Encoded video is HEVC Main10 ten-bit; any silent eight-bit fallback is FAIL.")
    if ordinal == 157:
        values.append("P010 ten-bit samples and packet metadata remain internally consistent end to end.")
    if ordinal in (158, 159, 160, 161):
        values.append("ffprobe frame/PTS evidence sustains the requested cadence with no unexplained missing or duplicate frames.")
    if ordinal == 164:
        values.append("Measured bitrate satisfies the selected policy without exceeding device-admitted limits or falling below the quality floor.")
    if ordinal == 169:
        values.append("Sustained payload throughput is at least 589,824,000 bytes/s with no RAW ownership ceiling exhaustion.")
    if ordinal == 170:
        values.append("Recording remains valid for at least 30 continuous minutes and finalizes cleanly.")
    if ordinal == 176:
        values.append("HEVC export requires the strict 200,000,000 bit/s floor; an honest hardware BLOCKED result is not PASS.")
    if ordinal == 177:
        values.append("Finalized output frame count exactly equals admitted input frame count.")
    if ordinal == 178:
        values.append("Output PTS values are strictly monotonic after timebase conversion and final duration differs by no more than one output tick.")
    if ordinal == 180:
        values.append("CPU/GPU parity delta: mean <= 2, maximum <= 4, and p99.9 <= 4 code values.")
    if ordinal == 183:
        values.append("Converted output remains ten-bit; any eight-bit fallback is FAIL.")
    if ordinal == 190:
        values.append("Presented playback cadence contains no unexplained decoder drop, scheduler skip, or repeated frame.")
    if ordinal == 215:
        values.append("Tampered vault data is rejected and every concurrent transaction preserves isolation and authenticity.")
    if ordinal == 220:
        values.append("Production readiness is GO only when every mandatory prerequisite has valid evidence or formally approved external ownership.")
    return tuple(values)


def _required_evidence_for(record: LedgerRecord, execution_target: str) -> tuple[str, ...]:
    evidence = [
        "frozen source-candidate SHA-256 manifest",
        "checkpoint procedure transcript with exit/result state",
        "machine-readable oracle result",
    ]
    if execution_target == "external":
        evidence.extend(("production signing identity", "owner/legal approval record"))
    if "device" in execution_target:
        evidence.extend(
            (
                "candidate and installed APK SHA-256 identity",
                "device model, build, ABI and API identity",
                "pre/during/post thermal and storage admission evidence",
                "checkpoint-scoped logcat plus crash and ANR buffers",
            )
        )
    ordinal = record.ordinal
    if 34 <= ordinal <= 40:
        evidence.append("raw capability inventory or instrumentation probe output")
    if 45 <= ordinal <= 60 or 76 <= ordinal <= 110:
        evidence.extend(("UI hierarchy", "timestamped screenshot or screen recording"))
    if 141 <= ordinal <= 170:
        evidence.extend(("MediaStore before/after inventory", "captured media hash and metadata"))
    if 156 <= ordinal <= 165 or 171 <= ordinal <= 200:
        evidence.extend(
            (
                "ffprobe stream and frame analysis",
                "codec/profile/pixel-format/bit-depth/colour metadata",
                "frame count, PTS intervals, keyframe structure and measured bitrate",
            )
        )
    if 207 <= ordinal <= 214:
        evidence.extend(("gfxinfo/Perfetto/Simpleperf or memory trace", "power and thermal timeline"))
    return tuple(dict.fromkeys(evidence))


def _subchecks_for(
    record: LedgerRecord,
    execution_target: str,
    procedure: str,
    expected_result: str,
    thresholds: tuple[str, ...],
    required_evidence: tuple[str, ...],
) -> CheckpointSubchecks:
    checkpoint_id = record.checkpoint_id
    device_admission = (
        "Verify sole ADB ownership, device/build identity, installed candidate hash, "
        "battery, storage, thermal state, CameraService ownership, and background load."
    )
    host_admission = (
        "Verify frozen source hash, required tool versions, isolated output/cache paths, "
        "and absence of conflicting writers."
    )
    if execution_target == "external":
        admission_procedure = (
            "Verify owner-supplied signing credentials, store configuration, legal "
            "approval, and candidate identity before release work."
        )
    elif "device" in execution_target:
        admission_procedure = device_admission
    else:
        admission_procedure = host_admission

    common_threshold = thresholds[0]
    cleanup_procedure = (
        "Stop checkpoint workloads, release images/codecs/surfaces/files exactly once, "
        "roll back pending MediaStore transactions, restore changed device/test state, "
        "and retain all evidence."
        if "device" in execution_target
        else "Close processes and temporary resources, restore test-owned state, and retain all evidence."
    )
    values = {
        "admission": Subcheck(
            subcheck_id=f"{checkpoint_id}:admission",
            phase="admission",
            execution_target=execution_target,
            procedure=admission_procedure,
            expected_result="All prerequisites are identified, attributable, conflict-free, and valid for execution.",
            pass_fail_threshold="Do not execute or claim PASS when candidate identity, ownership, required capacity, or admission data is invalid.",
            required_evidence=("admission manifest", "candidate identity hash"),
        ),
        "execution": Subcheck(
            subcheck_id=f"{checkpoint_id}:execution",
            phase="execution",
            execution_target=execution_target,
            procedure=procedure,
            expected_result=expected_result,
            pass_fail_threshold=common_threshold,
            required_evidence=("timestamped execution transcript",),
        ),
        "oracle": Subcheck(
            subcheck_id=f"{checkpoint_id}:oracle",
            phase="oracle",
            execution_target=("host" if execution_target == "host+device" else execution_target),
            procedure="Evaluate every declared threshold against raw evidence; classify PASS, FAIL, BLOCKED, or PENDING without inference from a build or simulation alone.",
            expected_result="The status is reproducible from the evidence and every measured value is reported with units.",
            pass_fail_threshold="PASS requires every threshold; contradictory or incomplete evidence is not PASS.",
            required_evidence=("machine-readable oracle result", "measured values and threshold comparison"),
        ),
        "cleanup": Subcheck(
            subcheck_id=f"{checkpoint_id}:cleanup",
            phase="cleanup",
            execution_target=execution_target,
            procedure=cleanup_procedure,
            expected_result="No test-owned resource leak, corrupt publication, stale camera owner, or untracked state remains.",
            pass_fail_threshold="Cleanup is exact-once and superseded/failed evidence remains traceable.",
            required_evidence=("cleanup result", "post-test resource/state inventory"),
        ),
        "evidence": Subcheck(
            subcheck_id=f"{checkpoint_id}:evidence",
            phase="evidence",
            execution_target="host",
            procedure="Hash, index, and atomically seal all required raw and derived artifacts under the checkpoint and candidate identity.",
            expected_result="Every cited artifact exists, is non-empty when applicable, hashes correctly, and maps to this checkpoint run.",
            pass_fail_threshold="Evidence must be reproducible and attributable; stale, missing, or hash-mismatched evidence invalidates PASS.",
            required_evidence=required_evidence,
        ),
    }
    return CheckpointSubchecks(**values)


def _blockers_for(record: LedgerRecord) -> tuple[str, ...]:
    if record.status == "BLOCKED" and record.root_cause_or_note:
        return (record.root_cause_or_note,)
    return ()


def _ledger_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def compile_checkpoint_graph(
    ledger_path: str | os.PathLike[str],
) -> CheckpointGraph:
    """Compile the validated ledger into the complete dependency graph."""

    path = Path(ledger_path)
    records = read_canonical_ledger(path)
    prerequisites = {
        record.checkpoint_id: _prerequisites_for(record.ordinal)
        for record in records
    }
    dependents: dict[str, list[str]] = {
        record.checkpoint_id: [] for record in records
    }
    for checkpoint_id, required in prerequisites.items():
        for prerequisite in required:
            dependents[prerequisite].append(checkpoint_id)

    nodes: list[CheckpointNode] = []
    for record in records:
        execution_target = _execution_target_for(record.ordinal)
        procedure = _test_procedure_for(record, execution_target)
        expected_result = _expected_result_for(record)
        thresholds = _thresholds_for(record)
        required_evidence = _required_evidence_for(record, execution_target)
        subchecks = _subchecks_for(
            record,
            execution_target,
            procedure,
            expected_result,
            thresholds,
            required_evidence,
        )
        nodes.append(
            CheckpointNode(
                checkpoint_id=record.checkpoint_id,
                ordinal=record.ordinal,
                category=record.category,
                subcategory=_subcategory_for(record.ordinal),
                checkpoint=record.checkpoint,
                status=record.status,
                evidence=record.evidence,
                root_cause_or_note=record.root_cause_or_note,
                source_areas=_source_areas_for(record.ordinal, record.checkpoint),
                build_variants=_build_variants_for(record.ordinal),
                execution_target=execution_target,
                prerequisites=prerequisites[record.checkpoint_id],
                test_procedure=procedure,
                expected_result=expected_result,
                pass_fail_thresholds=thresholds,
                required_evidence=required_evidence,
                regression_links=tuple(
                    sorted(
                        dependents[record.checkpoint_id],
                        key=lambda value: int(value[-3:]),
                    )
                ),
                blockers=_blockers_for(record),
                subchecks=subchecks,
            )
        )

    edges = tuple(
        GraphEdge(prerequisite=prerequisite, checkpoint_id=node.checkpoint_id)
        for node in nodes
        for prerequisite in node.prerequisites
    )
    graph = CheckpointGraph(
        schema_version=SCHEMA_VERSION,
        ledger_path=_portable_path(path),
        ledger_sha256=_ledger_sha256(path),
        nodes=tuple(nodes),
        edges=edges,
    )
    validate_graph(graph)
    return graph


def _validate_acyclic(graph: CheckpointGraph) -> None:
    node_ids = {node.checkpoint_id for node in graph.nodes}
    incoming = {
        node.checkpoint_id: set(node.prerequisites) for node in graph.nodes
    }
    unknown = {
        checkpoint_id: sorted(required - node_ids)
        for checkpoint_id, required in incoming.items()
        if required - node_ids
    }
    if unknown:
        raise GraphValidationError(f"Unknown prerequisite references: {unknown}")
    ready = sorted(
        (checkpoint_id for checkpoint_id, required in incoming.items() if not required),
        key=lambda value: int(value[-3:]),
    )
    visited: set[str] = set()
    while ready:
        checkpoint_id = ready.pop(0)
        if checkpoint_id in visited:
            continue
        visited.add(checkpoint_id)
        for dependent, required in incoming.items():
            if checkpoint_id in required:
                required.remove(checkpoint_id)
                if not required and dependent not in visited:
                    ready.append(dependent)
        ready.sort(key=lambda value: int(value[-3:]))
    if len(visited) != len(graph.nodes):
        unresolved = sorted(node_ids - visited, key=lambda value: int(value[-3:]))
        raise GraphCycleError(
            "Checkpoint dependency cycle detected: " + ", ".join(unresolved)
        )


def validate_graph(graph: CheckpointGraph) -> None:
    """Validate completeness, phase contracts, edges, regressions and scheduling."""

    if graph.schema_version != SCHEMA_VERSION:
        raise GraphValidationError(
            f"Unsupported graph schema: {graph.schema_version}"
        )
    actual_ids = tuple(node.checkpoint_id for node in graph.nodes)
    if actual_ids != EXPECTED_CHECKPOINT_IDS:
        raise GraphValidationError(
            "Graph must contain ordered CP-001 through CP-220 exactly once"
        )
    if not re.fullmatch(r"[0-9a-f]{64}", graph.ledger_sha256):
        raise GraphValidationError("Ledger SHA-256 is malformed")
    _validate_acyclic(graph)

    ordinal_by_id = {node.checkpoint_id: node.ordinal for node in graph.nodes}
    for expected_ordinal, node in enumerate(graph.nodes, start=1):
        if node.ordinal != expected_ordinal:
            raise GraphValidationError(
                f"{node.checkpoint_id} ordinal mismatch: {node.ordinal}"
            )
        if node.status not in VALID_STATUSES:
            raise GraphValidationError(
                f"{node.checkpoint_id} has invalid status {node.status}"
            )
        if node.execution_target not in VALID_EXECUTION_TARGETS:
            raise GraphValidationError(
                f"{node.checkpoint_id} has invalid execution target"
            )
        required_text = (
            node.category,
            node.subcategory,
            node.checkpoint,
            node.test_procedure,
            node.expected_result,
        )
        if any(not value.strip() for value in required_text):
            raise GraphValidationError(
                f"{node.checkpoint_id} has an empty required text field"
            )
        required_collections = (
            node.source_areas,
            node.build_variants,
            node.pass_fail_thresholds,
            node.required_evidence,
        )
        if any(not values for values in required_collections):
            raise GraphValidationError(
                f"{node.checkpoint_id} has an empty required collection"
            )
        if any(
            not isinstance(value, str) or not value.strip()
            for values in required_collections
            for value in values
        ):
            raise GraphValidationError(
                f"{node.checkpoint_id} has a blank entry in a required collection"
            )
        if len(node.prerequisites) != len(set(node.prerequisites)):
            raise GraphValidationError(
                f"{node.checkpoint_id} has duplicate prerequisite references"
            )
        for prerequisite in node.prerequisites:
            if ordinal_by_id[prerequisite] >= node.ordinal:
                raise GraphValidationError(
                    f"{node.checkpoint_id} prerequisite {prerequisite} must precede it"
                )
        subchecks = node.subchecks.ordered()
        if tuple(subcheck.phase for subcheck in subchecks) != SUBCHECK_PHASES:
            raise GraphValidationError(
                f"{node.checkpoint_id} does not have all five ordered subchecks"
            )
        for subcheck in subchecks:
            if subcheck.subcheck_id != f"{node.checkpoint_id}:{subcheck.phase}":
                raise GraphValidationError(
                    f"{node.checkpoint_id} has malformed subcheck ID"
                )
            if not subcheck.required_evidence:
                raise GraphValidationError(
                    f"{subcheck.subcheck_id} has no evidence contract"
                )
            if subcheck.execution_target not in VALID_EXECUTION_TARGETS:
                raise GraphValidationError(
                    f"{subcheck.subcheck_id} has an invalid execution target"
                )
            if any(
                not value.strip()
                for value in (
                    subcheck.procedure,
                    subcheck.expected_result,
                    subcheck.pass_fail_threshold,
                )
            ):
                raise GraphValidationError(
                    f"{subcheck.subcheck_id} has an empty required text field"
                )
            if any(not value.strip() for value in subcheck.required_evidence):
                raise GraphValidationError(
                    f"{subcheck.subcheck_id} has blank required evidence"
                )

    expected_edges = {
        (prerequisite, node.checkpoint_id)
        for node in graph.nodes
        for prerequisite in node.prerequisites
    }
    actual_edges = {
        (edge.prerequisite, edge.checkpoint_id) for edge in graph.edges
    }
    if len(actual_edges) != len(graph.edges) or actual_edges != expected_edges:
        raise GraphValidationError("Graph edges do not exactly match prerequisites")
    reverse = {checkpoint_id: [] for checkpoint_id in actual_ids}
    for prerequisite, checkpoint_id in expected_edges:
        reverse[prerequisite].append(checkpoint_id)
    for node in graph.nodes:
        expected_links = tuple(
            sorted(reverse[node.checkpoint_id], key=lambda value: int(value[-3:]))
        )
        if node.regression_links != expected_links:
            raise GraphValidationError(
                f"{node.checkpoint_id} regression links do not match dependents"
            )

    # This imports lazily so the schema and scheduler remain independently usable.
    from .scheduling import build_parallel_schedule

    build_parallel_schedule(graph)


def _atomic_write_text(path: Path, contents: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=path.parent,
    )
    temporary_path = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(contents)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_path, path)
    finally:
        if temporary_path.exists():
            temporary_path.unlink()


def _json_cell(values: Iterable[str]) -> str:
    return json.dumps(list(values), ensure_ascii=False, separators=(",", ":"))


def write_graph_artifacts(
    graph: CheckpointGraph,
    output_directory: str | os.PathLike[str],
) -> dict[str, Path]:
    """Atomically write deterministic JSON, CSV, and validation artifacts."""

    validate_graph(graph)
    from .scheduling import build_parallel_schedule

    schedule = build_parallel_schedule(graph)
    validation = {
        "result": "PASS",
        "node_count": len(graph.nodes),
        "edge_count": len(graph.edges),
        "wave_count": len(schedule.waves),
        "exclusive_device_count": len(schedule.exclusive_device_sequence),
        "ordered_ids": "CP-001..CP-220",
        "duplicate_ids": 0,
        "missing_ids": 0,
        "dependency_cycles": 0,
        "max_device_controllers_per_wave": 1,
        "status_summary": graph.status_summary(),
    }
    json_payload = graph.to_dict()
    json_payload["schedule"] = schedule.to_dict()
    json_payload["validation"] = validation
    json_contents = json.dumps(
        json_payload,
        ensure_ascii=False,
        indent=2,
        sort_keys=True,
    ) + "\n"

    wave_by_id = {
        checkpoint_id: wave
        for wave in schedule.waves
        for checkpoint_id in wave.checkpoints
    }
    csv_fields = (
        "checkpoint_id",
        "ordinal",
        "category",
        "subcategory",
        "checkpoint",
        "status",
        "evidence",
        "root_cause_or_note",
        "source_areas",
        "build_variants",
        "execution_target",
        "prerequisites",
        "test_procedure",
        "expected_result",
        "pass_fail_thresholds",
        "required_evidence",
        "regression_links",
        "blockers",
        "wave_index",
        "schedule_lane",
        "admission_subcheck",
        "execution_subcheck",
        "oracle_subcheck",
        "cleanup_subcheck",
        "evidence_subcheck",
    )
    csv_buffer = io.StringIO(newline="")
    writer = csv.DictWriter(
        csv_buffer,
        fieldnames=csv_fields,
        lineterminator="\n",
        extrasaction="raise",
    )
    writer.writeheader()
    for node in graph.nodes:
        wave = wave_by_id[node.checkpoint_id]
        if wave.device_checkpoint == node.checkpoint_id:
            lane = "exclusive-device"
        elif node.checkpoint_id in wave.external_checkpoints:
            lane = "external-owner"
        else:
            lane = "parallel-host"
        subchecks = node.subchecks.to_dict()
        writer.writerow(
            {
                "checkpoint_id": node.checkpoint_id,
                "ordinal": node.ordinal,
                "category": node.category,
                "subcategory": node.subcategory,
                "checkpoint": node.checkpoint,
                "status": node.status,
                "evidence": node.evidence,
                "root_cause_or_note": node.root_cause_or_note,
                "source_areas": _json_cell(node.source_areas),
                "build_variants": _json_cell(node.build_variants),
                "execution_target": node.execution_target,
                "prerequisites": _json_cell(node.prerequisites),
                "test_procedure": node.test_procedure,
                "expected_result": node.expected_result,
                "pass_fail_thresholds": _json_cell(node.pass_fail_thresholds),
                "required_evidence": _json_cell(node.required_evidence),
                "regression_links": _json_cell(node.regression_links),
                "blockers": _json_cell(node.blockers),
                "wave_index": wave.index,
                "schedule_lane": lane,
                "admission_subcheck": json.dumps(subchecks["admission"], ensure_ascii=False, sort_keys=True, separators=(",", ":")),
                "execution_subcheck": json.dumps(subchecks["execution"], ensure_ascii=False, sort_keys=True, separators=(",", ":")),
                "oracle_subcheck": json.dumps(subchecks["oracle"], ensure_ascii=False, sort_keys=True, separators=(",", ":")),
                "cleanup_subcheck": json.dumps(subchecks["cleanup"], ensure_ascii=False, sort_keys=True, separators=(",", ":")),
                "evidence_subcheck": json.dumps(subchecks["evidence"], ensure_ascii=False, sort_keys=True, separators=(",", ":")),
            }
        )

    status_summary = graph.status_summary()
    report_lines = (
        "NEO-QA HYPERGRID CHECKPOINT GRAPH VALIDATION",
        "result=PASS",
        f"schema={graph.schema_version}",
        f"ledger={graph.ledger_path}",
        f"ledger_sha256={graph.ledger_sha256}",
        f"nodes={len(graph.nodes)}",
        f"edges={len(graph.edges)}",
        "id_range=CP-001..CP-220",
        "duplicate_ids=0",
        "missing_ids=0",
        "dependency_cycles=0",
        f"parallel_waves={len(schedule.waves)}",
        f"exclusive_device_nodes={len(schedule.exclusive_device_sequence)}",
        "max_device_controllers_per_wave=1",
        "subchecks_per_checkpoint=admission,execution,oracle,cleanup,evidence",
        f"status_PASS={status_summary['PASS']}",
        f"status_FAIL={status_summary['FAIL']}",
        f"status_BLOCKED={status_summary['BLOCKED']}",
        f"status_PENDING={status_summary['PENDING']}",
    )
    report_contents = "\n".join(report_lines) + "\n"

    output = Path(output_directory)
    paths = {
        "json": output / "checkpoint_graph.json",
        "csv": output / "checkpoint_graph.csv",
        "validation": output / "checkpoint_graph_validation.txt",
    }
    _atomic_write_text(paths["json"], json_contents)
    _atomic_write_text(paths["csv"], csv_buffer.getvalue())
    _atomic_write_text(paths["validation"], report_contents)
    return paths
