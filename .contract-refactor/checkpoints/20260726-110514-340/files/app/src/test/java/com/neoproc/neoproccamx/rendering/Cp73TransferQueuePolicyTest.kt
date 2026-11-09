package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** s7-f11 / CP73 — pure dedicated-transfer-queue capability resolver + native-detection presence pin. */
class Cp73TransferQueuePolicyTest {

    @Test
    fun parsesAvailabilityTokenOnlyAtBoundary() {
        assertTrue(Cp73TransferQueuePolicy.parseDedicatedTransferQueueAvailable("a dedicatedTransferQueue=available b"))
        assertTrue(Cp73TransferQueuePolicy.parseDedicatedTransferQueueAvailable("dedicatedTransferQueue=available"))
        assertFalse(Cp73TransferQueuePolicy.parseDedicatedTransferQueueAvailable("gpu=ok dedicatedTransferQueue=unavailable"))
        assertFalse(Cp73TransferQueuePolicy.parseDedicatedTransferQueueAvailable("nope=1"))
        // Leading substring must not spoof the token.
        assertFalse(Cp73TransferQueuePolicy.parseDedicatedTransferQueueAvailable("xdedicatedTransferQueue=available"))
    }

    @Test
    fun zeroCopyTransferReadyRequiresBothFamilyAndFlag() {
        val withFamily = "zeroCopyLiveImport=available dedicatedTransferQueue=available"
        val withoutFamily = "dedicatedTransferQueue=unavailable"
        val withoutLiveImport = "zeroCopyLiveImport=unavailable dedicatedTransferQueue=available"
        // Explicit opt-out => never ready, even with the family present.
        assertFalse(Cp73TransferQueuePolicy.zeroCopyTransferReady(withFamily, flagEnabled = false))
        // Default-requested ON + all native capability tokens present => ready for the device-gated transfer path.
        assertTrue(Cp73TransferQueuePolicy.zeroCopyTransferReady(withFamily, flagEnabled = true))
        // Default-requested ON but no family => not ready (no synthesized pass).
        assertFalse(Cp73TransferQueuePolicy.zeroCopyTransferReady(withoutFamily, flagEnabled = true))
        // Default-requested ON but no live zero-copy import => not ready (partial capability is not enough).
        assertFalse(Cp73TransferQueuePolicy.zeroCopyTransferReady(withoutLiveImport, flagEnabled = true))
    }

    @Test
    fun nativeDetectionIsWiredAndReadOnly() {
        var dir = File(System.getProperty("user.dir") ?: ".")
        var cpp: String? = null
        repeat(6) {
            val c = File(dir, "app/src/main/cpp/vulkan_bridge.cpp")
            if (c.isFile) { cpp = c.readText(); return@repeat }
            val appRel = File(dir, "src/main/cpp/vulkan_bridge.cpp")
            if (appRel.isFile) { cpp = appRel.readText(); return@repeat }
            dir = dir.parentFile ?: return@repeat
        }
        val source = cpp ?: error("vulkan_bridge.cpp not found")
        // The detection scans for a transfer-only family and reports it via the token consumed above.
        assertTrue(source.contains("VK_QUEUE_TRANSFER_BIT"))
        assertTrue(source.contains("hasDedicatedTransferQueue"))
        assertTrue(source.contains("dedicatedTransferQueue="))
    }
}
