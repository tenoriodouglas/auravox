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

    override fun close() {
        flushBuffer()
        stream.flush()
        stream.close()
        // Patch the two lengths now that they are known
        RandomAccessFile(file, "rw").use { out ->
            val dataBytes = frames * 4L
            out.seek(4)
            out.write(intLe((36L + dataBytes).toInt()))
            out.seek(40)
            out.write(intLe(dataBytes.toInt()))
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

    private fun intLe(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte()
    )
}
