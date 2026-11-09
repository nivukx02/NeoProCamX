package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Contract for the RAW clip store: a session directory is convertible only when it holds BOTH the
 * NPRAW stream AND the develop.meta sidecar, so an interrupted/half-written capture never appears.
 */
class Raw12MasterStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun session(name: String, withStream: Boolean, withMeta: Boolean): File {
        val dir = tmp.newFolder(name)
        if (withStream) File(dir, Raw12MasterStore.STREAM_NAME).writeBytes(ByteArray(16))
        if (withMeta) File(dir, Raw12MasterStore.META_NAME).writeBytes(ByteArray(8))
        return dir
    }

    @Test
    fun listsOnlyCompleteClips() {
        session("clip_a", withStream = true, withMeta = true)
        session("clip_b", withStream = true, withMeta = true)
        session("stream_only", withStream = true, withMeta = false)
        session("meta_only", withStream = false, withMeta = true)
        session("empty", withStream = false, withMeta = false)

        val clips = Raw12MasterStore(tmp.root).listClips()
        assertEquals(2, clips.size)
        assertTrue(clips.all { it.streamFile.isFile && it.metaFile.isFile })
        assertTrue(clips.map { it.name }.containsAll(listOf("clip_a", "clip_b")))
    }

    @Test
    fun resolveClipRejectsSessionMissingArtifacts() {
        assertNull(Raw12MasterStore(tmp.root).resolveClip(session("x", withStream = true, withMeta = false)))
        assertNull(Raw12MasterStore(tmp.root).resolveClip(session("y", withStream = false, withMeta = true)))
    }

    @Test
    fun emptyRootListsNothing() {
        assertTrue(Raw12MasterStore(tmp.newFolder("empty_root")).listClips().isEmpty())
    }
}
