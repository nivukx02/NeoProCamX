"""Read-only, loopback-only HTTP ranges over one retained Android QA recording.

ffprobe/ffmpeg can seek in the actual recording without a multi-GB host copy.
This is transport for physical evidence, not a camera or codec simulation.
Run only after capture has stopped, under the exclusive SDK-ADB controller.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shlex
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

if __name__ == "__main__" and not __package__:
    from device_orchestrator import ExclusiveAdbLease
else:
    from tools.neo_qa_hypergrid.device_orchestrator import ExclusiveAdbLease


MAX_CHUNK_BYTES = 4 * 1024 * 1024


def parse_range(value: str | None, size: int) -> tuple[int, int, bool]:
    """Return inclusive bounds. Invalid/multipart/out-of-file ranges fail closed."""
    if size <= 0:
        raise ValueError("Media must be nonempty")
    if value is None:
        return 0, size - 1, False
    match = re.fullmatch(r"bytes=(\d*)-(\d*)", value)
    if match is None or not any(match.groups()):
        raise ValueError("Expected exactly one byte range")
    first, last = match.groups()
    if not first:
        suffix = int(last)
        if suffix <= 0:
            raise ValueError("Empty suffix range")
        return max(0, size - suffix), size - 1, True
    start = int(first)
    end = min(int(last), size - 1) if last else size - 1
    if start >= size or start > end:
        raise ValueError("Range outside retained media")
    return start, end, True


def validate_source(path: str) -> str:
    roots = (
        "/sdcard/DCIM/CamX/", "/storage/emulated/0/DCIM/CamX/",
        "/sdcard/Movies/CamX/", "/storage/emulated/0/Movies/CamX/",
        "/sdcard/Documents/CamX/", "/storage/emulated/0/Documents/CamX/",
    )
    if (not path.startswith(roots) or any(c in path for c in "\r\n\0")
            or any(part in (".", "..") for part in path.split("/"))
            or not path.lower().endswith((".mov", ".mp4", ".npraw", ".npv10"))):
        raise ValueError("Expected one absolute recording path inside a CamX media directory")
    return path


def read_command(source: str, offset: int, count: int) -> str:
    validate_source(source)
    if offset < 0 or not 0 < count <= MAX_CHUNK_BYTES:
        raise ValueError("Read exceeds the bounded transport window")
    # No output filename, mutation, shell substitution, or error-concealing dd options.
    return (f"dd if={shlex.quote(source)} bs={MAX_CHUNK_BYTES} "
            f"skip={offset} count={count} iflag=skip_bytes,count_bytes status=none")


class AdbMediaReader:
    def __init__(self, adb: str, serial: str, source: str, expected_size: int):
        self.command_lock = threading.RLock()
        self.adb = str(Path(adb).resolve(strict=True))
        if Path(self.adb).name.lower() not in ("adb.exe", "adb"):
            raise ValueError("Expected an SDK ADB executable")
        if not re.fullmatch(r"[A-Za-z0-9_.:-]+", serial):
            raise ValueError("Invalid device serial")
        self.serial = serial
        self.source = validate_source(source)
        self.identity = self.snapshot()
        self.size = int(self.identity.split(":", 1)[0])
        if self.size != expected_size or self.size <= 0:
            raise ValueError(f"Source size changed: expected {expected_size}, got {self.size}")

    def command(self, command: str, timeout: int = 60) -> bytes:
        # A media client may open its seek request before closing the previous socket.
        # HTTP sessions may coexist, but this single owner NEVER issues parallel ADB commands.
        with self.command_lock:
            result = subprocess.run(
                [self.adb, "-s", self.serial, "exec-out", command],
                stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True, timeout=timeout,
            )
        return result.stdout

    def snapshot(self) -> str:
        text = self.command(f"stat -c '%s:%Y:%i' {shlex.quote(self.source)}").decode().strip()
        if not re.fullmatch(r"\d+:\d+:\d+", text):
            raise RuntimeError(f"Cannot establish source identity: {text[:200]}")
        return text

    def sha256(self) -> str:
        output = self.command(f"sha256sum {shlex.quote(self.source)}", timeout=300).decode()
        match = re.match(r"^([a-fA-F0-9]{64})\s", output)
        if match is None:
            raise RuntimeError("Device did not return a valid source SHA-256")
        return match.group(1).lower()

    def assert_unchanged(self) -> None:
        if self.snapshot() != self.identity:
            raise RuntimeError("Source identity/size/mtime changed while inspecting recording")

    def read(self, offset: int, count: int) -> bytes:
        if offset < 0 or offset + count > self.size:
            raise ValueError("Read outside admitted recording")
        payload = self.command(read_command(self.source, offset, count))
        if len(payload) != count:
            raise RuntimeError(f"Short physical read: expected {count}, got {len(payload)}")
        return payload


def make_handler(reader: AdbMediaReader, token: str):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def setup(self):
            super().setup()
            self.connection.settimeout(15)

        def do_HEAD(self):
            self.serve_media(head_only=True)

        def do_GET(self):
            self.serve_media(head_only=False)

        def do_POST(self):
            # Only the unguessable loopback capability can terminate this host helper.
            # No HTTP method can create, overwrite, or delete Android media.
            self.close_connection = True
            if self.path != f"/{token}/finish":
                self.send_error(405)
                return
            self.server.finish_requested = True
            self.send_response(200)
            self.send_header("Content-Length", "0")
            self.send_header("Connection", "close")
            self.end_headers()

        def serve_media(self, head_only: bool):
            self.close_connection = True
            if self.path != f"/{token}/media":
                self.send_error(404)
                return
            try:
                start, end, partial = parse_range(self.headers.get("Range"), reader.size)
            except ValueError:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{reader.size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            try:
                reader.assert_unchanged()
            except (OSError, RuntimeError, subprocess.SubprocessError) as failure:
                self.server.transport_failed = True
                print(json.dumps({"event": "source_validation_failed", "error": str(failure)}), flush=True)
                self.send_error(502)
                return
            self.send_response(206 if partial else 200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(end - start + 1))
            self.send_header("Accept-Ranges", "bytes")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            if partial:
                self.send_header("Content-Range", f"bytes {start}-{end}/{reader.size}")
            self.end_headers()
            if head_only:
                return
            sent = 0
            try:
                while start + sent <= end:
                    count = min(MAX_CHUNK_BYTES, end - start - sent + 1)
                    payload = reader.read(start + sent, count)
                    try:
                        self.wfile.write(payload)
                        self.wfile.flush()
                    except TimeoutError:
                        # A paused/abandoned HTTP consumer is not a failed Android read.
                        # Preserve the event; ffprobe/ffmpeg must independently finish successfully.
                        self.close_connection = True
                        print(json.dumps({"event": "consumer_timeout", "offset": start + sent}), flush=True)
                        break
                    sent += count
                    with self.server.stats_lock:
                        self.server.delivered_bytes += count
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                # A media reader normally closes a range before opening a new seek.
                self.close_connection = True
            except (OSError, RuntimeError, ValueError, subprocess.SubprocessError) as failure:
                self.server.transport_failed = True
                self.close_connection = True
                print(json.dumps({"event": "physical_read_failed", "offset": start + sent,
                                  "error": str(failure)}), flush=True)
            finally:
                print(json.dumps({"event": "range", "start": start, "end": end,
                                  "delivered_bytes": sent, "head_only": False}), flush=True)

        def log_message(self, fmt, *args):
            pass

    return Handler


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--expected-size", type=int, required=True)
    parser.add_argument("--serve-seconds", type=int, default=600)
    args = parser.parse_args()
    if not 10 <= args.serve_seconds <= 1800:
        parser.error("serve-seconds must be in 10..1800")
    with ExclusiveAdbLease(None, "root-inline-readonly-media-proxy", args.serial):
        return serve_under_lease(args)


def serve_under_lease(args) -> int:
    reader = AdbMediaReader(args.adb, args.serial, args.source, args.expected_size)
    before_hash = reader.sha256()
    token = secrets.token_hex(16)
    with ThreadingHTTPServer(("127.0.0.1", 0), make_handler(reader, token)) as server:
        server.timeout = 1
        server.transport_failed = False
        server.delivered_bytes = 0
        server.stats_lock = threading.Lock()
        server.finish_requested = False
        print(json.dumps({"event": "ready", "pid": os.getpid(), "source": reader.source,
                          "serial": reader.serial, "size": reader.size, "source_sha256": before_hash,
                          "tool_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                          "adb_commands_serialized": True,
                          "canonical_global_adb_lease_held": True,
                          "read_only": True, "url": f"http://127.0.0.1:{server.server_port}/{token}/media",
                          "finish_url": f"http://127.0.0.1:{server.server_port}/{token}/finish"}), flush=True)
        deadline = time.monotonic() + args.serve_seconds
        try:
            while not server.finish_requested and time.monotonic() < deadline:
                server.handle_request()
        except KeyboardInterrupt:
            pass
    # server_close joins every outstanding handler before this final immutable-source audit.
    reader.assert_unchanged()
    after_hash = reader.sha256()
    unchanged = before_hash == after_hash
    passed = unchanged and not server.transport_failed and server.delivered_bytes > 0
    print(json.dumps({"event": "closed", "source_sha256_after": after_hash,
                      "source_unchanged": unchanged, "transport_failed": server.transport_failed,
                      "delivered_bytes": server.delivered_bytes,
                      "status": "PASS" if passed else "FAIL"}), flush=True)
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
