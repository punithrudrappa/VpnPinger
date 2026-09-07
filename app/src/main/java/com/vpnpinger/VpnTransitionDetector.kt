package com.vpnpinger

/**
 * Classifies VPN connection changes from successive snapshots of the connected
 * VPN network identities.
 *
 * Why identity and not a boolean "VPN up/down":
 * A server switch (e.g. in ProtonVPN) can bring the new tunnel up while the old one
 * is still being torn down, so a VPN network is present the whole time and a boolean
 * comparison would never observe a change. Comparing *which* VPN network instances
 * exist catches that case.
 *
 * This class is intentionally pure JVM (no Android imports) so it can be unit-tested
 * on the host. In production the element type [T] is [android.net.Network] (whose
 * equals/hashCode are based on the network id); in tests it can be a plain String.
 *
 * All calls are expected from a single thread (the service serializes network
 * callbacks and the safety-net poll on its main-thread handler).
 */
class VpnTransitionDetector<T>(
    /** Same-kind transitions closer than this are collapsed into one change. */
    private val coalesceWindowMs: Long,
    /** Time source; use a monotonic clock (e.g. SystemClock.elapsedRealtime()). */
    private val nowMs: () -> Long,
) {

    enum class Change { CONNECT, DISCONNECT, CHANGE }

    private var lastNetworks: List<T>? = null
    private var lastChangeKind: Change? = null
    private var lastChangeAtMs: Long = 0L

    /** Whether a VPN network was present at the most recent snapshot. */
    val isUp: Boolean
        get() = !lastNetworks.isNullOrEmpty()

    /**
     * Feed the current snapshot of connected VPN network identities.
     *
     * @return the [Change] that happened since the previous snapshot, or `null` when
     *   nothing changed, when this is the first (baseline) snapshot, or when the
     *   change was coalesced away as rapid same-kind churn.
     */
    fun onSnapshot(current: List<T>): Change? {
        val previous = lastNetworks
        lastNetworks = current

        // First snapshot: only establishes the baseline, never reports a change.
        if (previous == null) return null

        // Nothing actually changed (identical networks, possibly re-parceled).
        if (previous.size == current.size && previous.containsAll(current)) return null

        val wasUp = previous.isNotEmpty()
        val isUp = current.isNotEmpty()
        val change = when {
            !wasUp && isUp -> Change.CONNECT
            wasUp && !isUp -> Change.DISCONNECT
            else -> Change.CHANGE
        }

        // An overlapping server switch is observed as A -> A+B -> B: several same-kind
        // snapshots within the window. Collapse them so one user-visible server change
        // produces one trigger, but keep every genuine cross-kind transition.
        val now = nowMs()
        if (lastChangeKind == change && now - lastChangeAtMs < coalesceWindowMs) {
            lastChangeAtMs = now
            return null
        }

        lastChangeKind = change
        lastChangeAtMs = now
        return change
    }
}
