#pragma once

#include <cmath>
#include <cstdint>

namespace av {

/** Flushes denormals to zero. Reverb tails generate them and they cost 100x. */
inline void enableFlushDenormals() {
#if defined(__aarch64__)
    uint64_t fpcr;
    asm volatile("mrs %0, fpcr" : "=r"(fpcr));
    fpcr |= (1ULL << 24);  // FZ bit
    asm volatile("msr fpcr, %0" : : "r"(fpcr));
#elif defined(__arm__)
    uint32_t fpscr;
    asm volatile("vmrs %0, fpscr" : "=r"(fpscr));
    fpscr |= (1u << 24);
    asm volatile("vmsr fpscr, %0" : : "r"(fpscr));
#endif
}

constexpr float kPi = 3.14159265358979323846f;

inline float dbToLin(float db) { return std::pow(10.0f, db * 0.05f); }

inline float linToDb(float lin) {
    return lin < 1e-7f ? -140.0f : 20.0f * std::log10(lin);
}

/** One-pole smoothing coefficient for a given time constant in ms. */
inline float timeCoef(float ms, int sampleRate) {
    if (ms <= 0.0f) return 1.0f;
    return 1.0f - std::exp(-1.0f / (ms * 0.001f * (float) sampleRate));
}

inline float clampf(float v, float lo, float hi) {
    return v < lo ? lo : (v > hi ? hi : v);
}

/** Mid/side width control. width 1 = unchanged, 0 = mono, >1 = wider. */
inline void applyWidth(float &l, float &r, float width) noexcept {
    const float mid = (l + r) * 0.5f;
    const float side = (l - r) * 0.5f * width;
    l = mid + side;
    r = mid - side;
}

/** Linear ramp toward a target, one step per sample. Avoids zipper noise. */
class Smoothed {
public:
    void prepare(float initial, float ms, int sampleRate) {
        value_ = target_ = initial;
        coef_ = timeCoef(ms, sampleRate);
    }

    void set(float target) { target_ = target; }
    void snap(float v) { value_ = target_ = v; }

    inline float next() noexcept {
        value_ += (target_ - value_) * coef_;
        return value_;
    }

    float current() const { return value_; }

private:
    float value_ = 0.0f, target_ = 0.0f, coef_ = 0.05f;
};

/** Transposed direct form II biquad, RBJ cookbook coefficients. */
class Biquad {
public:
    inline float process(float x) noexcept {
        const float y = b0_ * x + z1_;
        z1_ = b1_ * x - a1_ * y + z2_;
        z2_ = b2_ * x - a2_ * y;
        return y;
    }

    void reset() { z1_ = z2_ = 0.0f; }

    void highPass(int sr, float freq, float q) {
        const double w0 = 2.0 * kPi * freq / sr;
        const double c = std::cos(w0), alpha = std::sin(w0) / (2.0 * q);
        const double a0 = 1.0 + alpha;
        set((1.0 + c) / 2.0 / a0, -(1.0 + c) / a0, (1.0 + c) / 2.0 / a0,
            -2.0 * c / a0, (1.0 - alpha) / a0);
    }

    void lowPass(int sr, float freq, float q) {
        const double w0 = 2.0 * kPi * freq / sr;
        const double c = std::cos(w0), alpha = std::sin(w0) / (2.0 * q);
        const double a0 = 1.0 + alpha;
        set((1.0 - c) / 2.0 / a0, (1.0 - c) / a0, (1.0 - c) / 2.0 / a0,
            -2.0 * c / a0, (1.0 - alpha) / a0);
    }

    void bandPass(int sr, float freq, float q) {
        const double w0 = 2.0 * kPi * freq / sr;
        const double c = std::cos(w0), alpha = std::sin(w0) / (2.0 * q);
        const double a0 = 1.0 + alpha;
        set(alpha / a0, 0.0, -alpha / a0, -2.0 * c / a0, (1.0 - alpha) / a0);
    }

    void peaking(int sr, float freq, float q, float gainDb) {
        const double A = std::pow(10.0, gainDb / 40.0);
        const double w0 = 2.0 * kPi * freq / sr;
        const double c = std::cos(w0), alpha = std::sin(w0) / (2.0 * q);
        const double a0 = 1.0 + alpha / A;
        set((1.0 + alpha * A) / a0, -2.0 * c / a0, (1.0 - alpha * A) / a0,
            -2.0 * c / a0, (1.0 - alpha / A) / a0);
    }

    void lowShelf(int sr, float freq, float gainDb) {
        const double A = std::pow(10.0, gainDb / 40.0);
        const double w0 = 2.0 * kPi * freq / sr;
        const double c = std::cos(w0);
        const double alpha = std::sin(w0) * 0.5 * 1.41421356;  // slope S = 1
        const double sa = 2.0 * std::sqrt(A) * alpha;
        const double a0 = (A + 1.0) + (A - 1.0) * c + sa;
        set(A * ((A + 1.0) - (A - 1.0) * c + sa) / a0,
            2.0 * A * ((A - 1.0) - (A + 1.0) * c) / a0,
            A * ((A + 1.0) - (A - 1.0) * c - sa) / a0,
            -2.0 * ((A - 1.0) + (A + 1.0) * c) / a0,
            ((A + 1.0) + (A - 1.0) * c - sa) / a0);
    }

    void highShelf(int sr, float freq, float gainDb) {
        const double A = std::pow(10.0, gainDb / 40.0);
        const double w0 = 2.0 * kPi * freq / sr;
        const double c = std::cos(w0);
        const double alpha = std::sin(w0) * 0.5 * 1.41421356;
        const double sa = 2.0 * std::sqrt(A) * alpha;
        const double a0 = (A + 1.0) - (A - 1.0) * c + sa;
        set(A * ((A + 1.0) + (A - 1.0) * c + sa) / a0,
            -2.0 * A * ((A - 1.0) + (A + 1.0) * c) / a0,
            A * ((A + 1.0) + (A - 1.0) * c - sa) / a0,
            2.0 * ((A - 1.0) - (A + 1.0) * c) / a0,
            ((A + 1.0) - (A - 1.0) * c - sa) / a0);
    }

private:
    void set(double b0, double b1, double b2, double a1, double a2) {
        b0_ = (float) b0; b1_ = (float) b1; b2_ = (float) b2;
        a1_ = (float) a1; a2_ = (float) a2;
    }

    float b0_ = 1.0f, b1_ = 0.0f, b2_ = 0.0f, a1_ = 0.0f, a2_ = 0.0f;
    float z1_ = 0.0f, z2_ = 0.0f;
};

/** Linkwitz-Riley 4th order crossover: two cascaded Butterworth sections. */
class LR4 {
public:
    void prepare(int sr, float freq) {
        lp1_.lowPass(sr, freq, 0.707f);
        lp2_.lowPass(sr, freq, 0.707f);
        hp1_.highPass(sr, freq, 0.707f);
        hp2_.highPass(sr, freq, 0.707f);
        reset();
    }

    /** Splits one sample; low + high sums back to the input with flat magnitude. */
    inline void process(float x, float &low, float &high) noexcept {
        low = lp2_.process(lp1_.process(x));
        high = hp2_.process(hp1_.process(x));
    }

    void reset() { lp1_.reset(); lp2_.reset(); hp1_.reset(); hp2_.reset(); }

private:
    Biquad lp1_, lp2_, hp1_, hp2_;
};

}  // namespace av
