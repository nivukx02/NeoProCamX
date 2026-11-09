import os
import hashlib

def main():
    shader_path = r"app/src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp"
    spv_path = r"app/src/main/cpp/generated/neo_p010_export_enhance.comp.spv"
    header_path = r"app/src/main/cpp/generated/neo_p010_export_enhance_comp_spv.h"

    # Compute SHA-256 of shader source
    with open(shader_path, "rb") as f:
        shader_bytes = f.read()
    shader_hash = hashlib.sha256(shader_bytes).hexdigest()

    # Read SPV binary bytes
    with open(spv_path, "rb") as f:
        spv_bytes = f.read()

    # Group SPV bytes into 32-bit words (little-endian)
    words = []
    for i in range(0, len(spv_bytes), 4):
        word_bytes = spv_bytes[i:i+4]
        # Pad with zeros if less than 4 bytes (should be multiple of 4 though)
        if len(word_bytes) < 4:
            word_bytes = word_bytes.ljust(4, b'\x00')
        word = int.from_bytes(word_bytes, byteorder='little')
        words.append(word)

    # Format words as hex array lines (8 words per line)
    array_lines = []
    for i in range(0, len(words), 8):
        chunk = words[i:i+8]
        hex_words = [f"0x{w:08x}u" for w in chunk]
        array_lines.append("    " + ", ".join(hex_words) + ",")

    # Fix the trailing comma on the very last line
    if array_lines:
        array_lines[-1] = array_lines[-1].rstrip(",")

    # Generate complete header file content
    header_content = f"""#pragma once

#include <cstddef>
#include <cstdint>

// Generated from {os.path.basename(shader_path)}. Do not edit by hand.
// Source SHA-256: {shader_hash}
static constexpr uint32_t kNeoP010ExportEnhanceCompSpv[] = {{
{chr(10).join(array_lines)}
}};

static constexpr size_t kNeoP010ExportEnhanceCompSpvCount = sizeof(kNeoP010ExportEnhanceCompSpv) / sizeof(kNeoP010ExportEnhanceCompSpv[0]);
static constexpr size_t kNeoP010ExportEnhanceCompSpvSize = sizeof(kNeoP010ExportEnhanceCompSpv);
"""

    with open(header_path, "w", encoding="utf-8", newline="\n") as f:
        f.write(header_content)

    print(f"Successfully generated header {header_path}")
    print(f"Count: {len(words)} words, Size: {len(spv_bytes)} bytes")
    print(f"SHA-256: {shader_hash}")

if __name__ == "__main__":
    main()
