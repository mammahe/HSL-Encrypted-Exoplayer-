package com.example.sairam.myapplication.exoplayer;

import com.google.android.exoplayer.C;
import com.google.android.exoplayer.LoadControl;
import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.MediaFormatHolder;
import com.google.android.exoplayer.SampleHolder;
import com.google.android.exoplayer.SampleSource;
import com.google.android.exoplayer.SampleSource.SampleSourceReader;
import com.google.android.exoplayer.TrackInfo;
import com.google.android.exoplayer.TrackRenderer;
import com.google.android.exoplayer.chunk.BaseChunkSampleSourceEventListener;
import com.google.android.exoplayer.chunk.Chunk;
import com.google.android.exoplayer.chunk.Format;
import com.google.android.exoplayer.hls.HlsChunkSource;
import com.google.android.exoplayer.hls.HlsExtractorWrapper;
import com.google.android.exoplayer.hls.HlsSampleSource;
import com.google.android.exoplayer.hls.TsChunk;
import com.google.android.exoplayer.upstream.Loader;
import com.google.android.exoplayer.upstream.Loader.Loadable;
import com.google.android.exoplayer.util.Assertions;

import android.os.Handler;
import android.os.SystemClock;

import java.io.IOException;
import java.util.LinkedList;

/**
 * A {@link SampleSource} for HLS streams.
 */
public class HlsSampleSourcea implements SampleSource, SampleSourceReader, Loader.Callback {

    public interface EventListener extends BaseChunkSampleSourceEventListener {}
    public static final int DEFAULT_MIN_LOADABLE_RETRY_COUNT = 3;

    private static final int NO_RESET_PENDING = -1;

    private final HlsChunkSourcea chunkSource;
    private final LinkedList<HlsExtractorWrapper> extractors;
    private final boolean frameAccurateSeeking;
    private final int minLoadableRetryCount;
    private final int bufferSizeContribution;

    private final int eventSourceId;
    private final LoadControl loadControl;
    private final Handler eventHandler;
    private final com.google.android.exoplayer.hls.HlsSampleSource.EventListener eventListener;

    private int remainingReleaseCount;
    private boolean prepared;
    private boolean loadControlRegistered;
    private int trackCount;
    private int enabledTrackCount;
    private boolean[] trackEnabledStates;
    private boolean[] pendingDiscontinuities;
    private TrackInfo[] trackInfos;
    private MediaFormat[] downstreamMediaFormats;
    private Format downstreamFormat;

    private long downstreamPositionUs;
    private long lastSeekPositionUs;
    private long pendingResetPositionUs;

    private boolean loadingFinished;
    private Chunk currentLoadable;
    private TsChunk currentTsLoadable;
    private TsChunk previousTsLoadable;

    private Loader loader;
    private IOException currentLoadableException;
    private int currentLoadableExceptionCount;
    private long currentLoadableExceptionTimestamp;
    private long currentLoadStartTimeMs;
    public HlsSampleSourcea(HlsChunkSourcea chunkSource, LoadControl loadControl,
                           int bufferSizeContribution, boolean frameAccurateSeeking) {
        this(chunkSource, loadControl, bufferSizeContribution, frameAccurateSeeking, null, null, 0);
    }

    public HlsSampleSourcea(HlsChunkSourcea chunkSource, LoadControl loadControl,
                            int bufferSizeContribution, boolean frameAccurateSeeking, Handler eventHandler,
                            HlsSampleSource.EventListener eventListener, int eventSourceId) {
        this(chunkSource, loadControl, bufferSizeContribution, frameAccurateSeeking, eventHandler,
                eventListener, eventSourceId, DEFAULT_MIN_LOADABLE_RETRY_COUNT);
    }

    public HlsSampleSourcea(HlsChunkSourcea chunkSource, LoadControl loadControl,
                            int bufferSizeContribution, boolean frameAccurateSeeking, Handler eventHandler,
                            HlsSampleSource.EventListener eventListener, int eventSourceId, int minLoadableRetryCount) {
        this.chunkSource = chunkSource;
        this.loadControl = loadControl;
        this.bufferSizeContribution = bufferSizeContribution;
        this.frameAccurateSeeking = frameAccurateSeeking;
        this.minLoadableRetryCount = minLoadableRetryCount;
        this.eventHandler = eventHandler;
        this.eventListener = eventListener;
        this.eventSourceId = eventSourceId;
        this.pendingResetPositionUs = NO_RESET_PENDING;
        extractors = new LinkedList<>();
    }

