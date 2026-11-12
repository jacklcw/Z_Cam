package com.zeekr.sentrycam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.zeekr.sentrycam.sound.CarSpeakers

/** Live announcement: the cabin microphone is fed straight to the outside speaker while it runs (no recording, no file). */
object LivePa {
    private const val RATE = 48_000
    private const val GAIN = 4f          // digital boost on top of full track volume: the microphone is quiet next to the exterior speaker
    @Volatile private var running = false
    @Volatile var level = 0f          // 0..1 peak of the last block, for the meter
    @Volatile var status = ""         // empty = fine, else why it is not live
    private var thread: Thread? = null

    fun live() = running

    fun start(ctx: Context) {
        if (running) return
        status = ""
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { status = "Allow microphone in the Z Cam app"; return }
        val dev = CarSpeakers.find(ctx, CarSpeakers.OUTSIDE_NOTIFY) ?: CarSpeakers.find(ctx, CarSpeakers.OUTSIDE_PLAYBACK)
        if (dev == null) { status = "No outside speaker found"; return }
        running = true
        thread = Thread({
            var rec: AudioRecord? = null; var trk: AudioTrack? = null
            try {
                val inMin = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                rec = AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, inMin * 2)
                if (rec.state != AudioRecord.STATE_INITIALIZED) { status = "Microphone not available"; return@Thread }
                val outMin = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
                trk = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(AudioTrack.MODE_STREAM).setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY).setBufferSizeInBytes(outMin * 2).build()
                if (!trk.setPreferredDevice(dev)) { status = "Outside speaker refused"; return@Thread }
                val buf = ShortArray(inMin / 2 / 2 + 240)
                try { trk.setVolume(AudioTrack.getMaxVolume()) } catch (_: Throwable) {}
                rec.startRecording(); trk.play()
                while (running) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) { status = "Microphone read failed ($n)"; break }
                    var pk = 0
                    for (i in 0 until n) { val a = Math.abs(buf[i].toInt()); if (a > pk) pk = a }
                    level = pk / 32768f
                    for (i in 0 until n) { val x = buf[i] * GAIN; buf[i] = (if (x > 32767f) 32767f else if (x < -32768f) -32768f else x).toInt().toShort() }   // clipped, not wrapped
                    trk.write(buf, 0, n)
                }
            } catch (e: Throwable) { status = "PA failed: ${e.message ?: ""}" }
            finally {
                running = false; level = 0f
                try { rec?.stop() } catch (_: Throwable) {}; try { rec?.release() } catch (_: Throwable) {}
                try { trk?.stop() } catch (_: Throwable) {}; try { trk?.release() } catch (_: Throwable) {}
            }
        }, "live-pa").also { it.start() }
    }

    fun stop() { running = false }
}
