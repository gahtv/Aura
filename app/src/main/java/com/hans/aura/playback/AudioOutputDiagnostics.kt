package com.hans.aura.playback

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import com.hans.aura.util.AuraLog
import com.hans.aura.util.AuraTag

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AudioOutputDiagnostics(
    context: Context,
    private val player: ExoPlayer
) : AnalyticsListener {
    private val context = context.applicationContext

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: DecoderReuseEvaluation?
    ) {
        AuraLog.i(
            AuraTag.PLAY,
            "audio input mime=${format.sampleMimeType} codecs=${format.codecs} " +
                "channels=${format.channelCount} sampleRate=${format.sampleRate}"
        )
        logCapabilities()
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long
    ) {
        AuraLog.i(AuraTag.PLAY, "audio decoder=$decoderName")
    }

    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig
    ) {
        AuraLog.i(
            AuraTag.PLAY,
            "audio track encoding=${audioTrackConfig.encoding} " +
                "channelConfig=${audioTrackConfig.channelConfig} " +
                "sampleRate=${audioTrackConfig.sampleRate} offload=${audioTrackConfig.offload} " +
                "tunneling=${audioTrackConfig.tunneling}"
        )
        logCapabilities()
    }

    private fun logCapabilities() {
        val attributes = player.audioAttributes
        // Re-query the default route; this is not the sink's private capability cache.
        val capabilities = runCatching {
            AudioCapabilities.getCapabilities(context, attributes, /* routedDevice= */ null)
        }.getOrElse {
            AuraLog.w(AuraTag.PLAY, "audio capabilities query failed: ${it.javaClass.simpleName}")
            return
        }
        AuraLog.i(
            AuraTag.PLAY,
            "audio capabilities(defaultRoute) usage=${attributes.usage} " +
                "contentType=${attributes.contentType} $capabilities"
        )
    }
}
