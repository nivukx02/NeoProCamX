import tempfile
import unittest
from pathlib import Path

from PIL import Image

from ndr_preview_orientation_simulation import (
    generate_verification_image,
    simulate,
    verify,
)


class NdrPreviewOrientationSimulationTest(unittest.TestCase):
    def test_all_four_rotations(self) -> None:
        verify()
        self.assertEqual([270, 270, 270, 270], [item.sampling_degrees for item in simulate()])

    def test_visual_proof_contains_four_nonempty_panels(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            path = generate_verification_image(Path(directory) / "proof.png")
            with Image.open(path) as proof:
                self.assertEqual((1640, 1330), proof.size)
                self.assertGreater(len(proof.getcolors(maxcolors=1_000_000) or []), 10)


if __name__ == "__main__":
    unittest.main()
