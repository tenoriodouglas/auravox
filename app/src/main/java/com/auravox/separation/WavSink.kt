package com.auravox.separation

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Writes a stereo 16-bit WAV as the samples arrive.
 *
 * The length in the header is not known until the last split has been written,
 * so the two size fields are left at zero and patched on close. That is what
 * lets separation stream straight to disk instead of holding a whole song's
 * output in memory.
 *
 * Sixteen bits rather than float: a backing track is going to be played, not
 * processed further, and the file is already forty megabytes a song.
 */
class WavSink(private val file: File, private val sampleRate: Int) : Closeable {

    private val stream = BufferedOutputStream(file.outputStream(), 1 shl 16)
    private val bytes = ByteBuffer.allocate(1 shl 16).order(ByteOrder.LITTLE_ENDIAN)
    private var frames = 0L

    init {
        stream.write(header(0))
    }

    fun write(left: FloatArray, right: FloatArray, count: Int) {
        var i = 0
        while (i < count) {
            if (bytes.remaining() < 4) flushBuffer()
            bytes.putShort(toPcm(left[i]))
            bytes.putShort(toPcm(right[i]))
            ++i
        }
        frames += count
    }

    /**
     * Finishes the file and patches the two lengths.
     *
     * The stream is closed in a finally: a cache partition filling up throws
     * out of the final flush, and leaving the descriptor open then meant the
     * caller's own cleanup skipped the second stem's file as well.
     */
    override fun close() {
        try {
            flushBuffer()
            stream.flush()
        } finally {
            runCatching { stream.close() }
        }
        RandomAccessFile(file, "rw").use { out ->
            val dataBytes = frames * 4L
            out.seek(4)
            out.write(sizeLe(36L + dataBytes))
            out.seek(40)
            out.write(sizeLe(dataBytes))
        }
    }

    private fun flushBuffer() {
        if (bytes.position() > 0) {
            stream.write(bytes.array(), 0, bytes.position())
            bytes.clear()
        }
    }

    /** Clipped rather than wrapped: a mask can push a peak past full scale. */
    private fun toPcm(v: Float): Short {
        val scaled = (v * 32767.0f)
        return when {
            scaled >= 32767.0f -> 32767
            scaled <= -32768.0f -> -32768
            else -> scaled.toInt().toShort()
        }
    }

    private fun header(dataBytes: Int): ByteArray {
        val buffer = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray())
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16)
        buffer.putShort(1)                      // PCM
        buffer.putShort(2)                      // stereo
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 4)           // byte rate
        buffer.putShort(4)                      // block align
        buffer.putShort(16)                     // bits
        buffer.put("data".toByteArray())
        buffer.putInt(dataBytes)
        return buffer.array()
    }

    /**
     * A WAV size field: four bytes, unsigned, little endian.
     *
     * Written from a Long and capped rather than narrowed to Int. Narrowing
     * wrapped negative past about three and a half hours of stereo, and a
     * negative length is a file the platform decoder reports as having no
     * duration — which would have looked like separation producing silence.
     */
    private fun sizeLe(value: Long): ByteArray {
        val v = value.coerceIn(0L, 0xFFFFFFFFL)
        return byteArrayOf(
            (v and 0xFF).toByte(),
            ((v shr 8) and 0xFF).toByte(),
            ((v shr 16) and 0xFF).toByte(),
            ((v shr 24) and 0xFF).toByte()
        )
    }
}
