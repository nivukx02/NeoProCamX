package com.neoproc.neoproccamx.recording

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10WriteLifetimeContractTest {
    @Test fun `master placement hint is file local and precedes the first writer`() {
        val root = listOf(File("src/main/cpp"), File("app/src/main/cpp")).first { it.isDirectory }
        val native = File(root, "npv10_dmc_native.cpp").readText()
        val create = native.substringAfter("static std::shared_ptr<DirectMappedCommitEngine> create(")
            .substringBefore("~DirectMappedCommitEngine()")
        val hint = create.indexOf("RecordWriteLifetime<> lifetime(descriptor)")
        assertTrue(hint > create.indexOf("if (!S_ISREG(fileStatus.st_mode)"))
        assertTrue(hint < create.indexOf("engine->initializeAlignedRecordWriter(path)"))
        assertTrue(hint < create.indexOf("engine->startRecordWriterWorker()"))
        assertTrue(create.contains("lifetime.applied ? \"true\" : \"false\""))
        assertTrue(create.contains("scope=owned_master_inode durability=unchanged"))
        val header = File(root, "npv10_record_write_lifetime.h").readText()
        assertTrue(header.contains("std::uint64_t* value"))
        assertTrue(header.contains("previous == kUnspecified"))
        assertTrue(header.contains("observed != requested"))
        assertTrue(header.contains("errno = savedErrno"))
    }
}
