package app.morsecode.ui.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The video player's state rules, asked for their answers directly (§4.7).
 *
 * No screen, no lifecycle, no looper and no clock: [VideoTransportRules] is pure, so
 * what is asserted here is the arithmetic and the transitions themselves — that a seek
 * lands where it was asked to and nowhere outside the clip, that ten seconds back from
 * inside the first ten seconds is the start rather than a wrap or a negative position,
 * that mute stores a level and unmute gives that level back, that raising the volume
 * ends a mute, that a level of nothing looks like silence without claiming to be a
 * mute, that subtitles cycle only when a track exists, and that fullscreen is a state
 * that stays put.
 *
 * The clip length below is the reference's shortest (`clip_01.mp4`, 0:48), so the ends
 * these tests walk into are the ends its own catalogue has.
 */
class VideoTransportTest {

    /** `clip_01.mp4` in the reference's video list: 0:48. */
    private val duration = 48_000L

    private val middle = duration / 2

    @Test
    fun `a seek lands at the start, the middle and the end of the clip`() {
        assertEquals(0L, VideoTransportRules.seek(VideoTransport(), 0L, duration).positionMillis)
        assertEquals(middle, VideoTransportRules.seek(VideoTransport(), middle, duration).positionMillis)
        assertEquals(duration, VideoTransportRules.seek(VideoTransport(), duration, duration).positionMillis)
    }

    @Test
    fun `a seek outside the clip is pulled to the nearer end`() {
        assertEquals(0L, VideoTransportRules.seek(VideoTransport(), -5_000L, duration).positionMillis)
        assertEquals(
            duration,
            VideoTransportRules.seek(VideoTransport(), duration + 1L, duration).positionMillis,
        )
        // A long way outside is still just an end: nothing wraps round to the other side.
        assertEquals(0L, VideoTransportRules.seek(VideoTransport(), Long.MIN_VALUE, duration).positionMillis)
    }

    @Test
    fun `a clip whose length nobody has read accepts no position but the start`() {
        assertEquals(0L, VideoTransportRules.seek(VideoTransport(positionMillis = 9_000L), 30_000L, 0L).positionMillis)
        assertEquals(0L, VideoTransportRules.skip(VideoTransport(positionMillis = 9_000L), 10_000L, 0L).positionMillis)
    }

    @Test
    fun `ten seconds back from inside the first ten seconds is the start`() {
        val nudged = VideoTransportRules.skip(VideoTransport(positionMillis = 4_000L), -VideoTransport.SKIP_MILLIS, duration)

        assertEquals(0L, nudged.positionMillis)
    }

    @Test
    fun `ten seconds forward from inside the last ten seconds is the end`() {
        val nudged = VideoTransportRules.skip(
            VideoTransport(positionMillis = duration - 4_000L),
            VideoTransport.SKIP_MILLIS,
            duration,
        )

        assertEquals(duration, nudged.positionMillis)
    }

    @Test
    fun `the nudge buttons move by exactly ten seconds`() {
        val back = VideoTransportRules.skip(VideoTransport(positionMillis = middle), -VideoTransport.SKIP_MILLIS, duration)
        val forward = VideoTransportRules.skip(VideoTransport(positionMillis = middle), VideoTransport.SKIP_MILLIS, duration)

        assertEquals(middle - 10_000L, back.positionMillis)
        assertEquals(middle + 10_000L, forward.positionMillis)
        assertEquals(10_000L, VideoTransport.SKIP_MILLIS)
    }

    @Test
    fun `nudging past either end stays at that end however often it is nudged`() {
        var transport = VideoTransport(positionMillis = 3_000L)
        repeat(5) { transport = VideoTransportRules.skip(transport, -VideoTransport.SKIP_MILLIS, duration) }
        assertEquals(0L, transport.positionMillis)

        transport = VideoTransport(positionMillis = duration - 3_000L)
        repeat(5) { transport = VideoTransportRules.skip(transport, VideoTransport.SKIP_MILLIS, duration) }
        assertEquals(duration, transport.positionMillis)
    }

