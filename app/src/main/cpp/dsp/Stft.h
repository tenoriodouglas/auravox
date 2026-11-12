#pragma once

#include <cmath>
#include <cstddef>
#include <vector>

namespace av {

/**
 * Iterative radix-2 FFT, in place.
 *
 * Twiddles and the bit-reversal permutation are built once in prepare(), so a
 * transform allocates nothing. Used by the offline separation pass, which runs
 * on a worker thread, not by the realtime chain.
 */
class Fft {
public:
    /** size must be a power of two. */
    void prepare(int size) {
        n_ = size;
        cos_.resize((size_t) n_ / 2);
        sin_.resize((size_t) n_ / 2);
        for (int i = 0; i < n_ / 2; ++i) {
            const double a = 2.0 * M_PI * (double) i / (double) n_;
            cos_[(size_t) i] = (float) std::cos(a);
            sin_[(size_t) i] = (float) std::sin(a);
        }
        int levels = 0;
        while ((1 << levels) < n_) ++levels;
        rev_.resize((size_t) n_);
        for (int i = 0; i < n_; ++i) {
            int r = 0;
            for (int b = 0; b < levels; ++b) r |= ((i >> b) & 1) << (levels - 1 - b);
            rev_[(size_t) i] = r;
        }
    }

    int size() const noexcept { return n_; }

    /** Forward DFT: X[k] = sum x[n] e^(-2pi i kn/N). */
    void forward(float *re, float *im) const noexcept {
        for (int i = 0; i < n_; ++i) {
            const int j = rev_[(size_t) i];
            if (j > i) {
                std::swap(re[i], re[j]);
                std::swap(im[i], im[j]);
            }
        }
        for (int size = 2; size <= n_; size <<= 1) {
            const int half = size / 2;
            const int step = n_ / size;
            for (int i = 0; i < n_; i += size) {
                for (int j = i, k = 0; j < i + half; ++j, k += step) {
                    const int l = j + half;
                    const float c = cos_[(size_t) k];
                    const float s = sin_[(size_t) k];
                    const float tre = re[l] * c + im[l] * s;
                    const float tim = -re[l] * s + im[l] * c;
                    re[l] = re[j] - tre;
                    im[l] = im[j] - tim;
                    re[j] += tre;
                    im[j] += tim;
                }
            }
        }
    }

    /** Inverse DFT, scaled by 1/N so inverse(forward(x)) == x. */
    void inverse(float *re, float *im) const noexcept {
        forward(im, re);  // conjugate trick: swapping the planes inverts the sign
        const float g = 1.0f / (float) n_;
        for (int i = 0; i < n_; ++i) {
            re[i] *= g;
            im[i] *= g;
        }
    }

private:
    int n_ = 0;
    std::vector<float> cos_, sin_;
    std::vector<int> rev_;
};

/**
 * Short-time Fourier transform with weighted overlap-add resynthesis.
 *
 * Separation models take a magnitude spectrogram and return a soft mask. The
 * mask multiplies the magnitude while the original phase is kept, which is
 * what every mask-based separator does and is why the result is usable without
 * a phase reconstruction stage.
 *
 * Synthesis accumulates w[n]*frame[n] into the output and w[n]^2 into a
 * divisor, then divides once in finish(). Doing it that way makes the
 * reconstruction exact for any window and hop that overlap at all, including
 * the partial frames at both ends of the file, instead of relying on a
 * constant-overlap-add constant that only holds in the middle.
 */
class Stft {
public:
    void prepare(int nFft, int hop) {
        nFft_ = nFft;
        hop_ = hop;
        bins_ = nFft / 2 + 1;
        fft_.prepare(nFft);
        window_.resize((size_t) nFft);
        for (int i = 0; i < nFft; ++i) {
            // Periodic Hann: the form that overlap-adds cleanly
            window_[(size_t) i] =
                0.5f - 0.5f * (float) std::cos(2.0 * M_PI * (double) i / (double) nFft);
        }
        re_.resize((size_t) nFft);
        im_.resize((size_t) nFft);
    }

    int bins() const noexcept { return bins_; }
    int hop() const noexcept { return hop_; }
    int nFft() const noexcept { return nFft_; }

    /** Frame count for a signal of `samples`, centred so frame 0 sits at t=0. */
    int frameCount(int samples) const noexcept {
        if (samples <= 0) return 0;
        return samples / hop_ + 1;
    }

    /**
     * One analysis frame centred on `frame * hop`.
     *
     * Reads outside the signal are zero, so the caller does not pad the input.
     */
    void analyze(const float *x, int len, int frame, float *mag, float *phase) noexcept {
        const int centre = frame * hop_;
        const int start = centre - nFft_ / 2;
        for (int i = 0; i < nFft_; ++i) {
            const int src = start + i;
            const float s = (src >= 0 && src < len) ? x[(size_t) src] : 0.0f;
            re_[(size_t) i] = s * window_[(size_t) i];
            im_[(size_t) i] = 0.0f;
        }
        fft_.forward(re_.data(), im_.data());
        for (int k = 0; k < bins_; ++k) {
            const float a = re_[(size_t) k];
            const float b = im_[(size_t) k];
            mag[k] = std::sqrt(a * a + b * b);
            phase[k] = std::atan2(b, a);
        }
    }

    /** Starts an overlap-add pass over a buffer of `len` samples. */
    void beginSynthesis(int len) {
        acc_.assign((size_t) len, 0.0f);
        norm_.assign((size_t) len, 0.0f);
    }

    /** Adds one frame back, from magnitude and the phase analysis gave. */
    void addFrame(const float *mag, const float *phase, int frame) noexcept {
        for (int k = 0; k < bins_; ++k) {
            re_[(size_t) k] = mag[k] * std::cos(phase[k]);
            im_[(size_t) k] = mag[k] * std::sin(phase[k]);
        }
        // Mirror the upper half so the inverse transform is real
        for (int k = bins_; k < nFft_; ++k) {
            const int m = nFft_ - k;
            re_[(size_t) k] = re_[(size_t) m];
            im_[(size_t) k] = -im_[(size_t) m];
        }
        fft_.inverse(re_.data(), im_.data());

        const int start = frame * hop_ - nFft_ / 2;
        const int len = (int) acc_.size();
        for (int i = 0; i < nFft_; ++i) {
            const int dst = start + i;
            if (dst < 0 || dst >= len) continue;
            const float w = window_[(size_t) i];
            acc_[(size_t) dst] += re_[(size_t) i] * w;
            norm_[(size_t) dst] += w * w;
        }
    }

    /** Divides out the accumulated window power and writes the result. */
    void finish(float *out) noexcept {
        const int len = (int) acc_.size();
        for (int i = 0; i < len; ++i) {
            const float w = norm_[(size_t) i];
            out[i] = w > 1e-8f ? acc_[(size_t) i] / w : 0.0f;
        }
    }

private:
    int nFft_ = 0, hop_ = 0, bins_ = 0;
    Fft fft_;
    std::vector<float> window_, re_, im_, acc_, norm_;
};

}  // namespace av
