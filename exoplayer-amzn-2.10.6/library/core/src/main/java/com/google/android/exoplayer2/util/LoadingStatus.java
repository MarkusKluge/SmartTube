package com.google.android.exoplayer2.util;

import android.os.SystemClock;

import com.google.android.exoplayer2.C;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * What the player is doing while the user stares at a spinner: video info, requests, redirects,
 * network switches, backoffs and the live transfer rate per track. Written by the app, the SABR
 * chunk source and the data sources, read by the loading overlay.
 */
public final class LoadingStatus {
    private static final String TAG = "LoadingStatus";
    private static final int MAX_STEPS = 6;
    private static final long RATE_WINDOW_MS = 1_000;
    private static final long STALE_RATE_MS = 2_000;
    private static final int TRACKS = 3; // C.TRACK_TYPE_DEFAULT (DASH/unknown), AUDIO, VIDEO

    private static final ArrayDeque<String> sSteps = new ArrayDeque<>();
    private static final long[] sBytes = new long[TRACKS];
    private static final long[] sWindowBytes = new long[TRACKS];
    private static final long[] sWindowStartMs = new long[TRACKS];
    private static final long[] sLastDataMs = new long[TRACKS];
    private static final long[] sRateBps = new long[TRACKS];
    private static final String[] sFormats = new String[TRACKS];
    private static final String[] sHosts = new String[TRACKS];
    private static long sStartMs;
    private static boolean sActive;

    private LoadingStatus() {
    }

    /** A new video starts loading. */
    public static synchronized void begin(String step) {
        sSteps.clear();

        for (int i = 0; i < TRACKS; i++) {
            sBytes[i] = 0;
            sWindowBytes[i] = 0;
            sWindowStartMs[i] = 0;
            sLastDataMs[i] = 0;
            sRateBps[i] = 0;
            sFormats[i] = null;
            sHosts[i] = null;
        }

        sStartMs = now();
        sActive = true;
        step(step);
    }

    /** The first frames are playing. */
    public static synchronized void finish() {
        if (sActive) {
            Log.d(TAG, String.format(Locale.US, "Playback started after %.1f s", elapsedMs() / 1_000f));
        }

        sActive = false;
    }

    /** True from {@link #begin} until playback starts. */
    public static synchronized boolean isActive() {
        return sActive;
    }

    public static synchronized long elapsedMs() {
        return sStartMs == 0 ? 0 : now() - sStartMs;
    }

    public static synchronized void step(String text) {
        if (sStartMs == 0) {
            sStartMs = now();
        }

        sSteps.addLast(String.format(Locale.US, "%5.1fs  %s", elapsedMs() / 1_000f, text));

        while (sSteps.size() > MAX_STEPS) {
            sSteps.removeFirst();
        }

        Log.d(TAG, text);
    }

    /** What a track is loading right now (format label, media host). */
    public static synchronized void setTrack(int trackType, @androidx.annotation.Nullable String format,
                                             @androidx.annotation.Nullable String host) {
        int i = index(trackType);

        if (format != null) {
            sFormats[i] = format;
        }

        if (host != null) {
            sHosts[i] = host;
        }
    }

    public static synchronized void addBytes(int trackType, int count) {
        if (count <= 0) {
            return;
        }

        int i = index(trackType);
        long now = now();

        sBytes[i] += count;
        sLastDataMs[i] = now;

        if (sWindowStartMs[i] == 0) {
            sWindowStartMs[i] = now;
        }

        sWindowBytes[i] += count;
        long windowMs = now - sWindowStartMs[i];

        if (windowMs >= RATE_WINDOW_MS) {
            sRateBps[i] = sWindowBytes[i] * 8 * 1_000 / windowMs;
            sWindowBytes[i] = 0;
            sWindowStartMs[i] = now;
        }
    }

    /** Multi-line text for the overlay. */
    public static synchronized String render() {
        StringBuilder sb = new StringBuilder();

        if (sActive) {
            sb.append(String.format(Locale.US, "Lade Video · %.1f s", elapsedMs() / 1_000f));
        } else {
            sb.append("Puffere nach");
        }

        appendTrack(sb, "Video", C.TRACK_TYPE_VIDEO);
        appendTrack(sb, "Audio", C.TRACK_TYPE_AUDIO);
        appendTrack(sb, "Daten", C.TRACK_TYPE_DEFAULT);

        for (String step : sSteps) {
            sb.append('\n').append(step);
        }

        return sb.toString();
    }

    private static void appendTrack(StringBuilder sb, String label, int trackType) {
        int i = index(trackType);

        if (sBytes[i] == 0 && sFormats[i] == null) {
            return;
        }

        long now = now();
        boolean stale = sLastDataMs[i] == 0 || now - sLastDataMs[i] > STALE_RATE_MS;

        sb.append('\n').append(label);

        if (sFormats[i] != null) {
            sb.append(' ').append(sFormats[i]);
        }

        if (sHosts[i] != null) {
            sb.append(" · ").append(shortHost(sHosts[i]));
        }

        sb.append(String.format(Locale.US, " · %.1f MB · %s",
                sBytes[i] / 1_048_576f,
                stale ? (sBytes[i] == 0 ? "wartet" : "keine Daten") : String.format(Locale.US, "%.1f Mbit/s", sRateBps[i] / 1_000_000f)));
    }

    private static String shortHost(String host) {
        return host.endsWith(".googlevideo.com") ? host.substring(0, host.length() - ".googlevideo.com".length()) : host;
    }

    private static int index(int trackType) {
        return trackType == C.TRACK_TYPE_AUDIO || trackType == C.TRACK_TYPE_VIDEO ? trackType : C.TRACK_TYPE_DEFAULT;
    }

    private static long now() {
        return SystemClock.elapsedRealtime();
    }
}
