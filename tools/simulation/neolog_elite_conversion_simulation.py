#!/usr/bin/env python3
"""Deterministic host oracle for NeoLog 10-bit packing and elite evidence gates.

The model proves math and bookkeeping invariants that do not require Android:
continuous floating-point RGB-to-YCbCr, a single final 10-bit quantization boundary,
bounded deterministic TPDF, correct 4:2:2 pair averaging, and exact frame/geometry
accounting. It does not emulate Camera2, MediaCodec, GPU execution, thermals, bitrate,
or perceived image quality; those remain device-validation responsibilities.
"""

from __future__ import annotations

import json
import math
from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Iterable, Sequence


MAX10 = 1023
DITHER_LSB = 0.5
KR = 0.2627
KB = 0.0593
KG = 1.0 - KR - KB
LUMA_MIN = 64.0
LUMA_SPAN = 876.0
CHROMA_ZERO = 512.0
CHROMA_SPAN = 896.0
UINT32_MASK = (1 << 32) - 1


class PackingFormat(str, Enum):
    P010_420 = "P010_420"
    V210_422 = "V210_422"


@dataclass(frozen=True)
class Frame:
    index: int
    width: int
    height: int
    rgb: tuple[tuple[float, float, float], ...]

    def __post_init__(self) -> None:
        if self.index < 0:
            raise ValueError("frame index must be non-negative")
        if self.width <= 0 or self.height <= 0:
            raise ValueError("frame geometry must be positive")
        if len(self.rgb) != self.width * self.height:
            raise ValueError("RGB sample count must exactly match frame geometry")
        if not all(math.isfinite(channel) for pixel in self.rgb for channel in pixel):
            raise ValueError("RGB samples must be finite")


@dataclass
class QuantizationTrace:
    pre_matrix_sample_calls: int = 0
    final_sample_calls: int = 0
    final_boundary_count: int = 1


@dataclass(frozen=True)
class PackedFrame:
    index: int
    width: int
    height: int
    packing: PackingFormat
    luma_codes: tuple[int, ...]
    chroma_codes: tuple[tuple[int, int], ...]
    pre_matrix_quantizations: int
    final_quantizations: int
    final_quantization_boundaries: int

    @property
    def emitted_component_count(self) -> int:
        return len(self.luma_codes) + 2 * len(self.chroma_codes)

    @property
    def single_final_quantization_proven(self) -> bool:
        return (
            self.pre_matrix_quantizations == 0
            and self.final_quantization_boundaries == 1
            and self.final_quantizations == self.emitted_component_count
        )


@dataclass(frozen=True)
class ClipSimulation:
    packing: PackingFormat
    source_frames: tuple[Frame, ...]
    output_frames: tuple[PackedFrame, ...]

    @property
    def frame_count_preserved(self) -> bool:
        return len(self.source_frames) == len(self.output_frames)

    @property
    def frame_order_preserved(self) -> bool:
        return tuple(frame.index for frame in self.source_frames) == tuple(
            frame.index for frame in self.output_frames
        )

    @property
    def geometry_preserved(self) -> bool:
        return all(
            source.width == output.width
            and source.height == output.height
            and len(output.luma_codes) == source.width * source.height
            for source, output in zip(self.source_frames, self.output_frames)
        )

    @property
    def every_frame_single_quantized(self) -> bool:
        return all(frame.single_final_quantization_proven for frame in self.output_frames)


@dataclass(frozen=True)
class EliteEvidence:
    source_width: int
    source_height: int
    output_width: int
    output_height: int
    source_fps: int
    output_fps: int
    source_frame_count: int | None
    output_frame_count: int | None
    source_bit_depth: int
    output_bit_depth: int
    working_precision_bits: int
    working_signal_linear: bool | None
    tone_map_applications: int
    output_transfer_applications: int
    final_quantization_applications: int
    metadata_preserved: bool | None
    color_evidence_consistent: bool | None
    used_eight_bit_fallback: bool
    quality_reduced_for_speed: bool
    destructive_fallback_used: bool
    compressed_output: bool
    bitrate_evidence_present: bool
    bitrate_floor_satisfied: bool | None


class EvidenceRejected(ValueError):
    """Raised when publication evidence does not prove the elite contract."""


def _hash_unit(value: int) -> float:
    """Exact unsigned-32-bit equivalent of the Kotlin integer hash."""
    hashed = (value * -0x61C88647) & UINT32_MASK
    hashed = (hashed ^ (hashed >> 16)) & UINT32_MASK
    hashed = (hashed * -0x7EE3623B) & UINT32_MASK
    hashed = (hashed ^ (hashed >> 13)) & UINT32_MASK
    hashed = (hashed * -0x3361D2AF) & UINT32_MASK
    hashed = (hashed ^ (hashed >> 16)) & UINT32_MASK
    return (hashed >> 8) / float(1 << 24)


