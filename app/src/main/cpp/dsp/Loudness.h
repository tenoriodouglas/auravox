#pragma once

#include <cmath>

#include "Biquad.h"

namespace av {

/**
 * How loud a bus sounds, for balancing one against another.
 *
 * Deliberately not a LUFS meter. A real one needs the exact shelf and
 * high-pass the standard tabulates, 400 ms blocks with 75% overlap, and a
 * two-stage gate — and none of that would change the only number this is for,
 * which is the difference between two buses. Both go through the same
 * weighting, so whatever the filter gets wrong it gets wrong equally and
 * cancels in the subtraction.
 *
 * What the weighting is for: a kick drum and a voice at the same peak level
 * are nowhere near the same loudness, and balancing on peaks is why an
 * amateur mix comes back with the voice buried under a bass line. Rolling off
 * the bottom and lifting the top before measuring is what makes the number
 * agree with what the ear says.
 *
 * The window is a one-pole average rather than a block queue: this runs on the
 * audio thread, a readout that lags a few hundred milliseconds is the right
 * behaviour for a fader anyway, and a queue would be an allocation.
 */
class Loudness {
public:
    void prepare(int sampleRate, float windowMs = 2000.0f) {
        // The standard's own frequencies and gain, through the shelf and
        // high-pass that are already here
        shelf_.highShelf(sampleRate, 1681.97f, 4.0f);
        highPass_.highPass(sampleRate, 38.14f, 0.5f);
        coef_ = timeCoef(windowMs, sampleRate);
        reset();
    }

    inline void push(float x) noexcept {
        const float weighted = highPass_.process(shelf_.process(x));
        meanSquare_ += (weighted * weighted - meanSquare_) * coef_;
    }

    /** Pushes a stereo frame, summed the way a loudness measure sums channels. */
    inline void pushStereo(float l, float r) noexcept {
        push((l + r) * 0.5f);
    }

    /** Weighted level in dB. Silence reads as the floor, not as negative infinity. */
    float db() const noexcept {
        return meanSquare_ > kFloor
            ? 10.0f * std::log10(meanSquare_) : kFloorDb;
    }

    bool hasSignal() const noexcept { return meanSquare_ > kFloor * 100.0f; }

    void reset() {
        shelf_.reset();
        highPass_.reset();
        meanSquare_ = 0.0f;
    }

private:
    static constexpr float kFloor = 1e-10f;
    static constexpr float kFloorDb = -100.0f;

    Biquad shelf_, highPass_;
    float meanSquare_ = 0.0f;
    float coef_ = 0.001f;
};

}  // namespace av
