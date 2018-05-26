package com.example.sairam.myapplication.exoplayer;

import com.android.volley.Request;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.StringRequest;
import com.google.android.exoplayer.C;
import com.google.android.exoplayer.MediaFormat;
import com.google.android.exoplayer.audio.AudioCapabilities;
import com.google.android.exoplayer.chunk.BaseChunkSampleSourceEventListener;
import com.google.android.exoplayer.chunk.Chunk;
import com.google.android.exoplayer.chunk.DataChunk;
import com.google.android.exoplayer.chunk.Format;
import com.google.android.exoplayer.extractor.Extractor;
import com.google.android.exoplayer.extractor.ts.AdtsExtractor;
import com.google.android.exoplayer.extractor.ts.TsExtractor;
import com.google.android.exoplayer.hls.HlsChunkSource;
import com.google.android.exoplayer.hls.HlsExtractorWrapper;
import com.google.android.exoplayer.hls.HlsMasterPlaylist;
import com.google.android.exoplayer.hls.HlsMediaPlaylist;
import com.google.android.exoplayer.hls.HlsPlaylist;
import com.google.android.exoplayer.hls.HlsPlaylistParser;
import com.google.android.exoplayer.hls.HlsSampleSource;
import com.google.android.exoplayer.hls.TsChunk;
import com.google.android.exoplayer.hls.Variant;
import com.google.android.exoplayer.upstream.BandwidthMeter;
import com.google.android.exoplayer.upstream.DataSource;
import com.google.android.exoplayer.upstream.DataSpec;
import com.google.android.exoplayer.upstream.HttpDataSource.InvalidResponseCodeException;
import com.google.android.exoplayer.util.Assertions;
import com.google.android.exoplayer.util.UriUtil;
import com.google.android.exoplayer.util.Util;

import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.support.annotation.RequiresApi;
import android.text.TextUtils;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A temporary test source of HLS chunks.
 * <p>
 * TODO: Figure out whether this should merge with the chunk package, or whether the hls
 * implementation is going to naturally diverge.
 */
public class HlsChunkSourcea extends HlsChunkSource{

    public interface EventListener extends BaseChunkSampleSourceEventListener {}

    public static final int ADAPTIVE_MODE_NONE = 0;

    public static final int ADAPTIVE_MODE_SPLICE = 1;

    public static final int ADAPTIVE_MODE_ABRUPT = 3;

    public static final long DEFAULT_MIN_BUFFER_TO_SWITCH_UP_MS = 5000;

    public static final long DEFAULT_MAX_BUFFER_TO_SWITCH_DOWN_MS = 20000;
    public static final long DEFAULT_PLAYLIST_BLACKLIST_MS = 60000;

    private static final String TAG = "HlsChunkSource";
    private static final String AAC_FILE_EXTENSION = ".aac";
    private static final float BANDWIDTH_FRACTION = 0.8f;

    private final DataSource dataSource;
    private final HlsPlaylistParser playlistParser;
    private final BandwidthMeter bandwidthMeter;
    private final int adaptiveMode;
    private final String baseUri;
    private final int maxWidth;
    private final int maxHeight;
    private final long minBufferDurationToSwitchUpUs;
    private final long maxBufferDurationToSwitchDownUs;
    private final AudioCapabilities audioCapabilities;
    private final Variant[] variants;
    private final HlsMediaPlaylist[] variantPlaylists;
    private final long[] variantLastPlaylistLoadTimesMs;
    private final long[] variantBlacklistTimes;
    private int selectedVariantIndex;

    private byte[] scratchSpace;
    private boolean live;
    private long durationUs;

    private Uri encryptionKeyUri;
    private byte[] encryptionKey;
    private String encryptionIvString;
    private byte[] encryptionIv;

