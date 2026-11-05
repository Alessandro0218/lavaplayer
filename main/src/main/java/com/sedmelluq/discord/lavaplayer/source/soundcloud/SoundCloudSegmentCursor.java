package com.sedmelluq.discord.lavaplayer.source.soundcloud;

import com.sedmelluq.discord.lavaplayer.container.playlists.HlsStreamSegment;

import java.util.List;

/**
 * Which URL a chained segment stream reads next. Every chain starts with the initialization segment when the playlist
 * has one, so a stream opened after a seek is as decodable as the first one.
 */
class SoundCloudSegmentCursor {
    private final List<HlsStreamSegment> segments;
    private String initSegmentUrl;
    private int segmentIndex = 0;
    private boolean initSegmentPending = false;

    SoundCloudSegmentCursor(SoundCloudSegmentList segmentList) {
        this.segments = segmentList.segments;
        this.initSegmentUrl = segmentList.initSegmentUrl;
    }

    List<HlsStreamSegment> segments() {
        return segments;
    }

    /**
     * A new chained stream is starting at the current segment.
     */
    void startChain() {
        initSegmentPending = initSegmentUrl != null;
    }

    void seekToSegment(int index) {
        segmentIndex = index;
    }

    void seekToEnd() {
        segmentIndex = segments.size();
    }

    /**
     * Swaps in the URLs of a refreshed playlist (they are signed and expire); the caller has checked the segments line
     * up with the current ones.
     */
    void replace(SoundCloudSegmentList refreshed) {
        for (int i = 0; i < segments.size(); i++) {
            segments.set(i, refreshed.segments.get(i));
        }

        if (refreshed.initSegmentUrl != null) {
            initSegmentUrl = refreshed.initSegmentUrl;
        }
    }

    boolean hasMoreSegments() {
        return segmentIndex < segments.size();
    }

    /**
     * @return The next URL to read in the current chain, or null once the segments are exhausted.
     */
    String nextUrl() {
        if (initSegmentPending) {
            initSegmentPending = false;
            return initSegmentUrl;
        }

        if (segmentIndex < segments.size()) {
            return segments.get(segmentIndex++).url;
        }

        return null;
    }
}
