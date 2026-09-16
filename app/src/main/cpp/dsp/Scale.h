#pragma once

#include <cmath>

namespace av {

enum class ScaleType { Chromatic = 0, Major, Minor, MinorHarmonic, Pentatonic, Blues, Dorian, Mixolydian, Count };

struct ScaleDef {
    int count;
    int degrees[12];
};

inline const ScaleDef &scaleDef(ScaleType s) {
    static const ScaleDef kScales[(int) ScaleType::Count] = {
        {12, {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}},  // chromatic
        {7,  {0, 2, 4, 5, 7, 9, 11}},                  // major
        {7,  {0, 2, 3, 5, 7, 8, 10}},                  // natural minor
        {7,  {0, 2, 3, 5, 7, 8, 11}},                  // harmonic minor
        {5,  {0, 2, 4, 7, 9}},                         // major pentatonic
        {6,  {0, 3, 5, 6, 7, 10}},                     // blues
        {7,  {0, 2, 3, 5, 7, 9, 10}},                  // dorian
        {7,  {0, 2, 4, 5, 7, 9, 10}},                  // mixolydian
    };
    return kScales[(int) s];
}

/** Nearest in-scale midi note, fractional input, fractional-free output. */
inline float snapToScale(float midi, int keyRoot, ScaleType scale) {
    const ScaleDef &sd = scaleDef(scale);
    const float rel = midi - (float) keyRoot;
    const int octave = (int) std::floor(rel / 12.0f);
    const float within = rel - (float) octave * 12.0f;

    int bestDeg = sd.degrees[0];
    float bestDist = 1e30f;
    for (int i = 0; i < sd.count; ++i) {
        // Test this octave and the next, so the wrap point is covered
        for (int o = 0; o <= 12; o += 12) {
            const float d = std::fabs(within - (float) (sd.degrees[i] + o));
            if (d < bestDist) { bestDist = d; bestDeg = sd.degrees[i] + o; }
        }
    }
    return (float) keyRoot + (float) octave * 12.0f + (float) bestDeg;
}

/** Moves n scale degrees up (or down) from an already-snapped note. */
inline float stepInScale(float snappedMidi, int steps, int keyRoot, ScaleType scale) {
    const ScaleDef &sd = scaleDef(scale);
    const int rel = (int) std::lround(snappedMidi) - keyRoot;
    const int octave = (int) std::floor((float) rel / 12.0f);
    const int within = rel - octave * 12;

    int idx = 0;
    for (int i = 0; i < sd.count; ++i) {
        if (sd.degrees[i] == within) { idx = i; break; }
    }
    const int target = idx + steps;
    const int octShift = (int) std::floor((float) target / (float) sd.count);
    const int degIdx = target - octShift * sd.count;
    return (float) (keyRoot + (octave + octShift) * 12 + sd.degrees[degIdx]);
}

}  // namespace av
