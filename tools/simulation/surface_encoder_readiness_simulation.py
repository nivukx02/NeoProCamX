#!/usr/bin/env python3
"""Exhaustive model of the non-clip MediaCodec surface-readiness gate."""

from dataclasses import dataclass
from itertools import permutations


@dataclass
class Model:
    phase: str = "WARMING"
    format_ok: bool = False
    sample_discarded: bool = False
    discarded_samples: int = 0
    muxed_samples: int = 0
    clip_clock_resets: int = 0
    sync_requests: int = 0
    keyframe_required: bool = False
    failed: bool = False

    def event(self, name: str) -> None:
        if name == "format":
            assert self.phase in {"WARMING", "READY"}
            self.format_ok = True
        elif name == "preroll_sample":
            assert self.phase in {"WARMING", "READY"}
            self.sample_discarded = True
            self.discarded_samples += 1
        elif name == "commit":
            assert self.phase == "READY"
            self.clip_clock_resets += 1
            self.sync_requests += 1
            self.keyframe_required = True
            self.phase = "CLIP_ACTIVE"
        elif name == "commit_sync_rejected":
            assert self.phase == "READY"
            self.sync_requests += 1
            self.failed = True
            self.phase = "FAILED"
        elif name in {"sensor_keyframe", "sensor_delta"}:
            assert self.phase == "CLIP_ACTIVE"
            if self.keyframe_required and name != "sensor_keyframe":
                self.failed = True
                self.phase = "FAILED"
                return
            self.keyframe_required = False
            self.muxed_samples += 1
        else:
            raise AssertionError(name)
        if self.phase == "WARMING" and self.format_ok and self.sample_discarded:
            self.phase = "READY"


def valid_precommit_orders():
    for order in permutations(("format", "preroll_sample")):
        model = Model()
        for event in order:
            model.event(event)
        yield order, model


def main() -> None:
    cases = 0
    for order, model in valid_precommit_orders():
        assert model.phase == "READY"
        assert model.discarded_samples == 1
        assert model.muxed_samples == 0
        model.event("commit")
        model.event("sensor_keyframe")
        for _ in range(749):
            model.event("sensor_delta")
        assert model.discarded_samples == 1
        assert model.muxed_samples == 750
        assert model.clip_clock_resets == 1
        assert model.sync_requests == 1
        cases += 1

    rejected = 0
    for format_ok, sample_discarded in ((False, False), (True, False), (False, True)):
        model = Model(format_ok=format_ok, sample_discarded=sample_discarded)
        try:
            model.event("commit")
        except AssertionError:
            rejected += 1
    assert rejected == 3

    missing_idr = Model(format_ok=True, sample_discarded=True, phase="READY")
    missing_idr.event("commit")
    missing_idr.event("sensor_delta")
    assert missing_idr.failed
    assert missing_idr.muxed_samples == 0

    rejected_sync = Model(format_ok=True, sample_discarded=True, phase="READY")
    rejected_sync.event("commit_sync_rejected")
    assert rejected_sync.failed
    assert rejected_sync.muxed_samples == 0

    print(
        "surface_encoder_readiness_simulation: PASS "
        f"orders={cases} rejected_incomplete={rejected} clip_frames_per_order=750 "
        "preroll_muxed=0 sync_boundary=1 missing_idr_rejected=1 sync_request_rejected=1"
    )


if __name__ == "__main__":
    main()
