"""Deterministic compile, SPIR-V, manifest, and descriptor-ABI gate for NIV99 shaders.

This validator does not claim numerical GPU parity. It proves that committed binaries are exact
rebuilds of their sources, pass SPIR-V validation, expose only the declared descriptor set/bindings,
retain the 256-byte uniform offsets, and use the expected workgroup geometry.
"""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, Iterable, List, Mapping, Sequence, Set, Tuple


REPO_ROOT = Path(__file__).resolve().parents[2]
TOOLS_ROOT = REPO_ROOT / "tools"
if str(TOOLS_ROOT) not in sys.path:
    sys.path.insert(0, str(TOOLS_ROOT))

from glslc_locator import GlslcNotFoundError, find_glslc  # noqa: E402


@dataclass(frozen=True)
class ShaderContract:
    name: str
    local_size: Tuple[int, int, int]
    bindings: Mapping[str, int]

    @property
    def source(self) -> Path:
        return REPO_ROOT / "app" / "src" / "main" / "cpp" / "shaders" / "vulkan" / f"{self.name}.comp"

    @property
    def binary(self) -> Path:
        return REPO_ROOT / "app" / "src" / "main" / "cpp" / "generated" / f"{self.name}.comp.spv"

    @property
    def header(self) -> Path:
        return REPO_ROOT / "app" / "src" / "main" / "cpp" / "generated" / f"{self.name}_comp_spv.h"


