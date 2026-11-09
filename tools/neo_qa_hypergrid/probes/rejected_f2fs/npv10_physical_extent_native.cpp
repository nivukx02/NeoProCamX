// Rejected experimental JNI, excluded from every application target.
#include "npv10_physical_extent_reservation.h"
#include <jni.h>

extern "C" JNIEXPORT jintArray JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10PhysicalExtentAllocator_nativeAllocate(
    JNIEnv* env, jclass, jint descriptor, jlong offset, jlong count) {
    const auto result = neoproc::npv10::PhysicalExtentReservation<>::allocate(descriptor, offset, count);
    const jint values[] = {result.error, result.capabilityError, result.releaseError,
        result.materialized ? 1 : 0};
    auto output = env->NewIntArray(4);
    if (output != nullptr) env->SetIntArrayRegion(output, 0, 4, values);
    return output;
}
