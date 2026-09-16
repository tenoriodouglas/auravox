#pragma once

#include <algorithm>
#include <cmath>
#include <vector>
#include "../track/RingBuffer.h"
#include "Biquad.h"

namespace av {

/**
 * Key and tempo change for the backing track: WSOLA followed by a resampler.
 *
 * Two independent stages compose into both effects:
 *   - WSOLA walks the source at `consumeRate` source frames per output frame
 *     and overlap-adds pitch-aligned grains, changing speed but not pitch
 *   - a cubic resampler then reads that stream at `resampleRate`, changing
 *     both together
 *
 * For a pitch multiplier P, a tempo multiplier T and a rate conversion factor
 * F = trackRate / deviceRate, the settings are resampleRate = F * P and
 * consumeRate = T / P. With no key or tempo change and matching rates both
 * land on exactly 1.0, where the whole path is bit-transparent: the Hann
 * window at 50% overlap sums to one and the cubic kernel at phase zero returns
 * the sample untouched.
 *
 * The grid position advances by a fixed analysis hop regardless of where the
 * correlation search lands, so the playhead never drifts away from the song.
 * A karaoke app cannot tolerate drift: lyrics and scoring both hang off it.
 */
class TimeScale {
public:
    void prepare(int /*sampleRate*/) {
        window_.resize(kGrain);
        for (int i = 0; i < kGrain; ++i) {
            // Periodic Hann: w[n] + w[n + hop] == 1, so overlap-add is unity
            window_[i] = 0.5f - 0.5f * std::cos(2.0f * kPi * (float) i / (float) kGrain);
        }
        acc_.assign((size_t) kGrain * 2, 0.0f);
        tail_.assign((size_t) kHop, 0.0f);
        fifo_.assign((size_t) kFifo * 2, 0.0f);
        reset();
    }

    float consumeRate = 1.0f;   // source frames per stretched frame
    float resampleRate = 1.0f;  // stretched frames per output frame

    void reset() {
        std::fill(acc_.begin(), acc_.end(), 0.0f);
        std::fill(tail_.begin(), tail_.end(), 0.0f);
        std::fill(fifo_.begin(), fifo_.end(), 0.0f);
        target_ = 0.0;
        fifoWrite_ = 0;
        fifoRead_ = 0.0;
        consumed_ = 0;
        primed_ = false;
    }

    /** Source frames walked so far, counting the ideal grid, not the search. */
    double sourcePosition() const { return (double) consumed_ + target_; }

    /**
     * Renders `frames` stereo frames. Returns how many carry real audio; the
     * rest are silence because the decoder fell behind.
     */
    int render(SpscRing &src, float *outLR, int frames) noexcept {
        const double needUntil = fifoRead_ + (double) frames * resampleRate;
        const int64_t needWrite = (int64_t) std::floor(needUntil) + 3;

        while (fifoWrite_ < needWrite) {
            if (!produceHop(src)) break;
        }

        // Cubic needs one frame of history and two of lookahead
        const int64_t usable = fifoWrite_ - 2;
        int produced = 0;
        for (int i = 0; i < frames; ++i) {
            const int64_t i0 = (int64_t) std::floor(fifoRead_);
            if (i0 < 0 || i0 >= usable) {
                outLR[i * 2] = 0.0f;
                outLR[i * 2 + 1] = 0.0f;
                continue;
            }
            const float t = (float) (fifoRead_ - (double) i0);
            outLR[i * 2] = cubic(i0, 0, t);
            outLR[i * 2 + 1] = cubic(i0, 1, t);
            fifoRead_ += resampleRate;
            ++produced;
        }
        return produced;
    }

private:
    static constexpr int kHop = 512;
    static constexpr int kGrain = kHop * 2;
    static constexpr int kSearch = 480;   // +- 10 ms covers one period down to 50 Hz
    static constexpr int kCorrDecim = 4;
    static constexpr int kFifo = 1 << 14;
    static constexpr int64_t kFifoMask = kFifo - 1;

