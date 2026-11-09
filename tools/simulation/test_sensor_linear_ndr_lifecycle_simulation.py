from __future__ import annotations

import threading
import unittest
from pathlib import Path

from sensor_linear_ndr_lifecycle_simulation import (
    NdrCallbackLineageModel,
    OwnedFrame,
    PublicationIdentity,
    RawResultLineageModel,
    SensorLinearNdrLifecycleModel,
    SessionPublicationGate,
    SubmissionResult,
    SubmissionStatus,
)


class SensorLinearNdrLifecycleSimulationTest(unittest.TestCase):
    def _run_thread(self, target) -> threading.Thread:
        thread = threading.Thread(target=target, daemon=True)
        thread.start()
        return thread

    def test_failure_during_accepted_pending_retires_native_tail_before_finish(self) -> None:
        model = SensorLinearNdrLifecycleModel()
        model.start(session_generation=41)
        frame = OwnedFrame(1)
        self.assertTrue(model.enqueue(frame))
        processor_entered, release_processor = threading.Event(), threading.Event()

        def submit(_frame: OwnedFrame) -> SubmissionResult:
            processor_entered.set()
            self.assertTrue(release_processor.wait(2.0))
            return SubmissionResult(SubmissionStatus.ACCEPTED_PENDING, completion_pending=True)

        worker = self._run_thread(lambda: model.run_worker_once(submit))
        self.assertTrue(processor_entered.wait(2.0))
        model.request_failure()
        self.assertTrue(model.active)
        release_processor.set()
        worker.join(2.0)
        self.assertFalse(worker.is_alive())
        self.assertEqual(1, frame.close_count)
        self.assertTrue(model.native_pending)
        self.assertFalse(model.stop_latch.is_set())

        model.run_worker_once(lambda _: self.fail("no post-failure submission"), lambda: True)
        self.assertTrue(model.stop_latch.is_set())
        self.assertEqual(1, model.finish_count)
        self.assertEqual(1, model.failure_callback_count)

    def test_failure_during_retry_pending_discards_frame_but_polls_prior_tail(self) -> None:
        model = SensorLinearNdrLifecycleModel()
        model.start(session_generation=42)
        frame = OwnedFrame(2)
        self.assertTrue(model.enqueue(frame))
        processor_entered, release_processor = threading.Event(), threading.Event()

        def submit(_frame: OwnedFrame) -> SubmissionResult:
            processor_entered.set()
            self.assertTrue(release_processor.wait(2.0))
            return SubmissionResult(SubmissionStatus.RETRY_PENDING, completion_pending=True)

        worker = self._run_thread(lambda: model.run_worker_once(submit))
        self.assertTrue(processor_entered.wait(2.0))
        model.request_failure()
        release_processor.set()
        worker.join(2.0)
        self.assertEqual(1, frame.close_count)
        self.assertEqual(1, frame.submission_count)
        self.assertTrue(model.native_pending)

        model.run_worker_once(lambda _: self.fail("discarded retry was resubmitted"), lambda: True)
        self.assertEqual(1, frame.submission_count)
        self.assertEqual(1, frame.close_count)
        self.assertTrue(model.stop_latch.is_set())

    def test_capacity_retry_retries_same_frame_without_native_poll(self) -> None:
        model = SensorLinearNdrLifecycleModel()
        model.start(session_generation=420)
        first = OwnedFrame(sequence=20, sensor_timestamp_ns=8_400_000_020)
        second = OwnedFrame(sequence=21, sensor_timestamp_ns=8_400_000_021)
        self.assertTrue(model.enqueue(first))
        self.assertTrue(model.enqueue(second))
        attempts: list[tuple[OwnedFrame, int, int]] = []
        native_polls = 0

        def poll_native() -> bool:
            nonlocal native_polls
            native_polls += 1
            return True

        def submit(frame: OwnedFrame) -> SubmissionResult:
            self.assertIs(frame, first, "FIFO advanced past the retained capacity retry")
            attempts.append((frame, frame.sequence, frame.sensor_timestamp_ns))
            if len(attempts) <= 3:
                return SubmissionResult(
                    SubmissionStatus.RETRY_PENDING,
                    completion_pending=False,
                )
            return SubmissionResult(SubmissionStatus.ACCEPTED_COMPLETE)

        for expected_attempt in range(1, 5):
            model.run_worker_once(submit, poll_native)
            self.assertEqual(expected_attempt, first.submission_count)
            if expected_attempt < 4:
                self.assertIs(model.retry_frame, first)
                self.assertFalse(model.native_pending)
                self.assertEqual(0, first.close_count)
                self.assertEqual(0, second.submission_count)

        self.assertEqual([first, first, first, first], [entry[0] for entry in attempts])
        self.assertEqual([20, 20, 20, 20], [entry[1] for entry in attempts])
        self.assertEqual(
            [8_400_000_020] * 4,
            [entry[2] for entry in attempts],
        )
        self.assertEqual(0, native_polls)
        self.assertEqual(1, first.close_count)
        self.assertEqual(4, first.submission_count)
        self.assertEqual(0, second.submission_count)

        model.run_worker_once(lambda frame: SubmissionResult(SubmissionStatus.ACCEPTED_COMPLETE))
        self.assertEqual(1, second.close_count)
        self.assertEqual(1, second.submission_count)

    def test_stop_and_destroy_during_capacity_retry_need_no_native_drain(self) -> None:
        for action in ("stop", "destroy"):
            with self.subTest(action=action):
                model = SensorLinearNdrLifecycleModel()
                model.start(session_generation=421)
                frame = OwnedFrame(sequence=22, sensor_timestamp_ns=8_410_000_022)
                self.assertTrue(model.enqueue(frame))
                model.run_worker_once(
                    lambda _: SubmissionResult(
                        SubmissionStatus.RETRY_PENDING,
                        completion_pending=False,
                    )
                )
                self.assertFalse(model.native_pending)
                self.assertIs(model.retry_frame, frame)

                if action == "stop":
                    model.request_stop(discard=True)
                else:
                    model.request_destroy()
                native_polls = 0

                def poll_native() -> bool:
                    nonlocal native_polls
                    native_polls += 1
                    return True

                model.run_worker_once(
                    lambda _: self.fail("discarded capacity retry was resubmitted"),
                    poll_native,
                )
                self.assertEqual(0, native_polls)
                self.assertEqual(1, frame.close_count)
                self.assertEqual(1, frame.submission_count)
                self.assertEqual(0, model.owned_frames)
                self.assertTrue(model.stop_latch.is_set())
                self.assertTrue(model.physically_quiescent())
                self.assertEqual(action == "destroy", model.destroyed)

    def test_capacity_timeout_releases_exact_retry_once_without_phantom_drain(self) -> None:
        model = SensorLinearNdrLifecycleModel()
        model.start(session_generation=422)
        frame = OwnedFrame(sequence=23, sensor_timestamp_ns=8_420_000_023)
        self.assertTrue(model.enqueue(frame))
        model.run_worker_once(
            lambda _: SubmissionResult(
                SubmissionStatus.RETRY_PENDING,
                completion_pending=False,
            )
        )

        self.assertTrue(model.request_retry_timeout(frame))
        self.assertFalse(model.request_retry_timeout(frame), "exact retry must have one release permit")
        self.assertEqual(1, frame.close_count)
        self.assertEqual(0, model.owned_frames)
        self.assertEqual(1, model.failure_callback_count)
        self.assertFalse(model.native_pending)
        native_polls = 0

        def poll_native() -> bool:
            nonlocal native_polls
            native_polls += 1
            return True

        model.run_worker_once(
            lambda _: self.fail("timed-out capacity retry was resubmitted"),
            poll_native,
        )
        self.assertEqual(0, native_polls)
        self.assertEqual(1, model.failure_callback_count)
        self.assertEqual(1, model.finish_count)
        self.assertTrue(model.physically_quiescent())

    def test_retry_with_older_native_tail_discards_retry_then_polls_tail(self) -> None:
        model = SensorLinearNdrLifecycleModel()
        model.start(session_generation=423)
        accepted = OwnedFrame(sequence=24, sensor_timestamp_ns=8_430_000_024)
        retry = OwnedFrame(sequence=25, sensor_timestamp_ns=8_430_000_025)
        self.assertTrue(model.enqueue(accepted))
        self.assertTrue(model.enqueue(retry))
        model.run_worker_once(
            lambda frame: SubmissionResult(
                SubmissionStatus.ACCEPTED_PENDING,
                completion_pending=True,
            )
            if frame is accepted
            else self.fail("second frame overtook accepted frame")
        )
        model.run_worker_once(
            lambda frame: SubmissionResult(
                SubmissionStatus.RETRY_PENDING,
                completion_pending=True,
            )
            if frame is retry
            else self.fail("retry identity changed")
        )
        self.assertTrue(model.native_pending)
        self.assertIs(model.retry_frame, retry)

        model.request_stop(discard=True)
        native_polls = 0

        def poll_native() -> bool:
            nonlocal native_polls
            native_polls += 1
            return True

        model.run_worker_once(
            lambda _: self.fail("discarded retry reached processor"),
            poll_native,
        )
        self.assertEqual(1, native_polls)
        self.assertEqual(1, accepted.close_count)
        self.assertEqual(1, retry.close_count)
        self.assertEqual(0, model.owned_frames)
        self.assertTrue(model.physically_quiescent())

    def test_capacity_retry_is_strict_fifo_until_exact_head_is_accepted(self) -> None:
        model = SensorLinearNdrLifecycleModel()
        model.start(session_generation=424)
        frames = [OwnedFrame(sequence=value, sensor_timestamp_ns=9_000 + value) for value in range(30, 33)]
        for frame in frames:
            self.assertTrue(model.enqueue(frame))
        submitted: list[OwnedFrame] = []

        def submit(frame: OwnedFrame) -> SubmissionResult:
            submitted.append(frame)
            if frame is frames[0] and frame.submission_count < 3:
                return SubmissionResult(
                    SubmissionStatus.RETRY_PENDING,
                    completion_pending=False,
                )
            return SubmissionResult(SubmissionStatus.ACCEPTED_COMPLETE)

        for _ in range(5):
            model.run_worker_once(submit)

        self.assertEqual([frames[0], frames[0], frames[0], frames[1], frames[2]], submitted)
        self.assertEqual([1, 1, 1], [frame.close_count for frame in frames])
        self.assertEqual(0, model.owned_frames)

    def test_stop_during_worker_unscheduled_handoff_cannot_lose_wakeup(self) -> None:
        model = SensorLinearNdrLifecycleModel()
        model.start(session_generation=43)
        # Schedule an otherwise idle worker, then stop while it is paused immediately before the
        # atomic scheduled->terminal/reschedule handoff.
        with model._lock:
            model._ensure_worker_locked()
        handoff_reached, release_handoff = threading.Event(), threading.Event()
        worker = self._run_thread(
            lambda: model.run_worker_once(
                lambda _: self.fail("idle worker submitted a frame"),
                before_handoff_arrived=handoff_reached,
                allow_handoff=release_handoff,
            )
        )
        self.assertTrue(handoff_reached.wait(2.0))
        model.request_stop(discard=False)
        release_handoff.set()
        worker.join(2.0)
        self.assertFalse(worker.is_alive())
        self.assertTrue(model.stop_latch.is_set())
        self.assertFalse(model.worker_scheduled)
        self.assertEqual(1, model.finish_count)

    def test_discard_after_dequeue_closes_exactly_once_without_processor_call(self) -> None:
        model = SensorLinearNdrLifecycleModel()
        model.start(session_generation=44)
        frame = OwnedFrame(3)
        model.enqueue(frame)
        dequeued, release_submit = threading.Event(), threading.Event()
        calls = 0

        def submit(_frame: OwnedFrame) -> SubmissionResult:
            nonlocal calls
            calls += 1
            return SubmissionResult(SubmissionStatus.ACCEPTED_COMPLETE)

        worker = self._run_thread(
            lambda: model.run_worker_once(
                submit,
                before_submit_arrived=dequeued,
                allow_submit=release_submit,
            )
        )
        self.assertTrue(dequeued.wait(2.0))
        model.request_stop(discard=True)
        release_submit.set()
        worker.join(2.0)
        self.assertEqual(0, calls)
        self.assertEqual(0, frame.submission_count)
        self.assertEqual(1, frame.close_count)
        self.assertTrue(model.stop_latch.is_set())

    def test_destroy_is_published_only_after_active_native_tail_retires(self) -> None:
        model = SensorLinearNdrLifecycleModel()
        model.start(session_generation=45)
        frame = OwnedFrame(4)
        model.enqueue(frame)
        model.run_worker_once(
            lambda _: SubmissionResult(SubmissionStatus.ACCEPTED_PENDING, completion_pending=True)
        )
        model.request_destroy()
        self.assertFalse(model.destroyed)
        self.assertTrue(model.native_pending)

        model.run_worker_once(lambda _: self.fail("destroy resubmitted a frame"), lambda: True)
        self.assertTrue(model.destroyed)
        self.assertTrue(model.physically_quiescent())
        self.assertEqual(1, frame.close_count)

    def test_new_generation_requires_physical_not_only_logical_quiescence(self) -> None:
        model = SensorLinearNdrLifecycleModel()
        model.start(session_generation=46)
        frame = OwnedFrame(5)
        model.enqueue(frame)
        model.run_worker_once(
            lambda _: SubmissionResult(SubmissionStatus.ACCEPTED_PENDING, completion_pending=True)
        )
        model.request_stop(discard=True)
        with self.assertRaisesRegex(RuntimeError, "physical quiescence"):
            model.start(session_generation=47)
        model.run_worker_once(lambda _: self.fail("tail must only be polled"), lambda: True)
        self.assertEqual(2, model.start(session_generation=47))

    def test_stale_session_is_rejected_at_preview_encoder_and_analytics_boundaries(self) -> None:
        gate = SessionPublicationGate(ingress_generation=7, session_generation=100)
        old = PublicationIdentity(7, 100)
        self.assertTrue(gate.publish("preview", old))
        gate.advance_session(101)
        for channel in SessionPublicationGate.CHANNELS:
            self.assertFalse(gate.publish(channel, old), channel)
        current = PublicationIdentity(7, 101)
        for channel in SessionPublicationGate.CHANNELS:
            self.assertTrue(gate.publish(channel, current), channel)
        gate.retire()
        for channel in SessionPublicationGate.CHANNELS:
            self.assertFalse(gate.publish(channel, current), channel)

    def test_callback_entering_before_open_or_before_restart_cannot_be_relabelled(self) -> None:
        model = NdrCallbackLineageModel()
        before_open = model.observe()
        generation_one = model.start(session_generation=100)
        self.assertFalse(model.accept_image(before_open, 1_000))
        self.assertTrue(model.accept_image(generation_one, 1_100))
        model.signal_stop()
        model.commit_stop()
        self.assertTrue(model.accept_result(generation_one, 1_100, 100))
        model.finish()

        generation_two = model.start(session_generation=100)
        self.assertIsNot(generation_one, generation_two)
        self.assertFalse(model.accept_image(generation_one, 2_000))
        self.assertFalse(model.accept_result(generation_one, 2_000, 100))
        self.assertIsNone(model.latest_result_timestamp_ns)

    def test_camera2_cutover_retires_delayed_preview_images_without_sequence_backlog(self) -> None:
        model = NdrCallbackLineageModel()
        token = model.start(
            session_generation=100,
            cutover_timestamp_floor_ns=8_000,
        )

        # Model the clean device failure: eight same-session RAW buffers arrive only after arm,
        # although their Camera2 results completed on the preview side of the cutover.
        for timestamp_ns in range(1_000, 9_000, 1_000):
            self.assertFalse(model.accept_image(token, timestamp_ns))
        self.assertEqual(8, model.cutover_retired_images)
        self.assertEqual(0, model.owned_frames)
        self.assertEqual(1, model.owned_high_watermark)
        self.assertFalse(model.pending_images)

        # The first post-cutover exact pair enters normally and leaves no ownership behind.
        self.assertTrue(model.accept_result(token, 9_000, 100))
        self.assertTrue(model.accept_image(token, 9_000))
        self.assertEqual([9_000], model.paired_timestamps)
        self.assertEqual(0, model.owned_frames)
        self.assertFalse(model.pending_images)

    def test_stop_signal_gap_allows_only_an_exact_owned_ndr_result(self) -> None:
        model = NdrCallbackLineageModel()
        token = model.start(session_generation=101)
        self.assertTrue(model.accept_image(token, 3_000))
        model.signal_stop()
        self.assertTrue(model.accepting, "signal path intentionally precedes lifecycle-lock commit")
        self.assertTrue(model.accept_result(None, 3_000, 101))
        previous_latest = model.latest_result_timestamp_ns
        self.assertFalse(model.accept_result(None, 3_001, 101))
        self.assertEqual(previous_latest, model.latest_result_timestamp_ns)

    def test_raw_pause_resume_completes_same_writer_join_without_replacing_latest(self) -> None:
        model = RawResultLineageModel(session_generation=200)
        before_pause = model.start()
        self.assertTrue(model.accept_image(10_000))
        model.pause()
        after_resume = model.resume()
        self.assertEqual(before_pause.writer_session, after_resume.writer_session)
        self.assertNotEqual(before_pause.admission_epoch, after_resume.admission_epoch)
        self.assertTrue(model.accept_result(after_resume, 10_000))
        self.assertIn(10_000, model.recent_results)
        self.assertIsNone(
            model.latest_result_timestamp_ns,
            "retired pause epoch must not replace current descriptor fallback metadata",
        )

    def test_raw_restart_timestamp_barrier_purges_late_result_before_first_image(self) -> None:
        model = RawResultLineageModel(session_generation=201)
        old_token = model.start()
        self.assertTrue(model.accept_image(20_000))
        model.finish()
        new_token = model.start()
        self.assertIsNot(old_token, new_token)
        self.assertFalse(model.accept_result(old_token, 20_500))

        # A callback invoked only after restart can sample the new token before its first Image. It may
        # enter the bounded result cache briefly, but the first monotonic Image purges it before use.
        self.assertTrue(model.accept_result(new_token, 20_500))
        self.assertEqual(20_500, model.latest_result_timestamp_ns)
        self.assertTrue(model.accept_image(21_000))
        self.assertNotIn(20_500, model.recent_results)
        self.assertIsNone(model.latest_result_timestamp_ns)
        self.assertFalse(model.accept_result(new_token, 20_500))
        self.assertTrue(model.accept_result(new_token, 21_000))

        source = (
            Path(__file__).resolve().parents[2]
            / "app/src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt"
        ).read_text(encoding="utf-8")
        self.assertIn("rawCaptureFirstAcceptedSensorTimestampNs.compareAndSet(0L, timestamp)", source)
        self.assertIn("timestamp >", source)
        self.assertIn("retiredSensorTimestampFloorNs", source)


if __name__ == "__main__":
    unittest.main()
