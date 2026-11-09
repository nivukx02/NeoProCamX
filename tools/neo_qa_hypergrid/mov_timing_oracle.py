"""Bounded ISO-BMFF/MOV timing evidence parser for CP-178.

The parser reads atom headers and the small timing tables directly from disk. It never
loads media payloads or expands a potentially large ``stts`` table into one item per
sample. Structural corruption is rejected; semantic duration relationships are
reported explicitly so failed artifacts remain useful evidence.

This is host evidence tooling. It does not replace a physical-device conversion run.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import struct
import sys
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import BinaryIO, Sequence


SCHEMA = "neo-qa-hypergrid/mov-timing-evidence/v1"
UINT32_MAX = (1 << 32) - 1
UINT64_MAX = (1 << 64) - 1


class MovTimingParseError(ValueError):
    """Raised when a MOV cannot provide bounded, structurally trustworthy evidence."""


@dataclass(frozen=True)
class MovTimingLimits:
    """Hard resource ceilings for parsing untrusted conversion artifacts."""

    max_file_bytes: int = 16 * 1024**4
    max_atoms: int = 1_000_000
    max_depth: int = 16
    max_tracks: int = 64
    max_stts_entries: int = 1_000_000
    max_elst_entries: int = 1_000_000
    max_expanded_samples: int = 100_000_000
    max_duration_units: int = UINT64_MAX

    def __post_init__(self) -> None:
        for name, value in asdict(self).items():
            if isinstance(value, bool) or not isinstance(value, int) or value <= 0:
                raise ValueError(f"MOV timing limit {name} must be a positive integer")
        if self.max_duration_units > UINT64_MAX:
            raise ValueError("MOV timing duration limit cannot exceed uint64")


@dataclass(frozen=True)
class MovieHeaderTiming:
    version: int
    timescale: int
    duration_units: int
    duration_seconds: float


@dataclass(frozen=True)
class TimeToSampleRun:
    index: int
    sample_count: int
    sample_delta_units: int
    run_duration_units: int
    sample_delta_seconds: float


@dataclass(frozen=True)
class TimeToSampleEvidence:
    version: int
    entry_count: int
    runs: tuple[TimeToSampleRun, ...]
    expanded_sample_count: int
    total_duration_units: int
    total_duration_seconds: float
    final_sample_duration_units: int | None
    final_sample_duration_seconds: float | None
    last_sample_dts_units: int | None
    last_sample_dts_seconds: float | None


@dataclass(frozen=True)
class EditListEntryEvidence:
    index: int
    segment_duration_movie_units: int
    segment_duration_seconds: float
    media_time_track_units: int
    media_time_seconds: float | None
    media_rate_integer: int
    media_rate_fraction: int


@dataclass(frozen=True)
class EditListEvidence:
    present: bool
    version: int | None
    flags: int | None
    entry_count: int
    entries: tuple[EditListEntryEvidence, ...]
    total_segment_duration_movie_units: int
    total_segment_duration_seconds: float


@dataclass(frozen=True)
class TrackTimingEvidence:
    track_index: int
    track_id: int
    handler_type: str
    tkhd_version: int
    track_duration_timescale: int
    track_duration_movie_units: int
    track_duration_seconds: float
    mdhd_version: int
    media_timescale: int
    media_duration_units: int
    media_duration_seconds: float
    stts: TimeToSampleEvidence
    edit_list: EditListEvidence
    consistency_errors: tuple[str, ...]

    @property
    def consistent(self) -> bool:
        return not self.consistency_errors


@dataclass(frozen=True)
class MovTimingReport:
    schema: str
    source_path: str
    source_bytes: int
    source_sha256: str
    atom_count: int
    movie_header: MovieHeaderTiming
    tracks: tuple[TrackTimingEvidence, ...]
    consistency_errors: tuple[str, ...]

    @property
    def consistent(self) -> bool:
        return not self.consistency_errors and all(track.consistent for track in self.tracks)

    def to_dict(self) -> dict[str, object]:
        payload = asdict(self)
        payload["consistent"] = self.consistent
        for index, track in enumerate(self.tracks):
            payload["tracks"][index]["consistent"] = track.consistent
        return payload


@dataclass(frozen=True)
class TrackTimingExpectation:
    """Exact CP-178 oracle values for one selected MOV track."""

    media_timescale: int
    stts_runs: tuple[tuple[int, int], ...]
    expanded_sample_count: int
    total_duration_units: int
    final_sample_duration_units: int
    require_edit_list: bool | None = None

    def __post_init__(self) -> None:
        numeric = (
            self.media_timescale,
            self.expanded_sample_count,
            self.total_duration_units,
            self.final_sample_duration_units,
        )
        if any(isinstance(value, bool) or value <= 0 for value in numeric):
            raise ValueError("MOV timing expectations must be positive")
        if not self.stts_runs:
            raise ValueError("MOV timing expectation must include at least one stts run")
        if any(count <= 0 or delta <= 0 for count, delta in self.stts_runs):
            raise ValueError("Expected stts counts and deltas must be positive")


@dataclass(frozen=True)
class _Box:
    box_type: bytes
    start: int
    size: int
    header_size: int
    payload_start: int
    end: int
    path: str


class _BoundedBoxReader:
    def __init__(self, source: BinaryIO, file_size: int, limits: MovTimingLimits):
        self.source = source
        self.file_size = file_size
        self.limits = limits
        self.atom_count = 0

    def read_exact(self, offset: int, size: int, bound: int, description: str) -> bytes:
        if offset < 0 or size < 0 or bound < 0:
            raise MovTimingParseError(f"negative read range for {description}")
        end = offset + size
        if end < offset or end > bound or end > self.file_size:
            raise MovTimingParseError(
                f"truncated {description}: offset={offset} bytes={size} bound={bound}"
            )
        self.source.seek(offset)
        payload = self.source.read(size)
        if len(payload) != size:
            raise MovTimingParseError(
                f"short read for {description}: expected={size} actual={len(payload)}"
            )
        return payload

    def boxes(self, start: int, end: int, depth: int, parent_path: str) -> tuple[_Box, ...]:
        if depth > self.limits.max_depth:
            raise MovTimingParseError(
                f"MOV atom nesting exceeds limit {self.limits.max_depth} at {parent_path}"
            )
        if start < 0 or end < start or end > self.file_size:
            raise MovTimingParseError(
                f"invalid container bounds at {parent_path}: start={start} end={end}"
            )
        result: list[_Box] = []
        cursor = start
        occurrence: dict[bytes, int] = {}
        while cursor < end:
            remaining = end - cursor
            if remaining < 8:
                raise MovTimingParseError(
                    f"truncated atom header at {parent_path}: offset={cursor} remaining={remaining}"
                )
            header = self.read_exact(cursor, 8, end, f"atom header at {parent_path}")
            size32, box_type = struct.unpack(">I4s", header)
            header_size = 8
            if size32 == 1:
                large = self.read_exact(
                    cursor + 8,
                    8,
                    end,
                    f"extended atom size for {_fourcc(box_type)}",
                )
                size = struct.unpack(">Q", large)[0]
                header_size = 16
                if size < header_size:
                    raise MovTimingParseError(
                        f"extended atom {_fourcc(box_type)} size {size} is smaller than its header"
                    )
            elif size32 == 0:
                size = remaining
            else:
                size = size32
                if size < header_size:
                    raise MovTimingParseError(
                        f"atom {_fourcc(box_type)} size {size} is smaller than its header"
                    )

            if box_type == b"uuid":
                header_size += 16
                if size < header_size:
                    raise MovTimingParseError("uuid atom is missing its 16-byte extended type")
                self.read_exact(
                    cursor + header_size - 16,
                    16,
                    end,
                    "uuid extended type",
                )

            box_end = cursor + size
            if box_end < cursor or box_end > end:
                raise MovTimingParseError(
                    f"atom {_fourcc(box_type)} exceeds {parent_path}: "
                    f"start={cursor} size={size} parentEnd={end}"
                )
            if box_end <= cursor:
                raise MovTimingParseError(f"atom {_fourcc(box_type)} made no forward progress")

            self.atom_count += 1
            if self.atom_count > self.limits.max_atoms:
                raise MovTimingParseError(
                    f"MOV atom count exceeds limit {self.limits.max_atoms}"
                )
            occurrence[box_type] = occurrence.get(box_type, 0) + 1
            name = _fourcc(box_type)
            path = f"{parent_path}/{name}[{occurrence[box_type] - 1}]"
            result.append(
                _Box(
                    box_type=box_type,
                    start=cursor,
                    size=size,
                    header_size=header_size,
                    payload_start=cursor + header_size,
                    end=box_end,
                    path=path,
                )
            )
            cursor = box_end
        if cursor != end:
            raise MovTimingParseError(
                f"atom parser did not terminate on {parent_path} boundary: {cursor} != {end}"
            )
        return tuple(result)


def _fourcc(value: bytes) -> str:
    return value.decode("latin-1")


def _sha256_open_file(
    source: BinaryIO,
    expected_size: int,
    chunk_size: int = 1024 * 1024,
) -> str:
    digest = hashlib.sha256()
    source.seek(0)
    remaining = expected_size
    while remaining:
        chunk = source.read(min(chunk_size, remaining))
        if not chunk:
            raise MovTimingParseError(
                f"MOV input truncated while hashing: remaining={remaining}"
            )
        digest.update(chunk)
        remaining -= len(chunk)
    if source.read(1):
        raise MovTimingParseError("MOV input grew while hashing")
    return digest.hexdigest().upper()


def _single_box(
    boxes: Sequence[_Box],
    box_type: bytes,
    context: str,
    *,
    required: bool = True,
) -> _Box | None:
    matches = [box for box in boxes if box.box_type == box_type]
    if len(matches) > 1:
        raise MovTimingParseError(
            f"{context} contains duplicate {_fourcc(box_type)} atoms ({len(matches)})"
        )
    if required and not matches:
        raise MovTimingParseError(f"{context} is missing required {_fourcc(box_type)} atom")
    return matches[0] if matches else None


def _full_box_header(reader: _BoundedBoxReader, box: _Box) -> tuple[int, int]:
    header = reader.read_exact(box.payload_start, 4, box.end, f"{box.path} full-box header")
    version = header[0]
    flags = int.from_bytes(header[1:4], "big")
    return version, flags


def _unpack_from_box(
    reader: _BoundedBoxReader,
    box: _Box,
    relative_offset: int,
    fmt: str,
    description: str,
) -> tuple[int, ...]:
    size = struct.calcsize(fmt)
    payload = reader.read_exact(
        box.payload_start + relative_offset,
        size,
        box.end,
        f"{box.path} {description}",
    )
    return struct.unpack(fmt, payload)


def _require_known_duration(value: int, maximum: int, box: _Box) -> int:
    if value == maximum:
        raise MovTimingParseError(f"{box.path} uses an unknown-duration sentinel")
    return value


def _parse_mvhd(reader: _BoundedBoxReader, box: _Box) -> MovieHeaderTiming:
    version, flags = _full_box_header(reader, box)
    if flags != 0:
        raise MovTimingParseError(f"{box.path} requires mvhd flags=0, found {flags}")
    if version == 0:
        timescale, duration = _unpack_from_box(reader, box, 12, ">II", "v0 timing")
        duration = _require_known_duration(duration, UINT32_MAX, box)
    elif version == 1:
        timescale = _unpack_from_box(reader, box, 20, ">I", "v1 timescale")[0]
        duration = _unpack_from_box(reader, box, 24, ">Q", "v1 duration")[0]
        duration = _require_known_duration(duration, UINT64_MAX, box)
    else:
        raise MovTimingParseError(f"{box.path} has unsupported mvhd version {version}")
    if timescale <= 0:
        raise MovTimingParseError(f"{box.path} has zero movie timescale")
    return MovieHeaderTiming(version, timescale, duration, duration / timescale)


def _parse_tkhd(reader: _BoundedBoxReader, box: _Box) -> tuple[int, int, int]:
    version, flags = _full_box_header(reader, box)
    if flags & ~0xF:
        raise MovTimingParseError(f"{box.path} contains unsupported tkhd flags {flags:#x}")
    if version == 0:
        track_id = _unpack_from_box(reader, box, 12, ">I", "v0 track ID")[0]
        duration = _unpack_from_box(reader, box, 20, ">I", "v0 duration")[0]
        duration = _require_known_duration(duration, UINT32_MAX, box)
    elif version == 1:
        track_id = _unpack_from_box(reader, box, 20, ">I", "v1 track ID")[0]
        duration = _unpack_from_box(reader, box, 28, ">Q", "v1 duration")[0]
        duration = _require_known_duration(duration, UINT64_MAX, box)
    else:
        raise MovTimingParseError(f"{box.path} has unsupported tkhd version {version}")
    if track_id <= 0:
        raise MovTimingParseError(f"{box.path} has invalid track ID {track_id}")
    return version, track_id, duration


def _parse_mdhd(reader: _BoundedBoxReader, box: _Box) -> tuple[int, int, int]:
    version, flags = _full_box_header(reader, box)
    if flags != 0:
        raise MovTimingParseError(f"{box.path} requires mdhd flags=0, found {flags}")
    if version == 0:
        timescale, duration = _unpack_from_box(reader, box, 12, ">II", "v0 timing")
        duration = _require_known_duration(duration, UINT32_MAX, box)
    elif version == 1:
        timescale = _unpack_from_box(reader, box, 20, ">I", "v1 timescale")[0]
        duration = _unpack_from_box(reader, box, 24, ">Q", "v1 duration")[0]
        duration = _require_known_duration(duration, UINT64_MAX, box)
    else:
        raise MovTimingParseError(f"{box.path} has unsupported mdhd version {version}")
    if timescale <= 0:
        raise MovTimingParseError(f"{box.path} has zero media timescale")
    return version, timescale, duration


def _parse_handler(reader: _BoundedBoxReader, box: _Box) -> str:
    version, flags = _full_box_header(reader, box)
    if version != 0 or flags != 0:
        raise MovTimingParseError(
            f"{box.path} requires hdlr version=0 flags=0, found "
            f"version={version} flags={flags}"
        )
    value = reader.read_exact(box.payload_start + 8, 4, box.end, f"{box.path} handler type")
    return _fourcc(value)


def _bounded_add(left: int, right: int, limit: int, description: str) -> int:
    result = left + right
    if result > limit:
        raise MovTimingParseError(f"{description} exceeds bounded limit {limit}")
    return result


def _bounded_multiply(left: int, right: int, limit: int, description: str) -> int:
    result = left * right
    if result > limit:
        raise MovTimingParseError(f"{description} exceeds bounded limit {limit}")
    return result


def _parse_stts(
    reader: _BoundedBoxReader,
    box: _Box,
    media_timescale: int,
) -> TimeToSampleEvidence:
    version, flags = _full_box_header(reader, box)
    if version != 0 or flags != 0:
        raise MovTimingParseError(
            f"{box.path} requires stts version=0 flags=0, found version={version} flags={flags}"
        )
    entry_count = _unpack_from_box(reader, box, 4, ">I", "entry count")[0]
    if entry_count > reader.limits.max_stts_entries:
        raise MovTimingParseError(
            f"{box.path} entry count {entry_count} exceeds limit "
            f"{reader.limits.max_stts_entries}"
        )
    expected_payload_size = 8 + entry_count * 8
    actual_payload_size = box.end - box.payload_start
    if actual_payload_size != expected_payload_size:
        raise MovTimingParseError(
            f"{box.path} payload size mismatch: expected={expected_payload_size} "
            f"actual={actual_payload_size}"
        )

    runs: list[TimeToSampleRun] = []
    expanded_count = 0
    total_duration = 0
    for index in range(entry_count):
        count, delta = _unpack_from_box(
            reader,
            box,
            8 + index * 8,
            ">II",
            f"entry {index}",
        )
        if count <= 0:
            raise MovTimingParseError(f"{box.path} entry {index} has zero sample count")
        if delta <= 0:
            raise MovTimingParseError(f"{box.path} entry {index} has zero sample delta")
        expanded_count = _bounded_add(
            expanded_count,
            count,
            reader.limits.max_expanded_samples,
            f"{box.path} expanded sample count",
        )
        run_duration = _bounded_multiply(
            count,
            delta,
            reader.limits.max_duration_units,
            f"{box.path} entry {index} duration",
        )
        total_duration = _bounded_add(
            total_duration,
            run_duration,
            reader.limits.max_duration_units,
            f"{box.path} total duration",
        )
        runs.append(
            TimeToSampleRun(
                index=index,
                sample_count=count,
                sample_delta_units=delta,
                run_duration_units=run_duration,
                sample_delta_seconds=delta / media_timescale,
            )
        )

    final_duration = runs[-1].sample_delta_units if runs else None
    last_dts = total_duration - final_duration if final_duration is not None else None
    return TimeToSampleEvidence(
        version=version,
        entry_count=entry_count,
        runs=tuple(runs),
        expanded_sample_count=expanded_count,
        total_duration_units=total_duration,
        total_duration_seconds=total_duration / media_timescale,
        final_sample_duration_units=final_duration,
        final_sample_duration_seconds=(
            final_duration / media_timescale if final_duration is not None else None
        ),
        last_sample_dts_units=last_dts,
        last_sample_dts_seconds=(last_dts / media_timescale if last_dts is not None else None),
    )


def _absent_edit_list() -> EditListEvidence:
    return EditListEvidence(False, None, None, 0, (), 0, 0.0)


def _parse_elst(
    reader: _BoundedBoxReader,
    box: _Box,
    movie_timescale: int,
    media_timescale: int,
) -> EditListEvidence:
    version, flags = _full_box_header(reader, box)
    if version not in {0, 1}:
        raise MovTimingParseError(f"{box.path} has unsupported elst version {version}")
    if flags != 0:
        raise MovTimingParseError(f"{box.path} requires elst flags=0, found {flags}")
    entry_count = _unpack_from_box(reader, box, 4, ">I", "entry count")[0]
    if entry_count > reader.limits.max_elst_entries:
        raise MovTimingParseError(
            f"{box.path} entry count {entry_count} exceeds limit "
            f"{reader.limits.max_elst_entries}"
        )
    entry_size = 12 if version == 0 else 20
    expected_payload_size = 8 + entry_count * entry_size
    actual_payload_size = box.end - box.payload_start
    if actual_payload_size != expected_payload_size:
        raise MovTimingParseError(
            f"{box.path} payload size mismatch: expected={expected_payload_size} "
            f"actual={actual_payload_size}"
        )

    entries: list[EditListEntryEvidence] = []
    total_segment_duration = 0
    for index in range(entry_count):
        offset = 8 + index * entry_size
        if version == 0:
            segment_duration, media_time, rate_integer, rate_fraction = _unpack_from_box(
                reader,
                box,
                offset,
                ">Iihh",
                f"entry {index}",
            )
        else:
            segment_duration, media_time, rate_integer, rate_fraction = _unpack_from_box(
                reader,
                box,
                offset,
                ">Qqhh",
                f"entry {index}",
            )
        total_segment_duration = _bounded_add(
            total_segment_duration,
            segment_duration,
            reader.limits.max_duration_units,
            f"{box.path} total segment duration",
        )
        entries.append(
            EditListEntryEvidence(
                index=index,
                segment_duration_movie_units=segment_duration,
                segment_duration_seconds=segment_duration / movie_timescale,
                media_time_track_units=media_time,
                media_time_seconds=(media_time / media_timescale if media_time >= 0 else None),
                media_rate_integer=rate_integer,
                media_rate_fraction=rate_fraction,
            )
        )
    return EditListEvidence(
        present=True,
        version=version,
        flags=flags,
        entry_count=entry_count,
        entries=tuple(entries),
        total_segment_duration_movie_units=total_segment_duration,
        total_segment_duration_seconds=total_segment_duration / movie_timescale,
    )


def _parse_track(
    reader: _BoundedBoxReader,
    track_box: _Box,
    track_index: int,
    movie_header: MovieHeaderTiming,
) -> TrackTimingEvidence:
    children = reader.boxes(
        track_box.payload_start,
        track_box.end,
        2,
        track_box.path,
    )
    tkhd_box = _single_box(children, b"tkhd", track_box.path)
    mdia_box = _single_box(children, b"mdia", track_box.path)
    assert tkhd_box is not None and mdia_box is not None
    tkhd_version, track_id, track_duration = _parse_tkhd(reader, tkhd_box)

    mdia_children = reader.boxes(mdia_box.payload_start, mdia_box.end, 3, mdia_box.path)
    mdhd_box = _single_box(mdia_children, b"mdhd", mdia_box.path)
    hdlr_box = _single_box(mdia_children, b"hdlr", mdia_box.path)
    minf_box = _single_box(mdia_children, b"minf", mdia_box.path)
    assert mdhd_box is not None and hdlr_box is not None and minf_box is not None
    mdhd_version, media_timescale, media_duration = _parse_mdhd(reader, mdhd_box)
    handler_type = _parse_handler(reader, hdlr_box)

    minf_children = reader.boxes(minf_box.payload_start, minf_box.end, 4, minf_box.path)
    stbl_box = _single_box(minf_children, b"stbl", minf_box.path)
    assert stbl_box is not None
    stbl_children = reader.boxes(stbl_box.payload_start, stbl_box.end, 5, stbl_box.path)
    stts_box = _single_box(stbl_children, b"stts", stbl_box.path)
    assert stts_box is not None
    stts = _parse_stts(reader, stts_box, media_timescale)

    edts_box = _single_box(children, b"edts", track_box.path, required=False)
    edit_list = _absent_edit_list()
    if edts_box is not None:
        edts_children = reader.boxes(edts_box.payload_start, edts_box.end, 3, edts_box.path)
        elst_box = _single_box(edts_children, b"elst", edts_box.path, required=False)
        if elst_box is not None:
            edit_list = _parse_elst(
                reader,
                elst_box,
                movie_header.timescale,
                media_timescale,
            )

    errors: list[str] = []
    if stts.expanded_sample_count <= 0:
        errors.append("stts contains no samples")
    if stts.total_duration_units != media_duration:
        errors.append(
            f"stts duration {stts.total_duration_units} != mdhd duration {media_duration}"
        )
    if edit_list.present:
        if edit_list.total_segment_duration_movie_units != track_duration:
            errors.append(
                "elst segment duration "
                f"{edit_list.total_segment_duration_movie_units} != tkhd duration {track_duration}"
            )
        for entry in edit_list.entries:
            if entry.media_time_track_units < -1:
                errors.append(
                    f"elst entry {entry.index} has invalid media time "
                    f"{entry.media_time_track_units}"
                )
            if (entry.media_rate_integer, entry.media_rate_fraction) != (1, 0):
                errors.append(
                    f"elst entry {entry.index} changes media rate to "
                    f"{entry.media_rate_integer}+{entry.media_rate_fraction}/65536"
                )
    elif track_duration * media_timescale != media_duration * movie_header.timescale:
        errors.append(
            f"unedited tkhd duration {track_duration}/{movie_header.timescale} != "
            f"mdhd duration {media_duration}/{media_timescale}"
        )

    return TrackTimingEvidence(
        track_index=track_index,
        track_id=track_id,
        handler_type=handler_type,
        tkhd_version=tkhd_version,
        track_duration_timescale=movie_header.timescale,
        track_duration_movie_units=track_duration,
        track_duration_seconds=track_duration / movie_header.timescale,
        mdhd_version=mdhd_version,
        media_timescale=media_timescale,
        media_duration_units=media_duration,
        media_duration_seconds=media_duration / media_timescale,
        stts=stts,
        edit_list=edit_list,
        consistency_errors=tuple(errors),
    )


def analyze_mov_timing(
    path: Path,
    limits: MovTimingLimits | None = None,
) -> MovTimingReport:
    """Parse one MOV and return bounded, exact timing-table evidence."""

    path = path.resolve()
    if not path.is_file():
        raise MovTimingParseError(f"MOV input is not a regular file: {path}")
    path_stat_before = path.stat()
    file_size = path_stat_before.st_size
    active_limits = limits or MovTimingLimits()
    if file_size <= 0:
        raise MovTimingParseError("MOV input is empty")
    if file_size > active_limits.max_file_bytes:
        raise MovTimingParseError(
            f"MOV input size {file_size} exceeds limit {active_limits.max_file_bytes}"
        )

    with path.open("rb") as source:
        descriptor_stat_before = os.fstat(source.fileno())
        if descriptor_stat_before.st_size != file_size:
            raise MovTimingParseError("MOV input changed before inspection could start")
        reader = _BoundedBoxReader(source, file_size, active_limits)
        top_level = reader.boxes(0, file_size, 0, "root")
        moov_box = _single_box(top_level, b"moov", "root")
        assert moov_box is not None
        moov_children = reader.boxes(moov_box.payload_start, moov_box.end, 1, moov_box.path)
        mvhd_box = _single_box(moov_children, b"mvhd", moov_box.path)
        assert mvhd_box is not None
        movie_header = _parse_mvhd(reader, mvhd_box)
        track_boxes = [box for box in moov_children if box.box_type == b"trak"]
        if not track_boxes:
            raise MovTimingParseError(f"{moov_box.path} contains no tracks")
        if len(track_boxes) > active_limits.max_tracks:
            raise MovTimingParseError(
                f"MOV track count {len(track_boxes)} exceeds limit {active_limits.max_tracks}"
            )
        tracks = tuple(
            _parse_track(reader, box, index, movie_header)
            for index, box in enumerate(track_boxes)
        )
        global_errors: list[str] = []
        track_ids = [track.track_id for track in tracks]
        if len(track_ids) != len(set(track_ids)):
            global_errors.append("duplicate track IDs")
        maximum_track_duration = max(track.track_duration_movie_units for track in tracks)
        if movie_header.duration_units != maximum_track_duration:
            global_errors.append(
                f"mvhd duration {movie_header.duration_units} != maximum tkhd duration "
                f"{maximum_track_duration}"
            )
        atom_count = reader.atom_count
        source_sha256 = _sha256_open_file(source, file_size)
        descriptor_stat_after = os.fstat(source.fileno())

    path_stat_after = path.stat()
    descriptor_changed = (
        descriptor_stat_after.st_size != descriptor_stat_before.st_size
        or descriptor_stat_after.st_mtime_ns != descriptor_stat_before.st_mtime_ns
    )
    path_changed = (
        path_stat_after.st_size != path_stat_before.st_size
        or path_stat_after.st_mtime_ns != path_stat_before.st_mtime_ns
        or (
            path_stat_before.st_ino
            and path_stat_after.st_ino
            and path_stat_before.st_ino != path_stat_after.st_ino
        )
    )
    if descriptor_changed or path_changed:
        raise MovTimingParseError("MOV input changed during inspection")

    return MovTimingReport(
        schema=SCHEMA,
        source_path=str(path),
        source_bytes=file_size,
        source_sha256=source_sha256,
        atom_count=atom_count,
        movie_header=movie_header,
        tracks=tracks,
        consistency_errors=tuple(global_errors),
    )


def verify_track_timing(
    track: TrackTimingEvidence,
    expectation: TrackTimingExpectation,
) -> tuple[str, ...]:
    """Compare parsed wire timing with an exact CP-178 expectation."""

    failures = list(track.consistency_errors)
    actual_runs = tuple(
        (run.sample_count, run.sample_delta_units) for run in track.stts.runs
    )
    if track.media_timescale != expectation.media_timescale:
        failures.append(
            f"media timescale {track.media_timescale} != {expectation.media_timescale}"
        )
    if actual_runs != expectation.stts_runs:
        failures.append(f"stts runs {actual_runs} != {expectation.stts_runs}")
    if track.stts.expanded_sample_count != expectation.expanded_sample_count:
        failures.append(
            f"sample count {track.stts.expanded_sample_count} != "
            f"{expectation.expanded_sample_count}"
        )
    if track.stts.total_duration_units != expectation.total_duration_units:
        failures.append(
            f"total duration {track.stts.total_duration_units} != "
            f"{expectation.total_duration_units}"
        )
    if track.stts.final_sample_duration_units != expectation.final_sample_duration_units:
        failures.append(
            f"final sample duration {track.stts.final_sample_duration_units} != "
            f"{expectation.final_sample_duration_units}"
        )
    if expectation.require_edit_list is not None and (
        track.edit_list.present != expectation.require_edit_list
    ):
        failures.append(
            f"edit-list presence {track.edit_list.present} != "
            f"{expectation.require_edit_list}"
        )
    return tuple(failures)


def write_mov_timing_report(report: MovTimingReport, output: Path) -> None:
    """Atomically write canonical JSON for a parsed timing report."""

    output = output.resolve()
    if output == Path(report.source_path).resolve():
        raise ValueError("MOV timing report must not overwrite its source artifact")
    output.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(report.to_dict(), indent=2, sort_keys=True) + "\n"
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{output.name}.",
        dir=output.parent,
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as target:
            target.write(payload)
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Parse bounded MOV timing atoms for NEO-QA CP-178 evidence",
    )
    parser.add_argument("input", type=Path, help="MOV artifact to inspect")
    parser.add_argument("--output", type=Path, help="Atomic JSON report path")
    arguments = parser.parse_args(argv)
    try:
        report = analyze_mov_timing(arguments.input)
        if arguments.output is not None:
            write_mov_timing_report(report, arguments.output)
        else:
            json.dump(report.to_dict(), sys.stdout, indent=2, sort_keys=True)
            sys.stdout.write("\n")
    except (OSError, MovTimingParseError, ValueError) as error:
        print(f"MOV_TIMING_ORACLE_ERROR: {error}", file=sys.stderr)
        return 2
    return 0 if report.consistent else 1


if __name__ == "__main__":
    raise SystemExit(main())
