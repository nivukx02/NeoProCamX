"""Audit an actual completed Cinema recording. Never simulate frames or edit the ledger.

Uses the installed Android tests to inspect every real frame header and export three exact
P010 frames. Independently parses/CRC-checks the captured AAC companion and decodes all packets.
No recording, source deletion, thermal override, or production-readiness claim is performed.
"""
from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import datetime
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import struct
import subprocess
import time
import uuid
import zlib

from tools.neo_qa_hypergrid.device_orchestrator import ExclusiveAdbLease


PACKAGE = "com.neoproc.neoproccamx"
SDK_ADB = Path(r"C:\Users\ELCOT\AppData\Local\Android\Sdk\platform-tools\adb.exe")
AUDIT_TAG = "Npv10ActualMasterAudit"


@contextmanager
def capture_live_audit_log(adb_path: Path, serial: str, root: Path, invoke_adb):
    """Retain proof while Android emits it, not after a noisy shared ring can evict it.

    This passive stream belongs to the caller's existing exclusive ADB lease. A unique
    shell-log marker proves the reader is connected before instrumentation is launched.
    No log buffer is cleared and no missing sample is replaced by old-run evidence.
    """
    command = [str(adb_path), "-s", serial, "logcat", "-T", "1", "-v", "threadtime",
               "-s", AUDIT_TAG + ":I", "*:S"]
    log_path = root / "actual-master-live-logcat.txt"
    marker = "NEO_QA_AUDIT_READER_READY_" + uuid.uuid4().hex
    process = None
    try:
        with log_path.open("xb") as stdout, (root / "actual-master-live-logcat-stderr.txt").open("xb") as stderr:
            try:
                process = subprocess.Popen(command, stdout=stdout, stderr=stderr)
                invoke_adb(["shell", "log", "-p", "i", "-t", AUDIT_TAG, marker])
                deadline = time.monotonic() + 5.0
                next_marker = time.monotonic() + 0.25
                while marker not in log_path.read_text(encoding="utf-8", errors="replace"):
                    if process.poll() is not None:
                        raise RuntimeError("Live original-audit log reader ended before admission")
                    if time.monotonic() >= deadline:
                        raise RuntimeError("Live original-audit log reader did not acknowledge its unique marker")
                    # Popen returning does not mean logd has attached its reader. With -T 1,
                    # unrelated startup logs can evict the first marker before attachment.
                    # Reannounce only this run's nonce until the live reader acknowledges it;
                    # retain the same deadline and never admit using an old logcat snapshot.
                    if time.monotonic() >= next_marker:
                        invoke_adb(["shell", "log", "-p", "i", "-t", AUDIT_TAG, marker])
                        next_marker = time.monotonic() + 0.25
                    time.sleep(0.05)
                yield log_path, marker
                if process.poll() is not None:
                    raise RuntimeError("Live original-audit log reader ended during instrumentation")
                # The command channel's test completion and logd's output channel are independent.
                # Missing proof still fails after this bounded delivery allowance.
                time.sleep(0.25)
            finally:
                if process is not None and process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
    finally:
        with (root / "commands.jsonl").open("a", encoding="utf-8") as log:
            log.write(json.dumps({"time": datetime.now().isoformat(), "command": command,
                                  "reader_marker": marker, "passive_stream": True,
                                  "exit": None if process is None else process.poll(),
                                  "reader_stopped_by_controller": process is not None}) + "\n")


