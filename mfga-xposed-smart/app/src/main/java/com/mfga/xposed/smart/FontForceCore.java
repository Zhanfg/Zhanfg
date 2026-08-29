package com.mfga.xposed.smart;

import android.graphics.Typeface;
import android.os.Build;

/** Shared replacement core. Keeps style/weight while routing ordinary app fonts back to Typeface.DEFAULT. */
public final class FontForceCore {
    private static final ThreadLocal<Boolean> IN_REPLACEMENT =
            ThreadLocal.withInitial(() -> Boolean.FALSE);

    private FontForceCore() {}

    public static boolean isReplacing() {
        return Boolean.TRUE.equals(IN_REPLACEMENT.get());
    }

    public static Typeface systemReplacementFor(Typeface original) {
        IN_REPLACEMENT.set(Boolean.TRUE);
        try {
            int style = original != null ? original.getStyle() : Typeface.NORMAL;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && original != null) {
                int weight = original.getWeight();
                if (weight > 0) {
                    return Typeface.create(Typeface.DEFAULT, weight, original.isItalic());
                }
            }
            return Typeface.create(Typeface.DEFAULT, style);
        } catch (Throwable ignored) {
            return Typeface.DEFAULT;
        } finally {
            IN_REPLACEMENT.set(Boolean.FALSE);
        }
    }
}
