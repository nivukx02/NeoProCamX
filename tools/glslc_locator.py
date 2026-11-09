"""Portable, deterministic discovery for the shader generators' ``glslc`` executable.

Discovery deliberately prefers the project's pinned Android NDK over an arbitrary installed NDK
version.  Selecting an unpinned compiler can change committed SPIR-V bytes between machines.
"""

import os
import platform
import re
import shutil
from pathlib import Path
from typing import Callable, Mapping, Optional, Sequence, Union


class GlslcNotFoundError(FileNotFoundError):
    """Raised when no deterministic ``glslc`` executable can be selected."""


PathValue = Union[str, os.PathLike]


def _unescape_java_property(value: str) -> str:
    """Decode the escapes Gradle writes in ``local.properties`` path values."""
    output = []
    index = 0
    simple_escapes = {"t": "\t", "n": "\n", "r": "\r", "f": "\f"}
    while index < len(value):
        if value[index] != "\\" or index + 1 >= len(value):
            output.append(value[index])
            index += 1
            continue

        escaped = value[index + 1]
        if escaped == "u" and index + 5 < len(value):
            hex_digits = value[index + 2:index + 6]
            if re.fullmatch(r"[0-9a-fA-F]{4}", hex_digits):
                output.append(chr(int(hex_digits, 16)))
                index += 6
                continue
        output.append(simple_escapes.get(escaped, escaped))
        index += 2
    return "".join(output)


def _read_local_property(properties_path: Path, key: str) -> Optional[str]:
    if not properties_path.is_file():
        return None
    pattern = re.compile(r"^\s*" + re.escape(key) + r"\s*[=:]\s*(.*?)\s*$")
    try:
        lines = properties_path.read_text(encoding="utf-8").splitlines()
    except (OSError, UnicodeError):
        return None
    for line in lines:
        if line.lstrip().startswith(("#", "!")):
            continue
        match = pattern.match(line)
        if match:
            return _unescape_java_property(match.group(1))
    return None


def _read_pinned_ndk_version(repo_root: Path) -> Optional[str]:
    build_file = repo_root / "app" / "build.gradle"
    try:
        build_text = build_file.read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        return None
    match = re.search(r"\bndkVersion\s*(?:=\s*)?['\"]([^'\"]+)['\"]", build_text)
    return match.group(1).strip() if match else None


def _host_shader_tool(system_name: str) -> Sequence[str]:
    normalized = system_name.lower()
    if normalized == "windows":
        return "windows-x86_64", "glslc.exe"
    if normalized == "darwin":
        return "darwin-x86_64", "glslc"
    if normalized == "linux":
        return "linux-x86_64", "glslc"
    return "", "glslc"


def _clean_path_value(value: Optional[str]) -> Optional[str]:
    if value is None:
        return None
    cleaned = value.strip()
    if len(cleaned) >= 2 and cleaned[0] == cleaned[-1] and cleaned[0] in ("'", '"'):
        cleaned = cleaned[1:-1].strip()
    return os.path.expanduser(cleaned) if cleaned else None


def _is_executable_file(path: Path, system_name: str) -> bool:
    return path.is_file() and (
        system_name.lower() == "windows" or os.access(str(path), os.X_OK)
    )


def _absolute(path: Path) -> str:
    return str(path.resolve())


def _deduplicate_paths(paths: Sequence[PathValue]) -> Sequence[Path]:
    result = []
    seen = set()
    for raw_path in paths:
        cleaned = _clean_path_value(os.fspath(raw_path))
        if not cleaned:
            continue
        path = Path(cleaned)
        identity = os.path.normcase(os.path.abspath(str(path)))
        if identity not in seen:
            seen.add(identity)
            result.append(path)
    return result


def _standard_sdk_roots(system_name: str, environ: Mapping[str, str]) -> Sequence[Path]:
    normalized = system_name.lower()
    home = _clean_path_value(environ.get("USERPROFILE") or environ.get("HOME"))
    if normalized == "windows":
        local_app_data = _clean_path_value(environ.get("LOCALAPPDATA"))
        return [Path(local_app_data) / "Android" / "Sdk"] if local_app_data else []
    if not home:
        return []
    if normalized == "darwin":
        return [Path(home) / "Library" / "Android" / "sdk"]
    if normalized == "linux":
        return [Path(home) / "Android" / "Sdk"]
    return []


def find_glslc(
    repo_root: Optional[PathValue] = None,
    environ: Optional[Mapping[str, str]] = None,
    path_lookup: Optional[Callable[[str], Optional[str]]] = None,
    system_name: Optional[str] = None,
) -> str:
    """Return an absolute path to ``glslc`` using a stable, documented priority.

    Priority is: explicit ``GLSLC`` override; explicit NDK roots; the exact ``ndkVersion`` under
    configured SDK roots; and finally ``glslc`` on ``PATH``.  An invalid ``GLSLC`` override is an
    error instead of silently selecting a different compiler.
    """
    root = Path(repo_root) if repo_root is not None else Path(__file__).resolve().parent.parent
    env = os.environ if environ is None else environ
    lookup = shutil.which if path_lookup is None else path_lookup
    host_system = platform.system() if system_name is None else system_name
    host_tag, executable_name = _host_shader_tool(host_system)

    explicit = _clean_path_value(env.get("GLSLC"))
    if explicit:
        explicit_path = Path(explicit)
        if _is_executable_file(explicit_path, host_system):
            return _absolute(explicit_path)
        located_override = lookup(explicit)
        if located_override and _is_executable_file(Path(located_override), host_system):
            return _absolute(Path(located_override))
        raise GlslcNotFoundError(
            f"GLSLC is set but is not an executable file or PATH command: {explicit}"
        )

    searched = []
    local_properties = root / "local.properties"
    direct_ndk_roots = _deduplicate_paths(
        [
            value
            for value in (
                env.get("ANDROID_NDK_HOME"),
                env.get("ANDROID_NDK_ROOT"),
                env.get("NDK_HOME"),
                _read_local_property(local_properties, "ndk.dir"),
            )
            if value
        ]
    )
    if host_tag:
        for ndk_root in direct_ndk_roots:
            candidate = ndk_root / "shader-tools" / host_tag / executable_name
            searched.append(candidate)
            if _is_executable_file(candidate, host_system):
                return _absolute(candidate)

    sdk_roots = _deduplicate_paths(
        [
            value
            for value in (
                env.get("ANDROID_SDK_ROOT"),
                env.get("ANDROID_HOME"),
                _read_local_property(local_properties, "sdk.dir"),
                *_standard_sdk_roots(host_system, env),
            )
            if value
        ]
    )
    pinned_ndk_version = _read_pinned_ndk_version(root)
    if host_tag and pinned_ndk_version:
        for sdk_root in sdk_roots:
            candidate = (
                sdk_root / "ndk" / pinned_ndk_version / "shader-tools" / host_tag / executable_name
            )
            searched.append(candidate)
            if _is_executable_file(candidate, host_system):
                return _absolute(candidate)

    located_on_path = lookup("glslc")
    if located_on_path and _is_executable_file(Path(located_on_path), host_system):
        return _absolute(Path(located_on_path))

    searched_summary = ", ".join(str(path) for path in searched) or "no configured NDK candidate"
    version_summary = pinned_ndk_version or "not declared in app/build.gradle"
    raise GlslcNotFoundError(
        "glslc not found. Set GLSLC, configure an Android SDK containing the project's pinned "
        f"NDK ({version_summary}), or put glslc on PATH. Searched: {searched_summary}"
    )
