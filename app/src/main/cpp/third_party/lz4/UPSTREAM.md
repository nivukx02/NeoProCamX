# Pinned upstream LZ4

- Repository: https://github.com/lz4/lz4
- Release: v1.10.0
- Commit: ebb370ca83af193212df4dcbadcc5d87bc0de2f0
- Vendored, unmodified files: lib/lz4.c, lib/lz4.h, lib/LICENSE.
- License: BSD-2-Clause (included in LICENSE).

Only the portable block codec is compiled; no network fetch occurs at build time. It uses the same
arm64-v8a, armeabi-v7a and x86_64 toolchains as the containing JNI library. State is per-worker and
reused; incompressible chunks retain their exact source bytes. This is not lossy quantization and
does not alter resolution, FPS, camera processing, colour, audio or the packet CRC contract.

New compressed records use the existing NLC2 split-ten-bit LZ4 representation. The bounded legacy
decoder remains for old recordings whose final match does not meet upstream LZ4 tail constraints.
Every decoded record still has to pass the original packed-source CRC.
