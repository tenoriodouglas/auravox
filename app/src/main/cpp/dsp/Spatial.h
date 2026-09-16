#pragma once

#include <vector>
#include "Biquad.h"

namespace av {

/** Freeverb: 8 parallel damped combs feeding 4 series allpasses. */
class Reverb {
public:
    void prepare(int sampleRate) {
        static const int kComb[8] = {1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617};
        static const int kAllpass[4] = {556, 441, 341, 225};
        const float scale = sampleRate / 44100.0f;

        for (int i = 0; i < 8; ++i) {
            combs_[i].resize((int) (kComb[i] * scale));
            // Right channel runs slightly longer combs: that offset is the
            // whole reason the tail reads as a room and not as a mono blur
            combsR_[i].resize((int) (kComb[i] * scale) + 23);
        }
        for (int i = 0; i < 4; ++i) {
            allpasses_[i].resize((int) (kAllpass[i] * scale));
            allpassesR_[i].resize((int) (kAllpass[i] * scale) + 11);
        }

        preDelay_.assign((size_t) (sampleRate * 0.1f), 0.0f);  // up to 100 ms
        preIdx_ = 0;
        setRoomSize(0.7f);
        setDamping(0.4f);
    }

    float mix = 0.2f;

    void setRoomSize(float v) {
        const float f = v * 0.28f + 0.7f;
        for (int i = 0; i < 8; ++i) { combs_[i].feedback = f; combsR_[i].feedback = f; }
    }

    void setDamping(float v) {
        for (int i = 0; i < 8; ++i) { combs_[i].damping = v * 0.4f; combsR_[i].damping = v * 0.4f; }
    }

    void setPreDelayMs(float ms, int sampleRate) {
        preDelaySamples_ = (int) (ms * 0.001f * sampleRate);
        if (preDelaySamples_ >= (int) preDelay_.size())
            preDelaySamples_ = (int) preDelay_.size() - 1;
        if (preDelaySamples_ < 0) preDelaySamples_ = 0;
    }

    /** Mono in, stereo wet added to l/r according to mix. */
    inline void process(float x, float &l, float &r) noexcept {
        if (mix <= 0.001f) return;

        int rd = preIdx_ - preDelaySamples_;
        if (rd < 0) rd += (int) preDelay_.size();
        const float pre = preDelay_[rd];
        preDelay_[preIdx_] = x;
        if (++preIdx_ >= (int) preDelay_.size()) preIdx_ = 0;

        const float in = pre * 0.015f;  // fixed input gain keeps the comb bank stable
        float wetL = 0.0f, wetR = 0.0f;
        for (int i = 0; i < 8; ++i) { wetL += combs_[i].process(in); wetR += combsR_[i].process(in); }
        for (int i = 0; i < 4; ++i) { wetL = allpasses_[i].process(wetL); wetR = allpassesR_[i].process(wetR); }

        l += wetL * mix;
        r += wetR * mix;
    }

    void reset() {
        for (int i = 0; i < 8; ++i) { combs_[i].reset(); combsR_[i].reset(); }
        for (int i = 0; i < 4; ++i) { allpasses_[i].reset(); allpassesR_[i].reset(); }
        std::fill(preDelay_.begin(), preDelay_.end(), 0.0f);
        preIdx_ = 0;
    }

private:
    struct Comb {
        std::vector<float> buf;
        int idx = 0;
        float store = 0.0f;
        float feedback = 0.84f;
        float damping = 0.2f;

        void resize(int n) { buf.assign((size_t) n, 0.0f); idx = 0; store = 0.0f; }

        inline float process(float x) noexcept {
            const float out = buf[idx];
            store = out * (1.0f - damping) + store * damping;
            buf[idx] = x + store * feedback;
            if (++idx >= (int) buf.size()) idx = 0;
            return out;
        }

        void reset() { std::fill(buf.begin(), buf.end(), 0.0f); idx = 0; store = 0.0f; }
    };

    struct Allpass {
        std::vector<float> buf;
        int idx = 0;
        static constexpr float kFeedback = 0.5f;

        void resize(int n) { buf.assign((size_t) n, 0.0f); idx = 0; }

        inline float process(float x) noexcept {
            const float bufOut = buf[idx];
            const float out = -x + bufOut;
            buf[idx] = x + bufOut * kFeedback;
            if (++idx >= (int) buf.size()) idx = 0;
            return out;
        }

        void reset() { std::fill(buf.begin(), buf.end(), 0.0f); idx = 0; }
    };

    Comb combs_[8], combsR_[8];
    Allpass allpasses_[4], allpassesR_[4];
    std::vector<float> preDelay_;
    int preIdx_ = 0;
    int preDelaySamples_ = 0;
};

/** Ping-pong feedback delay with a damping filter in the loop. */
class Delay {
public:
    void prepare(int sampleRate) {
        sr_ = sampleRate;
        bufL_.assign((size_t) sampleRate * 2, 0.0f);  // 2 s max
        bufR_.assign((size_t) sampleRate * 2, 0.0f);
        damp_.lowPass(sampleRate, 6000.0f, 0.707f);
        hp_.highPass(sampleRate, 250.0f, 0.707f);
        damp2_.lowPass(sampleRate, 6000.0f, 0.707f);
        hp2_.highPass(sampleRate, 250.0f, 0.707f);
        setTimeMs(250.0f);
        writeIdx_ = 0;
    }

