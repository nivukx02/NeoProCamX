"""Reader-handshake tests only; these are not physical recording evidence."""
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest import TestCase, main
from unittest.mock import Mock, patch

from tools.neo_qa_hypergrid import audit_lq001_cinema_master as audit


class ActualMasterLiveReaderTests(TestCase):
    def exercise(self, acknowledged_call=None, ended=False):
        with TemporaryDirectory() as directory:
            root = Path(directory)
            process = Mock()
            process.poll.return_value = 1 if ended else None
            clock = [0.0]
            markers = []

            def sleep(seconds):
                clock[0] += seconds

            def invoke(command):
                markers.append(command[-1])
                if len(markers) == acknowledged_call:
                    with (root / "actual-master-live-logcat.txt").open("ab") as output:
                        output.write((command[-1] + "\n").encode())

            with patch.object(audit.subprocess, "Popen", return_value=process), \
                 patch.object(audit.time, "monotonic", side_effect=lambda: clock[0]), \
                 patch.object(audit.time, "sleep", side_effect=sleep):
                try:
                    with audit.capture_live_audit_log(Path("SDK/platform-tools/adb.exe"),
                                                      "physical-test-target", root, invoke) as (path, marker):
                        self.assertIn(marker, path.read_text())
                finally:
                    self.assertTrue((root / "commands.jsonl").is_file())
                    if not ended:
                        process.terminate.assert_called_once()
                        process.wait.assert_called_once_with(timeout=5)
            self.assertEqual(1, len(set(markers)), "Retries must use only this run's nonce")
            return markers, clock[0]

    def test_attached_reader_needs_no_reannouncement(self):
        markers, _ = self.exercise(acknowledged_call=1)
        self.assertEqual(1, len(markers))

    def test_marker_lost_before_attachment_is_reannounced(self):
        markers, elapsed = self.exercise(acknowledged_call=2)
        self.assertEqual(2, len(markers))
        self.assertLess(elapsed, 5.0)

    def test_unacknowledged_reader_does_not_admit_instrumentation(self):
        with self.assertRaisesRegex(RuntimeError, "did not acknowledge"):
            self.exercise()

    def test_exited_reader_fails_before_instrumentation(self):
        with self.assertRaisesRegex(RuntimeError, "ended before admission"):
            self.exercise(ended=True)


if __name__ == "__main__":
    main()
