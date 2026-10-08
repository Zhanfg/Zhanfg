package io.github.andrealtb.coloroslyrics.provider.apple;

import android.media.MediaMetadata;
import android.os.Bundle;

import java.lang.reflect.Field;

/** Low-level metadata mutation utility used only by ProviderRuntimeV3. */
final class TrackTransitionLyricGuard {
    private static final String KEY = "lyricInfo";
    private static final String PROVIDER =
            "\"provider\":\"com.apple.android.music\"";
    private static final String SOURCE =
            "\"source\":\"com.apple.android.music-v5\"";

    private TrackTransitionLyricGuard() {}

    static boolean clearOwnedLyricInfo(MediaMetadata metadata) {
        if (metadata == null) return false;
        String payload = metadata.getString(KEY);
        if (!isOwned(payload)) return false;
        try {
            Field bundleField = null;
            for (Field field : MediaMetadata.class.getDeclaredFields()) {
                if (Bundle.class.isAssignableFrom(field.getType())) {
                    bundleField = field;
                    if ("mBundle".equals(field.getName())) break;
                }
            }
            if (bundleField == null) return false;
            bundleField.setAccessible(true);
            Bundle bundle = (Bundle) bundleField.get(metadata);
            if (bundle == null) return false;
            String existing = bundle.getString(KEY);
            if (!isOwned(existing)) return false;
            // ColorOS' lyric consumer treats an explicit empty lyricInfo as a retraction.
            // Removing the key can leave the previously parsed provider payload alive in SystemUI
            // until another metadata event arrives.
            bundle.putCharSequence(KEY, "");
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isOwned(String payload) {
        return payload != null &&
                payload.contains(PROVIDER) &&
                payload.contains(SOURCE);
    }
}
