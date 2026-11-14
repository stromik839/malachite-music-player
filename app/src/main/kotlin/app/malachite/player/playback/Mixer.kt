package app.malachite.player.playback

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import kotlinx.serialization.Serializable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/** Positions of the mixer's knobs: -1 … 1, 0 in the middle (nothing changed). */
@Serializable
data class MixerKnobs(
    val trim: Float = 0f,
    val hi: Float = 0f,
    val mid: Float = 0f,
    val low: Float = 0f,
    val filter: Float = 0f,
    val balance: Float = 0f,
) {
    /** Every knob in the middle: the sound passes untouched. */
    val centred: Boolean
        get() = abs(trim) < 0.005f && abs(hi) < 0.005f && abs(mid) < 0.005f && abs(low) < 0.005f &&
            abs(filter) < 0.005f && abs(balance) < 0.005f
}

/**
 * A DJ mixer's channel in front of the speaker, written for this player: TRIM (the level), a
 * three-band isolator HI / MID / LOW that lifts a band a little or takes it away completely (KILL
 * at the end of the knob), a FILTER (to the left only the bass stays, to the right only the highs)
 * and the left–right BALANCE.
 *
 * The knobs are set on the main thread and read by the sound thread. Every change glides over a few
 * milliseconds, so turning a knob never clicks; with every knob in the middle the sound passes
 * untouched, bit for bit. The mixer works on the decoded sound before it is queued for the speaker,
 * so a turn is heard a fraction of a second later.
 */
object Mixer {
    @Volatile var on: Boolean = true
    @Volatile var knobs: MixerKnobs = MixerKnobs()

    /** A band knob turned this far to the left takes its band away completely. */
    const val KILL = -0.97f

    /** Where the bands meet. */
    const val LOW_MID_HZ = 250.0
    const val MID_HIGH_HZ = 3000.0

    /** Near the middle the FILTER knob does nothing. */
    const val FILTER_DEAD = 0.04f

    fun trimDb(v: Float) = 12f * v

    /** Up to +6 dB to the right, down to -26 dB to the left; at the very end nothing (see [KILL]). */
    fun bandDb(v: Float) = if (v >= 0f) 6f * v else 26f * v / -KILL

    fun bandGain(v: Float) = if (v <= KILL) 0f else gain(bandDb(v))

    fun gain(db: Float) = 10f.pow(db / 20f)

    /** The FILTER knob's cut-off: a low-pass to the left (18 kHz down to 100 Hz), a high-pass to the right (30 Hz up to 6 kHz). */
    fun filterHz(v: Float): Float? {
        val a = abs(v)
        if (a < FILTER_DEAD) return null
        val u = (a - FILTER_DEAD) / (1f - FILTER_DEAD)
        return if (v < 0f) 18_000f * (100f / 18_000f).pow(u) else 30f * (6_000f / 30f).pow(u)
    }

    /** Gains of the left and the right channel for a BALANCE position. */
    fun balanceLeft(v: Float) = if (v > 0f) 1f - v else 1f
    fun balanceRight(v: Float) = if (v < 0f) 1f + v else 1f

    /**
     * The usual renderers with the mixer in front of the speaker; [withSpectrum]: the mixed sound is
     * also looked at for the visualizer and the meter ([Spectrum], no microphone).
     */
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun renderers(context: Context, withSpectrum: Boolean): RenderersFactory = object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink? =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(
                    if (withSpectrum) arrayOf<AudioProcessor>(MixerProcessor(), TeeAudioProcessor(Spectrum))
                    else arrayOf<AudioProcessor>(MixerProcessor()),
                )
                .build()
    }
}

