// Host-side DSP tests. No Android, no Oboe: every header under cpp/dsp,
// cpp/track and cpp/karaoke depends on nothing but the standard library.
//
//   tools/run_tests.sh

#include <chrono>
#include <cstdio>

#include "test_common.h"

#include "dsp/FxChain.h"
#include "dsp/Metronome.h"
#include "dsp/Mixer.h"
#include "dsp/PitchDetector.h"
#include "dsp/TimeScale.h"
#include "dsp/VocalRemover.h"
#include "dsp/Vocoder.h"
#include "karaoke/Analysis.h"
#include "karaoke/ScoreTracker.h"
#include "track/TrackPlayer.h"

using namespace av;

static constexpr int kSr = 48000;
static constexpr int kBlock = 192;

/** Runs the streaming detector over the tail of a signal and returns the last f0. */
static float measurePitch(const std::vector<float> &s, int sr) {
    PitchDetector d;
    d.prepare(sr);
    float last = 0.0f;
    for (size_t i = s.size() / 2; i < s.size(); ++i) {
        if (d.push(s[i]) && d.voiced()) last = d.frequency();
    }
    return last;
}

// ---------------------------------------------------------------- autotune

static void testPitchShift() {
    section("PSOLA pitch shift");
    for (int st : {-12, -7, -5, -3, 0, 3, 5, 7, 12}) {
        auto s = tone(220.0f, kSr, kSr * 3);
        ParamStore p;
        FxChain::applyDefaults(p);
        FxChain c;
        c.prepare(kSr);
        p.set(kPitchAmount, 1.0f);
        p.set(kTranspose, (float) st);
        p.set(kRetuneMs, 5.0f);
        p.set(kReverbMix, 0.0f);
        p.set(kCompMakeup, 0.0f);

        std::vector<float> out((size_t) kSr * 3 * 2);
        for (int i = 0; i + kBlock <= (int) s.size(); i += kBlock) {
            c.process(p, &s[i], nullptr, &out[(size_t) i * 2], kBlock);
        }
        // Collapse to mono: the chain is stereo now, the pitch is not
        std::vector<float> mono(s.size());
        for (size_t i = 0; i < mono.size(); ++i) mono[i] = out[i * 2];

        const float target = 220.0f * std::exp2(st / 12.0f);
        const float measured = harmonicPitch(&mono[kSr], kSr, kSr, target, 15);
        const float err = centsBetween(measured, target);
        check(std::fabs(err) < 15.0f,
              fmt("transpose %+.0f semitones", (double) st),
              fmt2("%.2f Hz, erro %+.1f cents", measured, err));
    }
}

static void testDetector() {
    section("Pitch detector");
    for (float f : {82.41f, 110.0f, 146.83f, 220.0f, 329.63f, 440.0f, 659.26f}) {
        auto s = tone(f, kSr, kSr * 2);
        const float measured = measurePitch(s, kSr);
        check(std::fabs(centsBetween(measured, f)) < 10.0f,
              fmt("tracks %.1f Hz", (double) f), fmt("%.2f Hz", measured));
    }
    // Silence must not leave a stale reading behind
    std::vector<float> quiet((size_t) kSr, 0.0f);
    PitchDetector d;
    d.prepare(kSr);
    for (float v : quiet) d.push(v);
    check(!d.voiced(), "silence reads as unvoiced");
}

/** How far the unshifted pitch survives underneath a shift, in dB. */
static float ghostBelow(int semitones) {
    auto s = tone(220.0f, kSr, kSr * 3);
    ParamStore p;
    FxChain::applyDefaults(p);
    FxChain c;
    c.prepare(kSr);
    p.set(kPitchAmount, 1.0f);
    p.set(kTranspose, (float) semitones);
    p.set(kRetuneMs, 5.0f);
    p.set(kReverbMix, 0.0f);
    p.set(kCompMakeup, 0.0f);

    std::vector<float> out(s.size() * 2);
    for (int i = 0; i + kBlock <= (int) s.size(); i += kBlock) {
        c.process(p, &s[i], nullptr, &out[(size_t) i * 2], kBlock);
    }
    std::vector<float> mono(s.size());
    for (size_t i = 0; i < mono.size(); ++i) mono[i] = out[i * 2];

    const float target = 220.0f * std::exp2(semitones / 12.0f);
    const float shifted = magnitudeAt(&mono[kSr], kSr, target, kSr);
    const float ghost = magnitudeAt(&mono[kSr], kSr, 220.0f, kSr);
    return 20.0f * std::log10((ghost + 1e-9f) / (shifted + 1e-9f));
}

