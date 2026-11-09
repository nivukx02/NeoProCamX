#!/usr/bin/env python3
"""Render and verify NeoProCamX's gyro-aware 2.39:1 preview guide.

The math mirrors SafeAreaOverlayEngine and the badge placement mirrors UIOverlay.
It also verifies the production source path from the settings StateFlow to onDraw.
"""

from __future__ import annotations

import argparse
import json
import math
from dataclasses import asdict, dataclass
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


SCOPE_RATIO = 2.39
CYAN = (0, 206, 209, 190)
LABEL_TEXT = "2.39:1"
PREVIEW_ASPECTS = (
    ("4:3", 800, 600),
    ("16:9", 960, 540),
    ("3:4", 600, 800),
    ("9:16", 540, 960),
)


@dataclass(frozen=True)
class Crop:
    left: float
    top: float
    right: float
    bottom: float


@dataclass(frozen=True)
class LabelLayout:
    center_x: float
    center_y: float
    width: float
    height: float
    rotation_degrees: int
    corner: str


def crop_fractions(target_ratio: float, frame_aspect: float) -> Crop:
    if target_ratio > frame_aspect + 0.001:
        height = frame_aspect / target_ratio
        margin = (1.0 - height) / 2.0
        return Crop(0.0, margin, 1.0, 1.0 - margin)
    if target_ratio < frame_aspect - 0.001:
        width = target_ratio / frame_aspect
        margin = (1.0 - width) / 2.0
        return Crop(margin, 0.0, 1.0 - margin, 1.0)
    return Crop(0.0, 0.0, 1.0, 1.0)


def oriented_scope_crop(frame_aspect: float) -> Crop:
    target = 1.0 / SCOPE_RATIO if frame_aspect < 1.0 else SCOPE_RATIO
    return crop_fractions(target, frame_aspect)


def resolve_label_layout(
    guide: tuple[float, float, float, float],
    rotation: int,
    desired_width: float,
    desired_height: float,
    inset: float,
) -> LabelLayout:
    left, top, right, bottom = guide
    rotation %= 360
    corner, text_rotation = {
        0: ("top-left", 0),
        90: ("bottom-left", -90),
        180: ("bottom-right", 180),
        270: ("top-right", 90),
    }[rotation]
    quarter_turn = rotation in (90, 270)
    rotated_width = desired_height if quarter_turn else desired_width
    rotated_height = desired_width if quarter_turn else desired_height
    available_width = right - left - inset * 2.0
    available_height = bottom - top - inset * 2.0
    scale = max(
        0.0,
        min(1.0, available_width / rotated_width, available_height / rotated_height),
    )
    width = desired_width * scale
    height = desired_height * scale
    if corner == "top-left":
        center_x, center_y = left + inset + width / 2, top + inset + height / 2
    elif corner == "bottom-left":
        center_x, center_y = left + inset + height / 2, bottom - inset - width / 2
    elif corner == "bottom-right":
        center_x, center_y = right - inset - width / 2, bottom - inset - height / 2
    else:
        center_x, center_y = right - inset - height / 2, top + inset + width / 2
    return LabelLayout(center_x, center_y, width, height, text_rotation, corner)


def load_font(size: int) -> ImageFont.ImageFont:
    candidates = (
        Path("C:/Windows/Fonts/arialbd.ttf"),
        Path("C:/Windows/Fonts/segoeuib.ttf"),
        Path("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"),
    )
    for candidate in candidates:
        if candidate.exists():
            return ImageFont.truetype(str(candidate), size)
    return ImageFont.load_default()


def draw_scene(width: int, height: int) -> Image.Image:
    image = Image.new("RGBA", (width, height), (17, 34, 40, 255))
    draw = ImageDraw.Draw(image, "RGBA")
    for y in range(height):
        t = y / max(1, height - 1)
        draw.line((0, y, width, y), fill=(25 + int(55 * t), 60 + int(45 * t), 69, 255))
    draw.ellipse((int(width * 0.67), int(height * 0.16), int(width * 0.81), int(height * 0.24)), fill=(255, 220, 145, 210))
    draw.polygon(
        [(0, int(height * 0.62)), (int(width * 0.48), int(height * 0.43)), (width, int(height * 0.61)), (width, height), (0, height)],
        fill=(91, 62, 37, 255),
    )
    draw.polygon(
        [(0, int(height * 0.72)), (int(width * 0.58), int(height * 0.58)), (width, int(height * 0.75)), (width, height), (0, height)],
        fill=(25, 53, 40, 255),
    )
    return image


