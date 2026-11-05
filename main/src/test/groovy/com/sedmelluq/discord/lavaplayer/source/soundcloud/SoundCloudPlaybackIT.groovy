package com.sedmelluq.discord.lavaplayer.source.soundcloud

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import spock.lang.Requires
import spock.lang.Specification

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Searches SoundCloud and plays a result end to end, then seeks into it: client id, search, format choice, fragmented
 * MP4 HLS with its init segment, AAC decoding. Network test: SOUNDCLOUD_IT=1 ./gradlew :main:test
 */
@Requires({ env.SOUNDCLOUD_IT == "1" })
class SoundCloudPlaybackIT extends Specification {
    def manager = new DefaultAudioPlayerManager()

    def setup() {
        manager.registerSourceManager(SoundCloudAudioSourceManager.createDefault())
    }

    def cleanup() {
        manager.shutdown()
    }

    AudioTrack firstSearchResult(String query) {
        def result = new CompletableFuture<AudioTrack>()
        manager.loadItem("scsearch:" + query, new AudioLoadResultHandler() {
            void trackLoaded(AudioTrack track) { result.complete(track) }

            void playlistLoaded(AudioPlaylist playlist) {
                result.complete(playlist.tracks.find { it.duration > 120_000 } ?: playlist.tracks.first())
            }

            void noMatches() { result.completeExceptionally(new AssertionError("no matches for " + query)) }

            void loadFailed(FriendlyException e) { result.completeExceptionally(e) }
        })
        result.get(60, TimeUnit.SECONDS)
    }

    static int frames(player, int wanted) {
        int count = 0
        long deadline = System.currentTimeMillis() + 60_000
        while (count < wanted && System.currentTimeMillis() < deadline) {
            if (player.provide(1, TimeUnit.SECONDS) != null) {
                count++
            }
        }
        count
    }

    def "searches, plays and seeks a SoundCloud track"() {
        given:
        def track = firstSearchResult("lofi hip hop")
        def player = manager.createPlayer()

        expect: "it goes through the fragmented MP4 path"
        track.identifier.startsWith("A:")

        when:
        player.playTrack(track)

        then: "about 5 seconds of audio decode from the start"
        frames(player, 250) == 250

        when:
        player.playingTrack.position = 60_000

        then: "after a seek it keeps decoding, from where it was asked to"
        frames(player, 250) == 250
        player.playingTrack.position >= 60_000

        cleanup:
        player.destroy()
    }
}
