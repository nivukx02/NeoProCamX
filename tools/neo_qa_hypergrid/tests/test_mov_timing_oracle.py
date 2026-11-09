from __future__ import annotations

import json
import struct
import tempfile
import unittest
from pathlib import Path

from tools.neo_qa_hypergrid.mov_timing_oracle import (
    MovTimingLimits,
    MovTimingParseError,
    TrackTimingExpectation,
    UINT64_MAX,
    analyze_mov_timing,
    main,
    verify_track_timing,
    write_mov_timing_report,
)


def _box(box_type: bytes, payload: bytes) -> bytes:
    if len(box_type) != 4:
        raise ValueError("box type must contain four bytes")
    return struct.pack(">I4s", 8 + len(payload), box_type) + payload


def _full_box(version: int, flags: int, payload: bytes) -> bytes:
    return bytes((version,)) + flags.to_bytes(3, "big") + payload


def _mvhd(timescale: int, duration: int, version: int = 0) -> bytes:
    if version == 0:
        timing = struct.pack(">IIII", 0, 0, timescale, duration)
    else:
        timing = struct.pack(">QQIQ", 0, 0, timescale, duration)
    remainder = (
        struct.pack(">I", 0x00010000)
        + struct.pack(">H", 0x0100)
        + bytes(10)
        + bytes(36)
        + bytes(24)
        + struct.pack(">I", 2)
    )
    return _box(b"mvhd", _full_box(version, 0, timing + remainder))


def _tkhd(track_id: int, duration: int, version: int = 0) -> bytes:
    if version == 0:
        timing = struct.pack(">IIIII", 0, 0, track_id, 0, duration)
    else:
        timing = struct.pack(">QQIIQ", 0, 0, track_id, 0, duration)
    remainder = (
        bytes(8)
        + struct.pack(">hhhh", 0, 0, 0, 0)
        + bytes(36)
        + bytes(8)
    )
    return _box(b"tkhd", _full_box(version, 7, timing + remainder))


def _mdhd(timescale: int, duration: int, version: int = 0) -> bytes:
    if version == 0:
        timing = struct.pack(">IIII", 0, 0, timescale, duration)
    else:
        timing = struct.pack(">QQIQ", 0, 0, timescale, duration)
    return _box(b"mdhd", _full_box(version, 0, timing + bytes(4)))


def _hdlr(handler: bytes = b"vide") -> bytes:
    return _box(
        b"hdlr",
        _full_box(0, 0, struct.pack(">I4s", 0, handler) + bytes(12) + b"test\0"),
    )


def _stts(runs: tuple[tuple[int, int], ...], version: int = 0) -> bytes:
    entries = b"".join(struct.pack(">II", count, delta) for count, delta in runs)
    return _box(
        b"stts",
        _full_box(version, 0, struct.pack(">I", len(runs)) + entries),
    )


def _elst(
    version: int,
    entries: tuple[tuple[int, int, int, int], ...],
) -> bytes:
    if version == 0:
        encoded = b"".join(
            struct.pack(">Iihh", duration, media_time, rate_integer, rate_fraction)
            for duration, media_time, rate_integer, rate_fraction in entries
        )
    else:
        encoded = b"".join(
            struct.pack(">Qqhh", duration, media_time, rate_integer, rate_fraction)
            for duration, media_time, rate_integer, rate_fraction in entries
        )
    return _box(
        b"elst",
        _full_box(version, 0, struct.pack(">I", len(entries)) + encoded),
    )


def _movie(
    runs: tuple[tuple[int, int], ...],
    *,
    timescale: int = 1_000_000,
    header_version: int = 0,
    edit_version: int | None = None,
    edits: tuple[tuple[int, int, int, int], ...] = (),
    stts_override: bytes | None = None,
) -> bytes:
    media_duration = sum(count * delta for count, delta in runs)
    track_duration = sum(entry[0] for entry in edits) if edit_version is not None else media_duration
    edit_container = (
        _box(b"edts", _elst(edit_version, edits)) if edit_version is not None else b""
    )
    stbl = _box(b"stbl", stts_override if stts_override is not None else _stts(runs))
    minf = _box(b"minf", stbl)
    mdia = _box(
        b"mdia",
        _mdhd(timescale, media_duration, header_version) + _hdlr() + minf,
    )
    trak = _box(
        b"trak",
        _tkhd(1, track_duration, header_version) + edit_container + mdia,
    )
    moov = _box(
        b"moov",
        _mvhd(timescale, track_duration, header_version) + trak,
    )
    ftyp = _box(b"ftyp", b"qt  \0\0\0\0qt  ")
    return ftyp + moov + _box(b"mdat", b"")


def _movie_with_raw_stts(raw_stts: bytes, duration: int = 0) -> bytes:
    stbl = _box(b"stbl", raw_stts)
    mdia = _box(
        b"mdia",
        _mdhd(1_000_000, duration) + _hdlr() + _box(b"minf", stbl),
    )
    trak = _box(b"trak", _tkhd(1, duration) + mdia)
    return _box(b"moov", _mvhd(1_000_000, duration) + trak)


