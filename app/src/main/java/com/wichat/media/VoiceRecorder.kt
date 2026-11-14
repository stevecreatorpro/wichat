package com.wichat.media

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/** Records voice notes as Opus (Android 10+) or AAC, about 240 KB per minute. */
class VoiceRecorder(private val context: Context) {

    class Recording(val file: File, val durationMs: Long)

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    val isRecording: Boolean get() = recorder != null

    /** Caller must hold RECORD_AUDIO. */
    fun start() {
        check(recorder == null) { "Already recording" }
        val opus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val target = File(context.cacheDir, "voice-${System.currentTimeMillis()}." + if (opus) "ogg" else "m4a")
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        mr.setAudioSource(MediaRecorder.AudioSource.MIC)
        if (opus) {
            mr.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
        } else {
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        }
        mr.setAudioEncodingBitRate(32_000)
        mr.setAudioSamplingRate(48_000)
        mr.setOutputFile(target.absolutePath)
        mr.prepare()
        mr.start()
        recorder = mr
        file = target
        startedAt = SystemClock.elapsedRealtime()
    }

    /** Returns null if the recording was too short to keep. */
    fun stop(): Recording? {
        val mr = recorder ?: return null
        val target = file
        val duration = SystemClock.elapsedRealtime() - startedAt
        recorder = null
        file = null
        val ok = runCatching { mr.stop() }.isSuccess
        mr.release()
        if (!ok || target == null || duration < MIN_DURATION_MS) {
            target?.delete()
            return null
        }
        return Recording(target, duration)
    }

    fun cancel() {
        stop()?.file?.delete()
    }

    fun elapsedMs(): Long = if (recorder != null) SystemClock.elapsedRealtime() - startedAt else 0

    private companion object {
        const val MIN_DURATION_MS = 700L
    }
}
