"""Compile a Vulkan GLSL compute/fragment shader to SPIR-V and emit the committed C++ header.

Usage (run from repo root):
    python tools/compile_shader_header.py app/src/main/cpp/shaders/vulkan/argus_flow_blockmatch.comp

glslc is taken from $GLSLC, else the project-pinned Android NDK, else PATH. The generated header
matches the format produced by tools/compile_p010_header.py (kPascalNameCompSpv + ...Count/...Size).
"""
import hashlib
import os
import subprocess
import sys

if __package__:
    from .glslc_locator import GlslcNotFoundError, find_glslc
else:
    from glslc_locator import GlslcNotFoundError, find_glslc


def pascal(name: str) -> str:
    return "".join(part.capitalize() for part in name.split("_"))


def stage_suffix(ext: str) -> str:
    # ".comp" -> "Comp", ".frag" -> "Frag", ".vert" -> "Vert"
    return ext.lstrip(".").capitalize()


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: compile_shader_header.py <shader path>", file=sys.stderr)
        return 2

    shader_path = sys.argv[1].replace("\\", "/")
    if not os.path.isfile(shader_path):
        print(f"shader not found: {shader_path}", file=sys.stderr)
        return 2

    try:
        glslc = find_glslc()
    except GlslcNotFoundError as error:
        print(error, file=sys.stderr)
        return 2

    base = os.path.basename(shader_path)            # argus_flow_blockmatch.comp
    name, ext = os.path.splitext(base)              # ("argus_flow_blockmatch", ".comp")
    generated_dir = "app/src/main/cpp/generated"
    spv_path = f"{generated_dir}/{name}{ext}.spv"   # argus_flow_blockmatch.comp.spv
    header_path = f"{generated_dir}/{name}_{ext.lstrip('.')}_spv.h"  # ..._comp_spv.h
    symbol = f"k{pascal(name)}{stage_suffix(ext)}Spv"

    # Compile to SPIR-V 1.3 (vulkan1.1) to match the existing committed headers.
    result = subprocess.run(
        [glslc, "--target-env=vulkan1.1", "-O", shader_path, "-o", spv_path],
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        sys.stderr.write(result.stdout)
        sys.stderr.write(result.stderr)
        print(f"glslc failed for {shader_path}", file=sys.stderr)
        return 1

    with open(shader_path, "rb") as f:
        shader_hash = hashlib.sha256(f.read()).hexdigest()
    with open(spv_path, "rb") as f:
        spv_bytes = f.read()

    words = []
    for i in range(0, len(spv_bytes), 4):
        chunk = spv_bytes[i:i + 4].ljust(4, b"\x00")
        words.append(int.from_bytes(chunk, byteorder="little"))

    array_lines = []
    for i in range(0, len(words), 8):
        hex_words = [f"0x{w:08x}u" for w in words[i:i + 8]]
        array_lines.append("    " + ", ".join(hex_words) + ",")
    if array_lines:
        array_lines[-1] = array_lines[-1].rstrip(",")

    header = f"""#pragma once

#include <cstddef>
#include <cstdint>

// Generated from {base}. Do not edit by hand.
// Source SHA-256: {shader_hash}
static constexpr uint32_t {symbol}[] = {{
{chr(10).join(array_lines)}
}};

static constexpr size_t {symbol}Count = sizeof({symbol}) / sizeof({symbol}[0]);
static constexpr size_t {symbol}Size = sizeof({symbol});
"""
    with open(header_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(header)

    print(f"OK {header_path}  ({len(words)} words, {len(spv_bytes)} bytes)  symbol={symbol}")
    print(f"SHA-256: {shader_hash}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
