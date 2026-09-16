#include "AudioEngine.h"

#include <android/log.h>
#include <thread>

#define LOG_TAG "AuraVox"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace av {

bool AudioEngine::start() {
    std::lock_guard<std::mutex> lock(lifecycleLock_);
    if (running_.load(std::memory_order_acquire)) return true;

    if (!openStreams()) {
        closeStreams();
        return false;
    }

    chain_.prepare(sampleRate_);
    player_.prepare(sampleRate_, kMaxBlockFrames);
    mixer_.prepare(sampleRate_);
    metronome_.prepare(sampleRate_);

    micBuf_.assign(kMaxBlockFrames, 0.0f);
    clickBuf_.assign(kMaxBlockFrames, 0.0f);
    voiceBuf_.assign((size_t) kMaxBlockFrames * 2, 0.0f);
    trackBuf_.assign((size_t) kMaxBlockFrames * 2, 0.0f);
    recBuf_.assign((size_t) kMaxBlockFrames * 2, 0.0f);
    drainBuf_.assign((size_t) framesPerBurst_ * 8, 0.0f);

    // Re-publish every parameter so the freshly prepared DSP picks up the
    // values the UI set while the engine was down
    params_.touchRange(0, kParamCount);
    primed_.store(false, std::memory_order_release);
    xruns_.store(0, std::memory_order_relaxed);

    // Input must be started first or the first callbacks find an empty stream
    oboe::Result r = input_->requestStart();
    if (r != oboe::Result::OK) {
        LOGW("input start failed: %s", oboe::convertToText(r));
        closeStreams();
        return false;
    }

    r = output_->requestStart();
    if (r != oboe::Result::OK) {
        LOGW("output start failed: %s", oboe::convertToText(r));
        closeStreams();
        return false;
    }

    running_.store(true, std::memory_order_release);
    updateLatency();
    return true;
}

bool AudioEngine::openStreams() {
    oboe::AudioStreamBuilder inBuilder;
    inBuilder.setDirection(oboe::Direction::Input)
             ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
             ->setSharingMode(oboe::SharingMode::Exclusive)
             ->setFormat(oboe::AudioFormat::Float)
             ->setChannelCount(oboe::ChannelCount::Mono)
             ->setSampleRateConversionQuality(oboe::SampleRateConversionQuality::Medium)
             // VoicePerformance skips AEC, AGC and noise suppression. Those
             // three are what make the stock mic path unusable for singing.
             ->setInputPreset(oboe::InputPreset::VoicePerformance);

    oboe::Result r = inBuilder.openStream(input_);
    if (r != oboe::Result::OK) {
        LOGW("open input failed: %s", oboe::convertToText(r));
        return false;
    }

    sampleRate_ = input_->getSampleRate();
    framesPerBurst_ = input_->getFramesPerBurst();

    oboe::AudioStreamBuilder outBuilder;
    outBuilder.setDirection(oboe::Direction::Output)
              ->setPerformanceMode(oboe::PerformanceMode::LowLatency)
              ->setSharingMode(oboe::SharingMode::Exclusive)
              ->setFormat(oboe::AudioFormat::Float)
              ->setChannelCount(oboe::ChannelCount::Stereo)
              ->setSampleRate(sampleRate_)
              ->setUsage(oboe::Usage::Media)
              ->setContentType(oboe::ContentType::Music)
              ->setDataCallback(this)
              ->setErrorCallback(this);

    r = outBuilder.openStream(output_);
    if (r != oboe::Result::OK) {
        LOGW("open output failed: %s", oboe::convertToText(r));
        return false;
    }

    // Two bursts is the smallest size that survives normal scheduler jitter
    output_->setBufferSizeInFrames(output_->getFramesPerBurst() * 2);

    LOGI("streams open: %d Hz, burst %d, api %s",
         sampleRate_, framesPerBurst_, oboe::convertToText(output_->getAudioApi()));
    return true;
}

void AudioEngine::stop() {
    std::lock_guard<std::mutex> lock(lifecycleLock_);
    if (!running_.load(std::memory_order_acquire)) return;

    running_.store(false, std::memory_order_release);
    wav_.stop();
    closeStreams();
}

void AudioEngine::closeStreams() {
    if (output_) {
        output_->stop();
        output_->close();
        output_.reset();
    }
    if (input_) {
        input_->stop();
        input_->close();
        input_.reset();
    }
}

