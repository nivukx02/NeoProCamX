from __future__ import annotations

import sys
import unittest
from pathlib import Path


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
sys.path.insert(0, str(HERE))

from conversion_acceleration_simulation import (  # noqa: E402
    ACCELERATED_COPY_CHUNK_BYTES,
    LEGACY_COPY_CHUNK_BYTES,
    REFERENCE_FRAMES,
    REQUIRED_WORK_REDUCTION,
    ConversionFamily,
    QualityInvariants,
    WorkBudget,
    evaluate_work_budget,
    reference_budgets,
    run_reference_simulation,
)


class ConversionAccelerationSimulationTest(unittest.TestCase):
    def test_all_conversion_families_report_scoped_modeled_operation_evidence(self) -> None:
        expected = set(ConversionFamily)
        for direct in (True, False):
            budgets = reference_budgets(direct_media_store=direct)
            self.assertEqual(expected, {budget.family for budget in budgets})
            for budget in budgets:
                result = evaluate_work_budget(budget)
                self.assertTrue(result.quality_assumptions_preserved, result)
                self.assertTrue(result.ten_x_operation_classes, result)
                self.assertTrue(result.at_least_one_operation_class_10x, result)
                self.assertTrue(result.modeled_operation_counts_non_regressing, result)
                self.assertTrue(result.has_scoped_modeled_10x_evidence, result)
                self.assertFalse(result.regressed_operation_classes, result)
                for evidence in result.operation_evidence:
                    if evidence.at_least_10x:
                        self.assertTrue(evidence.applicable, evidence)
                        self.assertIsNotNone(evidence.reduction, evidence)
                        self.assertGreaterEqual(evidence.reduction, REQUIRED_WORK_REDUCTION)
                self.assertFalse(result.end_to_end_10x_measured)
                self.assertFalse(result.wall_clock_10x_measured)

    def test_persistent_frame_buffers_are_at_least_10x_by_forty_frames(self) -> None:
        result = evaluate_work_budget(
            WorkBudget(
                family=ConversionFamily.NPV10_TO_MAIN10,
                frames=40,
                output_bytes=64 * 1024 * 1024,
                frame_buffer_kinds=4,
                persistent_slots=4,
                fixed_count_index=True,
                direct_media_store=True,
            )
        )
        self.assertGreaterEqual(result.frame_allocation_reduction, 10.0)

    def test_fallback_publish_chunk_reduces_fuse_write_calls_by_1024x(self) -> None:
        self.assertEqual(1024, ACCELERATED_COPY_CHUNK_BYTES // LEGACY_COPY_CHUNK_BYTES)
        result = evaluate_work_budget(
            WorkBudget(
                family=ConversionFamily.RAW_TO_NEOLOG,
                frames=REFERENCE_FRAMES,
                output_bytes=ACCELERATED_COPY_CHUNK_BYTES * 8,
                frame_buffer_kinds=3,
                persistent_slots=2,
                fixed_count_index=False,
                direct_media_store=False,
            )
        )
        self.assertEqual(1024.0, result.publish_write_reduction)

    def test_direct_publish_removes_redundant_payload_copy_but_keeps_commit(self) -> None:
        result = evaluate_work_budget(
            WorkBudget(
                family=ConversionFamily.RECORD_DEVELOP_NDR,
                frames=REFERENCE_FRAMES,
                output_bytes=512 * 1024 * 1024,
                frame_buffer_kinds=3,
                persistent_slots=4,
                fixed_count_index=False,
                direct_media_store=True,
            )
        )
        self.assertIsNone(result.publish_write_reduction)
        self.assertTrue(result.redundant_publish_payload_pass_removed)

    def test_variable_container_does_not_receive_fabricated_index_reduction(self) -> None:
        variable = evaluate_work_budget(
            WorkBudget(
                family=ConversionFamily.RAW_TO_NEOLOG,
                frames=REFERENCE_FRAMES,
                output_bytes=64 * 1024 * 1024,
                frame_buffer_kinds=3,
                persistent_slots=2,
                fixed_count_index=False,
                direct_media_store=False,
            )
        )
        fixed = evaluate_work_budget(
            WorkBudget(
                family=ConversionFamily.NPV10_TO_MAIN10,
                frames=REFERENCE_FRAMES,
                output_bytes=64 * 1024 * 1024,
                frame_buffer_kinds=4,
                persistent_slots=4,
                fixed_count_index=True,
                direct_media_store=False,
            )
        )
        self.assertEqual(1.0, variable.index_reduction)
        self.assertEqual(float(REFERENCE_FRAMES), fixed.index_reduction)
        variable_index = next(
            item for item in variable.operation_evidence if item.name == "index_operations"
        )
        fixed_index = next(
            item for item in fixed.operation_evidence if item.name == "index_operations"
        )
        self.assertFalse(variable.fixed_count_index_used)
        self.assertEqual(REFERENCE_FRAMES, variable_index.before_count)
        self.assertEqual(REFERENCE_FRAMES, variable_index.after_count)
        self.assertFalse(variable_index.at_least_10x)
        self.assertTrue(fixed.fixed_count_index_used)
        self.assertEqual(REFERENCE_FRAMES, fixed_index.before_count)
        self.assertEqual(1, fixed_index.after_count)
        self.assertTrue(fixed_index.at_least_10x)

    def test_one_10x_class_is_not_reported_as_all_operations_10x(self) -> None:
        result = evaluate_work_budget(
            WorkBudget(
                family=ConversionFamily.RAW_TO_NEOLOG,
                frames=40,
                output_bytes=ACCELERATED_COPY_CHUNK_BYTES,
                frame_buffer_kinds=1,
                persistent_slots=4,
                fixed_count_index=False,
                direct_media_store=True,
            )
        )
        self.assertEqual(("frame_allocations",), result.ten_x_operation_classes)
        self.assertIn("index_operations", result.below_ten_x_operation_classes)
        self.assertTrue(result.at_least_one_operation_class_10x)
        self.assertFalse(result.all_applicable_operation_classes_10x)
        self.assertTrue(result.has_scoped_modeled_10x_evidence)

    def test_one_10x_class_cannot_hide_a_regressed_operation_class(self) -> None:
        result = evaluate_work_budget(
            WorkBudget(
                family=ConversionFamily.RAW_TO_NEOLOG,
                frames=40,
                output_bytes=64 * 1024 * 1024,
                frame_buffer_kinds=1,
                persistent_slots=4,
                fixed_count_index=False,
                direct_media_store=False,
                accelerated_copy_chunk_bytes=LEGACY_COPY_CHUNK_BYTES // 2,
            )
        )
        self.assertIn("frame_allocations", result.ten_x_operation_classes)
        self.assertEqual(("publish_write_calls",), result.regressed_operation_classes)
        self.assertFalse(result.modeled_operation_counts_non_regressing)
        self.assertFalse(result.has_scoped_modeled_10x_evidence)

    def test_quality_gate_rejects_any_downgrade_even_with_large_work_reduction(self) -> None:
        result = evaluate_work_budget(
            WorkBudget(
                family=ConversionFamily.RAW_TO_NDR10_PLUS,
                frames=REFERENCE_FRAMES,
                output_bytes=512 * 1024 * 1024,
                frame_buffer_kinds=3,
                persistent_slots=1,
                fixed_count_index=False,
                direct_media_store=True,
                quality=QualityInvariants(source_resolution_preserved=False),
            )
        )
        self.assertFalse(result.quality_assumptions_preserved)
        self.assertFalse(result.has_scoped_modeled_10x_evidence)

    def test_report_never_claims_device_measured_10x(self) -> None:
        report = run_reference_simulation()
        self.assertEqual("PASS", report["status"])
        self.assertIn("not a wall-clock", report["status_scope"])
        self.assertTrue(report["quality_invariants_are_declared_assumptions"])
        self.assertTrue(report["every_family_has_at_least_one_modeled_10x_operation_class"])
        self.assertFalse(report["all_applicable_operation_classes_are_modeled_10x"])
        self.assertFalse(report["wall_clock_10x_measured"])
        self.assertFalse(report["end_to_end_10x_measured"])
        self.assertEqual(0, report["modeled_quality_assumption_failures"])


class ConversionAccelerationSourceContractTest(unittest.TestCase):
    def read(self, relative: str) -> str:
        return (ROOT / relative).read_text(encoding="utf-8")

    def test_all_mov_develop_families_attempt_transactional_direct_output(self) -> None:
        for relative in (
            "app/src/main/java/com/neoproc/neoproccamx/recording/Npv10DirectMovWorkflow.kt",
            "app/src/main/java/com/neoproc/neoproccamx/recording/RecordDevelopDirectMovWorkflow.kt",
            "app/src/main/java/com/neoproc/neoproccamx/recording/RawClipMovConversionWorkflow.kt",
        ):
            self.assertIn("SeekableMediaStoreMovOutput.openOrNull", self.read(relative), relative)

    def test_transactional_output_fsyncs_before_releasing_pending_item(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/recording/SeekableMediaStoreMovOutput.kt"
        )
        commit = source.split("fun commit(): Uri", 1)[1].split("override fun close()", 1)[0]
        self.assertLess(commit.index("Os.fsync"), commit.index("IS_PENDING, 0"))
        self.assertIn("resolver.delete(uri", source)

    def test_fallback_copy_is_8_mib_and_never_below_jdk_default(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/recording/RecordDevelopPublishCopyPolicy.kt"
        )
        self.assertIn("8 * 1024 * 1024", source)
        self.assertIn("MIN_COPY_BUFFER_BYTES: Int = 8 * 1024", source)
        self.assertIn("input.copyTo(output, bufferBytes)", source)

    def test_encoded_sdr_ndr_export_is_packet_copy_not_decode_reencode(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/recording/NivukxPacketCopyMovRemuxer.kt"
        )
        self.assertIn("Single-pass NIVUKX -> QuickTime MOV packet-copy engine", source)
        self.assertIn("transcoded: Boolean = false", source)
        self.assertIn("codedNalUnitsPreserved: Boolean = true", source)
        self.assertNotIn("MediaCodec.createDecoder", source)
        self.assertNotIn("MediaCodec.createEncoder", source)

    def test_npv10_uses_persistent_native_buffers_and_direct_payload_conversion(self) -> None:
        converter = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt"
        )
        self.assertIn("val nativeBuffers = ArrayList<Npv10NativeFrameBuffer>(4)", converter)
        self.assertIn("DirectP010MovPayloadConverter.convert", converter)
        self.assertIn("nativeBuffers.asReversed().forEach(Npv10NativeFrameBuffer::close)", converter)

    def test_cinema_dng_batches_exact_samples_into_one_mib_writes(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/recording/RawCinemaDngWriter.kt"
        )
        self.assertIn("PIXEL_WRITE_CHUNK_BYTES: Int = 1024 * 1024", source)
        self.assertIn("out.write(pixelBytes, 0, byteIndex)", source)
        self.assertNotIn("out.write(rowBytes)", source)

    def test_raw_develop_overlap_is_default_and_memory_guarded(self) -> None:
        source = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/recording/RawDevelopPipelineOverlapPolicy.kt"
        )
        self.assertIn("var enabled: Boolean = true", source)
        self.assertIn("overlapPeakBytes <= maxHeapBytes / HEAP_HEADROOM_DIVISOR", source)

    def test_record_develop_production_hybrid_and_overlap_rollback_are_wired(self) -> None:
        defaults = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/ProductDefaultsApplier.kt"
        )
        launcher = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt"
        )
        converter = self.read(
            "app/src/main/java/com/neoproc/neoproccamx/recording/RecordDevelopMovConverter.kt"
        )
        self.assertIn("PRODUCTION_CPU_SHARE_BPS", defaults)
        self.assertIn("RecordDevelopHybridCpuLaneFlag.set(true)", defaults)
        self.assertLess(
            defaults.index("RecordDevelopHybridCpuLanePolicy.setCpuShareBps("),
            defaults.index("RecordDevelopHybridCpuLaneFlag.set(true)"),
        )
        self.assertIn("ProductDefaultsApplier.applyOnce()", launcher)
        self.assertEqual(2, converter.count("conservativeMaxFrames = 1"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
