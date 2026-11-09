from __future__ import annotations

import ast
import operator
import re
import unittest

import activation_evidence
import validate_shaders


_INTEGER_OPERATORS = {
    ast.Add: operator.add,
    ast.Sub: operator.sub,
    ast.Mult: operator.mul,
    ast.FloorDiv: operator.floordiv,
}


def _evaluate_kotlin_integer_constants(source: str) -> dict[str, int]:
    expressions = dict(
        re.findall(r"^\s*const val (\w+)\s*=\s*([^\r\n]+)", source, flags=re.MULTILINE)
    )
    resolved: dict[str, int] = {}

    def evaluate(node: ast.AST) -> int:
        if isinstance(node, ast.Constant) and isinstance(node.value, int):
            return node.value
        if isinstance(node, ast.Name) and node.id in resolved:
            return resolved[node.id]
        if isinstance(node, ast.BinOp) and type(node.op) in _INTEGER_OPERATORS:
            return _INTEGER_OPERATORS[type(node.op)](evaluate(node.left), evaluate(node.right))
        raise ValueError(ast.dump(node))

    pending = dict(expressions)
    while pending:
        progress = False
        for name, expression in list(pending.items()):
            try:
                resolved[name] = evaluate(ast.parse(expression, mode="eval").body)
            except (SyntaxError, ValueError, ZeroDivisionError):
                continue
            del pending[name]
            progress = True
        if not progress:
            break
    return resolved


def _native_header() -> str:
    return (
        validate_shaders.REPO_ROOT / "app/src/main/cpp/niv99_vulkan_pipeline.h"
    ).read_text(encoding="utf-8")


def _native_source() -> str:
    return (
        validate_shaders.REPO_ROOT / "app/src/main/cpp/niv99_vulkan_pipeline.cpp"
    ).read_text(encoding="utf-8")


def _method_through_next(source: str, start: str, next_start: str) -> str:
    start_index = source.find(start)
    if start_index < 0:
        raise AssertionError(f"missing native method marker: {start}")
    end_index = source.find(next_start, start_index + len(start))
    if end_index < 0:
        raise AssertionError(f"missing next native method marker: {next_start}")
    return source[start_index:end_index]


