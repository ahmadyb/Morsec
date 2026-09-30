package app.morsecode.ui.video

import app.morsecode.core.design.component.ScrubberMath
import kotlin.math.roundToInt

/**
 * What a subtitle control can honestly say about one file.
 *
 * Three answers, because two are not enough: a file with no subtitle track and a
 * file whose track is switched off are different situations, and a control that
 * cannot tell them apart either offers subtitles that do not exist or hides the
 * ones that do. This build can only ever report [UNAVAILABLE] — nothing here can
 * open a container and enumerate its tracks until Media3 arrives in milestone 10 —
 * but the model already knows the other two, so the control is a switch that
 * switches something the day a track exists rather than a relic to be rewritten.
 */
public enum class SubtitleState {
    /** The file has no subtitle track. The control says so instead of offering one. */
    UNAVAILABLE,

    /** A track exists and is off. */
    OFF,

    /** A track exists and is being shown. */
    ON,
}

/**
 * The video player's controls, as state alone (master prompt §4.7).
 *
 * Every field here is something a user caused. Nothing advances on a clock: this
 * build has no video engine — that is Media3, milestone 10, and
 * [app.morsecode.core.model.FeatureArea.MEDIA_PLAYBACK] is gated until then — so the
 * position moves only when the user seeks or nudges it, [playing] only records which
 * of the two labels the transport buttons should wear, and no timer anywhere pretends
 * to be a decoder.
 *
 * The volume half is the reference's own `volCtl`: ten bars, a level in tenths, a
 * separate mute flag, and a remembered level so that unmuting gives the sound back
 * rather than silence. Mute does not lower the volume; it overrides what is in force.
 *
 * Instances are immutable and every transition returns a new one, so a screen reading
 * [VideoPlayerViewModel.state] can never catch this halfway through a change, and a
 * recomposition that reads the same value draws the same picture.
 */
public data class VideoTransport(
    /** Which of "play" and "pause" the two transport buttons are wearing. */
    val playing: Boolean = false,
    /** Where inside the clip the user has seeked or nudged to. */
    val positionMillis: Long = 0L,
    /** The level the bars show, in tenths: 0.0 is silent, 1.0 is loudest. */
    val volume: Float = DEFAULT_VOLUME,
    val muted: Boolean = false,
    /**
     * The last level that was actually audible.
     *
     * Mute stores it and unmute restores it, so a file muted at 70% comes back at 70%
     * and a file muted while silent comes back at the last level that made a sound
     * rather than at nothing.
     */
    val rememberedVolume: Float = DEFAULT_VOLUME,
    val subtitles: SubtitleState = SubtitleState.UNAVAILABLE,
    /** The player's own fullscreen state: the header gives its height to the picture. */
    val fullscreen: Boolean = false,
) {
    /**
     * The level in force.
     *
     * Mute wins over the stored level, exactly as the reference computes it
     * (`const v=S.muted?0:S.vol`), which is why setting the volume to nothing and
     * muting look the same on screen while remaining two different states underneath:
     * one of them still has a level to give back.
     */
    public val effectiveVolume: Float
        get() = if (muted) 0f else volume

    /** How many of the ten bars are lit, rounded the way the reference rounds. */
    public val levelOfTen: Int
        get() = (effectiveVolume * VOLUME_STEPS).roundToInt().coerceIn(0, VOLUME_STEPS)

    /** True when nothing would be heard: muted, or the level itself is nothing. */
    public val isSilent: Boolean
        get() = effectiveVolume <= 0f

    public companion object {
        /** The level the reference starts at (`vol:0.7`). */
        public const val DEFAULT_VOLUME: Float = 0.7f

        /** Ten bars, so a level is a whole number of tenths. */
        public const val VOLUME_STEPS: Int = 10

        /** What "back 10" and "forward 10" move by. */
        public const val SKIP_MILLIS: Long = 10_000L
    }
}

/**
 * The transitions the video player's controls perform.
 *
 * One rule per control, kept out of the view model on purpose: these are the answers
 * the screen draws and the tests assert, and a pure function can be asked for the same
 * input twice without a lifecycle, a coroutine or a looper being involved. The view
 * model holds the current value, writes it to [androidx.lifecycle.SavedStateHandle] and
 * formats it; it does not decide it.
 *
 * Clamping is delegated to [ScrubberMath], the same arithmetic the shared scrubber uses,
 * so a seek from a finger, a nudge from a button and a restored position all land inside
 * the clip by one rule rather than three that agree today.
 */
