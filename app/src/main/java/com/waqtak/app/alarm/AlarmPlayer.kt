package com.waqtak.app.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.waqtak.app.R
import com.waqtak.app.data.Tone

fun Tone.rawRes(): Int = when (this) {
    Tone.PULSE -> R.raw.tone_pulse
    Tone.CHIME -> R.raw.tone_chime
    Tone.RISE -> R.raw.tone_rise
}

/**
 * Plays an alarm on the ALARM stream (USAGE_ALARM), so it rings in silent/vibrate ringer
 * modes. Owns every resource it takes (player, vibration, audio focus, wake lock) and
 * [stop] releases all of them; it is idempotent and safe to call from any stop path.
 */
class AlarmPlayer(context: Context) {
    private val ctx = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val audio = ctx.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var vibrating = false
    private var ramp: Runnable? = null

    val isActive: Boolean get() = player != null || vibrating

    private val vibrator: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            ctx.getSystemService(Vibrator::class.java)
        }
    }

    companion object {
        val ALARM_ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        private const val TAG = "WaqtakPlayer"
        private const val WAKE_LOCK_TIMEOUT = 30 * 60_000L
    }

    fun start(tone: Tone, volume: Float, crescendo: Boolean, vibrate: Boolean, loop: Boolean = true) {
        stop()
        val target = volume.coerceIn(0.05f, 1f)
        wakeLock = ctx.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "waqtak:ringing")
            .apply { setReferenceCounted(false); acquire(WAKE_LOCK_TIMEOUT) }
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(ALARM_ATTRIBUTES)
            .setOnAudioFocusChangeListener { }
            .build()
            .also { audio.requestAudioFocus(it) }
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(ALARM_ATTRIBUTES)
                ctx.resources.openRawResourceFd(tone.rawRes()).use { afd ->
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
                isLooping = loop
                prepare()
                val initial = if (crescendo) target * 0.2f else target
                setVolume(initial, initial)
                start()
            }
            if (crescendo) startRamp(target)
        } catch (e: Exception) {
            Log.e(TAG, "cannot play tone", e)
            player?.release()
            player = null
        }
        if (vibrate) startVibration(loop)
    }

    private fun startRamp(target: Float) {
        var step = 0
        val steps = 20
        val r = object : Runnable {
            override fun run() {
                val p = player ?: return
                step++
                val v = target * (0.2f + 0.8f * step / steps)
                runCatching { p.setVolume(v, v) }
                if (step < steps) handler.postDelayed(this, 1_000)
            }
        }
        ramp = r
        handler.postDelayed(r, 1_000)
    }

    private fun startVibration(loop: Boolean) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 700, 500, 700, 900), if (loop) 0 else -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, ALARM_ATTRIBUTES)
        }
        vibrating = true
    }

    fun stop() {
        ramp?.let { handler.removeCallbacks(it) }
        ramp = null
        player?.let {
            runCatching { if (it.isPlaying) it.stop() }
            it.release()
        }
        player = null
        if (vibrating) {
            runCatching { vibrator?.cancel() }
            vibrating = false
        }
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}

/** Short, non-looping preview for the settings screen. */
object TonePreview {
    private var player: AlarmPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val stopper = Runnable { stop() }

    fun play(ctx: Context, tone: Tone, volume: Float, vibrate: Boolean) {
        stop()
        player = AlarmPlayer(ctx).also { it.start(tone, volume, crescendo = false, vibrate = vibrate, loop = false) }
        handler.postDelayed(stopper, 4_000)
    }

    fun stop() {
        handler.removeCallbacks(stopper)
        player?.stop()
        player = null
    }
}
