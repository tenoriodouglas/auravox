#pragma once

#include <cmath>
#include <cstddef>
#include <vector>

namespace av {

/**
 * Offline rate conversion by windowed sinc.
 *
 * Only the separation pass needs this: the neural net was trained at 44.1 kHz
 * and its frequency bins mean nothing at another rate, so a 48 kHz file has to
 * be converted before it is analysed. Playback does not go through here — the
 * engine already resamples a track of any rate on its way to the device.
 *
 * The cubic interpolator the time-stretcher uses would be the cheap answer and
 * is the wrong one here, because it does not band-limit: converting 48 kHz
 * down to 44.1 folds everything between 22.05 and 24 kHz back into the top
 * octave as alias, and the separator would then be asked to find a voice in a
 * spectrum with rubbish in it. A sinc kernel with its cutoff at the lower of
 * the two Nyquists removes that band instead of folding it.
 *
 * The kernel is quantised into phases and built once, so converting a whole
 * song is a pair of multiply-adds per tap and nothing else.
 */
class Resampler {
public:
    /** Prepares a conversion. Returns false when the rates are unusable. */
    bool prepare(double inRate, double outRate) {
        if (inRate <= 0.0 || outRate <= 0.0) return false;
        inRate_ = inRate;
        outRate_ = outRate;
        step_ = inRate / outRate;

        // Band-limit to whichever Nyquist is lower, with a little margin so
        // the transition band is not sitting exactly on it
        const double cutoff = 0.5 * std::min(1.0, outRate / inRate) * 0.94;

        table_.assign((size_t) kPhases * (size_t) kTaps, 0.0f);
        const int half = kTaps / 2;
        for (int p = 0; p < kPhases; ++p) {
            const double frac = (double) p / (double) kPhases;
            double sum = 0.0;
            for (int t = 0; t < kTaps; ++t) {
                // Distance from the output point to this input sample
                const double x = (double) (t - half + 1) - frac;
                const double arg = 2.0 * cutoff * x;
                const double sinc = (std::fabs(arg) < 1e-9)
                    ? 1.0 : std::sin(M_PI * arg) / (M_PI * arg);
                // Blackman window over the whole kernel width
                const double w = windowAt((x + (double) half) / (double) kTaps);
                const double h = 2.0 * cutoff * sinc * w;
                table_[(size_t) p * kTaps + (size_t) t] = (float) h;
                sum += h;
            }
            // Normalise each phase so a constant comes through at unity
            if (sum > 1e-12) {
                for (int t = 0; t < kTaps; ++t) {
                    table_[(size_t) p * kTaps + (size_t) t] /= (float) sum;
                }
            }
        }
        return true;
    }

    bool passthrough() const noexcept { return std::fabs(step_ - 1.0) < 1e-9; }

    /** Output frames a run of `inFrames` produces. */
    int outputFrames(int inFrames) const noexcept {
        if (inFrames <= 0) return 0;
        return (int) std::floor((double) inFrames / step_);
    }

    /**
     * Converts one channel. Reads outside the input count as zero, so the
     * caller does not pad.
     */
    void process(const float *in, int inFrames, float *out, int outFrames) const noexcept {
        const int half = kTaps / 2;
        for (int i = 0; i < outFrames; ++i) {
            const double pos = (double) i * step_;
            const int base = (int) std::floor(pos);
            const double frac = pos - (double) base;

            int phase = (int) (frac * (double) kPhases);
            if (phase >= kPhases) phase = kPhases - 1;
            const float *h = &table_[(size_t) phase * kTaps];

            float acc = 0.0f;
            for (int t = 0; t < kTaps; ++t) {
                const int idx = base + t - half + 1;
                const float s = (idx >= 0 && idx < inFrames) ? in[idx] : 0.0f;
                acc += s * h[t];
            }
            out[i] = acc;
        }
    }

private:
    /** Blackman, over a normalised 0..1 position across the kernel. */
    static double windowAt(double u) {
        if (u <= 0.0 || u >= 1.0) return 0.0;
        return 0.42 - 0.5 * std::cos(2.0 * M_PI * u) + 0.08 * std::cos(4.0 * M_PI * u);
    }

    static constexpr int kTaps = 32;
    static constexpr int kPhases = 256;

    double inRate_ = 0.0, outRate_ = 0.0, step_ = 1.0;
    std::vector<float> table_;
};

}  // namespace av