/**
 * The sound work of [Mixer]. The isolator splits the sound in three with Linkwitz–Riley crossovers
 * (two Butterworth sections each) and puts the bands back together; the bass goes through the same
 * phase turn as the other two, so with the bands untouched the sum has the same loudness at every
 * frequency. Filters are the usual biquads (RBJ formulas), computed in doubles.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MixerProcessor : BaseAudioProcessor() {
    private companion object {
        const val BLOCK = 64
        /** How fast a value follows its knob (seconds). */
        const val GLIDE_S = 0.010
        const val SOFT = 0.9

        // the biquads of one channel
        const val LP_LOW_1 = 0
        const val LP_LOW_2 = 1
        const val HP_LOW_1 = 2
        const val HP_LOW_2 = 3
        const val LP_HIGH_1 = 4
        const val LP_HIGH_2 = 5
        const val HP_HIGH_1 = 6
        const val HP_HIGH_2 = 7
        const val AP_HIGH = 8
        const val FILTER = 9
        const val STAGES = 10

        const val LOWPASS = 0
        const val HIGHPASS = 1
        const val ALLPASS = 2
        const val BUTTERWORTH_Q = 0.7071067811865476
        const val FILTER_Q = 1.1

        val CENTRED = MixerKnobs()
    }

    private var channels = 2
    private var rate = 44_100
    private var floats = false
    private var designedFor = 0

    private val coef = DoubleArray(STAGES * 5)
    private var z = DoubleArray(STAGES * 2 * 2)

    // where every value is now (they glide to the knobs)
    private var trim = 1f
    private var low = 1f
    private var mid = 1f
    private var high = 1f
    /** How much of the isolator is heard: 0 while the three band knobs are in the middle. */
    private var iso = 0f
    /** How much of the filter is heard, its kind (-1 low-pass, 1 high-pass, 0 none) and cut-off (ln Hz). */
    private var filterMix = 0f
    private var filterKind = 0
    private var filterLnHz = 0.0
    private var left = 1f
    private var right = 1f

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val e = inputAudioFormat.encoding
        return if (e == C.ENCODING_PCM_16BIT || e == C.ENCODING_PCM_FLOAT) inputAudioFormat else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun onFlush() {
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        rate = inputAudioFormat.sampleRate.takeIf { it > 0 } ?: 44_100
        floats = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        if (designedFor != rate) {
            designedFor = rate
            design(LP_LOW_1, LOWPASS, Mixer.LOW_MID_HZ, BUTTERWORTH_Q)
            design(LP_LOW_2, LOWPASS, Mixer.LOW_MID_HZ, BUTTERWORTH_Q)
            design(HP_LOW_1, HIGHPASS, Mixer.LOW_MID_HZ, BUTTERWORTH_Q)
            design(HP_LOW_2, HIGHPASS, Mixer.LOW_MID_HZ, BUTTERWORTH_Q)
            design(LP_HIGH_1, LOWPASS, Mixer.MID_HIGH_HZ, BUTTERWORTH_Q)
            design(LP_HIGH_2, LOWPASS, Mixer.MID_HIGH_HZ, BUTTERWORTH_Q)
            design(HP_HIGH_1, HIGHPASS, Mixer.MID_HIGH_HZ, BUTTERWORTH_Q)
            design(HP_HIGH_2, HIGHPASS, Mixer.MID_HIGH_HZ, BUTTERWORTH_Q)
            design(AP_HIGH, ALLPASS, Mixer.MID_HIGH_HZ, BUTTERWORTH_Q)
            if (filterKind != 0) design(FILTER, if (filterKind < 0) LOWPASS else HIGHPASS, exp(filterLnHz), FILTER_Q)
        }
        if (z.size != channels * STAGES * 2) z = DoubleArray(channels * STAGES * 2) else z.fill(0.0)
        // after a seek or another song the sound starts with the knobs as they are, nothing glides
        settle(target())
    }

    override fun onReset() {
        z = DoubleArray(STAGES * 2 * 2)
        channels = 2
        designedFor = 0
        settle(CENTRED)
        filterMix = 0f
        filterKind = 0
    }

    private fun target(): MixerKnobs = Mixer.knobs.takeIf { Mixer.on && !it.centred } ?: CENTRED

    /** Every value straight at its knob. */
    private fun settle(k: MixerKnobs) {
        trim = Mixer.gain(Mixer.trimDb(k.trim))
        low = Mixer.bandGain(k.low)
        mid = Mixer.bandGain(k.mid)
        high = Mixer.bandGain(k.hi)
        iso = if (isoWanted(k)) 1f else 0f
        left = Mixer.balanceLeft(k.balance)
        right = Mixer.balanceRight(k.balance)
        val hz = Mixer.filterHz(k.filter)
        filterKind = if (hz == null) 0 else if (k.filter < 0f) -1 else 1
        filterMix = if (hz == null) 0f else 1f
        if (hz != null) {
            filterLnHz = ln(hz.toDouble())
            design(FILTER, if (filterKind < 0) LOWPASS else HIGHPASS, hz.toDouble(), FILTER_Q)
        }
    }

    private fun isoWanted(k: MixerKnobs) = abs(k.hi) >= 0.005f || abs(k.mid) >= 0.005f || abs(k.low) >= 0.005f

    /** Nothing is changed by the mixer now, and nothing is on its way. */
    private fun idle(k: MixerKnobs) = k.centred && trim == 1f && iso == 0f && filterMix == 0f && filterKind == 0 && left == 1f && right == 1f

    override fun queueInput(inputBuffer: ByteBuffer) {
        val size = inputBuffer.remaining()
        if (size == 0) return
        val out = replaceOutputBuffer(size)
        val k = target()
        if (idle(k)) {
            out.put(inputBuffer)
            out.flip()
            return
        }
        val src = inputBuffer.order(ByteOrder.nativeOrder())
        val frameBytes = (if (floats) 4 else 2) * channels
        val frames = size / frameBytes
        var done = 0
        while (done < frames) {
            val n = min(BLOCK, frames - done)
            block(src, out, n, k)
            done += n
        }
        // a broken last frame passes as it is
        while (src.hasRemaining()) out.put(src.get())
        out.flip()
    }

    private fun block(src: ByteBuffer, out: ByteBuffer, n: Int, k: MixerKnobs) {
        // the values at the start of the block; [glide] moves them to where they are at its end
        val trim0 = trim; val low0 = low; val mid0 = mid; val high0 = high
        val iso0 = iso; val filter0 = filterMix; val left0 = left; val right0 = right
        glide(k, n)
        val isoOn = iso0 > 0f || iso > 0f
        val filterOn = filterKind != 0 && (filter0 > 0f || filterMix > 0f)
        val step = 1f / n
        for (i in 0 until n) {
            val f = (i + 1) * step
            val g = trim0 + (trim - trim0) * f
            val gl = low0 + (low - low0) * f
            val gm = mid0 + (mid - mid0) * f
            val gh = high0 + (high - high0) * f
            val gi = iso0 + (iso - iso0) * f
            val gf = filter0 + (filterMix - filter0) * f
            val bl = left0 + (left - left0) * f
            val br = right0 + (right - right0) * f
            for (ch in 0 until channels) {
                val x = if (floats) src.getFloat().toDouble() else src.getShort() / 32768.0
                var y = x * g
                val base = ch * STAGES * 2
                if (isoOn) {
                    val lowBand = bq(AP_HIGH, base, bq(LP_LOW_2, base, bq(LP_LOW_1, base, y)))
                    val rest = bq(HP_LOW_2, base, bq(HP_LOW_1, base, y))
                    val midBand = bq(LP_HIGH_2, base, bq(LP_HIGH_1, base, rest))
                    val highBand = bq(HP_HIGH_2, base, bq(HP_HIGH_1, base, rest))
                    y += (gl * lowBand + gm * midBand + gh * highBand - y) * gi
                }
                if (filterOn) y += (bq(FILTER, base, y) - y) * gf
                if (channels == 2) y *= if (ch == 0) bl else br
                y = soft(y)
                if (floats) out.putFloat(y.toFloat())
                else out.putShort((y * 32768.0).roundToInt().coerceIn(-32768, 32767).toShort())
            }
        }
        // a part that went quiet starts from silence next time
        if (!isoOn || iso == 0f) clear(LP_LOW_1, AP_HIGH)
    }

    /** Moves every value [n] frames towards its knob. */
    private fun glide(k: MixerKnobs, n: Int) {
        val a = (1.0 - exp(-n / (rate * GLIDE_S))).toFloat()
        fun towards(now: Float, to: Float): Float {
            val v = now + (to - now) * a
            return if (abs(to - v) < 1e-4f) to else v
        }
        trim = towards(trim, Mixer.gain(Mixer.trimDb(k.trim)))
        low = towards(low, Mixer.bandGain(k.low))
        mid = towards(mid, Mixer.bandGain(k.mid))
        high = towards(high, Mixer.bandGain(k.hi))
        iso = towards(iso, if (isoWanted(k)) 1f else 0f)
        left = towards(left, Mixer.balanceLeft(k.balance))
        right = towards(right, Mixer.balanceRight(k.balance))
        // the filter: another kind first fades the old one out, then starts from silence
        val hz = Mixer.filterHz(k.filter)
        val kind = if (hz == null) 0 else if (k.filter < 0f) -1 else 1
        if (kind != filterKind) {
            filterMix = towards(filterMix, 0f)
            if (filterMix == 0f) {
                filterKind = kind
                clear(FILTER, FILTER)
                if (hz != null) {
                    filterLnHz = ln(hz.toDouble())
                    design(FILTER, if (kind < 0) LOWPASS else HIGHPASS, hz.toDouble(), FILTER_Q)
                }
            }
        } else if (hz != null) {
            filterMix = towards(filterMix, 1f)
            val to = ln(hz.toDouble())
            if (abs(to - filterLnHz) > 1e-4) {
                filterLnHz += (to - filterLnHz) * a
                design(FILTER, if (kind < 0) LOWPASS else HIGHPASS, exp(filterLnHz), FILTER_Q)
            }
        }
    }

    /** One biquad (transposed direct form II) of one channel. */
    private fun bq(stage: Int, base: Int, x: Double): Double {
        val c = stage * 5
        val s = base + stage * 2
        val y = coef[c] * x + z[s]
        z[s] = coef[c + 1] * x - coef[c + 3] * y + z[s + 1]
        z[s + 1] = coef[c + 2] * x - coef[c + 4] * y
        return y
    }

    private fun clear(fromStage: Int, toStage: Int) {
        for (ch in 0 until channels) {
            val base = ch * STAGES * 2
            for (st in fromStage..toStage) { z[base + st * 2] = 0.0; z[base + st * 2 + 1] = 0.0 }
        }
    }

    private fun design(stage: Int, kind: Int, hz: Double, q: Double) {
        val w = 2 * PI * hz.coerceIn(10.0, rate * 0.45) / rate
        val cw = cos(w)
        val alpha = sin(w) / (2 * q)
        val a0 = 1 + alpha
        val b0: Double
        val b1: Double
        val b2: Double
        when (kind) {
            LOWPASS -> { b0 = (1 - cw) / 2; b1 = 1 - cw; b2 = b0 }
            HIGHPASS -> { b0 = (1 + cw) / 2; b1 = -(1 + cw); b2 = b0 }
            else -> { b0 = 1 - alpha; b1 = -2 * cw; b2 = 1 + alpha }
        }
        val c = stage * 5
        coef[c] = b0 / a0
        coef[c + 1] = b1 / a0
        coef[c + 2] = b2 / a0
        coef[c + 3] = -2 * cw / a0
        coef[c + 4] = (1 - alpha) / a0
    }

    /** Above 0.9 the sound bends smoothly instead of being cut off hard (a lifted band can go over the top). */
    private fun soft(y: Double): Double {
        val a = abs(y)
        if (a <= SOFT) return y
        val s = SOFT + (1 - SOFT) * tanh((a - SOFT) / (1 - SOFT))
        return if (y < 0) -s else s
    }
}
