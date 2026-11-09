package com.neoproc.neoproccamx.niv99

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class Niv99GpuState {
    NEW,
    READY,
    DRAINING,
    FAILED,
    QUARANTINED,
    CLOSED
}

enum class Niv99GpuSubmitStatus {
    ACCEPTED,
    BACKPRESSURE,
    STALE_GENERATION,
    INVALID_ARGUMENT,
    UNSUPPORTED,
    FAILED,
    CLOSED
}

enum class Niv99GpuCompletionStatus {
    IDLE,
    PENDING,
    COMPLETED,
    FAILED,
    STALE_GENERATION
}

class Niv99GpuFrameToken {
    val identity = Niv99FrameIdentity()
    var generation: Long = -1L
    var inputHandle: Long = 0L
    var outputHandle: Long = 0L
    var waitTimelineValue: Long = 0L
    var signalTimelineValue: Long = 0L
    var slot: Int = -1
    var active: Boolean = false

    fun clear() {
        identity.set("", -1L, -1L, -1L)
        generation = -1L
        inputHandle = 0L
        outputHandle = 0L
        waitTimelineValue = 0L
        signalTimelineValue = 0L
        slot = -1
        active = false
    }
}

class Niv99GpuDispatchParameters(maxTiles: Int) {
    val maximumTiles: Int = maxTiles.coerceAtLeast(1)
    val identity = Niv99FrameIdentity()
    val whiteBalanceMatrix = FloatArray(9)
    val rawStrength = FloatArray(maximumTiles)
    val lumaStrength = FloatArray(maximumTiles)
    val chromaStrength = FloatArray(maximumTiles)
    val temporalAdmission = FloatArray(maximumTiles)
    val edgeProtection = FloatArray(maximumTiles)
    val textureProtection = FloatArray(maximumTiles)
    val sharpening = FloatArray(maximumTiles)
    var tileCount: Int = 0
    var width: Int = 0
    var height: Int = 0
    var bitDepth: Int = 10
    var inputDomain: Niv99SignalDomain = Niv99SignalDomain.LINEAR_RGB

    init {
        Niv99Math.setIdentity3(whiteBalanceMatrix)
    }
}

interface Niv99GpuBackend {
    fun initialize(capabilities: Niv99HardwareCapabilities, maximumInFlight: Int): Boolean

    fun submit(token: Niv99GpuFrameToken, parameters: Niv99GpuDispatchParameters): Boolean

    fun poll(token: Niv99GpuFrameToken): Niv99GpuCompletionStatus

    /** Returns true only after the backend has rejected future work for [generation]. */
    fun cancelGeneration(generation: Long): Boolean

    /** Returns true only when every native/GPU resource is retired and no retry is required. */
    fun closeAcknowledged(): Boolean
}