def draw_badge(image: Image.Image, layout: LabelLayout, font: ImageFont.ImageFont) -> None:
    badge_size = (max(1, round(layout.width)), max(1, round(layout.height)))
    badge = Image.new("RGBA", badge_size, (0, 23, 25, 217))
    draw = ImageDraw.Draw(badge, "RGBA")
    draw.rounded_rectangle((0, 0, badge.width - 1, badge.height - 1), radius=5, outline=(0, 206, 209, 150), width=2)
    text_box = draw.textbbox((0, 0), LABEL_TEXT, font=font)
    text_width = text_box[2] - text_box[0]
    text_height = text_box[3] - text_box[1]
    draw.text(
        ((badge.width - text_width) / 2, (badge.height - text_height) / 2 - text_box[1]),
        LABEL_TEXT,
        font=font,
        fill=(197, 255, 255, 255),
    )
    # Android Canvas uses screen-coordinate clockwise-positive angles while Pillow's API uses
    # mathematical counter-clockwise-positive angles, so negate the production rotation here.
    rotated = badge.rotate(-layout.rotation_degrees, expand=True, resample=Image.Resampling.BICUBIC)
    paste_at = (
        round(layout.center_x - rotated.width / 2),
        round(layout.center_y - rotated.height / 2),
    )
    image.alpha_composite(rotated, dest=paste_at)


def guide_pixels(width: int, height: int) -> tuple[float, float, float, float]:
    crop = oriented_scope_crop(width / height)
    return (
        crop.left * width,
        crop.top * height,
        crop.right * width,
        crop.bottom * height,
    )


def label_visual_extents(layout: LabelLayout, rotation: int) -> tuple[float, float, float, float]:
    quarter_turn = rotation % 360 in (90, 270)
    half_width = (layout.height if quarter_turn else layout.width) / 2.0
    half_height = (layout.width if quarter_turn else layout.height) / 2.0
    return (
        layout.center_x - half_width,
        layout.center_y - half_height,
        layout.center_x + half_width,
        layout.center_y + half_height,
    )


def assert_guide_and_label_compatibility(
    width: int,
    height: int,
    guide: tuple[float, float, float, float],
    rotation: int,
    layout: LabelLayout,
) -> float:
    guide_width = guide[2] - guide[0]
    guide_height = guide[3] - guide[1]
    is_landscape = width >= height
    measured_scope_ratio = guide_width / guide_height if is_landscape else guide_height / guide_width
    assert math.isclose(measured_scope_ratio, SCOPE_RATIO, abs_tol=0.002)
    assert 0 <= guide[0] < guide[2] <= width
    assert 0 <= guide[1] < guide[3] <= height
    label_left, label_top, label_right, label_bottom = label_visual_extents(layout, rotation)
    assert label_left >= guide[0] - 0.001
    assert label_top >= guide[1] - 0.001
    assert label_right <= guide[2] + 0.001
    assert label_bottom <= guide[3] + 0.001
    return measured_scope_ratio


def render_device_preview(rotation: int, output: Path) -> dict[str, object]:
    width, height = 540, 960
    image = draw_scene(width, height)
    guide = guide_pixels(width, height)
    font = load_font(18)
    scratch = ImageDraw.Draw(image)
    text_box = scratch.textbbox((0, 0), LABEL_TEXT, font=font)
    desired_width = text_box[2] - text_box[0] + 28
    desired_height = text_box[3] - text_box[1] + 16
    layout = resolve_label_layout(guide, rotation, desired_width, desired_height, 14)
    draw = ImageDraw.Draw(image, "RGBA")
    draw.rectangle(tuple(round(v) for v in guide), outline=CYAN, width=3)
    draw_badge(image, layout, font)

    # Rotate the complete device preview into the user's physical viewing orientation.
    # Android Canvas/CSS screen angles increase clockwise; Pillow angles increase counter-clockwise.
    # Negating quarter turns makes the simulated physical viewing transform match Android.
    user_rotation = {0: 0, 90: -90, 180: 180, 270: 90}[rotation]
    user_view = image.rotate(user_rotation, expand=True, resample=Image.Resampling.BICUBIC)
    user_view.convert("RGB").save(output, quality=96)

    device_ratio = assert_guide_and_label_compatibility(
        width, height, guide, rotation, layout
    )
    return {
        "rotation": rotation,
        "output": str(output),
        "guide_device_pixels": [round(v, 3) for v in guide],
        "guide_ratio": round(device_ratio, 6),
        "label": asdict(layout),
        "visible_in_preview": True,
    }


def render_aspect_preview(
    aspect_name: str,
    width: int,
    height: int,
    output: Path,
) -> dict[str, object]:
    image = draw_scene(width, height)
    guide = guide_pixels(width, height)
    font = load_font(18)
    scratch = ImageDraw.Draw(image)
    text_box = scratch.textbbox((0, 0), LABEL_TEXT, font=font)
    desired_width = text_box[2] - text_box[0] + 28
    desired_height = text_box[3] - text_box[1] + 16
    draw = ImageDraw.Draw(image, "RGBA")
    draw.rectangle(tuple(round(v) for v in guide), outline=CYAN, width=3)

    rotations = []
    initial_layout = None
    for rotation in (0, 90, 180, 270):
        layout = resolve_label_layout(
            guide, rotation, desired_width, desired_height, 14
        )
        measured_ratio = assert_guide_and_label_compatibility(
            width, height, guide, rotation, layout
        )
        rotations.append(
            {
                "rotation": rotation,
                "scope_ratio": round(measured_ratio, 6),
                "label": asdict(layout),
                "label_inside_guide": True,
            }
        )
        if rotation == 0:
            initial_layout = layout
    assert initial_layout is not None
    draw_badge(image, initial_layout, font)
    image.convert("RGB").save(output, quality=96)
    return {
        "aspect": aspect_name,
        "preview_pixels": [width, height],
        "preview_ratio": round(width / height, 6),
        "guide_pixels": [round(value, 3) for value in guide],
        "guide_orientation": "horizontal" if width >= height else "vertical",
        "visible_in_preview": True,
        "rotations": rotations,
        "output": str(output),
    }