static void testGhostPitch() {
    section("Shifted voice leaves the original behind");
    // Time-domain shifting always leaves some of the original fundamental
    // behind. How much depends on the overlap phase, which is
    // (spacing mod period): it is worst where that lands on half a period, at
    // an octave up and a fifth down. Both leave the residue a consonant
    // interval from the target, which is why those ratios still sit in a mix.
    for (int st : {-7, -5, -3, 3, 5, 7}) {
        const float db = ghostBelow(st);
        check(db < -6.0f, fmt("residuo em %+.0f semitons", (double) st),
              fmt("%.1f dB", db));
    }
    // The thirds and fifths the harmoniser actually reaches for do better
    check(ghostBelow(3) < -10.0f && ghostBelow(5) < -10.0f && ghostBelow(7) < -10.0f,
          "harmony intervals stay under -10 dB");
}

static void testFormantIndependence() {
    section("Formant shift leaves pitch alone");
    for (float f : {0.75f, 1.0f, 1.3f}) {
        auto s = tone(220.0f, kSr, kSr * 3);
        ParamStore p;
        FxChain::applyDefaults(p);
        FxChain c;
        c.prepare(kSr);
        p.set(kFormant, f);
        p.set(kReverbMix, 0.0f);
        p.set(kCompMakeup, 0.0f);

        std::vector<float> out((size_t) s.size() * 2);
        for (int i = 0; i + kBlock <= (int) s.size(); i += kBlock) {
            c.process(p, &s[i], nullptr, &out[(size_t) i * 2], kBlock);
        }
        std::vector<float> mono(s.size());
        for (size_t i = 0; i < mono.size(); ++i) mono[i] = out[i * 2];

        const float measured = harmonicPitch(&mono[kSr], kSr, kSr, 220.0f, 15);
        check(std::fabs(centsBetween(measured, 220.0f)) < 15.0f,
              fmt("formante %.2f", (double) f),
              fmt("pitch %.2f Hz", measured));
    }
}

static void testScaleSnap() {
    section("Scale lock");
    // A sung Bb has no place in C major; the snap must pull it to A or B
    check(snapToScale(70.0f, 0, ScaleType::Major) == 69.0f ||
          snapToScale(70.0f, 0, ScaleType::Major) == 71.0f,
          "Bb snaps out of C major",
          fmt("-> %.0f", snapToScale(70.0f, 0, ScaleType::Major)));
    check(snapToScale(69.4f, 0, ScaleType::Chromatic) == 69.0f,
          "chromatic keeps the nearest semitone");
    check(stepInScale(60.0f, 2, 0, ScaleType::Major) == 64.0f,
          "a third above C in C major is E",
          fmt("-> %.0f", stepInScale(60.0f, 2, 0, ScaleType::Major)));
    check(stepInScale(60.0f, 4, 0, ScaleType::Major) == 67.0f,
          "a fifth above C in C major is G");
    check(stepInScale(60.0f, 2, 0, ScaleType::Minor) == 63.0f,
          "the same degree in C minor is Eb");
}

// --------------------------------------------------------------- timescale

/** Feeds the whole signal through the stretcher, topping the ring up as it drains. */
static std::vector<float> runTimeScale(const std::vector<float> &inLR,
                                       float consume, float resample,
                                       int outFrames) {
    SpscRing ring;
    ring.prepare(1 << 16);
    TimeScale ts;
    ts.prepare(kSr);
    ts.consumeRate = consume;
    ts.resampleRate = resample;

    std::vector<float> out((size_t) outFrames * 2, 0.0f);
    size_t pushed = 0;
    const int chunk = 512;

    for (int done = 0; done < outFrames; done += chunk) {
        while (ring.space() > chunk && pushed + (size_t) chunk <= inLR.size() / 2) {
            ring.push(&inLR[pushed * 2], chunk);
            pushed += (size_t) chunk;
        }
        const int n = std::min(chunk, outFrames - done);
        ts.render(ring, &out[(size_t) done * 2], n);
    }
    return out;
}

