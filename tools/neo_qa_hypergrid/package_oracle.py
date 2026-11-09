"""Fail-closed APK startup-runtime packaging validation.

Android's resource linker can successfully merge a library resource while an
incremental DEX build accidentally omits that library's generated ``R`` class.
The resulting APK passes compilation and signing but crashes before
``Application.onCreate``.  This oracle reads the final APKs directly and makes
that class/resource/provider relationship a release-candidate admission gate.
"""

from __future__ import annotations

import hashlib
import struct
import zipfile
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable


_DEX_MAGIC_PREFIX = b"dex\n"
_STARTUP_APP_INITIALIZER = "Landroidx/startup/AppInitializer;"
_STARTUP_PROVIDER = "Landroidx/startup/InitializationProvider;"
_STARTUP_R = "Landroidx/startup/R;"
_STARTUP_R_STRING = "Landroidx/startup/R$string;"
_REQUIRED_APP_CLASSES = frozenset(
    {
        _STARTUP_APP_INITIALIZER,
        _STARTUP_PROVIDER,
        _STARTUP_R,
        _STARTUP_R_STRING,
    }
)
# Instrumentation class loaders search the test APK before the target APK.  Any
# duplicate in this linked family can therefore mix code and resource IDs from
# two packages, so the test APK must not define even the generated R classes.
_TEST_SHADOW_CLASSES = _REQUIRED_APP_CLASSES


class ApkPackagingError(ValueError):
    """Raised when an APK or DEX structure cannot be validated safely."""


@dataclass(frozen=True)
class ApkStartupPackagingReport:
    schema: str
    status: str
    app_apk: str
    app_sha256: str
    test_apk: str
    test_sha256: str
    app_startup_classes: tuple[str, ...]
    test_startup_classes: tuple[str, ...]
    app_manifest_has_provider: bool
    app_resources_have_startup_key: bool
    errors: tuple[str, ...]

    def to_dict(self) -> dict[str, object]:
        return asdict(self)


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest().upper()


def _u32(data: bytes, offset: int, label: str) -> int:
    if offset < 0 or offset + 4 > len(data):
        raise ApkPackagingError(f"{label} is outside DEX bounds")
    return struct.unpack_from("<I", data, offset)[0]


def _uleb128_end(data: bytes, offset: int, label: str) -> int:
    for _ in range(5):
        if offset >= len(data):
            raise ApkPackagingError(f"{label} has a truncated ULEB128 length")
        value = data[offset]
        offset += 1
        if value < 0x80:
            return offset
    raise ApkPackagingError(f"{label} has an invalid ULEB128 length")


def _dex_class_descriptors(data: bytes, entry_name: str) -> frozenset[str]:
    if len(data) < 0x70 or not data.startswith(_DEX_MAGIC_PREFIX):
        raise ApkPackagingError(f"{entry_name} is not a valid DEX header")

    string_count = _u32(data, 0x38, f"{entry_name} string count")
    string_offset = _u32(data, 0x3C, f"{entry_name} string table")
    type_count = _u32(data, 0x40, f"{entry_name} type count")
    type_offset = _u32(data, 0x44, f"{entry_name} type table")
    class_count = _u32(data, 0x60, f"{entry_name} class count")
    class_offset = _u32(data, 0x64, f"{entry_name} class table")

    if string_count > 2_000_000 or type_count > 2_000_000 or class_count > 2_000_000:
        raise ApkPackagingError(f"{entry_name} declares unreasonable table sizes")
    if string_offset + string_count * 4 > len(data):
        raise ApkPackagingError(f"{entry_name} string table is truncated")
    if type_offset + type_count * 4 > len(data):
        raise ApkPackagingError(f"{entry_name} type table is truncated")
    if class_offset + class_count * 32 > len(data):
        raise ApkPackagingError(f"{entry_name} class table is truncated")

    strings: list[str] = []
    for index in range(string_count):
        data_offset = _u32(data, string_offset + index * 4, f"{entry_name} string[{index}]")
        content_offset = _uleb128_end(data, data_offset, f"{entry_name} string[{index}]")
        try:
            terminator = data.index(0, content_offset)
        except ValueError as exc:
            raise ApkPackagingError(
                f"{entry_name} string[{index}] has no terminator"
            ) from exc
        # DEX uses Modified UTF-8, including the C0 80 representation for an
        # embedded NUL and CESU-8 surrogate pairs.  Startup class descriptors
        # are ASCII; replacement decoding keeps unrelated localized strings
        # from making the structural class-definition scan fail spuriously.
        strings.append(data[content_offset:terminator].decode("utf-8", errors="replace"))

    types: list[str] = []
    for index in range(type_count):
        descriptor_index = _u32(data, type_offset + index * 4, f"{entry_name} type[{index}]")
        if descriptor_index >= len(strings):
            raise ApkPackagingError(f"{entry_name} type[{index}] references a missing string")
        types.append(strings[descriptor_index])

    descriptors: set[str] = set()
    for index in range(class_count):
        class_index = _u32(data, class_offset + index * 32, f"{entry_name} class[{index}]")
        if class_index >= len(types):
            raise ApkPackagingError(f"{entry_name} class[{index}] references a missing type")
        descriptors.add(types[class_index])
    return frozenset(descriptors)


