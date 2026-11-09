"""Inspect retained physical MOVs with bounded ADB reads and the existing MOV oracle.

The only generated artifacts are host evidence. No device media is modified or removed.
One canonical ADB lease covers all files; camera recordings must have ended first.
"""
from __future__ import annotations

import argparse
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re
import secrets
import struct
import subprocess
import threading
from http.server import ThreadingHTTPServer

from tools.neo_qa_hypergrid.adb_readonly_media_proxy import AdbMediaReader, make_handler
from tools.neo_qa_hypergrid.device_orchestrator import ExclusiveAdbLease
from tools.neo_qa_hypergrid.mov_timing_oracle import analyze_mov_timing


def save_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def extract_moov(reader: AdbMediaReader, destination: Path) -> dict:
    offset = 0
    for _ in range(100):
        if offset + 8 > reader.size:
            raise ValueError("No complete moov atom in physical recording")
        header = reader.read(offset, min(16, reader.size - offset))
        size, kind = struct.unpack(">I4s", header[:8])
        header_bytes = 8
        if size == 1:
            size = struct.unpack(">Q", header[8:16])[0]
            header_bytes = 16
        elif size == 0:
            size = reader.size - offset
        if size < header_bytes or offset + size > reader.size:
            raise ValueError("Invalid physical MOV atom extent")
        if kind == b"moov":
            if size > 16 * 1024 * 1024:
                raise ValueError("MOV metadata exceeds bounded audit size")
            payload = bytearray()
            while len(payload) < size:
                payload.extend(reader.read(offset + len(payload), min(4 * 1024 * 1024, size - len(payload))))
            destination.write_bytes(payload)
            return {"physical_offset": offset, "bytes": size,
                    "sha256": hashlib.sha256(payload).hexdigest(),
                    "scope": "Exact moov bytes; this artifact does not contain the media payload"}
        offset += size
    raise ValueError("Physical MOV exceeds bounded top-level atom count")


def inspect(args, run_directory: Path) -> dict:
    run = json.loads((run_directory / "run-result.json").read_text(encoding="utf-8-sig"))
    identity = (run_directory / "candidate-device-identity.txt").read_text(encoding="utf-8-sig")
    match = re.search(r"^apkSha256=([A-Fa-f0-9]{64})\s", identity, re.M)
    if match is None or match.group(1).upper() != args.apk_sha256.upper():
        raise ValueError("Run candidate identity differs from requested audit candidate")
    if run["pipeline"] != "DIRECT" or not run["output_finalized"]:
        raise ValueError("Expected one finalized Direct recording")
    row = run["media_store_row"]
    name = re.search(r"_display_name=(.*?), relative_path=", row).group(1)
    size = int(re.search(r"_size=(\d+)", row).group(1))
    source = "/sdcard/DCIM/CamX/" + name
    evidence = run_directory / ("independent-" + datetime.now().strftime("%Y%m%d-%H%M%S"))
    evidence.mkdir()
    reader = AdbMediaReader(args.adb, args.serial, source, size)
    before_hash = reader.sha256()
    save_json(evidence / "source-before.json", {"source": source, "stat": reader.identity,
                                               "sha256": before_hash, "apk_sha256": args.apk_sha256})
    moov = extract_moov(reader, evidence / "physical-moov.bin")
    save_json(evidence / "moov-extraction.json", moov)
    timing = analyze_mov_timing(evidence / "physical-moov.bin")
    save_json(evidence / "mov-timing.json", timing.to_dict())
    token = secrets.token_hex(16)
    with ThreadingHTTPServer(("127.0.0.1", 0), make_handler(reader, token)) as server:
        server.transport_failed = False
        server.delivered_bytes = 0
        server.stats_lock = threading.Lock()
        server.finish_requested = False
        worker = threading.Thread(target=server.serve_forever, daemon=True)
        worker.start()
        url = f"http://127.0.0.1:{server.server_port}/{token}/media"
        try:
            probe = subprocess.run(["ffprobe", "-v", "error", "-show_streams", "-show_format",
                                    "-of", "json", url], capture_output=True, timeout=180)
            (evidence / "ffprobe.json").write_bytes(probe.stdout)
            (evidence / "ffprobe-stderr.txt").write_bytes(probe.stderr)
            probe.check_returncode()
            streams = json.loads(probe.stdout)["streams"]
            video = next(stream for stream in streams if stream["codec_type"] == "video")
            audio = next(stream for stream in streams if stream["codec_type"] == "audio")
            duration = float(video["duration"])
            for label, seconds in [("first", 1.0), ("middle", duration / 2), ("last", duration - 1)]:
                image_path = evidence / f"decoded-{label}.png"
                command = ["ffmpeg", "-nostdin", "-v", "error", "-ss", str(seconds), "-threads", "2",
                           "-i", url, "-map", "0:v:0", "-frames:v", "1", "-pix_fmt", "rgb48be",
                           "-compression_level", "3", str(image_path)]
                decoded = subprocess.run(command, capture_output=True, timeout=180)
                (evidence / f"decoded-{label}-stderr.txt").write_bytes(decoded.stderr)
                decoded.check_returncode()
                if not image_path.is_file() or image_path.stat().st_size == 0:
                    raise ValueError("Actual recorded frame failed to decode")
        finally:
            server.shutdown()
            worker.join(timeout=10)
    reader.assert_unchanged()
    after_hash = reader.sha256()
    video_timing = next(track for track in timing.tracks if track.handler_type == "vide")
    audio_timing = next(track for track in timing.tracks if track.handler_type == "soun")
    gates = {
        "source_unchanged": before_hash == after_hash,
        "no_transport_error": not server.transport_failed,
        "metadata_consistent": timing.consistent,
        "resolution": f'{video["width"]}x{video["height"]}' == run["resolution"],
        "ten_bit_hevc": video["codec_name"] == "hevc" and video["profile"] == "Main 10"
                        and video["pix_fmt"] == "yuv420p10le",
        "sdr_colour": video.get("color_primaries") == "bt709" and video.get("color_transfer") == "bt709",
        "frame_count": video_timing.stts.expanded_sample_count == run["encoder_submitted_frames"]
                       == run["source_recorded_frames"] == int(video["nb_frames"]),
        "exact_25fps_dts": all(item.sample_delta_units * 25 == video_timing.media_timescale
                               for item in video_timing.stts.runs),
        "no_reordered_frames": video.get("has_b_frames") == 0,
        "bitrate": int(video["bit_rate"]) >= 158400000,
        "duration": duration >= 300 and run["measured_wait_seconds"] >= 300,
        "audio": int(audio["sample_rate"]) == 48000 and audio["channels"] == 2,
        "terminal_av_difference": abs(video_timing.stts.total_duration_seconds -
                                      audio_timing.stts.total_duration_seconds) <= 0.020001,
        "source_cadence": run["source_cadence_evidence_valid"] is True,
        "source_zero_drops": run["source_dropped_recording_frames"] == 0 and
                             run["source_unrecovered_timestamp_gap_frames"] == 0,
        "profile": run["detail_profile_evidence_valid"] is True,
        "run_gates": all(run.get(key) is True for key in (
            "cadence_evidence_valid", "ten_bit_evidence_valid", "audio_evidence_valid",
            "duration_evidence_valid", "quality_evidence_not_failed", "planned_stop_origin_valid")),
    }
    summary = {"status": "PASS" if all(gates.values()) else "FAIL", "gates": gates,
               "scope": "Five-minute recording transport and three actual decoded frames; calibrated quality and physical A/V sync are not proven",
               "candidate_apk_sha256": args.apk_sha256, "device_serial": args.serial,
               "physical_serial": args.physical_serial, "source": source,
               "source_sha256_before": before_hash, "source_sha256_after": after_hash,
               "video": video, "audio": audio, "decoded_frames": 3,
               "thermal_boundaries": [run["thermal_before_status"], run["thermal_after_status"]],
               "continuous_thermal_proof": False, "production_ready": False,
               "evidence_directory": str(evidence), "http_delivered_bytes": server.delivered_bytes}
    save_json(evidence / "summary.json", summary)
    print(json.dumps({"event": "INDEPENDENT_AUDIT", "status": summary["status"],
                      "evidence": str(evidence), "gates": gates}), flush=True)
    return summary


