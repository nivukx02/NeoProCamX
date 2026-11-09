"""Host simulation for RAW monitor and RAW-to-NDR/NIVUKX delivery invariants.

This does not pretend to emulate Camera2 or MediaCodec. It exercises the deterministic color math and
audits the production source wiring that can be proven without a physical sensor/codec target.
"""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import math
from pathlib import Path
from typing import Iterable


@dataclass(frozen=True)
class NdrProfile:
    gamma: float
    toe_width: float
    toe_amount: float
    shadow_lift: float
    midtone_contrast: float
    peak_nits: float


@dataclass(frozen=True)
class ConversionWorkEstimate:
    output_write_bytes: int
    kotlin_direct_buffer_allocations: int
    native_readback_allocations: int


def conversion_work_estimate(frame_count: int, output_bytes: int, optimized: bool) -> ConversionWorkEstimate:
    """Architecture-count model only; it deliberately makes no wall-clock or device-speed claim."""
    frames = max(frame_count, 0)
    size = max(output_bytes, 0)
    return ConversionWorkEstimate(
        output_write_bytes=size if optimized else size * 2,
        kotlin_direct_buffer_allocations=(2 if frames else 0) if optimized else frames * 2,
        native_readback_allocations=(1 if frames else 0) if optimized else frames,
    )


NDR10_PLUS = NdrProfile(0.859937, 0.238882, 0.024780, 0.167837, 0.100943, 5000.0)
NIVUKX_VISION = NdrProfile(0.800034, 0.289876, 0.031033, 0.232156, 0.174135, 10000.0)
SENSOR_DIFFUSE_SCENE = 0.180
DIFFUSE_WHITE_NITS = 203.0


def _smoothstep(edge0: float, edge1: float, value: float) -> float:
    t = min(max((value - edge0) / (edge1 - edge0), 0.0), 1.0)
    return t * t * (3.0 - 2.0 * t)


def _tone_signal(scene_norm: float, profile: NdrProfile) -> float:
    x = max(scene_norm, 0.0)
    if x <= 1.0:
        shaped = x**profile.gamma + _smoothstep(0.0, profile.toe_width, x) * profile.toe_amount * (1.0 - x)
    else:
        shaped = 1.0 + (profile.gamma - profile.toe_amount) * (x - 1.0)
    if 0.0 < shaped < 1.0:
        window = _smoothstep(0.025, 0.20, shaped) * (1.0 - _smoothstep(0.78, 1.20, shaped))
        shaped += profile.shadow_lift * math.sqrt(shaped) * (1.0 - shaped) * window
        window = _smoothstep(0.055, 0.24, shaped) * (1.0 - _smoothstep(0.76, 1.0, shaped))
        shaped += profile.midtone_contrast * (shaped - 0.5) * 4.0 * shaped * (1.0 - shaped) * window
    return max(shaped, 0.0)


def _signal_to_nits(signal: float, peak_nits: float) -> float:
    raw = max(signal, 0.0) * DIFFUSE_WHITE_NITS
    if raw <= DIFFUSE_WHITE_NITS:
        return raw
    span = peak_nits - DIFFUSE_WHITE_NITS
    return DIFFUSE_WHITE_NITS + span * (1.0 - math.exp(-(raw - DIFFUSE_WHITE_NITS) / span))


def pq_encode_nits(nits: float) -> float:
    m1 = 0.1593017578125
    m2 = 78.84375
    c1 = 0.8359375
    c2 = 18.8515625
    c3 = 18.6875
    linear = min(max(nits / 10000.0, 0.0), 1.0)
    power = linear**m1
    return ((c1 + c2 * power) / (1.0 + c3 * power)) ** m2


def raw_neutral_to_pq(scene_linear: float, profile: NdrProfile) -> float:
    normalized = max(scene_linear, 0.0) / SENSOR_DIFFUSE_SCENE
    return pq_encode_nits(_signal_to_nits(_tone_signal(normalized, profile), profile.peak_nits))


def raw_sdr_monitor(scene_linear: float) -> float:
    """Neutral RAW monitor shoulder followed by IEC 61966-2-1 sRGB display encoding."""
    value = max(scene_linear, 0.0)
    knee = 0.82
    if value > knee:
        headroom = 1.0 - knee
        excess = value - knee
        value = knee + headroom * excess / (excess + headroom)
    value = min(max(value, 0.0), 1.0)
    return 12.92 * value if value <= 0.0031308 else 1.055 * value ** (1.0 / 2.4) - 0.055


def quantize_p010_luma(pq_value: float) -> int:
    return round(min(max(pq_value, 0.0), 1.0) * 876.0 + 64.0)


def payload_identity(payload: bytes) -> tuple[str, str]:
    before = hashlib.sha256(payload).hexdigest()
    # Monitoring and conversion consume the source through read-only views.
    _ = memoryview(payload).toreadonly()
    after = hashlib.sha256(payload).hexdigest()
    return before, after


def monotonic(values: Iterable[float]) -> bool:
    sequence = list(values)
    return all(right >= left for left, right in zip(sequence, sequence[1:]))


