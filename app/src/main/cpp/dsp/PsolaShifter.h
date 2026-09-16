#pragma once

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <vector>
#include "Biquad.h"

namespace av {

/**
 * Time-domain PSOLA pitch shifter with independent formant control.
 *
 * The method rests on two separate clocks:
 *   - analysis marks advance by one pitch period through the input
 *   - synthesis marks advance by period/pitchRatio through the output
 *
 * Each synthesis mark copies the grain sitting at the most recent analysis
 * mark and overlap-adds it at the synthesis position. That time displacement
 * is what changes the pitch. Grains are never resampled, so the spectral
 * envelope survives intact and the voice keeps its character.
 *
 * formantRatio resamples the grain interior only, sliding the envelope without
 * touching perceived pitch. 1.0 natural, >1 brighter, <1 darker.
 *
 * Every clock here is fractional, window included. A detected pitch almost
 * never lands on a whole number of samples, and snapping either the period or
 * the grain position to an integer leaves the original fundamental partly
 * uncancelled: at an octave up it comes back only 5 to 10 dB under the shifted
 * note, as a ghost an octave below. Buffers are integer indexed, so a grain
 * still writes to whole slots, but both the window phase and the source
 * position are evaluated at the true fractional offset of each slot.
 *
 * Latency is maxPeriod + 2 samples, about 12 ms at an 80 Hz floor. The two
 * extra samples cover a grain that starts just before the placement point.
 */
class PsolaShifter {
public:
    void prepare(int sampleRate, float minHz = 80.0f) {
        maxPeriod_ = (int) (sampleRate / minHz) + 2;

        int size = 1;
        while (size < maxPeriod_ * 8) size <<= 1;
        mask_ = size - 1;

        input_.assign((size_t) size, 0.0f);
        accum_.assign((size_t) size, 0.0f);

        // Hann table addressed by normalized grain position
        hann_.resize(kWindowTable + 1);
        for (int i = 0; i <= kWindowTable; ++i) {
            hann_[i] = 0.5f - 0.5f * std::cos(2.0f * kPi * i / kWindowTable);
        }
        reset();
    }

    float pitchRatio = 1.0f;
    float formantRatio = 1.0f;

    /** Detected period in samples, fractional. Zero means unvoiced. */
    void setPeriod(float p) {
        if (p < 0.0f) p = 0.0f;
        if (p > (float) maxPeriod_) p = (float) maxPeriod_;
        period_ = p;
    }

    int latencySamples() const { return maxPeriod_ + 2; }

    inline float process(float x) noexcept {
        input_[(int) (t_ & mask_)] = x;

        const bool active = period_ > 1.0f &&
                            (pitchRatio != 1.0f || formantRatio != 1.0f);

        if (active) {
            // Analysis clock: steps one pitch period at a time through the input
            if ((double) t_ >= nextMark_) {
                markPos_ = nextMark_;
                nextMark_ += (double) period_;
            }
            // Resync after an unvoiced gap or a large period jump
            if ((double) t_ - markPos_ > (double) period_ * 2.0) {
                markPos_ = (double) t_;
                nextMark_ = (double) t_ + (double) period_;
            }

            // Synthesis clock: fires faster or slower than the analysis clock.
            // Spacing is kept fractional; rounding it to an integer makes the
            // overlap-add ripple beat against the period and breeds subharmonics.
            if ((double) t_ >= nextSynth_) {
                double spacing = (double) period_ / (double) pitchRatio;
                if (spacing < 2.0) spacing = 2.0;

                // Grain half-length follows the synthesis spacing, not the
                // analysis period, so the overlap stays at 50% whichever way
                // the pitch moves. Sizing grains by the input period instead
                // stacks four copies of the same grain when shifting up, and
                // the odd harmonics comb out: an octave up then comes back
                // only 3 dB above the note it was supposed to leave behind.
                double half = spacing;
                if (half > (double) maxPeriod_) half = (double) maxPeriod_;

                emitGrain(markPos_, nextSynth_, half, (float) (spacing / half));
                nextSynth_ += spacing;
                if (nextSynth_ < (double) t_) nextSynth_ = (double) t_ + spacing;
            }
        } else {
            // Unvoiced or bypassed: dry signal straight into the accumulator
            accum_[(int) (t_ & mask_)] += x;
            nextSynth_ = (double) t_;
            nextMark_ = (double) t_;
            markPos_ = (double) t_;
        }

        // Output trails the placement point by one worst-case period, so every
        // grain is fully written before its slots are read
        const int outIdx = (int) ((t_ - maxPeriod_ - 2) & mask_);
        const float out = accum_[outIdx];
        accum_[outIdx] = 0.0f;

        ++t_;
        return out;
    }

    void reset() {
        std::fill(input_.begin(), input_.end(), 0.0f);
        std::fill(accum_.begin(), accum_.end(), 0.0f);
        t_ = (int64_t) maxPeriod_ * 4;  // start clear of the wrap-around
        nextMark_ = (double) t_;
        nextSynth_ = (double) t_;
        markPos_ = (double) t_;
        period_ = 0.0f;
    }

private:
    static constexpr int kWindowTable = 2048;

    /**
     * Reads a two-period Hann grain centred on the analysis mark and
     * overlap-adds it centred on the synthesis position.
     *
     * Gain compensation: Hann grains of half-length H summed at spacing S
     * give a constant of H/S, so the caller passes S/H to hold the level
     * steady. At the usual H == S that is exactly 1.
     */
    inline void emitGrain(double analysisCentre, double synthCentre,
                          double half, float gain) noexcept {
        const double len = half * 2.0;
        if (len < 4.0) return;

        // Grain spans [centre - P, centre + P) in fractional time; u is where
        // each integer output slot falls inside that span
        const double left = synthCentre - half;
        const int64_t first = (int64_t) std::ceil(left);
        const int count = (int) len;
        const double winScale = (double) kWindowTable / len;

        for (int i = 0; i < count; ++i) {
            const int64_t dst = first + i;
            const double u = (double) dst - left;
            if (u < 0.0 || u >= len) continue;

            int wi = (int) (u * winScale);
            if (wi > kWindowTable) wi = kWindowTable;
            const float w = hann_[wi] * gain;

            // Offset runs -P..+P around the centre. Scaling it by formantRatio
            // resamples the grain interior, which moves formants, not pitch.
            const double src = analysisCentre + (u - half) * (double) formantRatio;
            const int64_t i0 = (int64_t) std::floor(src);
            const float frac = (float) (src - (double) i0);
            const float a = input_[(int) (i0 & mask_)];
            const float b = input_[(int) ((i0 + 1) & mask_)];
            const float s = a + (b - a) * frac;

            accum_[(int) (dst & mask_)] += s * w;
        }
    }

    std::vector<float> input_, accum_, hann_;
    int mask_ = 0, maxPeriod_ = 0;
    float period_ = 0.0f;
    int64_t t_ = 0;
    double nextMark_ = 0.0, markPos_ = 0.0, nextSynth_ = 0.0;
};

}  // namespace av