CONTRACTS: Tuple[ShaderContract, ...] = (
    ShaderContract(
        "niv99_statistics",
        (8, 8, 1),
        {"rawInput": 0, "lensShadingMap": 1, "statisticsOutput": 12, "uniforms": 15},
    ),
    ShaderContract(
        "niv99_raw_denoise",
        (16, 16, 1),
        {
            "rawInput": 0,
            "lensShadingMap": 1,
            "persistentDefectMap": 2,
            "denoisedRawOutput": 3,
            "statisticsInput": 12,
            "tileControlInput": 13,
            "uniforms": 15,
        },
    ),
    ShaderContract(
        "niv99_motion_confidence",
        (16, 16, 1),
        {
            "currentLinearInput": 4,
            "historyLinearInput": 5,
            "forwardFlowInput": 6,
            "backwardFlowInput": 7,
            "motionConfidenceOutput": 9,
            "uniforms": 15,
        },
    ),
    ShaderContract(
        "niv99_temporal_fusion",
        (16, 16, 1),
        {
            "currentLinearInput": 4,
            "historyLinearInput": 5,
            "forwardFlowInput": 6,
            "motionConfidenceInput": 9,
            "temporalOutput": 10,
            "tileControlInput": 13,
            "uniforms": 15,
        },
    ),
    ShaderContract(
        "niv99_reconstruction",
        (16, 16, 1),
        {
            "semanticProtectionInput": 8,
            "temporalInput": 10,
            "reconstructedOutput": 11,
            "tileControlInput": 13,
            "uniforms": 15,
        },
    ),
    ShaderContract(
        "niv99_quality_telemetry",
        (8, 8, 1),
        {
            "referenceLinearInput": 4,
            "motionConfidenceInput": 9,
            "processedLinearInput": 11,
            "telemetryOutput": 14,
            "uniforms": 15,
        },
    ),
)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def run_checked(command: Sequence[str]) -> str:
    result = subprocess.run(command, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        detail = "\n".join(part for part in (result.stdout.strip(), result.stderr.strip()) if part)
        raise RuntimeError(f"command failed ({result.returncode}): {' '.join(command)}\n{detail}")
    return result.stdout


def shader_tools() -> Tuple[Path, Path, Path]:
    glslc = Path(find_glslc(repo_root=REPO_ROOT))
    suffix = ".exe" if glslc.suffix.lower() == ".exe" else ""
    validator = glslc.with_name(f"spirv-val{suffix}")
    disassembler = glslc.with_name(f"spirv-dis{suffix}")
    if not validator.is_file() or not disassembler.is_file():
        raise FileNotFoundError(
            f"SPIR-V validator/disassembler must be adjacent to {glslc}"
        )
    return glslc, validator, disassembler


def manifest_entries() -> Dict[str, Mapping[str, object]]:
    manifest_path = REPO_ROOT / "app" / "src" / "main" / "assets" / "shaders" / "shader_manifest.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("formatVersion") != "SINGULARITY_ASSET_MANIFEST_1":
        raise ValueError("unexpected shader manifest format")
    entries: Dict[str, Mapping[str, object]] = {}
    for entry in manifest.get("entries", []):
        asset_id = entry.get("assetId")
        if not isinstance(asset_id, str) or not asset_id:
            raise ValueError("shader manifest entry has no assetId")
        if asset_id in entries:
            raise ValueError(f"duplicate shader assetId: {asset_id}")
        entries[asset_id] = entry
    return entries


def expected_asset_id(contract: ShaderContract) -> str:
    return contract.name


def header_bytes(header: Path) -> bytes:
    text = header.read_text(encoding="utf-8")
    words = [int(value, 16) for value in re.findall(r"0x([0-9a-fA-F]{8})u", text)]
    if not words:
        raise ValueError(f"no SPIR-V words found in {header}")
    return b"".join(word.to_bytes(4, byteorder="little", signed=False) for word in words)


def descriptor_bindings(disassembly: str) -> Set[Tuple[int, int]]:
    bindings: Dict[str, int] = {}
    descriptor_sets: Dict[str, int] = {}
    for line in disassembly.splitlines():
        binding_match = re.search(r"OpDecorate\s+(%\S+)\s+Binding\s+(\d+)", line)
        if binding_match:
            bindings[binding_match.group(1)] = int(binding_match.group(2))
        set_match = re.search(r"OpDecorate\s+(%\S+)\s+DescriptorSet\s+(\d+)", line)
        if set_match:
            descriptor_sets[set_match.group(1)] = int(set_match.group(2))
    return {
        (descriptor_sets.get(identifier, -1), binding)
        for identifier, binding in bindings.items()
    }


def has_uniform_offsets(disassembly: str, expected: Set[int]) -> bool:
    offsets_by_type: Dict[str, Set[int]] = {}
    pattern = re.compile(r"OpMemberDecorate\s+(%\S+)\s+\d+\s+Offset\s+(\d+)")
    for line in disassembly.splitlines():
        match = pattern.search(line)
        if match:
            offsets_by_type.setdefault(match.group(1), set()).add(int(match.group(2)))
    return expected in offsets_by_type.values()


def source_declares_binding(source: str, name: str, binding: int) -> bool:
    declaration = re.compile(
        rf"layout\s*\([^)]*\bbinding\s*=\s*{binding}\b[^)]*\)[\s\S]*?\b{re.escape(name)}\b",
        re.MULTILINE,
    )
    match = declaration.search(source)
    if match is None:
        return False
    # Do not let a match skip across a second layout declaration and accidentally associate a name
    # with an earlier binding.
    matched_text = match.group(0)
    return len(re.findall(r"\blayout\s*\(", matched_text)) == 1


def local_size(disassembly: str) -> Tuple[int, int, int] | None:
    match = re.search(r"OpExecutionMode\s+%\S+\s+LocalSize\s+(\d+)\s+(\d+)\s+(\d+)", disassembly)
    if match is None:
        return None
    return int(match.group(1)), int(match.group(2)), int(match.group(3))


def validate_contract(
    contract: ShaderContract,
    manifest: Mapping[str, Mapping[str, object]],
    glslc: Path,
    validator: Path,
    disassembler: Path,
) -> List[str]:
    errors: List[str] = []
    for path in (contract.source, contract.binary, contract.header):
        if not path.is_file():
            errors.append(f"{contract.name}: missing {path.relative_to(REPO_ROOT)}")
    if errors:
        return errors
    entry = manifest.get(expected_asset_id(contract))
    if entry is None:
        errors.append(f"{contract.name}: missing manifest entry")
        return errors
    source_hash = sha256(contract.source)
    binary_hash = sha256(contract.binary)
    if entry.get("fileHashSha256") != source_hash:
        errors.append(f"{contract.name}: source hash does not match manifest")
    if entry.get("binaryHash") != binary_hash:
        errors.append(f"{contract.name}: binary hash does not match manifest")
    expected_relative_source = contract.source.relative_to(REPO_ROOT).as_posix()
    if entry.get("file") != expected_relative_source:
        errors.append(f"{contract.name}: manifest source path mismatch")
    if entry.get("uniformBufferBytes") != 256:
        errors.append(f"{contract.name}: manifest uniform size is not 256")
    if entry.get("runtimeActivation") != "GATED_PENDING_SHADER_PARITY_AND_PIPELINE_INTEGRATION":
        errors.append(f"{contract.name}: runtime activation gate is missing")

    header_text = contract.header.read_text(encoding="utf-8")
    if f"Source SHA-256: {source_hash}" not in header_text:
        errors.append(f"{contract.name}: generated header source hash mismatch")
    if header_bytes(contract.header) != contract.binary.read_bytes():
        errors.append(f"{contract.name}: generated header words differ from committed SPIR-V")

    run_checked([str(validator), "--target-env", "vulkan1.1", str(contract.binary)])
    disassembly = run_checked([str(disassembler), str(contract.binary)])
    if local_size(disassembly) != contract.local_size:
        errors.append(f"{contract.name}: unexpected SPIR-V local size {local_size(disassembly)}")
    source_text = contract.source.read_text(encoding="utf-8")
    for name, binding in contract.bindings.items():
        if not source_declares_binding(source_text, name, binding):
            errors.append(
                f"{contract.name}: source does not declare {name} at binding={binding}"
            )
    declared_bindings = descriptor_bindings(disassembly)
    expected_bindings = set(contract.bindings.values())
    expected_set_bindings = {(0, binding) for binding in expected_bindings}
    if declared_bindings != expected_set_bindings:
        errors.append(
            f"{contract.name}: descriptor binding set mismatch "
            f"expected={sorted(expected_set_bindings)} actual={sorted(declared_bindings)}"
        )
    expected_offsets = set(range(0, 256, 16))
    if not has_uniform_offsets(disassembly, expected_offsets):
        errors.append(
            f"{contract.name}: uniform offsets differ from 0..240 in 16-byte steps"
        )
    if "OpTypeFloat 16" in disassembly:
        errors.append(f"{contract.name}: unexpected FP16 arithmetic type in calibration shader")
    if "OpTypeInt 8" in disassembly:
        errors.append(f"{contract.name}: unexpected eight-bit arithmetic type")

    with tempfile.TemporaryDirectory(prefix=f"{contract.name}-") as temporary:
        rebuilt = Path(temporary) / f"{contract.name}.comp.spv"
        run_checked(
            [
                str(glslc),
                "--target-env=vulkan1.1",
                "-O",
                str(contract.source),
                "-o",
                str(rebuilt),
            ]
        )
        if rebuilt.read_bytes() != contract.binary.read_bytes():
            errors.append(f"{contract.name}: committed SPIR-V is not a deterministic rebuild")
    return errors


def validate_all() -> List[str]:
    glslc, validator, disassembler = shader_tools()
    manifest = manifest_entries()
    errors: List[str] = []
    for contract in CONTRACTS:
        errors.extend(validate_contract(contract, manifest, glslc, validator, disassembler))
    return errors


def main() -> int:
    try:
        errors = validate_all()
    except (GlslcNotFoundError, FileNotFoundError, ValueError, RuntimeError) as error:
        print(f"NIV99 shader validation failed: {error}", file=sys.stderr)
        return 1
    if errors:
        for error in errors:
            print(error, file=sys.stderr)
        return 1
    print(f"NIV99 shader validation passed: {len(CONTRACTS)} shaders")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