    @Override
    public SampleSourceReader register() {
        remainingReleaseCount++;
        return this;
    }

    @Override
    public boolean prepare(long positionUs) throws IOException {
        if (prepared) {
            return true;
        }
        if (!extractors.isEmpty()) {
            HlsExtractorWrapper extractor = getCurrentExtractor();
            if (extractor.isPrepared()) {
                trackCount = extractor.getTrackCount();
                trackEnabledStates = new boolean[trackCount];
                pendingDiscontinuities = new boolean[trackCount];
                downstreamMediaFormats = new MediaFormat[trackCount];
                trackInfos = new TrackInfo[trackCount];
                for (int i = 0; i < trackCount; i++) {
                    MediaFormat format = extractor.getMediaFormat(i);
                    trackInfos[i] = new TrackInfo(format.mimeType, chunkSource.getDurationUs());
                }
                prepared = true;
                return true;
            }
        }
        if (loader == null) {
            loader = new Loader("Loader:HLS");
        }
        if (!loadControlRegistered) {
            loadControl.register(this, bufferSizeContribution);
            loadControlRegistered = true;
        }
        if (!loader.isLoading()) {
            pendingResetPositionUs = positionUs;
            downstreamPositionUs = positionUs;
        }
        maybeStartLoading();
        maybeThrowLoadableException();
        return false;
    }

    @Override
    public int getTrackCount() {
        Assertions.checkState(prepared);
        return trackCount;
    }

    @Override
    public TrackInfo getTrackInfo(int track) {
        Assertions.checkState(prepared);
        return trackInfos[track];
    }

    @Override
    public void enable(int track, long positionUs) {
        Assertions.checkState(prepared);
        Assertions.checkState(!trackEnabledStates[track]);
        enabledTrackCount++;
        trackEnabledStates[track] = true;
        downstreamMediaFormats[track] = null;
        downstreamFormat = null;
        if (!loadControlRegistered) {
            loadControl.register(this, bufferSizeContribution);
            loadControlRegistered = true;
        }
        if (enabledTrackCount == 1) {
            seekToUs(positionUs);
        }
        pendingDiscontinuities[track] = false;
    }

    @Override
    public void disable(int track) {
        Assertions.checkState(prepared);
        Assertions.checkState(trackEnabledStates[track]);
        enabledTrackCount--;
        trackEnabledStates[track] = false;
        if (enabledTrackCount == 0) {
            downstreamPositionUs = Long.MIN_VALUE;
            if (loadControlRegistered) {
                loadControl.unregister(this);
                loadControlRegistered = false;
            }
            if (loader.isLoading()) {
                loader.cancelLoading();
            } else {
                clearState();
                loadControl.trimAllocator();
            }
        }
    }

    @Override
    public boolean continueBuffering(int track, long playbackPositionUs) throws IOException {
        Assertions.checkState(prepared);
        Assertions.checkState(trackEnabledStates[track]);
        downstreamPositionUs = playbackPositionUs;
        if (!extractors.isEmpty()) {
            discardSamplesForDisabledTracks(getCurrentExtractor(), downstreamPositionUs);
        }
        if (loadingFinished) {
            return true;
        }
        maybeStartLoading();
        if (isPendingReset() || extractors.isEmpty()) {
            return false;
        }

        for (int extractorIndex = 0; extractorIndex < extractors.size(); extractorIndex++) {
            HlsExtractorWrapper extractor = extractors.get(extractorIndex);
            if (!extractor.isPrepared()) {
                break;
            }
            if (extractor.hasSamples(track)) {
                return true;
            }
        }
        maybeThrowLoadableException();
        return false;
    }

