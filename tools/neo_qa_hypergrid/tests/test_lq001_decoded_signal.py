"""Arithmetic/format tests only; generated test pixels are not camera evidence."""
import struct
import unittest
import zlib

import numpy as np

from tools.neo_qa_hypergrid.lq001_decoded_signal import measure_rgb16, png_dimensions


def png_header(width=4096, height=3072, bits=16, colour=2, interlace=0):
    payload = b"IHDR" + struct.pack(">IIBBBBB", width, height, bits, colour, 0, 0, interlace)
    return b"\x89PNG\r\n\x1a\n\0\0\0\r" + payload + struct.pack(">I", zlib.crc32(payload))


class DecodedSignalTests(unittest.TestCase):
    def test_rgb48_full_frame_header_is_admitted(self):
        self.assertEqual((4096, 3072), png_dimensions(png_header()))

    def test_transposed_full_resolution_is_admitted(self):
        self.assertEqual((3072, 4096), png_dimensions(png_header(3072, 4096)))

    def test_eight_bit_substitution_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "RGB48"):
            png_dimensions(png_header(bits=8))

    def test_bad_header_crc_is_rejected(self):
        damaged = bytearray(png_header())
        damaged[-1] ^= 1
        with self.assertRaisesRegex(ValueError, "checksum"):
            png_dimensions(bytes(damaged))

    def test_non_rgb_or_interlaced_or_overlarge_headers_are_rejected(self):
        for header in (png_header(colour=6), png_header(interlace=1), png_header(100000, 100000)):
            with self.subTest(header=header), self.assertRaises(ValueError):
                png_dimensions(header)

    def test_incomplete_header_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "IHDR"):
            png_dimensions(png_header()[:-1])

    def test_black_pixels_do_not_pass_signal(self):
        result = measure_rgb16(np.zeros((4, 4, 3), dtype=np.uint16))
        self.assertEqual(0.0, result["nonblack_ratio"])
        self.assertFalse(any(result["signal_gates"].values()))

    def test_uniform_white_is_nonblack_but_not_spatial_detail(self):
        result = measure_rgb16(np.full((4, 4, 3), 65535, dtype=np.uint16))
        self.assertEqual(255.0, result["mean_luma_8bit_scale"])
        self.assertEqual([1.0, 1.0, 1.0], result["rgb_full_fraction"])
        self.assertTrue(result["signal_gates"]["nonblack_signal"])
        self.assertFalse(result["signal_gates"]["spatial_signal"])

    def test_sixteen_bit_detail_below_one_eight_bit_step_is_not_quantized_away(self):
        image = np.full((2, 2, 3), 32768, dtype=np.uint16)
        image[0, 0] += 1
        result = measure_rgb16(image)
        self.assertAlmostEqual(1 / 257, result["luma_range_8bit_scale"], places=10)

    def test_luma_accumulation_does_not_overflow_uint16(self):
        image = np.array([[[0, 0, 0], [65535, 65535, 65535]]] * 2, dtype=np.uint16)
        result = measure_rgb16(image)
        self.assertEqual(127.5, result["mean_luma_8bit_scale"])
        self.assertEqual(127.5, result["luma_stddev_8bit_scale"])
        self.assertEqual(255.0, result["luma_range_8bit_scale"])
        self.assertTrue(all(result["signal_gates"].values()))

    def test_row_chunks_measure_every_pixel(self):
        image = np.zeros((259, 2, 3), dtype=np.uint16)
        image[-1, :, :] = 65535
        result = measure_rgb16(image)
        self.assertEqual(518, result["pixels_measured"])
        self.assertAlmostEqual(1 / 259, result["nonblack_ratio"])

    def test_invalid_dimensions_types_and_channels_are_rejected(self):
        for image in (np.zeros((2, 2, 3), np.uint8), np.zeros((2, 2, 3), np.int16),
                      np.zeros((2, 2, 3), np.float32), np.zeros((1, 2, 3), np.uint16),
                      np.zeros((2, 2, 4), np.uint16), np.zeros((2, 2), np.uint16)):
            with self.subTest(shape=image.shape, dtype=image.dtype), self.assertRaises(ValueError):
                measure_rgb16(image)


if __name__ == "__main__":
    unittest.main()
