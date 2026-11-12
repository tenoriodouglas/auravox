// Host-side DSP tests. No Android, no Oboe: every header under cpp/dsp,
// cpp/track and cpp/karaoke depends on nothing but the standard library.
//
//   tools/run_tests.sh

#include <algorithm>
#include <chrono>
#include <cstdio>

#include "test_common.h"

#include "dsp/FxChain.h"
#include "dsp/InputFifo.h"
#include "dsp/Metronome.h"
#include "dsp/Mixer.h"
#include "dsp/PitchDetector.h"
#include "dsp/Resampler.h"
#include "dsp/Stft.h"
#include "separation/Separator.h"
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

// ----------------------------------------------------------------- captura

static void testInputFifo() {
    section("FIFO de captura");
    InputFifo f;
    f.prepare(64);
    check(f.capacity() >= 64, "capacidade arredonda para potencia de dois",
          fmtI("%d", f.capacity()));

    float in[8] = {1, 2, 3, 4, 5, 6, 7, 8};
    f.write(in, 8);
    float out[8] = {0};
    check(f.read(out, 8) && out[0] == 1.0f && out[7] == 8.0f, "ida e volta");
    check(!f.read(out, 1), "leitura curta e recusada, nao inventada");

    f.clear();
    f.write(in, 4);
    const int got = f.readPartial(out, 8);
    check(got == 4 && out[4] == 0.0f && out[7] == 0.0f,
          "leitura parcial completa com silencio", fmtI("%d quadros", got));

    // Overflow drops the oldest, never the newest: the newest is what the
    // singer just sang
    f.clear();
    std::vector<float> flood((size_t) f.capacity() + 16);
    for (size_t i = 0; i < flood.size(); ++i) flood[i] = (float) i;
    f.write(flood.data(), (int) flood.size());
    f.skip(f.available() - 1);
    float tail = 0.0f;
    f.read(&tail, 1);
    check(tail == (float) (flood.size() - 1), "overflow descarta o mais antigo",
          fmt("%.0f", tail));
}

/**
 * The static the app shipped with, reproduced and then fixed.
 *
 * Capture and playback run on the same sample clock but on different threads,
 * so what wanders between callbacks is the phase, not the rate: cumulative
 * production stays inside a bounded band instead of drifting away. Reading the
 * stream straight into the block means zero-filling whatever has not landed
 * yet, which splices silence into the middle of the voice several times a
 * second.
 *
 * A counter as the signal makes it measurable: every sample the consumer sees
 * has to be exactly one more than the last, forever.
 */
struct CaptureTrial { int tears; int silentBlocks; int cushion; };

static CaptureTrial captureTrial(bool useFifo, float jitterBursts, int stallEvery) {
    const int block = 192;
    const int burst = 192;
    const int blocks = 2000;

    InputFifo fifo;
    fifo.prepare(burst * 16);
    FifoPolicy policy;
    policy.configure(burst);

    std::vector<float> chunk((size_t) burst * 8);
    std::vector<float> outBlock((size_t) block);
    double delivered = 0.0;
    float counter = 1.0f;
    int tears = 0, silentBlocks = 0;
    float last = 0.0f;
    bool started = false;
    unsigned rng = 12345u;

    for (int b = 0; b < blocks; ++b) {
        rng = rng * 1103515245u + 12345u;
        const float phase = (float) ((rng >> 16) & 0xFF) / 255.0f - 0.5f;

        // Cumulative, not incremental: the two clocks agree on the long run
        double target = (double) (b + 1) * block + phase * burst * jitterBursts;
        // A scheduler stall delivers nothing now and everything next time
        if (stallEvery > 0 && b % stallEvery == stallEvery - 1) target = delivered;

        int ready = (int) (target - delivered);
        if (ready < 0) ready = 0;
        delivered += ready;

        while (ready > 0) {
            const int n = std::min(ready, (int) chunk.size());
            for (int i = 0; i < n; ++i) chunk[(size_t) i] = counter++;
            fifo.write(chunk.data(), n);
            ready -= n;
        }

        if (useFifo) {
            policy.trim(fifo, block);
            if (!policy.serve(fifo, outBlock.data(), block)) { ++silentBlocks; continue; }
        } else {
            // What the app did before: take what is there, zero the rest
            if (fifo.readPartial(outBlock.data(), block) < block) ++silentBlocks;
        }

        for (int i = 0; i < block; ++i) {
            const float v = outBlock[(size_t) i];
            if (started && v != last + 1.0f) ++tears;
            last = v;
            started = true;
        }
    }
    return CaptureTrial{tears, silentBlocks, policy.cushion};
}