    public HlsChunkSourcea(DataSource dataSource, String playlistUrl, HlsPlaylist playlist,
                          BandwidthMeter bandwidthMeter, int[] variantIndices, int adaptiveMode,
                          AudioCapabilities audioCapabilities) {
        this(dataSource, playlistUrl, playlist, bandwidthMeter, variantIndices, adaptiveMode,
                DEFAULT_MIN_BUFFER_TO_SWITCH_UP_MS, DEFAULT_MAX_BUFFER_TO_SWITCH_DOWN_MS,
                audioCapabilities);
    }
    public HlsChunkSourcea(DataSource dataSource, String playlistUrl, HlsPlaylist playlist,
                          BandwidthMeter bandwidthMeter, int[] variantIndices, int adaptiveMode,
                          long minBufferDurationToSwitchUpMs, long maxBufferDurationToSwitchDownMs,
                          AudioCapabilities audioCapabilities) {
        super(dataSource,playlistUrl,playlist,bandwidthMeter,variantIndices,adaptiveMode,minBufferDurationToSwitchUpMs,maxBufferDurationToSwitchDownMs,audioCapabilities);
        this.dataSource = dataSource;
        this.bandwidthMeter = bandwidthMeter;
        this.adaptiveMode = adaptiveMode;
        this.audioCapabilities = audioCapabilities;
        minBufferDurationToSwitchUpUs = minBufferDurationToSwitchUpMs * 1000;
        maxBufferDurationToSwitchDownUs = maxBufferDurationToSwitchDownMs * 1000;
        baseUri = playlist.baseUri;
        playlistParser = new HlsPlaylistParser();

        if (playlist.type == HlsPlaylist.TYPE_MEDIA) {
            variants = new Variant[] {new Variant(0, playlistUrl, 0, null, -1, -1)};
            variantPlaylists = new HlsMediaPlaylist[1];
            variantLastPlaylistLoadTimesMs = new long[1];
            variantBlacklistTimes = new long[1];
            setMediaPlaylist(0, (HlsMediaPlaylist) playlist);
            // We won't be adapting between different variants.
            maxWidth = -1;
            maxHeight = -1;
        } else {
            List<Variant> masterPlaylistVariants = ((HlsMasterPlaylist) playlist).variants;
            variants = buildOrderedVariants(masterPlaylistVariants, variantIndices);
            variantPlaylists = new HlsMediaPlaylist[variants.length];
            variantLastPlaylistLoadTimesMs = new long[variants.length];
            variantBlacklistTimes = new long[variants.length];
            int maxWidth = -1;
            int maxHeight = -1;
            // Select the variant that comes first in their original order in the master playlist.
            int minOriginalVariantIndex = Integer.MAX_VALUE;
            for (int i = 0; i < variants.length; i++) {
                int originalVariantIndex = masterPlaylistVariants.indexOf(variants[i]);
                if (originalVariantIndex < minOriginalVariantIndex) {
                    minOriginalVariantIndex = originalVariantIndex;
                    selectedVariantIndex = i;
                }
                Format variantFormat = variants[i].format;
                maxWidth = Math.max(variantFormat.width, maxWidth);
                maxHeight = Math.max(variantFormat.height, maxHeight);
            }
            if (variants.length <= 1 || adaptiveMode == ADAPTIVE_MODE_NONE) {
                // We won't be adapting between different variants.
                this.maxWidth = -1;
                this.maxHeight = -1;
            } else {
                // We will be adapting between different variants.
                // TODO: We should allow the default values to be passed through the constructor.
                this.maxWidth = maxWidth > 0 ? maxWidth : 1920;
                this.maxHeight = maxHeight > 0 ? maxHeight : 1080;
            }
        }
    }

    public long getDurationUs() {
        return live ? C.UNKNOWN_TIME_US : durationUs;
    }

