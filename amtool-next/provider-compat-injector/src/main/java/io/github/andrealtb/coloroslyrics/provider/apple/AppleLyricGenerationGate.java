package io.github.andrealtb.coloroslyrics.provider.apple;

/**
 * Pure generation/async gate for the Apple lyric pipeline.
 *
 * All delayed work carries (generation, epoch). A track transition atomically invalidates every
 * previous request, retry, playback-item poll and publication. This class deliberately knows
 * nothing about MediaSession, Xposed or Apple model objects, so the lifecycle can be tested as a
 * state machine instead of inferred from hook ordering.
 */
final class AppleLyricGenerationGate {
    enum Phase {
        EMPTY,
        REQUESTING,
        READY,
        NO_LYRICS
    }

    enum TimeoutAction {
        IGNORE,
        RETRY,
        NO_LYRICS
    }

    static final class Ticket {
        final long generation;
        final long epoch;

        Ticket(long generation, long epoch) {
            this.generation = generation;
            this.epoch = epoch;
        }
    }

    private long generation = Long.MIN_VALUE;
    private long epoch;
    private int requestAttempts;
    private int playbackPolls;
    private Phase phase = Phase.EMPTY;

    synchronized boolean bindGeneration(long nextGeneration) {
        if (generation == nextGeneration) return false;
        generation = nextGeneration;
        epoch++;
        requestAttempts = 0;
        playbackPolls = 0;
        phase = Phase.EMPTY;
        return true;
    }

    synchronized Ticket ticket() {
        return new Ticket(generation, epoch);
    }

    /**
     * Invalidates every delayed request/callback without changing the canonical track generation.
     * Used when the Apple Music task is explicitly removed from Recents while the playback service
     * and MediaSession may still stay alive.
     */
    synchronized void invalidateCurrent() {
        epoch++;
        requestAttempts = 0;
        playbackPolls = 0;
        phase = Phase.EMPTY;
    }

    synchronized boolean accepts(Ticket ticket) {
        return ticket != null &&
                ticket.generation == generation &&
                ticket.epoch == epoch;
    }

    synchronized boolean beginProviderRequest() {
        if (phase == Phase.READY || phase == Phase.NO_LYRICS || phase == Phase.REQUESTING) {
            return false;
        }
        phase = Phase.REQUESTING;
        requestAttempts++;
        return true;
    }

    synchronized void markHostRequestInFlight() {
        if (phase == Phase.READY || phase == Phase.NO_LYRICS) return;
        phase = Phase.REQUESTING;
        if (requestAttempts == 0) requestAttempts = 1;
    }

    synchronized TimeoutAction onTimeout(Ticket ticket, int maxAttempts) {
        if (!accepts(ticket) || phase == Phase.READY || phase == Phase.NO_LYRICS) {
            return TimeoutAction.IGNORE;
        }
        if (requestAttempts >= maxAttempts) {
            phase = Phase.NO_LYRICS;
            return TimeoutAction.NO_LYRICS;
        }
        phase = Phase.EMPTY;
        return TimeoutAction.RETRY;
    }

    synchronized boolean markReady(Ticket ticket) {
        if (!accepts(ticket)) return false;
        phase = Phase.READY;
        return true;
    }

    synchronized boolean markNoLyrics(Ticket ticket) {
        if (!accepts(ticket)) return false;
        phase = Phase.NO_LYRICS;
        return true;
    }

    synchronized int nextPlaybackPoll(Ticket ticket, int maxPolls) {
        if (!accepts(ticket) || playbackPolls >= maxPolls) return -1;
        playbackPolls++;
        return playbackPolls;
    }

    synchronized void playbackItemFound(Ticket ticket) {
        if (!accepts(ticket)) return;
        playbackPolls = 0;
        if (phase != Phase.READY && phase != Phase.NO_LYRICS) {
            phase = Phase.EMPTY;
        }
    }

    synchronized Phase phase() {
        return phase;
    }

    synchronized int attempts() {
        return requestAttempts;
    }

    synchronized long generation() {
        return generation;
    }
}
