package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraSessionSurfaceRolePolicyTest {

    @Test
    fun `preview surface remains preview even when encoder is present`() {
        val role = CameraSessionSurfaceRolePolicy.resolveStreamUseCaseRole(
            CameraSessionSurfaceKind.PREVIEW
        )

        assertEquals(StreamUseCasePolicy.StreamRole.PREVIEW, role)
    }

    @Test
    fun `encoder surface is the only video record stream use case`() {
        val role = CameraSessionSurfaceRolePolicy.resolveStreamUseCaseRole(
            CameraSessionSurfaceKind.RECORD
        )

        assertEquals(StreamUseCasePolicy.StreamRole.VIDEO_RECORD, role)
    }

    @Test
    fun `preview parity encoder surface still uses video record stream use case`() {
        val role = CameraSessionSurfaceRolePolicy.resolveStreamUseCaseRole(
            CameraSessionSurfaceKind.RECORD,
            previewParityRecording = true
        )

        assertEquals(StreamUseCasePolicy.StreamRole.VIDEO_RECORD, role)
    }

    @Test
    fun `hardware import preview uses preview tuned stream use case`() {
        val role = CameraSessionSurfaceRolePolicy.resolveStreamUseCaseRole(
            CameraSessionSurfaceKind.HARDWARE_IMPORT
        )

        assertEquals(StreamUseCasePolicy.StreamRole.PREVIEW, role)
    }
}
