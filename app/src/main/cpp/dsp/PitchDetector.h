#pragma once

#include <algorithm>
#include <cmath>
#include <vector>
#include "Biquad.h"

namespace av {

inline float hzToMidi(float hz) {
    return 69.0f + 12.0f * std::log2(hz / 440.0f);
}

inline float midiToHz(float midi) {
    return 440.0f * std::exp2((midi - 69.0f) / 12.0f);
}

/**
 * YIN fundamental estimator with octave-continuity correction.
 *
 * Runs on a 2x decimated signal, which cuts the difference function cost by 4x
 * and still reaches well above any sung note. An anti-alias low pass precedes
 * the decimation. All storage is allocated in prepare(); push() never allocates.
 *
 * Plain YIN halves or doubles the period on low, harmonic-poor voices. After
 * picking a candidate it also scores tau/2 and 2*tau against the previous
 * stable estimate, which is a one-step Viterbi over the octave lattice and
 * costs three extra table reads.
 */
class PitchDetector {
public:
    void prepare(int sampleRate, float minHz = 65.0f, float maxHz = 1100.0f) {
        sr_ = sampleRate;
        decimRate_ = sampleRate / kDecim;

        antiAlias_.lowPass(sampleRate, decimRate_ * 0.45f, 0.707f);

        minTau_ = (int) (decimRate_ / maxHz);
        if (minTau_ < 2) minTau_ = 2;
        maxTau_ = (int) (decimRate_ / minHz) + 1;
        if (maxTau_ > kWindow / 2) maxTau_ = kWindow / 2;

        window_.assign(kWindow, 0.0f);
        diff_.assign((size_t) maxTau_ + 1, 0.0f);
        cmnd_.assign((size_t) maxTau_ + 1, 0.0f);
        reset();
    }

    /** Hop between estimates in samples at the original rate. */
    int hopSamples() const { return kWindow / 2 * kDecim; }

    /** Feeds one sample. Returns true when a fresh estimate is available. */
    inline bool push(float x) noexcept {
        const float filtered = antiAlias_.process(x);
        if (++decimCount_ < kDecim) return false;
        decimCount_ = 0;

        window_[fill_++] = filtered;
        if (fill_ < kWindow) return false;

        analyze();

        // Slide by half a window: a new estimate roughly every 21 ms
        const int half = kWindow / 2;
        std::copy(window_.begin() + half, window_.end(), window_.begin());
        fill_ = half;
        return true;
    }

    float frequency() const { return frequency_; }
    float confidence() const { return confidence_; }
    bool voiced() const { return frequency_ > 0.0f && confidence_ > 0.45f; }
    float midi() const { return frequency_ > 0.0f ? hzToMidi(frequency_) : -1.0f; }

    void reset() {
        fill_ = 0; decimCount_ = 0;
        frequency_ = 0.0f; confidence_ = 0.0f;
        prevMidi_ = -1.0f; silentFrames_ = 0;
        antiAlias_.reset();
        std::fill(window_.begin(), window_.end(), 0.0f);
    }

private:
    static constexpr int kDecim = 2;
    static constexpr int kWindow = 1024;

    void analyze() {
        const int half = kWindow / 2;

        // Bail out on silence before spending cycles on the difference function
        float energy = 0.0f;
        for (int i = 0; i < half; ++i) energy += window_[i] * window_[i];
        if (energy < 1e-5f) {
            frequency_ = 0.0f; confidence_ = 0.0f;
            if (++silentFrames_ > 4) prevMidi_ = -1.0f;  // let the track restart
            return;
        }
        silentFrames_ = 0;

        for (int tau = minTau_; tau <= maxTau_; ++tau) {
            float sum = 0.0f;
            const float *a = window_.data();
            const float *b = window_.data() + tau;
            for (int i = 0; i < half; ++i) {
                const float d = a[i] - b[i];
                sum += d * d;
            }
            diff_[tau] = sum;
        }

        // Cumulative mean normalized difference
        float running = 0.0f;
        cmnd_[minTau_] = 1.0f;
        for (int tau = minTau_ + 1; tau <= maxTau_; ++tau) {
            running += diff_[tau];
            cmnd_[tau] = running <= 0.0f
                         ? 1.0f
                         : diff_[tau] * (float) (tau - minTau_ + 1) / running;
        }

        // First local minimum under threshold, walked down to its bottom
        const float threshold = 0.15f;
        int best = -1;
        for (int tau = minTau_ + 1; tau < maxTau_; ++tau) {
            if (cmnd_[tau] < threshold) {
                while (tau + 1 < maxTau_ && cmnd_[tau + 1] < cmnd_[tau]) ++tau;
                best = tau;
                break;
            }
        }

        if (best < 0) {
            float minVal = 1e30f;
            for (int tau = minTau_; tau <= maxTau_; ++tau) {
                if (cmnd_[tau] < minVal) { minVal = cmnd_[tau]; best = tau; }
            }
            if (best < 0 || minVal > 0.55f) {
                frequency_ = 0.0f; confidence_ = 0.0f;
                return;
            }
        }

        best = resolveOctave(best);

        const float refined = parabolic(best);
        frequency_ = decimRate_ / refined;
        confidence_ = clampf(1.0f - cmnd_[best], 0.0f, 1.0f);
        if (confidence_ > 0.45f) prevMidi_ = hzToMidi(frequency_);
    }

    /**
     * Picks between tau, tau/2 and 2*tau by total cost: how well the candidate
     * explains the frame plus how far it jumps from the last stable note.
     *
     * An alternative only gets to compete when it explains the frame about as
     * well as the original. Without that guard a confident estimate could be
     * dragged an octave by continuity alone, which is a worse failure than the
     * octave error this is here to fix.
     */
    int resolveOctave(int tau) const {
        if (prevMidi_ < 0.0f) return tau;

        const int candidates[3] = {tau, tau / 2, tau * 2};
        int bestTau = tau;
        float bestCost = 1e30f;

        for (int c : candidates) {
            if (c < minTau_ || c > maxTau_) continue;
            if (cmnd_[c] > cmnd_[tau] + 0.15f) continue;

            const float f = decimRate_ / (float) c;
            const float jump = std::fabs(hzToMidi(f) - prevMidi_);
            // 0.05 per semitone: an octave jump has to beat a 0.6 cmnd gap to win
            const float cost = cmnd_[c] + 0.05f * jump;
            if (cost < bestCost) { bestCost = cost; bestTau = c; }
        }
        return bestTau;
    }

    float parabolic(int t) const {
        if (t <= minTau_ || t >= maxTau_) return (float) t;
        const float s0 = cmnd_[t - 1], s1 = cmnd_[t], s2 = cmnd_[t + 1];
        const float denom = 2.0f * (2.0f * s1 - s2 - s0);
        if (std::fabs(denom) < 1e-9f) return (float) t;
        return (float) t + (s2 - s0) / denom;
    }

    int sr_ = 48000, decimRate_ = 24000;
    int minTau_ = 20, maxTau_ = 400;
    int fill_ = 0, decimCount_ = 0, silentFrames_ = 0;
    float frequency_ = 0.0f, confidence_ = 0.0f, prevMidi_ = -1.0f;

    Biquad antiAlias_;
    std::vector<float> window_, diff_, cmnd_;
};

}  // namespace av
