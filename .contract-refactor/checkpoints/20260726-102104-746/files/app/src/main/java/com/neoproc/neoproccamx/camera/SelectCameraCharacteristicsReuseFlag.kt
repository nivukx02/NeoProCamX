package com.neoproc.neoproccamx.camera

/**
 * SelectCameraCharacteristicsReuseFlag — reversible reuse (DEFAULT OFF) of the CameraCharacteristics that
 * [CameraController.selectCamera] already fetched during lens enumeration for the chosen camera id.
 *
 * selectCamera enumerates every id via buildLensCandidate(), each of which calls
 * cameraManager.getCameraCharacteristics(id). Once a winner is picked, loadCharacteristics(id) then issues
 * a SECOND getCameraCharacteristics(id) for that same id — a redundant Camera2 service query of static,
 * immutable per-camera metadata. When enabled, selectCamera accumulates the enumerated characteristics
 * into a SHORT-LIVED LOCAL map and hands the winner's already-fetched instance to a loadCharacteristics
 * overload, skipping the duplicate query.
 *
 * SAFE by construction: CameraCharacteristics is static/immutable for a given camera, so a fresh query
 * returns content-identical metadata. The map is a local val scoped to a single selectCamera call (GC'd
 * on return); there is NO process-wide memoization and nothing is stashed on CameraLensCandidate. When
 * OFF (shipped default) the enumeration sink is null, buildLensCandidate writes nothing, and
 * loadCharacteristics(id) re-queries exactly as today — byte-identical.
 *
 * NO-DOWNGRADE: removes one redundant service round-trip on camera switch; resolution/fps/bit-depth/look/
 * feature are unchanged. Default OFF (device A/B only, micro-latency IPC save):
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.select_camera_chars_reuse true
 */
object SelectCameraCharacteristicsReuseFlag {

    @Volatile
    var enabled: Boolean = false
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