def _contains_token(payload: bytes, token: str) -> bool:
    return token.encode("utf-8") in payload or token.encode("utf-16le") in payload


def _apk_startup_inventory(path: Path) -> tuple[frozenset[str], bool, bool]:
    try:
        with zipfile.ZipFile(path) as archive:
            dex_entries = sorted(
                name
                for name in archive.namelist()
                if name.startswith("classes") and name.endswith(".dex") and "/" not in name
            )
            if not dex_entries:
                raise ApkPackagingError(f"{path} contains no root DEX entries")
            descriptors: set[str] = set()
            for entry in dex_entries:
                descriptors.update(_dex_class_descriptors(archive.read(entry), entry))
            manifest = archive.read("AndroidManifest.xml")
            resources = archive.read("resources.arsc")
    except (KeyError, OSError, zipfile.BadZipFile) as exc:
        raise ApkPackagingError(f"cannot inspect APK {path}: {exc}") from exc

    startup_classes = frozenset(
        descriptor for descriptor in descriptors if descriptor.startswith("Landroidx/startup/")
    )
    return (
        startup_classes,
        _contains_token(manifest, "androidx.startup.InitializationProvider"),
        _contains_token(resources, "androidx_startup"),
    )


def verify_startup_packaging(app_apk: Path, test_apk: Path) -> ApkStartupPackagingReport:
    app_apk = app_apk.resolve(strict=True)
    test_apk = test_apk.resolve(strict=True)
    errors: list[str] = []
    try:
        app_classes, manifest_has_provider, resources_have_key = _apk_startup_inventory(app_apk)
    except ApkPackagingError as exc:
        app_classes = frozenset()
        manifest_has_provider = False
        resources_have_key = False
        errors.append(str(exc))
    try:
        test_classes, _, _ = _apk_startup_inventory(test_apk)
    except ApkPackagingError as exc:
        test_classes = frozenset()
        errors.append(str(exc))

    missing = sorted(_REQUIRED_APP_CLASSES - app_classes)
    if missing:
        errors.append("app APK is missing startup class definitions: " + ", ".join(missing))
    if not manifest_has_provider:
        errors.append("app manifest does not contain InitializationProvider")
    if not resources_have_key:
        errors.append("app resource table does not contain androidx_startup")
    shadows = sorted(_TEST_SHADOW_CLASSES & test_classes)
    if shadows:
        errors.append(
            "test APK shadows target startup runtime classes: " + ", ".join(shadows)
        )

    return ApkStartupPackagingReport(
        schema="neo-qa-hypergrid/apk-startup-packaging/v1",
        status="PASS" if not errors else "FAIL",
        app_apk=str(app_apk),
        app_sha256=_sha256(app_apk),
        test_apk=str(test_apk),
        test_sha256=_sha256(test_apk),
        app_startup_classes=tuple(sorted(app_classes)),
        test_startup_classes=tuple(sorted(test_classes)),
        app_manifest_has_provider=manifest_has_provider,
        app_resources_have_startup_key=resources_have_key,
        errors=tuple(errors),
    )


def required_startup_classes() -> Iterable[str]:
    """Expose the immutable contract for reporting and focused tests."""

    return tuple(sorted(_REQUIRED_APP_CLASSES))
