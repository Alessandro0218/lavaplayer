package com.sedmelluq.discord.lavaplayer.source.soundcloud

import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import spock.lang.Specification

class SoundCloudAacSupportSpec extends Specification {
    def handler = new DefaultSoundCloudFormatHandler()

    static format(String protocol, String mime, String url = "https://api-v2.soundcloud.com/media/x") {
        new DefaultSoundCloudTrackFormat("1", protocol, mime, url)
    }

    def "picks the fragmented MP4 HLS format, mime parameters and all"() {
        given:
        def aac = format("hls", 'audio/mp4; codecs="mp4a.40.2"', "https://api-v2.soundcloud.com/media/aac")

        expect:
        handler.chooseBestFormat([format("ctr-encrypted-hls", "audio/mp4"), aac]) == aac
        handler.buildFormatIdentifier(aac) == "A:https://api-v2.soundcloud.com/media/aac"
        handler.getM3uInfo("A:https://lookup").lookupUrl == "https://lookup"
    }

    def "still prefers the formats it read before"() {
        given:
        def opus = format("hls", "audio/ogg")

        expect:
        handler.chooseBestFormat([format("hls", "audio/mp4"), opus]) == opus
    }

    def "a track served only encrypted is reported as DRM"() {
        when:
        handler.chooseBestFormat([format("ctr-encrypted-hls", "audio/mp4"), format("cbc-encrypted-hls", "audio/mp4")])

        then:
        def e = thrown(FriendlyException)
        e.message.contains("DRM")
        e.severity == FriendlyException.Severity.COMMON
    }

    def "no usable format keeps the old message"() {
        when:
        handler.chooseBestFormat([format("progressive", "audio/aac")])

        then:
        def e = thrown(FriendlyException)
        e.message == "Did not detect any supported formats"
    }

    static final String[] PLAYLIST = [
        "#EXTM3U",
        "#EXT-X-VERSION:7",
        '#EXT-X-MAP:URI="init.mp4"',
        "#EXTINF:10.0,",
        "https://cdn.media-streaming.soundcloud.cloud/seg0.m4s?sig=a",
        "#EXTINF:10.0,",
        "https://cdn.media-streaming.soundcloud.cloud/seg1.m4s?sig=b",
        "#EXTINF:5.5,",
        "https://cdn.media-streaming.soundcloud.cloud/seg2.m4s?sig=c",
        "#EXT-X-ENDLIST",
    ]

    def "reads the init segment, resolved against the playlist"() {
        when:
        def list = SoundCloudSegmentList.parse(PLAYLIST, "https://playback.media-streaming.soundcloud.cloud/a/b/playlist.m3u8?sig=z")

        then:
        list.initSegmentUrl == "https://playback.media-streaming.soundcloud.cloud/a/b/init.mp4"
        list.segments*.duration == [10000L, 10000L, 5500L]
    }

    def "a playlist without EXT-X-MAP has no init segment"() {
        expect:
        SoundCloudSegmentList.parse(["#EXTINF:10.0,", "https://cf-hls-media.sndcdn.com/a.mp3"] as String[], "https://x/p.m3u8")
            .initSegmentUrl == null
    }

    static List<String> drain(SoundCloudSegmentCursor cursor) {
        def urls = []
        String url
        while ((url = cursor.nextUrl()) != null) {
            urls << url.replaceAll(/.*\//, "").replaceAll(/\?.*/, "")
        }
        urls
    }

    def "every chain starts with the init segment, the one after a seek too"() {
        given:
        def cursor = new SoundCloudSegmentCursor(SoundCloudSegmentList.parse(PLAYLIST, "https://x/a/playlist.m3u8"))

        when:
        cursor.startChain()

        then:
        drain(cursor) == ["init.mp4", "seg0.m4s", "seg1.m4s", "seg2.m4s"]

        when:
        cursor.seekToSegment(2)
        cursor.startChain()

        then:
        drain(cursor) == ["init.mp4", "seg2.m4s"]
    }

    def "without an init segment chains are just the segments"() {
        given:
        def lines = ["#EXTINF:10.0,", "https://cf-hls-media.sndcdn.com/a.mp3", "#EXTINF:10.0,", "https://cf-hls-media.sndcdn.com/b.mp3"] as String[]
        def cursor = new SoundCloudSegmentCursor(SoundCloudSegmentList.parse(lines, "https://x/p.m3u8"))

        when:
        cursor.seekToSegment(1)
        cursor.startChain()

        then:
        drain(cursor) == ["b.mp3"]
    }

    def "a refreshed playlist swaps in the new signed urls"() {
        given:
        def cursor = new SoundCloudSegmentCursor(SoundCloudSegmentList.parse(PLAYLIST, "https://x/a/playlist.m3u8"))
        def refreshed = PLAYLIST.collect { it.replace("sig=", "sig=new") }.collect { it.replace("init.mp4", "init2.mp4") } as String[]

        when:
        cursor.replace(SoundCloudSegmentList.parse(refreshed, "https://x/a/playlist.m3u8"))
        cursor.startChain()

        then:
        cursor.nextUrl().endsWith("init2.mp4")
        cursor.nextUrl().endsWith("sig=newa")
    }

    def "signed CDN urls get no client_id"() {
        expect:
        SoundCloudHttpContextFilter.isCdnHost(host) == cdn

        where:
        host                                         | cdn
        "cf-hls-media.sndcdn.com"                    | true
        "playback.media-streaming.soundcloud.cloud"  | true
        "api-v2.soundcloud.com"                      | false
        null                                         | false
    }
}
