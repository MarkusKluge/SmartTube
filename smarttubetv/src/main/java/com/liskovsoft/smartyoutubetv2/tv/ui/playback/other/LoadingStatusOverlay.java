package com.liskovsoft.smartyoutubetv2.tv.ui.playback.other;

import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.google.android.exoplayer2.util.LoadingStatus;

/**
 * LOCAL: shows what the player is doing while the spinner turns (video info, requests, redirects,
 * network switches, backoffs, live transfer rate). Text comes from {@link LoadingStatus}.
 */
public final class LoadingStatusOverlay {
    private static final long REFRESH_MS = 250;
    private static final long SHOW_DELAY_MS = 300; // no flicker on short buffering
    private static final int CLAUDE_GREEN = 0xFF2FD655;

    public interface PlayerState {
        boolean isPlaying();
        boolean isBuffering();
    }

    private final TextView mView;
    private final PlayerState mPlayerState;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private long mWantedSinceMs;
    private boolean mStarted;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            update();
            mHandler.postDelayed(this, REFRESH_MS);
        }
    };

    public LoadingStatusOverlay(ViewGroup root, PlayerState playerState) {
        mPlayerState = playerState;
        mView = new TextView(root.getContext());
        mView.setTextColor(CLAUDE_GREEN);
        mView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        mView.setTypeface(Typeface.MONOSPACE);
        mView.setShadowLayer(4, 0, 0, Color.BLACK);
        mView.setBackgroundColor(0x99000000);
        int padding = dp(root, 8);
        mView.setPadding(padding, padding / 2, padding, padding / 2);
        mView.setVisibility(View.GONE);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.CENTER;
        params.topMargin = dp(root, 200); // below the spinner
        root.addView(mView, params);
    }

    public void start() {
        if (mStarted) {
            return;
        }

        mStarted = true;
        mHandler.post(mTick);
    }

    public void stop() {
        mStarted = false;
        mHandler.removeCallbacks(mTick);
        mView.setVisibility(View.GONE);
        mWantedSinceMs = 0;
    }

    private void update() {
        boolean playing = mPlayerState.isPlaying();

        if (playing) {
            LoadingStatus.finish();
        }

        boolean wanted = !playing && (LoadingStatus.isActive() || mPlayerState.isBuffering());

        if (!wanted) {
            mWantedSinceMs = 0;
            mView.setVisibility(View.GONE);
            return;
        }

        long now = SystemClock.elapsedRealtime();

        if (mWantedSinceMs == 0) {
            mWantedSinceMs = now;
        }

        if (now - mWantedSinceMs < SHOW_DELAY_MS) {
            return;
        }

        mView.setText(LoadingStatus.render());
        mView.setVisibility(View.VISIBLE);
        mView.bringToFront();
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }
}
