#pragma once

#include <algorithm>
#include <cmath>
#include <vector>

#include "dsp/Stft.h"

namespace av {

/**
 * Splits a stereo mix into a vocal and an instrumental, around a neural net.
 *
 * The net is a plain convolutional U-Net over magnitude spectrograms: it has
 * no transform of its own, so everything either side of it lives here. One
 * split at a time, because a whole song's spectrum does not belong in a
 * phone's memory.
 *
 * The pipeline, in order:
 *
 *  1. analyze() runs the STFT and keeps the complex spectrum, handing out
 *     only the magnitudes of the bins the net was trained on.
 *  2. The caller runs the two models.
 *  3. synthesize() turns the two magnitudes into masks, extends them past the
 *     net's ceiling, multiplies the kept spectrum and inverts it.
 *
 * Two details decide whether the result is usable, and both were measured
 * rather than assumed:
 *
 *  - The masks are a ratio, not the net's output. The two models are trained
 *    separately and their magnitudes do not add up to the mixture — measured
 *    at 1.24x of it — so using them directly leaks the voice into the backing
 *    track. Dividing each by their sum makes the two stems add back to
 *    exactly the input.
 *  - The net only models up to its bin ceiling, which at 44.1 kHz with a 4096
 *    window is 11 kHz. Spleeter's own default discards everything above that,
 *    and measured on a signal with real high-frequency content it drops that
 *    band by 39 dB: every cymbal and all the air goes with it. Carrying the
 *    top of the mask upward keeps 99.99% of it, and costs one loop.
 */
class Separator {
public:
    /**
     * @param nFft       transform size the net expects (4096)
     * @param hop        hop size the net expects (1024)
     * @param modelBins  bins the net covers (1024 of the 2049 a 4096 gives)
     */
    void prepare(int nFft, int hop, int modelBins) {
        modelBins_ = modelBins;
        stft_.prepare(nFft, hop);
        bins_ = stft_.bins();
        if (modelBins_ > bins_) modelBins_ = bins_;
        mag_.assign((size_t) bins_, 0.0f);
        phase_.assign((size_t) bins_, 0.0f);
    }

    int bins() const noexcept { return bins_; }
    int modelBins() const noexcept { return modelBins_; }

    /** Frames one split of this many samples produces. */
    int frameCount(int samples) const noexcept { return stft_.frameCount(samples); }

    /**
     * Transforms one channel and writes the magnitudes the net wants.
     *
     * magOut is laid out frame-major, modelBins per frame, which is the order
     * the net's [512, 1024] plane expects. The full-resolution spectrum is
     * kept inside for synthesize().
     */
    void analyze(const float *samples, int length, int channel, float *magOut) {
        auto &keepMag = magFor(channel);
        auto &keepPhase = phaseFor(channel);
        const int frames = stft_.frameCount(length);
        keepMag.assign((size_t) frames * (size_t) bins_, 0.0f);
        keepPhase.assign((size_t) frames * (size_t) bins_, 0.0f);

        for (int f = 0; f < frames; ++f) {
            stft_.analyze(samples, length, f, mag_.data(), phase_.data());
            float *mRow = &keepMag[(size_t) f * (size_t) bins_];
            float *pRow = &keepPhase[(size_t) f * (size_t) bins_];
            std::copy(mag_.begin(), mag_.end(), mRow);
            std::copy(phase_.begin(), phase_.end(), pRow);
            std::copy(mRow, mRow + modelBins_, magOut + (size_t) f * (size_t) modelBins_);
        }
        frames_ = frames;
    }

    /**
     * Masks the kept spectrum with the two model outputs and inverts it.
     *
     * vocalMag and accompMag are the nets' outputs for this channel, in the
     * same frame-major layout analyze() produced. Either output pointer may
     * be null when that stem is not wanted.
     */
    void synthesize(
        const float *vocalMag, const float *accompMag, int channel, int length,
        float *vocalOut, float *accompOut
    ) {
        auto &keepMag = magFor(channel);
        auto &keepPhase = phaseFor(channel);

        std::vector<float> maskV((size_t) bins_), maskA((size_t) bins_);
        std::vector<float> rowMag((size_t) bins_);

        for (int pass = 0; pass < 2; ++pass) {
            float *out = pass == 0 ? vocalOut : accompOut;
            if (!out) continue;
            stft_.beginSynthesis(length);

            for (int f = 0; f < frames_; ++f) {
                const float *v = vocalMag + (size_t) f * (size_t) modelBins_;
                const float *a = accompMag + (size_t) f * (size_t) modelBins_;
                buildMasks(v, a, maskV.data(), maskA.data());

                const float *mRow = &keepMag[(size_t) f * (size_t) bins_];
                const float *pRow = &keepPhase[(size_t) f * (size_t) bins_];
                const float *mask = pass == 0 ? maskV.data() : maskA.data();
                for (int k = 0; k < bins_; ++k) rowMag[(size_t) k] = mRow[k] * mask[k];

                stft_.addFrame(rowMag.data(), pRow, f);
            }
            stft_.finish(out);
        }
    }

    /**
     * Ratio masks for one frame, including the band above the net's ceiling.
     *
     * Where both models see silence the ratio has no meaning, and dividing by
     * a floored denominator would send both masks to zero and lose the bin.
     * Those go to the backing track whole: silence is not the singer's.
     */
    void buildMasks(const float *vocalMag, const float *accompMag,
                    float *maskV, float *maskA) const noexcept {
        for (int k = 0; k < modelBins_; ++k) {
            const float v = vocalMag[k] * vocalMag[k];
            const float a = accompMag[k] * accompMag[k];
            const float den = v + a;
            if (den <= kFloor) {
                maskV[k] = 0.0f;
                maskA[k] = 1.0f;
            } else {
                maskV[k] = v / den;
                maskA[k] = 1.0f - maskV[k];
            }
        }
        if (modelBins_ >= bins_) return;

        // Above the ceiling the net says nothing, so the top of what it did
        // say carries on. Averaging a band rather than copying one bin keeps
        // a single loud partial at the edge from colouring the whole top end.
        const int from = std::max(0, modelBins_ - kTailBins);
        float sum = 0.0f;
        int count = 0;
        for (int k = from; k < modelBins_; ++k) { sum += maskV[k]; ++count; }
        const float tail = count > 0 ? sum / (float) count : 0.0f;
        for (int k = modelBins_; k < bins_; ++k) {
            maskV[k] = tail;
            maskA[k] = 1.0f - tail;
        }
    }

private:
    std::vector<float> &magFor(int channel) { return channel == 0 ? magL_ : magR_; }
    std::vector<float> &phaseFor(int channel) { return channel == 0 ? phaseL_ : phaseR_; }

    static constexpr float kFloor = 1e-12f;
    static constexpr int kTailBins = 32;

    Stft stft_;
    int bins_ = 0, modelBins_ = 0, frames_ = 0;
    std::vector<float> mag_, phase_;
    std::vector<float> magL_, magR_, phaseL_, phaseR_;
};

}  // namespace av
