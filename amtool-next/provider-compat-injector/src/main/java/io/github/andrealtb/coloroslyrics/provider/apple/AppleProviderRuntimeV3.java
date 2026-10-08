package io.github.andrealtb.coloroslyrics.provider.apple;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

/**
 * V3 provider engine.
 *
 * The legacy provider mixed MediaSession observation, PlaybackItem discovery, request retries,
 * cache/replay and publication into one mutable hooker. V3 makes the current track generation the
 * only authority. Every request/result/publication is bound to that generation.
 */
final class AppleProviderRuntimeV3 {
    private static final String TAG = "AppleProviderV3";
    private static final String HOST = "com.apple.android.music";
    private static final String MEDIA_ID =
            "com.apple.android.music.playback.metadata.METADATA_KEY_MEDIA_ID";
    private static final String LYRIC_INFO = "lyricInfo";
    private static final String APPLE_MEDIA_PLAYBACK_SERVICE =
            "com.apple.android.music.player.MediaPlaybackService";
    private static final long TASK_PROBE_DELAY_MS = 1_200L;
    private static final int TASK_REMOVAL_CONFIRMATIONS = 2;

    private static final class CanonicalTrack {
        final String id;
        final String title;
        final String artist;
        final String album;
        final long durationMs;

        CanonicalTrack(String id, String title, String artist, String album, long durationMs) {
            this.id = clean(id);
            this.title = clean(title);
            this.artist = clean(artist);
            this.album = clean(album);
            this.durationMs = Math.max(0L, durationMs);
        }

        boolean isBlank() {
            return empty(id) && empty(title) && empty(artist);
        }

        CanonicalTrack merge(CanonicalTrack other) {
            return new CanonicalTrack(
                    first(id, other.id),
                    first(title, other.title),
                    first(artist, other.artist),
                    first(album, other.album),
                    durationMs > 0L ? durationMs : other.durationMs
            );
        }

        boolean same(CanonicalTrack other) {
            if (other == null || isBlank() || other.isBlank()) return false;

            if (!empty(id) && !empty(other.id) && id.equals(other.id)) {
                return true;
            }

            // Apple can expose a different MediaSession/PlaybackItem ID while keeping the same
            // recording alive (route/decoder/library-vs-catalog hand-off). Do not create a new
            // lyric generation solely because that representation changed.
            if (!norm(title).equals(norm(other.title))) return false;
            if (!norm(artist).equals(norm(other.artist))) return false;

            if (durationMs > 0L && other.durationMs > 0L &&
                    Math.abs(durationMs - other.durationMs) > 3000L) {
                return false;
            }

            String leftAlbum = norm(album);
            String rightAlbum = norm(other.album);
            if (!leftAlbum.isEmpty() && !rightAlbum.isEmpty() && !leftAlbum.equals(rightAlbum)) {
                return false;
            }
            return true;
        }
    }

    private static final class SessionState {
        MediaMetadata metadata;
        CanonicalTrack track;
    }

    private final XposedModule module;
    private final ClassLoader moduleLoader;
    private final ClassLoader hostLoader;
    private final Application application;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final AppleLyricGenerationGate lyricGate = new AppleLyricGenerationGate();
    private final WeakHashMap<Activity, Boolean> startedActivities = new WeakHashMap<>();

    private boolean hostTaskPresent = true;
    private boolean taskProbeScheduled;
    private int emptyTaskProbeCount;

    private final WeakHashMap<MediaSession, SessionState> sessions = new WeakHashMap<>();

    private Object generationPolicy;
    private Method generationObserve;
    private Constructor<?> trackIdentityCtor;
    private Method loadLyricsMethod;
    private Method parseSongMethod;
    private Method applyTranslationMethod;
    private Method mapRichLinesMethod;
    private Method publishMethod;

    private CanonicalTrack current;
    private Object currentTrackIdentity;
    private long generation;
    private Object readyLines;
    private long readyGeneration;
    private long leaseEpoch;

    AppleProviderRuntimeV3(
            XposedModule module,
            ClassLoader moduleLoader,
            ClassLoader hostLoader,
            Application application
    ) {
        this.module = module;
        this.moduleLoader = moduleLoader;
        this.hostLoader = hostLoader;
        this.application = application;
    }

