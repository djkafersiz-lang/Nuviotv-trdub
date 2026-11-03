package com.nuvio.tv.core.audio

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts a verified Turkish audio track from an openly accessible, non-DRM
 * media URL. The source video is never muxed into the output.
 *
 * Android's platform demuxer/muxer only supports codecs and containers exposed
 * by the device. Unsupported tracks fail closed instead of attaching a guess.
 */
class TurkishAudioTrackExtractor(private val context: Context) {
    suspend fun extract(mediaUrl: String): File = withContext(Dispatchers.IO) {
        require(mediaUrl.startsWith("https://", true) || mediaUrl.startsWith("http://", true)) {
            "Kaynak doğrudan HTTP(S) medya bağlantısı değil."
        }

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var output: File? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(mediaUrl, emptyMap())
            val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                val language = runCatching { format.getString(MediaFormat.KEY_LANGUAGE).orEmpty() }
                    .getOrDefault("")
                    .lowercase(Locale.ROOT)
                mime.startsWith("audio/") &&
                    (language == "tr" || language == "tur" ||
                        language.startsWith("tr-") || language.contains("turkish") ||
                        language.contains("türkçe"))
            } ?: throw IllegalStateException("Kaynakta dili Türkçe olarak işaretlenmiş ses kanalı bulunamadı.")

            val format = extractor.getTrackFormat(audioTrack)
            val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
            require(mime == "audio/mp4a-latm" || mime == "audio/3gpp" ||
                mime == "audio/amr-wb" || mime == "audio/opus") {
                "Türkçe ses bulundu ancak cihazın MP4 dışa aktarıcısı bu ses biçimini desteklemiyor: $mime"
            }

            val dir = File(context.cacheDir, "turkish_audio").apply { mkdirs() }
            output = File.createTempFile("tr_audio_", ".mp4", dir)
            muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            extractor.selectTrack(audioTrack)
            val outputTrack = muxer.addTrack(format)
            muxer.start()
            muxerStarted = true

            val maxBuffer = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceIn(256 * 1024, 8 * 1024 * 1024)
            } else 2 * 1024 * 1024
            val buffer = ByteBuffer.allocateDirect(maxBuffer)
            val info = MediaMuxer.BufferInfo()
            var samples = 0
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                require(size <= buffer.capacity()) { "Ses örneği tampon sınırını aşıyor." }
                info.offset = 0
                info.size = size
                info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
                info.flags = extractor.sampleFlags
                muxer.writeSampleData(outputTrack, buffer, info)
                samples++
                extractor.advance()
            }
            require(samples > 0) { "Türkçe ses kanalından örnek okunamadı." }
            muxer.stop()
            muxerStarted = false
            output
        } catch (error: Exception) {
            output?.delete()
            throw error
        } finally {
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }
}
