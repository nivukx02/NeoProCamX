"""Measure retained real recording samples; never infer calibrated camera quality.

This host-only supplement reads the full-resolution RGB48 PNGs emitted by the physical
Direct recording inspector. It does not access ADB, replace the transport oracle, edit a
source, quantize the image, or promote a production checkpoint. Small-array unit tests
exercise arithmetic only and are never accepted as physical image evidence.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import struct
import subprocess
import zlib

import numpy as np


THRESHOLDS = {"minimum_nonblack_ratio": 0.01, "minimum_luma_stddev_8bit_scale": 1.0,
              "minimum_luma_range_8bit_scale": 8.0, "black_luma_threshold_8bit_scale": 3.0}
MAX_PIXELS = 4096 * 4096


def sha256(path: Path) -> str:
    with path.open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def png_dimensions(header: bytes) -> tuple[int, int]:
    if len(header) != 33 or header[:8] != b"\x89PNG\r\n\x1a\n" or header[8:16] != b"\0\0\0\rIHDR":
        raise ValueError("Missing complete PNG IHDR")
    if zlib.crc32(header[12:29]) != int.from_bytes(header[29:33], "big"):
        raise ValueError("PNG IHDR checksum mismatch")
    width, height, bits, colour, compression, filtering, interlace = struct.unpack(">IIBBBBB", header[16:29])
    if bits != 16 or colour != 2 or compression != 0 or filtering != 0 or interlace != 0:
        raise ValueError("Expected the original non-interlaced RGB48 PNG, without 8-bit substitution")
    if width < 2 or height < 2 or width * height > MAX_PIXELS:
        raise ValueError("PNG geometry exceeds the bounded full-resolution audit")
    return width, height


def measure_rgb16(pixels: np.ndarray) -> dict:
    if pixels.ndim != 3 or pixels.shape[2] != 3 or pixels.dtype.kind != "u" or pixels.dtype.itemsize != 2:
        raise ValueError("Expected an HxWx3 unsigned 16-bit image")
    height, width, _ = pixels.shape
    if min(height, width) < 2 or height * width > MAX_PIXELS:
        raise ValueError("Invalid audit geometry")
    count = height * width
    total = squares = 0.0
    minimum, maximum = 255.0, 0.0
    nonblack = 0
    channel_sum = np.zeros(3, dtype=np.uint64)
    channel_zero = np.zeros(3, dtype=np.uint64)
    channel_full = np.zeros(3, dtype=np.uint64)
    # Every pixel participates. Bounded row work avoids several frame-sized float64 copies.
    for first in range(0, height, 128):
        rows = pixels[first:first + 128].astype(np.uint32)
        weighted = 54 * rows[:, :, 0] + 183 * rows[:, :, 1] + 19 * rows[:, :, 2]
        luma = weighted.astype(np.float64) / (256.0 * 257.0)
        total += float(np.sum(luma, dtype=np.float64))
        squares += float(np.sum(luma * luma, dtype=np.float64))
        minimum = min(minimum, float(luma.min()))
        maximum = max(maximum, float(luma.max()))
        nonblack += int(np.count_nonzero(luma > THRESHOLDS["black_luma_threshold_8bit_scale"]))
        channel_sum += rows.sum(axis=(0, 1), dtype=np.uint64)
        channel_zero += (rows == 0).sum(axis=(0, 1), dtype=np.uint64)
        channel_full += (rows == 65535).sum(axis=(0, 1), dtype=np.uint64)
    mean = total / count
    deviation = max(0.0, squares / count - mean * mean) ** 0.5
    ratio, span = nonblack / count, maximum - minimum
    gates = {"nonblack_signal": ratio >= THRESHOLDS["minimum_nonblack_ratio"],
             "spatial_signal": deviation >= THRESHOLDS["minimum_luma_stddev_8bit_scale"],
             "luma_range": span >= THRESHOLDS["minimum_luma_range_8bit_scale"]}
    return {"width": width, "height": height, "pixels_measured": count, "decoded_bits_per_channel": 16,
            "mean_luma_8bit_scale": mean, "luma_stddev_8bit_scale": deviation,
            "luma_range_8bit_scale": span, "nonblack_ratio": ratio,
            "rgb_mean_code16": (channel_sum / count).tolist(),
            "rgb_zero_fraction": (channel_zero / count).tolist(),
            "rgb_full_fraction": (channel_full / count).tolist(), "signal_gates": gates}


def decode_sample(path: Path, expected_size: tuple[int, int], ffmpeg: str = "ffmpeg") -> dict:
    before = sha256(path)
    with path.open("rb") as source:
        width, height = png_dimensions(source.read(33))
    if (width, height) not in {expected_size, expected_size[::-1]}:
        raise ValueError("Decoded sample resolution differs from its recording (including orientation)")
    command = [ffmpeg, "-nostdin", "-v", "error", "-threads", "2", "-i", str(path),
               "-map", "0:v:0", "-frames:v", "1", "-pix_fmt", "rgb48le", "-f", "rawvideo", "pipe:1"]
    decoded = subprocess.run(command, capture_output=True, timeout=90)
    decoded.check_returncode()
    if decoded.stderr or len(decoded.stdout) != width * height * 6:
        raise ValueError("PNG decoder error or incomplete full-resolution RGB48 payload")
    pixels = np.frombuffer(decoded.stdout, dtype="<u2").reshape(height, width, 3)
    result = measure_rgb16(pixels)
    after = sha256(path)
    if before != after:
        raise ValueError("Evidence PNG changed during inspection")
    return {"path": str(path), "png_sha256_before": before, "png_sha256_after": after,
            "decoded_rgb48_sha256": hashlib.sha256(decoded.stdout).hexdigest(), **result}


def inspect_samples(audit_directory: Path, candidate_sha256: str) -> dict:
    if not re.fullmatch(r"[0-9A-Fa-f]{64}", candidate_sha256):
        raise ValueError("A complete candidate SHA-256 is required")
    summary_path = audit_directory / "summary.json"
    summary_hash = sha256(summary_path)
    summary = json.loads(summary_path.read_text(encoding="utf-8-sig"))
    if summary["candidate_apk_sha256"].upper() != candidate_sha256.upper():
        raise ValueError("Sample candidate does not match the requested APK")
    original = summary["source_sha256_before"]
    if not re.fullmatch(r"[0-9A-Fa-f]{64}", original) or original != summary["source_sha256_after"]:
        raise ValueError("Missing unchanged-original identity from the physical audit")
    size = (int(summary["video"]["width"]), int(summary["video"]["height"]))
    frames = [decode_sample(audit_directory / f"decoded-{label}.png", size)
              for label in ("first", "middle", "last")]
    if sha256(summary_path) != summary_hash:
        raise ValueError("Physical audit summary changed during signal inspection")
    signal_valid = all(all(frame["signal_gates"].values()) for frame in frames)
    distinct = len({frame["decoded_rgb48_sha256"] for frame in frames})
    return {"schema": "camx/retained-recording-decoded-signal/v1",
            "status": "PASS" if signal_valid and distinct >= 2 else "FAIL",
            "scope": "Signal presence in three real decoded frames only; not calibrated colour, sharpness, noise, motion or no-freeze proof",
            "candidate_apk_sha256": candidate_sha256.upper(), "physical_serial": summary["physical_serial"],
            "source": summary["source"], "source_sha256_at_physical_audit": original,
            "physical_audit": str(summary_path), "physical_audit_sha256": summary_hash,
            "thresholds": THRESHOLDS, "frames": frames, "distinct_decoded_frame_hashes": distinct,
            "production_ready": False, "calibrated_colour": "NOT_MEASURED",
            "temporal_caveat": "Distinct noise can change hashes; three samples cannot prove freedom from repeated/frozen intermediate frames."}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--audit-directory", required=True, action="append", type=Path)
    parser.add_argument("--candidate-sha256", required=True)
    args = parser.parse_args()
    failed = False
    for directory in args.audit_directory:
        stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S-%fZ")
        destination = directory / f"decoded-signal-{stamp}.json"
        try:
            report = inspect_samples(directory, args.candidate_sha256)
        except Exception as error:
            report = {"status": "ERROR", "error_type": type(error).__name__, "error": str(error),
                      "candidate_apk_sha256": args.candidate_sha256, "production_ready": False}
        with destination.open("x", encoding="utf-8") as output:
            output.write(json.dumps(report, indent=2, allow_nan=False) + "\n")
        print(json.dumps({"status": report["status"], "evidence": str(destination)}), flush=True)
        failed |= report["status"] != "PASS"
    return int(failed)


if __name__ == "__main__":
    raise SystemExit(main())