class Niv99ShaderContractTest(unittest.TestCase):
    def test_committed_spirv_is_reproducible_and_abi_valid(self) -> None:
        self.assertEqual([], validate_shaders.validate_all())

    def test_kotlin_and_native_record_sizes_match(self) -> None:
        root = validate_shaders.REPO_ROOT
        kotlin = (root / "app/src/main/java/com/neoproc/neoproccamx/niv99/Niv99ShaderAbi.kt").read_text(
            encoding="utf-8"
        )
        native = _native_header()
        expected = {
            "UNIFORM_BYTES": 256,
            "TILE_CONTROL_BYTES": 64,
            "TILE_STATISTICS_BYTES": 64,
            "TELEMETRY_RECORD_BYTES": 80,
        }
        kotlin_constants = _evaluate_kotlin_integer_constants(kotlin)
        for kotlin_name, size in expected.items():
            self.assertIn(kotlin_name, kotlin_constants)
            self.assertEqual(size, kotlin_constants[kotlin_name])
        native_names = {
            "kUniformBytes": 256,
            "kTileControlBytes": 64,
            "kTileStatisticsBytes": 64,
            "kTelemetryRecordBytes": 80,
        }
        for native_name, size in native_names.items():
            self.assertRegex(native, rf"constexpr uint32_t {native_name} = {size}u;")

    def test_python_and_kotlin_activation_evidence_bits_match(self) -> None:
        source = (
            validate_shaders.REPO_ROOT
            / "app/src/main/java/com/neoproc/neoproccamx/niv99/Niv99RuntimeActivation.kt"
        ).read_text(encoding="utf-8")
        match = re.search(
            r"enum class Niv99ActivationEvidence \{([\s\S]*?);\s*\n\s*val bit",
            source,
        )
        self.assertIsNotNone(match)
        body = match.group(1) if match is not None else ""
        kotlin_order = tuple(
            item.strip()
            for item in body.replace("\r", "").replace("\n", "").split(",")
            if item.strip()
        )
        self.assertEqual(activation_evidence.EVIDENCE_ORDER, kotlin_order)

    def test_python_and_kotlin_activation_certificate_fields_match(self) -> None:
        source = (
            validate_shaders.REPO_ROOT
            / "app/src/main/java/com/neoproc/neoproccamx/niv99/Niv99RuntimeActivation.kt"
        ).read_text(encoding="utf-8")
        match = re.search(
            r"data class Niv99RuntimeEvidenceCertificate\(([\s\S]*?)\n\)",
            source,
        )
        self.assertIsNotNone(match)
        body = match.group(1) if match is not None else ""
        kotlin_fields = tuple(re.findall(r"\bval\s+(\w+)\s*:", body))
        self.assertEqual(activation_evidence.CERTIFICATE_FIELDS, kotlin_fields)

    def test_submit_path_has_no_device_idle_or_descriptor_allocation(self) -> None:
        submit = _method_through_next(
            _native_source(),
            "Status VulkanPipeline::submit(",
            "Status VulkanPipeline::retireCompletedSlotsLocked",
        )
        self.assertNotIn("vkDeviceWaitIdle", submit)
        self.assertNotIn("vkQueueWaitIdle", submit)
        self.assertNotIn("vkAllocateDescriptorSets", submit)
        self.assertNotIn("vkAllocateMemory", submit)

    def test_native_requires_extended_storage_image_feature(self) -> None:
        header = _native_header()
        initialize = _method_through_next(
            _native_source(),
            "Status VulkanPipeline::initialize(",
            "Status VulkanPipeline::validateConfiguration(",
        )
        self.assertIn("bool storageImageExtendedFormatsEnabled = false;", header)
        self.assertIn("!context.storageImageExtendedFormatsEnabled", initialize)
        self.assertIn("return Status::kUnsupported;", initialize)

    def test_native_validates_full_descriptor_layout_and_compute_queue(self) -> None:
        validation = _method_through_next(
            _native_source(),
            "Status VulkanPipeline::validateConfiguration(",
            "Status VulkanPipeline::validateFormats(",
        )
        self.assertIn("vkGetPhysicalDeviceQueueFamilyProperties", validation)
        self.assertIn("VK_QUEUE_COMPUTE_BIT", validation)
        self.assertIn(
            "maxPerStageDescriptorStorageImages < kImageBindingCount",
            validation,
        )
        self.assertIn(
            "maxPerStageDescriptorStorageBuffers < kStorageBufferBindingCount",
            validation,
        )

    def test_reinitialization_requires_identical_device_and_graph(self) -> None:
        initialize = _method_through_next(
            _native_source(),
            "Status VulkanPipeline::initialize(",
            "Status VulkanPipeline::validateConfiguration(",
        )
        self.assertIn("const bool sameContext", initialize)
        self.assertIn("const bool sameConfiguration", initialize)
        self.assertIn(
            "NIV99 cannot be reinitialized with a different device or graph configuration",
            initialize,
        )

    def test_shared_queue_submission_is_externally_serialized(self) -> None:
        header = _native_header()
        submit = _method_through_next(
            _native_source(),
            "Status VulkanPipeline::submit(",
            "Status VulkanPipeline::retireCompletedSlotsLocked",
        )
        self.assertIn("std::mutex* queueMutex = nullptr;", header)
        self.assertRegex(
            submit,
            r"if \(context_\.queueMutex == nullptr\) \{\s*"
            r"submitResult = vkQueueSubmit\([^;]+;\s*"
            r"\} else \{\s*"
            r"std::lock_guard<std::mutex> queueLock\(\*context_\.queueMutex\);\s*"
            r"submitResult = vkQueueSubmit\([^;]+;",
        )

    def test_submit_clears_old_token_and_requires_explicit_timeline_input(self) -> None:
        header = _native_header()
        submit = _method_through_next(
            _native_source(),
            "Status VulkanPipeline::submit(",
            "Status VulkanPipeline::retireCompletedSlotsLocked",
        )
        self.assertIn("bool inputReadySemaphoreIsTimeline = false;", header)
        self.assertIn("*outputToken = SubmissionToken{};", submit)
        self.assertIn("!submission.inputReadySemaphoreIsTimeline", submit)
        self.assertIn("submission.inputReadyTimelineValue == 0u", submit)
        self.assertIn("NIV99 timeline sequence is exhausted", submit)

    def test_completion_query_failure_latches_and_blocks_native_submission(self) -> None:
        header = _native_header()
        source = _native_source()
        submit = _method_through_next(
            source,
            "Status VulkanPipeline::submit(",
            "Status VulkanPipeline::retireCompletedSlotsLocked",
        )
        retirement = _method_through_next(
            source,
            "Status VulkanPipeline::retireCompletedSlotsLocked",
            "Completion VulkanPipeline::poll(",
        )
        poll = _method_through_next(
            source,
            "Completion VulkanPipeline::poll(",
            "Status VulkanPipeline::cancelGeneration(",
        )

        self.assertIn("bool runtimeFailed_ = false;", header)
        self.assertIn("if (runtimeFailed_) return Status::kVulkanError;", submit)
        self.assertIn("const Status retirementStatus = retireCompletedSlotsLocked();", submit)
        self.assertIn("if (retirementStatus != Status::kOk) return retirementStatus;", submit)
        self.assertIn("NIV99 completion timeline query failed", retirement)
        self.assertRegex(
            retirement,
            r"completion timeline query failed[^}]+runtimeFailed_ = true;[^}]+"
            r"return Status::kVulkanError;",
        )
        self.assertIn(
            "if (retireCompletedSlotsLocked() != Status::kOk) return Completion::kFailed;",
            poll,
        )

    def test_writable_images_cannot_alias_inputs_or_each_other(self) -> None:
        validation = _method_through_next(
            _native_source(),
            "bool VulkanPipeline::validateFrameImages(",
            "Status VulkanPipeline::configureSlot(",
        )
        self.assertIn("const std::array<VkImage, 4u> writableImages", validation)
        self.assertIn("const std::array<VkImage, 8u> readOnlyImages", validation)
        self.assertIn("writableImages[left] == readOnlyImage", validation)
        self.assertIn("writableImages[left] == writableImages[right]", validation)
        self.assertIn("images.reconstructedOutput.image", validation)

    def test_completion_timeline_semaphore_is_exported_safely(self) -> None:
        header = _native_header()
        accessor = _method_through_next(
            _native_source(),
            "VkSemaphore VulkanPipeline::completionSemaphore() const noexcept",
            "VkBuffer VulkanPipeline::statisticsBuffer(",
        )
        self.assertIn("VkSemaphore completionSemaphore() const noexcept;", header)
        self.assertIn("std::lock_guard<std::mutex> lock(mutex_);", accessor)
        self.assertIn(
            "return initialized_ && !closed_ ? timelineSemaphore_ : VK_NULL_HANDLE;",
            accessor,
        )

    def test_teardown_refuses_to_destroy_resources_if_queue_cannot_retire(self) -> None:
        close = _method_through_next(
            _native_source(),
            "Status VulkanPipeline::close() noexcept",
            "void VulkanPipeline::destroyResourcesLocked(",
        )
        retirement_failure = close.find(
            'setFailure("NIV99 queue could not be retired safely during teardown")'
        )
        refusal = close.find("return Status::kVulkanError;", retirement_failure)
        destruction = close.find("destroyResourcesLocked(false);")
        self.assertGreaterEqual(retirement_failure, 0)
        self.assertGreater(refusal, retirement_failure)
        self.assertGreater(destruction, refusal)


if __name__ == "__main__":
    unittest.main()
