from __future__ import annotations

import unittest

import vulkan_parity


class Niv99VulkanParityTest(unittest.TestCase):
    def test_reconstruction_shader_matches_scalar_cpu_reference(self) -> None:
        result = vulkan_parity.run_reconstruction_parity()
        if not result.supported:
            self.skipTest(result.reason)
        self.assertTrue(result.passed, result)
        self.assertEqual(vulkan_parity.WIDTH * vulkan_parity.HEIGHT * 4, result.compared_components)
        self.assertLessEqual(result.maximum_absolute_error, result.tolerance)

    def test_complete_shader_graph_matches_stage_references(self) -> None:
        result = vulkan_parity.run_full_graph_parity()
        if not result.supported:
            self.skipTest(result.reason)
        self.assertTrue(result.passed, result)
        self.assertEqual(
            {
                "statistics",
                "raw_denoise",
                "motion_confidence",
                "temporal_fusion",
                "reconstruction",
                "quality_telemetry",
            },
            set(result.stage_maximum_errors),
        )


if __name__ == "__main__":
    unittest.main()