    @Test
    fun `play and pause are one switch that flips and flips back`() {
        val paused = VideoTransport()
        assertFalse(paused.playing)

        val playing = VideoTransportRules.togglePlay(paused)
        assertTrue(playing.playing)
        assertFalse(VideoTransportRules.togglePlay(playing).playing)
    }

    @Test
    fun `a transition returns a new state and leaves the one it was given alone`() {
        val before = VideoTransport(positionMillis = middle, volume = 0.4f)
        val after = VideoTransportRules.togglePlay(before)

        assertNotSame(before, after)
        assertFalse("the state that was handed in must not have moved", before.playing)
        assertEquals(middle, after.positionMillis)
        assertEquals(0.4f, after.volume, 0f)
    }

    @Test
    fun `playing is the only thing play and pause change`() {
        val before = VideoTransport(
            positionMillis = middle,
            volume = 0.3f,
            muted = true,
            subtitles = SubtitleState.ON,
            fullscreen = true,
        )

        val after = VideoTransportRules.togglePlay(before)

        assertEquals(before.copy(playing = true), after)
    }

    @Test
    fun `mute keeps the level and unmute gives it back`() {
        val playing = VideoTransport(volume = 0.7f)

        val muted = VideoTransportRules.mute(playing)
        assertTrue(muted.muted)
        assertTrue(muted.isSilent)
        assertEquals(0, muted.levelOfTen)
        assertEquals(0f, muted.effectiveVolume, 0f)
        // The level itself is still there: that is what unmute has to restore.
        assertEquals(0.7f, muted.volume, 0f)
        assertEquals(0.7f, muted.rememberedVolume, 0f)

        val restored = VideoTransportRules.unmute(muted)
        assertFalse(restored.muted)
        assertEquals(0.7f, restored.effectiveVolume, 0f)
        assertEquals(7, restored.levelOfTen)
    }

    @Test
    fun `a level set before the mute is the level the mute gives back`() {
        val quiet = VideoTransportRules.setVolumeLevel(VideoTransport(), 4)
        assertEquals(0.4f, quiet.volume, 0f)

        val restored = VideoTransportRules.unmute(VideoTransportRules.mute(quiet))

        assertEquals(0.4f, restored.effectiveVolume, 0f)
        assertEquals(4, restored.levelOfTen)
    }

    @Test
    fun `muting from silence remembers the last level that made a sound`() {
        val silent = VideoTransport(volume = 0f, rememberedVolume = 0.7f)
        assertTrue(silent.isSilent)

        val muted = VideoTransportRules.mute(silent)
        assertEquals(0.7f, muted.rememberedVolume, 0f)

        val restored = VideoTransportRules.unmute(muted)
        assertFalse(restored.isSilent)
        assertEquals(0.7f, restored.effectiveVolume, 0f)
    }

    @Test
    fun `the speaker button is one control that does whichever of the two is needed`() {
        val muted = VideoTransportRules.toggleMute(VideoTransport(volume = 0.6f))
        assertTrue(muted.muted)

        val unmuted = VideoTransportRules.toggleMute(muted)
        assertFalse(unmuted.muted)
        assertEquals(0.6f, unmuted.effectiveVolume, 0f)
    }

    @Test
    fun `raising the volume while muted ends the mute`() {
        val muted = VideoTransportRules.mute(VideoTransport(volume = 0.7f))

        val raised = VideoTransportRules.setVolumeLevel(muted, 5)

        assertFalse("a level above nothing is an instruction to be heard", raised.muted)
        assertEquals(0.5f, raised.volume, 0f)
        assertEquals(0.5f, raised.effectiveVolume, 0f)
        assertEquals(5, raised.levelOfTen)
        assertEquals(0.5f, raised.rememberedVolume, 0f)
    }

