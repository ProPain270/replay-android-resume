#include "libretro_core.h"

#include <cstdarg>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <filesystem>
#include <fstream>
#include <limits>
#include <memory>
#include <string_view>
#include <thread>
#include <utility>

namespace retro::runtime {

namespace {

constexpr std::size_t kMaximumInMemoryContent = 512u * 1024u * 1024u;
constexpr std::size_t kMaximumVideoBytes = 64u * 1024u * 1024u;
constexpr std::size_t kMaximumSaveStateBytes = 64u * 1024u * 1024u;

struct CoreSymbols {
    retro_api_version_t api_version = nullptr;
    retro_set_environment_t set_environment = nullptr;
    retro_set_video_refresh_t set_video_refresh = nullptr;
    retro_set_audio_sample_t set_audio_sample = nullptr;
    retro_set_audio_sample_batch_t set_audio_sample_batch = nullptr;
    retro_set_input_poll_t set_input_poll = nullptr;
    retro_set_input_state_t set_input_state = nullptr;
    retro_init_t init = nullptr;
    retro_deinit_t deinit = nullptr;
    retro_get_system_info_t get_system_info = nullptr;
    retro_get_system_av_info_t get_system_av_info = nullptr;
    retro_load_game_t load_game = nullptr;
    retro_unload_game_t unload_game = nullptr;
    retro_run_t run = nullptr;
    retro_get_memory_data_t get_memory_data = nullptr;
    retro_get_memory_size_t get_memory_size = nullptr;
    retro_serialize_size_t serialize_size = nullptr;
    retro_serialize_t serialize = nullptr;
    retro_unserialize_t unserialize = nullptr;
};

template <typename Function>
Function resolve(void* library, const char* name) noexcept {
    return reinterpret_cast<Function>(dlsym(library, name));
}

class LibretroCore;
thread_local LibretroCore* active_core = nullptr;

class ActiveCoreScope final {
public:
    explicit ActiveCoreScope(LibretroCore* core) noexcept : previous_(active_core) {
        active_core = core;
    }

    ~ActiveCoreScope() { active_core = previous_; }

    ActiveCoreScope(const ActiveCoreScope&) = delete;
    ActiveCoreScope& operator=(const ActiveCoreScope&) = delete;

private:
    LibretroCore* previous_;
};

class LibretroCore final : public std::enable_shared_from_this<LibretroCore> {
public:
    explicit LibretroCore(const LibretroSessionConfig& config)
        : content_path_(config.content_path),
          system_directory_(config.system_directory),
          save_directory_(config.save_directory),
          video_callback_(config.video_callback),
          audio_callback_(config.audio_callback),
          audio_sample_callback_(config.audio_sample_callback),
          input_poll_callback_(config.input_poll_callback),
          input_state_callback_(config.input_state_callback),
          message_callback_(config.message_callback),
          log_callback_(config.log_callback) {}

    ~LibretroCore() {
        if (library_ != nullptr) {
            dlclose(library_);
            library_ = nullptr;
        }
    }

    LibretroCore(const LibretroCore&) = delete;
    LibretroCore& operator=(const LibretroCore&) = delete;