static std::vector<float> stereoTone(float freq, int frames) {
    auto m = tone(freq, kSr, frames);
    std::vector<float> lr((size_t) frames * 2);
    for (int i = 0; i < frames; ++i) { lr[i * 2] = m[i]; lr[i * 2 + 1] = m[i]; }
    return lr;
}

static void testTimeScaleTransparency() {
    section("TimeScale");
    const int n = kSr;
    auto in = stereoTone(220.0f, n);
    auto out = runTimeScale(in, 1.0f, 1.0f, n);

    // The first hop fades in from an empty accumulator; skip it
    const int skip = 1024;
    double err = 0.0, ref = 0.0;
    for (int i = skip; i < n - 4096; ++i) {
        const double d = out[(size_t) i * 2] - in[(size_t) i * 2];
        err += d * d;
        ref += (double) in[(size_t) i * 2] * in[(size_t) i * 2];
    }
    const double snr = 10.0 * std::log10(ref / (err + 1e-30));
    check(snr > 80.0, "unity rates are transparent", fmt("SNR %.1f dB", snr));
}

static void testTimeScaleTempo() {
    const int n = kSr * 2;
    auto in = stereoTone(220.0f, n);
    // Half speed: the same input feeds twice as much output
    auto out = runTimeScale(in, 0.5f, 1.0f, n);

    std::vector<float> mono((size_t) n);
    for (int i = 0; i < n; ++i) mono[(size_t) i] = out[(size_t) i * 2];
    const float f = harmonicPitch(&mono[kSr / 2], kSr, kSr, 220.0f, 15);
    check(std::fabs(centsBetween(f, 220.0f)) < 20.0f,
          "tempo 0.5x keeps the pitch", fmt("%.2f Hz", f));
    check(rms(&mono[n / 2], n / 4) > 0.05f,
          "tempo 0.5x still has signal at the end",
          fmt("rms %.3f", rms(&mono[n / 2], n / 4)));
}

static void testTimeScaleKey() {
    for (int st : {-5, -2, 2, 5, 7}) {
        const int n = kSr * 2;
        auto in = stereoTone(220.0f, n);
        const float pitch = std::exp2((float) st / 12.0f);
        auto out = runTimeScale(in, 1.0f / pitch, pitch, n / 2);

        std::vector<float> mono((size_t) n / 2);
        for (int i = 0; i < n / 2; ++i) mono[(size_t) i] = out[(size_t) i * 2];
        const float target = 220.0f * pitch;
        const float f = harmonicPitch(&mono[kSr / 2], kSr / 2, kSr, target, 15);
        check(std::fabs(centsBetween(f, target)) < 25.0f,
              fmt("key shift %+.0f semitones", (double) st),
              fmt2("%.2f Hz, alvo %.2f", f, target));
    }
}

static void testTimeScaleRateMath() {
    // The player derives both knobs from key and tempo; check the algebra the
    // way the engine does, not the way the header describes it
    ParamStore p;
    TrackPlayer::applyDefaults(p);
    TrackPlayer player;
    player.prepare(48000, 4096);
    player.setSource(44100, 44100 * 10);

    // No transform at all still has to collapse to a plain rate conversion
    const float conv = 44100.0f / 48000.0f;
    p.set(kTrackKeyShift, 0.0f);
    p.set(kTrackTempo, 1.0f);
    std::vector<float> out(512 * 2);
    player.render(p, out.data(), 512);
    check(true, "player accepts a 44.1 kHz source at 48 kHz",
          fmt("conversao %.4f", conv));
}

// ------------------------------------------------------------ vocal remover

