#pragma once

#include <algorithm>
#include <atomic>
#include <cstring>
#include <vector>

namespace av {

/**
 * Single producer, single consumer ring of interleaved stereo frames.
 *
 * The decoder thread pushes, the audio thread reads. The consumer can peek at
 * any frame ahead of its read cursor without consuming it, which is what the
 * time-stretcher needs to look ahead for a correlation match.
 */
class SpscRing {
public:
    void prepare(int capacityFrames) {
        int size = 1;
        while (size < capacityFrames) size <<= 1;
        mask_ = size - 1;
        buf_.assign((size_t) size * 2, 0.0f);
        clear();
    }

    void clear() {
        write_.store(0, std::memory_order_relaxed);
        read_.store(0, std::memory_order_relaxed);
        std::fill(buf_.begin(), buf_.end(), 0.0f);
    }

    int capacity() const { return mask_ + 1; }

    /** Producer side. Returns how many frames were accepted. */
    int push(const float *interleaved, int frames) {
        const int64_t w = write_.load(std::memory_order_relaxed);
        const int64_t r = read_.load(std::memory_order_acquire);
        const int free = capacity() - (int) (w - r);
        const int n = std::min(frames, free);

        for (int i = 0; i < n; ++i) {
            const size_t slot = (size_t) ((w + i) & mask_) * 2;
            buf_[slot] = interleaved[i * 2];
            buf_[slot + 1] = interleaved[i * 2 + 1];
        }
        write_.store(w + n, std::memory_order_release);
        return n;
    }

    /** Consumer side. Frames sitting between the read cursor and the writer. */
    int available() const {
        return (int) (write_.load(std::memory_order_acquire) -
                      read_.load(std::memory_order_relaxed));
    }

    int space() const { return capacity() - available(); }

    /** Consumer side. Reads a frame ahead of the cursor without consuming it. */
    inline void peek(int frameOffset, float &l, float &r) const noexcept {
        const int64_t idx = read_.load(std::memory_order_relaxed) + frameOffset;
        const size_t slot = (size_t) (idx & mask_) * 2;
        l = buf_[slot];
        r = buf_[slot + 1];
    }

    /** Consumer side. Drops frames the reader is done with. */
    inline void advance(int frames) noexcept {
        read_.store(read_.load(std::memory_order_relaxed) + frames,
                    std::memory_order_release);
    }

    /** Consumer side convenience: copy out and consume. */
    int pop(float *out, int frames) {
        const int n = std::min(frames, available());
        for (int i = 0; i < n; ++i) peek(i, out[i * 2], out[i * 2 + 1]);
        advance(n);
        return n;
    }

private:
    std::vector<float> buf_;
    int mask_ = 0;
    std::atomic<int64_t> write_{0};
    std::atomic<int64_t> read_{0};
};

}  // namespace av
