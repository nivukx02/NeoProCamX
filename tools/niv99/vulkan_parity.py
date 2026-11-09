"""Host Vulkan execution parity for the NIV99 reconstruction shader.

This tool executes the committed SPIR-V on a real Vulkan compute queue and compares the result with
an independent scalar CPU implementation of the shader.  It intentionally targets a tiny,
deterministic linear-tiling fixture so it can run in host CI without Android or an APK build.

The optional ``vulkan`` PyPI package is only a host-test loader.  Production code does not depend on
it.  A missing loader or unsupported host GPU is reported as an explicit unsupported result rather
than being treated as successful parity evidence.
"""

from __future__ import annotations

import argparse
import json
import math
import struct
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Callable, Iterable, Sequence


REPO_ROOT = Path(__file__).resolve().parents[2]
SHADER_PATH = (
    REPO_ROOT
    / "app/src/main/cpp/generated/niv99_reconstruction.comp.spv"
)
FULL_GRAPH_SHADER_PATHS = (
    REPO_ROOT / "app/src/main/cpp/generated/niv99_statistics.comp.spv",
    REPO_ROOT / "app/src/main/cpp/generated/niv99_raw_denoise.comp.spv",
    REPO_ROOT / "app/src/main/cpp/generated/niv99_motion_confidence.comp.spv",
    REPO_ROOT / "app/src/main/cpp/generated/niv99_temporal_fusion.comp.spv",
    REPO_ROOT / "app/src/main/cpp/generated/niv99_reconstruction.comp.spv",
    REPO_ROOT / "app/src/main/cpp/generated/niv99_quality_telemetry.comp.spv",
)

WIDTH = 13
HEIGHT = 11
RGBA16F_PIXEL_BYTES = 8
UNIFORM_BYTES = 256
TILE_CONTROL_BYTES = 64
TOLERANCE = 3.0e-3


class VulkanUnavailable(RuntimeError):
    """The host cannot provide the Vulkan feature set required by this parity fixture."""


@dataclass(frozen=True)
class ParityResult:
    supported: bool
    passed: bool
    device_name: str
    driver_version: int
    compared_components: int
    maximum_absolute_error: float
    mean_absolute_error: float
    tolerance: float
    reason: str
    stage_maximum_errors: dict[str, float] = field(default_factory=dict)


@dataclass
class _Buffer:
    handle: object
    memory: object
    size: int


@dataclass
class _Image:
    handle: object
    view: object
    memory: object
    allocation_size: int
    row_pitch: int
    offset: int


@dataclass(frozen=True)
class FullGraphExecution:
    statistics: tuple[float, ...]
    denoised_raw: tuple[float, ...]
    motion: tuple[tuple[float, float, float, float], ...]
    temporal: tuple[tuple[float, float, float, float], ...]
    reconstructed: tuple[tuple[float, float, float, float], ...]
    telemetry_identity: tuple[int, ...]
    telemetry_values: tuple[float, ...]


def _clamp(value: float, lower: float = 0.0, upper: float = 1.0) -> float:
    return min(max(value, lower), upper)


def _smoothstep(lower: float, upper: float, value: float) -> float:
    position = _clamp((value - lower) / (upper - lower))
    return position * position * (3.0 - 2.0 * position)


def _mix(left: float, right: float, amount: float) -> float:
    return left + (right - left) * amount


def _half(value: float) -> float:
    return struct.unpack("<e", struct.pack("<e", value))[0]