    void install() throws Exception {
        resolveProviderPrimitives();
        installSessionHooks();
        installHostTaskLifecycleHooks();
        installLyricsHooks();
        module.log(Log.INFO, TAG, "provider runtime v3 alpha7 host-observer installed");
    }

    private void resolveProviderPrimitives() throws Exception {
        Class<?> trackIdentity = moduleLoader.loadClass(
                "io.github.andrealtb.coloroslyrics.provider.core.model.TrackIdentity");
        for (Constructor<?> ctor : trackIdentity.getDeclaredConstructors()) {
            Class<?>[] p = ctor.getParameterTypes();
            if (p.length == 5 &&
                    p[0] == String.class &&
                    p[1] == String.class &&
                    p[2] == String.class &&
                    p[3] == String.class &&
                    (p[4] == long.class || p[4] == Long.class)) {
                ctor.setAccessible(true);
                trackIdentityCtor = ctor;
                break;
            }
        }
        if (trackIdentityCtor == null) {
            throw new NoSuchMethodException("TrackIdentity(String,String,String,String,long)");
        }

        Class<?> generationType = moduleLoader.loadClass(
                "io.github.andrealtb.coloroslyrics.provider.core.policy.TrackGenerationPolicy");
        generationPolicy = generationType.getDeclaredConstructor().newInstance();
        generationObserve = findMethod(generationType, "onTrackObserved", 1);
        if (generationObserve == null) {
            throw new NoSuchMethodException("TrackGenerationPolicy#onTrackObserved");
        }

        Class<?> parserType = moduleLoader.loadClass(
                "io.github.andrealtb.coloroslyrics.provider.apple.AppleSongParser");
        Object parser = kotlinObject(parserType);
        parseSongMethod = findMethod(parserType, "parse", 1);
        applyTranslationMethod = findMethod(parserType, "applySystemTranslation", 2);
        if (parseSongMethod != null) parseSongMethod.setAccessible(true);
        if (applyTranslationMethod != null) applyTranslationMethod.setAccessible(true);
        parserInstance = parser;

        Class<?> mapperType = moduleLoader.loadClass(
                "io.github.andrealtb.coloroslyrics.provider.apple.AppleSongMapper");
        mapperInstance = kotlinObject(mapperType);
        mapRichLinesMethod = findMethod(mapperType, "toRichLines", 1);

        Class<?> publisherType = moduleLoader.loadClass(
                "io.github.andrealtb.coloroslyrics.provider.core.publisher.NativeLyricInfoPublisher");
        publisherInstance = kotlinObject(publisherType);
        publishMethod = findMethod(publisherType, "publishToHostMetadata", 7);
        if (publishMethod == null) {
            throw new NoSuchMethodException("NativeLyricInfoPublisher#publishToHostMetadata");
        }
    }

    private Object parserInstance;
    private Object mapperInstance;
    private Object publisherInstance;

    private void installSessionHooks() throws Exception {
        Method setMetadata = MediaSession.class.getDeclaredMethod(
                "setMetadata", MediaMetadata.class);
        setMetadata.setAccessible(true);
        module.hook(setMetadata)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> {
                    MediaSession session = (MediaSession) chain.getThisObject();
                    MediaMetadata metadata = (MediaMetadata) chain.getArg(0);
                    if (session == null || metadata == null) return chain.proceed();

                    onHostMetadata(session, metadata);
                    return chain.proceed();
                });