    public void getMaxVideoDimensions(MediaFormat out) {
        if (maxWidth == -1 || maxHeight == -1) {
            // Not adaptive.
            return;
        }
        out.setMaxVideoDimensions(maxWidth, maxHeight);
    }
    public Chunk getChunkOperation(TsChunk previousTsChunk, long seekPositionUs,
                                   long playbackPositionUs) {
        int nextVariantIndex;
        boolean switchingVariantSpliced;
        if (adaptiveMode == ADAPTIVE_MODE_NONE) {
            nextVariantIndex = selectedVariantIndex;
            switchingVariantSpliced = false;
        } else {
            nextVariantIndex = getNextVariantIndex(previousTsChunk, playbackPositionUs);
            switchingVariantSpliced = previousTsChunk != null
                    && !variants[nextVariantIndex].format.equals(previousTsChunk.format)
                    && adaptiveMode == ADAPTIVE_MODE_SPLICE;
        }

        HlsMediaPlaylist mediaPlaylist = variantPlaylists[nextVariantIndex];
        if (mediaPlaylist == null) {
            return newMediaPlaylistChunk(nextVariantIndex);
        }

        selectedVariantIndex = nextVariantIndex;
        int chunkMediaSequence = 0;
        boolean liveDiscontinuity = false;
        if (live) {
            if (previousTsChunk == null) {
                chunkMediaSequence = getLiveStartChunkMediaSequence(nextVariantIndex);
            } else {
                chunkMediaSequence = switchingVariantSpliced
                        ? previousTsChunk.chunkIndex : previousTsChunk.chunkIndex + 1;
                if (chunkMediaSequence < mediaPlaylist.mediaSequence) {
                    chunkMediaSequence = getLiveStartChunkMediaSequence(nextVariantIndex);
                    liveDiscontinuity = true;
                }
            }
        } else {
            if (previousTsChunk == null) {
                chunkMediaSequence = Util.binarySearchFloor(mediaPlaylist.segments, seekPositionUs, true,
                        true) + mediaPlaylist.mediaSequence;
            } else {
                chunkMediaSequence = switchingVariantSpliced
                        ? previousTsChunk.chunkIndex : previousTsChunk.chunkIndex + 1;
            }
        }

        int chunkIndex = chunkMediaSequence - mediaPlaylist.mediaSequence;
        if (chunkIndex >= mediaPlaylist.segments.size()) {
            if (mediaPlaylist.live && shouldRerequestMediaPlaylist(nextVariantIndex)) {
                return newMediaPlaylistChunk(nextVariantIndex);
            } else {
                return null;
            }
        }

        HlsMediaPlaylist.Segment segment = mediaPlaylist.segments.get(chunkIndex);
        Uri chunkUri = UriUtil.resolveToUri(mediaPlaylist.baseUri, segment.url);

        if (segment.isEncrypted) {
            Uri keyUri = UriUtil.resolveToUri(mediaPlaylist.baseUri, segment.encryptionKeyUri);
            if (!keyUri.equals(encryptionKeyUri)) {
                Chunk toReturn = newEncryptionKeyChunk(keyUri, segment.encryptionIV, selectedVariantIndex);
                return toReturn;
            }
            if (!Util.areEqual(segment.encryptionIV, encryptionIvString)) {
                setEncryptionData(keyUri, segment.encryptionIV, encryptionKey);
            }
        } else {
            clearEncryptionData();
        }
        DataSpec dataSpec = new DataSpec(chunkUri, segment.byterangeOffset, segment.byterangeLength,
                null);
        long startTimeUs;
        if (live) {
            if (previousTsChunk == null) {
                startTimeUs = 0;
            } else if (switchingVariantSpliced) {
                startTimeUs = previousTsChunk.startTimeUs;
            } else {
                startTimeUs = previousTsChunk.endTimeUs;
            }
        } else /* Not live */ {
            startTimeUs = segment.startTimeUs;
        }
        long endTimeUs = startTimeUs + (long) (segment.durationSecs * C.MICROS_PER_SECOND);
        boolean isLastChunk = !mediaPlaylist.live && chunkIndex == mediaPlaylist.segments.size() - 1;
        int trigger = Chunk.TRIGGER_UNSPECIFIED;
        Format format = variants[selectedVariantIndex].format;

        // Configure the extractor that will read the chunk.
        HlsExtractorWrapper extractorWrapper;
        if (previousTsChunk == null || segment.discontinuity || !format.equals(previousTsChunk.format)
                || liveDiscontinuity) {
            Extractor extractor = chunkUri.getLastPathSegment().endsWith(AAC_FILE_EXTENSION)
                    ? new AdtsExtractor(startTimeUs)
                    : new TsExtractor(startTimeUs, audioCapabilities);
            extractorWrapper = new HlsExtractorWrapper(trigger, format, startTimeUs, extractor,
                    switchingVariantSpliced);
        } else {
            extractorWrapper = previousTsChunk.extractorWrapper;
        }

        return new TsChunk(dataSource, dataSpec, trigger, format, startTimeUs, endTimeUs,
                chunkMediaSequence, isLastChunk, extractorWrapper, encryptionKey,
                encryptionIv);
    }
    public void onChunkLoadCompleted(Chunk chunk) {
        if (chunk instanceof MediaPlaylistChunk) {
            MediaPlaylistChunk mediaPlaylistChunk = (MediaPlaylistChunk) chunk;
            scratchSpace = mediaPlaylistChunk.getDataHolder();
            setMediaPlaylist(mediaPlaylistChunk.variantIndex, mediaPlaylistChunk.getResult());
        } else if (chunk instanceof EncryptionKeyChunk) {
            EncryptionKeyChunk encryptionKeyChunk = (EncryptionKeyChunk) chunk;
            scratchSpace = encryptionKeyChunk.getDataHolder();
            setEncryptionData(encryptionKeyChunk.dataSpec.uri, encryptionKeyChunk.iv,
                    encryptionKeyChunk.getResult());
        }
    }




