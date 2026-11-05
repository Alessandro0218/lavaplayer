package com.sedmelluq.discord.lavaplayer.source.soundcloud;

import com.sedmelluq.discord.lavaplayer.container.playlists.HlsStreamSegment;
import com.sedmelluq.discord.lavaplayer.tools.http.HttpStreamTools;
import com.sedmelluq.discord.lavaplayer.tools.io.ChainedInputStream;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.NonSeekableInputStream;
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.apache.http.client.methods.HttpGet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

public class SoundCloudM3uAudioTrack extends DelegatedAudioTrack {
    private static final Logger log = LoggerFactory.getLogger(SoundCloudM3uAudioTrack.class);

    private static final long SEGMENT_UPDATE_INTERVAL = TimeUnit.MINUTES.toMillis(10);

    private final HttpInterface httpInterface;
    private final SoundCloudM3uInfo m3uInfo;

    public SoundCloudM3uAudioTrack(AudioTrackInfo trackInfo, HttpInterface httpInterface, SoundCloudM3uInfo m3uInfo) {
        super(trackInfo);
        this.httpInterface = httpInterface;
        this.m3uInfo = m3uInfo;
    }

    @Override
    public void process(LocalAudioTrackExecutor localExecutor) throws Exception {
        try (SegmentTracker segmentTracker = createSegmentTracker()) {
            segmentTracker.decoder.prepareStream(true);

            localExecutor.executeProcessingLoop(() -> segmentTracker.decoder.playStream(
                localExecutor.getProcessingContext(),
                segmentTracker.streamStartPosition,
                segmentTracker.desiredPosition
            ), segmentTracker::seekToTimecode, true);
        }
    }

    private SoundCloudSegmentList loadSegments() throws IOException {
        String playbackUrl = SoundCloudHelper.loadPlaybackUrl(httpInterface, m3uInfo.lookupUrl);
        String[] lines = HttpClientTools.fetchResponseLines(httpInterface, new HttpGet(playbackUrl), "stream segments list");
        return SoundCloudSegmentList.parse(lines, playbackUrl);
    }

    private SegmentTracker createSegmentTracker() throws IOException {
        SegmentTracker tracker = new SegmentTracker(new SoundCloudSegmentCursor(loadSegments()));
        tracker.setupDecoder(m3uInfo.decoderFactory);
        return tracker;
    }

    private class SegmentTracker implements AutoCloseable {
        private final SoundCloudSegmentCursor cursor;
        private final List<HlsStreamSegment> segments;
        private long desiredPosition = 0;
        private long streamStartPosition = 0;
        private long lastUpdate;
        private SoundCloudSegmentDecoder decoder;

        private SegmentTracker(SoundCloudSegmentCursor cursor) {
            this.cursor = cursor;
            this.segments = cursor.segments();
            this.lastUpdate = System.currentTimeMillis();
        }

        private void setupDecoder(SoundCloudSegmentDecoder.Factory factory) {
            decoder = factory.create(this::createChainedStream);
        }

        private SeekableInputStream createChainedStream() {
            cursor.startChain();
            return new NonSeekableInputStream(new ChainedInputStream(this::getNextStream));
        }

        private void seekToTimecode(long timecode) throws IOException {
            long segmentTimecode = 0;

            for (int i = 0; i < segments.size(); i++) {
                Long duration = segments.get(i).duration;

                if (duration == null) {
                    break;
                }

                long nextTimecode = segmentTimecode + duration;

                if (timecode >= segmentTimecode && timecode < nextTimecode) {
                    seekToSegment(i, timecode, segmentTimecode);
                    return;
                }

                segmentTimecode = nextTimecode;
            }

            seekToEnd();
        }

        private void seekToSegment(int index, long requestedTimecode, long segmentTimecode) throws IOException {
            decoder.resetStream();

            cursor.seekToSegment(index);
            desiredPosition = requestedTimecode;
            streamStartPosition = segmentTimecode;

            decoder.prepareStream(streamStartPosition == 0);
        }

        private void seekToEnd() throws IOException {
            decoder.resetStream();

            cursor.seekToEnd();
        }

        private InputStream getNextStream() {
            if (cursor.hasMoreSegments()) {
                checkSegmentListUpdate();
            }

            String url = cursor.nextUrl();

            if (url == null) {
                return null;
            }

            return HttpStreamTools.streamContent(httpInterface, new HttpGet(url));
        }

        private void updateSegmentList() {
            try {
                SoundCloudSegmentList refreshed = loadSegments();
                List<HlsStreamSegment> newSegments = refreshed.segments;

                if (newSegments.size() != segments.size()) {
                    log.error("For {}, received different number of segments on update, skipping.", trackInfo.identifier);
                    return;
                }

                for (int i = 0; i < segments.size(); i++) {
                    if (!Objects.equals(newSegments.get(i).duration, segments.get(i).duration)) {
                        log.error("For {}, segment {} has different length than previously on update.", trackInfo.identifier, i);
                        return;
                    }
                }

                cursor.replace(refreshed);
            } catch (Exception e) {
                log.error("For {}, failed to update segment list, skipping.", trackInfo.identifier, e);
            }
        }

        private void checkSegmentListUpdate() {
            long now = System.currentTimeMillis();
            long delta = now - lastUpdate;

            if (delta > SEGMENT_UPDATE_INTERVAL) {
                log.debug("For {}, {}ms has passed since last segment update, updating", trackInfo.identifier, delta);

                updateSegmentList();
                lastUpdate = now;
            }
        }

        @Override
        public void close() throws Exception {
            decoder.resetStream();
        }
    }
}