oboe::DataCallbackResult AudioEngine::onAudioReady(oboe::AudioStream * /*stream*/,
                                                   void *audioData,
                                                   int32_t numFrames) {
    auto *out = static_cast<float *>(audioData);
    const int frames = std::min<int>(numFrames, kMaxBlockFrames);

    // Denormals cost roughly 100x on reverb tails. Set once per callback thread.
    if (!denormalsSet_.exchange(true, std::memory_order_relaxed)) {
        enableFlushDenormals();
    }

    if (!input_ || !running_.load(std::memory_order_relaxed)) {
        std::fill(out, out + numFrames * 2, 0.0f);
        return oboe::DataCallbackResult::Continue;
    }

    // First callback: drain whatever piled up between the two stream starts,
    // otherwise that backlog becomes permanent latency.
    if (!primed_.exchange(true, std::memory_order_relaxed)) {
        int32_t drained;
        do {
            auto res = input_->read(drainBuf_.data(), (int32_t) drainBuf_.size(), 0);
            drained = res ? res.value() : 0;
        } while (drained > 0);
        std::fill(out, out + numFrames * 2, 0.0f);
        return oboe::DataCallbackResult::Continue;
    }

    auto result = input_->read(micBuf_.data(), frames, 0 /* no timeout */);
    const int32_t framesRead = result ? result.value() : 0;
    if (framesRead < frames) {
        // Input underflow. Zero the tail rather than repeating stale samples.
        std::fill(micBuf_.begin() + framesRead, micBuf_.begin() + frames, 0.0f);
        if (framesRead == 0) xruns_.fetch_add(1, std::memory_order_relaxed);
    }

    chain_.process(params_, micBuf_.data(), voiceBuf_.data(), frames);
    player_.render(params_, trackBuf_.data(), frames);

    const bool clicking = metronome_.running();
    if (clicking) {
        for (int i = 0; i < frames; ++i) clickBuf_[i] = metronome_.process();
        if (metronome_.consumeFinished()) player_.play();
    }

    const bool recording = wav_.active();
    mixer_.process(params_, voiceBuf_.data(), trackBuf_.data(),
                   clicking ? clickBuf_.data() : nullptr,
                   out, recording ? recBuf_.data() : nullptr,
                   frames, player_.duckAmount());

    if (recording) wav_.push(recBuf_.data(), frames);

    // The singer reacts to what they heard, not to what is being written now.
    // Scoring the raw playhead marks every take as late by the round trip.
    if (player_.isPlaying()) {
        const float tempo = params_.get(kTrackTempo);
        const double songMs = player_.positionMs() -
                              (double) alignMs_.load(std::memory_order_relaxed) *
                              (tempo > 0.01f ? tempo : 1.0f);
        score_.update(songMs, chain_.pitchMidi(), chain_.confidence(), frames);
    }

    // Latency changes as the buffer size adapts; poll roughly twice a second
    if (++latencyPollCounter_ * frames > sampleRate_ / 2) {
        latencyPollCounter_ = 0;
        updateLatency();
    }

    return oboe::DataCallbackResult::Continue;
}

void AudioEngine::updateLatency() {
    float total = 0.0f;
    if (input_) {
        auto l = input_->calculateLatencyMillis();
        if (l) total += (float) l.value();
    }
    if (output_) {
        auto l = output_->calculateLatencyMillis();
        if (l) total += (float) l.value();
    }
    latencyMs_.store(total, std::memory_order_relaxed);

    // The PSOLA path adds its own delay on top, but only while it is engaged
    const float lookaheadMs = 1000.0f * (float) chain_.lookaheadSamples() / (float) sampleRate_;
    const float trim = params_.get(kLatencyTrimMs);
    const float align = std::max(0.0f, total + lookaheadMs + trim);
    alignMs_.store(align, std::memory_order_relaxed);
    mixer_.setAlignSamples((int) (align * 0.001f * (float) sampleRate_));
}

void AudioEngine::onErrorAfterClose(oboe::AudioStream * /*stream*/, oboe::Result error) {
    LOGW("stream error: %s", oboe::convertToText(error));

    // Disconnect happens when headphones are unplugged. Reopen on another
    // thread; restarting from inside the error callback would deadlock.
    if (error == oboe::Result::ErrorDisconnected) {
        std::thread([this] {
            stop();
            start();
        }).detach();
    }
}

bool AudioEngine::startRecording(const std::string &path) {
    if (!running_.load(std::memory_order_acquire)) return false;
    updateLatency();  // pin the alignment to the latency measured right now
    return wav_.start(path, sampleRate_);
}

void AudioEngine::stopRecording() {
    wav_.stop();
}

void AudioEngine::startCountIn(float bpm, int beats) {
    if (beats <= 0) {
        player_.play();
        return;
    }
    metronome_.start(bpm, beats);
}

}  // namespace av