    Result open(const std::string& path) noexcept {
        if (path.empty()) {
            return Result::kInvalidArgument;
        }

        library_ = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (library_ == nullptr) {
            const char* loader_error = dlerror();
            try {
                if (log_callback_) {
                    log_callback_(3, std::string("dlopen failed for ") + path + ": " +
                        (loader_error == nullptr ? "unknown loader error" : loader_error));
                }
            } catch (...) {
            }
            return Result::kCoreLoadFailed;
        }

        bool complete = true;
        complete = resolveRequired(symbols_.api_version, "retro_api_version") && complete;
        complete = resolveRequired(symbols_.set_environment, "retro_set_environment") && complete;
        complete = resolveRequired(symbols_.set_video_refresh, "retro_set_video_refresh") && complete;
        complete = resolveRequired(symbols_.set_audio_sample, "retro_set_audio_sample") && complete;
        complete = resolveRequired(symbols_.set_audio_sample_batch, "retro_set_audio_sample_batch") && complete;
        complete = resolveRequired(symbols_.set_input_poll, "retro_set_input_poll") && complete;
        complete = resolveRequired(symbols_.set_input_state, "retro_set_input_state") && complete;
        complete = resolveRequired(symbols_.init, "retro_init") && complete;
        complete = resolveRequired(symbols_.deinit, "retro_deinit") && complete;
        complete = resolveRequired(symbols_.get_system_info, "retro_get_system_info") && complete;
        complete = resolveRequired(symbols_.get_system_av_info, "retro_get_system_av_info") && complete;
        complete = resolveRequired(symbols_.load_game, "retro_load_game") && complete;
        complete = resolveRequired(symbols_.unload_game, "retro_unload_game") && complete;
        complete = resolveRequired(symbols_.run, "retro_run") && complete;
        if (!complete) {
            try {
                if (log_callback_) {
                    log_callback_(3, std::string("core missing one or more required Libretro symbols: ") + path);
                }
            } catch (...) {
            }
            dlclose(library_);
            library_ = nullptr;
            return Result::kCoreMissingSymbol;
        }
        // Persistence entry points are optional in the Libretro ABI. A core
        // can be playable while exposing neither battery RAM nor save states.
        symbols_.get_memory_data = resolve<retro_get_memory_data_t>(library_, "retro_get_memory_data");
        symbols_.get_memory_size = resolve<retro_get_memory_size_t>(library_, "retro_get_memory_size");
        symbols_.serialize_size = resolve<retro_serialize_size_t>(library_, "retro_serialize_size");
        symbols_.serialize = resolve<retro_serialize_t>(library_, "retro_serialize");
        symbols_.unserialize = resolve<retro_unserialize_t>(library_, "retro_unserialize");
        return Result::kOk;
    }

    Result load() noexcept {
        if (library_ == nullptr) {
            return Result::kCoreLoadFailed;
        }
        if (initialized_ || game_loaded_) {
            return Result::kInvalidState;
        }
        if (content_path_.empty()) {
            return Result::kInvalidArgument;
        }
        owner_thread_ = std::this_thread::get_id();
        ActiveCoreScope active(this);

        try {
            if (symbols_.api_version() != RETRO_API_VERSION) {
                return Result::kCoreApiMismatch;
            }

            installCallbacks();

            retro_system_info system_info{};
            symbols_.get_system_info(&system_info);
            const Result content_result = prepareContent(system_info.need_fullpath);
            if (content_result != Result::kOk) {
                return content_result;
            }

            symbols_.init();
            initialized_ = true;

            retro_game_info game{};
            game.path = content_path_.c_str();
            if (!system_info.need_fullpath) {
                game.data = content_data_.empty() ? nullptr : content_data_.data();
                game.size = content_data_.size();
            }

            if (!symbols_.load_game(&game)) {
                cleanupAfterFailedLoad();
                return Result::kContentLoadFailed;
            }
            game_loaded_ = true;
            // Libretro timing is content-dependent and must be queried after
            // retro_load_game succeeds, not immediately after retro_init.
            retro_system_av_info av_info{};
            symbols_.get_system_av_info(&av_info);
            if (std::isfinite(av_info.timing.sample_rate) &&
                av_info.timing.sample_rate >= 8000.0 &&
                av_info.timing.sample_rate <= 192000.0) {
                audio_sample_rate_ = av_info.timing.sample_rate;
            }
            if (std::isfinite(av_info.timing.fps) && av_info.timing.fps >= 1.0 && av_info.timing.fps <= 1000.0) {
                frames_per_second_ = av_info.timing.fps;
            }
            return Result::kOk;
        } catch (...) {
            cleanupAfterFailedLoad();
            return Result::kCoreOperationFailed;
        }
    }

    Result unload() noexcept {
        if (!isOwnerThread()) {
            return Result::kInvalidState;
        }
        ActiveCoreScope active(this);
        Result result = Result::kOk;
        if (game_loaded_) {
            try {
                symbols_.unload_game();
            } catch (...) {
                result = Result::kCoreOperationFailed;
            }
            game_loaded_ = false;
        }
        if (initialized_) {
            try {
                symbols_.deinit();
            } catch (...) {
                result = Result::kCoreOperationFailed;
            }
            initialized_ = false;
        }
        return result;
    }