    public boolean onChunkLoadError(Chunk chunk, IOException e) {
        if (chunk.bytesLoaded() == 0
                && (chunk instanceof TsChunk || chunk instanceof MediaPlaylistChunk
                || chunk instanceof EncryptionKeyChunk)
                && (e instanceof InvalidResponseCodeException)) {
            InvalidResponseCodeException responseCodeException = (InvalidResponseCodeException) e;
            int responseCode = responseCodeException.responseCode;
            if (responseCode == 404 || responseCode == 410) {
                int variantIndex;
                if (chunk instanceof TsChunk) {
                    TsChunk tsChunk = (TsChunk) chunk;
                    variantIndex = getVariantIndex(tsChunk.format);
                } else if (chunk instanceof MediaPlaylistChunk) {
                    MediaPlaylistChunk playlistChunk = (MediaPlaylistChunk) chunk;
                    variantIndex = playlistChunk.variantIndex;
                } else {
                    EncryptionKeyChunk encryptionChunk = (EncryptionKeyChunk) chunk;
                    variantIndex = encryptionChunk.variantIndex;
                }
                boolean alreadyBlacklisted = variantBlacklistTimes[variantIndex] != 0;
                variantBlacklistTimes[variantIndex] = SystemClock.elapsedRealtime();
                if (alreadyBlacklisted) {
                    // The playlist was already blacklisted.
                    Log.w(TAG, "Already blacklisted variant (" + responseCode + "): "
                            + chunk.dataSpec.uri);
                    return false;
                } else if (!allVariantsBlacklisted()) {
                    // We've handled the 404/410 by blacklisting the variant.
                    Log.w(TAG, "Blacklisted variant (" + responseCode + "): "
                            + chunk.dataSpec.uri);
                    return true;
                } else {
                    // This was the last non-blacklisted playlist. Don't blacklist it.
                    Log.w(TAG, "Final variant not blacklisted (" + responseCode + "): "
                            + chunk.dataSpec.uri);
                    variantBlacklistTimes[variantIndex] = 0;
                    return false;
                }
            }
        }
        return false;
    }

