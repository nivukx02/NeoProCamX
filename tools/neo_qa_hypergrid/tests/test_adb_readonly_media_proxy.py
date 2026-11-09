"""Host transport-contract tests only; not evidence of camera/codec performance."""

import importlib.util
import http.client
from pathlib import Path
import shlex
import threading
import time
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "adb_readonly_media_proxy", Path(__file__).resolve().parents[1] / "adb_readonly_media_proxy.py"
)
proxy = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(proxy)


class ReadOnlyRangeContractTest(unittest.TestCase):
    def test_whole_file(self):
        self.assertEqual((0, 999, False), proxy.parse_range(None, 1000))

    def test_explicit_range(self):
        self.assertEqual((400, 699, True), proxy.parse_range("bytes=400-699", 1000))

    def test_open_ended_range(self):
        self.assertEqual((400, 999, True), proxy.parse_range("bytes=400-", 1000))

    def test_end_clamped_to_real_source(self):
        self.assertEqual((900, 999, True), proxy.parse_range("bytes=900-2000", 1000))

    def test_suffix(self):
        self.assertEqual((900, 999, True), proxy.parse_range("bytes=-100", 1000))
        self.assertEqual((0, 999, True), proxy.parse_range("bytes=-2000", 1000))

    def test_large_file_preserves_64_bit_offsets(self):
        start = 7 * 1024**3
        self.assertEqual((start, start + 255, True),
                         proxy.parse_range(f"bytes={start}-{start + 255}", start + 256))

    def test_invalid_ranges_fail_closed(self):
        for value in ("bytes=-0", "bytes=-", "bytes=1000-", "bytes=2-1", "bytes=0-3,7-9",
                      "items=0-3", "bytes=NaN-1", "bytes=0-3\n", "bytes=--1"):
            with self.subTest(value=value), self.assertRaises(ValueError):
                proxy.parse_range(value, 1000)

    def test_empty_or_negative_size_rejected(self):
        for size in (0, -1):
            with self.assertRaises(ValueError):
                proxy.parse_range(None, size)

    def test_only_camx_media_admitted(self):
        path = "/sdcard/DCIM/CamX/Camera clip.mov"
        self.assertEqual(path, proxy.validate_source(path))
        for invalid in ("/data/private.mov", "/sdcard/DCIM/other.mov",
                        "/sdcard/DCIM/CamX/../private.mov", "/sdcard/DCIM/CamX/file.xml",
                        "/sdcard/DCIM/CamX/file\n.mov", "/sdcard/DCIM/CamX/./clip.mov"):
            with self.subTest(path=invalid), self.assertRaises(ValueError):
                proxy.validate_source(invalid)

    def test_shell_metacharacters_are_one_quoted_input_filename(self):
        source = "/sdcard/DCIM/CamX/clip '$name;$(id).mov"
        command = shlex.split(proxy.read_command(source, 17, 29))
        self.assertEqual("dd", command[0])
        self.assertEqual("if=" + source, command[1])
        self.assertEqual(["bs=4194304", "skip=17", "count=29",
                          "iflag=skip_bytes,count_bytes", "status=none"], command[2:])
        self.assertFalse(any(part.startswith(("of=", "conv=")) for part in command))

    def test_unbounded_reads_rejected(self):
        for offset, count in ((-1, 1), (0, 0), (0, proxy.MAX_CHUNK_BYTES + 1)):
            with self.assertRaises(ValueError):
                proxy.read_command("/sdcard/DCIM/CamX/test.mov", offset, count)

    def test_truncated_physical_data_is_not_zero_padded(self):
        reader = object.__new__(proxy.AdbMediaReader)
        reader.source = "/sdcard/DCIM/CamX/test.mov"
        reader.size = 1000
        reader.command = lambda command: b"abc"
        with self.assertRaisesRegex(RuntimeError, "Short physical read"):
            reader.read(0, 4)
        self.assertEqual(b"abc", reader.read(0, 3))

    def test_read_outside_admitted_file_rejected(self):
        reader = object.__new__(proxy.AdbMediaReader)
        reader.size = 10
        with self.assertRaises(ValueError):
            reader.read(9, 2)

    def test_source_changed_is_an_error(self):
        reader = object.__new__(proxy.AdbMediaReader)
        reader.identity = "1000:4:55"
        reader.snapshot = lambda: "1000:5:55"
        with self.assertRaisesRegex(RuntimeError, "identity/size/mtime changed"):
            reader.assert_unchanged()

    def test_concurrent_http_readers_never_run_concurrent_adb_commands(self):
        reader = object.__new__(proxy.AdbMediaReader)
        reader.command_lock = threading.RLock()
        reader.adb = "adb"
        reader.serial = "qa-test"
        active = 0
        peak = 0
        observations = threading.Lock()

        def fake_subprocess(*args, **kwargs):
            nonlocal active, peak
            with observations:
                active += 1
                peak = max(peak, active)
            time.sleep(0.002)
            with observations:
                active -= 1
            return proxy.subprocess.CompletedProcess(args[0], 0, b"test-transport")

        with patch.object(proxy.subprocess, "run", side_effect=fake_subprocess):
            readers = [threading.Thread(target=reader.command, args=("test read",)) for _ in range(20)]
            for worker in readers:
                worker.start()
            for worker in readers:
                worker.join(timeout=2)
                self.assertFalse(worker.is_alive())
        self.assertEqual(1, peak)
        self.assertEqual(0, active)