/** Fixed-slot GPU dispatcher. It never waits for device idle on submit and never grows its queue. */
class Niv99GpuPipeline(
    private val backend: Niv99GpuBackend,
    maximumInFlight: Int = DEFAULT_MAXIMUM_IN_FLIGHT
) : AutoCloseable {
    private val slotCount = maximumInFlight.coerceIn(1, MAXIMUM_IN_FLIGHT_LIMIT)
    private val tokens = Array(slotCount) { Niv99GpuFrameToken() }
    private val generation = AtomicLong(0L)
    private val timelineValue = AtomicLong(0L)
    private val accepting = AtomicBoolean(false)
    private var activationPermit: Niv99RuntimeActivationPermit? = null
    private var backendOwnershipAcquired = false
    private var backendInitialized = false
    private var closeCancellationAcknowledged = false
    @Volatile private var state: Niv99GpuState = Niv99GpuState.NEW
    @Volatile private var failureReason: String = ""

    @Synchronized
    fun initialize(
        capabilities: Niv99HardwareCapabilities,
        permit: Niv99RuntimeActivationPermit
    ): Boolean {
        if (state != Niv99GpuState.NEW) {
            return state == Niv99GpuState.READY && activationPermit === permit
        }
        if (!permit.claim()) {
            fail("runtime activation permit is invalid, revoked, or already claimed")
            return false
        }
        activationPermit = permit
        val capability = Niv99CapabilityDecision()
        Niv99CapabilityResolver.resolve(
            capabilities = capabilities,
            width = permit.identity.width,
            height = permit.identity.height,
            signalDomain = permit.identity.signalDomain,
            output = capability
        )
        if (capability.status != Niv99CapabilityStatus.SUPPORTED_ZERO_COPY &&
            capability.status != Niv99CapabilityStatus.SUPPORTED_BOUNDED_COPY
        ) {
            permit.revoke()
            activationPermit = null
            fail("runtime capabilities no longer satisfy the activation permit")
            return false
        }
        backendOwnershipAcquired = true
        if (!backend.initialize(capabilities, slotCount)) {
            accepting.set(false)
            if (backend.closeAcknowledged()) {
                backendOwnershipAcquired = false
                permit.revoke()
                activationPermit = null
                fail("backend initialization failed")
            } else {
                failureReason = "backend initialization failed and resource retirement is unproven"
                state = Niv99GpuState.QUARANTINED
            }
            return false
        }
        backendInitialized = true
        state = Niv99GpuState.READY
        accepting.set(true)
        return true
    }

    @Synchronized
    fun submit(
        context: Niv99FrameContext,
        inputHandle: Long,
        outputHandle: Long,
        parameters: Niv99GpuDispatchParameters,
        outputToken: Niv99GpuFrameToken
    ): Niv99GpuSubmitStatus {
        if (state == Niv99GpuState.CLOSED) return Niv99GpuSubmitStatus.CLOSED
        if (state == Niv99GpuState.QUARANTINED) return Niv99GpuSubmitStatus.FAILED
        if (state == Niv99GpuState.FAILED) return Niv99GpuSubmitStatus.FAILED
        if (state != Niv99GpuState.READY || !accepting.get()) return Niv99GpuSubmitStatus.UNSUPPORTED
        val permit = activationPermit ?: return Niv99GpuSubmitStatus.UNSUPPORTED
        if (!permit.matches(context)) return Niv99GpuSubmitStatus.STALE_GENERATION
        if (!validDispatch(context, inputHandle, outputHandle, parameters, outputToken)) {
            return Niv99GpuSubmitStatus.INVALID_ARGUMENT
        }
        if (!retireCompletedLocked()) return Niv99GpuSubmitStatus.FAILED
        var slot = -1
        for (index in tokens.indices) {
            if (!tokens[index].active) {
                slot = index
                break
            }
        }
        if (slot < 0) return Niv99GpuSubmitStatus.BACKPRESSURE
        val token = tokens[slot]
        val activeGeneration = generation.get()
        token.identity.copyFrom(context.identity)
        token.generation = activeGeneration
        token.inputHandle = inputHandle
        token.outputHandle = outputHandle
        val previousTimelineValue = timelineValue.get()
        if (previousTimelineValue == Long.MAX_VALUE) {
            token.clear()
            fail("timeline value exhausted")
            return Niv99GpuSubmitStatus.FAILED
        }
        token.waitTimelineValue = previousTimelineValue
        token.signalTimelineValue = timelineValue.incrementAndGet()
        token.slot = slot
        token.active = true
        if (!backend.submit(token, parameters)) {
            token.clear()
            fail("backend submission failed")
            return Niv99GpuSubmitStatus.FAILED
        }
        copyToken(token, outputToken)
        return Niv99GpuSubmitStatus.ACCEPTED
    }

    @Synchronized
    fun poll(token: Niv99GpuFrameToken): Niv99GpuCompletionStatus {
        if (!token.active || token.slot !in tokens.indices) return Niv99GpuCompletionStatus.IDLE
        val owned = tokens[token.slot]
        if (!owned.active || owned.signalTimelineValue != token.signalTimelineValue) {
            return Niv99GpuCompletionStatus.STALE_GENERATION
        }
        if (owned.generation != generation.get()) return Niv99GpuCompletionStatus.STALE_GENERATION
        val completion = backend.poll(owned)
        when (completion) {
            Niv99GpuCompletionStatus.COMPLETED -> {
                owned.clear()
                token.clear()
            }
            Niv99GpuCompletionStatus.FAILED,
            Niv99GpuCompletionStatus.STALE_GENERATION,
            Niv99GpuCompletionStatus.IDLE -> {
                owned.clear()
                token.clear()
                fail("backend returned an invalid completion for an active slot")
            }
            Niv99GpuCompletionStatus.PENDING -> Unit
        }
        return completion
    }

    @Synchronized
    fun cancelAndAdvanceGeneration(): Long {
        if (state == Niv99GpuState.CLOSED || state == Niv99GpuState.QUARANTINED) {
            return generation.get()
        }
        accepting.set(false)
        val retiredGeneration = generation.get()
        if (retiredGeneration == Long.MAX_VALUE) {
            fail("generation sequence exhausted")
            return retiredGeneration
        }
        if (!backend.cancelGeneration(retiredGeneration)) {
            failureReason = "backend did not acknowledge generation cancellation"
            state = Niv99GpuState.QUARANTINED
            return retiredGeneration
        }
        generation.incrementAndGet()
        for (token in tokens) token.clear()
        if (state == Niv99GpuState.READY) accepting.set(true)
        return generation.get()
    }

    @Synchronized
    fun beginDrain() {
        if (state != Niv99GpuState.READY) return
        accepting.set(false)
        state = Niv99GpuState.DRAINING
    }

    @Synchronized
    fun pollDrainComplete(): Boolean {
        if (!retireCompletedLocked()) return false
        for (token in tokens) if (token.active) return false
        return true
    }

    fun currentState(): Niv99GpuState = state

    fun failureReason(): String = failureReason

    @Synchronized
    fun isActivatedFor(target: Niv99ActivationTarget): Boolean =
        state == Niv99GpuState.READY && activationPermit?.target == target

    @Synchronized
    fun closeWithRetirementProof(): Boolean {
        if (state == Niv99GpuState.CLOSED) return true
        accepting.set(false)
        if (!backendOwnershipAcquired) {
            activationPermit?.revoke()
            activationPermit = null
            state = Niv99GpuState.CLOSED
            return true
        }
        if (backendInitialized && !closeCancellationAcknowledged) {
            val activeGeneration = generation.get()
            if (activeGeneration == Long.MAX_VALUE || !backend.cancelGeneration(activeGeneration)) {
                failureReason = "backend did not acknowledge cancellation during close"
                state = Niv99GpuState.QUARANTINED
                return false
            }
            generation.incrementAndGet()
            closeCancellationAcknowledged = true
        }
        if (!backend.closeAcknowledged()) {
            failureReason = "backend resource retirement is unproven"
            state = Niv99GpuState.QUARANTINED
            return false
        }
        for (token in tokens) token.clear()
        backendOwnershipAcquired = false
        backendInitialized = false
        activationPermit?.revoke()
        activationPermit = null
        state = Niv99GpuState.CLOSED
        return true
    }

    override fun close() {
        closeWithRetirementProof()
    }

    private fun retireCompletedLocked(): Boolean {
        for (token in tokens) {
            if (!token.active) continue
            when (backend.poll(token)) {
                Niv99GpuCompletionStatus.COMPLETED -> token.clear()
                Niv99GpuCompletionStatus.FAILED,
                Niv99GpuCompletionStatus.STALE_GENERATION,
                Niv99GpuCompletionStatus.IDLE -> {
                    token.clear()
                    fail("backend returned an invalid completion while retiring a slot")
                    return false
                }
                Niv99GpuCompletionStatus.PENDING -> Unit
            }
        }
        return true
    }

    private fun copyToken(source: Niv99GpuFrameToken, destination: Niv99GpuFrameToken) {
        destination.identity.copyFrom(source.identity)
        destination.generation = source.generation
        destination.inputHandle = source.inputHandle
        destination.outputHandle = source.outputHandle
        destination.waitTimelineValue = source.waitTimelineValue
        destination.signalTimelineValue = source.signalTimelineValue
        destination.slot = source.slot
        destination.active = source.active
    }

    private fun validDispatch(
        context: Niv99FrameContext,
        inputHandle: Long,
        outputHandle: Long,
        parameters: Niv99GpuDispatchParameters,
        outputToken: Niv99GpuFrameToken
    ): Boolean {
        if (context.validate() != Niv99ValidationResult.VALID ||
            inputHandle == 0L || outputHandle == 0L || inputHandle == outputHandle ||
            outputToken.active ||
            !Niv99ShaderAbi.isSameFrame(parameters.identity, context.identity) ||
            parameters.width != context.width || parameters.height != context.height ||
            parameters.inputDomain != context.signalDomain ||
            parameters.bitDepth !in MINIMUM_PROCESSING_BIT_DEPTH..MAXIMUM_PROCESSING_BIT_DEPTH ||
            parameters.tileCount !in 1..parameters.maximumTiles ||
            !allFinite(parameters.whiteBalanceMatrix, parameters.whiteBalanceMatrix.size)
        ) {
            return false
        }
        return allUnitInterval(parameters.rawStrength, parameters.tileCount) &&
            allUnitInterval(parameters.lumaStrength, parameters.tileCount) &&
            allUnitInterval(parameters.chromaStrength, parameters.tileCount) &&
            allUnitInterval(parameters.temporalAdmission, parameters.tileCount) &&
            allUnitInterval(parameters.edgeProtection, parameters.tileCount) &&
            allUnitInterval(parameters.textureProtection, parameters.tileCount) &&
            allUnitInterval(parameters.sharpening, parameters.tileCount)
    }

    private fun allFinite(values: FloatArray, count: Int): Boolean {
        for (index in 0 until count) if (!values[index].isFinite()) return false
        return true
    }

    private fun allUnitInterval(values: FloatArray, count: Int): Boolean {
        for (index in 0 until count) {
            val value = values[index]
            if (!value.isFinite() || value < 0f || value > 1f) return false
        }
        return true
    }

    private fun fail(reason: String) {
        failureReason = reason
        accepting.set(false)
        state = Niv99GpuState.FAILED
    }

    companion object {
        private const val DEFAULT_MAXIMUM_IN_FLIGHT = 3
        private const val MAXIMUM_IN_FLIGHT_LIMIT = 8
        private const val MINIMUM_PROCESSING_BIT_DEPTH = 10
        private const val MAXIMUM_PROCESSING_BIT_DEPTH = 32
    }
}
