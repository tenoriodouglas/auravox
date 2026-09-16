#pragma once

#include <algorithm>
#include <vector>

namespace av {

/**
 * Mono FIFO between the microphone stream and the output callback.
 *
 * Input and output are two streams with two clocks. Reading the input
 * directly inside the output callback means that whenever the callback
 * arrives a hair before the capture burst finished, the read comes up short —
 * and zero-filling the tail splices silence into the middle of the voice,
 * several times a second. That is the static.
 *
 * Draining the input into this FIFO and holding a cushion of about one burst
 * absorbs the jitter: the callback always finds a full block. The cushion
 * costs its own length in latency, which the engine adds to the alignment.
 *
 * Only the audio thread touches this, so there is nothing to synchronise.
 */
class InputFifo {
public:
    void prepare(int capacityFrames) {
        int size = 1;
        while (size < capacityFrames) size <<= 1;
        buf_.assign((size_t) size, 0.0f);
        mask_ = size - 1;
        clear();
    }

    void clear() { write_ = 0; read_ = 0; }

    int capacity() const { return mask_ + 1; }
    int available() const { return (int) (write_ - read_); }
    int space() const { return capacity() - available(); }

    /** Appends frames. Anything past capacity pushes the oldest out. */
    void write(const float *src, int n) noexcept {
        if (n <= 0) return;
        if (n > capacity()) {
            src += n - capacity();
            n = capacity();
        }
        for (int i = 0; i < n; ++i) buf_[(size_t) ((write_ + i) & mask_)] = src[i];
        write_ += n;
        const int over = available() - capacity();
        if (over > 0) read_ += over;
    }

    /** Copies out and consumes. False when the FIFO is short, and nothing moves. */
    bool read(float *dst, int n) noexcept {
        if (available() < n) return false;
        for (int i = 0; i < n; ++i) dst[i] = buf_[(size_t) ((read_ + i) & mask_)];
        read_ += n;
        return true;
    }

    /** Copies what there is and zero-fills the rest. Returns frames delivered. */
    int readPartial(float *dst, int n) noexcept {
        const int got = std::min(n, available());
        for (int i = 0; i < got; ++i) dst[i] = buf_[(size_t) ((read_ + i) & mask_)];
        std::fill(dst + got, dst + n, 0.0f);
        read_ += got;
        return got;
    }

    /** Throws away the oldest frames, to stop a slow consumer adding latency. */
    void skip(int n) noexcept {
        const int drop = std::min(n, available());
        read_ += drop;
    }

private:
    std::vector<float> buf_;
    int mask_ = 0;
    int64_t write_ = 0, read_ = 0;
};

/**
 * Priming and drift policy for the capture FIFO.
 *
 * Kept next to the FIFO rather than inline in the callback so it can be driven
 * by a test with the same jitter a real device produces. Two rules:
 *
 * Once primed, a block is either wholly continuous audio or refused. A
 * partially filled block is the static.
 *
 * The cushion grows whenever it is proved too small. How far the capture and
 * playback clocks drift apart between callbacks is a property of the device,
 * not something worth guessing at build time: a phone that never starves keeps
 * the lowest latency, and one that does settles within a second on the slack
 * it actually needs.
 */
struct FifoPolicy {
    int burst = 0;       // one capture burst, the unit the cushion grows by
    int cushion = 0;     // current slack held ahead of the consumer
    int maxCushion = 0;  // ceiling, so a broken device cannot add latency forever
    bool primed = false;

    void configure(int burstFrames, int maxBursts = 4) {
        burst = burstFrames > 0 ? burstFrames : 1;
        cushion = burst;
        maxCushion = burst * maxBursts;
        primed = false;
    }

    void reset() { primed = false; }

    /** Drops the backlog a slow consumer would otherwise turn into latency. */
    void trim(InputFifo &fifo, int frames) noexcept {
        const int maxFill = frames + cushion * 3;
        if (fifo.available() > maxFill) fifo.skip(fifo.available() - maxFill);
    }

    /**
     * Fills dst with one block. False means the FIFO could not serve a whole
     * one and dst holds silence: the caller outputs that rather than splicing
     * zeros into the middle of the voice.
     */
    bool serve(InputFifo &fifo, float *dst, int frames) noexcept {
        if (!primed) {
            if (fifo.available() < frames + cushion) {
                std::fill(dst, dst + frames, 0.0f);
                return false;
            }
            primed = true;
        }
        if (fifo.read(dst, frames)) return true;

        // The cushion was not enough for this device. Take the hit once, hold
        // more slack, and re-prime rather than limping one short block at a
        // time, which is what makes the tearing continuous.
        fifo.readPartial(dst, frames);
        primed = false;
        if (cushion + burst <= maxCushion) cushion += burst;
        return false;
    }
};

}  // namespace av