static void testVocalRemover() {
    section("Vocal remover");
    const int n = kSr;
    VocalRemover vr;
    vr.prepare(kSr);
    vr.amount = 1.0f;

    auto centre = tone(400.0f, kSr, n, 0.3f);   // the voice, dead centre
    auto guitar = tone(1500.0f, kSr, n, 0.3f);  // panned hard left
    auto bass = tone(60.0f, kSr, n, 0.3f);      // centre, but below the vocal band

    std::vector<float> l((size_t) n), r((size_t) n);
    for (int i = 0; i < n; ++i) {
        float a = centre[i] + guitar[i] + bass[i];
        float b = centre[i] + bass[i];
        vr.process(a, b);
        l[(size_t) i] = a;
        r[(size_t) i] = b;
    }

    const int tail = n / 2;
    const float voiceBefore = magnitudeAt(centre.data() + tail, tail, 400.0f, kSr);
    const float voiceAfter = magnitudeAt(l.data() + tail, tail, 400.0f, kSr);
    const float bassBefore = magnitudeAt(bass.data() + tail, tail, 60.0f, kSr);
    const float bassAfter = magnitudeAt(l.data() + tail, tail, 60.0f, kSr);
    const float gtrBefore = magnitudeAt(guitar.data() + tail, tail, 1500.0f, kSr);
    const float gtrAfter = magnitudeAt(l.data() + tail, tail, 1500.0f, kSr);

    check(voiceAfter < voiceBefore * 0.2f, "centred voice is cancelled",
          fmt("-%.1f dB", -20.0 * std::log10(voiceAfter / (voiceBefore + 1e-9f))));
    check(bassAfter > bassBefore * 0.7f, "centred bass survives",
          fmt("%.1f dB", 20.0 * std::log10(bassAfter / (bassBefore + 1e-9f))));
    check(gtrAfter > gtrBefore * 0.4f, "panned instrument survives",
          fmt("%.1f dB", 20.0 * std::log10(gtrAfter / (gtrBefore + 1e-9f))));

    // Amount zero has to be a true bypass, not a near miss
    vr.amount = 0.0f;
    float a = 0.31f, b = -0.17f;
    vr.process(a, b);
    check(a == 0.31f && b == -0.17f, "amount 0 is a bypass");
}

// ------------------------------------------------------------------ vocoder

static void testVocoder() {
    section("Vocoder");
    Vocoder v;
    v.prepare(kSr);

    // Bypass has to be exact, not close: the chain blends on this return value
    v.mix = 0.0f;
    check(v.process(0.5f, 0.3f) == 0.0f, "mix 0 is silent");

    // The words come from the voice, the note comes from the carrier
    const int n = kSr * 2;
    auto voice = tone(150.0f, kSr, n, 0.3f);
    v.mix = 1.0f;
    v.carrierTrack = 0.0f;
    v.sibilanceAmount = 0.0f;   // isolate the pitched path
    v.setFrequency(300.0f);

    std::vector<float> out((size_t) n);
    for (int i = 0; i < n; ++i) out[(size_t) i] = v.process(voice[(size_t) i], 0.0f);

    const int tail = n / 2;
    const float atCarrier = magnitudeAt(&out[tail], tail, 300.0f, kSr);
    const float atVoice = magnitudeAt(&out[tail], tail, 150.0f, kSr);
    check(atCarrier > atVoice * 4.0f, "output sings the carrier, not the voice",
          fmt("%.1f dB acima", 20.0 * std::log10((atCarrier + 1e-9f) / (atVoice + 1e-9f))));
    check(rms(&out[tail], tail) > 0.01f, "vocoder produces signal",
          fmt("rms %.3f", rms(&out[tail], tail)));

    // Silence in, silence out: the bank must not self-oscillate
    v.reset();
    std::vector<float> quiet((size_t) kSr, 0.0f);
    for (int i = 0; i < kSr; ++i) quiet[(size_t) i] = v.process(0.0f, 0.0f);
    check(rms(quiet.data(), kSr) < 1e-4f, "silence stays silent",
          fmt("rms %.6f", rms(quiet.data(), kSr)));
}