    Result runFrame() noexcept {
        if (!isOwnerThread() || !initialized_ || !game_loaded_) {
            return Result::kInvalidState;
        }
        ActiveCoreScope active(this);
        try {
            symbols_.run();
            return Result::kOk;
        } catch (...) {
            return Result::kCoreOperationFailed;
        }
    }

    double audioSampleRate() const noexcept { return audio_sample_rate_; }
    double framesPerSecond() const noexcept { return frames_per_second_; }

    Result saveNative(const std::string& path) noexcept {
        if (!isOwnerThread() || !initialized_ || !game_loaded_) return Result::kInvalidState;
        if (symbols_.get_memory_data == nullptr || symbols_.get_memory_size == nullptr) {
            return Result::kUnsupported;
        }
        ActiveCoreScope active(this);
        try {
            const std::size_t size = symbols_.get_memory_size(RETRO_MEMORY_SAVE_RAM);
            if (size == 0) return Result::kUnsupported;
            const void* data = symbols_.get_memory_data(RETRO_MEMORY_SAVE_RAM);
            if (data == nullptr) return Result::kCoreOperationFailed;
            return writePayload(path, data, size);
        } catch (...) {
            return Result::kCoreOperationFailed;
        }
    }

    Result loadNative(const std::string& path) noexcept {
        if (!isOwnerThread() || !initialized_ || !game_loaded_) return Result::kInvalidState;
        if (symbols_.get_memory_data == nullptr || symbols_.get_memory_size == nullptr) {
            return Result::kUnsupported;
        }
        if (path.empty()) return Result::kInvalidArgument;
        ActiveCoreScope active(this);
        try {
            const std::size_t expected_size = symbols_.get_memory_size(RETRO_MEMORY_SAVE_RAM);
            if (expected_size == 0) return Result::kUnsupported;
            void* destination = symbols_.get_memory_data(RETRO_MEMORY_SAVE_RAM);
            if (destination == nullptr) return Result::kCoreOperationFailed;
            std::ifstream input(path, std::ios::binary | std::ios::ate);
            if (!input) return Result::kCoreOperationFailed;
            const std::streamoff end = input.tellg();
            if (end <= 0 || static_cast<std::uintmax_t>(end) != expected_size ||
                static_cast<std::uintmax_t>(end) > kMaximumSaveStateBytes) {
                return Result::kCoreOperationFailed;
            }
            input.seekg(0, std::ios::beg);
            input.read(static_cast<char*>(destination), static_cast<std::streamsize>(expected_size));
            return input && input.gcount() == static_cast<std::streamsize>(expected_size)
                ? Result::kOk
                : Result::kCoreOperationFailed;
        } catch (...) {
            return Result::kCoreOperationFailed;
        }
    }

    Result saveState(const std::string& path) noexcept {
        if (!isOwnerThread() || !initialized_ || !game_loaded_) return Result::kInvalidState;
        if (symbols_.serialize_size == nullptr || symbols_.serialize == nullptr) {
            return Result::kUnsupported;
        }
        ActiveCoreScope active(this);
        try {
            const std::size_t size = symbols_.serialize_size();
            if (size == 0 || size > kMaximumSaveStateBytes) return Result::kUnsupported;
            std::vector<std::uint8_t> data(size);
            if (!symbols_.serialize(data.data(), data.size())) return Result::kCoreOperationFailed;
            return writePayload(path, data.data(), data.size());
        } catch (...) {
            return Result::kCoreOperationFailed;
        }
    }

