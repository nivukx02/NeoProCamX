package com.neoproc.neoproccamx.security

enum class RuntimeSecurityStartupAction {
    ALLOW,
    WARN,
    BLOCK
}

data class RuntimeSecurityDecision(
    val startupAction: RuntimeSecurityStartupAction,
    val reasons: Set<String>
)

object RuntimeAppSecurityPolicy {
    fun evaluate(
        isDebugBuild: Boolean,
        tampered: Boolean,
        rooted: Boolean,
        emulator: Boolean,
        debuggerAttached: Boolean,
        blockOnTamper: Boolean,
        blockOnCompromisedDevice: Boolean
    ): RuntimeSecurityDecision {
        val reasons = linkedSetOf<String>()
        if (tampered) reasons += "tampered_apk"
        if (rooted) reasons += "rooted_device"
        if (emulator) reasons += "emulator_environment"
        if (debuggerAttached) reasons += "debugger_attached"

        val action = when {
            isDebugBuild -> RuntimeSecurityStartupAction.ALLOW
            tampered && blockOnTamper -> RuntimeSecurityStartupAction.BLOCK
            (rooted || emulator || debuggerAttached) && blockOnCompromisedDevice -> RuntimeSecurityStartupAction.BLOCK
            reasons.isNotEmpty() -> RuntimeSecurityStartupAction.WARN
            else -> RuntimeSecurityStartupAction.ALLOW
        }
        return RuntimeSecurityDecision(
            startupAction = action,
            reasons = reasons
        )
    }
}