public object VideoTransportRules {

    /** Play becomes pause and pause becomes play. Nothing else moves. */
    public fun togglePlay(transport: VideoTransport): VideoTransport =
        transport.copy(playing = !transport.playing)

    /**
     * Seek to [positionMillis] of a clip that is [durationMillis] long.
     *
     * A clip with no known duration accepts nothing: the position stays at the start
     * rather than claiming a place inside a file whose length nobody has read.
     */
    public fun seek(
        transport: VideoTransport,
        positionMillis: Long,
        durationMillis: Long,
    ): VideoTransport =
        transport.copy(positionMillis = ScrubberMath.clamp(positionMillis, durationMillis))

    /**
     * Move by [deltaMillis] — the ±10 second buttons — and clamp at both ends.
     *
     * Nudging back from inside the first ten seconds lands on the start; nudging
     * forward from inside the last ten seconds lands on the end. Neither wraps.
     */
    public fun skip(
        transport: VideoTransport,
        deltaMillis: Long,
        durationMillis: Long,
    ): VideoTransport =
        transport.copy(
            positionMillis = ScrubberMath.clamp(transport.positionMillis + deltaMillis, durationMillis),
        )

    /**
     * Mute, storing the level to give back.
     *
     * The stored level itself is not touched — that is what makes unmute able to
     * restore it — but a mute from silence keeps the older audible level rather than
     * replacing it with nothing to restore.
     */
    public fun mute(transport: VideoTransport): VideoTransport =
        transport.copy(
            muted = true,
            rememberedVolume = if (transport.volume > 0f) transport.volume else transport.rememberedVolume,
        )

    /**
     * Unmute, restoring the remembered level.
     *
     * A transport that was never audible has nothing of its own to give back, so it
     * gives back the level it remembered; one that was merely overridden gives back
     * its own, which is the same number the reference kept in `S.vol` all along.
     */
    public fun unmute(transport: VideoTransport): VideoTransport =
        transport.copy(
            muted = false,
            volume = if (transport.volume > 0f) transport.volume else transport.rememberedVolume,
        )

    /** What the speaker button does: whichever of the two it is not already. */
    public fun toggleMute(transport: VideoTransport): VideoTransport =
        if (transport.muted) unmute(transport) else mute(transport)

    /**
     * Set the level from a bar index, 0 to [VideoTransport.VOLUME_STEPS].
     *
     * Raising the level above nothing is an instruction to be heard, so the mute flag
     * goes with it (`S.muted=false` in the reference's own handler). Setting it to
     * nothing is not an instruction to be silent — that is what mute is for — so the
     * flag stays as it was while the presentation becomes the silent one: no bars lit,
     * the muted speaker, and nothing in force.
     */
    public fun setVolumeLevel(transport: VideoTransport, levelOfTen: Int): VideoTransport {
        val level = levelOfTen.coerceIn(0, VideoTransport.VOLUME_STEPS)
        val volume = level.toFloat() / VideoTransport.VOLUME_STEPS
        return transport.copy(
            volume = volume,
            muted = if (volume > 0f) false else transport.muted,
            rememberedVolume = if (volume > 0f) volume else transport.rememberedVolume,
        )
    }

    /**
     * Move to the next subtitle answer.
     *
     * Off and on trade places. [SubtitleState.UNAVAILABLE] returns the same state
     * unchanged: a file with no track has no next answer, and inventing one would be
     * the control claiming a subtitle that cannot appear.
     */
    public fun cycleSubtitles(transport: VideoTransport): VideoTransport =
        when (transport.subtitles) {
            SubtitleState.UNAVAILABLE -> transport
            SubtitleState.OFF -> transport.copy(subtitles = SubtitleState.ON)
            SubtitleState.ON -> transport.copy(subtitles = SubtitleState.OFF)
        }

    /**
     * Fullscreen on or off.
     *
     * This is the player's own layout state — the header's title row gives its height
     * to the picture — and it is stored like every other field, so it survives
     * recomposition and comes back after process death. Locking the orientation and
     * hiding the system bars are the window's business and arrive with the real player
     * in milestone 10, which is why nothing here reaches for an Activity.
     */
    public fun toggleFullscreen(transport: VideoTransport): VideoTransport =
        transport.copy(fullscreen = !transport.fullscreen)
}