def select_actual_recording_evidence(logs: str, master_name: str, expected_frames: int):
    """Bind the latest complete export and timeline to the exact real master and session."""
    selected = [dict(re.findall(r"(\w+)=([^\s]+)", line)) | {"_line": line}
                for line in logs.splitlines() if "ACTUAL_" in line]
    selected = [item for item in selected if item.get("master") == master_name]
    exports = [item for item in selected if "ACTUAL_RECORDED_SAMPLE" in item["_line"]]
    if not exports:
        raise ValueError("Required real frame-export log proof is unavailable")
    newest_directory = PurePosixPath(exports[-1].get("path", "")).parent
    latest = [item for item in exports if PurePosixPath(item.get("path", "")).parent == newest_directory]
    samples = []
    for name, index in (("first", 0), ("middle", expected_frames // 2), ("last", expected_frames - 1)):
        matches = [item for item in latest if item.get("name") == name]
        if len(matches) != 1:
            raise ValueError("Latest real export lacks exactly one first/middle/last sample; do not reuse older proof")
        sample = dict(matches[0])
        sample.pop("_line")
        if (int(sample.get("index", -1)) != index or
                PurePosixPath(sample.get("path", "")).name != name + ".p010" or
                not re.fullmatch(r"[0-9a-f]{64}", sample.get("sha256", ""))):
            raise ValueError("Real exported sample identity/index/hash is invalid")
        samples.append(sample)
    timelines = [item for item in selected if "ACTUAL_COMPLETE_FRAME_TIMELINE" in item["_line"]]
    if not timelines:
        raise ValueError("Complete actual timeline is unavailable")
    timeline = dict(timelines[-1])
    timeline.pop("_line")
    if int(timeline.get("frames", -1)) != expected_frames:
        raise ValueError("Complete actual timeline disagrees with the recorded frame count")
    return samples, timeline


def original_proof_from_instrumentation(output: str) -> str:
    """Accept only the explicit status key from this invocation, never old logcat text.

    The caller still validates every master/index/hash and the actual test outcome. Failed
    timeline assertions may emit useful failure evidence; this parser never turns them into PASS.
    """
    prefix = "INSTRUMENTATION_STATUS: neoNpv10OriginalProof="
    lines = [line[len(prefix):] for line in output.splitlines() if line.startswith(prefix)]
    if not lines or any(not line.startswith(("ACTUAL_RECORDED_SAMPLE ",
                                            "ACTUAL_COMPLETE_FRAME_TIMELINE ")) for line in lines):
        raise ValueError("This instrumentation invocation did not emit valid original-file proof")
    return "\n".join(lines)


def fields(path: Path) -> dict[str, str]:
    return dict(line.split("=", 1) for line in path.read_text(encoding="utf-8-sig").splitlines()
                if "=" in line)


def audit_captured_audio(data: bytes, expected_origin_ns: int) -> tuple[dict, bytes]:
    """Parse actual NPV10A1 bytes strictly, including their durable end marker."""
    position = 0

    def take(size: int) -> bytes:
        nonlocal position
        if size < 0 or position + size > len(data):
            raise ValueError("Truncated captured AAC companion")
        part = data[position:position + size]
        position += size
        return part

    if take(8) != b"NPV10A1\x00":
        raise ValueError("Captured audio magic mismatch; shell output is not media")
    version, rate, channels, bitrate, origin, size = struct.unpack(">iiiiqi", take(28))
    if (version, rate, channels, bitrate) != (1, 48000, 2, 256000):
        raise ValueError("Actual audio does not match the selected version/rate/channels/bitrate")
    if origin != expected_origin_ns or not 1 <= size <= 4096:
        raise ValueError("Audio companion does not match the recorded sensor origin")
    config = take(size)
    if len(config) < 2:
        raise ValueError("Incomplete AudioSpecificConfig")
    value, width = int.from_bytes(config, "big"), len(config) * 8
    obj, frequency, channel_config = (value >> (width - 5)) & 31, (value >> (width - 9)) & 15, (value >> (width - 13)) & 15
    if (obj, frequency, channel_config) != (2, 3, 2):
        raise ValueError("Expected actual AAC-LC, 48 kHz, stereo codec configuration")
    count = total = 0
    first = last = -1
    maximum_interval = 0
    adts = bytearray()
    while True:
        magic = take(4)
        if magic == b"AEND":
            footer_count, end, footer_bytes = struct.unpack(">qqq", take(24))
            if (count == 0 or footer_count != count or footer_bytes != total
                    or end <= last or position != len(data)):
                raise ValueError("Invalid, incomplete or nonterminal captured audio footer")
            break
        if magic != b"AAC0":
            raise ValueError("Invalid captured AAC packet marker")
        pts, flags, size, crc = struct.unpack(">qiiI", take(20))
        if pts < 0 or pts <= last or not 1 <= size <= 65536 or flags & 2:
            raise ValueError("Invalid captured AAC PTS/packet/codec-config flag")
        payload = take(size)
        if zlib.crc32(payload) != crc:
            raise ValueError("Actual captured AAC CRC mismatch")
        length = size + 7
        if length > 8191:
            raise ValueError("Captured AAC unit exceeds supported lossless ADTS wrapping")
        adts.extend(bytes((255, 241, ((obj - 1) << 6) | (frequency << 2) | (channels >> 2),
                           ((channels & 3) << 6) | (length >> 11), (length >> 3) & 255,
                           ((length & 7) << 5) | 31, 252)))
        adts.extend(payload)
        if first < 0:
            first = pts
        else:
            maximum_interval = max(maximum_interval, pts - last)
        count += 1
        total += size
        last = pts
    return {
        "captured_audio_sha256": hashlib.sha256(data).hexdigest(),
        "packets": count, "payload_bytes": total, "every_packet_crc_valid": True,
        "strict_pts_monotonic": True, "first_pts_us": first, "last_pts_us": last,
        "audio_end_us": end, "sample_rate": rate, "channel_count": channels,
        "requested_bitrate": bitrate, "sensor_video_origin_ns": origin,
        "maximum_audio_pts_interval_us": maximum_interval,
        "expected_pcm_bytes": count * 1024 * channels * 2,
        "physical_av_sync": "NOT_PROVEN_REQUIRES_PHYSICAL_MARKERS",
    }, bytes(adts)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", type=Path, default=SDK_ADB)
    parser.add_argument("--serial", default="10BF8M2NDX003P3")
    parser.add_argument("--apk-sha256", required=True)
    parser.add_argument("--test-apk-sha256", required=True)
    parser.add_argument("--proof-channel", choices=("logcat", "instrumentation"), default="logcat",
                        help="Instrumentation requires the installed explicit original-proof status key")
    parser.add_argument("--run-directory", required=True, type=Path)
    parser.add_argument("--evidence-directory", required=True, type=Path)
    parser.add_argument("--ffmpeg", type=Path, default=Path("tools/ffmpeg/ffmpeg-8.1.1-essentials_build/bin/ffmpeg.exe"))
    args = parser.parse_args()
    if args.adb.resolve() != SDK_ADB.resolve():
        raise ValueError("Use the configured SDK ADB only")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", args.apk_sha256):
        raise ValueError("An exact APK SHA-256 is required")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", args.test_apk_sha256):
        raise ValueError("An exact test APK SHA-256 is required")
    run = json.loads((args.run_directory / "run-result.json").read_text(encoding="utf-8-sig"))
    evidence = fields(args.run_directory / "npv10-evidence.txt")
    certificate = fields(args.run_directory / "quality-certificate.txt")
    master = PurePosixPath(run["private_master_path"])
    if master.parent.as_posix() != "files/CamXNPV10" or master.suffix != ".npv10":
        raise ValueError("Only an exact private original master may be inspected")
    if run["pipeline"] != "CINEMA" or run["fps"] != 25:
        raise ValueError("This auditor is for the selected 25-FPS Cinema contract")
    frame_count = int(evidence["frameCount"])
    original_bytes = int(evidence["fileBytes"])
    if frame_count <= 2 or original_bytes <= 0:
        raise ValueError("No complete original recording")
    root = args.evidence_directory
    root.mkdir(parents=True, exist_ok=False)

    def adb(command: list[str], name: str | None = None, timeout: int = 55,
            checked: bool = True) -> str:
        process = subprocess.run([str(args.adb), "-s", args.serial, *command],
                                 capture_output=True, timeout=timeout)
        stdout, stderr = process.stdout.decode("utf-8", errors="replace"), process.stderr.decode("utf-8", errors="replace")
        with (root / "commands.jsonl").open("a", encoding="utf-8") as log:
            log.write(json.dumps({"time": datetime.now().isoformat(), "command": command,
                                  "exit": process.returncode, "stdout": stdout, "stderr": stderr}) + "\n")
        if name:
            (root / name).write_text(stdout + "\n" + stderr, encoding="utf-8")
        if checked and process.returncode:
            raise RuntimeError(f"ADB command failed: {command}: {stderr}")
        return stdout.strip()

    def retain(remote: str, name: str, size: int, magic: bytes | None = None,
               expected_sha: str | None = None) -> bytes:
        process = subprocess.run([str(args.adb), "-s", args.serial, "exec-out", "run-as",
                                  PACKAGE, "cat", remote], capture_output=True, timeout=100)
        blob = process.stdout
        sha = hashlib.sha256(blob).hexdigest()
        valid = (process.returncode == 0 and len(blob) == size and
                 (magic is None or blob.startswith(magic)) and
                 (expected_sha is None or sha == expected_sha))
        with (root / "commands.jsonl").open("a", encoding="utf-8") as log:
            log.write(json.dumps({"time": datetime.now().isoformat(),
                                  "command": ["exec-out", "run-as", PACKAGE, "cat", remote],
                                  "exit": process.returncode, "bytes": len(blob), "sha256": sha,
                                  "valid": valid, "stderr": process.stderr.decode(errors="replace")}) + "\n")
        (root / (name if valid else name + ".rejected")).write_bytes(blob)
        if not valid:
            raise ValueError("Private media size/magic/hash validation failed; not evidence of a valid file")
        return blob

    result = {"status": "FAIL", "scope": "Five-minute transport only, not full checkpoint or production readiness",
              "candidate_apk_sha256": args.apk_sha256, "device_serial": args.serial,
              "run_directory": str(args.run_directory), "source_original_retained": True,
              "files_changed": [], "physical_av_sync": "NOT_PROVEN"}
    with ExclusiveAdbLease(None, "root-inline-real-cinema-original-audit", args.serial):
        try:
            if adb(["shell", "getprop", "ro.serialno"], "device-serial.txt") != args.serial:
                raise ValueError("Wrong physical device")
            if adb(["shell", "pidof", PACKAGE], checked=False):
                raise ValueError("Camera app is not idle")
            apk_path = adb(["shell", "pm", "path", PACKAGE], "installed-apk-path.txt")
            if not apk_path.startswith("package:") or "\n" in apk_path:
                raise ValueError("Expected one installed APK")
            installed_hash = adb(["shell", "sha256sum", apk_path.removeprefix("package:")], "installed-apk-sha256.txt").split()[0]
            if installed_hash.upper() != args.apk_sha256.upper():
                raise ValueError("Installed APK identity mismatch")
            test_path = adb(["shell", "pm", "path", PACKAGE + ".test"], "installed-test-apk-path.txt")
            if not test_path.startswith("package:") or "\n" in test_path:
                raise ValueError("Expected one installed instrumentation APK")
            test_hash = adb(["shell", "sha256sum", test_path.removeprefix("package:")], "installed-test-apk-sha256.txt").split()[0]
            if test_hash.upper() != args.test_apk_sha256.upper():
                raise ValueError("Installed instrumentation APK identity mismatch")
            result["test_apk_sha256"] = test_hash
            adb(["shell", "getprop", "ro.build.fingerprint"], "device-build.txt")
            adb(["shell", "dumpsys", "thermalservice"], "thermal-before.txt")
            source = master.as_posix()
            before = adb(["shell", "run-as", PACKAGE, "stat", "-c", "%s:%Y", source], "source-stat-before.txt")
            if int(before.split(":")[0]) != original_bytes:
                raise ValueError("Original no longer matches recording evidence")
            adb(["shell", "input", "keyevent", "KEYCODE_WAKEUP"])
            adb(["shell", "wm", "dismiss-keyguard"], checked=False)
            test = "com.neoproc.neoproccamx.recording.Npv10RecordedMasterAuditInstrumentedTest#"
            classes = test + "retainedRealMasterExportsThreeBitExactReviewFrames," + test + "retainedRealMasterFrameHeadersProveSelectedCadence"
            command = ["shell", "am", "instrument", "-w", "-r", "-e", "class", classes,
                              "-e", "neoNpv10MasterRelativePath", source.removeprefix("files/"),
                              "-e", "neoNpv10ExpectedFrames", str(frame_count),
                              PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"]
            if args.proof_channel == "instrumentation":
                output = adb(command, "actual-master-instrumentation.txt", 300)
                logs = original_proof_from_instrumentation(output)
                result["log_evidence_source"] = "EXPLICIT_ORIGINAL_PROOF_STATUS_FROM_THIS_INSTRUMENTATION"
            else:
                with capture_live_audit_log(args.adb, args.serial, root, adb) as (live_path, marker):
                    output = adb(command, "actual-master-instrumentation.txt", 300)
                logs = live_path.read_text(encoding="utf-8", errors="strict").split(marker, 1)[1]
                result["log_evidence_source"] = "LIVE_READER_ACKNOWLEDGED_BEFORE_THIS_INSTRUMENTATION"
            adb(["logcat", "-d", "-v", "threadtime", "-s", AUDIT_TAG + ":I", "*:S"], "actual-master-logcat.txt")
            samples, timeline = select_actual_recording_evidence(logs, master.name, frame_count)
            for sample in samples:
                remote = PurePosixPath(sample["path"])
                if remote.is_absolute() or ".." in remote.parts or remote.parts[0] != "qa-recorded-frame-audits":
                    raise ValueError("Unexpected exported sample path")
                retain("files/" + str(remote), sample["name"] + ".p010", int(sample["bytes"]), expected_sha=sample["sha256"])
            result["timeline"] = timeline
            result["samples"] = samples
            audio_source = source + ".audio.npv10a"
            audio_stat = adb(["shell", "run-as", PACKAGE, "stat", "-c", "%s:%Y", audio_source], "audio-source-stat.txt")
            audio_sha = adb(["shell", "run-as", PACKAGE, "sha256sum", audio_source], "audio-source-sha256.txt").split()[0]
            captured = retain(audio_source, "captured-audio.npv10a", int(audio_stat.split(":")[0]), b"NPV10A1\x00", audio_sha)
            audio_report, aac = audit_captured_audio(captured, int(timeline["firstTimestampNs"]))
            (root / "captured-audio.aac").write_bytes(aac)
            command = [str(args.ffmpeg), "-nostdin", "-hide_banner", "-v", "error", "-xerror", "-err_detect", "explode",
                       "-i", str(root / "captured-audio.aac"), "-map", "0:a:0", "-f", "s16le", "pipe:1"]
            decoded = subprocess.run(command, capture_output=True, timeout=120)
            (root / "audio-decode-stderr.txt").write_bytes(decoded.stderr)
            audio_report.update({"decoder_exit": decoded.returncode, "decoder_stderr_bytes": len(decoded.stderr),
                                 "pcm_decode_bytes": len(decoded.stdout), "pcm_sha256": hashlib.sha256(decoded.stdout).hexdigest(),
                                 "video_declared_duration_us": frame_count * 40000,
                                 "terminal_difference_us": audio_report["audio_end_us"] - frame_count * 40000,
                                 "command": command})
            audio_ok = (decoded.returncode == 0 and not decoded.stderr and
                        len(decoded.stdout) == audio_report["expected_pcm_bytes"] and
                        abs(audio_report["terminal_difference_us"]) <= 40000)
            audio_report["status"] = "PASS" if audio_ok else "FAIL"
            (root / "audio-audit.json").write_text(json.dumps(audio_report, indent=2) + "\n", encoding="utf-8")
            print(json.dumps({"event": "REAL_FRAME_AND_AUDIO_AUDIT", "timeline": timeline, "audio": audio_report}), flush=True)
            # Close camera-free instrumentation before the large sequential hash read.
            adb(["shell", "am", "force-stop", PACKAGE])
            adb(["shell", "input", "keyevent", "KEYCODE_SLEEP"])
            source_hash = adb(["shell", "run-as", PACKAGE, "sha256sum", source], "source-sha256.txt", 420).split()[0]
            if not re.fullmatch(r"[0-9a-f]{64}", source_hash):
                raise ValueError("Complete original SHA-256 missing")
            after = adb(["shell", "run-as", PACKAGE, "stat", "-c", "%s:%Y", source], "source-stat-after.txt")
            if after != before:
                raise ValueError("Read-only audit changed the original")
            prep = float(certificate["npv10WriterStageMaxPreparedProcessingMs"])
            write = float(certificate["npv10DmcMaxAsyncRecordWriteMs"])
            gates = {
                "android_full_timeline_tests": "OK (2 tests)" in output and "FAILURES!!!" not in output,
                "frame_count": int(timeline["frames"]) == frame_count,
                "sensor_fps": abs(float(timeline["fps"]) - 25.0) <= 0.125,
                "no_frame_gap": int(timeline["intervalsAbove1p5FramePeriods"]) == 0 and int(evidence["droppedFrames"]) == 0,
                "full_original_integrity": all(evidence.get(k) == "true" for k in ("crcCheckPass", "sessionHashCheckPass", "byteCountCheckPass", "timestampCheckPass")),
                "preparation_deadline_40ms": prep <= 40.0,
                "async_write_deadline_40ms": write <= 40.0,
                "captured_audio": audio_ok,
                "runner_required_gates": all(run.get(k) is True for k in (
                    "recording_start_proven", "private_master_verified", "output_finalized",
                    "duration_evidence_valid", "ten_bit_evidence_valid", "audio_evidence_valid",
                    "detail_profile_evidence_valid", "thermal_evidence_valid", "rolling_10gib_evidence",
                    "planned_stop_origin_valid", "format_selection_evidence", "cadence_evidence_valid",
                    "source_cadence_evidence_valid", "encoded_cadence_evidence_valid",
                    "quality_evidence_not_failed", "niv99_offline_isolation_evidence_valid")),
            }
            result.update({"status": "PASS" if all(gates.values()) else "FAIL", "gates": gates,
                           "failed_gates": [name for name, passed in gates.items() if not passed],
                           "maximum_preparation_ms": prep, "maximum_async_write_ms": write,
                           "source_file_sha256": source_hash, "source_file_bytes": original_bytes,
                           "audio_audit": audio_report, "thermal_boundaries": [run["thermal_before_status"], run["thermal_after_status"]],
                           "continuous_status_4_proof": False})
        except BaseException as exc:
            result["error"] = str(exc)
            raise
        finally:
            # A server timeout must not suppress the second cleanup attempt or the failed
            # evidence record. Do not claim success when camera/screen cleanup is unconfirmed.
            cleanup_errors = []
            for command in (["shell", "am", "force-stop", PACKAGE],
                            ["shell", "input", "keyevent", "KEYCODE_SLEEP"]):
                try:
                    adb(command)
                except Exception as exc:
                    cleanup_errors.append(str(exc))
            result["cleanup_command_errors"] = cleanup_errors
            if cleanup_errors:
                result["status"] = "FAIL"
            (root / "independent-transport-audit.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result), flush=True)
    return 0 if result["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