class MovTimingOracleTest(unittest.TestCase):
    def _analyze(self, payload: bytes, limits: MovTimingLimits | None = None):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "fixture.mov"
        path.write_bytes(payload)
        return analyze_mov_timing(path, limits)

    def test_cfr_reports_exact_stts_count_duration_and_no_edit_list(self) -> None:
        report = self._analyze(_movie(((5, 40_000),)))
        self.assertTrue(report.consistent, report)
        self.assertEqual(1_000_000, report.movie_header.timescale)
        self.assertEqual(200_000, report.movie_header.duration_units)
        self.assertEqual(1, len(report.tracks))
        track = report.tracks[0]
        self.assertEqual("vide", track.handler_type)
        self.assertEqual(1_000_000, track.track_duration_timescale)
        self.assertEqual(5, track.stts.expanded_sample_count)
        self.assertEqual(200_000, track.stts.total_duration_units)
        self.assertEqual(40_000, track.stts.final_sample_duration_units)
        self.assertEqual(160_000, track.stts.last_sample_dts_units)
        self.assertFalse(track.edit_list.present)
        self.assertEqual((), track.edit_list.entries)
        self.assertEqual(
            (),
            verify_track_timing(
                track,
                TrackTimingExpectation(
                    media_timescale=1_000_000,
                    stts_runs=((5, 40_000),),
                    expanded_sample_count=5,
                    total_duration_units=200_000,
                    final_sample_duration_units=40_000,
                    require_edit_list=False,
                ),
            ),
        )

    def test_vfr_reports_every_run_and_measured_final_sample_duration(self) -> None:
        runs = ((1, 40_000), (1, 50_000), (1, 30_000), (2, 60_000))
        report = self._analyze(_movie(runs))
        self.assertTrue(report.consistent, report)
        track = report.tracks[0]
        self.assertEqual(
            runs,
            tuple((run.sample_count, run.sample_delta_units) for run in track.stts.runs),
        )
        self.assertEqual(5, track.stts.expanded_sample_count)
        self.assertEqual(240_000, track.stts.total_duration_units)
        self.assertEqual(60_000, track.stts.final_sample_duration_units)
        self.assertEqual(180_000, track.stts.last_sample_dts_units)
        self.assertEqual(
            (),
            verify_track_timing(
                track,
                TrackTimingExpectation(
                    media_timescale=1_000_000,
                    stts_runs=runs,
                    expanded_sample_count=5,
                    total_duration_units=240_000,
                    final_sample_duration_units=60_000,
                    require_edit_list=False,
                ),
            ),
        )

    def test_one_sample_uses_its_only_stts_delta_as_final_duration(self) -> None:
        report = self._analyze(_movie(((1, 33_333),)))
        track = report.tracks[0]
        self.assertEqual(1, track.stts.expanded_sample_count)
        self.assertEqual(33_333, track.stts.final_sample_duration_units)
        self.assertEqual(0, track.stts.last_sample_dts_units)
        self.assertTrue(report.consistent)

    def test_version_one_movie_headers_and_both_edit_list_versions(self) -> None:
        runs = ((2, 120_000),)
        edits = ((100_000, -1, 1, 0), (240_000, 0, 1, 0))
        for edit_version in (0, 1):
            with self.subTest(edit_version=edit_version):
                report = self._analyze(
                    _movie(
                        runs,
                        header_version=1,
                        edit_version=edit_version,
                        edits=edits,
                    )
                )
                self.assertTrue(report.consistent, report)
                self.assertEqual(1, report.movie_header.version)
                track = report.tracks[0]
                self.assertEqual(1, track.tkhd_version)
                self.assertEqual(1, track.mdhd_version)
                self.assertTrue(track.edit_list.present)
                self.assertEqual(edit_version, track.edit_list.version)
                self.assertEqual(2, track.edit_list.entry_count)
                self.assertEqual(-1, track.edit_list.entries[0].media_time_track_units)
                self.assertIsNone(track.edit_list.entries[0].media_time_seconds)
                self.assertEqual(340_000, track.edit_list.total_segment_duration_movie_units)

    def test_atomic_json_report_retains_all_runs_and_source_hash(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "input.mov"
            output = root / "evidence" / "timing.json"
            source.write_bytes(_movie(((1, 40_000), (2, 60_000))))
            report = analyze_mov_timing(source)
            write_mov_timing_report(report, output)
            parsed = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual("neo-qa-hypergrid/mov-timing-evidence/v1", parsed["schema"])
            self.assertTrue(parsed["consistent"])
            self.assertEqual(64, len(parsed["source_sha256"]))
            self.assertEqual(2, len(parsed["tracks"][0]["stts"]["runs"]))
            self.assertEqual(60_000, parsed["tracks"][0]["stts"]["final_sample_duration_units"])

    def test_report_writer_cannot_overwrite_the_mov_evidence(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "input.mov"
            source.write_bytes(_movie(((1, 40_000),)))
            report = analyze_mov_timing(source)
            original = source.read_bytes()
            with self.assertRaisesRegex(ValueError, "must not overwrite"):
                write_mov_timing_report(report, source)
            self.assertEqual(original, source.read_bytes())

    def test_module_cli_writes_a_consistent_report(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = root / "input.mov"
            output = root / "report.json"
            source.write_bytes(_movie(((5, 40_000),)))
            self.assertEqual(0, main((str(source), "--output", str(output))))
            payload = json.loads(output.read_text(encoding="utf-8"))
            self.assertTrue(payload["consistent"])
            self.assertEqual(5, payload["tracks"][0]["stts"]["expanded_sample_count"])

    def test_truncated_top_level_atom_header_is_rejected(self) -> None:
        with self.assertRaisesRegex(MovTimingParseError, "truncated atom header"):
            self._analyze(b"\0\0\0\x08moo")

    def test_atom_declared_beyond_parent_and_uint64_extended_size_are_rejected(self) -> None:
        malformed_child = _box(b"moov", struct.pack(">I4s", 100, b"mvhd"))
        with self.assertRaisesRegex(MovTimingParseError, "exceeds"):
            self._analyze(malformed_child)
        extended_overflow = struct.pack(">I4sQ", 1, b"moov", UINT64_MAX)
        with self.assertRaisesRegex(MovTimingParseError, "exceeds"):
            self._analyze(extended_overflow)

    def test_truncated_stts_and_elst_tables_are_rejected(self) -> None:
        truncated_stts = _box(
            b"stts",
            _full_box(0, 0, struct.pack(">I", 2) + struct.pack(">II", 1, 40_000)),
        )
        with self.assertRaisesRegex(MovTimingParseError, "payload size mismatch"):
            self._analyze(_movie_with_raw_stts(truncated_stts, 40_000))

        valid = _movie(((1, 40_000),), edit_version=0, edits=((40_000, 0, 1, 0),))
        marker = valid.index(b"elst")
        count_offset = marker + 8
        corrupted = bytearray(valid)
        corrupted[count_offset : count_offset + 4] = struct.pack(">I", 2)
        with self.assertRaisesRegex(MovTimingParseError, "payload size mismatch"):
            self._analyze(bytes(corrupted))

    def test_unsupported_full_box_versions_are_rejected(self) -> None:
        invalid_stts = _stts(((1, 40_000),), version=1)
        with self.assertRaisesRegex(MovTimingParseError, "requires stts version=0"):
            self._analyze(_movie_with_raw_stts(invalid_stts, 40_000))

        invalid_elst = bytearray(
            _movie(((1, 40_000),), edit_version=1, edits=((40_000, 0, 1, 0),))
        )
        marker = invalid_elst.index(b"elst")
        invalid_elst[marker + 4] = 2
        with self.assertRaisesRegex(MovTimingParseError, "unsupported elst version 2"):
            self._analyze(bytes(invalid_elst))

    def test_elst_entry_bound_and_reserved_flags_are_enforced(self) -> None:
        edits = ((10_000, -1, 1, 0), (40_000, 0, 1, 0))
        with self.assertRaisesRegex(MovTimingParseError, "entry count 2 exceeds limit 1"):
            self._analyze(
                _movie(((1, 40_000),), edit_version=0, edits=edits),
                MovTimingLimits(max_elst_entries=1),
            )

        nonzero_flags = bytearray(
            _movie(((1, 40_000),), edit_version=0, edits=((40_000, 0, 1, 0),))
        )
        marker = nonzero_flags.index(b"elst")
        nonzero_flags[marker + 7] = 1
        with self.assertRaisesRegex(MovTimingParseError, "requires elst flags=0"):
            self._analyze(bytes(nonzero_flags))

    def test_edit_list_rate_change_is_reported_as_semantic_failure(self) -> None:
        report = self._analyze(
            _movie(
                ((1, 40_000),),
                edit_version=0,
                edits=((40_000, 0, 2, 0),),
            )
        )
        self.assertFalse(report.consistent)
        self.assertTrue(
            any("changes media rate" in error for error in report.tracks[0].consistency_errors)
        )

    def test_stts_entry_sample_and_duration_overflow_limits_are_enforced(self) -> None:
        two_entries = _stts(((1, 40_000), (1, 50_000)))
        with self.assertRaisesRegex(MovTimingParseError, "entry count 2 exceeds limit 1"):
            self._analyze(
                _movie_with_raw_stts(two_entries, 90_000),
                MovTimingLimits(max_stts_entries=1),
            )

        overflowing = _stts(
            (
                (0xFFFFFFFF, 0xFFFFFFFF),
                (0xFFFFFFFF, 0xFFFFFFFF),
            )
        )
        with self.assertRaisesRegex(MovTimingParseError, "total duration exceeds"):
            self._analyze(
                _movie_with_raw_stts(overflowing),
                MovTimingLimits(max_expanded_samples=UINT64_MAX),
            )

    def test_semantic_duration_mismatch_is_reported_not_hidden(self) -> None:
        raw_stts = _stts(((2, 40_000),))
        report = self._analyze(_movie_with_raw_stts(raw_stts, duration=70_000))
        self.assertFalse(report.consistent)
        self.assertIn(
            "stts duration 80000 != mdhd duration 70000",
            report.tracks[0].consistency_errors,
        )


if __name__ == "__main__":
    unittest.main()