    Result loadState(const std::string& path) noexcept {
        if (!isOwnerThread() || !initialized_ || !game_loaded_) return Result::kInvalidState;
        if (symbols_.unserialize == nullptr) return Result::kUnsupported;
        if (path.empty()) return Result::kInvalidArgument;
        ActiveCoreScope active(this);
        try {
            std::ifstream input(path, std::ios::binary | std::ios::ate);
            if (!input) return Result::kCoreOperationFailed;
            const std::streamoff end = input.tellg();
            if (end <= 0 || static_cast<std::uintmax_t>(end) > kMaximumSaveStateBytes) {
                return Result::kCoreOperationFailed;
            }
            std::vector<std::uint8_t> data(static_cast<std::size_t>(end));
            input.seekg(0, std::ios::beg);
            input.read(reinterpret_cast<char*>(data.data()), static_cast<std::streamsize>(data.size()));
            if (!input || input.gcount() != static_cast<std::streamsize>(data.size())) {
                return Result::kCoreOperationFailed;
            }
            return symbols_.unserialize(data.data(), data.size())
                ? Result::kOk
                : Result::kCoreOperationFailed;
        } catch (...) {
            return Result::kCoreOperationFailed;
        }
    }

private:
    Result writePayload(const std::string& path, const void* data, const std::size_t size) noexcept {
        if (path.empty() || data == nullptr || size == 0) return Result::kInvalidArgument;
        const std::filesystem::path target(path);
        const std::filesystem::path temporary = target.string() + ".part";
        try {
            if (!target.parent_path().empty()) {
                std::filesystem::create_directories(target.parent_path());
            }
            {
                std::ofstream output(temporary, std::ios::binary | std::ios::trunc);
                if (!output) return Result::kCoreOperationFailed;
                output.write(static_cast<const char*>(data), static_cast<std::streamsize>(size));
                output.flush();
                if (!output) return Result::kCoreOperationFailed;
            }
            std::error_code error;
            std::filesystem::rename(temporary, target, error);
            if (error) {
                std::filesystem::remove(target, error);
                error.clear();
                std::filesystem::rename(temporary, target, error);
            }
            if (error) return Result::kCoreOperationFailed;
            return Result::kOk;
        } catch (...) {
            std::error_code ignored;
            std::filesystem::remove(temporary, ignored);
            return Result::kCoreOperationFailed;
        }
    }

    template <typename Function>
    bool resolveRequired(Function& destination, const char* name) noexcept {
        destination = resolve<Function>(library_, name);
        return destination != nullptr;
    }

    bool isOwnerThread() const noexcept {
        return owner_thread_ != std::thread::id{} && owner_thread_ == std::this_thread::get_id();
    }

    Result prepareContent(const bool need_fullpath) noexcept {
        if (content_path_.empty()) {
            return Result::kInvalidArgument;
        }

        std::ifstream input(content_path_, std::ios::binary);
        if (!input) {
            return Result::kContentLoadFailed;
        }
        if (need_fullpath) {
            content_data_.clear();
            return Result::kOk;
        }

        input.seekg(0, std::ios::end);
        const std::streamoff end = input.tellg();
        if (end < 0 || static_cast<std::uintmax_t>(end) > kMaximumInMemoryContent) {
            return Result::kContentLoadFailed;
        }
        input.seekg(0, std::ios::beg);
        content_data_.resize(static_cast<std::size_t>(end));
        if (!content_data_.empty()) {
            input.read(reinterpret_cast<char*>(content_data_.data()),
                       static_cast<std::streamsize>(content_data_.size()));
            if (!input || input.gcount() != static_cast<std::streamsize>(content_data_.size())) {
                content_data_.clear();
                return Result::kContentLoadFailed;
            }
        }
        return Result::kOk;
    }

    void installCallbacks() noexcept {
        symbols_.set_environment(&LibretroCore::environmentThunk);
        symbols_.set_video_refresh(&LibretroCore::videoRefreshThunk);
        symbols_.set_audio_sample(&LibretroCore::audioSampleThunk);
        symbols_.set_audio_sample_batch(&LibretroCore::audioBatchThunk);
        symbols_.set_input_poll(&LibretroCore::inputPollThunk);
        symbols_.set_input_state(&LibretroCore::inputStateThunk);
    }

    void cleanupAfterFailedLoad() noexcept {
        if (game_loaded_) {
            try {
                symbols_.unload_game();
            } catch (...) {
            }
            game_loaded_ = false;
        }
        if (initialized_) {
            try {
                symbols_.deinit();
            } catch (...) {
            }
            initialized_ = false;
        }
    }

