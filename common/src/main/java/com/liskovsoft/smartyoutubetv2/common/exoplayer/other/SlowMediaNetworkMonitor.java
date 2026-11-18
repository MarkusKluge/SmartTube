package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.source.MediaSourceEventListener.LoadEventInfo;
import com.google.android.exoplayer2.source.MediaSourceEventListener.MediaLoadData;
import com.google.android.exoplayer2.source.sabr.manifest.SabrCdnSelector;
import com.google.android.exoplayer2.util.LoadingStatus;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.util.ArrayDeque;
import java.util.Locale;

/**
 * LOCAL: moves playback off a media network that answers, but slower than real time.<br/>
 * Such a network never hits a read timeout (e.g. 1.5 Mbit/s for a 4 Mbit/s format), the buffer just drains
 * and playback pauses again and again. Measured per network as the real-time factor of the recent video
 * loads: seconds of media per second of loading. Below {@link #MIN_REALTIME_FACTOR} while the buffer is low,
 * the network is marked as failed; SABR ({@code DefaultSabrChunkSource}) and DASH ({@link CdnFailoverDataSource})
 * share that list and send the next request to the next advertised network.
 */
public final class SlowMediaNetworkMonitor implements AnalyticsListener {
    private static final String TAG = SlowMediaNetworkMonitor.class.getSimpleName();
    private static final String GOOGLEVIDEO_SUFFIX = ".googlevideo.com";
    /** Only act while the buffer is low. With a full buffer the player loads in bursts (and SABR paces on purpose). */
    private static final long MAX_BUFFER_MS = 15_000;
    /** Seconds of media per second of loading needed to (re)fill the buffer. */
    private static final float MIN_REALTIME_FACTOR = 1.2f;
    /** Enough loading time for a meaningful factor (one slow request after a seek doesn't count). */
    private static final long MIN_WINDOW_LOAD_MS = 6_000;
    private static final int MAX_SAMPLES = 4;
    private static final long MAX_SAMPLE_AGE_MS = 60_000;
    /** Give the next network time to prove itself before judging again. */
    private static final long COOLDOWN_MS = 15_000;

    private static final class Sample {
        final long atMs;
        final long mediaMs;
        final long loadMs;

        Sample(long atMs, long mediaMs, long loadMs) {
            this.atMs = atMs;
            this.mediaMs = mediaMs;
            this.loadMs = loadMs;
        }
    }

    private final ArrayDeque<Sample> mSamples = new ArrayDeque<>();
    private final Timeline.Window mWindow = new Timeline.Window();
    @Nullable private String mHost;
    private long mLastSwitchMs;

    @Override
    public void onLoadCompleted(EventTime eventTime, LoadEventInfo info, MediaLoadData data) {
        if (data.trackType != C.TRACK_TYPE_VIDEO || data.dataType != C.DATA_TYPE_MEDIA
                || data.mediaStartTimeMs == C.TIME_UNSET || data.mediaEndTimeMs == C.TIME_UNSET) {
            return;
        }

        long mediaMs = data.mediaEndTimeMs - data.mediaStartTimeMs;
        String host = getGooglevideoHost(info.uri);

        if (host == null || mediaMs <= 0 || info.loadDurationMs <= 0 || isLive(eventTime)) {
            return;
        }

        long now = SystemClock.elapsedRealtime();

        if (!host.equals(mHost)) {
            mHost = host;
            mSamples.clear();
        }

        mSamples.addLast(new Sample(now, mediaMs, info.loadDurationMs));

        while (mSamples.size() > MAX_SAMPLES || (!mSamples.isEmpty() && now - mSamples.peekFirst().atMs > MAX_SAMPLE_AGE_MS)) {
            mSamples.removeFirst();
        }

        if (now - mLastSwitchMs < COOLDOWN_MS || eventTime.totalBufferedDurationMs >= MAX_BUFFER_MS) {
            return;
        }

        long totalMediaMs = 0;
        long totalLoadMs = 0;

        for (Sample sample : mSamples) {
            totalMediaMs += sample.mediaMs;
            totalLoadMs += sample.loadMs;
        }

        if (totalLoadMs < MIN_WINDOW_LOAD_MS) {
            return;
        }

        float factor = (float) totalMediaMs / totalLoadMs;

        if (factor >= MIN_REALTIME_FACTOR) {
            return;
        }

        String message = String.format(Locale.US, "Media network %s too slow: %.2fx real time (%d ms media in %d ms, buffered %d ms)",
                host, factor, totalMediaMs, totalLoadMs, eventTime.totalBufferedDurationMs);
        Log.w(TAG, message);
        LoadingStatus.issue(message);
        SabrCdnSelector.markFailedUrl(info.uri.toString());
        mLastSwitchMs = now;
        mSamples.clear();
    }

    private boolean isLive(EventTime eventTime) {
        // At the live edge the server holds every response until the next segment exists.
        Timeline timeline = eventTime.timeline;

        if (timeline.isEmpty() || eventTime.windowIndex >= timeline.getWindowCount()) {
            return true;
        }

        return timeline.getWindow(eventTime.windowIndex, mWindow).isDynamic;
    }

    @Nullable
    private static String getGooglevideoHost(@Nullable Uri uri) {
        String host = uri != null ? uri.getHost() : null;
        return host != null && host.endsWith(GOOGLEVIDEO_SUFFIX) ? host : null;
    }
}
