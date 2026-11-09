#!/usr/bin/env python3
"""Deterministic operation-count model for the offline conversion architecture.

This is deliberately not a wall-clock benchmark.  It proves only work that can be
counted without Android hardware: per-frame buffer allocations, redundant whole-file
publish writes, and source indexing passes.  Pixel processing, MediaCodec throughput,
Vulkan execution time, storage bandwidth, and thermal behaviour still require a real
device benchmark.

The model uses a ten-second, 30 fps reference clip.  It classifies each countable
operation class independently; it never combines unrelated ratios into a synthetic
speedup and never treats one optimized class as proof that every operation or the
end-to-end conversion is 10x faster.  A direct pending-MediaStore destination removes
the redundant publish copy.  Providers that cannot expose a seekable descriptor retain
the full-quality fallback, whose 8 MiB copy chunks replace the old 8 KiB FUSE writes.
"""

from __future__ import annotations

import json
import math
from dataclasses import asdict, dataclass
from enum import Enum
from typing import Sequence


REFERENCE_FRAMES = 300
LEGACY_COPY_CHUNK_BYTES = 8 * 1024
ACCELERATED_COPY_CHUNK_BYTES = 8 * 1024 * 1024
REQUIRED_WORK_REDUCTION = 10.0


class ConversionFamily(str, Enum):
    SDR_NIVUKX_PACKET_COPY = "SDR_NIVUKX_PACKET_COPY"
    NDR_NIVUKX_PACKET_COPY = "NDR_NIVUKX_PACKET_COPY"
    NPV10_TO_MAIN10 = "NPV10_TO_MAIN10"
    NPV10_TO_R210 = "NPV10_TO_R210"
    NPV10_TO_V210 = "NPV10_TO_V210"
    RAW_TO_NEOLOG = "RAW_TO_NEOLOG"
    RAW_TO_NDR10_PLUS = "RAW_TO_NDR10_PLUS"
    RAW_TO_NIVUKX_VISION = "RAW_TO_NIVUKX_VISION"
    RAW_TO_CINEMA_DNG = "RAW_TO_CINEMA_DNG"
    RECORD_DEVELOP_SDR = "RECORD_DEVELOP_SDR"
    RECORD_DEVELOP_NDR = "RECORD_DEVELOP_NDR"


@dataclass(frozen=True)
class QualityInvariants:
    source_master_read_only: bool = True
    source_resolution_preserved: bool = True
    source_frame_count_preserved: bool = True
    source_frame_order_preserved: bool = True
    ten_bit_or_source_native_depth_preserved: bool = True
    color_math_unchanged: bool = True
    metadata_preserved: bool = True
    bitrate_policy_unchanged: bool = True

    @property
    def preserved(self) -> bool:
        return all(asdict(self).values())


@dataclass(frozen=True)
class WorkBudget:
    family: ConversionFamily
    frames: int
    output_bytes: int
    frame_buffer_kinds: int
    persistent_slots: int
    fixed_count_index: bool
    direct_media_store: bool
    staged_publish_copy_eliminated: bool = True
    accelerated_copy_chunk_bytes: int = ACCELERATED_COPY_CHUNK_BYTES
    quality: QualityInvariants = QualityInvariants()

    def __post_init__(self) -> None:
        if min(
            self.frames,
            self.output_bytes,
            self.frame_buffer_kinds,
            self.persistent_slots,
            self.accelerated_copy_chunk_bytes,
        ) <= 0:
            raise ValueError("work-budget values must be positive")


@dataclass(frozen=True)
class WorkResult:
    family: str
    direct_media_store: bool
    frame_allocation_reduction: float
    publish_write_reduction: float | None
    index_reduction: float
    redundant_publish_payload_pass_removed: bool
    ten_x_operation_classes: tuple[str, ...]
    below_ten_x_operation_classes: tuple[str, ...]
    regressed_operation_classes: tuple[str, ...]
    operation_evidence: tuple["OperationCountEvidence", ...]
    fixed_count_index_used: bool
    quality_assumptions_preserved: bool
    at_least_one_operation_class_10x: bool
    all_applicable_operation_classes_10x: bool
    modeled_operation_counts_non_regressing: bool
    has_scoped_modeled_10x_evidence: bool
    wall_clock_10x_measured: bool = False
    end_to_end_10x_measured: bool = False


@dataclass(frozen=True)
class OperationCountEvidence:
    """One independently countable operation class.

    ``applicable=False`` is reserved for work that the selected architecture removes
    entirely or that does not exist on that path.  Removed work is reported explicitly
    through ``eliminated`` and is not converted into an infinite numeric speedup.
    """

    name: str
    applicable: bool
    before_count: int | None
    after_count: int | None
    reduction: float | None
    at_least_10x: bool
    eliminated: bool = False


