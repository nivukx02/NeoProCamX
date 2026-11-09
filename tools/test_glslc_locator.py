import os
import sys
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

if __package__:
    from .glslc_locator import GlslcNotFoundError, find_glslc
    from . import compile_shader_header, regen_record_develop_header
else:
    from glslc_locator import GlslcNotFoundError, find_glslc
    import compile_shader_header
    import regen_record_develop_header


PINNED_NDK = "29.0.14206865"


class GlslcLocatorTest(unittest.TestCase):
    def _make_executable(self, path: Path) -> Path:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"test glslc")
        path.chmod(0o755)
        return path

    def _make_repo(self, root: Path, sdk_root: Path = None) -> None:
        app_dir = root / "app"
        app_dir.mkdir(parents=True)
        (app_dir / "build.gradle").write_text(
            f"android {{ ndkVersion = '{PINNED_NDK}' }}\n", encoding="utf-8"
        )
        if sdk_root is not None:
            escaped_sdk = str(sdk_root).replace("\\", "\\\\").replace(":", "\\:")
            (root / "local.properties").write_text(
                f"sdk.dir={escaped_sdk}\n", encoding="utf-8"
            )

    def test_explicit_glslc_override_has_highest_priority(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self._make_repo(root)
            override = self._make_executable(root / "override" / "glslc.exe")
            result = find_glslc(
                repo_root=root,
                environ={"GLSLC": str(override)},
                path_lookup=lambda _command: str(root / "wrong" / "glslc.exe"),
                system_name="Windows",
            )
            self.assertEqual(Path(result), override.resolve())

    def test_invalid_explicit_override_fails_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self._make_repo(root)
            path_candidate = self._make_executable(root / "path" / "glslc.exe")
            with self.assertRaisesRegex(GlslcNotFoundError, "GLSLC is set"):
                find_glslc(
                    repo_root=root,
                    environ={"GLSLC": str(root / "missing" / "glslc.exe")},
                    path_lookup=lambda command: str(path_candidate) if command == "glslc" else None,
                    system_name="Windows",
                )

    def test_explicit_ndk_root_precedes_sdk_and_path(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self._make_repo(root)
            ndk_root = root / "explicit-ndk"
            expected = self._make_executable(
                ndk_root / "shader-tools" / "windows-x86_64" / "glslc.exe"
            )
            result = find_glslc(
                repo_root=root,
                environ={"ANDROID_NDK_HOME": str(ndk_root)},
                path_lookup=lambda _command: None,
                system_name="Windows",
            )
            self.assertEqual(Path(result), expected.resolve())

    def test_local_properties_uses_exact_project_pinned_ndk(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sdk_root = root / "sdk"
            self._make_repo(root, sdk_root)
            expected = self._make_executable(
                sdk_root
                / "ndk"
                / PINNED_NDK
                / "shader-tools"
                / "windows-x86_64"
                / "glslc.exe"
            )
            self._make_executable(
                sdk_root
                / "ndk"
                / "99.0.0"
                / "shader-tools"
                / "windows-x86_64"
                / "glslc.exe"
            )
            result = find_glslc(
                repo_root=root,
                environ={},
                path_lookup=lambda _command: None,
                system_name="Windows",
            )
            self.assertEqual(Path(result), expected.resolve())

    def test_path_is_final_fallback(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self._make_repo(root)
            expected = self._make_executable(root / "path" / "glslc.exe")
            result = find_glslc(
                repo_root=root,
                environ={},
                path_lookup=lambda command: str(expected) if command == "glslc" else None,
                system_name="Windows",
            )
            self.assertEqual(Path(result), expected.resolve())


class ShaderGeneratorCommandContractTest(unittest.TestCase):
    def _run_generator(self, generator):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            shader = root / "app" / "src" / "main" / "cpp" / "shaders" / "vulkan" / "probe.comp"
            generated = root / "app" / "src" / "main" / "cpp" / "generated"
            shader.parent.mkdir(parents=True)
            generated.mkdir(parents=True)
            shader.write_text("#version 450\nvoid main() {}\n", encoding="utf-8", newline="\n")
            relative_shader = "app/src/main/cpp/shaders/vulkan/probe.comp"
            fake_glslc = str(root / "toolchain" / "glslc.exe")
            commands = []

            def compile_probe(command, capture_output, text):
                commands.append(command)
                self.assertTrue(capture_output)
                self.assertTrue(text)
                output = root / command[command.index("-o") + 1]
                output.write_bytes(bytes.fromhex("0302230701000000"))
                return SimpleNamespace(returncode=0, stdout="", stderr="")

            previous_directory = Path.cwd()
            try:
                os.chdir(root)
                with mock.patch.object(generator, "find_glslc", return_value=fake_glslc), mock.patch.object(
                    generator.subprocess, "run", side_effect=compile_probe
                ), mock.patch.object(sys, "argv", [generator.__file__, relative_shader]):
                    self.assertEqual(generator.main(), 0)
            finally:
                os.chdir(previous_directory)

            header = generated / "probe_comp_spv.h"
            return fake_glslc, relative_shader, commands, header.read_bytes()

    def test_general_generator_keeps_vulkan_1_1_optimized_recipe_and_lf_output(self):
        glslc, shader, commands, header = self._run_generator(compile_shader_header)
        self.assertEqual(
            commands,
            [[
                glslc,
                "--target-env=vulkan1.1",
                "-O",
                shader,
                "-o",
                "app/src/main/cpp/generated/probe.comp.spv",
            ]],
        )
        self.assertNotIn(b"\r\n", header)
        self.assertIn(b"0x07230203u, 0x00000001u\n};", header)

    def test_record_develop_generator_keeps_unoptimized_recipe_and_bom_crlf_output(self):
        glslc, shader, commands, header = self._run_generator(regen_record_develop_header)
        self.assertEqual(
            commands,
            [[glslc, shader, "-o", "app/src/main/cpp/generated/probe.comp.spv"]],
        )
        self.assertTrue(header.startswith(b"\xef\xbb\xbf#pragma once\r\n"))
        self.assertIn(b"0x07230203u, 0x00000001u,\r\n};", header)


if __name__ == "__main__":
    unittest.main(verbosity=2)
