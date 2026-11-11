package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.os.SystemClock;
import android.view.Surface;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.analytics.AnalyticsListener;
import com.google.android.exoplayer2.source.MediaSourceEventListener.LoadEventInfo;
import com.google.android.exoplayer2.source.MediaSourceEventListener.MediaLoadData;
import com.google.android.exoplayer2.util.Log;

import java.io.IOException;
import java.util.Locale;

/**
 * LOCAL (diagnostics): one log line per player event under the tag "PlayerMetrics", so a test run
 * can be evaluated from logcat alone: states with durations, every load (track, bytes, time, rate,
 * media range, buffer), format changes, bandwidth estimates, seeks, dropped frames, errors and the first frame.
 */
public final class PlayerMetricsLogger implements AnalyticsListener {
    private static final String TAG = "PlayerMetrics";
    private int mState = Player.STATE_IDLE;
    private long mStateSinceMs = SystemClock.elapsedRealtime();
    private long mPreparedAtMs;

    @Override
    public void onPlayerStateChanged(EventTime eventTime, boolean playWhenReady, int playbackState) {
        long now = SystemClock.elapsedRealtime();

        if (playbackState != mState) {
            log("state %s -> %s after %d ms (pos=%d ms, buffered=%d ms, playWhenReady=%s)",
                    state(mState), state(playbackState), now - mStateSinceMs,
                    eventTime.currentPlaybackPositionMs, eventTime.totalBufferedDurationMs, playWhenReady);
            mState = playbackState;
            mStateSinceMs = now;
        }

        if (playbackState == Player.STATE_BUFFERING && mPreparedAtMs == 0) {
            mPreparedAtMs = now;
        }
    }

    @Override
    public void onRenderedFirstFrame(EventTime eventTime, @Nullable Surface surface) {
        log("first frame %d ms after prepare (pos=%d ms, buffered=%d ms)",
                mPreparedAtMs > 0 ? SystemClock.elapsedRealtime() - mPreparedAtMs : -1,
                eventTime.currentPlaybackPositionMs, eventTime.totalBufferedDurationMs);
        mPreparedAtMs = 0;
    }

    @Override
    public void onLoadStarted(EventTime eventTime, LoadEventInfo info, MediaLoadData data) {
        log("load start %s %s media=%s..%s ms buffered=%d ms",
                track(data.trackType), dataType(data.dataType), ms(data.mediaStartTimeMs), ms(data.mediaEndTimeMs),
                eventTime.totalBufferedDurationMs);
    }

    @Override
    public void onLoadCompleted(EventTime eventTime, LoadEventInfo info, MediaLoadData data) {
        long rateKbps = info.loadDurationMs > 0 ? info.bytesLoaded * 8 / info.loadDurationMs : -1;
        log("load done %s %s %d KB in %d ms (%d kbit/s) media=%s..%s ms format=%s buffered=%d ms host=%s",
                track(data.trackType), dataType(data.dataType), info.bytesLoaded / 1024, info.loadDurationMs, rateKbps,
                ms(data.mediaStartTimeMs), ms(data.mediaEndTimeMs), format(data.trackFormat),
                eventTime.totalBufferedDurationMs, info.uri != null ? info.uri.getHost() : null);
    }

    @Override
    public void onLoadCanceled(EventTime eventTime, LoadEventInfo info, MediaLoadData data) {
        log("load canceled %s %s after %d ms, %d KB", track(data.trackType), dataType(data.dataType),
                info.loadDurationMs, info.bytesLoaded / 1024);
    }

    @Override
    public void onLoadError(EventTime eventTime, LoadEventInfo info, MediaLoadData data, IOException error, boolean wasCanceled) {
        log("load error %s %s after %d ms, %d KB: %s", track(data.trackType), dataType(data.dataType),
                info.loadDurationMs, info.bytesLoaded / 1024, error.getMessage());
    }

    @Override
    public void onDownstreamFormatChanged(EventTime eventTime, MediaLoadData data) {
        log("downstream format %s %s", track(data.trackType), format(data.trackFormat));
    }

    @Override
    public void onBandwidthEstimate(EventTime eventTime, int totalLoadTimeMs, long totalBytesLoaded, long bitrateEstimate) {
        log("bandwidth estimate %d kbit/s (%d KB in %d ms)", bitrateEstimate / 1000, totalBytesLoaded / 1024, totalLoadTimeMs);
    }

    @Override
    public void onSeekStarted(EventTime eventTime) {
        log("seek started (pos=%d ms)", eventTime.currentPlaybackPositionMs);
    }

    @Override
    public void onSeekProcessed(EventTime eventTime) {
        log("seek processed (pos=%d ms, buffered=%d ms)", eventTime.currentPlaybackPositionMs, eventTime.totalBufferedDurationMs);
    }

    @Override
    public void onDroppedVideoFrames(EventTime eventTime, int droppedFrames, long elapsedMs) {
        log("dropped %d frames in %d ms", droppedFrames, elapsedMs);
    }

    @Override
    public void onPlayerError(EventTime eventTime, ExoPlaybackException error) {
        Throwable cause = error.getCause() != null ? error.getCause() : error;
        log("player error %s: %s", cause.getClass().getSimpleName(), cause.getMessage());
    }

    private static void log(String format, Object... args) {
        Log.d(TAG, String.format(Locale.US, format, args));
    }

    private static String ms(long value) {
        return value == C.TIME_UNSET ? "unset" : String.valueOf(value);
    }

    private static String track(int trackType) {
        switch (trackType) {
            case C.TRACK_TYPE_VIDEO: return "video";
            case C.TRACK_TYPE_AUDIO: return "audio";
            case C.TRACK_TYPE_TEXT: return "text";
            case C.TRACK_TYPE_DEFAULT: return "default";
            default: return "track" + trackType;
        }
    }

    private static String dataType(int dataType) {
        switch (dataType) {
            case C.DATA_TYPE_MEDIA: return "media";
            case C.DATA_TYPE_MEDIA_INITIALIZATION: return "init";
            case C.DATA_TYPE_MANIFEST: return "manifest";
            default: return "type" + dataType;
        }
    }

    private static String format(@Nullable Format format) {
        if (format == null) {
            return "null";
        }

        return format.height > 0
                ? String.format(Locale.US, "%s %dp%s %s %dk", format.id, format.height,
                        format.frameRate > 0 ? Math.round(format.frameRate) : "", format.codecs, format.bitrate / 1000)
                : String.format(Locale.US, "%s %s %dk", format.id, format.codecs, format.bitrate / 1000);
    }

    private static String state(int state) {
        switch (state) {
            case Player.STATE_IDLE: return "IDLE";
            case Player.STATE_BUFFERING: return "BUFFERING";
            case Player.STATE_READY: return "READY";
            case Player.STATE_ENDED: return "ENDED";
            default: return String.valueOf(state);
        }
    }
}
