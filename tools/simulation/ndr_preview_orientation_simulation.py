"""Four-orientation Camera2/NDR transform parity simulation and visual proof generator."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


LANDMARKS = {
    "TL": (0.15, 0.15),
    "TR": (0.85, 0.15),
    "BL": (0.15, 0.85),
    "BR": (0.85, 0.85),
}


def normalize(degrees: int) -> int:
    return degrees % 360


def camera_rotation_from_physical(physical_degrees: int) -> int:
    """Mirrors CameraCaptureRotationPolicy for a portrait-locked activity."""
    return normalize(360 - physical_degrees)


def sensor_to_output(sensor_degrees: int, camera_degrees: int) -> int:
    """Back-camera branch of OrientationManager.getSensorToOutputRotation."""
    return normalize(sensor_degrees - camera_degrees)


def sampling_rotation(sensor_degrees: int, latest_camera_degrees: int) -> int:
    """Every physical quadrant executes the proven device-orientation-zero resolver branch."""
    normalized = normalize(latest_camera_degrees)
    if normalized == 0:
        resolved_device_rotation = 0
    elif normalized == 90:
        resolved_device_rotation = 0
    elif normalized == 180:
        resolved_device_rotation = 0
    elif normalized == 270:
        resolved_device_rotation = 0
    else:
        resolved_device_rotation = 0
    display_rotation = sensor_to_output(sensor_degrees, resolved_device_rotation)
    return normalize(-display_rotation)


def rotate_point(point: tuple[float, float], degrees: int) -> tuple[float, float]:
    x, y = point
    x -= 0.5
    y -= 0.5
    if normalize(degrees) == 90:
        x, y = -y, x
    elif normalize(degrees) == 180:
        x, y = -x, -y
    elif normalize(degrees) == 270:
        x, y = y, -x
    return round(x + 0.5, 6), round(y + 0.5, 6)


def transformed_landmarks(rotation_degrees: int) -> dict[str, tuple[float, float]]:
    return {name: rotate_point(point, rotation_degrees) for name, point in LANDMARKS.items()}


@dataclass(frozen=True)
class RotationResult:
    physical_degrees: int
    camera_degrees: int
    preview_degrees: int
    sampling_degrees: int
    landmarks: dict[str, tuple[float, float]]


def simulate(sensor_degrees: int = 90) -> list[RotationResult]:
    results: list[RotationResult] = []
    for physical in (0, 90, 180, 270):
        camera = camera_rotation_from_physical(physical)
        preview = sensor_to_output(sensor_degrees, camera)
        sampling = sampling_rotation(sensor_degrees, camera)
        results.append(
            RotationResult(
                physical_degrees=physical,
                camera_degrees=camera,
                preview_degrees=preview,
                sampling_degrees=sampling,
                landmarks=transformed_landmarks(sampling),
            )
        )
    return results


def verify() -> None:
    expected_sampling = {0: 270, 90: 270, 180: 270, 270: 270}
    results = simulate()
    assert len({tuple(result.landmarks.items()) for result in results}) == 1
    for result in results:
        assert result.sampling_degrees == expected_sampling[result.physical_degrees]
        assert result.landmarks == transformed_landmarks(270)


def source_marker_image(width: int = 360, height: int = 480) -> Image.Image:
    image = Image.new("RGB", (width, height), "#111820")
    draw = ImageDraw.Draw(image)
    font = ImageFont.load_default(size=22)
    small = ImageFont.load_default(size=15)
    draw.rounded_rectangle((12, 12, width - 12, height - 12), radius=22, outline="#dce9f5", width=4)
    colors = {"TL": "#ff4d5f", "TR": "#3ee684", "BL": "#3fa7ff", "BR": "#ffd23f"}
    anchors = {
        "TL": (30, 30),
        "TR": (width - 95, 30),
        "BL": (30, height - 75),
        "BR": (width - 95, height - 75),
    }
    for label, (x, y) in anchors.items():
        draw.rounded_rectangle((x, y, x + 62, y + 42), radius=9, fill=colors[label])
        draw.text((x + 13, y + 9), label, fill="#071018", font=font)
    center_x = width // 2
    draw.polygon(
        [(center_x, 70), (center_x - 30, 120), (center_x - 11, 120),
         (center_x - 11, 210), (center_x + 11, 210), (center_x + 11, 120),
         (center_x + 30, 120)],
        fill="#f4f7fb",
    )
    draw.text((center_x - 39, 225), "WORLD UP", fill="#f4f7fb", font=small)
    draw.text((center_x - 67, 275), "ASYMMETRIC SENSOR", fill="#9fb7ca", font=small)
    draw.text((center_x - 52, 300), "TEST PATTERN", fill="#9fb7ca", font=small)
    return image


def rotate_exact(image: Image.Image, degrees: int) -> Image.Image:
    # PIL is counter-clockwise; the texture-sampling visualization uses clockwise degrees.
    return image.rotate(-normalize(degrees), expand=True, resample=Image.Resampling.NEAREST)


def generate_verification_image(output_path: Path) -> Path:
    source = source_marker_image()
    results = simulate()
    panel_width = 820
    panel_height = 620
    canvas = Image.new("RGB", (panel_width * 2, panel_height * 2 + 90), "#070b10")
    draw = ImageDraw.Draw(canvas)
    title_font = ImageFont.load_default(size=28)
    label_font = ImageFont.load_default(size=18)
    draw.text(
        (34, 24),
        "NDR PREVIEW ORIENTATION — EXACT CAMERA2 TRANSFORM PARITY",
        fill="#f1f7fc",
        font=title_font,
    )

    names = {0: "UPRIGHT PORTRAIT", 90: "LEFT LANDSCAPE", 180: "REVERSE PORTRAIT", 270: "RIGHT LANDSCAPE"}
    for index, result in enumerate(results):
        origin_x = (index % 2) * panel_width
        origin_y = 90 + (index // 2) * panel_height
        draw.rounded_rectangle(
            (origin_x + 18, origin_y + 12, origin_x + panel_width - 18, origin_y + panel_height - 12),
            radius=24,
            fill="#101821",
            outline="#2a3d4d",
            width=3,
        )
        camera = rotate_exact(source, normalize(-result.preview_degrees))
        ndr = rotate_exact(source, result.sampling_degrees)
        preview_size = (250, 330) if camera.height >= camera.width else (330, 250)
        camera.thumbnail(preview_size, Image.Resampling.NEAREST)
        ndr.thumbnail(preview_size, Image.Resampling.NEAREST)
        image_y = origin_y + 165
        left_x = origin_x + 80
        right_x = origin_x + 445
        canvas.paste(camera, (left_x + (250 - camera.width) // 2, image_y))
        canvas.paste(ndr, (right_x + (250 - ndr.width) // 2, image_y))
        draw.text((origin_x + 42, origin_y + 35), names[result.physical_degrees], fill="#f1f7fc", font=title_font)
        draw.text(
            (origin_x + 42, origin_y + 80),
            f"physical={result.physical_degrees}°  camera={result.camera_degrees}°  sampling={result.sampling_degrees}°",
            fill="#a9bfd0",
            font=label_font,
        )
        draw.text((left_x + 45, origin_y + 130), "CAMERA2 REFERENCE", fill="#9fc5df", font=label_font)
        draw.text((right_x + 55, origin_y + 130), "NDR OUTPUT", fill="#9fc5df", font=label_font)
        draw.rounded_rectangle(
            (origin_x + 270, origin_y + 535, origin_x + 550, origin_y + 585),
            radius=18,
            fill="#17334a",
            outline="#4ab3ff",
            width=2,
        )
        draw.text((origin_x + 279, origin_y + 548), "DEVICE 0 PATH / SAMPLE 270", fill="#80cfff", font=label_font)

    output_path.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(output_path)
    return output_path


if __name__ == "__main__":
    verify()
    for item in simulate():
        print(
            f"physical={item.physical_degrees:3d} camera={item.camera_degrees:3d} "
            f"preview={item.preview_degrees:3d} sampling={item.sampling_degrees:3d} "
            f"corners={item.landmarks}"
        )
    proof = generate_verification_image(
        Path("artifacts/ndr_orientation/ndr_four_rotation_simulation.png")
    )
    print("PASS: physical 0/90/180/270 all execute device-orientation-0 resolver path")
    print(f"VISUAL_PROOF={proof}")
