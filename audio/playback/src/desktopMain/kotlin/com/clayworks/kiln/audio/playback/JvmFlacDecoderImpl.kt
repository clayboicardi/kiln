// Desktop FLAC Decoder — wraps libFLAC's stream decoder via JNA.
// Implements the commonMain Decoder contract by allocating a fresh decoder
// per call to open(), wiring the JvmFlacDecodedStream's callbacks, and
// running process_until_end_of_metadata before handing the stream back.

package com.clayworks.kiln.audio.playback

import arrow.core.Either
import com.clayworks.kiln.audio.playback.nativeio.LibFlacBinding
import com.clayworks.kiln.audio.playback.nativeio.LibFlacLoader
import com.clayworks.kiln.library.source.AudioCodec
import com.clayworks.kiln.library.source.Playable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Construct the Desktop FLAC [Decoder]. Public factory keeps the
 * [JvmFlacDecoderImpl] class itself internal — consumers receive the
 * [Decoder] interface from commonMain only. The JNA libFLAC binding is
 * acquired via [LibFlacLoader.load], which is idempotent across calls.
 *
 * Wire into your DI graph as the single source of a `Decoder` capable of
 * `AudioCodec.FLAC`.
 */
fun createJvmFlacDecoder(): Decoder = JvmFlacDecoderImpl(LibFlacLoader.load())

internal class JvmFlacDecoderImpl(
    private val libFlac: LibFlacBinding,
) : Decoder {

    override fun supports(codec: AudioCodec): Boolean = codec == AudioCodec.FLAC

    override suspend fun open(playable: Playable): Either<DecoderError, DecodedStream> = withContext(Dispatchers.IO) {
        if (playable.codec != AudioCodec.FLAC) {
            return@withContext Either.Left(DecoderError.UnsupportedCodec(playable.codec))
        }
        val handle = libFlac.FLAC__stream_decoder_new()
            ?: return@withContext Either.Left(
                DecoderError.NativeBindingFailed("FLAC__stream_decoder_new returned null"),
            )

        // Parse the URI properly instead of stripping prefix. `java.net.URI` +
        // `java.io.File(URI)` handles percent-encoded chars, Windows drive letters
        // (file:///D:/...), and forward-slash-vs-backslash normalization on Windows.
        // The producer (LocalLibrarySourceMappers.fileSystemPathToFileUri) emits
        // RFC 8089 file URIs; if anything malformed slips through, we surface it
        // as DecoderError.IoError rather than passing garbage to libFLAC's fopen.
        val filename = try {
            java.io.File(java.net.URI(playable.uri)).absolutePath
        } catch (e: Exception) {
            libFlac.FLAC__stream_decoder_delete(handle)
            return@withContext Either.Left(
                DecoderError.IoError(
                    java.io.IOException("Cannot resolve URI to file path: ${playable.uri}", e),
                ),
            )
        }
        val stream = JvmFlacDecodedStream(libFlac, handle, playable.durationMs)

        val initStatus = libFlac.FLAC__stream_decoder_init_file(
            handle = handle,
            filename = filename,
            writeCallback = stream.writeCallback,
            metadataCallback = stream.metadataCallback,
            errorCallback = stream.errorCallback,
            clientData = null,
        )
        if (initStatus != LibFlacBinding.INIT_STATUS_OK) {
            libFlac.FLAC__stream_decoder_delete(handle)
            return@withContext Either.Left(
                when (initStatus) {
                    LibFlacBinding.INIT_STATUS_ERROR_OPENING_FILE -> DecoderError.IoError(
                        java.io.IOException("libFLAC could not open file: $filename"),
                    )
                    LibFlacBinding.INIT_STATUS_UNSUPPORTED_CONTAINER -> DecoderError.CorruptStream(
                        "libFLAC reports unsupported container: $filename",
                    )
                    else -> DecoderError.NativeBindingFailed(
                        "FLAC__stream_decoder_init_file returned $initStatus for $filename",
                    )
                },
            )
        }

        if (!libFlac.FLAC__stream_decoder_process_until_end_of_metadata(handle)) {
            libFlac.FLAC__stream_decoder_finish(handle)
            libFlac.FLAC__stream_decoder_delete(handle)
            return@withContext Either.Left(
                DecoderError.CorruptStream("process_until_end_of_metadata returned false for $filename"),
            )
        }

        Either.Right(stream)
    }
}