    bool environment(unsigned command, void* data) noexcept {
        switch (command) {
            case RETRO_ENVIRONMENT_GET_CAN_DUPE:
                if (data == nullptr) return false;
                *static_cast<bool*>(data) = true;
                return true;
            case RETRO_ENVIRONMENT_SET_MESSAGE: {
                if (data == nullptr) return false;
                const auto* message = static_cast<const retro_message*>(data);
                const std::string text = message->msg == nullptr ? std::string{} : message->msg;
                try {
                    if (message_callback_) message_callback_(text, message->frames);
                } catch (...) {
                }
                return true;
            }
            case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
                return provideDirectory(data, system_directory_);
            case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
                if (data == nullptr) return false;
                return setPixelFormat(*static_cast<const retro_pixel_format*>(data));
            case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
                return data != nullptr;
            case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
                if (data == nullptr) return false;
                static_cast<retro_log_callback*>(data)->log = &LibretroCore::logThunk;
                return true;
            case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
                return provideDirectory(data, save_directory_);
            case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
                if (data == nullptr) return false;
                *static_cast<unsigned*>(data) = 0;
                return true;
            default:
                return false;
        }
    }

    bool provideDirectory(void* data, const std::string& directory) noexcept {
        if (data == nullptr) return false;
        *static_cast<const char**>(data) = directory.empty() ? nullptr : directory.c_str();
        return true;
    }

    bool setPixelFormat(const retro_pixel_format format) noexcept {
        switch (format) {
            case RETRO_PIXEL_FORMAT_0RGB1555:
            case RETRO_PIXEL_FORMAT_XRGB8888:
            case RETRO_PIXEL_FORMAT_RGB565:
                pixel_format_ = static_cast<LibretroPixelFormat>(format);
                return true;
            default:
                return false;
        }
    }

    void videoRefresh(const void* data, unsigned width, unsigned height, std::size_t pitch) noexcept {
        VideoFrame frame;
        frame.width = width;
        frame.height = height;
        frame.pitch = pitch;
        frame.format = pixel_format_;
        frame.duplicate = data == nullptr;
        frame.hardware_frame = reinterpret_cast<uintptr_t>(data) == RETRO_HW_FRAME_BUFFER_VALID;

        if (data != nullptr && !frame.hardware_frame) {
            const std::size_t bytes_per_pixel = pixel_format_ == LibretroPixelFormat::kXrgb8888 ? 4u : 2u;
            if (width == 0 || height == 0 || pitch == 0 ||
                static_cast<std::size_t>(width) > std::numeric_limits<std::size_t>::max() / bytes_per_pixel ||
                pitch < static_cast<std::size_t>(width) * bytes_per_pixel || pitch > kMaximumVideoBytes ||
                static_cast<std::size_t>(height) > kMaximumVideoBytes / pitch) {
                return;
            }
            const std::size_t bytes = pitch * static_cast<std::size_t>(height);
            try {
                frame.pixels.resize(bytes);
                for (unsigned row = 0; row < height; ++row) {
                    std::memcpy(frame.pixels.data() + static_cast<std::size_t>(row) * pitch,
                                static_cast<const std::uint8_t*>(data) + static_cast<std::size_t>(row) * pitch,
                                pitch);
                }
            } catch (...) {
                return;
            }
        }

        try {
            if (video_callback_) video_callback_(frame);
        } catch (...) {
        }
    }

    void audioSample(const std::int16_t left, const std::int16_t right) noexcept {
        try {
            if (audio_sample_callback_) {
                audio_sample_callback_(left, right);
                return;
            }
            if (audio_callback_) {
                AudioSamples samples;
                samples.frames = 1;
                samples.interleaved_stereo = {left, right};
                audio_callback_(samples);
            }
        } catch (...) {
        }
    }

    std::size_t audioBatch(const std::int16_t* data, const std::size_t frames) noexcept {
        if (data == nullptr && frames != 0) return 0;
        if (frames > std::numeric_limits<std::size_t>::max() / 2u) return 0;
        try {
            if (audio_callback_) {
                AudioSamples samples;
                samples.frames = frames;
                if (frames != 0) {
                    samples.interleaved_stereo.assign(data, data + frames * 2u);
                }
                audio_callback_(samples);
            }
            return frames;
        } catch (...) {
            return 0;
        }
    }

    void inputPoll() noexcept {
        try {
            if (input_poll_callback_) input_poll_callback_();
        } catch (...) {
        }
    }

    std::int16_t inputState(const unsigned port,
                            const unsigned device,
                            const unsigned index,
                            const unsigned id) noexcept {
        try {
            return input_state_callback_ ? input_state_callback_(port, device, index, id) : 0;
        } catch (...) {
            return 0;
        }
    }