    private int getNextVariantIndex(TsChunk previousTsChunk, long playbackPositionUs) {
        clearStaleBlacklistedVariants();
        long bitrateEstimate = bandwidthMeter.getBitrateEstimate();
        if (variantBlacklistTimes[selectedVariantIndex] != 0) {
            // The current variant has been blacklisted, so we have no choice but to re-evaluate.
            return getVariantIndexForBandwidth(bitrateEstimate);
        }
        if (previousTsChunk == null) {
            // Don't consider switching if we don't have a previous chunk.
            return selectedVariantIndex;
        }
        if (bitrateEstimate == BandwidthMeter.NO_ESTIMATE) {
            // Don't consider switching if we don't have a bandwidth estimate.
            return selectedVariantIndex;
        }
        int idealIndex = getVariantIndexForBandwidth(bitrateEstimate);
        if (idealIndex == selectedVariantIndex) {
            // We're already using the ideal variant.
            return selectedVariantIndex;
        }
        // We're not using the ideal variant for the available bandwidth, but only switch if the
        // conditions are appropriate.
        long bufferedPositionUs = adaptiveMode == ADAPTIVE_MODE_SPLICE ? previousTsChunk.startTimeUs
                : previousTsChunk.endTimeUs;
        long bufferedUs = bufferedPositionUs - playbackPositionUs;
        if (variantBlacklistTimes[selectedVariantIndex] != 0
                || (idealIndex > selectedVariantIndex && bufferedUs < maxBufferDurationToSwitchDownUs)
                || (idealIndex < selectedVariantIndex && bufferedUs > minBufferDurationToSwitchUpUs)) {
            // Switch variant.
            return idealIndex;
        }
        // Stick with the current variant for now.
        return selectedVariantIndex;
    }

    private int getVariantIndexForBandwidth(long bitrateEstimate) {
        if (bitrateEstimate == BandwidthMeter.NO_ESTIMATE) {
            // Select the lowest quality.
            bitrateEstimate = 0;
        }
        int effectiveBitrate = (int) (bitrateEstimate * BANDWIDTH_FRACTION);
        int lowestQualityEnabledVariantIndex = -1;
        for (int i = 0; i < variants.length; i++) {
            if (variantBlacklistTimes[i] == 0) {
                if (variants[i].format.bitrate <= effectiveBitrate) {
                    return i;
                }
                lowestQualityEnabledVariantIndex = i;
            }
        }
        Assertions.checkState(lowestQualityEnabledVariantIndex != -1);
        return lowestQualityEnabledVariantIndex;
    }

    private boolean shouldRerequestMediaPlaylist(int nextVariantIndex) {
        // Don't re-request media playlist more often than one-half of the target duration.
        HlsMediaPlaylist mediaPlaylist = variantPlaylists[nextVariantIndex];
        long timeSinceLastMediaPlaylistLoadMs =
                SystemClock.elapsedRealtime() - variantLastPlaylistLoadTimesMs[nextVariantIndex];
        return timeSinceLastMediaPlaylistLoadMs >= (mediaPlaylist.targetDurationSecs * 1000) / 2;
    }

    private int getLiveStartChunkMediaSequence(int variantIndex) {
        // For live start playback from the third chunk from the end.
        HlsMediaPlaylist mediaPlaylist = variantPlaylists[variantIndex];
        int chunkIndex = mediaPlaylist.segments.size() > 3 ? mediaPlaylist.segments.size() - 3 : 0;
        return chunkIndex + mediaPlaylist.mediaSequence;
    }

    private MediaPlaylistChunk newMediaPlaylistChunk(int variantIndex) {
        Uri mediaPlaylistUri = UriUtil.resolveToUri(baseUri, variants[variantIndex].url);
        DataSpec dataSpec = new DataSpec(mediaPlaylistUri, 0, C.LENGTH_UNBOUNDED, null,
                DataSpec.FLAG_ALLOW_GZIP);
        return new MediaPlaylistChunk(dataSource, dataSpec, scratchSpace, playlistParser, variantIndex,
                mediaPlaylistUri.toString());
    }

