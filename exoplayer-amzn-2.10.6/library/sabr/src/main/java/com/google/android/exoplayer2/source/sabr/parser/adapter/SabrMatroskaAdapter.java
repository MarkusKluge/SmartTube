package com.google.android.exoplayer2.source.sabr.parser.adapter;

import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.extractor.PositionHolder;
import com.google.android.exoplayer2.extractor.mkv.MatroskaExtractor;
import com.google.android.exoplayer2.source.sabr.parser.SabrStream;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrReloadPlayerError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrBackoffError;
import com.google.android.exoplayer2.source.sabr.parser.misc.SabrExtractorInput;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.IOException;
import java.net.SocketTimeoutException;

public class SabrMatroskaAdapter extends MatroskaExtractor {
    private static final String TAG = SabrMatroskaAdapter.class.getSimpleName();
    private final SabrExtractorInput extractorInput;

    public SabrMatroskaAdapter(SabrStream sabrStream) {
        this.extractorInput = new SabrExtractorInput(sabrStream);
    }

    public SabrMatroskaAdapter(int flags, SabrStream sabrStream) {
        super(flags);
        this.extractorInput = new SabrExtractorInput(sabrStream);
    }

    @Override
    public int read(ExtractorInput input, PositionHolder seekPosition)
            throws IOException, InterruptedException {
        int result = RESULT_END_OF_INPUT;

        try {
            extractorInput.init(input);
            result = super.read(extractorInput, seekPosition);
        } catch (SabrReloadPlayerError | SabrBackoffError e) {
            Log.e(TAG, "Propagating SABR control signal: %s", e.getMessage());
            throw e;
        } catch (IOException e) {
            // FIX: a media network that stalls mid-response must reach DefaultSabrChunkSource.onChunkLoadError(),
            // otherwise the chunk "ends" silently and the same stalled request is repeated forever.
            if (hasCause(e, SocketTimeoutException.class)) {
                Log.e(TAG, "Propagating read timeout: %s", e.getMessage());
                throw e;
            }
            Log.e(TAG, "User doing seek? %s: %s", e.getClass().getSimpleName(), e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            // Same stall, but wrapped by the UMP reader (e.g. IllegalStateException(HttpDataSourceException(SocketTimeoutException))).
            if (hasCause(e, SocketTimeoutException.class)) {
                Log.e(TAG, "Propagating wrapped read timeout: %s", e.getMessage());
                throw new IOException(e);
            }
            Log.e(TAG, "User doing seek? %s: %s", e.getClass().getSimpleName(), e.getMessage());
            e.printStackTrace();
        } finally {
            if (result != RESULT_CONTINUE) {
                Log.e(TAG, "MatroskaAdapter: disposing, result=%s", result);
                extractorInput.dispose();
            }
        }

        return result;
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (type.isInstance(cause)) {
                return true;
            }
        }

        return false;
    }
}
