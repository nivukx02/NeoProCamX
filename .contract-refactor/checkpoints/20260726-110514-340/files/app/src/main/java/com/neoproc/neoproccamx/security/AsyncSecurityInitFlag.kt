package com.neoproc.neoproccamx.security

/**
 * AsyncSecurityInitFlag — reversible cold-start sequencing (DEFAULT ON) of the runtime security
 * tamper/root evaluation and the on-device diagnostics log.
 *
 * The disabled diagnostic path runs startup tasks serially on the main thread before the first
 * activity can draw: NeoLogger.initialize() (log-dir mkdirs + file creation), PreferencesManager
 * construction/migrations, and RuntimeSecurityManager.initialize() (APK-signing tamper inspection +
 * root/emulator probes). The tamper/root probes and the log-file I/O are the slow, main-thread-blocking
 * pieces.
 *
 * When ENABLED: (1) RuntimeSecurityManager.initialize() is launched early as an
 * appScope.async(Dispatchers.Default) so the tamper/root evaluation runs OFF the main thread, and (2)
 * NeoLogger.initialize() moves onto an appScope.launch(Dispatchers.IO) task. PreferencesManager stays
 * fully synchronous (UI depends on it immediately).
 *
 * SECURITY-CRITICAL — NO SILENT TAMPER BYPASS: the startup gate (MainActivity.enforceRuntimeSecurityGate)
 * MUST NOT read getReport() while this flag is ON, because getReport() returns the default ALLOW report
 * until the async evaluation has finished. Instead the gate calls the @Synchronized, idempotent
 * RuntimeSecurityManager.initialize(): if the async task is still running the main thread blocks on the
 * same monitor until it completes and observes the REAL report; if it already finished this is a cheap
 * read; if it never started the gate performs the evaluation itself. Every path returns the genuine
 * verdict before startup proceeds — the gate still fails closed.
 *
 * NO-DOWNGRADE: default OFF executes the EXACT shipped serial path (byte-identical). When ON the security
 * verdict is identical (the gate still awaits it before proceeding); only WHERE/WHEN the work runs moves
 * off the main thread. Kept default OFF because it is device-startup-timing dependent and must be
 * validated on-device against a tampered/rooted build. Read once during onCreate (next cold start):
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.async_security_init true
 */
object AsyncSecurityInitFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
