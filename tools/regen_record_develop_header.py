"""Regenerate a record-develop family SPIR-V C++ header, byte-for-byte in the committed convention.

The committed record_develop_*/record_parity_* headers were NOT produced by
compile_shader_header.py (which uses `--target-env=vulkan1.1 -O` -> SPIR-V 1.3, LF, no BOM,
last-line comma stripped). They are:
    * plain `glslc <shader> -o <spv>`  (default env == vulkan1.0, NO -O)  -> SPIR-V 1.0
    * UTF-8 **with BOM**, **CRLF** line endings
    * 8 words per line, a trailing comma on EVERY line (including the last)

This script reproduces that exact format so the ONLY byte delta after a shader source edit is
the compiled SPIR-V itself (verified: regenerating an UNMODIFIED shader is byte-identical to the
committed header).

Usage (run from repo root):
    python tools/regen_record_develop_header.py app/src/main/cpp/shaders/vulkan/record_develop_p010_to_rgb10.comp

glslc is taken from $GLSLC, else the project-pinned Android NDK, else PATH.
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
    return ext.lstrip(".").capitalize()


def main() -> int:
    if len(sys.argv) < 2:
        print("usage: regen_record_develop_header.py <shader path>", file=sys.stderr)
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

    base = os.path.basename(shader_path)                       # record_develop_p010_to_rgb10.comp
    name, ext = os.path.splitext(base)
    generated_dir = "app/src/main/cpp/generated"
    spv_path = f"{generated_dir}/{name}{ext}.spv"
    header_path = f"{generated_dir}/{name}_{ext.lstrip('.')}_spv.h"
    symbol = f"k{pascal(name)}{stage_suffix(ext)}Spv"

    # Committed recipe: plain glslc, default target env (== vulkan1.0), NO optimisation.
    result = subprocess.run([glslc, shader_path, "-o", spv_path], capture_output=True, text=True)
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

    # 8 words/line, trailing comma on EVERY line (matches committed convention).
    array_lines = []
    for i in range(0, len(words), 8):
        hex_words = [f"0x{w:08x}u" for w in words[i:i + 8]]
        array_lines.append("    " + ", ".join(hex_words) + ",")

    lines = [
        "#pragma once",
        "",
        "#include <cstddef>",
        "#include <cstdint>",
        "",
        f"// Generated from {base}. Do not edit by hand.",
        f"// Source SHA-256: {shader_hash}",
        f"static constexpr uint32_t {symbol}[] = {{",
        *array_lines,
        "};",
        f"static constexpr size_t {symbol}Count = sizeof({symbol}) / sizeof({symbol}[0]);",
        f"static constexpr size_t {symbol}Size = sizeof({symbol});",
    ]
    # UTF-8 BOM + CRLF, trailing CRLF after the final line (matches committed bytes).
    text = "﻿" + "\r\n".join(lines) + "\r\n"
    with open(header_path, "wb") as f:
        f.write(text.encode("utf-8"))

    print(f"OK {header_path}  ({len(words)} words, {len(spv_bytes)} bytes)  symbol={symbol}")
    print(f"SHA-256: {shader_hash}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
