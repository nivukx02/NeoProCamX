package com.neoproc.neoproccamx.recording

import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class RawDevelopGpuWorkspaceTest {

    @Test
    fun `mapped route reuses only its bounded raw upload`() {
        val workspace = RawDevelopGpuInputWorkspace()
        lateinit var first: ByteBuffer
        workspace.use(8, 6) { input ->
            first = input.mosaic
            assertEquals(8 * 6 * Short.SIZE_BYTES, input.mosaic.capacity())
        }
        workspace.use(8, 6) { input -> assertSame(first, input.mosaic) }
        assertEquals(1, workspace.allocationCount)

        workspace.clear()
        workspace.use(8, 6) { input -> assertNotSame(first, input.mosaic) }
        assertEquals(2, workspace.allocationCount)
    }

    @Test
    fun `same geometry reuses both direct buffers`() {
        val workspace = RawDevelopGpuWorkspace()
        lateinit var firstMosaic: ByteBuffer
        lateinit var firstOutput: ByteBuffer

        workspace.use(8, 6) { buffers ->
            firstMosaic = buffers.mosaic
            firstOutput = buffers.out
            assertEquals(8 * 6 * Short.SIZE_BYTES, buffers.mosaic.capacity())
            assertEquals(8 * 6 * 3 * Float.SIZE_BYTES, buffers.out.capacity())
        }
        workspace.use(8, 6) { buffers ->
            assertSame(firstMosaic, buffers.mosaic)
            assertSame(firstOutput, buffers.out)
        }

        assertEquals(1, workspace.allocationCount)
    }

    @Test
    fun `geometry change or clear creates one correctly sized replacement`() {
        val workspace = RawDevelopGpuWorkspace()
        lateinit var original: ByteBuffer
        workspace.use(8, 6) { original = it.mosaic }

        workspace.use(10, 4) { replacement ->
            assertNotSame(original, replacement.mosaic)
            assertEquals(10 * 4 * Short.SIZE_BYTES, replacement.mosaic.capacity())
            assertEquals(10 * 4 * 3 * Float.SIZE_BYTES, replacement.out.capacity())
        }
        assertEquals(2, workspace.allocationCount)

        workspace.clear()
        workspace.use(10, 4) { afterClear ->
            assertEquals(10 * 4, afterClear.pixelCount)
        }
        assertEquals(3, workspace.allocationCount)
    }
}
