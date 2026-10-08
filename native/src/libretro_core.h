#pragma once

#include "libretro_abi.h"
#include "session_host.h"

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

namespace retro::runtime {

enum class LibretroPixelFormat : std::uint8_t {
    k0Rgb1555 = RETRO_PIXEL_FORMAT_0RGB1555,
    kXrgb8888 = RETRO_PIXEL_FORMAT_XRGB8888,
    kRgb565 = RETRO_PIXEL_FORMAT_RGB565,
};

struct VideoFrame {
    unsigned width = 0;
    unsigned height = 0;
    std::size_t pitch = 0;
    LibretroPixelFormat format = LibretroPixelFormat::k0Rgb1555;
    bool duplicate = false;
    bool hardware_frame = false;
    std::vector<std::uint8_t> pixels;
};

struct AudioSamples {
    std::size_t frames = 0;
    std::vector<std::int16_t> interleaved_stereo;
};

using VideoCallback = std::function<void(const VideoFrame&)>;
using AudioCallback = std::function<void(const AudioSamples&)>;
using AudioSampleCallback = std::function<void(std::int16_t left, std::int16_t right)>;
using InputPollCallback = std::function<void()>;
using InputStateCallback = std::function<std::int16_t(unsigned port,
                                                       unsigned device,
                                                       unsigned index,
                                                       unsigned id)>;
using MessageCallback = std::function<void(const std::string& message, unsigned frames)>;
using LogCallback = std::function<void(int level, const std::string& message)>;

// The path values are owned by the session for the complete lifetime of the
// dynamically loaded core. Libretro receives content_path through
// retro_game_info::path; when the core does not require a full path, the host
// also reads the file and supplies retro_game_info::data.
struct LibretroSessionConfig {
    std::string core_path;
    std::string content_path;
    std::string system_directory;
    std::string save_directory;

    VideoCallback video_callback;
    AudioCallback audio_callback;
    AudioSampleCallback audio_sample_callback;
    InputPollCallback input_poll_callback;
    InputStateCallback input_state_callback;
    MessageCallback message_callback;
    LogCallback log_callback;
};

// Opens the caller-selected shared library and resolves the required
// Libretro entry points. The returned operations keep the core alive until the
// owning Session has finished close(). A non-null status receives a stable
// Result value for loader failures.
CoreOperations makeLibretroOperations(const LibretroSessionConfig& config,
                                       Result* status) noexcept;

}  // namespace retro::runtime