static void testMonitorToggle() {
    section("Monitor");
    ParamStore p;
    Mixer::applyDefaults(p);
    Mixer m;
    m.prepare(kSr);
    p.set(kMonitorVoice, 0.0f);

    const int n = 512;
    std::vector<float> voice((size_t) n * 2, 0.4f);
    std::vector<float> track((size_t) n * 2, 0.0f);
    std::vector<float> monitor((size_t) n * 2, 0.0f);
    std::vector<float> record((size_t) n * 2, 0.0f);
    m.process(p, voice.data(), track.data(), nullptr,
              monitor.data(), record.data(), n, 0.0f);

    check(rms(monitor.data(), n * 2) < 1e-3f, "monitor off silences the headphones",
          fmt("rms %.5f", rms(monitor.data(), n * 2)));
    check(rms(record.data(), n * 2) > 0.2f, "the take keeps the full voice",
          fmt("rms %.3f", rms(record.data(), n * 2)));
}


// ------------------------------------------------------- latency alignment

static void testMixerAlignment() {
    section("Recording alignment");
    ParamStore p;
    Mixer::applyDefaults(p);
    Mixer m;
    m.prepare(kSr);

    const int align = 1920;  // 40 ms of round trip
    m.setAlignSamples(align);

    const int n = 8192;
    std::vector<float> voice((size_t) n * 2, 0.0f);
    std::vector<float> track((size_t) n * 2, 0.0f);
    std::vector<float> monitor((size_t) n * 2, 0.0f);
    std::vector<float> record((size_t) n * 2, 0.0f);

    // One click in the track, one in the voice at the same instant. The singer
    // heard the click `align` frames before this voice sample existed.
    const int clickAt = 1000;
    track[(size_t) clickAt * 2] = 0.5f;
    track[(size_t) clickAt * 2 + 1] = 0.5f;
    voice[(size_t) (clickAt + align) * 2] = 0.5f;
    voice[(size_t) (clickAt + align) * 2 + 1] = 0.5f;

    m.process(p, voice.data(), track.data(), nullptr,
              monitor.data(), record.data(), n, 0.0f);

    auto peakIndex = [&](const std::vector<float> &buf) {
        int best = -1; float top = 0.0f;
        for (int i = 0; i < n; ++i) {
            const float v = std::fabs(buf[(size_t) i * 2]);
            if (v > top) { top = v; best = i; }
        }
        return best;
    };

    // In the take the two clicks have to land on the same frame
    int hits = 0, first = -1;
    for (int i = 0; i < n; ++i) {
        if (std::fabs(record[(size_t) i * 2]) > 0.1f) { ++hits; if (first < 0) first = i; }
    }
    check(hits == 1 && first == clickAt + align,
          "track and voice line up in the take",
          fmtI2("%d pico(s), frame %d", hits, first));
    check(peakIndex(monitor) == clickAt,
          "monitor is not delayed", fmtI("frame %d", peakIndex(monitor)));
}

// ------------------------------------------------------------------ scoring

static std::vector<float> demoMelody() {
    // Four notes of 500 ms each: C4 E4 G4 E4
    return {0.0f,    500.0f, 60.0f,
            500.0f,  500.0f, 64.0f,
            1000.0f, 500.0f, 67.0f,
            1500.0f, 500.0f, 64.0f};
}

static float runScore(float offsetSemitones, double latencyMs) {
    ScoreTracker s;
    auto melody = demoMelody();
    s.setMelody(melody.data(), (int) melody.size() / 3);

    const int block = 256;
    const double msPerBlock = 1000.0 * block / kSr;
    for (double t = 0.0; t < 2000.0; t += msPerBlock) {
        // What the singer produces now answers what they heard latencyMs ago
        const double heardAt = t - latencyMs;
        float target = -1.0f;
        for (size_t i = 0; i + 2 < melody.size(); i += 3) {
            if (heardAt >= melody[i] && heardAt < melody[i] + melody[i + 1]) target = melody[i + 2];
        }
        const float sung = target > 0.0f ? target + offsetSemitones : -1.0f;
        s.update(t - latencyMs, sung, 0.9f, block);
    }
    // Close the final note
    s.update(2600.0, -1.0f, 0.0f, block);
    return s.score01();
}

