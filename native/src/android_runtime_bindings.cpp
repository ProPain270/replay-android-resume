#include "android_runtime_bindings.h"

#include <algorithm>
#include <android/log.h>
#include <android/native_window_jni.h>
#include <android/native_window.h>
#include <cstring>

namespace retro::runtime {

AndroidVideoSurface::~AndroidVideoSurface() {
    detach();
}

void AndroidVideoSurface::attach(ANativeWindow* window) noexcept {
    if (window != nullptr) ANativeWindow_acquire(window);
    std::lock_guard lock(mutex_);
    if (window_ != nullptr) ANativeWindow_release(window_);
    window_ = window;
    buffer_width_ = 0;
    buffer_height_ = 0;
    frame_count_ = 0;
    __android_log_print(ANDROID_LOG_INFO, "retro_runtime", "video surface attached: %p", static_cast<void*>(window_));
}

void AndroidVideoSurface::detach() noexcept {
    std::lock_guard lock(mutex_);
    if (window_ != nullptr) {
        ANativeWindow_release(window_);
        window_ = nullptr;
    }
    buffer_width_ = 0;
    buffer_height_ = 0;
}

AndroidVideoSurface::Geometry AndroidVideoSurface::geometry() const noexcept {
    std::lock_guard lock(mutex_);
    return Geometry{buffer_width_, buffer_height_};
}

AndroidVideoSurface::Performance AndroidVideoSurface::performance() const noexcept {
    std::lock_guard lock(mutex_);
    return performance_;
}

void AndroidVideoSurface::render(const VideoFrame& frame) noexcept {
    std::lock_guard lock(mutex_);
    ++performance_.video_callbacks;
    if (frame.duplicate) {
        ++performance_.duplicate_frames;
        return;
    }
    if (frame.hardware_frame || frame.pixels.empty() || frame.width == 0 || frame.height == 0 || window_ == nullptr) {
        ++performance_.frames_dropped;
        return;
    }

    int geometry_result = 0;
    if (buffer_width_ != frame.width || buffer_height_ != frame.height) {
        geometry_result = ANativeWindow_setBuffersGeometry(
            window_, static_cast<int32_t>(frame.width), static_cast<int32_t>(frame.height), WINDOW_FORMAT_RGBA_8888);
        if (geometry_result == 0) {
            buffer_width_ = frame.width;
            buffer_height_ = frame.height;
        }
    }
    if (geometry_result != 0) {
        ++performance_.frames_dropped;
        if (frame_count_ == 0) {
            __android_log_print(ANDROID_LOG_ERROR, "retro_runtime",
                                "video surface geometry failed: code=%d", geometry_result);
        }
        return;
    }
    ANativeWindow_Buffer buffer{};
    const int lock_result = ANativeWindow_lock(window_, &buffer, nullptr);
    if (lock_result != 0) {
        ++performance_.frames_dropped;
        if (frame_count_ == 0) {
            __android_log_print(ANDROID_LOG_ERROR, "retro_runtime",
                                "video surface lock failed: geometry=%d lock=%d bits=%p",
                                geometry_result, lock_result, buffer.bits);
        }
        return;
    }
    if (buffer.bits == nullptr || buffer.stride <= 0 || buffer.height <= 0) {
        ++performance_.frames_dropped;
        (void)ANativeWindow_unlockAndPost(window_);
        if (frame_count_ == 0) {
            __android_log_print(ANDROID_LOG_ERROR, "retro_runtime",
                                "video surface returned invalid buffer: bits=%p stride=%d height=%d",
                                buffer.bits, buffer.stride, buffer.height);
        }
        return;
    }

    const std::size_t source_row_bytes = frame.pitch;
    const std::size_t destination_row_bytes = static_cast<std::size_t>(buffer.stride) * 4u;
    const auto* source = frame.pixels.data();
    auto* destination = static_cast<std::uint8_t*>(buffer.bits);
    const unsigned copy_width = std::min(frame.width, static_cast<unsigned>(buffer.width));
    const unsigned copy_height = std::min(frame.height, static_cast<unsigned>(buffer.height));
    for (unsigned row = 0; row < copy_height; ++row) {
        const auto* source_row = source + static_cast<std::size_t>(row) * source_row_bytes;
        auto* destination_row = destination + static_cast<std::size_t>(row) * destination_row_bytes;
        for (unsigned column = 0; column < copy_width; ++column) {
            auto* output = destination_row + static_cast<std::size_t>(column) * 4u;
            if (frame.format == LibretroPixelFormat::kXrgb8888) {
                // Libretro XRGB8888 is 0x00RRGGBB. On little-endian Android
                // the source bytes are B,G,R,0; the window buffer is RGBA.
                const auto* pixel = source_row + static_cast<std::size_t>(column) * 4u;
                output[0] = pixel[2];
                output[1] = pixel[1];
                output[2] = pixel[0];
            } else {
                // Libretro's 16-bit formats are native-endian packed pixels.
                // Snes9x commonly selects 0RGB1555, while some cores select
                // RGB565; normalize both to the RGBA8888 Android window.
                std::uint16_t packed = 0;
                std::memcpy(&packed, source_row + static_cast<std::size_t>(column) * 2u,
                            sizeof(packed));
                if (frame.format == LibretroPixelFormat::k0Rgb1555) {
                    output[0] = static_cast<std::uint8_t>(((packed >> 10u) & 0x1Fu) * 255u / 31u);
                    output[1] = static_cast<std::uint8_t>(((packed >> 5u) & 0x1Fu) * 255u / 31u);
                    output[2] = static_cast<std::uint8_t>((packed & 0x1Fu) * 255u / 31u);
                } else if (frame.format == LibretroPixelFormat::kRgb565) {
                    output[0] = static_cast<std::uint8_t>(((packed >> 11u) & 0x1Fu) * 255u / 31u);
                    output[1] = static_cast<std::uint8_t>(((packed >> 5u) & 0x3Fu) * 255u / 63u);
                    output[2] = static_cast<std::uint8_t>((packed & 0x1Fu) * 255u / 31u);
                } else {
                    output[0] = 0;
                    output[1] = 0;
                    output[2] = 0;
                }
            }
            output[3] = 0xFFu;
        }
    }
    const int post_result = ANativeWindow_unlockAndPost(window_);
    if (post_result == 0) {
        ++performance_.frames_rendered;
        if (performance_.first_posted_at_ns < 0) performance_.first_posted_at_ns = monotonicNanos();
    } else {
        ++performance_.frames_dropped;
    }
    ++frame_count_;
    if (frame_count_ == 1) {
        __android_log_print(ANDROID_LOG_INFO, "retro_runtime",
                            "first video frame posted: %ux%u pitch=%zu post=%d",
                            frame.width, frame.height, frame.pitch, post_result);
    }
}

void AndroidAudioBuffer::append(const AudioSamples& samples) noexcept {
    if (samples.frames == 0 || samples.interleaved_stereo.size() < samples.frames * 2u) return;
    std::lock_guard lock(mutex_);
    const std::size_t maximum_samples = kMaximumFrames * 2u;
    const std::size_t incoming = samples.frames * 2u;
    if (incoming >= maximum_samples) {
        samples_.clear();
        samples_.insert(samples_.end(), samples.interleaved_stereo.end() - static_cast<std::ptrdiff_t>(maximum_samples),
                        samples.interleaved_stereo.end());
        return;
    }
    while (samples_.size() + incoming > maximum_samples) {
        samples_.pop_front();
        if (!samples_.empty()) samples_.pop_front();
    }
    samples_.insert(samples_.end(), samples.interleaved_stereo.begin(),
                    samples.interleaved_stereo.begin() + static_cast<std::ptrdiff_t>(incoming));
}

std::size_t AndroidAudioBuffer::read(std::int16_t* destination, const std::size_t max_frames) noexcept {
    if (destination == nullptr || max_frames == 0) return 0;
    std::lock_guard lock(mutex_);
    const std::size_t frames = std::min(max_frames, samples_.size() / 2u);
    for (std::size_t index = 0; index < frames * 2u; ++index) {
        destination[index] = samples_.front();
        samples_.pop_front();
    }
    return frames;
}

void AndroidAudioBuffer::clear() noexcept {
    std::lock_guard lock(mutex_);
    samples_.clear();
}

void AndroidInputState::setAnalog(const unsigned axis_index, const std::int16_t value) noexcept {
    if (axis_index < 4u) analog_[axis_index].store(value, std::memory_order_release);
}

std::int16_t AndroidInputState::read(const unsigned device, const unsigned index, const unsigned id) const noexcept {
    if (device == RETRO_DEVICE_ANALOG && index < 2u && id < 2u) {
        return analog_[index * 2u + id].load(std::memory_order_acquire);
    }
    if (device != RETRO_DEVICE_JOYPAD) return 0;
    if (id > 15u) return 0;
    return (mask_.load(std::memory_order_acquire) & (static_cast<std::uint16_t>(1u) << id)) != 0 ? 1 : 0;
}

}  // namespace retro::runtime
