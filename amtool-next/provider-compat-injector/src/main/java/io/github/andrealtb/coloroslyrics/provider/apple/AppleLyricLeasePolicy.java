package io.github.andrealtb.coloroslyrics.provider.apple;

/**
 * Pure rolling-lease window selection. No Android/Xposed dependency, so lifecycle behaviour can be
 * unit-tested independently from hook ordering.
 */
final class AppleLyricLeasePolicy {
    static final class Window {
        final int first;
        final int last;

        Window(int first, int last) {
            this.first = first;
            this.last = last;
        }
    }

    private AppleLyricLeasePolicy() {}

    static Window select(
            long[] begins,
            long[] ends,
            long positionMs,
            long pastMs,
            long futureMs
    ) {
        if (begins == null || ends == null || begins.length == 0 || begins.length != ends.length) {
            return null;
        }

        long start = Math.max(0L, positionMs - Math.max(0L, pastMs));
        long stop = positionMs + Math.max(0L, futureMs);
        int first = -1;
        int last = -1;

        for (int i = 0; i < begins.length; i++) {
            long begin = Math.max(0L, begins[i]);
            long end = ends[i] > 0L ? Math.max(begin, ends[i]) : begin;
            if (end >= start && begin <= stop) {
                if (first < 0) first = i;
                last = i;
            }
        }

        if (first >= 0) return new Window(first, last);

        // Long instrumental gap: pre-arm only the nearest line if it falls inside the lease.
        long nearest = Long.MAX_VALUE;
        int nearestIndex = -1;
        for (int i = 0; i < begins.length; i++) {
            long begin = Math.max(0L, begins[i]);
            if (begin >= positionMs && begin < nearest) {
                nearest = begin;
                nearestIndex = i;
            }
        }
        if (nearestIndex >= 0 && nearest <= stop) {
            return new Window(nearestIndex, nearestIndex);
        }
        return null;
    }
}