    inline float fifoAt(int64_t frame, int ch) const noexcept {
        return fifo_[(size_t) ((frame & kFifoMask) * 2 + ch)];
    }

    inline float cubic(int64_t i0, int ch, float t) const noexcept {
        const float p0 = fifoAt(i0 - 1, ch);
        const float p1 = fifoAt(i0, ch);
        const float p2 = fifoAt(i0 + 1, ch);
        const float p3 = fifoAt(i0 + 2, ch);
        return 0.5f * ((2.0f * p1) + (-p0 + p2) * t +
                       (2.0f * p0 - 5.0f * p1 + 4.0f * p2 - p3) * t * t +
                       (-p0 + 3.0f * p1 - 3.0f * p2 + p3) * t * t * t);
    }

    /** One analysis hop. False means the source ring does not hold enough yet. */
    bool produceHop(SpscRing &src) noexcept {
        const int need = (int) std::ceil(target_) + kSearch + kGrain + 2;
        if (src.available() < need) return false;

        const int base = findOffset(src);

        // Overlap-add one windowed grain into the accumulator
        for (int i = 0; i < kGrain; ++i) {
            float l, r;
            src.peek(base + i, l, r);
            const float w = window_[i];
            acc_[(size_t) i * 2] += l * w;
            acc_[(size_t) i * 2 + 1] += r * w;
        }

        // Emit the completed half and slide the accumulator down by one hop
        for (int i = 0; i < kHop; ++i) {
            const size_t dst = (size_t) ((fifoWrite_ + i) & kFifoMask) * 2;
            fifo_[dst] = acc_[(size_t) i * 2];
            fifo_[dst + 1] = acc_[(size_t) i * 2 + 1];
        }
        fifoWrite_ += kHop;

        std::copy(acc_.begin() + kHop * 2, acc_.end(), acc_.begin());
        std::fill(acc_.begin() + kHop * 2, acc_.end(), 0.0f);

        // The natural continuation of what was just emitted becomes the
        // template the next grain has to match
        for (int i = 0; i < kHop; ++i) {
            float l, r;
            src.peek(base + kHop + i, l, r);
            tail_[(size_t) i] = l + r;
        }
        primed_ = true;

        target_ += (double) kHop * (double) consumeRate;

        // Keep the grid position near the ring cursor so peeks stay in range
        const int advance = (int) (target_ - (double) kSearch);
        if (advance > 0) {
            src.advance(advance);
            target_ -= (double) advance;
            consumed_ += advance;
        }
        return true;
    }

    /**
     * Picks the grain start that lines up best with the previous tail.
     *
     * Search runs on a 4x decimated mono sum, and a small distance penalty
     * keeps offset zero winning ties. That penalty is what makes the unity
     * rate path transparent instead of merely close.
     */
    int findOffset(const SpscRing &src) const noexcept {
        const int centre = (int) std::lround(target_);
        if (!primed_ || std::fabs(consumeRate - 1.0f) < 1e-6f) return centre;

        float bestScore = -1e30f;
        int bestOffset = 0;

        for (int off = -kSearch; off <= kSearch; off += 2) {
            const int start = centre + off;
            if (start < 0) continue;

            float dot = 0.0f, energy = 1e-9f;
            for (int i = 0; i < kHop; i += kCorrDecim) {
                float l, r;
                src.peek(start + i, l, r);
                const float s = l + r;
                dot += s * tail_[(size_t) i];
                energy += s * s;
            }
            const float score = dot / std::sqrt(energy) -
                                0.002f * (float) std::abs(off);
            if (score > bestScore) { bestScore = score; bestOffset = off; }
        }
        return centre + bestOffset;
    }

    std::vector<float> window_, acc_, tail_, fifo_;
    double target_ = 0.0;
    double fifoRead_ = 0.0;
    int64_t fifoWrite_ = 0;
    int64_t consumed_ = 0;
    bool primed_ = false;
};

}  // namespace av