    private EncryptionKeyChunk newEncryptionKeyChunk(Uri keyUri, String iv, int variantIndex) {
        DataSpec dataSpec = new DataSpec(keyUri, 0, C.LENGTH_UNBOUNDED, null, DataSpec.FLAG_ALLOW_GZIP);
        return new EncryptionKeyChunk(dataSource, dataSpec, scratchSpace, iv, variantIndex);
    }

    private void setEncryptionData(Uri keyUri, String iv, byte[] secretKey) {
        String trimmedIv;
        if (iv.toLowerCase(Locale.getDefault()).startsWith("0x")) {
            trimmedIv = iv.substring(2);
        } else {
            trimmedIv = iv;
        }

        byte[] ivData = new BigInteger(trimmedIv, 16).toByteArray();
        byte[] ivDataWithPadding = new byte[16];
        int offset = ivData.length > 16 ? ivData.length - 16 : 0;
        System.arraycopy(ivData, offset, ivDataWithPadding, ivDataWithPadding.length - ivData.length
                + offset, ivData.length - offset);

        encryptionKeyUri = keyUri;
        Log.d("encryptionKeyUri", String.valueOf(encryptionKeyUri));
        encryptionKey = secretKey;
        Log.d("encryptionKey", String.valueOf(encryptionKey));
        encryptionIvString = iv;
        Log.d("encryptionIvString", String.valueOf(encryptionIvString));
        encryptionIv = ivDataWithPadding;
        Log.d("encryptionIv", String.valueOf(encryptionIv));
    }

    private void clearEncryptionData() {
        encryptionKeyUri = null;
        encryptionKey = null;
        encryptionIvString = null;
        encryptionIv = null;
    }

    private void setMediaPlaylist(int variantIndex, HlsMediaPlaylist mediaPlaylist) {
        variantLastPlaylistLoadTimesMs[variantIndex] = SystemClock.elapsedRealtime();
        variantPlaylists[variantIndex] = mediaPlaylist;
        live |= mediaPlaylist.live;
        durationUs = mediaPlaylist.durationUs;
    }

    /**
     * Selects a list of variants to use, returning them in order of decreasing bandwidth.
     *
     * @param originalVariants The original list of variants.
     * @param originalVariantIndices Indices of variants that in the original list that can be
     *     considered, or null to allow all variants to be considered.
     * @return The set of enabled variants in decreasing bandwidth order.
     */
    private static Variant[] buildOrderedVariants(List<Variant> originalVariants,
                                                  int[] originalVariantIndices) {
        ArrayList<Variant> enabledVariantList = new ArrayList<>();
        if (originalVariantIndices != null) {
            for (int i = 0; i < originalVariantIndices.length; i++) {
                enabledVariantList.add(originalVariants.get(originalVariantIndices[i]));
            }
        } else {
            // If variantIndices is null then all variants are initially considered.
            enabledVariantList.addAll(originalVariants);
        }

        ArrayList<Variant> definiteVideoVariants = new ArrayList<>();
        ArrayList<Variant> definiteAudioOnlyVariants = new ArrayList<>();
        for (int i = 0; i < enabledVariantList.size(); i++) {
            Variant variant = enabledVariantList.get(i);
            if (variant.format.height > 0 || variantHasExplicitCodecWithPrefix(variant, "avc")) {
                definiteVideoVariants.add(variant);
            } else if (variantHasExplicitCodecWithPrefix(variant, "mp4a")) {
                definiteAudioOnlyVariants.add(variant);
            }
        }

        if (!definiteVideoVariants.isEmpty()) {
            // We've identified some variants as definitely containing video. Assume variants within the
            // master playlist are marked consistently, and hence that we have the full set. Filter out
            // any other variants, which are likely to be audio only.
            enabledVariantList = definiteVideoVariants;
        } else if (definiteAudioOnlyVariants.size() < enabledVariantList.size()) {
            // We've identified some variants, but not all, as being audio only. Filter them out to leave
            // the remaining variants, which are likely to contain video.
            enabledVariantList.removeAll(definiteAudioOnlyVariants);
        } else {
            // Leave the enabled variants unchanged. They're likely either all video or all audio.
        }

        Variant[] enabledVariants = new Variant[enabledVariantList.size()];
        enabledVariantList.toArray(enabledVariants);
        Arrays.sort(enabledVariants, new Comparator<Variant>() {
            private final Comparator<Format> formatComparator =
                    new Format.DecreasingBandwidthComparator();
            @Override
            public int compare(Variant first, Variant second) {
                return formatComparator.compare(first.format, second.format);
            }
        });

        return enabledVariants;
    }