def _fixture_images() -> tuple[list[tuple[float, float, float, float]], list[tuple[float, float, float, float]]]:
    temporal: list[tuple[float, float, float, float]] = []
    semantic: list[tuple[float, float, float, float]] = []
    for y in range(HEIGHT):
        for x in range(WIDTH):
            checker = 0.045 if ((x // 2 + y // 3) & 1) else -0.025
            ramp = 0.08 + 0.72 * (x + 0.65 * y) / (WIDTH + 0.65 * HEIGHT - 1.65)
            temporal.append(
                tuple(
                    _half(component)
                    for component in (
                        _clamp(ramp + checker + 0.035),
                        _clamp(ramp - checker * 0.35),
                        _clamp(ramp - 0.04 - checker * 0.55),
                        1.0,
                    )
                )
            )
            centre_distance = abs(x - WIDTH // 2) + abs(y - HEIGHT // 2)
            skin = 0.72 if centre_distance <= 3 else 0.08
            hair = 0.55 if y < 3 and 3 <= x <= 9 else 0.04
            text = 0.65 if y >= HEIGHT - 3 and (x & 1) == 0 else 0.02
            semantic.append(tuple(_half(value) for value in (skin, hair, 0.10, text)))
    return temporal, semantic


def _uniform_bytes(history_valid: bool = False) -> bytes:
    payload = bytearray(UNIFORM_BYTES)
    struct.pack_into("<4i", payload, 0, WIDTH, HEIGHT, 1, 1)
    struct.pack_into("<4i", payload, 16, WIDTH, HEIGHT, 0, 16)
    struct.pack_into("<4I", payload, 32, 0xA12B34C5, 7, 41, 0x10203040)
    struct.pack_into("<4I", payload, 48, 0x00000099, 0, 0, 0x00000001)
    struct.pack_into("<4I", payload, 64, 0, int(history_valid), 0, 0)
    struct.pack_into("<4f", payload, 80, 64.0, 64.0, 64.0, 64.0)
    struct.pack_into("<4f", payload, 96, 4095.0, 0.015, 0.98, 0.02)
    struct.pack_into("<4f", payload, 112, 0.0018, 0.0014, 0.0014, 0.0021)
    struct.pack_into("<4f", payload, 128, 0.000006, 0.000004, 0.000004, 0.000008)
    struct.pack_into("<4f", payload, 144, 1.25, 1.0, 1.0, 1.42)
    struct.pack_into("<4f", payload, 160, 1.0, 0.0, 0.0, 0.0)
    struct.pack_into("<4f", payload, 176, 0.0, 1.0, 0.0, 0.0)
    struct.pack_into("<4f", payload, 192, 0.0, 0.0, 1.0, 0.0)
    struct.pack_into("<4f", payload, 208, 0.0, 0.0, 0.0, 1.0)
    struct.pack_into("<4f", payload, 224, 0.85, 0.60, 0.70, 3.0)
    struct.pack_into("<4f", payload, 240, 1.0, 0.32, 0.006, 0.015)
    return bytes(payload)


def _tile_control_bytes() -> bytes:
    payload = bytearray(TILE_CONTROL_BYTES)
    struct.pack_into("<4f", payload, 0, 0.36, 0.25, 0.62, 0.55)
    struct.pack_into("<4f", payload, 16, 0.48, 0.58, 0.24, 0.28)
    struct.pack_into("<4f", payload, 32, 0.009, 0.018, 0.032, 3.0)
    struct.pack_into("<4f", payload, 48, 1.12, 1.0, 0.91, 0.67)
    return bytes(payload)


def _cpu_reconstruction(
    temporal: Sequence[tuple[float, float, float, float]],
    semantic: Sequence[tuple[float, float, float, float]],
) -> list[tuple[float, float, float, float]]:
    luma_weights = (0.2126, 0.7152, 0.0722)
    denoise = (0.36, 0.25, 0.62, 0.0)
    protection = (0.48, 0.58, 0.24, 0.28)
    thresholds = (0.009, 0.018, 0.032, 3.0)
    illuminant = (1.12, 1.0, 0.91, 0.67)

    def pixel(values: Sequence[tuple[float, float, float, float]], x: int, y: int) -> tuple[float, float, float, float]:
        return values[min(max(y, 0), HEIGHT - 1) * WIDTH + min(max(x, 0), WIDTH - 1)]

    def rgb_at(x: int, y: int) -> tuple[float, float, float]:
        source = pixel(temporal, x, y)
        return max(source[0], 0.0), max(source[1], 0.0), max(source[2], 0.0)

    def luma(rgb: Sequence[float]) -> float:
        return sum(rgb[index] * luma_weights[index] for index in range(3))

    output: list[tuple[float, float, float, float]] = []
    offsets = ((-1, 0), (1, 0), (0, -1), (0, 1))
    for y in range(HEIGHT):
        for x in range(WIDTH):
            source = pixel(temporal, x, y)
            centre = list(rgb_at(x, y))
            semantic_value = tuple(_clamp(v) for v in pixel(semantic, x, y))
            semantic_detail = max(semantic_value[1], semantic_value[2], semantic_value[3])
            edge_protection = max(_clamp(protection[0]), semantic_detail)
            texture_protection = max(_clamp(protection[1]), semantic_detail)

            skin_confidence = max(semantic_value[0], protection[2])
            local_protection = _clamp(skin_confidence) * 0.70
            local_confidence = _clamp(illuminant[3]) * (1.0 - local_protection)
            local_gain = tuple(_mix(1.0, _clamp(illuminant[i], 0.5, 2.0), local_confidence) for i in range(3))
            corrected = [max(centre[i] * local_gain[i], 0.0) for i in range(3)]
            corrected_peak = max(corrected)
            if corrected_peak > 1.0 and max(centre) <= 1.0:
                corrected = [value / corrected_peak for value in corrected]
            centre = corrected

            chroma_strength = _clamp(denoise[2])
            centre_luma = luma(centre)
            centre_chroma = [value - centre_luma for value in centre]
            chroma_sum = [value * 2.0 for value in centre_chroma]
            weight_sum = 2.0
            for ox, oy in offsets:
                sample_rgb = rgb_at(x + ox, y + oy)
                sample_luma = luma(sample_rgb)
                difference = abs(sample_luma - centre_luma)
                weight = math.exp(-difference / max(0.012 + 0.06 * (1.0 - edge_protection), 1.0e-5))
                for channel in range(3):
                    chroma_sum[channel] += (sample_rgb[channel] - sample_luma) * weight
                weight_sum += weight
            cleaned_chroma = [value / max(weight_sum, 1.0e-5) for value in chroma_sum]
            admitted = _clamp(chroma_strength * (1.0 - 0.72 * edge_protection), 0.0, 0.78)
            centre = [max(centre_luma + _mix(centre_chroma[i], cleaned_chroma[i], admitted), 0.0) for i in range(3)]

            l00 = luma(rgb_at(x - 1, y - 1))
            l10 = luma(rgb_at(x, y - 1))
            l20 = luma(rgb_at(x + 1, y - 1))
            l01 = luma(rgb_at(x - 1, y))
            l11 = luma(centre)
            l21 = luma(rgb_at(x + 1, y))
            l02 = luma(rgb_at(x - 1, y + 1))
            l12 = luma(rgb_at(x, y + 1))
            l22 = luma(rgb_at(x + 1, y + 1))
            gradient_x = (l20 + 2.0 * l21 + l22 - l00 - 2.0 * l01 - l02) * 0.125
            gradient_y = (l02 + 2.0 * l12 + l22 - l00 - 2.0 * l10 - l20) * 0.125
            tensor_xx = gradient_x * gradient_x
            tensor_yy = gradient_y * gradient_y
            tensor_xy = gradient_x * gradient_y
            trace = tensor_xx + tensor_yy
            determinant = max(tensor_xx * tensor_yy - tensor_xy * tensor_xy, 0.0)
            discriminant = math.sqrt(max(trace * trace * 0.25 - determinant, 0.0))
            lambda_high = trace * 0.5 + discriminant
            lambda_low = trace * 0.5 - discriminant
            coherence = (lambda_high - lambda_low) / max(lambda_high + lambda_low, 1.0e-7)
            local_mean = (l00 + l10 + l20 + l01 + 4.0 * l11 + l21 + l02 + l12 + l22) / 12.0
            detail_residual = l11 - local_mean
            noise_sigma = max(0.006, thresholds[0] * 0.5)
            noise_gate = _smoothstep(noise_sigma, noise_sigma * 3.5 + 1.0e-6, abs(detail_residual))
            structure_gate = max(edge_protection, texture_protection * _mix(0.70, 1.0, coherence))
            sharpening = min(_clamp(protection[3]), 0.32) * noise_gate * structure_gate
            sharpening *= 1.0 - 0.72 * _clamp(semantic_value[0])
            sharpened_luma = max(l11 + detail_residual * sharpening, 0.0)
            reconstructed = centre.copy()
            if l11 > 1.0e-6:
                reconstructed = [value * sharpened_luma / l11 for value in reconstructed]

            neighbours = [rgb_at(x + ox, y + oy) for ox, oy in offsets]
            lower = [min([centre[channel]] + [p[channel] for p in neighbours]) for channel in range(3)]
            upper = [max([centre[channel]] + [p[channel] for p in neighbours]) for channel in range(3)]
            halo_allowance = 0.015 * max(upper[channel] - lower[channel] for channel in range(3))
            reconstructed = [
                _clamp(reconstructed[channel], max(lower[channel] - halo_allowance, 0.0), upper[channel] + halo_allowance)
                for channel in range(3)
            ]
            output.append(tuple(_half(value) for value in (*reconstructed, source[3])))
    return output


def _cpu_statistics() -> tuple[float, ...]:
    signal = (1024.0 - 64.0) / (4095.0 - 64.0)
    shot = (0.0018, 0.0014, 0.0014, 0.0021)
    read = (0.000006, 0.000004, 0.000004, 0.000008)
    reliability_sum = 0.0
    for y in range(HEIGHT):
        for x in range(WIDTH):
            channel = ((y & 1) << 1) | (x & 1)
            sigma = math.sqrt(shot[channel] * signal + read[channel])
            reliability_sum += signal / max(signal + 4.0 * sigma, 1.0e-7)
    reliability = reliability_sum / (WIDTH * HEIGHT)
    return (
        signal,
        signal,
        signal,
        signal,
        0.0,
        0.0,
        0.0,
        0.0,
        reliability,
        0.0,
        0.0,
        0.0,
        1.0,
        1.0,
        reliability,
        float(WIDTH * HEIGHT),
    )


def _cpu_telemetry(
    reference: Sequence[tuple[float, float, float, float]],
    processed: Sequence[tuple[float, float, float, float]],
    motion: Sequence[tuple[float, float, float, float]],
) -> tuple[float, ...]:
    luma_weights = (0.2126, 0.7152, 0.0722)

    def pixel(values: Sequence[tuple[float, float, float, float]], x: int, y: int) -> tuple[float, ...]:
        return values[min(max(y, 0), HEIGHT - 1) * WIDTH + min(max(x, 0), WIDTH - 1)]

    def rgb(values: Sequence[tuple[float, float, float, float]], x: int, y: int) -> tuple[float, float, float]:
        value = pixel(values, x, y)
        return max(value[0], 0.0), max(value[1], 0.0), max(value[2], 0.0)

    def luma(value: Sequence[float]) -> float:
        return sum(value[index] * luma_weights[index] for index in range(3))

    def gradient(values: Sequence[tuple[float, float, float, float]], x: int, y: int) -> float:
        horizontal = luma(rgb(values, x + 1, y)) - luma(rgb(values, x - 1, y))
        vertical = luma(rgb(values, x, y + 1)) - luma(rgb(values, x, y - 1))
        return 0.5 * math.sqrt(horizontal * horizontal + vertical * vertical)

    distortion = [0.0] * 4
    detail = [0.0] * 4
    boundary = [0.0] * 3
    for y in range(HEIGHT):
        for x in range(WIDTH):
            source = rgb(reference, x, y)
            result = rgb(processed, x, y)
            difference = [result[index] - source[index] for index in range(3)]
            luma_difference = luma(difference)
            for channel in range(3):
                distortion[channel] += difference[channel] * difference[channel]
            distortion[3] += luma_difference * luma_difference
            reference_gradient = gradient(reference, x, y)
            processed_gradient = gradient(processed, x, y)
            detail[0] += reference_gradient
            detail[1] += processed_gradient
            neighbours = [
                rgb(reference, x - 1, y),
                rgb(reference, x + 1, y),
                rgb(reference, x, y - 1),
                rgb(reference, x, y + 1),
                source,
            ]
            lower = [min(value[channel] for value in neighbours) for channel in range(3)]
            upper = [max(value[channel] for value in neighbours) for channel in range(3)]
            overshoot = max(
                max(result[channel] - upper[channel], 0.0) +
                max(lower[channel] - result[channel], 0.0)
                for channel in range(3)
            )
            detail[2] += overshoot
            detail[3] += abs(luma_difference) * (1.0 - _clamp(pixel(motion, x, y)[0]))
            processed_luma = luma(result)
            neighbour_luma = 0.25 * sum(
                luma(rgb(processed, x + ox, y + oy))
                for ox, oy in ((-1, 0), (1, 0), (0, -1), (0, 1))
            )
            flat_gate = 1.0 - _smoothstep(0.002, 0.025, reference_gradient)
            boundary[2] += abs(processed_luma - neighbour_luma) * flat_gate
    count = float(WIDTH * HEIGHT)
    return tuple(value / count for value in distortion + detail + boundary) + (count,)


class _HostVulkan:
    def __init__(self) -> None:
        try:
            import vulkan as vk
        except ImportError as failure:
            raise VulkanUnavailable("Python package 'vulkan==1.3.275.1' is not installed") from failure
        self.vk = vk
        self.instance = None
        self.device = None
        self.physical_device = None
        self.queue = None
        self.queue_family = -1
        self.command_pool = None
        self._cleanup: list[Callable[[], None]] = []
        self.device_name = ""
        self.driver_version = 0
        try:
            self._initialize()
        except Exception:
            self.close()
            raise

    def _initialize(self) -> None:
        vk = self.vk
        application = vk.VkApplicationInfo(
            pApplicationName="NIV99 host parity",
            applicationVersion=1,
            pEngineName="NIV99",
            engineVersion=1,
            apiVersion=vk.VK_API_VERSION,
        )
        try:
            self.instance = vk.vkCreateInstance(
                vk.VkInstanceCreateInfo(pApplicationInfo=application), None
            )
        except Exception as failure:
            raise VulkanUnavailable(f"Vulkan instance creation failed: {failure}") from failure
        devices = vk.vkEnumeratePhysicalDevices(self.instance)
        if not devices:
            raise VulkanUnavailable("No Vulkan physical device is available")
        self.physical_device = devices[0]
        properties = vk.vkGetPhysicalDeviceProperties(self.physical_device)
        raw_device_name = properties.deviceName
        self.device_name = (
            raw_device_name
            if isinstance(raw_device_name, str)
            else bytes(vk.ffi.string(raw_device_name)).decode("utf-8", errors="replace")
        )
        self.driver_version = int(properties.driverVersion)
        features = vk.vkGetPhysicalDeviceFeatures(self.physical_device)
        if not features.shaderStorageImageExtendedFormats:
            raise VulkanUnavailable("shaderStorageImageExtendedFormats is unavailable")
        families = vk.vkGetPhysicalDeviceQueueFamilyProperties(self.physical_device)
        self.queue_family = next(
            (index for index, family in enumerate(families) if family.queueFlags & vk.VK_QUEUE_COMPUTE_BIT),
            -1,
        )
        if self.queue_family < 0:
            raise VulkanUnavailable("No Vulkan compute queue family is available")
        queue_info = vk.VkDeviceQueueCreateInfo(
            queueFamilyIndex=self.queue_family,
            queueCount=1,
            pQueuePriorities=[1.0],
        )
        enabled_features = vk.VkPhysicalDeviceFeatures(shaderStorageImageExtendedFormats=True)
        try:
            self.device = vk.vkCreateDevice(
                self.physical_device,
                vk.VkDeviceCreateInfo(
                    queueCreateInfoCount=1,
                    pQueueCreateInfos=[queue_info],
                    pEnabledFeatures=enabled_features,
                ),
                None,
            )
        except Exception as failure:
            raise VulkanUnavailable(f"Vulkan device creation failed: {failure}") from failure
        self.queue = vk.vkGetDeviceQueue(self.device, self.queue_family, 0)
        self.command_pool = vk.vkCreateCommandPool(
            self.device,
            vk.VkCommandPoolCreateInfo(
                flags=vk.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT,
                queueFamilyIndex=self.queue_family,
            ),
            None,
        )

    def _memory_type(self, bits: int, required: int) -> int:
        vk = self.vk
        properties = vk.vkGetPhysicalDeviceMemoryProperties(self.physical_device)
        for index in range(properties.memoryTypeCount):
            flags = properties.memoryTypes[index].propertyFlags
            if bits & (1 << index) and flags & required == required:
                return index
        raise VulkanUnavailable(f"No host-visible coherent Vulkan memory type for mask 0x{bits:x}")

    def create_buffer(self, payload: bytes, usage: int) -> _Buffer:
        vk = self.vk
        size = len(payload)
        handle = vk.vkCreateBuffer(
            self.device,
            vk.VkBufferCreateInfo(
                size=size,
                usage=usage,
                sharingMode=vk.VK_SHARING_MODE_EXCLUSIVE,
            ),
            None,
        )
        requirements = vk.vkGetBufferMemoryRequirements(self.device, handle)
        memory = vk.vkAllocateMemory(
            self.device,
            vk.VkMemoryAllocateInfo(
                allocationSize=requirements.size,
                memoryTypeIndex=self._memory_type(
                    requirements.memoryTypeBits,
                    vk.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | vk.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                ),
            ),
            None,
        )
        vk.vkBindBufferMemory(self.device, handle, memory, 0)
        mapped = vk.vkMapMemory(self.device, memory, 0, requirements.size, 0)
        mapped[:size] = payload
        vk.vkUnmapMemory(self.device, memory)
        resource = _Buffer(handle, memory, size)
        self._cleanup.append(lambda: vk.vkFreeMemory(self.device, memory, None))
        self._cleanup.append(lambda: vk.vkDestroyBuffer(self.device, handle, None))
        return resource

    def create_linear_image(
        self,
        image_format: int,
        rows: Sequence[bytes],
        expected_row_bytes: int,
    ) -> _Image:
        vk = self.vk
        if len(rows) != HEIGHT or any(len(row) != expected_row_bytes for row in rows):
            raise ValueError("linear image fixture has the wrong extent or row size")
        format_properties = vk.vkGetPhysicalDeviceFormatProperties(
            self.physical_device, image_format
        )
        if not format_properties.linearTilingFeatures & vk.VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT:
            raise VulkanUnavailable(
                f"Vulkan format {image_format} lacks linear storage-image support"
            )
        handle = vk.vkCreateImage(
            self.device,
            vk.VkImageCreateInfo(
                imageType=vk.VK_IMAGE_TYPE_2D,
                format=image_format,
                extent=vk.VkExtent3D(width=WIDTH, height=HEIGHT, depth=1),
                mipLevels=1,
                arrayLayers=1,
                samples=vk.VK_SAMPLE_COUNT_1_BIT,
                tiling=vk.VK_IMAGE_TILING_LINEAR,
                usage=vk.VK_IMAGE_USAGE_STORAGE_BIT,
                sharingMode=vk.VK_SHARING_MODE_EXCLUSIVE,
                initialLayout=vk.VK_IMAGE_LAYOUT_PREINITIALIZED,
            ),
            None,
        )
        requirements = vk.vkGetImageMemoryRequirements(self.device, handle)
        memory = vk.vkAllocateMemory(
            self.device,
            vk.VkMemoryAllocateInfo(
                allocationSize=requirements.size,
                memoryTypeIndex=self._memory_type(
                    requirements.memoryTypeBits,
                    vk.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | vk.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                ),
            ),
            None,
        )
        vk.vkBindImageMemory(self.device, handle, memory, 0)
        subresource = vk.VkImageSubresource(
            aspectMask=vk.VK_IMAGE_ASPECT_COLOR_BIT,
            mipLevel=0,
            arrayLayer=0,
        )
        layout = vk.vkGetImageSubresourceLayout(self.device, handle, subresource)
        mapped = vk.vkMapMemory(self.device, memory, 0, requirements.size, 0)
        storage = mapped
        for y, row in enumerate(rows):
            offset = int(layout.offset + y * layout.rowPitch)
            storage[offset : offset + len(row)] = row
        vk.vkUnmapMemory(self.device, memory)
        view = vk.vkCreateImageView(
            self.device,
            vk.VkImageViewCreateInfo(
                image=handle,
                viewType=vk.VK_IMAGE_VIEW_TYPE_2D,
                format=image_format,
                components=vk.VkComponentMapping(
                    r=vk.VK_COMPONENT_SWIZZLE_IDENTITY,
                    g=vk.VK_COMPONENT_SWIZZLE_IDENTITY,
                    b=vk.VK_COMPONENT_SWIZZLE_IDENTITY,
                    a=vk.VK_COMPONENT_SWIZZLE_IDENTITY,
                ),
                subresourceRange=vk.VkImageSubresourceRange(
                    aspectMask=vk.VK_IMAGE_ASPECT_COLOR_BIT,
                    baseMipLevel=0,
                    levelCount=1,
                    baseArrayLayer=0,
                    layerCount=1,
                ),
            ),
            None,
        )
        resource = _Image(
            handle=handle,
            view=view,
            memory=memory,
            allocation_size=int(requirements.size),
            row_pitch=int(layout.rowPitch),
            offset=int(layout.offset),
        )
        self._cleanup.append(lambda: vk.vkFreeMemory(self.device, memory, None))
        self._cleanup.append(lambda: vk.vkDestroyImage(self.device, handle, None))
        self._cleanup.append(lambda: vk.vkDestroyImageView(self.device, view, None))
        return resource

    def create_rgba16f_image(
        self, pixels: Sequence[tuple[float, float, float, float]]
    ) -> _Image:
        if len(pixels) != WIDTH * HEIGHT:
            raise ValueError("RGBA16F fixture has the wrong pixel count")
        rows = [
            b"".join(struct.pack("<4e", *pixels[y * WIDTH + x]) for x in range(WIDTH))
            for y in range(HEIGHT)
        ]
        return self.create_linear_image(
            self.vk.VK_FORMAT_R16G16B16A16_SFLOAT,
            rows,
            WIDTH * RGBA16F_PIXEL_BYTES,
        )

    def read_rgba16f(self, image: _Image) -> list[tuple[float, float, float, float]]:
        vk = self.vk
        mapped = vk.vkMapMemory(self.device, image.memory, 0, image.allocation_size, 0)
        storage = mapped
        pixels: list[tuple[float, float, float, float]] = []
        for y in range(HEIGHT):
            row_start = image.offset + y * image.row_pitch
            for x in range(WIDTH):
                offset = row_start + x * RGBA16F_PIXEL_BYTES
                pixels.append(struct.unpack("<4e", bytes(storage[offset : offset + RGBA16F_PIXEL_BYTES])))
        vk.vkUnmapMemory(self.device, image.memory)
        return pixels

    def read_linear_values(self, image: _Image, format_code: str, pixel_bytes: int) -> tuple[float, ...]:
        vk = self.vk
        mapped = vk.vkMapMemory(self.device, image.memory, 0, image.allocation_size, 0)
        values: list[float] = []
        components = len(struct.unpack(format_code, bytes(mapped[image.offset : image.offset + pixel_bytes])))
        for y in range(HEIGHT):
            row_start = image.offset + y * image.row_pitch
            for x in range(WIDTH):
                offset = row_start + x * pixel_bytes
                unpacked = struct.unpack(format_code, bytes(mapped[offset : offset + pixel_bytes]))
                values.extend(float(unpacked[index]) for index in range(components))
        vk.vkUnmapMemory(self.device, image.memory)
        return tuple(values)

    def read_buffer(self, buffer: _Buffer) -> bytes:
        vk = self.vk
        mapped = vk.vkMapMemory(self.device, buffer.memory, 0, buffer.size, 0)
        payload = bytes(mapped[: buffer.size])
        vk.vkUnmapMemory(self.device, buffer.memory)
        return payload

    def execute_reconstruction(self) -> list[tuple[float, float, float, float]]:
        vk = self.vk
        temporal, semantic = _fixture_images()
        semantic_image = self.create_rgba16f_image(semantic)
        temporal_image = self.create_rgba16f_image(temporal)
        output_image = self.create_rgba16f_image([(0.0, 0.0, 0.0, 0.0)] * (WIDTH * HEIGHT))
        tile_buffer = self.create_buffer(_tile_control_bytes(), vk.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
        uniform_buffer = self.create_buffer(_uniform_bytes(), vk.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT)

        bindings = [
            vk.VkDescriptorSetLayoutBinding(
                binding=binding,
                descriptorType=descriptor_type,
                descriptorCount=1,
                stageFlags=vk.VK_SHADER_STAGE_COMPUTE_BIT,
            )
            for binding, descriptor_type in (
                (8, vk.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                (10, vk.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                (11, vk.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE),
                (13, vk.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER),
                (15, vk.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER),
            )
        ]
        descriptor_layout = vk.vkCreateDescriptorSetLayout(
            self.device,
            vk.VkDescriptorSetLayoutCreateInfo(
                bindingCount=len(bindings),
                pBindings=bindings,
            ),
            None,
        )
        self._cleanup.append(
            lambda: vk.vkDestroyDescriptorSetLayout(self.device, descriptor_layout, None)
        )
        pipeline_layout = vk.vkCreatePipelineLayout(
            self.device,
            vk.VkPipelineLayoutCreateInfo(setLayoutCount=1, pSetLayouts=[descriptor_layout]),
            None,
        )
        self._cleanup.append(lambda: vk.vkDestroyPipelineLayout(self.device, pipeline_layout, None))

        shader_bytes = SHADER_PATH.read_bytes()
        shader_module = vk.vkCreateShaderModule(
            self.device,
            vk.VkShaderModuleCreateInfo(codeSize=len(shader_bytes), pCode=shader_bytes),
            None,
        )
        self._cleanup.append(lambda: vk.vkDestroyShaderModule(self.device, shader_module, None))
        stage = vk.VkPipelineShaderStageCreateInfo(
            stage=vk.VK_SHADER_STAGE_COMPUTE_BIT,
            module=shader_module,
            pName="main",
        )
        pipelines = vk.vkCreateComputePipelines(
            self.device,
            vk.VK_NULL_HANDLE,
            1,
            [vk.VkComputePipelineCreateInfo(stage=stage, layout=pipeline_layout)],
            None,
        )
        pipeline = pipelines[0]
        self._cleanup.append(lambda: vk.vkDestroyPipeline(self.device, pipeline, None))

        pool_sizes = [
            vk.VkDescriptorPoolSize(type=vk.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, descriptorCount=3),
            vk.VkDescriptorPoolSize(type=vk.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, descriptorCount=1),
            vk.VkDescriptorPoolSize(type=vk.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, descriptorCount=1),
        ]
        descriptor_pool = vk.vkCreateDescriptorPool(
            self.device,
            vk.VkDescriptorPoolCreateInfo(
                maxSets=1,
                poolSizeCount=len(pool_sizes),
                pPoolSizes=pool_sizes,
            ),
            None,
        )
        self._cleanup.append(lambda: vk.vkDestroyDescriptorPool(self.device, descriptor_pool, None))
        descriptor_set = vk.vkAllocateDescriptorSets(
            self.device,
            vk.VkDescriptorSetAllocateInfo(
                descriptorPool=descriptor_pool,
                descriptorSetCount=1,
                pSetLayouts=[descriptor_layout],
            ),
        )[0]
        image_infos = {
            8: vk.VkDescriptorImageInfo(
                imageView=semantic_image.view, imageLayout=vk.VK_IMAGE_LAYOUT_GENERAL
            ),
            10: vk.VkDescriptorImageInfo(
                imageView=temporal_image.view, imageLayout=vk.VK_IMAGE_LAYOUT_GENERAL
            ),
            11: vk.VkDescriptorImageInfo(
                imageView=output_image.view, imageLayout=vk.VK_IMAGE_LAYOUT_GENERAL
            ),
        }
        buffer_infos = {
            13: vk.VkDescriptorBufferInfo(buffer=tile_buffer.handle, offset=0, range=tile_buffer.size),
            15: vk.VkDescriptorBufferInfo(buffer=uniform_buffer.handle, offset=0, range=uniform_buffer.size),
        }
        writes = [
            vk.VkWriteDescriptorSet(
                dstSet=descriptor_set,
                dstBinding=binding,
                descriptorCount=1,
                descriptorType=vk.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                pImageInfo=[info],
            )
            for binding, info in image_infos.items()
        ]
        writes.extend(
            vk.VkWriteDescriptorSet(
                dstSet=descriptor_set,
                dstBinding=binding,
                descriptorCount=1,
                descriptorType=(
                    vk.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
                    if binding == 15
                    else vk.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                ),
                pBufferInfo=[info],
            )
            for binding, info in buffer_infos.items()
        )
        vk.vkUpdateDescriptorSets(self.device, len(writes), writes, 0, None)

        command_buffer = vk.vkAllocateCommandBuffers(
            self.device,
            vk.VkCommandBufferAllocateInfo(
                commandPool=self.command_pool,
                level=vk.VK_COMMAND_BUFFER_LEVEL_PRIMARY,
                commandBufferCount=1,
            ),
        )[0]
        vk.vkBeginCommandBuffer(
            command_buffer,
            vk.VkCommandBufferBeginInfo(flags=vk.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT),
        )
        barriers = [
            vk.VkImageMemoryBarrier(
                srcAccessMask=vk.VK_ACCESS_HOST_WRITE_BIT,
                dstAccessMask=vk.VK_ACCESS_SHADER_READ_BIT | vk.VK_ACCESS_SHADER_WRITE_BIT,
                oldLayout=vk.VK_IMAGE_LAYOUT_PREINITIALIZED,
                newLayout=vk.VK_IMAGE_LAYOUT_GENERAL,
                srcQueueFamilyIndex=vk.VK_QUEUE_FAMILY_IGNORED,
                dstQueueFamilyIndex=vk.VK_QUEUE_FAMILY_IGNORED,
                image=image.handle,
                subresourceRange=vk.VkImageSubresourceRange(
                    aspectMask=vk.VK_IMAGE_ASPECT_COLOR_BIT,
                    baseMipLevel=0,
                    levelCount=1,
                    baseArrayLayer=0,
                    layerCount=1,
                ),
            )
            for image in (semantic_image, temporal_image, output_image)
        ]
        vk.vkCmdPipelineBarrier(
            command_buffer,
            vk.VK_PIPELINE_STAGE_HOST_BIT,
            vk.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0,
            0,
            None,
            0,
            None,
            len(barriers),
            barriers,
        )
        vk.vkCmdBindPipeline(
            command_buffer, vk.VK_PIPELINE_BIND_POINT_COMPUTE, pipeline
        )
        vk.vkCmdBindDescriptorSets(
            command_buffer,
            vk.VK_PIPELINE_BIND_POINT_COMPUTE,
            pipeline_layout,
            0,
            1,
            [descriptor_set],
            0,
            None,
        )
        vk.vkCmdDispatch(command_buffer, (WIDTH + 15) // 16, (HEIGHT + 15) // 16, 1)
        output_barrier = vk.VkImageMemoryBarrier(
            srcAccessMask=vk.VK_ACCESS_SHADER_WRITE_BIT,
            dstAccessMask=vk.VK_ACCESS_HOST_READ_BIT,
            oldLayout=vk.VK_IMAGE_LAYOUT_GENERAL,
            newLayout=vk.VK_IMAGE_LAYOUT_GENERAL,
            srcQueueFamilyIndex=vk.VK_QUEUE_FAMILY_IGNORED,
            dstQueueFamilyIndex=vk.VK_QUEUE_FAMILY_IGNORED,
            image=output_image.handle,
            subresourceRange=vk.VkImageSubresourceRange(
                aspectMask=vk.VK_IMAGE_ASPECT_COLOR_BIT,
                baseMipLevel=0,
                levelCount=1,
                baseArrayLayer=0,
                layerCount=1,
            ),
        )
        vk.vkCmdPipelineBarrier(
            command_buffer,
            vk.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            vk.VK_PIPELINE_STAGE_HOST_BIT,
            0,
            0,
            None,
            0,
            None,
            1,
            [output_barrier],
        )
        vk.vkEndCommandBuffer(command_buffer)
        fence = vk.vkCreateFence(self.device, vk.VkFenceCreateInfo(), None)
        self._cleanup.append(lambda: vk.vkDestroyFence(self.device, fence, None))
        vk.vkQueueSubmit(
            self.queue,
            1,
            [vk.VkSubmitInfo(commandBufferCount=1, pCommandBuffers=[command_buffer])],
            fence,
        )
        vk.vkWaitForFences(self.device, 1, [fence], True, 5_000_000_000)
        return self.read_rgba16f(output_image)

    def execute_full_graph(self) -> FullGraphExecution:
        vk = self.vk
        temporal_pixels, semantic_pixels = _fixture_images()

        def rows(format_code: str, values: Sequence[Sequence[float | int]]) -> list[bytes]:
            return [
                b"".join(
                    struct.pack(format_code, *values[y * WIDTH + x])
                    for x in range(WIDTH)
                )
                for y in range(HEIGHT)
            ]

        raw_values = [(1024,)] * (WIDTH * HEIGHT)
        lens_values = [(1.0, 1.0, 1.0, 1.0)] * (WIDTH * HEIGHT)
        defect_values = [(0,)] * (WIDTH * HEIGHT)
        zero_r32 = [(0.0,)] * (WIDTH * HEIGHT)
        zero_rg16 = [(0.0, 0.0)] * (WIDTH * HEIGHT)
        zero_rgba16 = [(0.0, 0.0, 0.0, 0.0)] * (WIDTH * HEIGHT)
        image_specs = (
            (vk.VK_FORMAT_R16_UINT, rows("<H", raw_values), WIDTH * 2),
            (vk.VK_FORMAT_R16G16B16A16_SFLOAT, rows("<4e", lens_values), WIDTH * 8),
            (vk.VK_FORMAT_R8_UINT, rows("<B", defect_values), WIDTH),
            (vk.VK_FORMAT_R32_SFLOAT, rows("<f", zero_r32), WIDTH * 4),
            (vk.VK_FORMAT_R16G16B16A16_SFLOAT, rows("<4e", temporal_pixels), WIDTH * 8),
            (vk.VK_FORMAT_R16G16B16A16_SFLOAT, rows("<4e", temporal_pixels), WIDTH * 8),
            (vk.VK_FORMAT_R16G16_SFLOAT, rows("<2e", zero_rg16), WIDTH * 4),
            (vk.VK_FORMAT_R16G16_SFLOAT, rows("<2e", zero_rg16), WIDTH * 4),
            (vk.VK_FORMAT_R16G16B16A16_SFLOAT, rows("<4e", semantic_pixels), WIDTH * 8),
            (vk.VK_FORMAT_R16G16B16A16_SFLOAT, rows("<4e", zero_rgba16), WIDTH * 8),
            (vk.VK_FORMAT_R16G16B16A16_SFLOAT, rows("<4e", zero_rgba16), WIDTH * 8),
            (vk.VK_FORMAT_R16G16B16A16_SFLOAT, rows("<4e", zero_rgba16), WIDTH * 8),
        )
        images = [self.create_linear_image(*specification) for specification in image_specs]
        statistics_buffer = self.create_buffer(bytes(64), vk.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
        tile_buffer = self.create_buffer(_tile_control_bytes(), vk.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
        telemetry_buffer = self.create_buffer(bytes(80), vk.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT)
        uniform_buffer = self.create_buffer(
            _uniform_bytes(history_valid=True), vk.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
        )

        bindings = [
            vk.VkDescriptorSetLayoutBinding(
                binding=binding,
                descriptorType=(
                    vk.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                    if binding < 12
                    else vk.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
                    if binding == 15
                    else vk.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                ),
                descriptorCount=1,
                stageFlags=vk.VK_SHADER_STAGE_COMPUTE_BIT,
            )
            for binding in range(16)
        ]
        descriptor_layout = vk.vkCreateDescriptorSetLayout(
            self.device,
            vk.VkDescriptorSetLayoutCreateInfo(
                bindingCount=len(bindings), pBindings=bindings
            ),
            None,
        )
        self._cleanup.append(
            lambda: vk.vkDestroyDescriptorSetLayout(self.device, descriptor_layout, None)
        )
        pipeline_layout = vk.vkCreatePipelineLayout(
            self.device,
            vk.VkPipelineLayoutCreateInfo(setLayoutCount=1, pSetLayouts=[descriptor_layout]),
            None,
        )
        self._cleanup.append(lambda: vk.vkDestroyPipelineLayout(self.device, pipeline_layout, None))
        pipelines = []
        for shader_path in FULL_GRAPH_SHADER_PATHS:
            shader_bytes = shader_path.read_bytes()
            shader_module = vk.vkCreateShaderModule(
                self.device,
                vk.VkShaderModuleCreateInfo(codeSize=len(shader_bytes), pCode=shader_bytes),
                None,
            )
            stage = vk.VkPipelineShaderStageCreateInfo(
                stage=vk.VK_SHADER_STAGE_COMPUTE_BIT,
                module=shader_module,
                pName="main",
            )
            pipeline = vk.vkCreateComputePipelines(
                self.device,
                vk.VK_NULL_HANDLE,
                1,
                [vk.VkComputePipelineCreateInfo(stage=stage, layout=pipeline_layout)],
                None,
            )[0]
            vk.vkDestroyShaderModule(self.device, shader_module, None)
            pipelines.append(pipeline)
            self._cleanup.append(lambda pipeline=pipeline: vk.vkDestroyPipeline(self.device, pipeline, None))

        pool_sizes = [
            vk.VkDescriptorPoolSize(type=vk.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, descriptorCount=12),
            vk.VkDescriptorPoolSize(type=vk.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, descriptorCount=3),
            vk.VkDescriptorPoolSize(type=vk.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, descriptorCount=1),
        ]
        descriptor_pool = vk.vkCreateDescriptorPool(
            self.device,
            vk.VkDescriptorPoolCreateInfo(
                maxSets=1,
                poolSizeCount=len(pool_sizes),
                pPoolSizes=pool_sizes,
            ),
            None,
        )
        self._cleanup.append(lambda: vk.vkDestroyDescriptorPool(self.device, descriptor_pool, None))
        descriptor_set = vk.vkAllocateDescriptorSets(
            self.device,
            vk.VkDescriptorSetAllocateInfo(
                descriptorPool=descriptor_pool,
                descriptorSetCount=1,
                pSetLayouts=[descriptor_layout],
            ),
        )[0]
        writes = [
            vk.VkWriteDescriptorSet(
                dstSet=descriptor_set,
                dstBinding=binding,
                descriptorCount=1,
                descriptorType=vk.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,
                pImageInfo=[
                    vk.VkDescriptorImageInfo(
                        imageView=image.view, imageLayout=vk.VK_IMAGE_LAYOUT_GENERAL
                    )
                ],
            )
            for binding, image in enumerate(images)
        ]
        buffers = (statistics_buffer, tile_buffer, telemetry_buffer, uniform_buffer)
        for binding, buffer in zip(range(12, 16), buffers):
            writes.append(
                vk.VkWriteDescriptorSet(
                    dstSet=descriptor_set,
                    dstBinding=binding,
                    descriptorCount=1,
                    descriptorType=(
                        vk.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER
                        if binding == 15
                        else vk.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER
                    ),
                    pBufferInfo=[
                        vk.VkDescriptorBufferInfo(
                            buffer=buffer.handle, offset=0, range=buffer.size
                        )
                    ],
                )
            )
        vk.vkUpdateDescriptorSets(self.device, len(writes), writes, 0, None)

        command_buffer = vk.vkAllocateCommandBuffers(
            self.device,
            vk.VkCommandBufferAllocateInfo(
                commandPool=self.command_pool,
                level=vk.VK_COMMAND_BUFFER_LEVEL_PRIMARY,
                commandBufferCount=1,
            ),
        )[0]
        vk.vkBeginCommandBuffer(
            command_buffer,
            vk.VkCommandBufferBeginInfo(flags=vk.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT),
        )
        subresource_range = vk.VkImageSubresourceRange(
            aspectMask=vk.VK_IMAGE_ASPECT_COLOR_BIT,
            baseMipLevel=0,
            levelCount=1,
            baseArrayLayer=0,
            layerCount=1,
        )
        initial_barriers = [
            vk.VkImageMemoryBarrier(
                srcAccessMask=vk.VK_ACCESS_HOST_WRITE_BIT,
                dstAccessMask=vk.VK_ACCESS_SHADER_READ_BIT | vk.VK_ACCESS_SHADER_WRITE_BIT,
                oldLayout=vk.VK_IMAGE_LAYOUT_PREINITIALIZED,
                newLayout=vk.VK_IMAGE_LAYOUT_GENERAL,
                srcQueueFamilyIndex=vk.VK_QUEUE_FAMILY_IGNORED,
                dstQueueFamilyIndex=vk.VK_QUEUE_FAMILY_IGNORED,
                image=image.handle,
                subresourceRange=subresource_range,
            )
            for image in images
        ]
        vk.vkCmdPipelineBarrier(
            command_buffer,
            vk.VK_PIPELINE_STAGE_HOST_BIT,
            vk.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0,
            0,
            None,
            0,
            None,
            len(initial_barriers),
            initial_barriers,
        )
        vk.vkCmdBindDescriptorSets(
            command_buffer,
            vk.VK_PIPELINE_BIND_POINT_COMPUTE,
            pipeline_layout,
            0,
            1,
            [descriptor_set],
            0,
            None,
        )

        def dispatch(index: int, groups_x: int, groups_y: int) -> None:
            vk.vkCmdBindPipeline(
                command_buffer, vk.VK_PIPELINE_BIND_POINT_COMPUTE, pipelines[index]
            )
            vk.vkCmdDispatch(command_buffer, groups_x, groups_y, 1)

        def image_dependency(image: _Image) -> None:
            barrier = vk.VkImageMemoryBarrier(
                srcAccessMask=vk.VK_ACCESS_SHADER_WRITE_BIT,
                dstAccessMask=vk.VK_ACCESS_SHADER_READ_BIT,
                oldLayout=vk.VK_IMAGE_LAYOUT_GENERAL,
                newLayout=vk.VK_IMAGE_LAYOUT_GENERAL,
                srcQueueFamilyIndex=vk.VK_QUEUE_FAMILY_IGNORED,
                dstQueueFamilyIndex=vk.VK_QUEUE_FAMILY_IGNORED,
                image=image.handle,
                subresourceRange=subresource_range,
            )
            vk.vkCmdPipelineBarrier(
                command_buffer,
                vk.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                vk.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0,
                0,
                None,
                0,
                None,
                1,
                [barrier],
            )

        dispatch(0, 1, 1)
        statistics_dependency = vk.VkBufferMemoryBarrier(
            srcAccessMask=vk.VK_ACCESS_SHADER_WRITE_BIT,
            dstAccessMask=vk.VK_ACCESS_SHADER_READ_BIT,
            srcQueueFamilyIndex=vk.VK_QUEUE_FAMILY_IGNORED,
            dstQueueFamilyIndex=vk.VK_QUEUE_FAMILY_IGNORED,
            buffer=statistics_buffer.handle,
            offset=0,
            size=statistics_buffer.size,
        )
        vk.vkCmdPipelineBarrier(
            command_buffer,
            vk.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            vk.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0,
            0,
            None,
            1,
            [statistics_dependency],
            0,
            None,
        )
        image_groups_x = (WIDTH + 15) // 16
        image_groups_y = (HEIGHT + 15) // 16
        dispatch(1, image_groups_x, image_groups_y)
        dispatch(2, image_groups_x, image_groups_y)
        image_dependency(images[9])
        dispatch(3, image_groups_x, image_groups_y)
        image_dependency(images[10])
        dispatch(4, image_groups_x, image_groups_y)
        image_dependency(images[11])
        dispatch(5, 1, 1)

        final_image_barriers = [
            vk.VkImageMemoryBarrier(
                srcAccessMask=vk.VK_ACCESS_SHADER_WRITE_BIT,
                dstAccessMask=vk.VK_ACCESS_HOST_READ_BIT,
                oldLayout=vk.VK_IMAGE_LAYOUT_GENERAL,
                newLayout=vk.VK_IMAGE_LAYOUT_GENERAL,
                srcQueueFamilyIndex=vk.VK_QUEUE_FAMILY_IGNORED,
                dstQueueFamilyIndex=vk.VK_QUEUE_FAMILY_IGNORED,
                image=images[index].handle,
                subresourceRange=subresource_range,
            )
            for index in (3, 9, 10, 11)
        ]
        final_buffer_barriers = [
            vk.VkBufferMemoryBarrier(
                srcAccessMask=vk.VK_ACCESS_SHADER_WRITE_BIT,
                dstAccessMask=vk.VK_ACCESS_HOST_READ_BIT,
                srcQueueFamilyIndex=vk.VK_QUEUE_FAMILY_IGNORED,
                dstQueueFamilyIndex=vk.VK_QUEUE_FAMILY_IGNORED,
                buffer=buffer.handle,
                offset=0,
                size=buffer.size,
            )
            for buffer in (statistics_buffer, telemetry_buffer)
        ]
        vk.vkCmdPipelineBarrier(
            command_buffer,
            vk.VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            vk.VK_PIPELINE_STAGE_HOST_BIT,
            0,
            0,
            None,
            len(final_buffer_barriers),
            final_buffer_barriers,
            len(final_image_barriers),
            final_image_barriers,
        )
        vk.vkEndCommandBuffer(command_buffer)
        fence = vk.vkCreateFence(self.device, vk.VkFenceCreateInfo(), None)
        self._cleanup.append(lambda: vk.vkDestroyFence(self.device, fence, None))
        vk.vkQueueSubmit(
            self.queue,
            1,
            [vk.VkSubmitInfo(commandBufferCount=1, pCommandBuffers=[command_buffer])],
            fence,
        )
        vk.vkWaitForFences(self.device, 1, [fence], True, 5_000_000_000)

        statistics = struct.unpack("<16f", self.read_buffer(statistics_buffer))
        telemetry_payload = self.read_buffer(telemetry_buffer)
        return FullGraphExecution(
            statistics=tuple(statistics),
            denoised_raw=self.read_linear_values(images[3], "<f", 4),
            motion=tuple(self.read_rgba16f(images[9])),
            temporal=tuple(self.read_rgba16f(images[10])),
            reconstructed=tuple(self.read_rgba16f(images[11])),
            telemetry_identity=tuple(struct.unpack_from("<8I", telemetry_payload, 0)),
            telemetry_values=tuple(struct.unpack_from("<12f", telemetry_payload, 32)),
        )

    def close(self) -> None:
        vk = self.vk
        if self.device is not None:
            try:
                vk.vkDeviceWaitIdle(self.device)
            except Exception:
                pass
            while self._cleanup:
                self._cleanup.pop()()
            if self.command_pool is not None:
                vk.vkDestroyCommandPool(self.device, self.command_pool, None)
                self.command_pool = None
            vk.vkDestroyDevice(self.device, None)
            self.device = None
        if self.instance is not None:
            vk.vkDestroyInstance(self.instance, None)
            self.instance = None


def run_reconstruction_parity() -> ParityResult:
    harness: _HostVulkan | None = None
    try:
        harness = _HostVulkan()
        temporal, semantic = _fixture_images()
        expected = _cpu_reconstruction(temporal, semantic)
        actual = harness.execute_reconstruction()
        differences = [
            abs(actual_pixel[channel] - expected_pixel[channel])
            for actual_pixel, expected_pixel in zip(actual, expected)
            for channel in range(4)
        ]
        maximum_error = max(differences, default=0.0)
        mean_error = sum(differences) / max(len(differences), 1)
        return ParityResult(
            supported=True,
            passed=maximum_error <= TOLERANCE,
            device_name=harness.device_name,
            driver_version=harness.driver_version,
            compared_components=len(differences),
            maximum_absolute_error=maximum_error,
            mean_absolute_error=mean_error,
            tolerance=TOLERANCE,
            reason="" if maximum_error <= TOLERANCE else "GPU output exceeded the parity tolerance",
            stage_maximum_errors={"reconstruction": maximum_error},
        )
    except VulkanUnavailable as failure:
        return ParityResult(
            supported=False,
            passed=False,
            device_name=harness.device_name if harness is not None else "",
            driver_version=harness.driver_version if harness is not None else 0,
            compared_components=0,
            maximum_absolute_error=0.0,
            mean_absolute_error=0.0,
            tolerance=TOLERANCE,
            reason=str(failure),
        )
    finally:
        if harness is not None:
            harness.close()


def run_full_graph_parity() -> ParityResult:
    harness: _HostVulkan | None = None
    try:
        harness = _HostVulkan()
        execution = harness.execute_full_graph()
        temporal, semantic = _fixture_images()
        expected_reconstruction = _cpu_reconstruction(temporal, semantic)
        expected_statistics = _cpu_statistics()
        calibrated_raw = (1024.0 - 64.0) / (4095.0 - 64.0)
        expected_motion = ((1.0, 1.0, 1.0, 0.0),) * (WIDTH * HEIGHT)
        expected_telemetry = _cpu_telemetry(
            temporal, execution.reconstructed, execution.motion
        )

        def differences(
            actual: Iterable[float], expected: Iterable[float]
        ) -> list[float]:
            return [abs(left - right) for left, right in zip(actual, expected)]

        statistics_error = differences(execution.statistics, expected_statistics)
        raw_error = differences(
            execution.denoised_raw,
            (calibrated_raw,) * (WIDTH * HEIGHT),
        )
        motion_error = differences(
            (value for pixel in execution.motion for value in pixel),
            (value for pixel in expected_motion for value in pixel),
        )
        temporal_error = differences(
            (value for pixel in execution.temporal for value in pixel),
            (value for pixel in temporal for value in pixel),
        )
        reconstruction_error = differences(
            (value for pixel in execution.reconstructed for value in pixel),
            (value for pixel in expected_reconstruction for value in pixel),
        )
        telemetry_error = differences(execution.telemetry_values, expected_telemetry)
        stage_errors = {
            "statistics": max(statistics_error, default=0.0),
            "raw_denoise": max(raw_error, default=0.0),
            "motion_confidence": max(motion_error, default=0.0),
            "temporal_fusion": max(temporal_error, default=0.0),
            "reconstruction": max(reconstruction_error, default=0.0),
            "quality_telemetry": max(telemetry_error, default=0.0),
        }
        stage_tolerances = {
            "statistics": 2.0e-5,
            "raw_denoise": 2.0e-5,
            "motion_confidence": 2.0e-5,
            # The shader performs opponent-space round trips before writing RGBA16F. One half-float
            # ULP at this fixture's signal level is 4.8828125e-4.
            "temporal_fusion": 6.0e-4,
            "reconstruction": TOLERANCE,
            "quality_telemetry": 5.0e-4,
        }
        expected_identity = (
            0xA12B34C5,
            7,
            41,
            0x10203040,
            0x00000099,
            0,
            0,
            1,
        )
        identity_matches = execution.telemetry_identity == expected_identity
        passed = identity_matches and all(
            stage_errors[name] <= stage_tolerances[name] for name in stage_errors
        )
        all_errors = (
            statistics_error
            + raw_error
            + motion_error
            + temporal_error
            + reconstruction_error
            + telemetry_error
        )
        reason = ""
        if not identity_matches:
            reason = "quality telemetry did not preserve the complete frame identity"
        elif not passed:
            failed_stages = [
                name
                for name, error in stage_errors.items()
                if error > stage_tolerances[name]
            ]
            reason = "GPU output exceeded parity tolerance for: " + ", ".join(failed_stages)
        return ParityResult(
            supported=True,
            passed=passed,
            device_name=harness.device_name,
            driver_version=harness.driver_version,
            compared_components=len(all_errors) + len(expected_identity),
            maximum_absolute_error=max(all_errors, default=0.0),
            mean_absolute_error=sum(all_errors) / max(len(all_errors), 1),
            tolerance=TOLERANCE,
            reason=reason,
            stage_maximum_errors=stage_errors,
        )
    except VulkanUnavailable as failure:
        return ParityResult(
            supported=False,
            passed=False,
            device_name=harness.device_name if harness is not None else "",
            driver_version=harness.driver_version if harness is not None else 0,
            compared_components=0,
            maximum_absolute_error=0.0,
            mean_absolute_error=0.0,
            tolerance=TOLERANCE,
            reason=str(failure),
        )
    finally:
        if harness is not None:
            harness.close()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, help="Optional JSON evidence path")
    parser.add_argument(
        "--stage",
        choices=("full", "reconstruction"),
        default="full",
        help="Execute the complete six-stage graph or only reconstruction",
    )
    arguments = parser.parse_args()
    result = (
        run_full_graph_parity()
        if arguments.stage == "full"
        else run_reconstruction_parity()
    )
    payload = asdict(result)
    encoded = json.dumps(payload, indent=2, sort_keys=True)
    print(encoded)
    if arguments.output is not None:
        arguments.output.parent.mkdir(parents=True, exist_ok=True)
        arguments.output.write_text(encoded + "\n", encoding="utf-8")
    return 0 if result.supported and result.passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
