package com.google.android.exoplayer2.source.sabr;

import android.net.Uri;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.TransferListener;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

/**
 * Fails a SABR response that trickles in far slower than the format needs.
 * A media network that sends a few bytes every second never hits the read timeout, yet the buffer
 * drains and playback hangs until the app gives up. Failing it as a (slow) read timeout lets the
 * chunk source move to another media network.
 */
final class SlowNetworkGuardDataSource implements DataSource {
    /** Not enough data yet for a meaningful rate before this (includes connect and first byte). */
    private static final long GRACE_MS = 6_000;

    /** Thrown when the response arrives too slowly. A read timeout, so the network failover kicks in. */
    static final class SlowNetworkException extends SocketTimeoutException {
        SlowNetworkException(String message) {
            super(message);
        }
    }

    private final DataSource upstream;
    private final long minBitsPerSecond;
    private long openTimeMs;
    private long bytesRead;

    SlowNetworkGuardDataSource(DataSource upstream, long minBitsPerSecond) {
        this.upstream = upstream;
        this.minBitsPerSecond = minBitsPerSecond;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        openTimeMs = SystemClock.elapsedRealtime();
        bytesRead = 0;
        return upstream.open(dataSpec);
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException {
        int read = upstream.read(buffer, offset, readLength);

        if (read > 0) {
            bytesRead += read;
        }

        long elapsedMs = SystemClock.elapsedRealtime() - openTimeMs;

        if (minBitsPerSecond > 0 && elapsedMs >= GRACE_MS) {
            long bitsPerSecond = bytesRead * 8 * 1_000 / elapsedMs;

            if (bitsPerSecond < minBitsPerSecond) {
                Uri uri = upstream.getUri();
                throw new SlowNetworkException(String.format("Media network %s too slow: %d kbit/s, need %d kbit/s",
                        uri != null ? uri.getHost() : null, bitsPerSecond / 1_000, minBitsPerSecond / 1_000));
            }
        }

        return read;
    }

    @Nullable
    @Override
    public Uri getUri() {
        return upstream.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        upstream.close();
    }
}