    private static boolean variantHasExplicitCodecWithPrefix(Variant variant, String prefix) {
        String codecs = variant.format.codecs;
        if (TextUtils.isEmpty(codecs)) {
            return false;
        }
        String[] codecArray = codecs.split("(\\s*,\\s*)|(\\s*$)");
        for (int i = 0; i < codecArray.length; i++) {
            if (codecArray[i].startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean allVariantsBlacklisted() {
        for (int i = 0; i < variantBlacklistTimes.length; i++) {
            if (variantBlacklistTimes[i] == 0) {
                return false;
            }
        }
        return true;
    }

    private void clearStaleBlacklistedVariants() {
        long currentTime = SystemClock.elapsedRealtime();
        for (int i = 0; i < variantBlacklistTimes.length; i++) {
            if (variantBlacklistTimes[i] != 0
                    && currentTime - variantBlacklistTimes[i] > DEFAULT_PLAYLIST_BLACKLIST_MS) {
                variantBlacklistTimes[i] = 0;
            }
        }
    }

    private int getVariantIndex(Format format) {
        for (int i = 0; i < variants.length; i++) {
            if (variants[i].format.equals(format)) {
                return i;
            }
        }
        // Should never happen.
        throw new IllegalStateException("Invalid format: " + format);
    }

    private static class MediaPlaylistChunk extends DataChunk {

        public final int variantIndex;

        private final HlsPlaylistParser playlistParser;
        private final String playlistUrl;

        private HlsMediaPlaylist result;

        public MediaPlaylistChunk(DataSource dataSource, DataSpec dataSpec, byte[] scratchSpace,
                                  HlsPlaylistParser playlistParser, int variantIndex, String playlistUrl) {
            super(dataSource, dataSpec, Chunk.TYPE_MANIFEST, Chunk.TRIGGER_UNSPECIFIED, null,
                    scratchSpace);
            this.variantIndex = variantIndex;
            this.playlistParser = playlistParser;
            this.playlistUrl = playlistUrl;
        }

        @Override
        protected void consume(byte[] data, int limit) throws IOException {
            result = (HlsMediaPlaylist) playlistParser.parse(playlistUrl,
                    new ByteArrayInputStream(data, 0, limit));
        }

        public HlsMediaPlaylist getResult() {
            return result;
        }

    }

    private static class EncryptionKeyChunk extends DataChunk {

        public final String iv;
        public final int variantIndex;

        private byte[] result;

        public EncryptionKeyChunk(DataSource dataSource, DataSpec dataSpec, byte[] scratchSpace,
                                  String iv, int variantIndex) {
            super(dataSource, dataSpec, Chunk.TYPE_DRM, Chunk.TRIGGER_UNSPECIFIED, null, scratchSpace);
            this.iv = iv;
            this.variantIndex = variantIndex;
        }

        @Override
        protected void consume(byte[] data, int limit) {
            result = Arrays.copyOf(data, limit);
        }

        public byte[] getResult() {
            return result;
        }

    }

}