def tpdf_unit(index: int) -> float:
    return _hash_unit(index * 2) - _hash_unit(index * 2 + 1)


def dither_lsb(index: int) -> float:
    return DITHER_LSB * tpdf_unit(index)


def rgb_to_ycbcr_continuous(rgb: tuple[float, float, float]) -> tuple[float, float, float]:
    """Convert continuous NeoLog RGB without clamping or integer pre-quantization."""
    red, green, blue = rgb
    luma = KR * red + KG * green + KB * blue
    cb = (blue - luma) / (2.0 * (1.0 - KB))
    cr = (red - luma) / (2.0 * (1.0 - KR))
    return luma, cb, cr


def final_quantize_10(code: float, index: int, trace: QuantizationTrace) -> int:
    if trace.final_boundary_count != 1:
        raise ValueError("exactly one final quantization boundary is required")
    trace.final_sample_calls += 1
    # Kotlin roundToInt follows Math.round semantics: nearest integer, with exact ties
    # toward positive infinity. Codes are clamped only after this final operation.
    rounded = math.floor(code + dither_lsb(index) + 0.5)
    return min(max(rounded, 0), MAX10)


def _packed_frame(
    frame: Frame,
    packing: PackingFormat,
    luma_codes: list[int],
    chroma_codes: list[tuple[int, int]],
    trace: QuantizationTrace,
) -> PackedFrame:
    return PackedFrame(
        index=frame.index,
        width=frame.width,
        height=frame.height,
        packing=packing,
        luma_codes=tuple(luma_codes),
        chroma_codes=tuple(chroma_codes),
        pre_matrix_quantizations=trace.pre_matrix_sample_calls,
        final_quantizations=trace.final_sample_calls,
        final_quantization_boundaries=trace.final_boundary_count,
    )


def pack_p010(frame: Frame) -> PackedFrame:
    """Pack continuous NeoLog RGB into modeled limited-range P010 component codes."""
    trace = QuantizationTrace()
    ycbcr = tuple(rgb_to_ycbcr_continuous(pixel) for pixel in frame.rgb)
    luma_codes = [
        final_quantize_10(ycbcr[pixel][0] * LUMA_SPAN + LUMA_MIN, pixel, trace)
        for pixel in range(frame.width * frame.height)
    ]
    chroma_width = (frame.width + 1) // 2
    chroma_height = (frame.height + 1) // 2
    chroma_codes: list[tuple[int, int]] = []
    for cy in range(chroma_height):
        for cx in range(chroma_width):
            samples = [
                ycbcr[y * frame.width + x]
                for y in range(cy * 2, min(cy * 2 + 2, frame.height))
                for x in range(cx * 2, min(cx * 2 + 2, frame.width))
            ]
            cb = sum(sample[1] for sample in samples) / len(samples)
            cr = sum(sample[2] for sample in samples) / len(samples)
            chroma_sample = cy * chroma_width + cx
            dither_index = frame.width * frame.height + chroma_sample * 2
            chroma_codes.append(
                (
                    final_quantize_10(cb * CHROMA_SPAN + CHROMA_ZERO, dither_index, trace),
                    final_quantize_10(cr * CHROMA_SPAN + CHROMA_ZERO, dither_index + 1, trace),
                )
            )
    return _packed_frame(frame, PackingFormat.P010_420, luma_codes, chroma_codes, trace)


def pack_v210(frame: Frame) -> PackedFrame:
    """Model v210 codes with continuous chroma averaged across every real 2-pixel pair."""
    trace = QuantizationTrace()
    ycbcr = tuple(rgb_to_ycbcr_continuous(pixel) for pixel in frame.rgb)
    luma_codes = [
        final_quantize_10(ycbcr[pixel][0] * LUMA_SPAN + LUMA_MIN, pixel, trace)
        for pixel in range(frame.width * frame.height)
    ]
    chroma_width = (frame.width + 1) // 2
    chroma_codes: list[tuple[int, int]] = []
    for y in range(frame.height):
        for cx in range(chroma_width):
            x0 = cx * 2
            samples = [
                ycbcr[y * frame.width + x]
                for x in range(x0, min(x0 + 2, frame.width))
            ]
            cb = sum(sample[1] for sample in samples) / len(samples)
            cr = sum(sample[2] for sample in samples) / len(samples)
            chroma_sample = y * chroma_width + cx
            dither_index = frame.width * frame.height + chroma_sample * 2
            chroma_codes.append(
                (
                    final_quantize_10(cb * CHROMA_SPAN + CHROMA_ZERO, dither_index, trace),
                    final_quantize_10(cr * CHROMA_SPAN + CHROMA_ZERO, dither_index + 1, trace),
                )
            )
    return _packed_frame(frame, PackingFormat.V210_422, luma_codes, chroma_codes, trace)


