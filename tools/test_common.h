#pragma once

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

// Shared helpers for the host-side DSP tests. Nothing here touches Android.

static int gPassed = 0;
static int gFailed = 0;

inline void check(bool ok, const std::string &name, const std::string &detail = "") {
    if (ok) {
        ++gPassed;
        std::printf("  \033[32mok\033[0m   %-52s %s\n", name.c_str(), detail.c_str());
    } else {
        ++gFailed;
        std::printf("  \033[31mFAIL\033[0m %-52s %s\n", name.c_str(), detail.c_str());
    }
}

inline void section(const char *title) {
    std::printf("\n\033[1m%s\033[0m\n", title);
}

inline std::string fmt(const char *pattern, double a) {
    char buf[128];
    std::snprintf(buf, sizeof(buf), pattern, a);
    return buf;
}

inline std::string fmt2(const char *pattern, double a, double b) {
    char buf[160];
    std::snprintf(buf, sizeof(buf), pattern, a, b);
    return buf;
}

inline std::string fmtI(const char *pattern, int a) {
    char buf[128];
    std::snprintf(buf, sizeof(buf), pattern, a);
    return buf;
}

inline std::string fmtI2(const char *pattern, int a, int b) {
    char buf[160];
    std::snprintf(buf, sizeof(buf), pattern, a, b);
    return buf;
}

/** Harmonic-rich tone: a sawtooth-ish spectrum, close enough to a sung vowel. */
inline std::vector<float> tone(float freq, int sampleRate, int frames, float amp = 0.22f) {
    std::vector<float> out((size_t) frames);
    for (int i = 0; i < frames; ++i) {
        const float t = (float) i / (float) sampleRate;
        float v = 0.0f;
        for (int h = 1; h <= 12; ++h) v += std::sin(2.0f * (float) M_PI * freq * h * t) / (float) h;
        out[(size_t) i] = v * amp;
    }
    return out;
}

/** Magnitude of one frequency, by direct correlation. Cheaper than an FFT here. */
inline float magnitudeAt(const float *x, int n, float freq, int sampleRate) {
    double re = 0.0, im = 0.0;
    for (int i = 0; i < n; ++i) {
        const double w = 2.0 * M_PI * freq * i / sampleRate;
        re += x[i] * std::cos(w);
        im += x[i] * std::sin(w);
    }
    return (float) (2.0 * std::sqrt(re * re + im * im) / n);
}

/**
 * Fundamental that best explains the signal, by harmonic product spectrum.
 *
 * Summing the log magnitude of the first four harmonics punishes a
 * subharmonic candidate hard: half the true f0 has nothing at its own
 * frequency or at its third harmonic. Measuring a pitch shifter with a second
 * pitch tracker only tells you the two trackers agree.
 */
inline float harmonicPitch(const float *x, int n, int sampleRate,
                           float expected, int semitoneRange = 24) {
    float bestScore = -1e30f, bestFreq = 0.0f;
    for (int cents = -semitoneRange * 100; cents <= semitoneRange * 100; cents += 10) {
        const float f = expected * std::pow(2.0f, cents / 1200.0f);
        if (f < 20.0f || f * 4.0f > sampleRate * 0.45f) continue;
        float score = 0.0f;
        for (int h = 1; h <= 4; ++h) {
            score += std::log(magnitudeAt(x, n, f * h, sampleRate) + 1e-6f);
        }
        if (score > bestScore) { bestScore = score; bestFreq = f; }
    }
    return bestFreq;
}

inline float rms(const float *x, int n) {
    double sum = 0.0;
    for (int i = 0; i < n; ++i) sum += (double) x[i] * x[i];
    return (float) std::sqrt(sum / (n > 0 ? n : 1));
}

inline float centsBetween(float a, float b) {
    return 1200.0f * std::log2(a / b);
}
