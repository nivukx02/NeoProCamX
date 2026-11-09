package com.neoproc.neoproccamx.recording

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class OwnedPendingMediaStoreQueryTest {
    @Test
    fun ownerAndNamesRemainBoundParametersOnBothAndroidQueryRoutes() {
        val owner = "com.test' OR 1=1"
        for (sdk in listOf(29, 30, 36)) {
            val plan = OwnedPendingMediaStoreQuery.plan(sdk, owner, "_display_name=?", arrayOf("clip.npv10"))
            assertEquals(sdk >= 30, plan.modernArguments)
            assertEquals("(owner_package_name=?) AND (_display_name=?)", plan.selection)
            assertArrayEquals(arrayOf(owner, "clip.npv10"), plan.arguments)
            assertFalse(plan.selection.contains(owner))
        }
    }

    @Test
    fun missingOwnershipOrScopeCannotQueryUnrelatedPendingRows() {
        assertTrue(runCatching {
            OwnedPendingMediaStoreQuery.plan(36, "", "_id=?", arrayOf("1"))
        }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching {
            OwnedPendingMediaStoreQuery.plan(36, "com.test", "", emptyArray())
        }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun bothMtpReplicationAndStartupMovRecoveryIncludeOwnedPendingRows() {
        for (name in listOf("CinemaCamMtpPublisher", "RecordingMediaStoreOutputStore")) {
            val source = File("src/main/java/com/neoproc/neoproccamx/recording/$name.kt").readText()
            assertTrue("$name lost pending recovery", source.contains("OwnedPendingMediaStoreQuery.query("))
        }
        val helper = File("src/main/java/com/neoproc/neoproccamx/recording/OwnedPendingMediaStoreQuery.kt").readText()
        assertTrue(helper.contains("putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)"))
        assertTrue(helper.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.R"))
        assertTrue(helper.contains("includePendingOnAndroid10(collection)"))
    }
}
