package io.github.andrealtb.coloroslyrics.provider.apple;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.os.Bundle;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

final class TrackTransitionLyricGuard {
    private static final String TAG = "AppleProvider-AMToolCompat";
    private static final String KEY = "lyricInfo";
    private static final String PROVIDER =
            "\"provider\":\"com.apple.android.music\"";
    private static final String SOURCE =
            "\"source\":\"com.apple.android.music-v5\"";

    private final Object lock = new Object();
    private final WeakHashMap<MediaMetadata, String> seen = new WeakHashMap<>();
    private String currentTrackKey;

    void install(XposedModule module) throws Exception {
        Method setMetadata = MediaSession.class.getDeclaredMethod(
                "setMetadata", MediaMetadata.class);
        setMetadata.setAccessible(true);

        module.hook(setMetadata).intercept(chain -> {
            MediaMetadata metadata = (MediaMetadata) chain.getArg(0);
            if (metadata == null) return chain.proceed();

            String trackKey = trackKey(metadata);
            if (trackKey == null || trackKey.isEmpty()) return chain.proceed();

            String payload = metadata.getString(KEY);
            boolean owned = isOwned(payload);

            synchronized (lock) {
                String seenKey = seen.get(metadata);
                if (currentTrackKey == null) {
                    currentTrackKey = trackKey;
                    seen.put(metadata, trackKey);
                    return chain.proceed();
                }

                if (!trackKey.equals(currentTrackKey)) {
                    boolean staleReplay = seenKey != null && seenKey.equals(trackKey);
                    if (owned && clearInPlace(metadata)) {
                        module.log(
                                Log.INFO,
                                TAG,
                                staleReplay
                                    ? "dropped stale lyricInfo from old track"
                                    : "cleared previous-track lyricInfo on transition"
                        );
                    }
                    if (!staleReplay) {
                        currentTrackKey = trackKey;
                        seen.put(metadata, trackKey);
                    }
                    return chain.proceed();
                }

                seen.put(metadata, trackKey);
            }

            return chain.proceed();
        });

        module.log(Log.INFO, TAG, "track transition lyric guard installed");
    }

    private static String trackKey(MediaMetadata metadata) {
        String id = firstNonBlank(
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                metadata.getString("com.apple.android.music.metadata.MEDIA_ID")
        );
        if (id != null) return "id:" + id.trim();

        String title = firstNonBlank(
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        );
        String artist = firstNonBlank(
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        );
        String album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        if (title == null && artist == null && album == null && duration <= 0L) return null;

        return "meta:" + normalize(title) + "|" + normalize(artist) + "|" +
                normalize(album) + "|" + duration;
    }

    private static boolean isOwned(String payload) {
        return payload != null &&
                payload.contains(PROVIDER) &&
                payload.contains(SOURCE);
    }

    private static boolean clearInPlace(MediaMetadata metadata) {
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
            bundle.remove(KEY);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) return value;
        }
        return null;
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