    @Override
    public int readData(int track, long playbackPositionUs, MediaFormatHolder formatHolder,
                        SampleHolder sampleHolder, boolean onlyReadDiscontinuity) throws IOException {
        Assertions.checkState(prepared);
        downstreamPositionUs = playbackPositionUs;

        if (pendingDiscontinuities[track]) {
            pendingDiscontinuities[track] = false;
            return DISCONTINUITY_READ;
        }

        if (onlyReadDiscontinuity) {
            return NOTHING_READ;
        }

        if (isPendingReset()) {
            maybeThrowLoadableException();
            return NOTHING_READ;
        }

        HlsExtractorWrapper extractor = getCurrentExtractor();
        if (!extractor.isPrepared()) {
            maybeThrowLoadableException();
            return NOTHING_READ;
        }

        if (downstreamFormat == null || !downstreamFormat.equals(extractor.format)) {
            // Notify a change in the downstream format.
            notifyDownstreamFormatChanged(extractor.format, extractor.trigger, extractor.startTimeUs);
            downstreamFormat = extractor.format;
        }

        if (extractors.size() > 1) {
            // If there's more than one extractor, attempt to configure a seamless splice from the
            // current one to the next one.
            extractor.configureSpliceTo(extractors.get(1));
        }

        int extractorIndex = 0;
        while (extractors.size() > extractorIndex + 1 && !extractor.hasSamples(track)) {
            // We're finished reading from the extractor for this particular track, so advance to the
            // next one for the current read.
            extractor = extractors.get(++extractorIndex);
            if (!extractor.isPrepared()) {
                maybeThrowLoadableException();
                return NOTHING_READ;
            }
        }

        MediaFormat mediaFormat = extractor.getMediaFormat(track);
        if (mediaFormat != null && !mediaFormat.equals(downstreamMediaFormats[track], true)) {
            chunkSource.getMaxVideoDimensions(mediaFormat);
            formatHolder.format = mediaFormat;
            downstreamMediaFormats[track] = mediaFormat;
            return FORMAT_READ;
        }

        if (extractor.getSample(track, sampleHolder)) {
            boolean decodeOnly = frameAccurateSeeking && sampleHolder.timeUs < lastSeekPositionUs;
            sampleHolder.flags |= decodeOnly ? C.SAMPLE_FLAG_DECODE_ONLY : 0;
            return SAMPLE_READ;
        }

        if (loadingFinished) {
            return END_OF_STREAM;
        }

        maybeThrowLoadableException();
        return NOTHING_READ;
    }

    @Override
    public void seekToUs(long positionUs) {
        Assertions.checkState(prepared);
        Assertions.checkState(enabledTrackCount > 0);

        long currentPositionUs = isPendingReset() ? pendingResetPositionUs : downstreamPositionUs;
        downstreamPositionUs = positionUs;
        lastSeekPositionUs = positionUs;
        if (currentPositionUs == positionUs) {
            return;
        }

        // TODO: Optimize the seek for the case where the position is already buffered.
        downstreamPositionUs = positionUs;
        for (int i = 0; i < pendingDiscontinuities.length; i++) {
            pendingDiscontinuities[i] = true;
        }
        restartFrom(positionUs);
    }

    @Override
    public long getBufferedPositionUs() {
        Assertions.checkState(prepared);
        Assertions.checkState(enabledTrackCount > 0);
        if (isPendingReset()) {
            return pendingResetPositionUs;
        } else if (loadingFinished) {
            return TrackRenderer.END_OF_TRACK_US;
        } else {
            long largestParsedTimestampUs = extractors.getLast().getLargestParsedTimestampUs();
            return largestParsedTimestampUs == Long.MIN_VALUE ? downstreamPositionUs
                    : largestParsedTimestampUs;
        }
    }

    @Override
    public void release() {
        Assertions.checkState(remainingReleaseCount > 0);
        if (--remainingReleaseCount == 0 && loader != null) {
            loader.release();
            loader = null;
        }
    }

    @Override
    public void onLoadCompleted(Loadable loadable) {
        Assertions.checkState(loadable == currentLoadable);
        long now = SystemClock.elapsedRealtime();
        long loadDurationMs = now - currentLoadStartTimeMs;
        chunkSource.onChunkLoadCompleted(currentLoadable);
        if (isTsChunk(currentLoadable)) {
            Assertions.checkState(currentLoadable == currentTsLoadable);
            loadingFinished = currentTsLoadable.isLastChunk;
            previousTsLoadable = currentTsLoadable;
            notifyLoadCompleted(currentLoadable.bytesLoaded(), currentTsLoadable.type,
                    currentTsLoadable.trigger, currentTsLoadable.format, currentTsLoadable.startTimeUs,
                    currentTsLoadable.endTimeUs, now, loadDurationMs);
        } else {
            notifyLoadCompleted(currentLoadable.bytesLoaded(), currentLoadable.type,
                    currentLoadable.trigger, currentLoadable.format, -1, -1, now, loadDurationMs);
        }
        clearCurrentLoadable();
        if (enabledTrackCount > 0 || !prepared) {
            maybeStartLoading();
        }
    }

