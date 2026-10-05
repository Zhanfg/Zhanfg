package io.github.andrealtb.coloroslyrics.provider.apple;

import static org.junit.Assert.*;

import org.junit.Test;

public class AppleLyricGenerationGateTest {
    @Test
    public void trackChangeInvalidatesOldTicketAndResetsState() {
        AppleLyricGenerationGate gate = new AppleLyricGenerationGate();
        assertTrue(gate.bindGeneration(1L));
        AppleLyricGenerationGate.Ticket first = gate.ticket();
        assertTrue(gate.beginProviderRequest());
        assertEquals(AppleLyricGenerationGate.Phase.REQUESTING, gate.phase());

        assertTrue(gate.bindGeneration(2L));
        assertFalse(gate.accepts(first));
        assertEquals(AppleLyricGenerationGate.Phase.EMPTY, gate.phase());
        assertEquals(0, gate.attempts());
    }

    @Test
    public void emptyResultBecomesTerminalNoLyricsForCurrentGeneration() {
        AppleLyricGenerationGate gate = new AppleLyricGenerationGate();
        gate.bindGeneration(7L);
        AppleLyricGenerationGate.Ticket ticket = gate.ticket();
        assertTrue(gate.beginProviderRequest());
        assertTrue(gate.markNoLyrics(ticket));
        assertEquals(AppleLyricGenerationGate.Phase.NO_LYRICS, gate.phase());
        assertFalse(gate.beginProviderRequest());
    }

    @Test
    public void lateOldResultCannotOverwriteNewTrack() {
        AppleLyricGenerationGate gate = new AppleLyricGenerationGate();
        gate.bindGeneration(10L);
        AppleLyricGenerationGate.Ticket old = gate.ticket();
        gate.beginProviderRequest();

        gate.bindGeneration(11L);
        assertFalse(gate.markReady(old));
        assertEquals(AppleLyricGenerationGate.Phase.EMPTY, gate.phase());
    }

    @Test
    public void retryBudgetEndsInNoLyrics() {
        AppleLyricGenerationGate gate = new AppleLyricGenerationGate();
        gate.bindGeneration(3L);

        AppleLyricGenerationGate.Ticket one = gate.ticket();
        gate.beginProviderRequest();
        assertEquals(
                AppleLyricGenerationGate.TimeoutAction.RETRY,
                gate.onTimeout(one, 3)
        );

        AppleLyricGenerationGate.Ticket two = gate.ticket();
        gate.beginProviderRequest();
        assertEquals(
                AppleLyricGenerationGate.TimeoutAction.RETRY,
                gate.onTimeout(two, 3)
        );

        AppleLyricGenerationGate.Ticket three = gate.ticket();
        gate.beginProviderRequest();
        assertEquals(
                AppleLyricGenerationGate.TimeoutAction.NO_LYRICS,
                gate.onTimeout(three, 3)
        );
        assertEquals(AppleLyricGenerationGate.Phase.NO_LYRICS, gate.phase());
    }

    @Test
    public void playbackPollsAreGenerationBound() {
        AppleLyricGenerationGate gate = new AppleLyricGenerationGate();
        gate.bindGeneration(1L);
        AppleLyricGenerationGate.Ticket first = gate.ticket();
        assertEquals(1, gate.nextPlaybackPoll(first, 2));
        assertEquals(2, gate.nextPlaybackPoll(first, 2));
        assertEquals(-1, gate.nextPlaybackPoll(first, 2));

        gate.bindGeneration(2L);
        AppleLyricGenerationGate.Ticket second = gate.ticket();
        assertEquals(1, gate.nextPlaybackPoll(second, 2));
        assertEquals(-1, gate.nextPlaybackPoll(first, 2));
    }
    @Test
    public void explicitTaskRemovalInvalidatesSameGenerationCallbacks() {
        AppleLyricGenerationGate gate = new AppleLyricGenerationGate();
        gate.bindGeneration(42L);
        AppleLyricGenerationGate.Ticket beforeRemoval = gate.ticket();
        assertTrue(gate.beginProviderRequest());

        gate.invalidateCurrent();

        assertFalse(gate.accepts(beforeRemoval));
        assertEquals(42L, gate.generation());
        assertEquals(AppleLyricGenerationGate.Phase.EMPTY, gate.phase());
        assertEquals(0, gate.attempts());

        AppleLyricGenerationGate.Ticket afterReopen = gate.ticket();
        assertTrue(gate.accepts(afterReopen));
        assertTrue(gate.beginProviderRequest());
    }

}
