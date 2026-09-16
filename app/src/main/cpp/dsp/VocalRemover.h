#pragma once

#include "Biquad.h"

namespace av {

/**
 * Turns an ordinary stereo mix into a backing track.
 *
 * Lead vocals sit in the centre, so subtracting the mid channel removes them.
 * Doing that across the full spectrum also removes the kick, the bass and the
 * snare, which is why plain L-R karaoke sounds hollow. Here the cancellation
 * is confined to the band the voice actually occupies: below 160 Hz and above
 * 7 kHz the mid passes untouched, so the low end and the cymbals survive.
 *
 * At amount 0 the whole stage is bypassed, so importing a real instrumental
 * costs nothing.
 */
class VocalRemover {
public:
    void prepare(int sampleRate) {
        lowSplit_.prepare(sampleRate, 160.0f);
        highSplit_.prepare(sampleRate, 7000.0f);
        reset();
    }

    float amount = 0.0f;  // 0 = untouched, 1 = full centre cancellation

    inline void process(float &l, float &r) noexcept {
        if (amount <= 0.001f) return;

        const float mid = (l + r) * 0.5f;
        const float side = (l - r) * 0.5f;

        float low, rest, band, high;
        lowSplit_.process(mid, low, rest);
        highSplit_.process(rest, band, high);

        const float midOut = low + band * (1.0f - amount) + high;
        l = midOut + side;
        r = midOut - side;
    }

    void reset() { lowSplit_.reset(); highSplit_.reset(); }

private:
    LR4 lowSplit_, highSplit_;
};

}  // namespace av
