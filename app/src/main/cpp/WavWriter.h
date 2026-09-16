#pragma once

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <thread>
#include <vector>

namespace av {

/**
 * Writes the performance to a 16-bit stereo WAV.
 *
 * The audio thread only copies floats into a ring and publishes a write index.
 * A separate thread does the file I/O, so a slow write can never stall the
 * callback. Single producer, single consumer, no locks.
 */
class WavWriter {
public:
    ~WavWriter() { stop(); }

    bool start(const std::string &path, int sampleRate) {
        stop();

        file_ = std::fopen(path.c_str(), "wb");
        if (!file_) return false;

        sampleRate_ = sampleRate;
        writeHeader(0);

        ring_.assign(kRingSize, 0.0f);
        writeIdx_.store(0, std::memory_order_relaxed);
        readIdx_.store(0, std::memory_order_relaxed);
        frames_ = 0;
        overruns_.store(0, std::memory_order_relaxed);

        running_.store(true, std::memory_order_release);
        worker_ = std::thread(&WavWriter::drainLoop, this);
        return true;
    }

    void stop() {
        if (!running_.load(std::memory_order_acquire)) return;
        running_.store(false, std::memory_order_release);
        if (worker_.joinable()) worker_.join();

        if (file_) {
            std::fflush(file_);
            patchHeader();
            std::fclose(file_);
            file_ = nullptr;
        }
    }

    bool active() const { return running_.load(std::memory_order_acquire); }
    int overruns() const { return overruns_.load(std::memory_order_relaxed); }
    int64_t framesWritten() const { return frames_; }

    /** Audio thread side. Interleaved stereo. Copies only, never blocks. */
    inline void push(const float *interleaved, int frames) noexcept {
        if (!running_.load(std::memory_order_relaxed)) return;

        const int64_t samples = (int64_t) frames * 2;
        const int64_t w = writeIdx_.load(std::memory_order_relaxed);
        const int64_t r = readIdx_.load(std::memory_order_acquire);

        if (w - r + samples > (int64_t) kRingSize) {
            // Consumer fell behind. Dropping is better than blocking the callback.
            overruns_.fetch_add(1, std::memory_order_relaxed);
            return;
        }

        for (int64_t i = 0; i < samples; ++i) {
            ring_[(size_t) ((w + i) & kMask)] = interleaved[i];
        }
        writeIdx_.store(w + samples, std::memory_order_release);
    }

private:
    static constexpr size_t kRingSize = 1u << 19;  // ~5.5 s of stereo at 48 kHz
    static constexpr int64_t kMask = (int64_t) kRingSize - 1;

    void drainLoop() {
        std::vector<int16_t> out(8192);

        while (true) {
            const int64_t w = writeIdx_.load(std::memory_order_acquire);
            const int64_t r = readIdx_.load(std::memory_order_relaxed);
            const int64_t avail = w - r;

            if (avail <= 0) {
                if (!running_.load(std::memory_order_acquire)) break;
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
                continue;
            }

            const int n = (int) std::min<int64_t>(avail, (int64_t) out.size());
            for (int i = 0; i < n; ++i) {
                float v = ring_[(size_t) ((r + i) & kMask)];
                if (v > 1.0f) v = 1.0f;
                else if (v < -1.0f) v = -1.0f;
                out[(size_t) i] = (int16_t) (v * 32767.0f);
            }
            readIdx_.store(r + n, std::memory_order_release);

            std::fwrite(out.data(), sizeof(int16_t), (size_t) n, file_);
            frames_ += n / 2;
        }
    }

    void writeHeader(int dataBytes) {
        auto u32 = [&](uint32_t v) { std::fwrite(&v, 4, 1, file_); };
        auto u16 = [&](uint16_t v) { std::fwrite(&v, 2, 1, file_); };

        std::fwrite("RIFF", 1, 4, file_);
        u32((uint32_t) (36 + dataBytes));
        std::fwrite("WAVE", 1, 4, file_);
        std::fwrite("fmt ", 1, 4, file_);
        u32(16);
        u16(1);                                    // PCM
        u16(2);                                    // stereo
        u32((uint32_t) sampleRate_);
        u32((uint32_t) (sampleRate_ * 4));         // byte rate
        u16(4);                                    // block align
        u16(16);                                   // bits per sample
        std::fwrite("data", 1, 4, file_);
        u32((uint32_t) dataBytes);
    }

    /** Sizes are unknown until the take ends, so the header is patched on close. */
    void patchHeader() {
        const uint32_t dataBytes = (uint32_t) (frames_ * 4);
        const uint32_t riffSize = 36 + dataBytes;
        std::fseek(file_, 4, SEEK_SET);
        std::fwrite(&riffSize, 4, 1, file_);
        std::fseek(file_, 40, SEEK_SET);
        std::fwrite(&dataBytes, 4, 1, file_);
    }

    std::FILE *file_ = nullptr;
    int sampleRate_ = 48000;
    int64_t frames_ = 0;

    std::vector<float> ring_;
    std::atomic<int64_t> writeIdx_{0};
    std::atomic<int64_t> readIdx_{0};
    std::atomic<bool> running_{false};
    std::atomic<int> overruns_{0};
    std::thread worker_;
};

}  // namespace av