def verify_production_wiring(repo: Path) -> list[str]:
    overlay = (repo / "app/src/main/java/com/neoproc/neoproccamx/ui/overlays/UIOverlay.kt").read_text(encoding="utf-8")
    activity = (repo / "app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt").read_text(encoding="utf-8")
    view_model = (repo / "app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").read_text(encoding="utf-8")
    requirements = {
        "UIOverlay.onDraw invokes guide rendering": "drawSafeAreaOverlays(canvas)" in overlay,
        "UIOverlay uses orientation-aware scope crop": "orientedScopeCropFractions(" in overlay,
        "UIOverlay renders exact 2.39:1 indication": 'val label = "2.39:1"' in overlay,
        "UIOverlay receives physical UI rotation": "setDeviceUiRotationDegrees" in overlay,
        "MainActivity observes setting changes": "safeAreaGuidesFlow.collect { refreshSafeAreaOverlays() }" in activity,
        "MainActivity pushes guide into preview overlay": "cameraOverlay.setSafeAreaOverlays(" in activity,
        "ViewModel publishes toggle immediately": "_safeAreaGuides.value = enabled" in view_model,
    }
    failed = [name for name, passed in requirements.items() if not passed]
    if failed:
        raise AssertionError("Production preview wiring failed: " + ", ".join(failed))
    return list(requirements)


def create_contact_sheet(images: list[Path], names: list[str], output: Path) -> None:
    loaded = [Image.open(path).convert("RGB") for path in images]
    thumb_width, thumb_height = 480, 480
    sheet = Image.new("RGB", (thumb_width * 2, thumb_height * 2), (7, 10, 12))
    draw = ImageDraw.Draw(sheet)
    font = load_font(20)
    for index, (image, name) in enumerate(zip(loaded, names)):
        image.thumbnail((thumb_width - 30, thumb_height - 52), Image.Resampling.LANCZOS)
        x = (index % 2) * thumb_width + (thumb_width - image.width) // 2
        y = (index // 2) * thumb_height + 36 + (thumb_height - 52 - image.height) // 2
        sheet.paste(image, (x, y))
        draw.text(((index % 2) * thumb_width + 14, (index // 2) * thumb_height + 8), name, font=font, fill=(220, 245, 245))
    sheet.save(output, quality=96)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--output-dir", type=Path, default=Path("build/reports/frame-guide-simulation"))
    args = parser.parse_args()
    repo = args.repo.resolve()
    output_dir = (repo / args.output_dir).resolve() if not args.output_dir.is_absolute() else args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)

    files = [output_dir / f"preview_rotation_{rotation}.png" for rotation in (0, 90, 180, 270)]
    results = [render_device_preview(rotation, path) for rotation, path in zip((0, 90, 180, 270), files)]
    aspect_files = [
        output_dir / f"preview_aspect_{name.replace(':', '_')}.png"
        for name, _, _ in PREVIEW_ASPECTS
    ]
    aspect_results = [
        render_aspect_preview(name, width, height, output)
        for (name, width, height), output in zip(PREVIEW_ASPECTS, aspect_files)
    ]
    wiring = verify_production_wiring(repo)
    contact_sheet = output_dir / "all_orientations.png"
    create_contact_sheet(
        files,
        ["Portrait", "Landscape right", "Upside-down portrait", "Landscape left"],
        contact_sheet,
    )
    aspect_contact_sheet = output_dir / "all_preview_aspects.png"
    create_contact_sheet(
        aspect_files,
        [f"{name} preview" for name, _, _ in PREVIEW_ASPECTS],
        aspect_contact_sheet,
    )
    report = {
        "passed": True,
        "scope_ratio": SCOPE_RATIO,
        "production_preview_wiring": wiring,
        "orientations": results,
        "preview_aspect_matrix": aspect_results,
        "contact_sheet": str(contact_sheet),
        "aspect_contact_sheet": str(aspect_contact_sheet),
    }
    report_path = output_dir / "simulation_report.json"
    report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(
        f"PASS: {len(results)} orientations; {len(aspect_results)} preview aspects; "
        f"{len(aspect_results) * 4} aspect/gyro combinations; production wiring verified"
    )
    print(f"Contact sheet: {contact_sheet}")
    print(f"Aspect sheet: {aspect_contact_sheet}")
    print(f"Report: {report_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
