#pragma once

#include <algorithm>
#include <atomic>
#include <cmath>
#include <vector>

namespace av {

/**
 * Karaoke scoring against a reference melody.
 *
 * The audio thread feeds it the detected pitch and the song position the
 * singer was actually reacting to — position minus round-trip latency, which
 * is the part every phone karaoke app gets wrong. Without that correction a
 * singer who is dead on time scores as consistently late.
 *
 * The melody is double buffered: the loader writes the idle slot and publishes
 * an index, so a song can be swapped in without stopping the audio thread.
 */
class ScoreTracker {
public:
    struct Note { float startMs, endMs, midi; };

    /** UI thread. Triples of (startMs, durationMs, midi). */
    void setMelody(const float *triples, int noteCount) {
        const int idx = 1 - active_.load(std::memory_order_relaxed);
        auto &dst = melody_[idx];
        dst.clear();
        dst.reserve((size_t) std::max(noteCount, 0));
        for (int i = 0; i < noteCount; ++i) {
            Note n;
            n.startMs = triples[i * 3];
            n.endMs = triples[i * 3] + triples[i * 3 + 1];
            n.midi = triples[i * 3 + 2];
            dst.push_back(n);
        }
        active_.store(idx, std::memory_order_release);
        reset();
    }

    void reset() {
        cursor_ = 0;
        framesInNote_ = 0;
        creditInNote_ = 0.0f;
        scored_ = 0.0f;
        possible_ = 0.0f;
        combo_.store(0, std::memory_order_relaxed);
        maxCombo_.store(0, std::memory_order_relaxed);
        perfect_.store(0, std::memory_order_relaxed);
        great_.store(0, std::memory_order_relaxed);
        good_.store(0, std::memory_order_relaxed);
        miss_.store(0, std::memory_order_relaxed);
        score_.store(0.0f, std::memory_order_relaxed);
        liveAccuracy_.store(0.0f, std::memory_order_relaxed);
        targetMidi_.store(-1.0f, std::memory_order_relaxed);
        lastGrade_.store(-1, std::memory_order_relaxed);
    }

    bool enabled() const { return enabled_.load(std::memory_order_relaxed); }
    void setEnabled(bool v) { enabled_.store(v, std::memory_order_relaxed); }

    /**
     * Audio thread. One call per block.
     * songMs is already latency corrected; midi is negative when unvoiced.
     */
    void update(double songMs, float midi, float confidence, int blockFrames) noexcept {
        if (!enabled_.load(std::memory_order_relaxed)) return;
        const auto &notes = melody_[active_.load(std::memory_order_acquire)];
        if (notes.empty()) return;

        // The playhead only moves forward, so the cursor walks instead of searching
        while (cursor_ < notes.size() && songMs >= (double) notes[cursor_].endMs) {
            closeNote(notes[cursor_]);
            ++cursor_;
        }
        if (cursor_ >= notes.size()) {
            targetMidi_.store(-1.0f, std::memory_order_relaxed);
            return;
        }

        const Note &n = notes[cursor_];
        if (songMs < (double) n.startMs) {
            targetMidi_.store(-1.0f, std::memory_order_relaxed);
            liveAccuracy_.store(0.0f, std::memory_order_relaxed);
            return;
        }

        targetMidi_.store(n.midi, std::memory_order_relaxed);
        framesInNote_ += blockFrames;
        const float credit = (midi > 0.0f && confidence > 0.4f)
                             ? creditFor(midi, n.midi) : 0.0f;
        creditInNote_ += credit * (float) blockFrames;

        liveAccuracy_.store(framesInNote_ > 0 ? creditInNote_ / (float) framesInNote_ : 0.0f,
                            std::memory_order_relaxed);
    }