    @Override
    public void onLoadCanceled(Loadable loadable) {
        notifyLoadCanceled(currentLoadable.bytesLoaded());
        if (enabledTrackCount > 0) {
            restartFrom(pendingResetPositionUs);
        } else {
            clearState();
            loadControl.trimAllocator();
        }
    }

    @Override
    public void onLoadError(Loadable loadable, IOException e) {
        if (chunkSource.onChunkLoadError(currentLoadable, e)) {
            // Error handled by source.
            if (previousTsLoadable == null && !isPendingReset()) {
                pendingResetPositionUs = lastSeekPositionUs;
            }
            clearCurrentLoadable();
        } else {
            currentLoadableException = e;
            currentLoadableExceptionCount++;
            currentLoadableExceptionTimestamp = SystemClock.elapsedRealtime();
        }
        notifyLoadError(e);
        maybeStartLoading();
    }

    /**
     * Gets the current extractor from which samples should be read.
     * <p>
     * Calling this method discards extractors without any samples from the front of the queue. The
     * last extractor is retained even if it doesn't have any samples.
     * <p>
     * This method must not be called unless {@link #extractors} is non-empty.
     *
     * @return The current extractor from which samples should be read. Guaranteed to be non-null.
     */
    private HlsExtractorWrapper getCurrentExtractor() {
        HlsExtractorWrapper extractor = extractors.getFirst();
        while (extractors.size() > 1 && !haveSamplesForEnabledTracks(extractor)) {
            // We're finished reading from the extractor for all tracks, and so can discard it.
            extractors.removeFirst().clear();
            extractor = extractors.getFirst();
        }
        return extractor;
    }

    private void discardSamplesForDisabledTracks(HlsExtractorWrapper extractor, long timeUs) {
        if (!extractor.isPrepared()) {
            return;
        }
        for (int i = 0; i < trackEnabledStates.length; i++) {
            if (!trackEnabledStates[i]) {
                extractor.discardUntil(i, timeUs);
            }
        }
    }

    private boolean haveSamplesForEnabledTracks(HlsExtractorWrapper extractor) {
        if (!extractor.isPrepared()) {
            return false;
        }
        for (int i = 0; i < trackEnabledStates.length; i++) {
            if (trackEnabledStates[i] && extractor.hasSamples(i)) {
                return true;
            }
        }
        return false;
    }

    private void maybeThrowLoadableException() throws IOException {
        if (currentLoadableException != null && currentLoadableExceptionCount > minLoadableRetryCount) {
            throw currentLoadableException;
        }
    }

    private void restartFrom(long positionUs) {
        pendingResetPositionUs = positionUs;
        loadingFinished = false;
        if (loader.isLoading()) {
            loader.cancelLoading();
        } else {
            clearState();
            maybeStartLoading();
        }
    }

    private void clearState() {
        for (int i = 0; i < extractors.size(); i++) {
            extractors.get(i).clear();
        }
        extractors.clear();
        clearCurrentLoadable();
        previousTsLoadable = null;
    }

    private void clearCurrentLoadable() {
        currentTsLoadable = null;
        currentLoadable = null;
        currentLoadableException = null;
        currentLoadableExceptionCount = 0;
    }

