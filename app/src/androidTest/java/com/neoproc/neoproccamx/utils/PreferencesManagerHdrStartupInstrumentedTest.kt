package com.neoproc.neoproccamx.utils

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Device-level construction test for the real SharedPreferences migration path. */
@RunWith(AndroidJUnit4::class)
class PreferencesManagerHdrStartupInstrumentedTest {
    private lateinit var isolatedContext: Context
    private lateinit var sharedPreferences: SharedPreferences

    @Before
    fun setUp() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        isolatedContext = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
                baseContext.getSharedPreferences(ISOLATED_PREFS_NAME, mode)
        }
        sharedPreferences = isolatedContext.getSharedPreferences(
            PreferencesManager.PREFS_NAME,
            Context.MODE_PRIVATE
        )
        assertTrue(sharedPreferences.edit().clear().commit())
    }

    @After
    fun tearDown() {
        assertTrue(sharedPreferences.edit().clear().commit())
    }

    @Test
    fun emptyPreferencesConstructAsVideoOffWithoutDisablingTenBitTransport() {
        val manager = PreferencesManager(isolatedContext)

        assertEquals("OFF", manager.videoHdrMode)
        assertEquals(
            "OFF",
            sharedPreferences.getString(PreferencesManager.KEY_VIDEO_HDR_MODE, null)
        )
        assertTrue(
            sharedPreferences.getBoolean(HDR_DEFAULT_MIGRATION_MARKER, false)
        )
        assertTrue(manager.tenBitRecordingEnabled)
    }

    @Test
    fun everyExplicitHdrSelectionSurvivesRealManagerConstruction() {
        listOf("OFF", "NDR10+", "NIVUKXVISION").forEach { selectedMode ->
            assertTrue(sharedPreferences.edit().clear().commit())
            assertTrue(
                sharedPreferences.edit()
                    .putString(PreferencesManager.KEY_VIDEO_HDR_MODE, selectedMode)
                    .commit()
            )

            assertEquals(selectedMode, PreferencesManager(isolatedContext).videoHdrMode)
            assertEquals(
                selectedMode,
                sharedPreferences.getString(PreferencesManager.KEY_VIDEO_HDR_MODE, null)
            )
        }
    }

    @Test
    fun completedMigrationNeverOverwritesALaterExplicitSelection() {
        val firstConstruction = PreferencesManager(isolatedContext)
        assertEquals("OFF", firstConstruction.videoHdrMode)
        assertTrue(sharedPreferences.getBoolean(HDR_DEFAULT_MIGRATION_MARKER, false))

        firstConstruction.videoHdrMode = "NDR10+"
        val reconstructed = PreferencesManager(isolatedContext)

        assertEquals("NDR10+", reconstructed.videoHdrMode)
        assertEquals(
            "NDR10+",
            sharedPreferences.getString(PreferencesManager.KEY_VIDEO_HDR_MODE, null)
        )
    }

    private companion object {
        const val ISOLATED_PREFS_NAME = "hdr_startup_authority_instrumented_test"
        const val HDR_DEFAULT_MIGRATION_MARKER = "hdr_recording_default_migrated_v1"
    }
}