def simulate_clip(frames: Iterable[Frame], packing: PackingFormat) -> ClipSimulation:
    source_frames = tuple(frames)
    packer = pack_p010 if packing == PackingFormat.P010_420 else pack_v210
    output_frames = tuple(packer(frame) for frame in source_frames)
    return ClipSimulation(packing, source_frames, output_frames)


def audit_evidence(evidence: EliteEvidence) -> tuple[str, ...]:
    """Return every rejection reason; unknown critical booleans fail closed."""
    reasons: list[str] = []
    if evidence.source_width <= 0 or evidence.source_height <= 0:
        reasons.append("source geometry must be positive")
    if evidence.output_width <= 0 or evidence.output_height <= 0:
        reasons.append("output geometry must be positive")
    if (evidence.source_width, evidence.source_height) != (
        evidence.output_width,
        evidence.output_height,
    ):
        reasons.append("conversion must preserve exact geometry")
    if evidence.source_fps <= 0 or evidence.output_fps <= 0:
        reasons.append("frame rate must be positive")
    if evidence.source_fps != evidence.output_fps:
        reasons.append("conversion must preserve exact frame rate")
    if (evidence.source_frame_count is None) != (evidence.output_frame_count is None):
        reasons.append("frame counts must be evidenced together")
    elif evidence.source_frame_count is not None and evidence.output_frame_count is not None:
        if evidence.source_frame_count <= 0 or evidence.output_frame_count <= 0:
            reasons.append("evidenced frame counts must be positive")
        if evidence.source_frame_count != evidence.output_frame_count:
            reasons.append("conversion must preserve every frame")
    if evidence.source_bit_depth < 10:
        reasons.append("source must be at least 10-bit")
    if evidence.output_bit_depth != 10:
        reasons.append("developed output must be exactly 10-bit")
    if evidence.working_precision_bits < 32:
        reasons.append("working precision must be at least Float32")
    if evidence.working_signal_linear is not True:
        reasons.append("working signal linearity must be proven")
    if evidence.tone_map_applications not in (0, 1):
        reasons.append("tone mapping may be applied at most once")
    if evidence.output_transfer_applications != 1:
        reasons.append("exactly one output transfer is required")
    if evidence.final_quantization_applications != 1:
        reasons.append("exactly one final quantization boundary is required")
    if evidence.metadata_preserved is not True:
        reasons.append("metadata preservation must be proven")
    if evidence.color_evidence_consistent is not True:
        reasons.append("color evidence consistency must be proven")
    if evidence.used_eight_bit_fallback:
        reasons.append("8-bit fallback is forbidden")
    if evidence.quality_reduced_for_speed:
        reasons.append("quality reduction for speed is forbidden")
    if evidence.destructive_fallback_used:
        reasons.append("destructive fallback is forbidden")
    if evidence.compressed_output:
        if not evidence.bitrate_evidence_present:
            reasons.append("compressed output requires bitrate evidence")
        if evidence.bitrate_floor_satisfied is not True:
            reasons.append("compressed output bitrate floor must be proven")
    elif evidence.bitrate_evidence_present:
        reasons.append("uncompressed output must not invent encoded bitrate evidence")
    return tuple(reasons)


def enforce_evidence(evidence: EliteEvidence) -> EliteEvidence:
    reasons = audit_evidence(evidence)
    if reasons:
        raise EvidenceRejected("; ".join(reasons))
    return evidence