def _ratio(before: int, after: int) -> float:
    if before < 0 or after < 0:
        raise ValueError("operation counts must not be negative")
    if after == 0:
        return math.inf if before > 0 else 1.0
    return before / after


def evaluate_work_budget(budget: WorkBudget) -> WorkResult:
    # Legacy converters allocated each large frame-buffer kind for every frame.
    legacy_frame_allocations = budget.frames * budget.frame_buffer_kinds
    accelerated_frame_allocations = budget.persistent_slots * budget.frame_buffer_kinds

    # Legacy publication staged the complete MOV and copied it again using 8 KiB chunks.
    legacy_publish_writes = math.ceil(budget.output_bytes / LEGACY_COPY_CHUNK_BYTES)
    publish_pass_removed = budget.direct_media_store and budget.staged_publish_copy_eliminated
    accelerated_publish_writes = 0 if publish_pass_removed else math.ceil(
        budget.output_bytes / budget.accelerated_copy_chunk_bytes
    )

    # Fixed-packet masters answer frame count in O(1). Variable containers still require the
    # existing packet walk; do not assign them an invented indexing improvement.
    legacy_index_operations = budget.frames
    accelerated_index_operations = 1 if budget.fixed_count_index else legacy_index_operations

    allocation_ratio = _ratio(legacy_frame_allocations, accelerated_frame_allocations)
    publish_ratio = None if publish_pass_removed else _ratio(
        legacy_publish_writes, accelerated_publish_writes
    )
    index_ratio = _ratio(legacy_index_operations, accelerated_index_operations)
    operation_evidence = (
        OperationCountEvidence(
            name="frame_allocations",
            applicable=True,
            before_count=legacy_frame_allocations,
            after_count=accelerated_frame_allocations,
            reduction=allocation_ratio,
            at_least_10x=allocation_ratio >= REQUIRED_WORK_REDUCTION,
        ),
        OperationCountEvidence(
            name="publish_write_calls",
            applicable=not publish_pass_removed,
            before_count=legacy_publish_writes if not publish_pass_removed else None,
            after_count=accelerated_publish_writes if not publish_pass_removed else None,
            reduction=publish_ratio,
            at_least_10x=(
                publish_ratio is not None and publish_ratio >= REQUIRED_WORK_REDUCTION
            ),
            eliminated=publish_pass_removed,
        ),
        OperationCountEvidence(
            name="index_operations",
            applicable=True,
            before_count=legacy_index_operations,
            after_count=accelerated_index_operations,
            reduction=index_ratio,
            at_least_10x=index_ratio >= REQUIRED_WORK_REDUCTION,
        ),
    )
    applicable_evidence = tuple(item for item in operation_evidence if item.applicable)
    ten_x_classes = tuple(
        item.name for item in applicable_evidence if item.at_least_10x
    )
    below_ten_x_classes = tuple(
        item.name for item in applicable_evidence if not item.at_least_10x
    )
    regressed_classes = tuple(
        item.name for item in applicable_evidence
        if item.reduction is None or item.reduction < 1.0
    )
    no_operation_regressed = not regressed_classes
    quality_ok = budget.quality.preserved
    at_least_one_10x = bool(ten_x_classes)
    all_applicable_10x = bool(applicable_evidence) and not below_ten_x_classes
    return WorkResult(
        family=budget.family.value,
        direct_media_store=budget.direct_media_store,
        frame_allocation_reduction=allocation_ratio,
        publish_write_reduction=publish_ratio,
        index_reduction=index_ratio,
        redundant_publish_payload_pass_removed=publish_pass_removed,
        ten_x_operation_classes=ten_x_classes,
        below_ten_x_operation_classes=below_ten_x_classes,
        regressed_operation_classes=regressed_classes,
        operation_evidence=operation_evidence,
        fixed_count_index_used=budget.fixed_count_index,
        quality_assumptions_preserved=quality_ok,
        at_least_one_operation_class_10x=at_least_one_10x,
        all_applicable_operation_classes_10x=all_applicable_10x,
        modeled_operation_counts_non_regressing=no_operation_regressed,
        # This deliberately says "scoped evidence", not "10x conversion". It means
        # only that at least one explicitly named operation class crosses the threshold
        # under full-quality assumptions and no other modeled count regresses.
        has_scoped_modeled_10x_evidence=(
            quality_ok and no_operation_regressed and at_least_one_10x
        ),
    )