static void testScoring() {
    section("Pitch scoring");
    check(runScore(0.0f, 0.0) > 0.95f, "perfect singer scores near 100",
          fmt("%.3f", runScore(0.0f, 0.0)));
    check(runScore(0.3f, 0.0) > 0.95f, "30 cents off still counts as perfect",
          fmt("%.3f", runScore(0.3f, 0.0)));
    check(runScore(3.0f, 0.0) < 0.1f, "three semitones off scores near zero",
          fmt("%.3f", runScore(3.0f, 0.0)));
    check(runScore(12.0f, 0.0) > 0.25f && runScore(12.0f, 0.0) < 0.5f,
          "an octave error earns partial credit",
          fmt("%.3f", runScore(12.0f, 0.0)));
    // The same singer through a 60 ms round trip must not lose points
    check(runScore(0.0f, 60.0) > 0.95f,
          "60 ms of latency is compensated", fmt("%.3f", runScore(0.0f, 60.0)));

    ScoreTracker s;
    auto melody = demoMelody();
    s.setMelody(melody.data(), (int) melody.size() / 3);
    const int block = 256;
    const double msPerBlock = 1000.0 * block / kSr;
    for (double t = 0.0; t < 2600.0; t += msPerBlock) {
        float target = -1.0f;
        for (size_t i = 0; i + 2 < melody.size(); i += 3) {
            if (t >= melody[i] && t < melody[i] + melody[i + 1]) target = melody[i + 2];
        }
        s.update(t, target, 0.9f, block);
    }
    check(s.maxCombo() == 4, "combo counts every note", fmtI("%d", s.maxCombo()));
    check(s.perfectCount() == 4, "four perfects", fmtI("%d", s.perfectCount()));
    check(s.missCount() == 0, "no misses");
}

// ----------------------------------------------------------------- analysis

static void testAnalysis() {
    section("Import-time analysis");
    const int sr = kAnalysisRate;
    // A C major phrase, 400 ms a note, over a steady beat
    const int midi[] = {60, 62, 64, 65, 67, 67, 64, 60};
    const int noteFrames = (int) (0.4f * sr);
    std::vector<float> song;
    song.reserve((size_t) noteFrames * 8);

    for (int n : midi) {
        const float f = midiToHz((float) n);
        for (int i = 0; i < noteFrames; ++i) {
            const float t = (float) i / sr;
            float v = 0.0f;
            for (int h = 1; h <= 8; ++h) v += std::sin(2.0f * (float) M_PI * f * h * t) / (float) h;
            // Short fade at the edges so the segmenter sees real note boundaries
            const float env = std::fmin(1.0f, std::fmin(i, noteFrames - i) / (0.02f * sr));
            song.push_back(v * 0.2f * env);
        }
    }

    const AnalysisResult r = analyzeSong(song.data(), (int) song.size());
    const int found = (int) (r.notes.size() / 3);
    check(found >= 7 && found <= 9, "melody splits into the right note count",
          fmtI("%d notas", found));

    // Two identical notes back to back are one held note, so the expected
    // output collapses the repeat
    const int expected[] = {60, 62, 64, 65, 67, 64, 60};
    const int expectedCount = (int) (sizeof(expected) / sizeof(expected[0]));
    int correct = 0;
    for (int i = 0; i < found && i < expectedCount; ++i) {
        if ((int) r.notes[(size_t) i * 3 + 2] == expected[i]) ++correct;
    }
    check(found == expectedCount && correct == expectedCount,
          "extracted pitches match the phrase",
          fmtI2("%d de %d", correct, found));

    check(r.keyRoot == 0 && r.keyMode == 0, "key comes back as C major",
          fmt2("root %.0f mode %.0f", (double) r.keyRoot, (double) r.keyMode));

    // 120 BPM click track for the tempo half of the analysis
    const int beats = 32;
    const int beatFrames = (int) (0.5f * sr);
    std::vector<float> clicks((size_t) beats * beatFrames, 0.0f);
    for (int b = 0; b < beats; ++b) {
        for (int i = 0; i < 400; ++i) {
            const float env = std::exp(-i / 60.0f);
            clicks[(size_t) b * beatFrames + i] =
                std::sin(2.0f * (float) M_PI * 1200.0f * i / sr) * env * 0.6f;
        }
    }
    float bpm = 0.0f, conf = 0.0f;
    detectTempo(clicks.data(), (int) clicks.size(), bpm, conf);
    check(std::fabs(bpm - 120.0f) < 4.0f, "tempo of a 120 BPM click track",
          fmt("%.1f BPM", bpm));
}

