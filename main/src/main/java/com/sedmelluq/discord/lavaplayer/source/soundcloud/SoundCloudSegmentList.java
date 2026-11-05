package com.sedmelluq.discord.lavaplayer.source.soundcloud;

import com.sedmelluq.discord.lavaplayer.container.playlists.ExtendedM3uParser;
import com.sedmelluq.discord.lavaplayer.container.playlists.HlsStreamSegment;
import com.sedmelluq.discord.lavaplayer.container.playlists.HlsStreamSegmentParser;

import java.net.URI;
import java.util.List;

/**
 * The segments of a SoundCloud HLS media playlist, plus its initialization segment (EXT-X-MAP) when it has one:
 * fragmented MP4 segments cannot be decoded without it, MP3 and Opus playlists carry none.
 */
class SoundCloudSegmentList {
    final String initSegmentUrl;
    final List<HlsStreamSegment> segments;

    SoundCloudSegmentList(String initSegmentUrl, List<HlsStreamSegment> segments) {
        this.initSegmentUrl = initSegmentUrl;
        this.segments = segments;
    }

    static SoundCloudSegmentList parse(String[] lines, String playlistUrl) {
        return new SoundCloudSegmentList(findInitSegmentUrl(lines, playlistUrl), HlsStreamSegmentParser.parseFromLines(lines));
    }

    private static String findInitSegmentUrl(String[] lines, String playlistUrl) {
        for (String lineText : lines) {
            ExtendedM3uParser.Line line = ExtendedM3uParser.parseLine(lineText);

            if (line.isDirective() && "EXT-X-MAP".equals(line.directiveName)) {
                String uri = line.directiveArguments.get("URI");

                if (uri == null || uri.isEmpty()) {
                    return null;
                }

                return URI.create(playlistUrl).resolve(uri).toString();
            }
        }

        return null;
    }
}