def reference_budgets(*, direct_media_store: bool) -> list[WorkBudget]:
    # 512 MiB is intentionally conservative for a ten-second high-quality 4K derivative.
    # The operation-ratio proof remains above 10x for much smaller outputs because frame
    # buffers are persistent and the fallback copy chunk itself is 1024x larger.
    output_bytes = 512 * 1024 * 1024
    profiles = {
        ConversionFamily.SDR_NIVUKX_PACKET_COPY: (1, 2, False),
        ConversionFamily.NDR_NIVUKX_PACKET_COPY: (1, 2, False),
        ConversionFamily.NPV10_TO_MAIN10: (4, 4, True),
        ConversionFamily.NPV10_TO_R210: (4, 4, True),
        ConversionFamily.NPV10_TO_V210: (4, 4, True),
        ConversionFamily.RAW_TO_NEOLOG: (3, 2, False),
        ConversionFamily.RAW_TO_NDR10_PLUS: (3, 2, False),
        ConversionFamily.RAW_TO_NIVUKX_VISION: (3, 2, False, True, ACCELERATED_COPY_CHUNK_BYTES),
        # CinemaDNG is already written directly to MediaStore. Its deterministic 10x-class win is
        # replacing one 8 KiB-ish row write with 1 MiB sample-aligned batches, not removing a stage.
        ConversionFamily.RAW_TO_CINEMA_DNG: (1, REFERENCE_FRAMES, False, False, 1024 * 1024),
        ConversionFamily.RECORD_DEVELOP_SDR: (3, 4, False, True, ACCELERATED_COPY_CHUNK_BYTES),
        ConversionFamily.RECORD_DEVELOP_NDR: (3, 4, False, True, ACCELERATED_COPY_CHUNK_BYTES),
    }
    # Keep the tuple declaration compact while assigning the common values to older entries.
    normalized_profiles = {
        family: values if len(values) == 5 else (*values, True, ACCELERATED_COPY_CHUNK_BYTES)
        for family, values in profiles.items()
    }
    return [
        WorkBudget(
            family=family,
            frames=REFERENCE_FRAMES,
            output_bytes=output_bytes,
            frame_buffer_kinds=buffer_kinds,
            persistent_slots=slots,
            fixed_count_index=fixed_count,
            direct_media_store=direct_media_store,
            staged_publish_copy_eliminated=eliminate_staging,
            accelerated_copy_chunk_bytes=copy_chunk_bytes,
        )
        for family, (
            buffer_kinds,
            slots,
            fixed_count,
            eliminate_staging,
            copy_chunk_bytes,
        ) in normalized_profiles.items()
    ]


def run_reference_simulation() -> dict[str, object]:
    direct = [evaluate_work_budget(item) for item in reference_budgets(direct_media_store=True)]
    fallback = [evaluate_work_budget(item) for item in reference_budgets(direct_media_store=False)]
    results = direct + fallback
    passed = all(result.has_scoped_modeled_10x_evidence for result in results)
    all_applicable_10x = all(
        result.all_applicable_operation_classes_10x for result in results
    )
    return {
        "status": "PASS" if passed else "FAIL",
        "status_scope": (
            "modeled operation-count evidence only; PASS is not a wall-clock, "
            "all-operation, all-path, or end-to-end 10x result"
        ),
        "required_operation_class_reduction": REQUIRED_WORK_REDUCTION,
        "reference_frames": REFERENCE_FRAMES,
        "results": [asdict(result) for result in results],
        "quality_invariants_are_declared_assumptions": True,
        "modeled_quality_assumption_failures": sum(
            not result.quality_assumptions_preserved for result in results
        ),
        "every_family_has_at_least_one_modeled_10x_operation_class": all(
            result.at_least_one_operation_class_10x for result in results
        ),
        "all_applicable_operation_classes_are_modeled_10x": all_applicable_10x,
        "wall_clock_10x_measured": False,
        "end_to_end_10x_measured": False,
        "limitations": [
            "Operation classes are reported separately and are never summed into a synthetic speedup.",
            "A family PASS means at least one named operation class reaches 10x; it does not mean every class does.",
            "This models deterministic operation counts, not elapsed time or throughput.",
            "Frame-buffer kinds and persistent slots are explicit architecture assumptions, not runtime measurements.",
            "Quality fields are fail-closed contract assumptions; byte/color parity belongs to JVM golden tests.",
            "MediaCodec, Vulkan, storage bandwidth, GPU/CPU contention, and thermals require adb profiling.",
            "Every output pixel, frame, quality policy, and metadata contract remains in scope.",
        ],
    }


def main(argv: Sequence[str] | None = None) -> int:
    del argv
    report = run_reference_simulation()
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