    float score01() const { return score_.load(std::memory_order_relaxed); }
    int combo() const { return combo_.load(std::memory_order_relaxed); }
    int maxCombo() const { return maxCombo_.load(std::memory_order_relaxed); }
    int perfectCount() const { return perfect_.load(std::memory_order_relaxed); }
    int greatCount() const { return great_.load(std::memory_order_relaxed); }
    int goodCount() const { return good_.load(std::memory_order_relaxed); }
    int missCount() const { return miss_.load(std::memory_order_relaxed); }
    float targetMidi() const { return targetMidi_.load(std::memory_order_relaxed); }
    float liveAccuracy() const { return liveAccuracy_.load(std::memory_order_relaxed); }
    /** Grade of the note that just closed: 3 perfect, 2 great, 1 good, 0 miss. */
    int lastGrade() const { return lastGrade_.load(std::memory_order_relaxed); }
    int noteCount() const { return (int) melody_[active_.load(std::memory_order_acquire)].size(); }

private:
    /**
     * Full credit inside 50 cents, tapering to zero at a semitone. An octave
     * error still earns something: the singer found the note, just not the
     * register, and scoring it as a total miss reads as a bug to the user.
     */
    static float creditFor(float sung, float target) noexcept {
        const float d = std::fabs(sung - target);
        if (d <= 0.5f) return 1.0f;
        if (d <= 1.0f) return 1.0f - (d - 0.5f) * 2.0f;

        const float octaves = std::fabs(sung - target) / 12.0f;
        const float folded = std::fabs(octaves - std::round(octaves)) * 12.0f;
        if (folded <= 0.5f) return 0.35f;
        return 0.0f;
    }

    void closeNote(const Note &n) noexcept {
        if (framesInNote_ <= 0) {
            // The note went by while the app was not listening; do not punish it
            framesInNote_ = 0;
            creditInNote_ = 0.0f;
            return;
        }
        const float accuracy = creditInNote_ / (float) framesInNote_;
        const float weight = std::fmax(n.endMs - n.startMs, 1.0f);

        int grade;
        if (accuracy >= 0.9f) grade = 3;
        else if (accuracy >= 0.7f) grade = 2;
        else if (accuracy >= 0.45f) grade = 1;
        else grade = 0;

        switch (grade) {
            case 3: perfect_.fetch_add(1, std::memory_order_relaxed); break;
            case 2: great_.fetch_add(1, std::memory_order_relaxed); break;
            case 1: good_.fetch_add(1, std::memory_order_relaxed); break;
            default: miss_.fetch_add(1, std::memory_order_relaxed); break;
        }
        lastGrade_.store(grade, std::memory_order_relaxed);

        if (grade == 0) {
            combo_.store(0, std::memory_order_relaxed);
        } else {
            const int c = combo_.load(std::memory_order_relaxed) + 1;
            combo_.store(c, std::memory_order_relaxed);
            if (c > maxCombo_.load(std::memory_order_relaxed)) {
                maxCombo_.store(c, std::memory_order_relaxed);
            }
        }

        // Longer notes weigh more: holding a phrase is harder than clipping it
        scored_ += accuracy * weight;
        possible_ += weight;
        score_.store(possible_ > 0.0f ? scored_ / possible_ : 0.0f,
                     std::memory_order_relaxed);

        framesInNote_ = 0;
        creditInNote_ = 0.0f;
    }

    std::vector<Note> melody_[2];
    std::atomic<int> active_{0};
    std::atomic<bool> enabled_{true};

    size_t cursor_ = 0;
    int framesInNote_ = 0;
    float creditInNote_ = 0.0f;
    float scored_ = 0.0f, possible_ = 0.0f;

    std::atomic<float> score_{0.0f};
    std::atomic<float> liveAccuracy_{0.0f};
    std::atomic<float> targetMidi_{-1.0f};
    std::atomic<int> combo_{0}, maxCombo_{0};
    std::atomic<int> perfect_{0}, great_{0}, good_{0}, miss_{0};
    std::atomic<int> lastGrade_{-1};
};

}  // namespace av
