from __future__ import annotations

import struct
import tempfile
import unittest
import zipfile
from pathlib import Path

from tools.neo_qa_hypergrid.package_oracle import verify_startup_packaging


APP_INITIALIZER = "Landroidx/startup/AppInitializer;"
PROVIDER = "Landroidx/startup/InitializationProvider;"
R_CLASS = "Landroidx/startup/R;"
R_STRING = "Landroidx/startup/R$string;"


def synthetic_dex(descriptors: tuple[str, ...]) -> bytes:
    header_size = 0x70
    string_table = header_size
    type_table = string_table + len(descriptors) * 4
    class_table = type_table + len(descriptors) * 4
    data_offset = class_table + len(descriptors) * 32
    payload = bytearray(data_offset)
    payload[:8] = b"dex\n039\x00"
    struct.pack_into("<I", payload, 0x24, header_size)
    struct.pack_into("<II", payload, 0x38, len(descriptors), string_table)
    struct.pack_into("<II", payload, 0x40, len(descriptors), type_table)
    struct.pack_into("<II", payload, 0x60, len(descriptors), class_table)
    for index, descriptor in enumerate(descriptors):
        encoded = descriptor.encode("utf-8")
        if len(encoded) >= 0x80:
            raise AssertionError("test descriptor is too long for one-byte ULEB128")
        struct.pack_into("<I", payload, string_table + index * 4, len(payload))
        payload.extend(bytes((len(encoded),)))
        payload.extend(encoded)
        payload.append(0)
        struct.pack_into("<I", payload, type_table + index * 4, index)
        struct.pack_into("<I", payload, class_table + index * 32, index)
    return bytes(payload)


def write_apk(path: Path, descriptors: tuple[str, ...], *, provider: bool, resource: bool) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("classes.dex", synthetic_dex(descriptors))
        archive.writestr(
            "AndroidManifest.xml",
            "androidx.startup.InitializationProvider" if provider else "manifest",
        )
        archive.writestr(
            "resources.arsc",
            "androidx_startup" if resource else "resources",
        )


class PackageOracleTest(unittest.TestCase):
    def test_accepts_complete_app_and_non_shadowing_test_apk(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = root / "app.apk"
            test = root / "test.apk"
            write_apk(app, (APP_INITIALIZER, PROVIDER, R_CLASS, R_STRING), provider=True, resource=True)
            write_apk(test, ("Lexample/InstrumentedTest;",), provider=False, resource=False)
            report = verify_startup_packaging(app, test)
            self.assertEqual("PASS", report.status)
            self.assertEqual((), report.errors)
            self.assertEqual(64, len(report.app_sha256))

    def test_rejects_resource_present_but_generated_r_class_missing(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = root / "app.apk"
            test = root / "test.apk"
            write_apk(app, (APP_INITIALIZER, PROVIDER, R_CLASS), provider=True, resource=True)
            write_apk(test, ("Lexample/InstrumentedTest;",), provider=False, resource=False)
            report = verify_startup_packaging(app, test)
            self.assertEqual("FAIL", report.status)
            self.assertTrue(any(R_STRING in error for error in report.errors))

    def test_rejects_test_apk_that_shadows_target_startup_runtime(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = root / "app.apk"
            test = root / "test.apk"
            write_apk(app, (APP_INITIALIZER, PROVIDER, R_CLASS, R_STRING), provider=True, resource=True)
            write_apk(test, (APP_INITIALIZER,), provider=False, resource=False)
            report = verify_startup_packaging(app, test)
            self.assertEqual("FAIL", report.status)
            self.assertTrue(any("shadows" in error for error in report.errors))

    def test_rejects_test_apk_that_shadows_startup_resource_class(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            app = root / "app.apk"
            test = root / "test.apk"
            write_apk(app, (APP_INITIALIZER, PROVIDER, R_CLASS, R_STRING), provider=True, resource=True)
            write_apk(test, (R_STRING,), provider=False, resource=False)
            report = verify_startup_packaging(app, test)
            self.assertEqual("FAIL", report.status)
            self.assertTrue(any(R_STRING in error and "shadows" in error for error in report.errors))


if __name__ == "__main__":
    unittest.main()
