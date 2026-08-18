package com.meapet.mobile.live2d.audio

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.MediaPlayer
import android.util.Log

/**
 * 语音播放器。
 * 从 assets/voice/ 加载并播放 .wav 文件。
 * 同步 prepare — 由 GL 线程调用，阻塞时间在可接受范围。
 *
 * @param voiceDir 语音子目录，如 "voice/upper"、"voice/lower_left"、"voice/lower_right"
 */
class VoicePlayer(
    private val context: Context,
    private val voiceDir: String
) {
    private var player: MediaPlayer? = null

    companion object {
        private const val TAG = "VoicePlayer"
    }

    /** 列出该子目录下所有 .wav 文件。 */
    fun listVoices(): List<String> {
        return try {
            context.assets.list(voiceDir)
                ?.filter { it.endsWith(".wav") }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun play(filename: String) {
        try {
            stop()
            val afd: AssetFileDescriptor = context.assets.openFd("$voiceDir/$filename")
            player = MediaPlayer().apply {
                setDataSource(afd)
                setOnCompletionListener { release() }
                setOnErrorListener { _, what, extra ->
                    Log.w(TAG, "MediaPlayer error: $what / $extra"); true
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play $filename", e)
        }
    }

    fun stop() {
        try { player?.release() } catch (_: Exception) {}
        player = null
    }
}
