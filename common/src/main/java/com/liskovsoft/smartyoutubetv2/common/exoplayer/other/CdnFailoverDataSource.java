package com.liskovsoft.smartyoutubetv2.common.exoplayer.other;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.source.sabr.manifest.SabrCdnSelector;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DataSpec;
import com.google.android.exoplayer2.upstream.HttpDataSource.InvalidResponseCodeException;
import com.google.android.exoplayer2.upstream.TransferListener;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;

/**
 * DASH counterpart of the SABR media network failover.<br/>
 * Rewrites googlevideo requests to the next advertised media network ("mn") when the assigned one
 * doesn't answer or stalls (e.g. an ISP-hosted cache node hanging on cache misses).
 * Failed networks are shared with {@link SabrCdnSelector}.
 */
public final class CdnFailoverDataSource implements DataSource {
    private static final String TAG = CdnFailoverDataSource.class.getSimpleName();
    private static final String GOOGLEVIDEO_SUFFIX = ".googlevideo.com";
    private final DataSource upstream;
    @Nullable private Uri currentUri;

    public static final class Factory implements DataSource.Factory {
        private final DataSource.Factory upstreamFactory;

        public Factory(DataSource.Factory upstreamFactory) {
            this.upstreamFactory = upstreamFactory;
        }

        @Override
        public DataSource createDataSource() {
            return new CdnFailoverDataSource(upstreamFactory.createDataSource());
        }
    }

    public CdnFailoverDataSource(DataSource upstream) {
        this.upstream = upstream;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        if (!isGooglevideo(dataSpec.uri)) {
            currentUri = dataSpec.uri;
            return upstream.open(dataSpec);
        }

        DataSpec spec = withHealthyUri(dataSpec);

        try {
            currentUri = spec.uri;
            return upstream.open(spec);
        } catch (IOException e) {
            if (!isNetworkFailure(e)) {
                throw e;
            }

            SabrCdnSelector.markFailedUrl(spec.uri.toString());
            DataSpec nextSpec = withHealthyUri(dataSpec);

            if (nextSpec.uri.equals(spec.uri)) {
                throw e;
            }

            Log.w(TAG, "Media network failed (%s). Retrying on %s", e.getMessage(), nextSpec.uri.getHost());
            closeQuietly();
            currentUri = nextSpec.uri;
            return upstream.open(nextSpec);
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException {
        try {
            return upstream.read(buffer, offset, readLength);
        } catch (IOException e) {
            // Stalled in the middle of a response. The loader retries the chunk, which then goes to the next network.
            if (currentUri != null && isGooglevideo(currentUri) && isNetworkFailure(e)) {
                SabrCdnSelector.markFailedUrl(currentUri.toString());
            }
            throw e;
        }
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

    private void closeQuietly() {
        try {
            upstream.close();
        } catch (IOException e) {
            // ignore
        }
    }

    private static DataSpec withHealthyUri(DataSpec dataSpec) {
        String url = dataSpec.uri.toString();
        String healthyUrl = SabrCdnSelector.getHealthyUrl(url);
        return healthyUrl.equals(url) ? dataSpec : dataSpec.withUri(Uri.parse(healthyUrl));
    }

    private static boolean isGooglevideo(@Nullable Uri uri) {
        String host = uri != null ? uri.getHost() : null;
        return host != null && host.endsWith(GOOGLEVIDEO_SUFFIX);
    }

    private static boolean isNetworkFailure(IOException error) {
        if (error instanceof InvalidResponseCodeException) {
            return false; // the network answered (e.g. 403), switching it won't help
        }

        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof ConnectException
                    || cause instanceof UnknownHostException) {
                return true;
            }
        }

        return false;
    }
}