class LoopbackHttpIntegrationTest(unittest.TestCase):
    def setUp(self):
        self.payload = bytes(range(256)) * 13
        reader = object.__new__(proxy.AdbMediaReader)
        reader.size = len(self.payload)
        reader.assert_unchanged = lambda: None
        reader.read = lambda offset, count: self.payload[offset:offset + count]
        self.reader = reader
        self.server = proxy.ThreadingHTTPServer(("127.0.0.1", 0), proxy.make_handler(reader, "test-capability"))
        self.server.transport_failed = False
        self.server.delivered_bytes = 0
        self.server.stats_lock = threading.Lock()
        self.server.finish_requested = False
        self.worker = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.02})
        self.worker.start()

    def tearDown(self):
        self.server.shutdown()
        self.worker.join(timeout=2)
        self.server.server_close()
        self.assertFalse(self.worker.is_alive())

    def request(self, method="GET", path="/test-capability/media", range_value=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=2)
        try:
            headers = {"Range": range_value} if range_value else {}
            connection.request(method, path, headers=headers)
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def test_full_body_matches_test_transport_fixture_exactly(self):
        status, headers, body = self.request()
        self.assertEqual(200, status)
        self.assertEqual(str(len(self.payload)), headers["Content-Length"])
        self.assertEqual(self.payload, body)
        self.assertEqual(len(self.payload), self.server.delivered_bytes)

    def test_range_body_and_headers_match_exact_bytes(self):
        status, headers, body = self.request(range_value="bytes=17-288")
        self.assertEqual(206, status)
        self.assertEqual(f"bytes 17-288/{len(self.payload)}", headers["Content-Range"])
        self.assertEqual(self.payload[17:289], body)

    def test_head_does_not_read_payload(self):
        status, headers, body = self.request(method="HEAD")
        self.assertEqual(200, status)
        self.assertEqual(b"", body)
        self.assertEqual(0, self.server.delivered_bytes)
        self.assertEqual(str(len(self.payload)), headers["Content-Length"])

    def test_bad_range_rejected(self):
        status, headers, body = self.request(range_value="bytes=9999-")
        self.assertEqual(416, status)
        self.assertEqual(f"bytes */{len(self.payload)}", headers["Content-Range"])
        self.assertEqual(b"", body)

    def test_wrong_capability_cannot_read_or_finish(self):
        self.assertEqual(404, self.request(path="/wrong/media")[0])
        self.assertEqual(405, self.request(method="POST", path="/wrong/finish")[0])
        self.assertFalse(self.server.finish_requested)
        self.assertEqual(0, self.server.delivered_bytes)

    def test_http_media_write_refused(self):
        self.assertEqual(405, self.request(method="POST")[0])
        self.assertFalse(self.server.finish_requested)
        self.assertEqual(0, self.server.delivered_bytes)

    def test_explicit_finish_only_terminates_helper(self):
        self.assertEqual(200, self.request(method="POST", path="/test-capability/finish")[0])
        self.assertTrue(self.server.finish_requested)
        self.assertEqual(0, self.server.delivered_bytes)

    def test_changed_source_is_fail_closed_not_empty_success(self):
        def changed():
            raise RuntimeError("injected source mutation")
        self.reader.assert_unchanged = changed
        self.assertEqual(502, self.request()[0])
        self.assertTrue(self.server.transport_failed)
        self.assertEqual(0, self.server.delivered_bytes)


class ControllerLeaseIntegrationTest(unittest.TestCase):
    arguments = ["media-proxy", "--adb", "adb", "--serial", "fixture-device",
                 "--source", "/sdcard/DCIM/CamX/test.mov", "--expected-size", "64"]

    def test_main_holds_canonical_lease_before_any_adb_access(self):
        with patch("sys.argv", self.arguments), patch.object(proxy, "ExclusiveAdbLease") as lease:
            with patch.object(proxy, "serve_under_lease", return_value=0) as serve:
                self.assertEqual(0, proxy.main())
                lease.assert_called_once_with(None, "root-inline-readonly-media-proxy", "fixture-device")
                lease.return_value.__enter__.assert_called_once()
                serve.assert_called_once()
                lease.return_value.__exit__.assert_called_once()

    def test_occupied_lease_prevents_media_access(self):
        with patch("sys.argv", self.arguments), patch.object(proxy, "ExclusiveAdbLease") as lease:
            lease.return_value.__enter__.side_effect = RuntimeError("ADB already owned")
            with patch.object(proxy, "serve_under_lease") as serve:
                with self.assertRaisesRegex(RuntimeError, "already owned"):
                    proxy.main()
                serve.assert_not_called()

    def test_media_failure_still_releases_lease(self):
        with patch("sys.argv", self.arguments), patch.object(proxy, "ExclusiveAdbLease") as lease:
            with patch.object(proxy, "serve_under_lease", side_effect=RuntimeError("read failed")):
                with self.assertRaisesRegex(RuntimeError, "read failed"):
                    proxy.main()
                lease.return_value.__exit__.assert_called_once()


if __name__ == "__main__":
    unittest.main()