    float feedback = 0.3f;
    float mix = 0.0f;
    float pingPong = 1.0f;  // 0 = both taps in place, 1 = fully crossed

    void setTimeMs(float ms) {
        int n = (int) (ms * 0.001f * sr_);
        if (n < 1) n = 1;
        if (n >= (int) bufL_.size()) n = (int) bufL_.size() - 1;
        delaySamples_ = n;
    }

    inline void process(float x, float &l, float &r) noexcept {
        if (mix <= 0.001f) return;

        int rd = writeIdx_ - delaySamples_;
        if (rd < 0) rd += (int) bufL_.size();
        const float dl = bufL_[rd];
        const float dr = bufR_[rd];

        const float fbL = hp_.process(damp_.process(dl)) * feedback;
        const float fbR = hp2_.process(damp2_.process(dr)) * feedback;

        // Crossing the feedback paths is what makes the repeats alternate sides
        bufL_[writeIdx_] = x + fbR * pingPong + fbL * (1.0f - pingPong);
        bufR_[writeIdx_] = fbL * pingPong + fbR * (1.0f - pingPong);
        if (++writeIdx_ >= (int) bufL_.size()) writeIdx_ = 0;

        l += dl * mix;
        r += dr * mix;
    }

    void reset() {
        std::fill(bufL_.begin(), bufL_.end(), 0.0f);
        std::fill(bufR_.begin(), bufR_.end(), 0.0f);
        writeIdx_ = 0;
        damp_.reset(); hp_.reset(); damp2_.reset(); hp2_.reset();
    }

private:
    int sr_ = 48000;
    std::vector<float> bufL_, bufR_;
    int writeIdx_ = 0, delaySamples_ = 12000;
    Biquad damp_, hp_, damp2_, hp2_;
};

/**
 * Doubler: two short modulated taps panned hard, one per side.
 *
 * The delay is long enough to read as a second take and short enough to stay
 * fused with the lead. Random-walk modulation gives the detune without a
 * second pitch shifter.
 */
class Doubler {
public:
    void prepare(int sampleRate) {
        sr_ = sampleRate;
        buf_.assign((size_t) (sampleRate * 0.12f), 0.0f);  // 120 ms is plenty
        writeIdx_ = 0;
        baseL_ = 0.019f * sampleRate;
        baseR_ = 0.027f * sampleRate;
        phaseL_ = 0.0f;
        phaseR_ = 1.7f;
        lfoStep_ = 2.0f * kPi * 0.27f / sampleRate;
    }

    float mix = 0.0f;      // 0..1 level of the doubled pair
    float spread = 1.0f;   // 0 = centred, 1 = hard left/right
    float detune = 0.5f;   // 0..1, scales the modulation depth

    inline void process(float x, float &l, float &r) noexcept {
        buf_[writeIdx_] = x;

        if (mix > 0.001f) {
            const float depth = detune * 0.004f * sr_;  // up to 4 ms of sweep
            const float dL = baseL_ + std::sin(phaseL_) * depth;
            const float dR = baseR_ + std::sin(phaseR_ * 0.83f) * depth;

            const float a = read(dL);
            const float b = read(dR);

            // Equal-power pan of the two taps to opposite sides
            const float side = spread * 0.5f;
            l += (a * (0.5f + side) + b * (0.5f - side)) * mix;
            r += (a * (0.5f - side) + b * (0.5f + side)) * mix;
        }

        phaseL_ += lfoStep_;
        phaseR_ += lfoStep_ * 1.31f;
        if (phaseL_ > 2.0f * kPi) phaseL_ -= 2.0f * kPi;
        if (phaseR_ > 2.0f * kPi) phaseR_ -= 2.0f * kPi;
        if (++writeIdx_ >= (int) buf_.size()) writeIdx_ = 0;
    }

    void reset() {
        std::fill(buf_.begin(), buf_.end(), 0.0f);
        writeIdx_ = 0; phaseL_ = 0.0f; phaseR_ = 1.7f;
    }

private:
    inline float read(float delaySamples) const noexcept {
        const int n = (int) buf_.size();
        float pos = (float) writeIdx_ - delaySamples;
        while (pos < 0.0f) pos += (float) n;
        const int i0 = (int) pos;
        const float frac = pos - (float) i0;
        const int i1 = (i0 + 1) % n;
        return buf_[i0] + (buf_[i1] - buf_[i0]) * frac;
    }

    int sr_ = 48000;
    std::vector<float> buf_;
    int writeIdx_ = 0;
    float baseL_ = 0.0f, baseR_ = 0.0f;
    float phaseL_ = 0.0f, phaseR_ = 0.0f, lfoStep_ = 0.0f;
};

}  // namespace av
