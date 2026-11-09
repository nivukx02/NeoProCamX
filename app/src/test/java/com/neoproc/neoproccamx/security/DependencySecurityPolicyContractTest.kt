package com.neoproc.neoproccamx.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DependencySecurityPolicyContractTest {
    @Test
    fun releaseRuntime_pinsProtobufToReviewedFixedCompatibleLine() {
        val catalog = File("../gradle/libs.versions.toml").readText()
        val appBuild = File("build.gradle").readText()

        assertTrue(catalog.contains("protobuf-javalite = \"4.27.5\""))
        assertTrue(
            catalog.contains(
                "protobuf-javalite = { module = \"com.google.protobuf:protobuf-javalite\", " +
                    "version.ref = \"protobuf-javalite\" }"
            )
        )
        assertTrue(appBuild.contains("implementation(libs.protobuf.javalite)"))
        assertTrue(appBuild.contains("strictly(libs.versions.protobuf.javalite.get())"))
        assertTrue(appBuild.contains("CVE-2024-7254 / GHSA-735f-pc8j-v9w8"))
    }

    @Test
    fun hostOnlyAnnotationAndLintGraphs_pinReviewedNonAffectedVersions() {
        val catalog = File("../gradle/libs.versions.toml").readText()
        val appBuild = File("build.gradle").readText()

        assertTrue(catalog.contains("guava-annotation-processor = \"33.3.1-android\""))
        assertTrue(appBuild.contains("annotationProcessor(libs.guava.annotation.processor)"))
        assertTrue(appBuild.contains("strictly(libs.versions.guava.annotation.processor.get())"))
        listOf(
            "org.apache.commons:commons-lang3:3.18.0",
            "org.apache.httpcomponents:httpclient:4.5.14",
            "org.bouncycastle:bcpkix-jdk18on:1.84",
            "org.bouncycastle:bcprov-jdk18on:1.84",
            "org.bouncycastle:bcutil-jdk18on:1.84"
        ).forEach { coordinate ->
            assertTrue(coordinate, appBuild.contains("add('androidLintTool', '$coordinate')"))
        }
    }

    @Test
    fun rootBuildscript_pinsReviewedFixedTransitivesWithoutPrereleaseKotlin() {
        val rootBuild = File("../build.gradle").readText()

        listOf(
            "org.apache.commons:commons-lang3:3.18.0",
            "org.bitbucket.b_c:jose4j:0.9.6",
            "org.bouncycastle:bcpkix-jdk18on:1.84",
            "org.bouncycastle:bcprov-jdk18on:1.84",
            "org.bouncycastle:bcutil-jdk18on:1.84",
            "org.jdom:jdom2:2.0.6.1"
        ).forEach { coordinate ->
            assertTrue(coordinate, rootBuild.contains("classpath('$coordinate')"))
        }
        assertTrue(rootBuild.contains("CVE-2026-53914"))
        assertTrue(rootBuild.contains("must not consume caches from untrusted writers"))
        assertFalse(rootBuild.contains("kotlin-gradle-plugin:2.4.20-Beta"))
        assertFalse(rootBuild.contains("kotlin-gradle-plugin:2.4.20-RC"))
    }

    @Test
    fun strictVerificationMetadata_containsIndependentlyReviewedFixedArtifacts() {
        val metadata = File("../gradle/verification-metadata.xml").readText()

        listOf(
            "protobuf-javalite-4.27.5.jar" to
                "210b5eb69f95a5d2985001dce8535e2f0084a365ff73429c28189d14c9baea8b",
            "commons-lang3-3.18.0.jar" to
                "4eeeae8d20c078abb64b015ec158add383ac581571cddc45c68f0c9ae0230720",
            "jose4j-0.9.6.jar" to
                "7314af50cde9c99e8eaf43eee617a23edcc6bb43036221064355094999d837ef",
            "bcpkix-jdk18on-1.84.jar" to
                "c87f16ed9e5ec61bc94151e9f3646ac44e50cd448121ce84367fa4b7ec7ec1bb",
            "bcprov-jdk18on-1.84.jar" to
                "64d6c5a6121fcd927152dd182cbed39afe0fda641a970d9bcc0c9cb1858b2731",
            "bcutil-jdk18on-1.84.jar" to
                "b374e16963421fb9cfb01cc20d7ad8fd2f8b8188e3eef0ec0a8965e245f7619a",
            "jdom2-2.0.6.1.jar" to
                "0b20f45e3a0fd8f0d12cdc5316b06776e902b1365db00118876f9175c60f302c"
        ).forEach { (artifact, checksum) ->
            val artifactIndex = metadata.indexOf("<artifact name=\"$artifact\">")
            assertTrue("Missing $artifact", artifactIndex >= 0)
            val checksumIndex = metadata.indexOf("<sha256 value=\"$checksum\"", artifactIndex)
            assertTrue("Missing reviewed SHA-256 for $artifact", checksumIndex > artifactIndex)
        }
    }
}
