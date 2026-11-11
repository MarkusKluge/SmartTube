package com.google.android.exoplayer2.util;

import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * What the player is doing while the user stares at a spinner. Written by the app, the SABR chunk
 * source and the data sources, read by the loading overlay. Kept short on purpose:
 * current phase, one line per track (quality, bytes, rate) and the last few problems.
 */
public final class LoadingStatus {
    private static final String TAG = "LoadingStatus";
    private static final int MAX_ISSUES = 3;
    private static final long RATE_WINDOW_MS = 1_000;
    private static final long STALE_RATE_MS = 2_000;
    private static final int TRACKS = 3; // C.TRACK_TYPE_DEFAULT (DASH/other), AUDIO, VIDEO

    private static final ArrayDeque<String> sIssues = new ArrayDeque<>();
    private static final long[] sBytes = new long[TRACKS];
    private static final long[] sWindowBytes = new long[TRACKS];
    private static final long[] sWindowStartMs = new long[TRACKS];
    private static final long[] sLastDataMs = new long[TRACKS];
    private static final long[] sRateBps = new long[TRACKS];
    private static final String[] sFormats = new String[TRACKS];
    private static String sPhase;
    private static long sStartMs;
    private static boolean sActive;

    private LoadingStatus() {
    }

    /** A new video starts loading. */
    public static synchronized void begin(String phase) {
        sIssues.clear();

        for (int i = 0; i < TRACKS; i++) {
            sBytes[i] = 0;
            sWindowBytes[i] = 0;
            sWindowStartMs[i] = 0;
            sLastDataMs[i] = 0;
            sRateBps[i] = 0;
            sFormats[i] = null;
        }

        sStartMs = now();
        sActive = true;
        phase(phase);
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

    /** What happens right now (replaces the previous phase). */
    public static synchronized void phase(String text) {
        sPhase = text;
        Log.d(TAG, time() + " " + text);
    }

    /** Something went wrong or slowed us down (kept in a short list). */
    public static synchronized void issue(String text) {
        sIssues.addLast(time() + "  " + text);

        while (sIssues.size() > MAX_ISSUES) {
            sIssues.removeFirst();
        }

        Log.w(TAG, time() + " " + text);
    }

    /** The format a track is loading right now. */
    public static synchronized void setTrack(int trackType, @Nullable String format) {
        if (format != null) {
            sFormats[index(trackType)] = format;
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

    /** Text for the overlay. */
    public static synchronized String render() {
        StringBuilder sb = new StringBuilder();

        sb.append(sActive ? String.format(Locale.US, "Loading %.1f s", elapsedMs() / 1_000f) : "Rebuffering");

        if (sPhase != null) {
            sb.append(" · ").append(sPhase);
        }

        appendTrack(sb, "Video", C.TRACK_TYPE_VIDEO);
        appendTrack(sb, "Audio", C.TRACK_TYPE_AUDIO);
        appendTrack(sb, "Media", C.TRACK_TYPE_DEFAULT);

        for (String issue : sIssues) {
            sb.append("\n! ").append(issue);
        }

        return sb.toString();
    }

    private static void appendTrack(StringBuilder sb, String label, int trackType) {
        int i = index(trackType);

        if (sBytes[i] == 0 && sFormats[i] == null) {
            return;
        }

        boolean stale = sLastDataMs[i] == 0 || now() - sLastDataMs[i] > STALE_RATE_MS;
        String rate = stale ? (sBytes[i] == 0 ? "waiting" : "stalled")
                : String.format(Locale.US, "%.1f Mbit/s", sRateBps[i] / 1_000_000f);

        sb.append('\n').append(label);

        if (sFormats[i] != null) {
            sb.append(' ').append(sFormats[i]);
        }

        sb.append(String.format(Locale.US, " · %.1f MB · %s", sBytes[i] / 1_048_576f, rate));
    }

    private static String time() {
        return String.format(Locale.US, "%.1fs", elapsedMs() / 1_000f);
    }

    private static long elapsedMs() {
        return sStartMs == 0 ? 0 : now() - sStartMs;
    }

    private static int index(int trackType) {
        return trackType == C.TRACK_TYPE_AUDIO || trackType == C.TRACK_TYPE_VIDEO ? trackType : C.TRACK_TYPE_DEFAULT;
    }

    private static long now() {
        return SystemClock.elapsedRealtime();
    }
}