def production_source_contracts(repo_root: Path) -> dict[str, bool]:
    packer = (
        repo_root
        / "app/src/main/java/com/neoproc/neoproccamx/recording/RawAcescgTenBitPacker.kt"
    ).read_text(encoding="utf-8")
    contract = (
        repo_root
        / "app/src/main/java/com/neoproc/neoproccamx/recording/EliteConversionQualityContract.kt"
    ).read_text(encoding="utf-8")
    p010 = packer.split("fun packP010420", 1)[1].split("private fun fillNeoLogRow", 1)[0]
    v210 = packer.split("private fun packV210", 1)[1].split("private fun quantizeFinal10", 1)[0]
    return {
        "p010_uses_continuous_neolog_rows": (
            "fillNeoLogRow" in p010
            and "quantizeFinal10" in p010
            and "quantize10(" not in p010
        ),
        "v210_uses_continuous_neolog_rows": (
            "fillNeoLogRow" in v210
            and "quantizeFinal10" in v210
            and "quantize10(" not in v210
        ),
        "v210_averages_real_pairs": (
            "val sampleCount = if (x + 1 < width) 2 else 1" in v210
            and "cbSum / sampleCount" in v210
            and "crSum / sampleCount" in v210
        ),
        "final_quantizer_has_deterministic_tpdf": (
            "QUANTIZE_DITHER_LSB.toDouble() * tpdfUnit(index).toDouble()" in packer
            and "roundToInt()" in packer
        ),
        "elite_contract_enforces_single_boundary": (
            "fun enforce(evidence: Evidence)" in contract
            and "finalQuantizationApplications != 1" in contract
        ),
        "elite_contract_rejects_geometry_or_frame_loss": (
            "conversion must preserve the exact source resolution" in contract
            and "conversion must preserve every source frame" in contract
        ),
        "elite_contract_rejects_quality_fallbacks": (
            "8-bit fallback is forbidden" in contract
            and "quality reduction for conversion speed is forbidden" in contract
            and "destructive quality fallback is forbidden" in contract
        ),
    }


def reference_frames(count: int = 9, width: int = 7, height: int = 5) -> tuple[Frame, ...]:
    frames: list[Frame] = []
    for frame_index in range(count):
        pixels = tuple(
            (
                ((pixel * 37 + frame_index * 11 + 3) % 101) / 100.0,
                ((pixel * 19 + frame_index * 7 + 5) % 97) / 96.0,
                ((pixel * 53 + frame_index * 13 + 9) % 103) / 102.0,
            )
            for pixel in range(width * height)
        )
        frames.append(Frame(frame_index, width, height, pixels))
    return tuple(frames)


def reference_evidence(clip: ClipSimulation) -> EliteEvidence:
    first = clip.source_frames[0]
    return EliteEvidence(
        source_width=first.width,
        source_height=first.height,
        output_width=first.width,
        output_height=first.height,
        source_fps=25,
        output_fps=25,
        source_frame_count=len(clip.source_frames),
        output_frame_count=len(clip.output_frames),
        source_bit_depth=12,
        output_bit_depth=10,
        working_precision_bits=32,
        working_signal_linear=True,
        tone_map_applications=0,
        output_transfer_applications=1,
        final_quantization_applications=1,
        metadata_preserved=True,
        color_evidence_consistent=True,
        used_eight_bit_fallback=False,
        quality_reduced_for_speed=False,
        destructive_fallback_used=False,
        compressed_output=False,
        bitrate_evidence_present=False,
        bitrate_floor_satisfied=None,
    )


def run_reference_simulation(repo_root: Path | None = None) -> dict[str, object]:
    root = repo_root or Path(__file__).resolve().parents[2]
    frames = reference_frames()
    clips = tuple(simulate_clip(frames, packing) for packing in PackingFormat)
    source_contracts = production_source_contracts(root)
    evidence_rejections = audit_evidence(reference_evidence(clips[0]))
    passed = (
        all(clip.frame_count_preserved for clip in clips)
        and all(clip.frame_order_preserved for clip in clips)
        and all(clip.geometry_preserved for clip in clips)
        and all(clip.every_frame_single_quantized for clip in clips)
        and not evidence_rejections
        and all(source_contracts.values())
    )
    return {
        "status": "PASS" if passed else "FAIL",
        "scope": "deterministic host math, packing bookkeeping, and source-contract evidence only",
        "frame_count": len(frames),
        "geometry": f"{frames[0].width}x{frames[0].height}",
        "packings": {
            clip.packing.value: {
                "frame_count_preserved": clip.frame_count_preserved,
                "frame_order_preserved": clip.frame_order_preserved,
                "geometry_preserved": clip.geometry_preserved,
                "single_final_quantization": clip.every_frame_single_quantized,
            }
            for clip in clips
        },
        "source_contracts": source_contracts,
        "baseline_evidence_rejections": list(evidence_rejections),
        "tpdf_sample_bounds_lsb": [
            min(dither_lsb(index) for index in range(4096)),
            max(dither_lsb(index) for index in range(4096)),
        ],
        "limitations": [
            "No Camera2, MediaCodec, GPU, storage, thermal, or wall-clock behavior is simulated.",
            "A PASS does not prove perceived image quality or physical-device throughput.",
            "Device codec/color-tag inspection and captured-scene validation remain required.",
        ],
    }


def main(argv: Sequence[str] | None = None) -> int:
    del argv
    report = run_reference_simulation()
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