        Method release = MediaSession.class.getDeclaredMethod("release");
        release.setAccessible(true);
        module.hook(release)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> {
                    MediaSession session = (MediaSession) chain.getThisObject();
                    synchronized (lock) {
                        SessionState state = sessions.get(session);
                        if (state != null && state.metadata != null) {
                            clearOwnedLyricInfo(state.metadata);
                        }
                        sessions.remove(session);
                        leaseEpoch++;
                    }
                    return chain.proceed();
                });
    }

    /**
     * Recents removal is not equivalent to MediaSession.release().
     *
     * Apple Music may keep its playback service and MediaSession alive after the task card is
     * swiped away. ColorOS then keeps rendering the last lyricInfo indefinitely unless the provider
     * explicitly retracts it. We therefore model task presence as a separate lease:
     *
     * - Activity start => task present
     * - all activities stopped => poll ActivityManager#getAppTasks while backgrounded
     * - task disappears => invalidate every lyric ticket + lease and synchronously retract lyricInfo
     * - Service#onTaskRemoved => immediate fast path when the host service receives it
     */
    private void installHostTaskLifecycleHooks() throws Exception {
        Method onStart = Activity.class.getDeclaredMethod("onStart");
        onStart.setAccessible(true);
        module.hook(onStart)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    Activity activity = (Activity) chain.getThisObject();
                    if (activity != null && HOST.equals(activity.getPackageName())) {
                        synchronized (lock) {
                            startedActivities.put(activity, Boolean.TRUE);
                        }
                        onHostTaskPresent("activity-start");
                    }
                    return result;
                });

        Method onStop = Activity.class.getDeclaredMethod("onStop");
        onStop.setAccessible(true);
        module.hook(onStop)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    Activity activity = (Activity) chain.getThisObject();
                    if (activity != null && HOST.equals(activity.getPackageName())) {
                        boolean background;
                        synchronized (lock) {
                            startedActivities.remove(activity);
                            background = startedActivities.isEmpty();
                        }
                        if (background) scheduleTaskPresenceProbe();
                    }
                    return result;
                });

        Method onTaskRemoved = Service.class.getDeclaredMethod(
                "onTaskRemoved", Intent.class);
        onTaskRemoved.setAccessible(true);
        module.hook(onTaskRemoved)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> {
                    onHostTaskRemoved("service-onTaskRemoved");
                    return chain.proceed();
                });

        installExactApplePlaybackServiceLifecycleHooks();

        module.log(Log.INFO, TAG, "host task lifecycle hooks installed");
    }

    private void installExactApplePlaybackServiceLifecycleHooks() {
        try {
            Class<?> serviceType = hostLoader.loadClass(APPLE_MEDIA_PLAYBACK_SERVICE);

            Method onTaskRemoved = findMethod(serviceType, "onTaskRemoved", 1);
            if (onTaskRemoved != null &&
                    onTaskRemoved.getParameterTypes()[0] == Intent.class) {
                module.hook(onTaskRemoved)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            // Before the service handles task removal (or stops itself), send the
                            // explicit empty lyricInfo while its MediaSession is still valid.
                            onHostTaskRemoved("apple-MediaPlaybackService-onTaskRemoved");
                            return chain.proceed();
                        });
                module.log(
                        Log.INFO,
                        TAG,
                        "exact Apple MediaPlaybackService#onTaskRemoved hook installed"
                );
            }

            Method onDestroy = findMethod(serviceType, "onDestroy", 0);
            if (onDestroy != null) {
                module.hook(onDestroy)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            onHostTaskRemoved("apple-MediaPlaybackService-onDestroy");
                            return chain.proceed();
                        });
                module.log(
                        Log.INFO,
                        TAG,
                        "exact Apple MediaPlaybackService#onDestroy hook installed"
                );
            }
        } catch (Throwable error) {
            module.log(
                    Log.ERROR,
                    TAG,
                    "exact Apple MediaPlaybackService lifecycle hooks unavailable",
                    error
            );
        }
    }

    private void scheduleTaskPresenceProbe() {
        synchronized (lock) {
            if (taskProbeScheduled) return;
            taskProbeScheduled = true;
        }
        main.postDelayed(this::runTaskPresenceProbe, TASK_PROBE_DELAY_MS);
    }

    private void runTaskPresenceProbe() {
        boolean shouldContinue;
        synchronized (lock) {
            taskProbeScheduled = false;
            if (!startedActivities.isEmpty()) return;
        }

        if (!hasHostTask()) {
            boolean confirmedRemoved;
            synchronized (lock) {
                emptyTaskProbeCount++;
                confirmedRemoved = emptyTaskProbeCount >= TASK_REMOVAL_CONFIRMATIONS;
                shouldContinue = !confirmedRemoved &&
                        startedActivities.isEmpty() &&
                        hostTaskPresent;
            }
            if (confirmedRemoved) {
                onHostTaskRemoved("appTasks-empty-confirmed");
                return;
            }
            if (shouldContinue) scheduleTaskPresenceProbe();
            return;
        }

        synchronized (lock) {
            emptyTaskProbeCount = 0;
            shouldContinue = startedActivities.isEmpty() && hostTaskPresent;
        }
        if (shouldContinue) scheduleTaskPresenceProbe();
    }

    private boolean hasHostTask() {
        try {
            ActivityManager manager =
                    (ActivityManager) application.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return true;

            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                if (task == null) continue;
                ActivityManager.RecentTaskInfo info = task.getTaskInfo();
                if (info == null) continue;

                ComponentName base = info.baseActivity;
                ComponentName top = info.topActivity;
                ComponentName component =
                        info.baseIntent == null ? null : info.baseIntent.getComponent();

                if ((base != null && HOST.equals(base.getPackageName())) ||
                        (top != null && HOST.equals(top.getPackageName())) ||
                        (component != null && HOST.equals(component.getPackageName()))) {
                    return true;
                }
            }
            return false;
        } catch (Throwable error) {
            module.log(Log.ERROR, TAG, "host task presence probe failed", error);
            // Fail open: never retract lyrics merely because ActivityManager querying failed.
            return true;
        }
    }

    private void onHostTaskPresent(String source) {
        boolean resumed;
        synchronized (lock) {
            resumed = !hostTaskPresent;
            hostTaskPresent = true;
            taskProbeScheduled = false;
            emptyTaskProbeCount = 0;
            if (resumed) {
                lyricGate.invalidateCurrent();
                leaseEpoch++;
            }
        }

        if (resumed) {
            module.log(Log.INFO, TAG, "host task lease restored source=" + source);
        }
    }

    private void onHostTaskRemoved(String source) {
        boolean changed;
        synchronized (lock) {
            changed = hostTaskPresent;
            hostTaskPresent = false;
            taskProbeScheduled = false;
            emptyTaskProbeCount = 0;

            lyricGate.invalidateCurrent();
            readyLines = null;
            readyGeneration = 0L;

            leaseEpoch++;
        }

        // Strict playback-passive invariant: never create a provider-owned MediaSession metadata
        // transaction. Clear only our cached Java objects; process/session teardown owns delivery.
        clearOwnedLyricsInMemory();

        if (changed) {
            module.log(Log.INFO, TAG, "host task lease revoked source=" + source);
        }
    }

    private void installLyricsHooks() throws Exception {
        Class<?> viewModel = hostLoader.loadClass(
                "com.apple.android.music.player.viewmodel.PlayerLyricsViewModel");
        Class<?> playbackItem = hostLoader.loadClass(
                "com.apple.android.music.model.PlaybackItem");

        for (Method method : viewModel.getDeclaredMethods()) {
            if (method.getParameterCount() == 1 &&
                    playbackItem.isAssignableFrom(method.getParameterTypes()[0]) &&
                    method.getReturnType() == void.class &&
                    (method.getName().equals("loadLyrics") || loadLyricsMethod == null)) {
                loadLyricsMethod = method;
                if (method.getName().equals("loadLyrics")) break;
            }
        }
        if (loadLyricsMethod == null) {
            throw new NoSuchMethodException("PlayerLyricsViewModel#loadLyrics");
        }
        loadLyricsMethod.setAccessible(true);
        module.hook(loadLyricsMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> {
                    Object item = chain.getArg(0);
                    CanonicalTrack track = trackFromPlaybackItem(item);
                    if (track != null) {
                        transitionTo(track, "host-loadLyrics", false);
                        markHostLyricRequestInFlight();
                    }
                    return chain.proceed();
                });

        Class<?> songInfoPtr = hostLoader.loadClass(
                "com.apple.android.music.ttml.javanative.model.SongInfo$SongInfoPtr");
        Method build = null;
        for (Method method : viewModel.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) &&
                    method.getParameterCount() == 1 &&
                    method.getParameterTypes()[0] == songInfoPtr &&
                    method.getReturnType() == void.class) {
                if (method.getName().equals("buildTimeRangeToLyricsMap")) {
                    build = method;
                    break;
                }
                if (build == null) build = method;
            }
        }
        if (build == null) {
            throw new NoSuchMethodException("PlayerLyricsViewModel#buildTimeRangeToLyricsMap");
        }
        build.setAccessible(true);
        module.hook(build)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    Object songNative = unwrapSongInfo(chain.getArg(0));
                    if (songNative != null) onLyricsBuilt(songNative);
                    return result;
                });
    }

    private void onHostMetadata(MediaSession session, MediaMetadata metadata) {
        CanonicalTrack track = trackFromMetadata(metadata);
        if (track == null || track.isBlank()) {
            synchronized (lock) {
                SessionState info = sessionState(session);
                info.metadata = metadata;
            }
            return;
        }

        synchronized (lock) {
            SessionState info = sessionState(session);
            if (current != null && !current.same(track)) {
                clearOwnedLyricInfo(metadata);
            }
            info.metadata = metadata;
            info.track = track;
        }

        transitionTo(track, "media-session", true);
        attachReadyLyricsToHostMetadata(session, metadata);
    }

    private void transitionTo(CanonicalTrack incoming, String source, boolean requestNow) {
        if (incoming == null || incoming.isBlank()) return;

        boolean changed;
        synchronized (lock) {
            changed = current == null || !current.same(incoming);
            current = current == null || changed ? incoming : current.merge(incoming);
            try {
                currentTrackIdentity = toTrackIdentity(current);
                Object value = generationObserve.invoke(generationPolicy, currentTrackIdentity);
                generation = ((Number) value).longValue();
            } catch (Throwable error) {
                module.log(Log.ERROR, TAG, "generation transition failed", error);
                return;
            }

            if (changed) {
                lyricGate.bindGeneration(generation);
                readyLines = null;
                readyGeneration = 0L;
                leaseEpoch++;
            }
        }

        if (changed) {
            clearOwnedLyricsInMemory();
            module.log(
                    Log.INFO,
                    TAG,
                    "track generation=" + generation + " source=" + source +
                            " id=" + safe(current.id) + " title=" + safe(current.title)
            );
        }
        // requestNow is retained as a call-site semantic marker only. Provider V3 alpha7 never
        // invokes PlayerLyricsViewModel#loadLyrics itself; it only observes host-owned requests.
    }

    private void markHostLyricRequestInFlight() {
        synchronized (lock) {
            if (!hostTaskPresent || current == null) return;
            lyricGate.markHostRequestInFlight();
        }
    }

    private void onLyricsBuilt(Object songNative) {
        final AppleLyricGenerationGate.Ticket ticket;
        final CanonicalTrack track;
        synchronized (lock) {
            if (!hostTaskPresent) return;
            ticket = lyricGate.ticket();
            track = current;
        }
        if (track == null) return;

        try {
            applyPreferredTranslation(songNative);

            Object song = parseSongMethod == null
                    ? null
                    : parseSongMethod.invoke(parserInstance, songNative);
            if (song == null) return;

            String adamId = string(call(song, "getAdamId"));
            long duration = number(call(song, "getDurationMs"));
            if (!empty(track.id) && !empty(adamId) && !track.id.equals(adamId)) {
                module.log(
                        Log.INFO,
                        TAG,
                        "discard stale/prefetch lyrics current=" + track.id + " callback=" + adamId
                );
                return;
            }
            if (!lyricGate.accepts(ticket)) return;

            Object linesObject = mapRichLinesMethod == null
                    ? null
                    : mapRichLinesMethod.invoke(mapperInstance, song);
            if (!(linesObject instanceof List)) return;
            List<?> lines = (List<?>) linesObject;

            if (lines.isEmpty()) {
                if (!lyricGate.markNoLyrics(ticket)) return;
                synchronized (lock) {
                    readyLines = null;
                    readyGeneration = 0L;
                    leaseEpoch++;
                    }
                clearOwnedLyricsFromSessions();
                module.log(
                        Log.INFO,
                        TAG,
                        "lyrics callback empty generation=" + ticket.generation +
                                " duration=" + duration
                );
                return;
            }

            if (!lyricGate.markReady(ticket)) return;
            synchronized (lock) {
                if (!lyricGate.accepts(ticket)) return;
                readyLines = lines;
                readyGeneration = ticket.generation;
                leaseEpoch++;
            }
            module.log(
                    Log.INFO,
                    TAG,
                    "lyrics ready generation=" + ticket.generation +
                            " waiting for host metadata carrier lines=" + lines.size()
            );
        } catch (Throwable error) {
            module.log(Log.ERROR, TAG, "lyrics callback parse failed", error);
        }
    }

    /**
     * Attach ready lyrics only while Apple Music itself is already publishing MediaMetadata.
     *
     * Any provider-owned playback/session transaction can race
     * Apple Music's decoder/session hand-off. The device symptom is a track that advances only
     * a few seconds and then behaves like a preview until a lyric seek forces another player
     * transition. V3 alpha6+ therefore treats Apple's setMetadata call as the only live carrier:
     * mutate the incoming object before the host call proceeds, but never issue a second
     * setMetadata call from the module during active playback.
     */
    private void attachReadyLyricsToHostMetadata(
            MediaSession session,
            MediaMetadata metadata
    ) {
        final List<?> lines;
        final long gen;
        final long epoch;
        final Object trackIdentity;

        synchronized (lock) {
            if (!hostTaskPresent ||
                    !(readyLines instanceof List) ||
                    readyGeneration != generation ||
                    currentTrackIdentity == null) {
                return;
            }

            SessionState info = sessions.get(session);
            if (info == null || info.track == null || current == null ||
                    !current.same(info.track)) {
                return;
            }

            if (isOwnedLyricInfo(metadata.getString(LYRIC_INFO))) return;

            lines = (List<?>) readyLines;
            if (lines.isEmpty()) return;
            gen = generation;
            epoch = leaseEpoch;
            trackIdentity = currentTrackIdentity;
        }

        try {
            publishMethod.invoke(
                    publisherInstance,
                    metadata,
                    trackIdentity,
                    lines,
                    gen,
                    generationPolicy,
                    HOST,
                    HOST
            );

            synchronized (lock) {
                if (gen != generation || epoch != leaseEpoch || !hostTaskPresent) {
                    clearOwnedLyricInfo(metadata);
                    return;
                }
            }

            if (isOwnedLyricInfo(metadata.getString(LYRIC_INFO))) {
                module.log(
                        Log.INFO,
                        TAG,
                        "lyrics attached to host metadata generation=" + gen +
                                " lines=" + lines.size()
                );
            }
        } catch (Throwable error) {
            module.log(Log.ERROR, TAG, "host-owned lyric attachment failed", error);
        }
    }

    /**
     * Strip module-owned lyricInfo from cached Java objects without publishing another
     * MediaSession metadata transaction. The next Apple-owned metadata update carries the clean
     * object. Explicit STOPPED/release/task-removal paths may still call setMetadata because
     * playback is no longer active.
     */
    private void clearOwnedLyricsInMemory() {
        synchronized (lock) {
            for (SessionState state : sessions.values()) {
                if (state == null || state.metadata == null) continue;
                clearOwnedLyricInfo(state.metadata);
            }
        }
    }

    private void invalidatePublication() {
        synchronized (lock) {
            leaseEpoch++;
        }
    }



    private void clearOwnedLyricsFromSessions() {
        clearOwnedLyricsInMemory();
    }

    private SessionState sessionState(MediaSession session) {
        SessionState state = sessions.get(session);
        if (state == null) {
            state = new SessionState();
            sessions.put(session, state);
        }
        return state;
    }

    private CanonicalTrack trackFromMetadata(MediaMetadata metadata) {
        if (metadata == null) return null;
        String id = first(
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                metadata.getString(MEDIA_ID)
        );
        String title = first(
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
        );
        String artist = first(
                metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
        );
        String album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
        long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
        CanonicalTrack track = new CanonicalTrack(id, title, artist, album, duration);
        return track.isBlank() ? null : track;
    }

    private CanonicalTrack trackFromPlaybackItem(Object item) {
        if (item == null) return null;
        String id = first(
                string(call(item, "getSubscriptionStoreId")),
                string(call(item, "getId"))
        );
        String title = first(
                readStringField(item, "name"),
                string(call(item, "getNowPlayingTitle")),
                string(call(item, "getTitle"))
        );
        String artist = first(
                readStringField(item, "artistName"),
                string(call(item, "getArtistName")),
                string(call(item, "getNowPlayingSubtitle"))
        );
        String album = first(
                readStringField(item, "collectionName"),
                string(call(item, "getCollectionName"))
        );
        long duration = number(call(item, "getPlaybackDuration"));
        if (duration > 0L && duration < 10_000L) duration *= 1000L;
        CanonicalTrack track = new CanonicalTrack(id, title, artist, album, duration);
        return track.isBlank() ? null : track;
    }

    private Object toTrackIdentity(CanonicalTrack track) throws Exception {
        return trackIdentityCtor.newInstance(
                track.id,
                track.title,
                track.artist,
                track.album,
                track.durationMs
        );
    }

    private void applyPreferredTranslation(Object songNative) {
        try {
            if (applyTranslationMethod != null) {
                Object exact = applyTranslationMethod.invoke(parserInstance, songNative, "zh-Hans");
                if (Boolean.TRUE.equals(exact)) return;
            }
        } catch (Throwable ignored) {
        }

        Object vector = call(songNative, "getTranslationLanguages");
        List<String> available = new ArrayList<>();
        long size = number(call(vector, "size"));
        for (long i = 0; i < Math.min(size, 128L); i++) {
            Object value = call(vector, "get", i);
            if (!(value instanceof String)) value = call(vector, "get", (int) i);
            if (value instanceof String && !((String) value).trim().isEmpty()) {
                available.add(((String) value).trim());
            }
        }

        String selected = null;
        for (String candidate : available) {
            String normalized = candidate.replace('_', '-').toLowerCase(java.util.Locale.ROOT);
            if (normalized.equals("zh-hans")) {
                selected = candidate;
                break;
            }
        }
        if (selected == null) {
            for (String candidate : available) {
                String normalized = candidate.replace('_', '-').toLowerCase(java.util.Locale.ROOT);
                if (normalized.startsWith("zh-hans") || normalized.equals("zh-cn")) {
                    selected = candidate;
                    break;
                }
            }
        }
        if (selected == null) {
            for (String candidate : available) {
                String normalized = candidate.replace('_', '-').toLowerCase(java.util.Locale.ROOT);
                if (normalized.equals("zh") || normalized.startsWith("zh-")) {
                    selected = candidate;
                    break;
                }
            }
        }
        if (selected != null) {
            call(songNative, "setTranslation", selected);
        }
    }

    private Object unwrapSongInfo(Object ptr) {
        if (ptr == null) return null;
        try {
            Class<?> callsType = moduleLoader.loadClass(
                    "io.github.andrealtb.coloroslyrics.provider.apple.AppleNativeCalls");
            Object calls = kotlinObject(callsType);
            Method unwrap = findMethod(callsType, "unwrapPtr", 1);
            return unwrap == null ? null : unwrap.invoke(calls, ptr);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private boolean clearOwnedLyricInfo(MediaMetadata metadata) {
        if (metadata == null) return false;
        String payload = metadata.getString(LYRIC_INFO);
        if (!isOwnedLyricInfo(payload)) return false;
        return TrackTransitionLyricGuard.clearOwnedLyricInfo(metadata);
    }

    private static boolean isOwnedLyricInfo(String payload) {
        return payload != null &&
                payload.contains("\"provider\":\"com.apple.android.music\"") &&
                payload.contains("\"source\":\"com.apple.android.music-v5\"");
    }


    private static Object kotlinObject(Class<?> type) throws Exception {
        return type.getField("INSTANCE").get(null);
    }

    private static Method findMethod(Class<?> type, String name, int count) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == count) {
                    method.setAccessible(true);
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Object call(Object instance, String name, Object... args) {
        if (instance == null) return null;
        Method method = compatibleMethod(instance.getClass(), name, args);
        if (method == null) return null;
        try {
            method.setAccessible(true);
            return method.invoke(instance, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Method compatibleMethod(Class<?> type, String name, Object[] args) {
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!method.getName().equals(name) || method.getParameterCount() != args.length) {
                    continue;
                }
                Class<?>[] p = method.getParameterTypes();
                boolean ok = true;
                for (int i = 0; i < p.length; i++) {
                    if (!compatible(p[i], args[i])) {
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

    private static boolean compatible(Class<?> type, Object value) {
        if (value == null) return !type.isPrimitive();
        if (!type.isPrimitive()) return type.isInstance(value);
        if (type == boolean.class) return value instanceof Boolean;
        if (type == char.class) return value instanceof Character;
        return value instanceof Number;
    }

    private static String readStringField(Object instance, String name) {
        Class<?> current = instance.getClass();
        while (current != null) {
            try {
                java.lang.reflect.Field field = current.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(instance);
                return value instanceof String ? clean((String) value) : null;
            } catch (Throwable ignored) {
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static String string(Object value) {
        return value == null ? null : clean(String.valueOf(value));
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0L;
    }

    private static String first(String... values) {
        for (String value : values) if (!empty(value)) return clean(value);
        return null;
    }

    private static String clean(String value) {
        if (value == null) return null;
        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static boolean empty(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String norm(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
