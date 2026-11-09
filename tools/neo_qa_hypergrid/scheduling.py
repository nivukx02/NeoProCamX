"""Deterministic dependency and exclusive-device scheduling for HyperGrid."""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from .schema import CheckpointGraph, CheckpointNode


class SchedulingError(ValueError):
    """Base error for an invalid or unschedulable checkpoint graph."""


class ScheduleCycleError(SchedulingError):
    """Raised when no topological progress is possible."""


@dataclass(frozen=True, slots=True)
class ScheduleWave:
    """One parallel wave with at most one exclusive physical-device action."""

    index: int
    host_checkpoints: tuple[str, ...]
    external_checkpoints: tuple[str, ...]
    device_checkpoint: str | None

    @property
    def checkpoints(self) -> tuple[str, ...]:
        values = self.host_checkpoints + self.external_checkpoints
        if self.device_checkpoint is not None:
            values += (self.device_checkpoint,)
        return values

    def to_dict(self) -> dict[str, Any]:
        return {
            "index": self.index,
            "host_checkpoints": list(self.host_checkpoints),
            "external_checkpoints": list(self.external_checkpoints),
            "device_checkpoint": self.device_checkpoint,
        }


@dataclass(frozen=True, slots=True)
class ParallelSchedule:
    """Complete topological schedule with a globally serialized device lane."""

    waves: tuple[ScheduleWave, ...]
    exclusive_device_sequence: tuple[str, ...]

    def wave_for(self, checkpoint_id: str) -> ScheduleWave:
        for wave in self.waves:
            if checkpoint_id in wave.checkpoints:
                return wave
        raise KeyError(f"Checkpoint not scheduled: {checkpoint_id}")

    def to_dict(self) -> dict[str, Any]:
        return {
            "wave_count": len(self.waves),
            "exclusive_device_count": len(self.exclusive_device_sequence),
            "exclusive_device_sequence": list(self.exclusive_device_sequence),
            "waves": [wave.to_dict() for wave in self.waves],
        }


def _validate_references(nodes: tuple[CheckpointNode, ...]) -> None:
    node_ids = {node.checkpoint_id for node in nodes}
    if len(node_ids) != len(nodes):
        raise SchedulingError("Cannot schedule duplicate checkpoint IDs")
    for node in nodes:
        unknown = sorted(set(node.prerequisites) - node_ids)
        if unknown:
            raise SchedulingError(
                f"{node.checkpoint_id} has unknown prerequisites: {', '.join(unknown)}"
            )
        if node.checkpoint_id in node.prerequisites:
            raise ScheduleCycleError(
                f"{node.checkpoint_id} depends on itself"
            )


def build_parallel_schedule(graph: CheckpointGraph) -> ParallelSchedule:
    """Return a deterministic topological schedule for every graph node.

    All ready host checkpoints are placed into the current parallel wave.  At
    most one ready device checkpoint is selected, enforcing the single ADB and
    camera-controller rule globally.  External-owner actions are represented
    separately so they cannot be mistaken for automated host work.
    """

    nodes = tuple(sorted(graph.nodes, key=lambda node: node.ordinal))
    _validate_references(nodes)
    remaining = {node.checkpoint_id: node for node in nodes}
    completed: set[str] = set()
    waves: list[ScheduleWave] = []

    while remaining:
        ready = [
            node
            for node in remaining.values()
            if set(node.prerequisites).issubset(completed)
        ]
        ready.sort(key=lambda node: node.ordinal)
        if not ready:
            unresolved = ", ".join(sorted(remaining))
            raise ScheduleCycleError(
                "Dependency cycle prevents scheduling; unresolved checkpoints: "
                + unresolved
            )

        device_ready = [node for node in ready if node.requires_device]
        external_ready = [
            node
            for node in ready
            if not node.requires_device and node.execution_target == "external"
        ]
        host_ready = [
            node
            for node in ready
            if not node.requires_device and node.execution_target != "external"
        ]

        selected_device = device_ready[0] if device_ready else None
        selected = host_ready + external_ready
        if selected_device is not None:
            selected.append(selected_device)
        if not selected:
            # This is defensive; every valid target is assigned to one lane.
            raise SchedulingError("Ready checkpoints could not be assigned to a lane")

        wave = ScheduleWave(
            index=len(waves),
            host_checkpoints=tuple(node.checkpoint_id for node in host_ready),
            external_checkpoints=tuple(
                node.checkpoint_id for node in external_ready
            ),
            device_checkpoint=(
                selected_device.checkpoint_id
                if selected_device is not None
                else None
            ),
        )
        waves.append(wave)
        for node in selected:
            completed.add(node.checkpoint_id)
            del remaining[node.checkpoint_id]

    device_sequence = tuple(
        wave.device_checkpoint
        for wave in waves
        if wave.device_checkpoint is not None
    )
    schedule = ParallelSchedule(
        waves=tuple(waves),
        exclusive_device_sequence=device_sequence,
    )
    validate_exclusive_device_schedule(graph, schedule)
    return schedule


def exclusive_device_sequence(graph: CheckpointGraph) -> tuple[str, ...]:
    """Convenience accessor for the sole permitted physical-device lane."""

    return build_parallel_schedule(graph).exclusive_device_sequence


def validate_exclusive_device_schedule(
    graph: CheckpointGraph,
    schedule: ParallelSchedule,
) -> None:
    """Validate coverage, dependency order, and global device serialization."""

    expected = {node.checkpoint_id for node in graph.nodes}
    scheduled = [checkpoint for wave in schedule.waves for checkpoint in wave.checkpoints]
    if len(scheduled) != len(set(scheduled)):
        raise SchedulingError("Schedule contains a checkpoint more than once")
    if set(scheduled) != expected:
        missing = sorted(expected - set(scheduled))
        extra = sorted(set(scheduled) - expected)
        raise SchedulingError(
            f"Schedule coverage mismatch; missing={missing}, extra={extra}"
        )
    if any(
        len([value for value in (wave.device_checkpoint,) if value is not None]) > 1
        for wave in schedule.waves
    ):
        raise SchedulingError("A schedule wave has multiple device controllers")

    wave_index = {
        checkpoint: wave.index
        for wave in schedule.waves
        for checkpoint in wave.checkpoints
    }
    for node in graph.nodes:
        for prerequisite in node.prerequisites:
            if wave_index[prerequisite] >= wave_index[node.checkpoint_id]:
                raise SchedulingError(
                    f"Dependency order violated: {prerequisite} must precede "
                    f"{node.checkpoint_id}"
                )

    expected_device = {
        node.checkpoint_id for node in graph.nodes if node.requires_device
    }
    if set(schedule.exclusive_device_sequence) != expected_device:
        raise SchedulingError("Exclusive device sequence does not cover device nodes")
    if len(schedule.exclusive_device_sequence) != len(expected_device):
        raise SchedulingError("Exclusive device sequence contains duplicates")