    void log(int level, const char* format, va_list arguments) noexcept {
        if (!log_callback_) return;
        try {
            if (format == nullptr) {
                log_callback_(level, std::string{});
                return;
            }
            va_list sizing;
            va_copy(sizing, arguments);
            const int length = std::vsnprintf(nullptr, 0, format, sizing);
            va_end(sizing);
            if (length < 0) return;
            std::string message(static_cast<std::size_t>(length) + 1u, '\0');
            va_list writing;
            va_copy(writing, arguments);
            std::vsnprintf(message.data(), message.size(), format, writing);
            va_end(writing);
            message.resize(static_cast<std::size_t>(length));
            log_callback_(level, message);
        } catch (...) {
        }
    }

    static LibretroCore* active() noexcept { return active_core; }

    static bool environmentThunk(unsigned command, void* data) noexcept {
        LibretroCore* core = active();
        return core != nullptr && core->environment(command, data);
    }

    static void videoRefreshThunk(const void* data, unsigned width, unsigned height, std::size_t pitch) noexcept {
        if (LibretroCore* core = active(); core != nullptr) core->videoRefresh(data, width, height, pitch);
    }

    static void audioSampleThunk(std::int16_t left, std::int16_t right) noexcept {
        if (LibretroCore* core = active(); core != nullptr) core->audioSample(left, right);
    }

    static std::size_t audioBatchThunk(const std::int16_t* data, std::size_t frames) noexcept {
        return active() == nullptr ? 0 : active()->audioBatch(data, frames);
    }

    static void inputPollThunk() noexcept {
        if (LibretroCore* core = active(); core != nullptr) core->inputPoll();
    }

    static std::int16_t inputStateThunk(unsigned port, unsigned device, unsigned index, unsigned id) noexcept {
        return active() == nullptr ? 0 : active()->inputState(port, device, index, id);
    }

    static void logThunk(int level, const char* format, ...) noexcept {
        LibretroCore* core = active();
        if (core == nullptr) return;
        va_list arguments;
        va_start(arguments, format);
        core->log(level, format, arguments);
        va_end(arguments);
    }

    void* library_ = nullptr;
    CoreSymbols symbols_{};
    std::string content_path_;
    std::string system_directory_;
    std::string save_directory_;
    std::vector<std::uint8_t> content_data_;
    LibretroPixelFormat pixel_format_ = LibretroPixelFormat::k0Rgb1555;
    std::thread::id owner_thread_;
    bool initialized_ = false;
    bool game_loaded_ = false;
    double audio_sample_rate_ = 44100.0;
    double frames_per_second_ = 0.0;

    VideoCallback video_callback_;
    AudioCallback audio_callback_;
    AudioSampleCallback audio_sample_callback_;
    InputPollCallback input_poll_callback_;
    InputStateCallback input_state_callback_;
    MessageCallback message_callback_;
    LogCallback log_callback_;
};

}  // namespace

    CoreOperations makeLibretroOperations(const LibretroSessionConfig& config,
                                       Result* status) noexcept {
    if (status != nullptr) *status = Result::kInternalError;
    if (config.core_path.empty() || config.content_path.empty()) {
        if (status != nullptr) *status = Result::kInvalidArgument;
        return CoreOperations::unimplemented();
    }

    try {
        auto core = std::make_shared<LibretroCore>(config);
        const Result open_result = core->open(config.core_path);
        if (open_result != Result::kOk) {
            if (status != nullptr) *status = open_result;
            return CoreOperations::unimplemented();
        }
        if (status != nullptr) *status = Result::kOk;
        return CoreOperations{
            [core]() noexcept { return core->load(); },
            [core]() noexcept { return core->unload(); },
            [core]() noexcept { return core->runFrame(); },
            [core](const std::string& path) noexcept { return core->saveNative(path); },
            [core](const std::string& path) noexcept { return core->loadNative(path); },
            [core](const std::string& path) noexcept { return core->saveState(path); },
            [core](const std::string& path) noexcept { return core->loadState(path); },
            [core]() noexcept { return core->audioSampleRate(); },
            [core]() noexcept { return core->framesPerSecond(); },
        };
    } catch (...) {
        if (status != nullptr) *status = Result::kInternalError;
        return CoreOperations::unimplemented();
    }
}

}  // namespace retro::runtime
