package com.vpnpinger

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure-JVM detection state machine.
 *
 * Elements are plain strings standing in for VPN network identities (netIds).
 * A fake clock makes the coalescing-window behaviour deterministic.
 */
class VpnTransitionDetectorTest {

    private var nowMs = 0L

    private fun newDetector(coalesceMs: Long = 2_000L) =
        VpnTransitionDetector<String>(coalesceMs) { nowMs }

    // ---------------------------------------------------------------- baseline

    @Test
    fun `first snapshot establishes baseline and never fires`() {
        val detector = newDetector()
        assertNull(detector.onSnapshot(emptyList()))
        assertNull(detector.onSnapshot(emptyList()))
        assertFalse(detector.isUp)
    }

    @Test
    fun `baseline with VPN already connected never fires`() {
        val detector = newDetector()
        assertNull(detector.onSnapshot(listOf("vpn-a")))
        assertTrue(detector.isUp)
    }

    // ------------------------------------------------------------------ kinds

    @Test
    fun `vpn appearing after no vpn is a connect`() {
        val detector = newDetector()
        detector.onSnapshot(emptyList())
        assertEquals(
            VpnTransitionDetector.Change.CONNECT,
            detector.onSnapshot(listOf("vpn-a")),
        )
    }

    @Test
    fun `vpn disappearing is a disconnect`() {
        val detector = newDetector()
        detector.onSnapshot(listOf("vpn-a"))
        assertEquals(
            VpnTransitionDetector.Change.DISCONNECT,
            detector.onSnapshot(emptyList()),
        )
    }

    @Test
    fun `identical snapshot is not a change`() {
        val detector = newDetector()
        detector.onSnapshot(emptyList())
        detector.onSnapshot(listOf("vpn-a"))
        assertNull(detector.onSnapshot(listOf("vpn-a")))
    }

    @Test
    fun `vpn replaced while still up is a change`() {
        val detector = newDetector()
        detector.onSnapshot(listOf("vpn-a"))
        assertEquals(
            VpnTransitionDetector.Change.CHANGE,
            detector.onSnapshot(listOf("vpn-b")),
        )
        assertTrue(detector.isUp)
    }

    // -------------------------------------------------------------- coalescing

    @Test
    fun `overlapping server switch produces a single trigger`() {
        val detector = newDetector()
        detector.onSnapshot(listOf("vpn-a")) // baseline

        nowMs = 0
        // New tunnel comes up while the old one is still there.
        assertEquals(
            VpnTransitionDetector.Change.CHANGE,
            detector.onSnapshot(listOf("vpn-a", "vpn-b")),
        )

        nowMs = 1_000 // old tunnel torn down < 2 s later
        // Same kind within the coalescing window: swallowed.
        assertNull(detector.onSnapshot(listOf("vpn-b")))
    }

    @Test
    fun `cross-kind transition inside the window still fires`() {
        val detector = newDetector()
        detector.onSnapshot(emptyList()) // baseline

        nowMs = 0
        assertEquals(
            VpnTransitionDetector.Change.CONNECT,
            detector.onSnapshot(listOf("vpn-a")),
        )

        nowMs = 500 // genuine disconnect only half a second later
        assertEquals(
            VpnTransitionDetector.Change.DISCONNECT,
            detector.onSnapshot(emptyList()),
        )
    }

    @Test
    fun `same kind outside the coalescing window fires again`() {
        val detector = newDetector()
        detector.onSnapshot(listOf("vpn-a")) // baseline

        nowMs = 0
        assertEquals(
            VpnTransitionDetector.Change.CHANGE,
            detector.onSnapshot(listOf("vpn-a", "vpn-b")),
        )

        nowMs = 2_500 // beyond the 2 s window
        assertEquals(
            VpnTransitionDetector.Change.CHANGE,
            detector.onSnapshot(listOf("vpn-b")),
        )
    }

    @Test
    fun `zero-length coalescing window never collapses changes`() {
        val detector = newDetector(coalesceMs = 0)
        detector.onSnapshot(listOf("vpn-a")) // baseline
        assertEquals(
            VpnTransitionDetector.Change.CHANGE,
            detector.onSnapshot(listOf("vpn-a", "vpn-b")),
        )
        assertEquals(
            VpnTransitionDetector.Change.CHANGE,
            detector.onSnapshot(listOf("vpn-b")),
        )
    }
}
