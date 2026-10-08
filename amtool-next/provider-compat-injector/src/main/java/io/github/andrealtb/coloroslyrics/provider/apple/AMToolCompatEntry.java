package io.github.andrealtb.coloroslyrics.provider.apple;

import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.github.libxposed.api.XposedModule;

public final class AMToolCompatEntry extends XposedModule {
    private static final String TAG = "AppleProvider-AMToolCompat";
    private static final String TARGET = "com.apple.android.music";

    @Override
    public void onPackageReady(PackageReadyParam param) {
        if (!TARGET.equals(param.getPackageName()) || !param.isFirstPackage()) return;

        ClassLoader moduleLoader = AMToolCompatEntry.class.getClassLoader();
        try {
            installCanonicalPlaybackIdentity(moduleLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "canonical playback identity hook failed", t);
        }
        try {
            installTranslationAliasCompatibility(moduleLoader);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "translation alias compatibility hook failed", t);
        }
        try {
            new TrackTransitionLyricGuard().install(this);
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "track transition lyric guard failed", t);
        }
    }

    /**
     * AMTool localizes Apple Music's display getters. The provider must not use those display-only
     * values for its internal track binding, otherwise MediaSession and PlaybackItem can appear to
     * be different songs. Read the unmodified 6.5.3 backing fields and build TrackIdentity directly.
     */
    private void installCanonicalPlaybackIdentity(ClassLoader moduleLoader) throws Exception {
        Class<?> identityOwner = moduleLoader.loadClass(
                "io.github.andrealtb.coloroslyrics.provider.apple.AppleTrackIdentity");
        Method fromPlaybackItem = null;
        for (Method m : identityOwner.getDeclaredMethods()) {
            if (m.getName().equals("fromPlaybackItem") && m.getParameterCount() == 1) {
                fromPlaybackItem = m;
                break;
            }
        }
        if (fromPlaybackItem == null) throw new NoSuchMethodException("AppleTrackIdentity#fromPlaybackItem");
        fromPlaybackItem.setAccessible(true);

        Class<?> trackIdentity = moduleLoader.loadClass(
                "io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity");
        Constructor<?> ctor = null;
        for (Constructor<?> candidate : trackIdentity.getDeclaredConstructors()) {
            Class<?>[] p = candidate.getParameterTypes();
            if (p.length == 5 &&
                    p[0] == String.class &&
                    p[1] == String.class &&
                    p[2] == String.class &&
                    p[3] == String.class &&
                    (p[4] == long.class || p[4] == Long.class)) {
                ctor = candidate;
                break;
            }
        }
        if (ctor == null) throw new NoSuchMethodException("TrackIdentity(String,String,String,String,long)");
        ctor.setAccessible(true);
        final Constructor<?> trackCtor = ctor;

        hook(fromPlaybackItem).intercept(chain -> {
            Object playbackItem = chain.getArg(0);
            if (playbackItem == null) return chain.proceed();

            String rawTitle = readStringField(playbackItem, "name");
            String rawArtist = readStringField(playbackItem, "artistName");
            String rawAlbum = readStringField(playbackItem, "collectionName");

            // Only take over when the canonical 6.5.3 model fields are present.
            if (isBlank(rawTitle) && isBlank(rawArtist) && isBlank(rawAlbum)) {
                return chain.proceed();
            }

            String id = callString(playbackItem, "getId");
            if (isBlank(rawTitle)) rawTitle = firstNonBlank(
                    callString(playbackItem, "getNowPlayingTitle"),
                    callString(playbackItem, "getTitle"));
            if (isBlank(rawArtist)) rawArtist = firstNonBlank(
                    callString(playbackItem, "getArtistName"),
                    callString(playbackItem, "getNowPlayingSubtitle"));
            if (isBlank(rawAlbum)) rawAlbum = callString(playbackItem, "getCollectionName");

            long duration = callLong(playbackItem, "getPlaybackDuration");
            if (duration > 0L && duration < 10_000L) duration *= 1000L;

            return trackCtor.newInstance(id, rawTitle, rawArtist, rawAlbum, duration);
        });

        log(Log.INFO, TAG, "canonical playback identity compatibility installed");
    }

    /**
     * Apple may expose a concrete translation lane such as zh-Hans-CN while the provider asks for
     * zh-Hans. Try the exact tag first, then select a compatible language from SongInfo.
     */
    private void installTranslationAliasCompatibility(ClassLoader moduleLoader) throws Exception {
        Class<?> parser = moduleLoader.loadClass(
                "io.github.andrealtb.coloroslyrics.provider.apple.AppleSongParser");
        Method apply = null;
        for (Method m : parser.getDeclaredMethods()) {
            if (m.getName().equals("applySystemTranslation") && m.getParameterCount() == 2) {
                apply = m;
                break;
            }
        }
        if (apply == null) throw new NoSuchMethodException("AppleSongParser#applySystemTranslation");
        apply.setAccessible(true);

        hook(apply).intercept(chain -> {
            Object songNative = chain.getArg(0);
            Object languageArg = chain.getArg(1);
            if (songNative == null || !(languageArg instanceof String)) {
                return chain.proceed();
            }

            String requested = ((String) languageArg).trim();
            if (requested.isEmpty()) return false;

            Boolean exact = callBoolean(songNative, "setTranslation", requested);
            if (Boolean.TRUE.equals(exact)) return true;

            Object vector = call(songNative, "getTranslationLanguages");
            List<String> available = readStringVector(vector);
            String selected = selectCompatibleLanguage(requested, available);
            if (selected == null) return false;

            return Boolean.TRUE.equals(callBoolean(songNative, "setTranslation", selected));
        });

        log(Log.INFO, TAG, "translation language alias compatibility installed");
    }

    private static String selectCompatibleLanguage(String requested, List<String> available) {
        LanguageParts target = parseLanguage(requested);
        if (target == null) return null;

        for (String candidate : available) {
            LanguageParts value = parseLanguage(candidate);
            if (value != null && value.normalized.equals(target.normalized)) return candidate;
        }
        for (String candidate : available) {
            LanguageParts value = parseLanguage(candidate);
            if (value != null &&
                    value.language.equals(target.language) &&
                    value.script != null &&
                    target.script != null &&
                    value.script.equals(target.script)) {
                return candidate;
            }
        }
        for (String candidate : available) {
            LanguageParts value = parseLanguage(candidate);
            if (value != null &&
                    value.language.equals(target.language) &&
                    value.region != null &&
                    target.region != null &&
                    value.region.equals(target.region)) {
                return candidate;
            }
        }
        for (String candidate : available) {
            LanguageParts value = parseLanguage(candidate);
            if (value != null && value.language.equals(target.language)) return candidate;
        }
        return null;
    }

    private static final class LanguageParts {
        final String normalized;
        final String language;
        final String script;
        final String region;

        LanguageParts(String normalized, String language, String script, String region) {
            this.normalized = normalized;
            this.language = language;
            this.script = script;
            this.region = region;
        }
    }

    private static LanguageParts parseLanguage(String raw) {
        if (raw == null) return null;
        String normalized = raw.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return null;

        String[] parts = normalized.split("-");
        String language = parts.length > 0 ? parts[0] : null;
        if (language == null || language.isEmpty()) return null;

        String script = null;
        String region = null;
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (script == null && part.length() == 4) script = part;
            if (region == null &&
                    (part.length() == 2 ||
                            (part.length() == 3 && allDigits(part)))) {
                region = part;
            }
        }
        return new LanguageParts(normalized, language, script, region);
    }

    private static boolean allDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) return false;
        }
        return true;
    }

    private static List<String> readStringVector(Object vector) {
        ArrayList<String> out = new ArrayList<>();
        if (vector == null) return out;

        long size = callLong(vector, "size");
        if (size < 0L) return out;
        size = Math.min(size, 128L);
        for (long i = 0; i < size; i++) {
            Object value = call(vector, "get", i);
            if (!(value instanceof String)) value = call(vector, "get", (int) i);
            if (value instanceof String) {
                String text = ((String) value).trim();
                if (!text.isEmpty()) out.add(text);
            }
        }
        return out;
    }

    private static String readStringField(Object instance, String name) {
        Field field = findField(instance.getClass(), name);
        if (field == null) return null;
        try {
            field.setAccessible(true);
            Object value = field.get(instance);
            return value instanceof String ? ((String) value).trim() : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getName().equals(name)) return field;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Object call(Object instance, String name, Object... args) {
        if (instance == null) return null;
        Method method = findCompatibleMethod(instance.getClass(), name, args);
        if (method == null) return null;
        try {
            method.setAccessible(true);
            return method.invoke(instance, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String callString(Object instance, String name) {
        Object value = call(instance, name);
        return value instanceof String ? (String) value : null;
    }

    private static Boolean callBoolean(Object instance, String name, Object... args) {
        Object value = call(instance, name, args);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static long callLong(Object instance, String name) {
        Object value = call(instance, name);
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static Method findCompatibleMethod(Class<?> type, String name, Object[] args) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
                Class<?>[] params = method.getParameterTypes();
                boolean ok = true;
                for (int i = 0; i < params.length; i++) {
                    if (!isCompatible(params[i], args[i])) {
                        ok = false;
                        break;
                    }
                }
                if (ok) return method;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static boolean isCompatible(Class<?> parameter, Object value) {
        if (value == null) return !parameter.isPrimitive();
        if (!parameter.isPrimitive()) return parameter.isInstance(value);
        if (parameter == boolean.class) return value instanceof Boolean;
        if (parameter == char.class) return value instanceof Character;
        return value instanceof Number;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (!isBlank(value)) return value;
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