    private void maybeStartLoading() {
        long now = SystemClock.elapsedRealtime();
        long nextLoadPositionUs = getNextLoadPositionUs();
        boolean isBackedOff = currentLoadableException != null;
        boolean loadingOrBackedOff = loader.isLoading() || isBackedOff;

        // Update the control with our current state, and determine whether we're the next loader.
        boolean nextLoader = loadControl.update(this, downstreamPositionUs, nextLoadPositionUs,
                loadingOrBackedOff, false);

        if (isBackedOff) {
            long elapsedMillis = now - currentLoadableExceptionTimestamp;
            if (elapsedMillis >= getRetryDelayMillis(currentLoadableExceptionCount)) {
                currentLoadableException = null;
                loader.startLoading(currentLoadable, this);
            }
            return;
        }

        if (loader.isLoading() || !nextLoader) {
            return;
        }

        Chunk nextLoadable = chunkSource.getChunkOperation(previousTsLoadable, pendingResetPositionUs,
                downstreamPositionUs);
        if (nextLoadable == null) {
            return;
        }

        currentLoadStartTimeMs = now;
        currentLoadable = nextLoadable;
        if (isTsChunk(currentLoadable)) {
            TsChunk tsChunk = (TsChunk) currentLoadable;
            if (isPendingReset()) {
                pendingResetPositionUs = NO_RESET_PENDING;
            }
            HlsExtractorWrapper extractorWrapper = tsChunk.extractorWrapper;
            if (extractors.isEmpty() || extractors.getLast() != extractorWrapper) {
                extractorWrapper.init(loadControl.getAllocator());
                extractors.addLast(extractorWrapper);
            }
            notifyLoadStarted(tsChunk.dataSpec.length, tsChunk.type, tsChunk.trigger, tsChunk.format,
                    tsChunk.startTimeUs, tsChunk.endTimeUs);
            currentTsLoadable = tsChunk;
        } else {
            notifyLoadStarted(currentLoadable.dataSpec.length, currentLoadable.type,
                    currentLoadable.trigger, currentLoadable.format, -1, -1);
        }
        loader.startLoading(currentLoadable, this);
    }

    /**
     * Gets the next load time, assuming that the next load starts where the previous chunk ended (or
     * from the pending reset time, if there is one).
     */
    private long getNextLoadPositionUs() {
        if (isPendingReset()) {
            return pendingResetPositionUs;
        } else {
            return currentTsLoadable != null
                    ? (currentTsLoadable.isLastChunk ? -1 : currentTsLoadable.endTimeUs)
                    : (previousTsLoadable.isLastChunk ? -1 : previousTsLoadable.endTimeUs);
        }
    }

    private boolean isTsChunk(Chunk chunk) {
        return chunk instanceof TsChunk;
    }

    private boolean isPendingReset() {
        return pendingResetPositionUs != NO_RESET_PENDING;
    }

    private long getRetryDelayMillis(long errorCount) {
        return Math.min((errorCount - 1) * 1000, 5000);
    }

    protected final int usToMs(long timeUs) {
        return (int) (timeUs / 1000);
    }

    private void notifyLoadStarted(final long length, final int type, final int trigger,
                                   final Format format, final long mediaStartTimeUs, final long mediaEndTimeUs) {
        if (eventHandler != null && eventListener != null) {
            eventHandler.post(new Runnable()  {
                @Override
                public void run() {
                    eventListener.onLoadStarted(eventSourceId, length, type, trigger, format,
                            usToMs(mediaStartTimeUs), usToMs(mediaEndTimeUs));
                }
            });
        }
    }

    private void notifyLoadCompleted(final long bytesLoaded, final int type, final int trigger,
                                     final Format format, final long mediaStartTimeUs, final long mediaEndTimeUs,
                                     final long elapsedRealtimeMs, final long loadDurationMs) {
        if (eventHandler != null && eventListener != null) {
            eventHandler.post(new Runnable()  {
                @Override
                public void run() {
                    eventListener.onLoadCompleted(eventSourceId, bytesLoaded, type, trigger, format,
                            usToMs(mediaStartTimeUs), usToMs(mediaEndTimeUs), elapsedRealtimeMs, loadDurationMs);
                }
            });
        }
    }

    private void notifyLoadCanceled(final long bytesLoaded) {
        if (eventHandler != null && eventListener != null) {
            eventHandler.post(new Runnable()  {
                @Override
                public void run() {
                    eventListener.onLoadCanceled(eventSourceId, bytesLoaded);
                }
            });
        }
    }

    private void notifyLoadError(final IOException e) {
        if (eventHandler != null && eventListener != null) {
            eventHandler.post(new Runnable()  {
                @Override
                public void run() {
                    eventListener.onLoadError(eventSourceId, e);
                }
            });
        }
    }

    private void notifyDownstreamFormatChanged(final Format format, final int trigger,
                                               final long positionUs) {
        if (eventHandler != null && eventListener != null) {
            eventHandler.post(new Runnable()  {
                @Override
                public void run() {
                    eventListener.onDownstreamFormatChanged(eventSourceId, format, trigger,
                            usToMs(positionUs));
                }
            });
        }
    }

}

