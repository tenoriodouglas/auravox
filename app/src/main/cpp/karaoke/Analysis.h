#pragma once

#include <algorithm>
#include <cmath>
#include <vector>
#include "../dsp/Biquad.h"
#include "../dsp/Scale.h"

namespace av {

/**
 * Offline analysis of an imported song: melody guide, key and tempo.
 *
 * This is what lets any file the user owns become a scored karaoke track. It
 * runs once on a worker thread after import, allocates freely and never
 * touches the audio thread.
 *
 * Input is mono at a low rate, kAnalysisRate by convention. The caller does
 * the decode and the downmix because the platform already has a decoder.
 */
constexpr int kAnalysisRate = 11025;

struct AnalysisResult {
    std::vector<float> notes;   // triples: startMs, durationMs, midi
    int keyRoot = 0;            // 0 = C
    int keyMode = 0;            // 0 = major, 1 = minor
    float keyConfidence = 0.0f;
    float bpm = 0.0f;
    float bpmConfidence = 0.0f;
    float durationMs = 0.0f;
};

namespace detail {

constexpr int kFrameHop = 256;    // 23 ms
constexpr int kCorrWindow = 384;  // 35 ms, over two periods at the 70 Hz floor
constexpr int kMinTau = 10;       // 1100 Hz
constexpr int kMaxTau = 160;      // 69 Hz

/** YIN on one frame. Returns the period in samples, or 0 when unvoiced. */
inline float yinFrame(const float *x, float &confidenceOut) {
    static thread_local std::vector<float> cmnd;
    cmnd.assign(kMaxTau + 1, 1.0f);

    float energy = 0.0f;
    for (int i = 0; i < kCorrWindow; ++i) energy += x[i] * x[i];
    if (energy < 1e-6f) { confidenceOut = 0.0f; return 0.0f; }

    float running = 0.0f;
    for (int tau = kMinTau; tau <= kMaxTau; ++tau) {
        float sum = 0.0f;
        for (int i = 0; i < kCorrWindow; ++i) {
            const float d = x[i] - x[i + tau];
            sum += d * d;
        }
        running += sum;
        cmnd[tau] = running <= 0.0f ? 1.0f
                                    : sum * (float) (tau - kMinTau + 1) / running;
    }

    int best = -1;
    for (int tau = kMinTau + 1; tau < kMaxTau; ++tau) {
        if (cmnd[tau] < 0.15f) {
            while (tau + 1 < kMaxTau && cmnd[tau + 1] < cmnd[tau]) ++tau;
            best = tau;
            break;
        }
    }
    if (best < 0) {
        float lowest = 1e30f;
        for (int tau = kMinTau; tau <= kMaxTau; ++tau) {
            if (cmnd[tau] < lowest) { lowest = cmnd[tau]; best = tau; }
        }
        if (best < 0 || lowest > 0.5f) { confidenceOut = 0.0f; return 0.0f; }
    }

    // Parabolic refinement around the chosen minimum
    float refined = (float) best;
    if (best > kMinTau && best < kMaxTau) {
        const float s0 = cmnd[best - 1], s1 = cmnd[best], s2 = cmnd[best + 1];
        const float denom = 2.0f * (2.0f * s1 - s2 - s0);
        if (std::fabs(denom) > 1e-9f) refined += (s2 - s0) / denom;
    }
    confidenceOut = clampf(1.0f - cmnd[best], 0.0f, 1.0f);
    return refined;
}

/** Replaces each value with the median of its neighbourhood. Kills single-frame flips. */
inline void medianFilter(std::vector<float> &v, int radius) {
    if (v.size() < (size_t) (radius * 2 + 1)) return;
    std::vector<float> src = v;
    std::vector<float> win;
    win.reserve((size_t) radius * 2 + 1);

    for (size_t i = 0; i < v.size(); ++i) {
        win.clear();
        const size_t lo = i >= (size_t) radius ? i - radius : 0;
        const size_t hi = std::min(v.size() - 1, i + radius);
        for (size_t j = lo; j <= hi; ++j) if (src[j] > 0.0f) win.push_back(src[j]);
        if (win.empty()) { v[i] = 0.0f; continue; }
        std::sort(win.begin(), win.end());
        v[i] = win[win.size() / 2];
    }
}

/**
 * Pulls octave jumps back onto the trajectory.
 *
 * A melody almost never leaps an octave and immediately returns; YIN does that
 * all the time on low male voices. Any frame sitting a whole octave off both
 * of its neighbours is folded back.
 */
inline void fixOctaves(std::vector<float> &midi) {
    for (size_t i = 1; i + 1 < midi.size(); ++i) {
        if (midi[i] <= 0.0f || midi[i - 1] <= 0.0f || midi[i + 1] <= 0.0f) continue;
        const float ref = (midi[i - 1] + midi[i + 1]) * 0.5f;
        const float d = midi[i] - ref;
        if (std::fabs(d) > 7.0f) {
            const float octaves = std::round(d / 12.0f);
            if (octaves != 0.0f) midi[i] -= octaves * 12.0f;
        }
    }
}

}  // namespace detail

/** Tracks f0 per frame. midi[] is 0 where unvoiced; conf[] is the YIN confidence. */
inline void trackPitch(const float *mono, int n, std::vector<float> &midi,
                       std::vector<float> &conf) {
    using namespace detail;
    const int span = kCorrWindow + kMaxTau;
    const int frames = n > span ? (n - span) / kFrameHop : 0;

    midi.assign((size_t) std::max(frames, 0), 0.0f);
    conf.assign((size_t) std::max(frames, 0), 0.0f);

    for (int f = 0; f < frames; ++f) {
        float c = 0.0f;
        const float period = yinFrame(mono + (size_t) f * kFrameHop, c);
        conf[(size_t) f] = c;
        if (period > 0.0f && c > 0.4f) {
            midi[(size_t) f] = hzToMidi((float) kAnalysisRate / period);
        }
    }

    medianFilter(midi, 2);
    fixOctaves(midi);
}

/** Groups a pitch track into singable notes. */
inline std::vector<float> segmentNotes(const std::vector<float> &midi,
                                       const std::vector<float> &conf) {
    using namespace detail;
    const float msPerFrame = 1000.0f * (float) kFrameHop / (float) kAnalysisRate;
    const int minFrames = (int) std::ceil(90.0f / msPerFrame);   // 90 ms
    const int bridgeFrames = (int) std::ceil(80.0f / msPerFrame);

    std::vector<float> out;
    size_t i = 0;
    while (i < midi.size()) {
        if (midi[i] <= 0.0f) { ++i; continue; }

        const int pitch = (int) std::lround(midi[i]);
        size_t j = i;
        size_t lastGood = i;
        double sum = 0.0;
        int count = 0;

        // A run keeps going through short dropouts and through frames that
        // wobble by less than half a semitone around the same note
        while (j < midi.size()) {
            if (midi[j] > 0.0f && std::abs((int) std::lround(midi[j]) - pitch) == 0) {
                lastGood = j;
                sum += midi[j];
                ++count;
            } else if (j - lastGood > (size_t) bridgeFrames) {
                break;
            }
            ++j;
        }

        const int lengthFrames = (int) (lastGood - i + 1);
        if (lengthFrames >= minFrames && count > 0) {
            float weight = 0.0f;
            for (size_t k = i; k <= lastGood; ++k) weight += conf[k];
            if (weight / (float) lengthFrames > 0.35f) {
                out.push_back((float) i * msPerFrame);
                out.push_back((float) lengthFrames * msPerFrame);
                out.push_back((float) std::lround(sum / count));
            }
        }
        i = lastGood + 1;
    }
    return out;
}

/**
 * Krumhansl-Schmuckler key finding over a duration-weighted chroma.
 *
 * Correlating the profile against all 24 rotations is enough here: the input
 * is already a melody, so the tonic dominates the histogram.
 */
inline void detectKey(const std::vector<float> &notes, int &root, int &mode, float &confidence) {
    static const float kMajor[12] = {6.35f, 2.23f, 3.48f, 2.33f, 4.38f, 4.09f,
                                     2.52f, 5.19f, 2.39f, 3.66f, 2.29f, 2.88f};
    static const float kMinor[12] = {6.33f, 2.68f, 3.52f, 5.38f, 2.60f, 3.53f,
                                     2.54f, 4.75f, 3.98f, 2.69f, 3.34f, 3.17f};

    float chroma[12] = {0.0f};
    for (size_t i = 0; i + 2 < notes.size(); i += 3) {
        const int pc = ((int) notes[i + 2] % 12 + 12) % 12;
        chroma[pc] += notes[i + 1];  // weighted by how long the note is held
    }

    float total = 0.0f;
    for (float c : chroma) total += c;
    if (total <= 0.0f) { root = 0; mode = 0; confidence = 0.0f; return; }
    for (float &c : chroma) c /= total;

    float best = -2.0f, second = -2.0f;
    root = 0; mode = 0;

    for (int m = 0; m < 2; ++m) {
        const float *profile = m == 0 ? kMajor : kMinor;
        for (int r = 0; r < 12; ++r) {
            float meanC = 0.0f, meanP = 0.0f;
            for (int i = 0; i < 12; ++i) { meanC += chroma[i]; meanP += profile[i]; }
            meanC /= 12.0f; meanP /= 12.0f;

            float num = 0.0f, dc = 0.0f, dp = 0.0f;
            for (int i = 0; i < 12; ++i) {
                const float a = chroma[(i + r) % 12] - meanC;
                const float b = profile[i] - meanP;
                num += a * b; dc += a * a; dp += b * b;
            }
            const float corr = (dc > 0.0f && dp > 0.0f) ? num / std::sqrt(dc * dp) : 0.0f;
            if (corr > best) { second = best; best = corr; root = r; mode = m; }
            else if (corr > second) { second = corr; }
        }
    }
    // Margin over the runner-up says more than the raw correlation does
    confidence = clampf(best - std::fmax(second, 0.0f), 0.0f, 1.0f);
}

/** Onset-flux autocorrelation. Returns BPM folded into a musical range. */
inline void detectTempo(const float *mono, int n, float &bpm, float &confidence) {
    constexpr int kHop = 128;                  // 11.6 ms
    const float envRate = (float) kAnalysisRate / kHop;
    const int frames = n / kHop;
    if (frames < 64) { bpm = 0.0f; confidence = 0.0f; return; }

    Biquad bands[4];
    bands[0].lowPass(kAnalysisRate, 120.0f, 0.707f);
    bands[1].bandPass(kAnalysisRate, 600.0f, 0.9f);
    bands[2].bandPass(kAnalysisRate, 2000.0f, 0.9f);
    bands[3].highPass(kAnalysisRate, 4000.0f, 0.707f);

    std::vector<float> flux((size_t) frames, 0.0f);
    float prev[4] = {0.0f, 0.0f, 0.0f, 0.0f};

    for (int f = 0; f < frames; ++f) {
        float energy[4] = {0.0f, 0.0f, 0.0f, 0.0f};
        for (int i = 0; i < kHop; ++i) {
            const float x = mono[(size_t) f * kHop + i];
            for (int b = 0; b < 4; ++b) {
                const float y = bands[b].process(x);
                energy[b] += y * y;
            }
        }
        float sum = 0.0f;
        for (int b = 0; b < 4; ++b) {
            const float e = std::log1p(energy[b] * 1000.0f);
            const float d = e - prev[b];
            if (d > 0.0f) sum += d;      // rises only: onsets, not decays
            prev[b] = e;
        }
        flux[(size_t) f] = sum;
    }

    // Remove the slow trend so loud sections do not dominate the correlation
    float mean = 0.0f;
    for (float v : flux) mean += v;
    mean /= (float) frames;
    for (float &v : flux) v -= mean;

    const int minLag = (int) (envRate * 60.0f / 200.0f);
    const int maxLag = (int) (envRate * 60.0f / 55.0f);
    float best = 0.0f, bestLag = 0.0f, energy0 = 1e-9f;
    for (float v : flux) energy0 += v * v;

    for (int lag = minLag; lag <= maxLag && lag < frames / 2; ++lag) {
        float sum = 0.0f;
        for (int f = 0; f + lag < frames; ++f) sum += flux[(size_t) f] * flux[(size_t) (f + lag)];
        sum /= (float) (frames - lag);
        if (sum > best) { best = sum; bestLag = (float) lag; }
    }

    if (bestLag <= 0.0f) { bpm = 0.0f; confidence = 0.0f; return; }
    bpm = 60.0f * envRate / bestLag;
    while (bpm < 70.0f) bpm *= 2.0f;
    while (bpm > 180.0f) bpm *= 0.5f;
    confidence = clampf(best / (energy0 / (float) frames), 0.0f, 1.0f);
}

/** Full import-time analysis. mono is expected at kAnalysisRate. */
inline AnalysisResult analyzeSong(const float *mono, int n) {
    AnalysisResult out;
    out.durationMs = 1000.0f * (float) n / (float) kAnalysisRate;

    std::vector<float> midi, conf;
    trackPitch(mono, n, midi, conf);
    out.notes = segmentNotes(midi, conf);

    detectKey(out.notes, out.keyRoot, out.keyMode, out.keyConfidence);
    detectTempo(mono, n, out.bpm, out.bpmConfidence);
    return out;
}

}  // namespace av