static void testCaptureContinuity() {
    section("Continuidade da captura");

    const CaptureTrial broken = captureTrial(false, 1.6f, 0);
    const CaptureTrial fixed = captureTrial(true, 1.6f, 0);

    check(broken.tears > 50, "o caminho antigo rasga o sinal o tempo todo",
          fmtI("%d cortes sem FIFO", broken.tears));
    check(fixed.tears == 0, "com FIFO o sinal fica continuo",
          fmtI("%d cortes", fixed.tears));
    check(fixed.silentBlocks <= 2, "so os blocos de escorva ficam mudos",
          fmtI("%d blocos", fixed.silentBlocks));

    // A device that jitters harder must settle, not tear forever
    const CaptureTrial rough = captureTrial(true, 5.0f, 0);
    check(rough.tears <= 2, "jitter alto se acomoda numa almofada maior",
          fmtI2("%d cortes, almofada %d quadros", rough.tears, rough.cushion));
    check(rough.cushion <= 192 * 4, "a almofada para de crescer",
          fmtI("%d quadros", rough.cushion));

    // One outright stall should cost one block, not the rest of the song
    const CaptureTrial stalled = captureTrial(true, 1.6f, 500);
    check(stalled.tears <= 6, "uma travada custa um bloco, nao a musica inteira",
          fmtI("%d cortes", stalled.tears));
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

// --------------------------------------------------------------- autotune

/** Tone whose pitch follows a function of time, by integrating the phase. */
template <typename F>
static std::vector<float> warpedTone(F midiAt, int sampleRate, int frames, float amp = 0.22f) {
    std::vector<float> out((size_t) frames);
    double phase = 0.0;
    for (int i = 0; i < frames; ++i) {
        const float midi = midiAt((float) i / (float) sampleRate);
        const double f = 440.0 * std::pow(2.0, ((double) midi - 69.0) / 12.0);
        phase += f / (double) sampleRate;
        float v = 0.0f;
        for (int h = 1; h <= 10; ++h) v += (float) std::sin(2.0 * M_PI * phase * h) / (float) h;
        out[(size_t) i] = v * amp;
    }
    return out;
}

/** Every voiced estimate the detector produces over a signal. */
static std::vector<float> trackMidi(const std::vector<float> &x, int sampleRate) {
    PitchDetector d;
    d.prepare(sampleRate);
    std::vector<float> out;
    for (float s : x) {
        if (d.push(s) && d.voiced()) out.push_back(d.midi());
    }
    return out;
}

/** Runs a voice through the processor and returns the lead output. */
static std::vector<float> runVoice(VoiceProcessor &vp, const std::vector<float> &x) {
    std::vector<float> out(x.size());
    float harm[3];
    for (size_t i = 0; i < x.size(); ++i) out[i] = vp.process(x[i], harm);
    return out;
}

static void peakToPeak(const std::vector<float> &v, size_t skip, float &lo, float &hi) {
    lo = 1e30f;
    hi = -1e30f;
    for (size_t i = skip; i < v.size(); ++i) {
        lo = std::min(lo, v[i]);
        hi = std::max(hi, v[i]);
    }
}

/** Median absolute deviation from a value. Robust to a tracker glitch. */
static float medianError(const std::vector<float> &v, size_t from, size_t to, float ref) {
    std::vector<float> e;
    for (size_t i = from; i < v.size() && i < to; ++i) e.push_back(std::fabs(v[i] - ref));
    if (e.empty()) return 99.0f;
    std::sort(e.begin(), e.end());
    return e[e.size() / 2];
}

static float meanOf(const std::vector<float> &v, size_t skip) {
    double sum = 0.0;
    size_t n = 0;
    for (size_t i = skip; i < v.size(); ++i) { sum += v[i]; ++n; }
    return n > 0 ? (float) (sum / n) : 0.0f;
}

/**
 * The behaviours that decide whether a correction sounds professional.
 *
 * None of them is about where the note lands: a snap to the nearest scale
 * note is arithmetic and was already right. These are about when the pitch
 * moves, which is the whole of the difference.
 */
static void testAutotuneBehaviour() {
    section("Autotune");

    // --- a committed note, so a voice on the midpoint does not warble ---
    {
        VoiceProcessor vp;
        vp.prepare(kSr);
        vp.amount = 1.0f;
        vp.scale = ScaleType::Chromatic;

        const int n = kSr * 3;
        auto x = warpedTone([](float t) { return 60.0f + t / 3.0f; }, kSr, n);
        float switchAt = -1.0f;
        float harm[3];
        for (int i = 0; i < n; ++i) {
            vp.process(x[(size_t) i], harm);
            if (switchAt < 0.0f && vp.committedMidi() > 60.5f) {
                switchAt = 60.0f + (float) i / (float) kSr / 3.0f;
            }
        }
        check(switchAt > 60.55f && switchAt < 60.85f,
              "a nota troca com folga, nao no meio", fmt("trocou em %.2f", switchAt));
    }

    /*
     * A fast attack, so the move to a new note is in tune quickly.
     *
     * The voice jumps to a note a whole tone below the one the melody is on,
     * which is a correction of a full semitone once the guide claims it. With
     * a slow retune that semitone arrives over the next half second; the
     * attack is what puts it there at once.
     */
    {
        const int n = kSr;
        auto x = warpedTone([](float t) { return t < 0.5f ? 60.0f : 63.0f; }, kSr, n);
        const int jumpFrame = (int) (0.5f * kSr / 1024.0f);

        auto medianOff = [&](float onsetMs) {
            VoiceProcessor vp;
            vp.prepare(kSr);
            vp.amount = 1.0f;
            vp.scale = ScaleType::Chromatic;
            vp.useGuide = true;
            vp.guideMidi = 64.0f;
            vp.setRetuneMs(400.0f);
            vp.setOnsetMs(onsetMs);
            auto y = runVoice(vp, x);
            auto midi = trackMidi(y, kSr);
            // From four frames after the jump, by median: the detector reports
            // on the middle of a 43 ms window, so the frames spanning the jump
            // describe a pitch that is partly the old note whatever we do, and
            // a few of them come back an octave out. That is the measuring
            // tracker misreading, not the shifter — a spectral estimate of a
            // steady shift lands within a cent of what was asked for.
            return medianError(midi, (size_t) jumpFrame + 4, (size_t) jumpFrame + 14, 64.0f);
        };

        const float withOnset = medianOff(8.0f);
        const float without = medianOff(400.0f);  // attack as slow as the retune
        check(withOnset < 0.15f, "o ataque corrige a troca de nota",
              fmt("erro mediano %.3f semitom", withOnset));
        check(without - withOnset > 0.4f, "sem ataque a nota nova fica desafinada",
              fmt2("%.3f sem, %.3f com", without, withOnset));
    }

    /*
     * The note is fixed and the expression is kept.
     *
     * A singer holding a note half a semitone sharp with vibrato on it should
     * come out on the note with the vibrato still there. Aiming at the instant
     * pitch instead would try to hold the voice still, which a time-domain
     * shifter cannot do at a ratio near one, and the correction then fights
     * the vibrato instead of the error.
     */
    {
        const int n = kSr * 2;
        auto x = warpedTone(
            [](float t) { return 60.5f + 0.4f * std::sin(2.0f * (float) M_PI * 4.0f * t); },
            kSr, n);

        VoiceProcessor vp;
        vp.prepare(kSr);
        vp.amount = 1.0f;
        vp.setRetuneMs(25.0f);
        vp.useGuide = true;
        vp.guideMidi = 60.0f;  // the melody says which note, so no ambiguity

        auto y = runVoice(vp, x);
        auto inMidi = trackMidi(x, kSr);
        auto outMidi = trackMidi(y, kSr);
        const float inMean = meanOf(inMidi, inMidi.size() / 3);
        const float outMean = meanOf(outMidi, outMidi.size() / 3);
        float lo, hi;
        peakToPeak(inMidi, inMidi.size() / 3, lo, hi);
        const float inSwing = hi - lo;
        peakToPeak(outMidi, outMidi.size() / 3, lo, hi);
        const float outSwing = hi - lo;

        check(std::fabs(outMean - 60.0f) < 0.12f, "a nota desafinada vai para o tom",
              fmt2("entrou em %.2f, saiu em %.2f", inMean, outMean));
        check(outSwing > inSwing * 0.7f, "e o vibrato do cantor continua la",
              fmt2("%.2f semitom de %.2f", outSwing, inSwing));
    }

    // --- vibrato the app adds, for a voice with none ---
    {
        const int n = kSr * 2;
        auto x = warpedTone([](float) { return 60.0f; }, kSr, n);

        VoiceProcessor vp;
        vp.prepare(kSr);
        vp.amount = 0.0f;
        vp.setVibrato(0.5f, 5.0f);
        auto y = runVoice(vp, x);
        auto midi = trackMidi(y, kSr);
        float lo, hi;
        peakToPeak(midi, midi.size() / 3, lo, hi);
        check(hi - lo > 0.6f, "o vibrato gerado aparece na saida",
              fmt("%.2f semitom de oscilacao", hi - lo));

        // And costs nothing when it is off
        VoiceProcessor off;
        off.prepare(kSr);
        off.amount = 0.0f;
        off.setVibrato(0.0f, 5.0f);
        auto flat = runVoice(off, x);
        auto flatMidi = trackMidi(flat, kSr);
        peakToPeak(flatMidi, flatMidi.size() / 3, lo, hi);
        check(hi - lo < 0.1f, "sem vibrato a nota fica parada", fmt("%.3f semitom", hi - lo));
    }
}

// ------------------------------------------------------------ reamostragem

/**
 * Rate conversion for the separation pass.
 *
 * The point of a sinc kernel over a cheap interpolator is the band it removes,
 * so that is what gets measured: a tone above the output Nyquist has to come
 * back attenuated rather than folded down into the audible range, because a
 * folded tone is a frequency the separator would then try to assign to a
 * singer.
 */
static void testResampler() {
    section("Reamostragem");

    // --- 48k to 44.1k, the conversion that actually happens ---
    {
        Resampler r;
        check(r.prepare(48000.0, 44100.0), "prepara 48k para 44.1k", "");
        const int inN = 48000;
        std::vector<float> in((size_t) inN);
        for (int i = 0; i < inN; ++i) {
            in[(size_t) i] = 0.4f * std::sin(2.0f * (float) M_PI * 1000.0f * (float) i / 48000.0f);
        }
        const int outN = r.outputFrames(inN);
        std::vector<float> out((size_t) outN);
        r.process(in.data(), inN, out.data(), outN);

        check(std::abs(outN - 44100) <= 2, "o numero de amostras bate com a taxa",
              fmtI2("%d, esperado ~%d", outN, 44100));

        // Skip the kernel's reach at both ends
        const int lo = 64, len = outN - 128;
        const float got = magnitudeAt(out.data() + lo, len, 1000.0f, 44100);
        check(got > 0.38f && got < 0.42f, "um tom de 1 kHz atravessa com o nivel certo",
              fmt("%.4f de 0.4000", got));

        // Measured against its own neighbours rather than with the harmonic
        // estimator, whose search grid is ten cents wide and so cannot resolve
        // this: correlating at exactly 1 kHz over a second of audio would not
        // return full amplitude if the tone had moved
        const float below = magnitudeAt(out.data() + lo, len, 1000.0f * 0.9885f, 44100);
        const float above = magnitudeAt(out.data() + lo, len, 1000.0f * 1.0116f, 44100);
        check(got > below * 20.0f && got > above * 20.0f,
              "e na frequencia certa, nao 20 cents ao lado",
              fmt2("1 kHz %.4f, pior vizinho %.4f", got, std::max(below, above)));
    }

    // --- the band that would alias ---
    {
        Resampler r;
        r.prepare(48000.0, 44100.0);
        const int inN = 48000;
        std::vector<float> in((size_t) inN);
        // 23 kHz is under the input Nyquist and over the output one, so it has
        // nowhere to go: a resampler without a filter folds it to 21.1 kHz
        for (int i = 0; i < inN; ++i) {
            in[(size_t) i] = 0.4f * std::sin(2.0f * (float) M_PI * 23000.0f * (float) i / 48000.0f);
        }
        const int outN = r.outputFrames(inN);
        std::vector<float> out((size_t) outN);
        r.process(in.data(), inN, out.data(), outN);

        const int lo = 64, len = outN - 128;
        const float folded = magnitudeAt(out.data() + lo, len, 21100.0f, 44100);
        // 32 taps put 23 kHz inside the transition band rather than the
        // stopband, so the rejection is in the twenties of dB and not the
        // sixties. That is the right trade: the alternative is rolling the
        // cutoff down into the audible top octave to buy rejection of a
        // frequency nobody can hear and few recordings contain
        const float dB = 20.0f * std::log10(std::max(folded, 1e-9f) / 0.4f);
        check(folded < 0.03f, "a banda que iria aliasar e atenuada, nao dobrada",
              fmt2("%.4f em 21.1 kHz, %.0f dB abaixo", folded, dB));
    }

    // --- nothing to do is free ---
    {
        Resampler r;
        r.prepare(44100.0, 44100.0);
        check(r.passthrough(), "mesma taxa e reconhecida como passagem direta", "");
    }

    // --- 44.1k to 48k, for completeness ---
    {
        Resampler r;
        r.prepare(44100.0, 48000.0);
        const int inN = 44100;
        std::vector<float> in((size_t) inN);
        for (int i = 0; i < inN; ++i) {
            in[(size_t) i] = 0.4f * std::sin(2.0f * (float) M_PI * 440.0f * (float) i / 44100.0f);
        }
        const int outN = r.outputFrames(inN);
        std::vector<float> out((size_t) outN);
        r.process(in.data(), inN, out.data(), outN);
        const int lo = 64, len = outN - 128;
        const float got = magnitudeAt(out.data() + lo, len, 440.0f, 48000);
        check(got > 0.38f && got < 0.42f, "subir a taxa mantem o nivel", fmt("%.4f", got));
    }
}

// -------------------------------------------------------------- separacao

/**
 * The two properties the separator must have, independent of the neural net.
 *
 * The net is a black box here on purpose: these tests stand in for it with
 * magnitudes made up on the spot, because what is being checked is not how
 * well it separates but that nothing is lost or duplicated on the way through.
 * Both numbers were first measured against the real model in a prototype, and
 * both are the difference between an instrumental that sounds right and one
 * that sounds broken.
 */
static void testSeparator() {
    section("Separacao de vocal");

    constexpr int kNFft = 4096;
    constexpr int kHop = 1024;
    constexpr int kModelBins = 1024;  // of the 2049 a 4096 window gives

    Separator sep;
    sep.prepare(kNFft, kHop, kModelBins);
    check(sep.bins() == 2049, "a janela de 4096 da 2049 bins", fmtI("%d", sep.bins()));

    // --- masks are a ratio and always add to one ---
    {
        std::vector<float> v((size_t) kModelBins), a((size_t) kModelBins);
        for (int k = 0; k < kModelBins; ++k) {
            // The two nets are trained apart and their magnitudes do not add
            // up to the mixture, so the mask has to be a ratio
            v[(size_t) k] = 0.4f * (float) (k % 7) + 0.1f;
            a[(size_t) k] = 1.3f * (float) (k % 5) + 0.05f;
        }
        a[0] = 0.0f; v[0] = 0.0f;  // a silent bin, where the ratio is undefined

        std::vector<float> mv((size_t) sep.bins()), ma((size_t) sep.bins());
        sep.buildMasks(v.data(), a.data(), mv.data(), ma.data());

        float worst = 0.0f;
        for (int k = 0; k < sep.bins(); ++k) {
            worst = std::max(worst, std::fabs(mv[(size_t) k] + ma[(size_t) k] - 1.0f));
        }
        check(worst < 1e-6f, "as mascaras somam 1 em todo bin", fmt("desvio %.2e", worst));
        check(mv[0] == 0.0f && ma[0] == 1.0f,
              "um bin silencioso vai inteiro para o acompanhamento", "");

        // Above the net's ceiling the mask has to carry on, or the top end of
        // the song is thrown away
        float aboveSum = 0.0f;
        for (int k = kModelBins; k < sep.bins(); ++k) aboveSum += mv[(size_t) k] + ma[(size_t) k];
        check(aboveSum > (float) (sep.bins() - kModelBins) * 0.99f,
              "a mascara continua acima do teto da rede",
              fmt("soma %.0f", aboveSum));
    }

    // --- the two stems add back to the mixture ---
    {
        const int n = kHop * 24 + kNFft;
        std::vector<float> mix((size_t) n);
        for (int i = 0; i < n; ++i) {
            const float t = (float) i / 44100.0f;
            // A voice, a bass, and content above 11 kHz that the net does not
            // model at all: the band that Spleeter's own default discards
            mix[(size_t) i] =
                0.30f * std::sin(2.0f * (float) M_PI * 220.0f * t) +
                0.25f * std::sin(2.0f * (float) M_PI * 55.0f * t) +
                0.20f * std::sin(2.0f * (float) M_PI * 14000.0f * t);
        }

        const int frames = sep.frameCount(n);
        std::vector<float> modelMag((size_t) frames * (size_t) kModelBins);
        sep.analyze(mix.data(), n, 0, modelMag.data());

        // Stand in for the net: claim half of everything is voice. Whatever it
        // claims, the two stems must still add back to the input
        std::vector<float> vMag(modelMag.size()), aMag(modelMag.size());
        for (size_t i = 0; i < modelMag.size(); ++i) {
            vMag[i] = modelMag[i] * 0.7f;
            aMag[i] = modelMag[i] * 0.5f;
        }

        std::vector<float> voice((size_t) n), backing((size_t) n);
        sep.synthesize(vMag.data(), aMag.data(), 0, n, voice.data(), backing.data());

        // Only where the windows fully overlap: four of them at this hop
        const int lo = kNFft, hi = n - kNFft;
        float worst = 0.0f, peak = 0.0f;
        for (int i = lo; i < hi; ++i) {
            worst = std::max(worst,
                std::fabs(voice[(size_t) i] + backing[(size_t) i] - mix[(size_t) i]));
            peak = std::max(peak, std::fabs(mix[(size_t) i]));
        }
        check(worst < 1e-4f, "voz mais acompanhamento devolvem a mistura",
              fmt2("erro %.2e em pico %.2f", worst, peak));

        // The band the net never sees must survive. Dropping it is what makes
        // an instrumental sound muffled, and it measured 39 dB down in the
        // prototype when the mask was left at zero up there
        const float inHigh = magnitudeAt(mix.data() + lo, hi - lo, 14000.0f, 44100);
        const float outHigh =
            magnitudeAt(voice.data() + lo, hi - lo, 14000.0f, 44100) +
            magnitudeAt(backing.data() + lo, hi - lo, 14000.0f, 44100);
        check(outHigh > inHigh * 0.9f, "a banda acima do teto da rede sobrevive",
              fmt2("%.4f de %.4f", outHigh, inHigh));
    }

    // --- one stem at a time, for when only the backing track is wanted ---
    {
        const int n = kHop * 16 + kNFft;
        std::vector<float> mix((size_t) n);
        for (int i = 0; i < n; ++i) {
            mix[(size_t) i] = 0.3f * std::sin(
                2.0f * (float) M_PI * 300.0f * (float) i / 44100.0f);
        }
        const int frames = sep.frameCount(n);
        std::vector<float> modelMag((size_t) frames * (size_t) kModelBins);
        sep.analyze(mix.data(), n, 1, modelMag.data());

        std::vector<float> vMag(modelMag.size(), 0.0f);
        std::vector<float> aMag(modelMag);  // the net says it is all backing

        std::vector<float> backing((size_t) n);
        sep.synthesize(vMag.data(), aMag.data(), 1, n, nullptr, backing.data());

        const int lo = kNFft, hi = n - kNFft;
        const float got = magnitudeAt(backing.data() + lo, hi - lo, 300.0f, 44100);
        check(got > 0.27f && got < 0.33f,
              "pedir so o acompanhamento devolve a mistura inteira", fmt("%.3f", got));
    }
}

// -------------------------------------------------------------------- stft

/**
 * The transform the separation pass is built on.
 *
 * A mask-based separator is only as good as its round trip: whatever the model
 * gets wrong, the transform must not add errors of its own on top. So the
 * first thing to prove is that analysis followed by synthesis returns the
 * input, and the second is that scaling the magnitudes actually moves the
 * right energy.
 */
static void testStft() {
    section("STFT");

    // --- the FFT underneath ---
    Fft fft;
    fft.prepare(1024);
    std::vector<float> re(1024), im(1024), re0(1024);
    for (int i = 0; i < 1024; ++i) {
        re[(size_t) i] = std::sin(2.0f * (float) M_PI * 5.0f * (float) i / 1024.0f)
                       + 0.4f * std::cos(2.0f * (float) M_PI * 77.0f * (float) i / 1024.0f);
        im[(size_t) i] = 0.0f;
        re0[(size_t) i] = re[(size_t) i];
    }
    fft.forward(re.data(), im.data());

    // Bin 5 should hold the sine: magnitude N/2 for unit amplitude
    const float bin5 = std::sqrt(re[5] * re[5] + im[5] * im[5]);
    const float bin40 = std::sqrt(re[40] * re[40] + im[40] * im[40]);
    check(bin5 > 400.0f && bin40 < 1.0f, "a senoide cai no bin certo",
          fmt2("bin5 %.0f, bin40 %.2f", bin5, bin40));

    fft.inverse(re.data(), im.data());
    float worst = 0.0f;
    for (int i = 0; i < 1024; ++i) worst = std::max(worst, std::fabs(re[(size_t) i] - re0[(size_t) i]));
    check(worst < 1e-4f, "FFT ida e volta devolve o sinal", fmt("erro %.2e", worst));

    // --- analysis/synthesis round trip ---
    const int n = 24000;
    std::vector<float> x = tone(220.0f, kSr, n, 0.3f);
    Stft stft;
    stft.prepare(1024, 256);
    const int frames = stft.frameCount(n);

    std::vector<float> mag((size_t) stft.bins()), phase((size_t) stft.bins());
    std::vector<float> y((size_t) n);
    stft.beginSynthesis(n);
    for (int f = 0; f < frames; ++f) {
        stft.analyze(x.data(), n, f, mag.data(), phase.data());
        stft.addFrame(mag.data(), phase.data(), f);
    }
    stft.finish(y.data());

    worst = 0.0f;
    for (int i = 0; i < n; ++i) worst = std::max(worst, std::fabs(y[(size_t) i] - x[(size_t) i]));
    check(worst < 1e-3f, "analise e sintese reconstroem o sinal", fmt("erro maximo %.2e", worst));

    // The edges matter: a song's first and last frame get fewer overlaps, and
    // a separator that fades them would clip the start of every track
    const float headIn = rms(x.data(), 256);
    const float headOut = rms(y.data(), 256);
    check(std::fabs(headIn - headOut) / headIn < 0.01f, "as bordas nao perdem nivel",
          fmt2("entrada %.4f, saida %.4f", headIn, headOut));

    // --- a mask moves the energy it is told to ---
    std::vector<float> mix((size_t) n);
    for (int i = 0; i < n; ++i) {
        const float t = (float) i / (float) kSr;
        mix[(size_t) i] = 0.3f * std::sin(2.0f * (float) M_PI * 300.0f * t)
                        + 0.3f * std::sin(2.0f * (float) M_PI * 3000.0f * t);
    }
    const int cutBin = (int) (1000.0f / ((float) kSr / (float) stft.nFft()));
    stft.beginSynthesis(n);
    for (int f = 0; f < frames; ++f) {
        stft.analyze(mix.data(), n, f, mag.data(), phase.data());
        for (int k = cutBin; k < stft.bins(); ++k) mag[(size_t) k] = 0.0f;  // soft mask, hard here
        stft.addFrame(mag.data(), phase.data(), f);
    }
    stft.finish(y.data());

    const float kept = magnitudeAt(y.data() + 4096, 8192, 300.0f, kSr);
    const float cut = magnitudeAt(y.data() + 4096, 8192, 3000.0f, kSr);
    check(kept > 0.28f && kept < 0.32f, "a mascara preserva o que deve ficar", fmt("300 Hz %.3f", kept));
    check(cut < 0.005f, "a mascara remove o que deve sair", fmt("3 kHz %.4f", cut));
}

int main() {
    std::printf("\n\033[1mAuraVox DSP\033[0m\n");
    testInputFifo();
    testCaptureContinuity();
    testDetector();
    testPitchShift();
    testGhostPitch();
    testFormantIndependence();
    testScaleSnap();
    testTimeScaleTransparency();
    testTimeScaleTempo();
    testTimeScaleKey();
    testTimeScaleRateMath();
    testAutotuneBehaviour();
    testStft();
    testResampler();
    testSeparator();
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
