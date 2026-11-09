package com.neoproc.neoproccamx.ui

import android.util.Log
import com.neoproc.neoproccamx.camera.CameraController
import com.neoproc.neoproccamx.rendering.RecordOutputMirrorFlag
import com.neoproc.neoproccamx.rendering.RecordOutputMirrorPlan

internal fun CameraController.recordOutputMirrorPlan(reason: String): RecordOutputMirrorPlan {
    val plan = RecordOutputMirrorFlag.resolveForRecording(
        lensFacing = facing,
        sensorOrientationDegrees = sensorOrientation(),
        orientationHintDegrees = videoOrientationHint()
    )
    Log.i("CameraViewModel", "Record output mirror plan[$reason]: flipX=${plan.flipX} flipY=${plan.flipY} ${plan.source}")
    return plan
}
