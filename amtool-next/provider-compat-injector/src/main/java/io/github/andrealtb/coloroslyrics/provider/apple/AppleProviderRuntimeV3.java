package io.github.andrealtb.coloroslyrics.provider.apple;

import android.app.Application;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
    private static final long LEASE_HEARTBEAT_MS = 500L;
    private static final long LEASE_PAST_MS = 750L;
    private static final long LEASE_FUTURE_MS = 2_500L;

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
            if (!empty(id) && !empty(other.id)) return id.equals(other.id);
            if (!norm(title).equals(norm(other.title))) return false;
            if (!norm(artist).equals(norm(other.artist))) return false;
            if (durationMs > 0L && other.durationMs > 0L) {
                return Math.abs(durationMs - other.durationMs) <= 3000L;
            }
            return true;
        }
    }

    private static final class SessionState {
        MediaMetadata metadata;
        CanonicalTrack track;
        PlaybackState playback;
        int playbackState = PlaybackState.STATE_NONE;
        boolean active;
    }

    private final XposedModule module;
    private final ClassLoader moduleLoader;
    private final ClassLoader hostLoader;
    private final Application application;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object lock = new Object();
    private final ThreadLocal<Boolean> moduleWrite = new ThreadLocal<>();
    private final ThreadLocal<Boolean> ownLyricRequest = new ThreadLocal<>();
    private final AppleLyricGenerationGate lyricGate = new AppleLyricGenerationGate();

    private final WeakHashMap<MediaSession, SessionState> sessions = new WeakHashMap<>();
    private final LinkedHashMap<String, Object> playbackItems =
            new LinkedHashMap<String, Object>(24, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Object> eldest) {
                    return size() > 32;
                }
            };

    private Object generationPolicy;
    private Method generationObserve;
    private Constructor<?> trackIdentityCtor;
    private Object requester;
    private Method requesterSetLoadMethod;
    private Method requesterRequestDownload;
    private Method loadLyricsMethod;
    private Method parseSongMethod;
    private Method applyTranslationMethod;
    private Method mapRichLinesMethod;
    private Method publishMethod;

    private CanonicalTrack current;
    private Object currentTrackIdentity;
    private long generation;
    private Object readyLines;
    private long[] readyBegins;
    private long[] readyEnds;
    private long readyGeneration;
    private long leaseEpoch;
    private long heartbeatEpoch = -1L;
    private String lastLeaseWindowKey;

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
        installPlaybackDiscoveryHooks();
        installLyricsHooks();
        module.log(Log.INFO, TAG, "provider runtime v3 installed");
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

        Class<?> requesterType = moduleLoader.loadClass(
                "io.github.andrealtb.coloroslyrics.provider.apple.AppleLyricRequester");
        Constructor<?> requesterCtor = requesterType.getDeclaredConstructor(
                ClassLoader.class,
                Application.class,
                Handler.class
        );
        requesterCtor.setAccessible(true);
        requester = requesterCtor.newInstance(hostLoader, application, main);
        requesterSetLoadMethod = findMethod(requesterType, "setLoadLyricsMethod", 1);
        requesterRequestDownload = findMethod(requesterType, "requestDownload", 1);

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

                    if (Boolean.TRUE.equals(moduleWrite.get())) {
                        return chain.proceed();
                    }

                    onHostMetadata(session, metadata);
                    return chain.proceed();
                });

        Method setPlaybackState = MediaSession.class.getDeclaredMethod(
                "setPlaybackState", PlaybackState.class);
        setPlaybackState.setAccessible(true);
        module.hook(setPlaybackState)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    MediaSession session = (MediaSession) chain.getThisObject();
                    PlaybackState state = (PlaybackState) chain.getArg(0);
                    int value = state == null
                            ? PlaybackState.STATE_NONE
                            : state.getState();
                    synchronized (lock) {
                        SessionState info = sessionState(session);
                        info.playback = state;
                        info.playbackState = value;
                    }

                    if (isTerminalPlaybackState(value)) {
                        invalidateLease();
                        clearOwnedLyricsFromSession(session);
                    } else {
                        publishLeaseIfPossible();
                        scheduleLeaseHeartbeat();
                    }
                    return result;
                });

        Method setActive = MediaSession.class.getDeclaredMethod(
                "setActive", boolean.class);
        setActive.setAccessible(true);
        module.hook(setActive)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> {
                    Object result = chain.proceed();
                    MediaSession session = (MediaSession) chain.getThisObject();
                    boolean active = (Boolean) chain.getArg(0);
                    synchronized (lock) {
                        sessionState(session).active = active;
                    }
                    if (!active) {
                        clearOwnedLyricsFromSession(session);
                    } else {
                        publishLeaseIfPossible();
                        scheduleLeaseHeartbeat();
                    }
                    return result;
                });

        Method release = MediaSession.class.getDeclaredMethod("release");
        release.setAccessible(true);
        module.hook(release)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> {
                    MediaSession session = (MediaSession) chain.getThisObject();
                    clearOwnedLyricsFromSession(session);
                    synchronized (lock) {
                        sessions.remove(session);
                        leaseEpoch++;
                        heartbeatEpoch = -1L;
                    }
                    return chain.proceed();
                });
    }

    private void installPlaybackDiscoveryHooks() {
        for (String className : new String[] {
                "com.apple.android.music.player.N",
                "com.apple.android.music.player.M"
        }) {
            try {
                Class<?> type = hostLoader.loadClass(className);
                for (Method method : type.getDeclaredMethods()) {
                    if (!Modifier.isStatic(method.getModifiers()) ||
                            method.getParameterCount() != 1 ||
                            !method.getReturnType().getName().equals(
                                    "com.apple.android.music.model.PlaybackItem")) {
                        continue;
                    }
                    method.setAccessible(true);
                    module.hook(method)
                            .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                            .intercept(chain -> {
                                Object result = chain.proceed();
                                cachePlaybackItem(result);
                                return result;
                            });
                }
            } catch (Throwable ignored) {
            }
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
        if (requesterSetLoadMethod != null) {
            requesterSetLoadMethod.invoke(requester, loadLyricsMethod);
        }

        module.hook(loadLyricsMethod)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> {
                    Object item = chain.getArg(0);
                    cachePlaybackItem(item);
                    if (!Boolean.TRUE.equals(ownLyricRequest.get())) {
                        CanonicalTrack track = trackFromPlaybackItem(item);
                        if (track != null) {
                            transitionTo(track, "host-loadLyrics", false);
                            markHostLyricRequestInFlight();
                        }
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
        publishLeaseIfPossible();
        scheduleLeaseHeartbeat();
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
                readyBegins = null;
                readyEnds = null;
                readyGeneration = 0L;
                leaseEpoch++;
                heartbeatEpoch = -1L;
                lastLeaseWindowKey = null;
            }
        }

        if (changed) {
            clearOwnedLyricsFromSessions();
            module.log(
                    Log.INFO,
                    TAG,
                    "track generation=" + generation + " source=" + source +
                            " id=" + safe(current.id) + " title=" + safe(current.title)
            );
        }
        if (requestNow) maybeRequestLyrics();
    }

    private void markHostLyricRequestInFlight() {
        final AppleLyricGenerationGate.Ticket ticket;
        synchronized (lock) {
            if (current == null) return;
            lyricGate.markHostRequestInFlight();
            ticket = lyricGate.ticket();
        }
        scheduleRequestTimeout(ticket);
    }

    private void cachePlaybackItem(Object item) {
        CanonicalTrack track = trackFromPlaybackItem(item);
        if (item == null || track == null || empty(track.id)) return;
        synchronized (lock) {
            playbackItems.put(track.id, item);
        }

        CanonicalTrack snapshot;
        synchronized (lock) {
            snapshot = current;
        }
        if (snapshot != null && snapshot.same(track)) {
            maybeRequestLyrics();
        }
    }

    private void maybeRequestLyrics() {
        final CanonicalTrack track;
        final Object item;
        final AppleLyricGenerationGate.Ticket ticket;
        final int attempt;

        synchronized (lock) {
            track = current;
            if (track == null || empty(track.id)) return;

            AppleLyricGenerationGate.Phase phase = lyricGate.phase();
            if (phase == AppleLyricGenerationGate.Phase.READY ||
                    phase == AppleLyricGenerationGate.Phase.NO_LYRICS) {
                return;
            }

            item = playbackItems.get(track.id);
            ticket = lyricGate.ticket();
            if (item == null) {
                schedulePlaybackPoll(ticket);
                return;
            }
            if (!lyricGate.beginProviderRequest()) return;
            attempt = lyricGate.attempts();
        }

        main.post(() -> {
            if (!lyricGate.accepts(ticket)) return;

            boolean accepted = false;
            try {
                ownLyricRequest.set(true);
                Object value = requesterRequestDownload.invoke(requester, item);
                accepted = !(value instanceof Boolean) || (Boolean) value;
            } catch (Throwable error) {
                module.log(Log.ERROR, TAG, "lyrics request failed", error);
            } finally {
                ownLyricRequest.remove();
            }

            module.log(
                    Log.INFO,
                    TAG,
                    "lyrics request generation=" + ticket.generation +
                            " attempt=" + attempt +
                            " accepted=" + accepted
            );
            scheduleRequestTimeout(ticket);
        });
    }

    private void schedulePlaybackPoll(AppleLyricGenerationGate.Ticket ticket) {
        final int poll = lyricGate.nextPlaybackPoll(ticket, 8);
        if (poll < 0) return;

        main.postDelayed(() -> {
            if (!lyricGate.accepts(ticket)) return;

            boolean found;
            synchronized (lock) {
                if (current == null || empty(current.id)) return;
                found = playbackItems.containsKey(current.id);
            }

            if (found) {
                lyricGate.playbackItemFound(ticket);
                maybeRequestLyrics();
            } else if (poll < 8) {
                schedulePlaybackPoll(ticket);
            }
        }, 180L);
    }

    private void scheduleRequestTimeout(AppleLyricGenerationGate.Ticket ticket) {
        final int attempt = lyricGate.attempts();
        long delay = attempt <= 1 ? 650L : attempt == 2 ? 1400L : 2600L;

        main.postDelayed(() -> {
            AppleLyricGenerationGate.TimeoutAction action =
                    lyricGate.onTimeout(ticket, 3);
            switch (action) {
                case IGNORE:
                    return;
                case NO_LYRICS:
                    synchronized (lock) {
                        readyLines = null;
                        readyBegins = null;
                        readyEnds = null;
                        readyGeneration = 0L;
                        leaseEpoch++;
                        heartbeatEpoch = -1L;
                        lastLeaseWindowKey = null;
                    }
                    clearOwnedLyricsFromSessions();
                    module.log(
                            Log.INFO,
                            TAG,
                            "lyrics resolved as unavailable generation=" + ticket.generation
                    );
                    return;
                case RETRY:
                    maybeRequestLyrics();
                    return;
            }
        }, delay);
    }

    private void onLyricsBuilt(Object songNative) {
        final AppleLyricGenerationGate.Ticket ticket;
        final CanonicalTrack track;
        synchronized (lock) {
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
                    readyBegins = null;
                    readyEnds = null;
                    readyGeneration = 0L;
                    leaseEpoch++;
                    heartbeatEpoch = -1L;
                    lastLeaseWindowKey = null;
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
                readyBegins = extractLineTimes(lines, "getBegin");
                readyEnds = extractLineTimes(lines, "getEnd");
                readyGeneration = ticket.generation;
                leaseEpoch++;
                heartbeatEpoch = -1L;
                lastLeaseWindowKey = null;
            }
            publishLeaseIfPossible();
            scheduleLeaseHeartbeat();
        } catch (Throwable error) {
            module.log(Log.ERROR, TAG, "lyrics callback parse failed", error);
        }
    }

    private void publishLeaseIfPossible() {
        final List<?> fullLines;
        final List<?> leaseLines;
        final long gen;
        final long epoch;
        final Object trackIdentity;
        final MediaSession session;
        final MediaMetadata metadata;
        final SessionState info;
        final String windowKey;

        synchronized (lock) {
            if (!(readyLines instanceof List) ||
                    readyGeneration != generation ||
                    currentTrackIdentity == null) {
                return;
            }
            session = selectSessionLocked();
            if (session == null) return;
            info = sessions.get(session);
            if (info == null || info.metadata == null || !info.active) return;
            if (!validPlaybackState(info.playbackState)) return;

            fullLines = (List<?>) readyLines;
            long positionMs = estimatedPositionMs(info.playback);
            leaseLines = leaseWindow(fullLines, positionMs);
            if (leaseLines.isEmpty()) {
                // Instrumental gap / no current lease: remove the previous module-owned payload.
                // This is essential because ColorOS caches lyricInfo independently from the host.
                if (clearOwnedLyricInfo(info.metadata)) {
                    lastLeaseWindowKey = null;
                    main.post(() -> writeSessionMetadata(session, info.metadata));
                }
                return;
            }

            int first = fullLines.indexOf(leaseLines.get(0));
            int last = fullLines.indexOf(leaseLines.get(leaseLines.size() - 1));
            windowKey = generation + ":" + first + ":" + last;

            // Do not churn MediaSession metadata when the lease window has not advanced.
            if (windowKey.equals(lastLeaseWindowKey) &&
                    isOwnedLyricInfo(info.metadata.getString(LYRIC_INFO))) {
                return;
            }

            metadata = info.metadata;
            gen = generation;
            epoch = leaseEpoch;
            trackIdentity = currentTrackIdentity;
        }

        try {
            publishMethod.invoke(
                    publisherInstance,
                    metadata,
                    trackIdentity,
                    leaseLines,
                    gen,
                    generationPolicy,
                    HOST,
                    HOST
            );
            synchronized (lock) {
                if (gen != generation || epoch != leaseEpoch) return;
                if (isOwnedLyricInfo(metadata.getString(LYRIC_INFO))) {
                    lastLeaseWindowKey = windowKey;
                } else {
                    return;
                }
            }
            writeSessionMetadata(session, metadata);
            module.log(
                    Log.INFO,
                    TAG,
                    "lyrics lease published generation=" + gen +
                            " window=" + windowKey +
                            " lines=" + leaseLines.size()
            );
        } catch (Throwable error) {
            module.log(Log.ERROR, TAG, "lyrics lease publication failed", error);
        }
    }

    private void scheduleLeaseHeartbeat() {
        final long epoch;
        final long gen;

        synchronized (lock) {
            if (!(readyLines instanceof List) ||
                    readyGeneration != generation ||
                    lyricGate.phase() != AppleLyricGenerationGate.Phase.READY) {
                return;
            }
            MediaSession session = selectSessionLocked();
            if (session == null) return;
            SessionState info = sessions.get(session);
            if (info == null || !info.active ||
                    info.playbackState != PlaybackState.STATE_PLAYING) {
                return;
            }

            epoch = leaseEpoch;
            gen = generation;
            if (heartbeatEpoch == epoch) return;
            heartbeatEpoch = epoch;
        }

        main.postDelayed(() -> {
            synchronized (lock) {
                if (heartbeatEpoch == epoch) heartbeatEpoch = -1L;
                if (epoch != leaseEpoch || gen != generation) return;
            }
            publishLeaseIfPossible();
            scheduleLeaseHeartbeat();
        }, LEASE_HEARTBEAT_MS);
    }

    private void invalidateLease() {
        synchronized (lock) {
            leaseEpoch++;
            heartbeatEpoch = -1L;
            lastLeaseWindowKey = null;
        }
    }

    private List<?> leaseWindow(List<?> lines, long positionMs) {
        if (lines.isEmpty() || readyBegins == null || readyEnds == null) return List.of();
        AppleLyricLeasePolicy.Window window = AppleLyricLeasePolicy.select(
                readyBegins,
                readyEnds,
                positionMs,
                LEASE_PAST_MS,
                LEASE_FUTURE_MS
        );
        if (window == null) return List.of();
        return new ArrayList<>(lines.subList(window.first, window.last + 1));
    }

    private long[] extractLineTimes(List<?> lines, String getter) {
        long[] values = new long[lines.size()];
        for (int i = 0; i < lines.size(); i++) {
            values[i] = number(call(lines.get(i), getter));
        }
        return values;
    }

    private long estimatedPositionMs(PlaybackState state) {
        if (state == null) return 0L;
        long position = Math.max(0L, state.getPosition());
        if (state.getState() != PlaybackState.STATE_PLAYING) return position;

        long updatedAt = state.getLastPositionUpdateTime();
        if (updatedAt <= 0L) return position;
        long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - updatedAt);
        float speed = state.getPlaybackSpeed();
        return Math.max(0L, position + (long) (elapsed * speed));
    }

    private boolean isTerminalPlaybackState(int state) {
        return state == PlaybackState.STATE_NONE ||
                state == PlaybackState.STATE_STOPPED ||
                state == PlaybackState.STATE_ERROR;
    }

    private MediaSession selectSessionLocked() {
        MediaSession fallback = null;
        for (Map.Entry<MediaSession, SessionState> entry : sessions.entrySet()) {
            MediaSession session = entry.getKey();
            SessionState info = entry.getValue();
            if (session == null || info == null || info.metadata == null || info.track == null) {
                continue;
            }
            if (current != null && !current.same(info.track)) continue;
            if (info.active && validPlaybackState(info.playbackState)) return session;
            if (fallback == null) fallback = session;
        }
        return fallback;
    }

    private void clearOwnedLyricsFromSession(MediaSession session) {
        if (session == null) return;
        MediaMetadata metadata;
        synchronized (lock) {
            SessionState state = sessions.get(session);
            metadata = state == null ? null : state.metadata;
        }
        if (metadata != null && clearOwnedLyricInfo(metadata)) {
            writeSessionMetadata(session, metadata);
        }
    }

    private void clearOwnedLyricsFromSessions() {
        List<Map.Entry<MediaSession, MediaMetadata>> updates = new ArrayList<>();
        synchronized (lock) {
            for (Map.Entry<MediaSession, SessionState> entry : sessions.entrySet()) {
                MediaSession session = entry.getKey();
                SessionState state = entry.getValue();
                if (session == null || state == null || state.metadata == null) continue;
                if (clearOwnedLyricInfo(state.metadata)) {
                    updates.add(Map.entry(session, state.metadata));
                }
            }
        }
        for (Map.Entry<MediaSession, MediaMetadata> update : updates) {
            writeSessionMetadata(update.getKey(), update.getValue());
        }
    }

    private void writeSessionMetadata(MediaSession session, MediaMetadata metadata) {
        try {
            moduleWrite.set(true);
            session.setMetadata(metadata);
        } catch (Throwable error) {
            module.log(Log.ERROR, TAG, "session metadata write failed", error);
        } finally {
            moduleWrite.remove();
        }
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
        String id = string(call(item, "getId"));
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

    private static boolean validPlaybackState(int state) {
        return state == PlaybackState.STATE_PLAYING ||
                state == PlaybackState.STATE_PAUSED ||
                state == PlaybackState.STATE_BUFFERING ||
                state == PlaybackState.STATE_CONNECTING ||
                state == PlaybackState.STATE_SKIPPING_TO_NEXT ||
                state == PlaybackState.STATE_SKIPPING_TO_PREVIOUS ||
                state == PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM;
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
