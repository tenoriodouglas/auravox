package com.auravox.ui

/**
 * Ring of recent (songPositionMs, midi) samples for the pitch lane.
 *
 * Compose state is per object, and pushing 60 entries a second into a snapshot
 * list would invalidate the whole tree each frame. A plain ring plus one
 * version counter keeps the Canvas the only thing that redraws.
 */
class PitchTrail(private val capacity: Int = 600) {

    private val time = DoubleArray(capacity)
    private val midi = FloatArray(capacity)
    private var head = 0
    private var size = 0

    var version = 0
        private set

    fun push(positionMs: Double, midiValue: Float) {
        time[head] = positionMs
        midi[head] = midiValue
        head = (head + 1) % capacity
        if (size < capacity) ++size
        ++version
    }

    fun clear() {
        size = 0
        head = 0
        ++version
    }

    val count: Int get() = size

    /** Index 0 is the oldest sample still held. */
    fun timeAt(i: Int): Double = time[(head - size + i + capacity * 2) % capacity]
    fun midiAt(i: Int): Float = midi[(head - size + i + capacity * 2) % capacity]
}