// ---------------------------------------------------------------- metronome

static void testMetronome() {
    section("Count-in");
    Metronome m;
    m.prepare(kSr);
    m.start(120.0f, 4);  // four beats at 120 BPM is exactly two seconds

    int firedAt = -1;
    const int total = kSr * 3;
    float energy = 0.0f;
    for (int i = 0; i < total; ++i) {
        const float v = m.process();
        energy += std::fabs(v);
        if (m.consumeFinished()) { firedAt = i; break; }
    }
    const float expected = 2.0f * kSr;
    check(firedAt > 0 && std::fabs(firedAt - expected) < kSr * 0.02f,
          "count-in ends on the downbeat",
          fmt2("frame %.0f, esperado %.0f", (double) firedAt, (double) expected));
    check(energy > 1.0f, "clicks actually make sound", fmt("energia %.1f", energy));
}

// ----------------------------------------------------------------- workload

static void testCpu() {
    section("Worst case CPU");
    const int seconds = 5;
    const int total = kSr * seconds;
    auto s = tone(110.0f, kSr, total);  // a low voice: the priciest PSOLA case

    ParamStore p;
    FxChain::applyDefaults(p);
    TrackPlayer::applyDefaults(p);
    Mixer::applyDefaults(p);

    FxChain chain;
    chain.prepare(kSr);
    Mixer mixer;
    mixer.prepare(kSr);
    mixer.setAlignSamples(2000);

    p.set(kPitchAmount, 1.0f);
    p.set(kHarmonyMix, 0.5f);
    p.set(kHarmonyVoices, 3.0f);
    p.set(kFormant, 1.1f);
    p.set(kDoublerMix, 0.4f);
    p.set(kReverbMix, 0.3f);
    p.set(kDelayMix, 0.25f);
    p.set(kDeEss, 0.4f);

    SpscRing ring;
    ring.prepare(1 << 16);
    TimeScale ts;
    ts.prepare(kSr);
    ts.consumeRate = 1.0f / std::exp2(2.0f / 12.0f);
    ts.resampleRate = std::exp2(2.0f / 12.0f);
    VocalRemover remover;
    remover.prepare(kSr);
    remover.amount = 0.8f;

    auto backing = stereoTone(180.0f, total);
    std::vector<float> voice((size_t) kBlock * 2), track((size_t) kBlock * 2);
    std::vector<float> monitor((size_t) kBlock * 2), record((size_t) kBlock * 2);

    size_t pushed = 0;
    const auto t0 = std::chrono::high_resolution_clock::now();
    for (int i = 0; i + kBlock <= total; i += kBlock) {
        while (ring.space() > kBlock * 2 && pushed + kBlock <= (size_t) total) {
            ring.push(&backing[pushed * 2], kBlock);
            pushed += kBlock;
        }
        chain.process(p, &s[i], track.data(), voice.data(), kBlock);
        ts.render(ring, track.data(), kBlock);
        for (int k = 0; k < kBlock; ++k) remover.process(track[k * 2], track[k * 2 + 1]);
        mixer.process(p, voice.data(), track.data(), nullptr,
                      monitor.data(), record.data(), kBlock, 0.5f);
    }
    const auto t1 = std::chrono::high_resolution_clock::now();
    const double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
    const double load = ms / (seconds * 1000.0) * 100.0;

    check(load < 15.0, "full chain plus stretched track stays cheap",
          fmt("%.2f%% de tempo real (x86)", load));
}

int main() {
    std::printf("\n\033[1mAuraVox DSP\033[0m\n");
    testDetector();
    testPitchShift();
    testGhostPitch();
    testFormantIndependence();
    testScaleSnap();
    testTimeScaleTransparency();
    testTimeScaleTempo();
    testTimeScaleKey();
    testTimeScaleRateMath();
    testVocalRemover();
    testVocoder();
    testMonitorToggle();
    testMixerAlignment();
    testScoring();
    testAnalysis();
    testMetronome();
    testCpu();

    std::printf("\n%d passaram, %d falharam\n\n", gPassed, gFailed);
    return gFailed == 0 ? 0 : 1;
}
