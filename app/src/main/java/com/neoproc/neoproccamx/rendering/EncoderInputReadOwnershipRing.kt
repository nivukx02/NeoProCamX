package com.neoproc.neoproccamx.rendering

/**
 * Fixed-capacity ownership ledger for full-resolution RAW transports accepted by recorder EGL.
 *
 * The caller serializes every operation with the RAW demosaic completion lock. Slots are allocated
 * once so the irreversible MediaCodec acceptance edge only mutates existing storage: no queue node or
 * ownership record needs to be allocated after EGL has accepted a frame.
 */
internal class EncoderInputReadOwnershipRing(
    capacity: Int = CAPACITY,
    initialReservationSequence: Long = 1L
) {
    internal enum class State {
        FREE,
        RESERVED,
        COMMITTED
    }

    internal class Slot internal constructor(
        internal val index: Int
    ) {
        internal var state: State = State.FREE
            private set
        internal var frame: RawDemosaicFrameIdentity? = null
            private set
        internal var reservationSequence: Long = 0L
            private set
        internal var vulkanToken: Long = 0L
            private set
        internal var completionSerial: Long = 0L
            private set
        internal var eglReceipt: Long = 0L
            private set
        internal var submittedPresentationTimeNs: Long = 0L
            private set
        internal var fenceSignaled: Boolean = false
            private set
        internal var receiptAcknowledged: Boolean = false
            private set
        internal var gpuCompletionRecorded: Boolean = false
            private set

        internal fun reserve(
            identity: RawDemosaicFrameIdentity,
            sequence: Long
        ) {
            check(state == State.FREE)
            state = State.RESERVED
            frame = identity
            reservationSequence = sequence
        }

        internal fun bindTransport(token: Long, serial: Long): Boolean {
            if (state != State.RESERVED || token <= 0L || serial <= 0L ||
                vulkanToken != 0L || completionSerial != 0L
            ) return false
            vulkanToken = token
            completionSerial = serial
            return true
        }

        internal fun commit(receipt: Long, presentationTimeNs: Long): Boolean {
            if (state != State.RESERVED || receipt <= 0L || presentationTimeNs <= 0L) return false
            eglReceipt = receipt
            submittedPresentationTimeNs = presentationTimeNs
            state = State.COMMITTED
            return vulkanToken > 0L && completionSerial > 0L && frame != null
        }

        internal fun clear() {
            state = State.FREE
            frame = null
            reservationSequence = 0L
            vulkanToken = 0L
            completionSerial = 0L
            eglReceipt = 0L
            submittedPresentationTimeNs = 0L
            fenceSignaled = false
            receiptAcknowledged = false
            gpuCompletionRecorded = false
        }

        internal fun markFenceSignaled(): Boolean {
            if (state != State.COMMITTED) return false
            fenceSignaled = true
            return true
        }

        internal fun markReceiptAcknowledged(): Boolean {
            if (state != State.COMMITTED || !fenceSignaled) return false
            receiptAcknowledged = true
            return true
        }

        internal fun markGpuCompletionRecorded(): Boolean {
            if (state != State.COMMITTED || !fenceSignaled) return false
            gpuCompletionRecorded = true
            return true
        }
    }

    private val slots = Array(capacity) { Slot(it) }
    private var nextReservationSequence = initialReservationSequence
    private var quarantinedReason: String? = null

    init {
        require(capacity == CAPACITY) {
            "Full-resolution RAW transport ownership must match the native two-lane ring"
        }
        require(initialReservationSequence > 0L)
    }

    internal val outstandingCount: Int
        get() = slots.count { it.state != State.FREE }

    internal val committedCount: Int
        get() = slots.count { it.state == State.COMMITTED }

    internal val isQuarantined: Boolean
        get() = quarantinedReason != null

    internal fun quarantineReason(): String? = quarantinedReason

    internal fun quarantine(reason: String): String {
        if (quarantinedReason == null) quarantinedReason = reason
        return requireNotNull(quarantinedReason)
    }

    internal fun hasCapacity(): Boolean = !isQuarantined && outstandingCount < slots.size

    internal fun reserve(frame: RawDemosaicFrameIdentity): Slot? {
        if (isQuarantined || owns(frame)) return null
        val slot = slots.firstOrNull { it.state == State.FREE } ?: return null
        val sequence = allocateReservationSequence() ?: return null
        slot.reserve(frame, sequence)
        return slot
    }

    internal fun bindTransport(
        slot: Slot,
        vulkanToken: Long,
        completionSerial: Long
    ): Boolean {
        if (!ownsSlot(slot) || slots.any {
                it !== slot && it.state != State.FREE &&
                    (it.vulkanToken == vulkanToken || it.completionSerial == completionSerial)
            }
        ) return false
        return slot.bindTransport(vulkanToken, completionSerial)
    }

    /** The only mutation required immediately after irreversible EGL/MediaCodec acceptance. */
    internal fun commitAccepted(
        slot: Slot,
        eglReceipt: Long,
        submittedPresentationTimeNs: Long
    ): Boolean = ownsSlot(slot) && slot.commit(eglReceipt, submittedPresentationTimeNs)

    internal fun isReadyForAcceptance(slot: Slot): Boolean =
        ownsSlot(slot) && !isQuarantined && slot.state == State.RESERVED &&
            slot.frame != null && slot.vulkanToken > 0L && slot.completionSerial > 0L

    internal fun cancelReservation(slot: Slot): Boolean {
        if (!ownsSlot(slot) || slot.state != State.RESERVED) return false
        slot.clear()
        return true
    }

    /** FIFO head; later fences never release a newer Vulkan lane ahead of an older accepted frame. */
    internal fun oldestCommitted(): Slot? {
        var oldest: Slot? = null
        for (slot in slots) {
            if (slot.state != State.COMMITTED) continue
            if (oldest == null || slot.reservationSequence < oldest.reservationSequence) {
                oldest = slot
            }
        }
        return oldest
    }

    internal fun markFenceSignaled(slot: Slot): Boolean =
        ownsSlot(slot) && slot.markFenceSignaled()

    internal fun markReceiptAcknowledged(slot: Slot): Boolean =
        ownsSlot(slot) && slot.markReceiptAcknowledged()

    internal fun markGpuCompletionRecorded(slot: Slot): Boolean =
        ownsSlot(slot) && slot.markGpuCompletionRecorded()

    internal fun owns(frame: RawDemosaicFrameIdentity): Boolean =
        slots.any { it.state != State.FREE && it.frame == frame }

    internal fun ownsCommitted(frame: RawDemosaicFrameIdentity, receipt: Long): Boolean =
        slots.any {
            it.state == State.COMMITTED && it.frame == frame && it.eglReceipt == receipt
        }

    /** Clears only the exact FIFO head after all three external-retirement proofs are durable. */
    internal fun retire(slot: Slot): Boolean {
        if (isQuarantined || !ownsSlot(slot) || oldestCommitted() !== slot ||
            !slot.fenceSignaled || !slot.receiptAcknowledged ||
            !slot.gpuCompletionRecorded
        ) return false
        slot.clear()
        return true
    }

    private fun ownsSlot(slot: Slot): Boolean =
        slot.index in slots.indices && slots[slot.index] === slot

    private fun allocateReservationSequence(): Long? {
        if (nextReservationSequence <= 0L) {
            // Never wrap underneath a still-owned slot: numeric FIFO order must remain total.
            if (outstandingCount != 0) return null
            nextReservationSequence = 1L
        }
        val allocated = nextReservationSequence
        nextReservationSequence = if (allocated == Long.MAX_VALUE) 0L else allocated + 1L
        return allocated
    }

    internal companion object {
        const val CAPACITY = 2
    }
}