    @Test
    fun `a level of nothing is the silent presentation without being a mute`() {
        val silent = VideoTransportRules.setVolumeLevel(VideoTransport(volume = 0.7f), 0)

        assertEquals(0f, silent.volume, 0f)
        assertFalse("the mute flag belongs to the speaker, not to the level", silent.muted)
        assertTrue(silent.isSilent)
        assertEquals(0, silent.levelOfTen)
        assertEquals(0f, silent.effectiveVolume, 0f)
        // Nothing audible was stored, so the level to give back is still the older one.
        assertEquals(0.7f, silent.rememberedVolume, 0f)

        val givenBack = VideoTransportRules.unmute(VideoTransportRules.mute(silent))
        assertEquals(0.7f, givenBack.effectiveVolume, 0f)
    }

    @Test
    fun `a level outside the ten bars is pulled into them`() {
        assertEquals(1.0f, VideoTransportRules.setVolumeLevel(VideoTransport(), 14).volume, 0f)
        assertEquals(10, VideoTransportRules.setVolumeLevel(VideoTransport(), 14).levelOfTen)
        assertEquals(0f, VideoTransportRules.setVolumeLevel(VideoTransport(), -3).volume, 0f)
        assertEquals(0, VideoTransportRules.setVolumeLevel(VideoTransport(), -3).levelOfTen)
    }

    @Test
    fun `every one of the ten bars is a level and the tenth is the loudest`() {
        val levels = (1..VideoTransport.VOLUME_STEPS).map { VideoTransportRules.setVolumeLevel(VideoTransport(), it) }

        assertEquals((1..10).toList(), levels.map { it.levelOfTen })
        assertEquals(1.0f, levels.last().volume, 0f)
        assertTrue(levels.none { it.muted })
    }

    @Test
    fun `the bars light by the level in force, not by the level stored`() {
        val muted = VideoTransportRules.mute(VideoTransport(volume = 0.9f))

        assertEquals(0, muted.levelOfTen)
        assertEquals(9, VideoTransportRules.unmute(muted).levelOfTen)
    }

    @Test
    fun `subtitles trade places when a track exists and stay put when it does not`() {
        val available = VideoTransport(subtitles = SubtitleState.OFF)

        val on = VideoTransportRules.cycleSubtitles(available)
        assertEquals(SubtitleState.ON, on.subtitles)
        assertEquals(SubtitleState.OFF, VideoTransportRules.cycleSubtitles(on).subtitles)

        // No track means no next answer: the same state comes back, unchanged.
        val none = VideoTransport(subtitles = SubtitleState.UNAVAILABLE)
        assertSame(none, VideoTransportRules.cycleSubtitles(none))
        assertEquals(SubtitleState.UNAVAILABLE, VideoTransportRules.cycleSubtitles(none).subtitles)
    }

    @Test
    fun `fullscreen flips and is still flipped when it is read again`() {
        val windowed = VideoTransport()
        assertFalse(windowed.fullscreen)

        val full = VideoTransportRules.toggleFullscreen(windowed)
        assertTrue(full.fullscreen)
        // Recomposition reads the same value twice; both reads must agree.
        assertTrue(full.fullscreen)
        assertEquals(full, full.copy())
        assertFalse(VideoTransportRules.toggleFullscreen(full).fullscreen)
    }

    @Test
    fun `fullscreen does not disturb the position, the level or the subtitles`() {
        val before = VideoTransport(positionMillis = middle, volume = 0.2f, subtitles = SubtitleState.ON)

        val after = VideoTransportRules.toggleFullscreen(before)

        assertEquals(before.copy(fullscreen = true), after)
    }

    @Test
    fun `the player starts where the reference starts - paused, at the start, seven bars up`() {
        val transport = VideoTransport()

        assertFalse(transport.playing)
        assertEquals(0L, transport.positionMillis)
        assertEquals(0.7f, transport.volume, 0f)
        assertEquals(VideoTransport.DEFAULT_VOLUME, transport.volume, 0f)
        assertEquals(7, transport.levelOfTen)
        assertFalse(transport.muted)
        assertFalse(transport.isSilent)
        assertFalse(transport.fullscreen)
        assertEquals(SubtitleState.UNAVAILABLE, transport.subtitles)
    }
}