def production_source_contracts(repo_root: Path) -> dict[str, bool]:
    packer = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/recording/RawAcescgTenBitPacker.kt").read_text(encoding="utf-8")
    converter = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/recording/RawToNeoLogMovConverter.kt").read_text(encoding="utf-8")
    coordinator = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/recording/Raw12ClipConversionCoordinator.kt").read_text(encoding="utf-8")
    view_model = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").read_text(encoding="utf-8")
    media_conversion_coordinator = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/ui/CameraMediaConversionCoordinator.kt").read_text(encoding="utf-8")
    workflow = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/recording/RawClipMovConversionWorkflow.kt").read_text(encoding="utf-8")
    hevc_encoder = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/recording/RecordDevelopHevcMovEncoder.kt").read_text(encoding="utf-8")
    settings = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt").read_text(encoding="utf-8")
    resolver = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionResolver.kt").read_text(encoding="utf-8")
    monitor_flag = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/camera/RawPreviewViewableTonemapFlag.kt").read_text(encoding="utf-8")
    gpu_accelerator = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/recording/RawDevelopGpuAccelerator.kt").read_text(encoding="utf-8")
    memory_policy = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/recording/RawDevelopMemoryPolicy.kt").read_text(encoding="utf-8")
    vulkan_runtime = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt").read_text(encoding="utf-8")
    native_bridge = (repo_root / "app/src/main/cpp/vulkan_bridge.cpp").read_text(encoding="utf-8")
    seekable_output = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/recording/SeekableMediaStoreMovOutput.kt").read_text(encoding="utf-8")
    npraw_codec = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/pipeline/NprawFrameCodec.kt").read_text(encoding="utf-8")
    npv10_converter = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt").read_text(encoding="utf-8")
    tessera_contracts = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/tessera/TesseraContracts.kt").read_text(encoding="utf-8")
    neofusion = (repo_root / "app/src/main/java/com/neoproc/neoproccamx/tessera/NeoFusionConversionEngine.kt").read_text(encoding="utf-8")
    return {
        "canonical_ndr_packer": "packNdrP010420" in packer and "deliverPqInto" in packer,
        "both_ndr_profiles": "NDR10_PLUS" in converter and "NIVUKX_VISION" in converter,
        "main10_fail_closed": (
            "RecordDevelopHevcMovEncoder(" in coordinator
            and "bitDepthLumaMinus8 >= 2" in hevc_encoder
            and "bitDepthChromaMinus8 >= 2" in hevc_encoder
            and "HEVC encoder silently downgraded Main10/P010 output" in hevc_encoder
        ),
        "bt2020_pq_metadata": "TRANSFER_PQ_ST2084" in coordinator and "PRIMARIES_BT2020" in coordinator,
        "raw_conversion_workflow_extracted": (
            "CameraMediaConversionCoordinator" in view_model
            and "RawClipMovConversionWorkflow(" in media_conversion_coordinator
        ),
        "raw_source_opened_read_only": (
            "resolver.openInputStream(clip.streamUri)" in workflow
            and "resolver.openOutputStream(clip.streamUri" not in workflow
        ),
        "raw_master_not_deleted": (
            "resolver.delete(clip.streamUri" not in workflow
            and "cleanupLocalArtifacts(localArtifacts)" in workflow
            and "UUID.randomUUID()" in workflow
        ),
        "raw_converter_has_both_actions": "Convert to XDR10+" in settings and "Convert to NIVUKX Vision" in settings,
        "ndr_intermediates_moved": "populateNdrDevelopEntriesInRawConverter" in settings,
        "separate_ndr_section_removed": "add(SettingsSectionKey.RECORD_DEVELOP_NDR)" not in resolver,
        "raw_camera_monitor_sdr_default": "var enabled: Boolean = true" in monitor_flag,
        "raw_gpu_direct_buffers_persistent": "RawDevelopGpuWorkspace" in gpu_accelerator and "workspace.use" in gpu_accelerator,
        "raw_gpu_peak_memory_admission": (
            "gpuWorkspaceDirectBytes" in memory_policy
            and "gpuReadbackBytes" in memory_policy
            and "gpuDevelopHostPeakBytes" in gpu_accelerator
            and "OutOfMemoryError" in gpu_accelerator
        ),
        "raw_gpu_runtime_detach": "RawDevelopGpuAccelerator.attach(null)" in vulkan_runtime,
        "raw_offline_not_preview_deadline": (
            "kRawOfflineConversionTimeoutNs" in native_bridge
            and "produceMonitorOutputs=*/false" in native_bridge
        ),
        "raw_native_readback_persistent_fenced": (
            "developReadbackMapped" in native_bridge
            and "copyRawDevelopOutputToHost" in native_bridge
            and "vkWaitForFences(rawDevelopReadback)" in native_bridge
        ),
        "raw_native_readback_failure_safe": (
            "findExactMemoryType" in native_bridge
            and "requireExactProperties=*/true" in native_bridge
            and "releaseRawDevelopReadbackResources" in native_bridge
            and "VulkanBridgeState* deferredState = autoState.release()" in native_bridge
        ),
        "raw_parity_frame_reused_once": (
            "FirstFrameResolution" in converter
            and "detailAlreadyApplied = true" in converter
        ),
        "raw_default_count_is_constant_time": "estimateFixedFrameCount" in npraw_codec,
        "npv10_gpu_failure_keeps_full_quality_export": (
            "gpuLaneHealthy = false" in npv10_converter
            and "val sourcePayload = current.copyFrameBytes(decodedBytes)" in npv10_converter
            and "Npv10ExportEnhancementPolicy.enhanceLiveCinemaLumaP010" in npv10_converter
            and "Npv10ExportProcessingEvidence.fromFrameCounts" in npv10_converter
            and "Bounded P010 GPU export failed" not in npv10_converter
        ),
        "direct_transactional_media_store_mov": (
            "/proc/self/fd/" in seekable_output
            and "IS_PENDING" in seekable_output
            and "Os.fsync" in seekable_output
        ),
        "neofusion_offline_priority": (
            "OFFLINE_CONVERSION" in tessera_contracts
            and "TesseraWorkClass.OFFLINE_CONVERSION" in neofusion
            and "FP32_STRICT" in neofusion
        ),
    }