def inspect_and_record(args, run_directory: Path) -> dict:
    """Keep failed audit attempts as evidence, including missing original media.

    The caller must already own ExclusiveAdbLease and have stopped the camera.
    An ERROR is not a recording FAIL or PASS: the original-file proof is absent.
    """
    try:
        return inspect(args, run_directory)
    except Exception as failure:
        error_path = run_directory / (
            "independent-error-" + datetime.now().strftime("%Y%m%d-%H%M%S-%f") + ".json"
        )
        result = {
            "status": "ERROR",
            "scope": "Independent verification incomplete; no recording or quality PASS inferred",
            "candidate_apk_sha256": args.apk_sha256,
            "device_serial": args.serial,
            "physical_serial": args.physical_serial,
            "run_directory": str(run_directory),
            "error_type": type(failure).__name__,
            "error": str(failure),
            "production_ready": False,
            "error_evidence": str(error_path),
        }
        # Never replace a successful earlier audit or suppress the new failure.
        with error_path.open("x", encoding="utf-8") as target:
            target.write(json.dumps(result, indent=2) + "\n")
        print(json.dumps({"event": "INDEPENDENT_AUDIT_ERROR", **result}), flush=True)
        return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--physical-serial", required=True)
    parser.add_argument("--apk-sha256", required=True)
    parser.add_argument("--run-directory", type=Path, action="append", required=True)
    args = parser.parse_args()
    failed = False
    with ExclusiveAdbLease(None, "root-inline-direct-independent-audit", args.serial):
        observed = subprocess.check_output([args.adb, "-s", args.serial, "shell", "getprop", "ro.serialno"],
                                           text=True, timeout=20).strip()
        if observed != args.physical_serial:
            raise ValueError("Physical device identity mismatch")
        live = subprocess.run([args.adb, "-s", args.serial, "shell", "pidof", "com.neoproc.neoproccamx"],
                              capture_output=True, text=True, timeout=20)
        if live.stdout.strip():
            raise ValueError("Camera app must be stopped before physical media inspection")
        for run_directory in args.run_directory:
            failed |= inspect_and_record(args, run_directory)["status"] != "PASS"
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
