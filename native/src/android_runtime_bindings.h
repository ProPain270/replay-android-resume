#pragma once

#include "libretro_core.h"

#include <android/native_window.h>

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <deque>
#include <mutex>

namespace retro::runtime {

class AndroidVideoSurface final {
public:
    struct Geometry {
        unsigned width = 0;
        unsigned height = 0;
    };
    struct Performance {
        std::uint64_t video_callbacks = 0;
        std::uint64_t frames_rendered = 0;
        std::uint64_t frames_dropped = 0;
        std::uint64_t duplicate_frames = 0;
        std::int64_t first_posted_at_ns = -1;
    };
    AndroidVideoSurface() = default;
    ~AndroidVideoSurface();

    AndroidVideoSurface(const AndroidVideoSurface&) = delete;
    AndroidVideoSurface& operator=(const AndroidVideoSurface&) = delete;

    void attach(ANativeWindow* window) noexcept;
    void detach() noexcept;
    void render(const VideoFrame& frame) noexcept;
    Geometry geometry() const noexcept;
    Performance performance() const noexcept;

private:
    mutable std::mutex mutex_;
    ANativeWindow* window_ = nullptr;
    unsigned buffer_width_ = 0;
    unsigned buffer_height_ = 0;
    std::uint64_t frame_count_ = 0;
    // Lifetime of the session, not the Surface: attach/detach must not reset it.
    Performance performance_;
};

class AndroidAudioBuffer final {
public:
    static constexpr std::size_t kMaximumFrames = 96u * 1024u;

    void append(const AudioSamples& samples) noexcept;
    std::size_t read(std::int16_t* destination, std::size_t max_frames) noexcept;
    void clear() noexcept;

private:
    std::mutex mutex_;
    std::deque<std::int16_t> samples_;
};

class AndroidInputState final {
public:
    void setMask(std::uint16_t mask) noexcept { mask_.store(mask, std::memory_order_release); }
    void setAnalog(unsigned axis_index, std::int16_t value) noexcept;
    std::int16_t read(unsigned device, unsigned index, unsigned id) const noexcept;

private:
    std::atomic<std::uint16_t> mask_{0};
    std::atomic<std::int16_t> analog_[4]{{0}, {0}, {0}, {0}};
};

}  // namespace retro::runtime
