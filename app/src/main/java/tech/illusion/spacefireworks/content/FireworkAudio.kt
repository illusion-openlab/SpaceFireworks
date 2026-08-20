package tech.illusion.spacefireworks.content

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.random.Random

/**
 * The show's sound: the user's own samples, played through a [SoundPool].
 *
 * This replaced a from-scratch PCM synthesiser (a rising-whistle sweep and a three-layer noise
 * burst written into an `AudioTrack`). The synth worked -- every play reported `PLAYSTATE_PLAYING`
 * -- but real recordings beat anything worth hand-tuning here, and the user supplied them.
 * `SoundPool` is the right vehicle for short overlapping one-shots: it decodes once at load and
 * mixes concurrent streams itself, so there is no voice pool to manage.
 *
 * Deliberately NOT spatialised: the report plays at the same level wherever the listener is facing,
 * not from the shell's position. Positional audio needs the SDK's own audio components -- note the
 * `android.media.SpaceAudioTrackImpl` PICO looks for and the emulator does not have -- and is a
 * separate piece of work.
 */
class FireworkAudio private constructor(context: Context) {

    private val pool =
        SoundPool.Builder()
            .setMaxStreams(MAX_STREAMS)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()

    private val launchId = load(context, "sfx_launch.mp3")

    /**
     * Reports, smallest shell to largest.
     *
     * One explosion sample on repeat is the tell that a show is canned; picking by shell size also
     * stops the little ones arriving with the same weight as the big ones.
     */
    private val burstIds =
        listOf(
            load(context, "sfx_burst_small.mp3"),
            load(context, "sfx_burst_mid.wav"),
            load(context, "sfx_burst_big.wav"),
        )

    private fun load(context: Context, asset: String): Int =
        try {
            context.assets.openFd(asset).use { pool.load(it, 1) }
        } catch (e: Exception) {
            android.util.Log.e("FireworkAudio", "could not load $asset", e)
            0
        }

    /** Plays the launch whoosh. [loudness] is 0..1, scaled by how big the shell is. */
    fun playLaunch(loudness: Float) = play(launchId, loudness * LAUNCH_GAIN)

    /**
     * Plays the report, picking a recording at random with a bias toward shell size.
     *
     * Choosing purely by size was the first attempt and it always landed on the biggest sample:
     * shell radius only varies over 0.72-1.0 of the sky's maximum by design, which is nowhere near
     * enough spread to separate three buckets (measured -- every burst logged `sound=4`). Variety
     * is the actual goal, so the roll is mostly random with [SIZE_BIAS] of it coming from size.
     */
    fun playBurst(loudness: Float) {
        val span = (1f - MIN_LOUDNESS).coerceAtLeast(1e-4f)
        val fraction = ((loudness - MIN_LOUDNESS) / span).coerceIn(0f, 1f)
        val roll = Random.nextFloat() * (1f - SIZE_BIAS) + fraction * SIZE_BIAS
        val index = (roll * burstIds.size).toInt().coerceIn(0, burstIds.size - 1)
        play(burstIds[index], loudness * BURST_GAIN)
    }

    private var logged = 0

    private fun play(soundId: Int, gain: Float) {
        if (soundId == 0 || FireworkSfx.muted) return
        val volume = gain.coerceIn(0f, 1f)
        // Slight per-shot pitch variation, so a repeated sample stops sounding repeated.
        val rate = 1f + RATE_JITTER * (Random.nextFloat() * 2f - 1f)
        val stream = pool.play(soundId, volume, volume, 1, 0, rate)
        if (logged < DIAG_PLAYS) {
            logged++
            // A returned stream id of 0 means the pool refused the play -- usually the sample is
            // still decoding. "Loaded without error" is not the same as "audible".
            android.util.Log.i(
                "FireworkAudio",
                "play#$logged sound=$soundId vol=%.2f rate=%.2f -> stream=$stream"
                    .format(volume, rate),
            )
        }
    }

    /** Pauses every currently-playing stream in the pool — whichever launch/burst samples happen
     * to still be sounding at the moment the app backgrounds. */
    fun pause() = pool.autoPause()

    /** Resumes whatever [pause] paused. A no-op if nothing was playing. */
    fun resume() = pool.autoResume()

    fun release() = pool.release()

    companion object {

        private const val MAX_STREAMS = 6

        /** Plays to log; enough to prove the path, not enough to spam. */
        private const val DIAG_PLAYS = 6
        private const val LAUNCH_GAIN = 0.55f
        private const val BURST_GAIN = 1.0f
        private const val RATE_JITTER = 0.08f

        /** How much of the sample choice comes from shell size rather than chance. */
        private const val SIZE_BIAS = 0.35f

        /** Mirrors `FireworkDirector`'s own floor; sample choice maps over the same range. */
        private const val MIN_LOUDNESS = 0.45f

        @Volatile private var instance: FireworkAudio? = null

        /**
         * The one pool for the whole app.
         *
         * Shared rather than per-scene on purpose: decoding four samples twice would be waste, and
         * the window and the Stage only overlap for an instant. Held against the *application*
         * context so a closing container cannot leak an activity into it.
         */
        fun shared(context: Context): FireworkAudio =
            instance
                ?: synchronized(this) {
                    instance
                        ?: FireworkAudio(context.applicationContext).also {
                            instance = it
                            android.util.Log.i("FireworkAudio", "pool ready")
                        }
                }
    }
}

/**
 * Whether the show is muted, shared by every container.
 *
 * The volumetric window and the immersive Stage are separate compositions, so this cannot live in
 * either one's state -- muting in the window has to stay muted after switching into the Stage.
 * Compose state is process-wide, so a plain object holding it keeps both toolbars in sync with no
 * plumbing between containers.
 */
object FireworkSfx {

    /** Driven by the 禁音 button in the window toolbar and in the Stage HUD. */
    var muted by mutableStateOf(false)
}
