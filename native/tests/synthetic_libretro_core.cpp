#include "libretro_abi.h"

#include <array>
#include <cstdint>
#include <cstring>

namespace {

retro_environment_t g_environment = nullptr;
retro_video_refresh_t g_video = nullptr;
retro_audio_sample_t g_audio_sample = nullptr;
retro_audio_sample_batch_t g_audio_batch = nullptr;
retro_input_poll_t g_input_poll = nullptr;
retro_input_state_t g_input_state = nullptr;
retro_log_printf_t g_log = nullptr;
bool g_initialized = false;
bool g_loaded = false;
std::uint64_t g_frame = 0;
std::uint16_t g_input = 0;
std::array<std::uint8_t, 4> g_save_ram{0, 1, 2, 3};

}  // namespace

extern "C" {

__attribute__((visibility("default"))) unsigned retro_api_version(void) {
    return RETRO_API_VERSION;
}

__attribute__((visibility("default"))) void retro_set_environment(retro_environment_t callback) {
    g_environment = callback;
}

__attribute__((visibility("default"))) void retro_set_video_refresh(retro_video_refresh_t callback) {
    g_video = callback;
}

__attribute__((visibility("default"))) void retro_set_audio_sample(retro_audio_sample_t callback) {
    g_audio_sample = callback;
}

__attribute__((visibility("default"))) void retro_set_audio_sample_batch(retro_audio_sample_batch_t callback) {
    g_audio_batch = callback;
}

__attribute__((visibility("default"))) void retro_set_input_poll(retro_input_poll_t callback) {
    g_input_poll = callback;
}

__attribute__((visibility("default"))) void retro_set_input_state(retro_input_state_t callback) {
    g_input_state = callback;
}

__attribute__((visibility("default"))) void retro_init(void) {
    g_initialized = true;
    g_loaded = false;
    g_frame = 0;
    g_input = 0;
    g_save_ram = {0, 1, 2, 3};

    if (g_environment != nullptr) {
        retro_pixel_format format = RETRO_PIXEL_FORMAT_XRGB8888;
        (void)g_environment(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, &format);
        const char* system_directory = nullptr;
        const char* save_directory = nullptr;
        (void)g_environment(RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY, &system_directory);
        (void)g_environment(RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY, &save_directory);
        retro_log_callback log_callback{};
        if (g_environment(RETRO_ENVIRONMENT_GET_LOG_INTERFACE, &log_callback)) {
            g_log = log_callback.log;
        }
        unsigned options_version = 99;
        (void)g_environment(RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION, &options_version);
        retro_message message{"synthetic core initialized", 1};
        (void)g_environment(RETRO_ENVIRONMENT_SET_MESSAGE, &message);
    }
    if (g_log != nullptr) g_log(0, "synthetic core init\n");
}

__attribute__((visibility("default"))) void retro_deinit(void) {
    g_loaded = false;
    g_initialized = false;
    g_log = nullptr;
}

__attribute__((visibility("default"))) void retro_get_system_info(retro_system_info* info) {
    if (info == nullptr) return;
    info->library_name = "Native Boundary Synthetic Core";
    info->library_version = "1";
    info->valid_extensions = "synthetic";
    info->need_fullpath = false;
    info->block_extract = true;
}

__attribute__((visibility("default"))) void retro_get_system_av_info(retro_system_av_info* info) {
    if (info == nullptr) return;
    info->geometry.base_width = 2;
    info->geometry.base_height = 2;
    info->geometry.max_width = 2;
    info->geometry.max_height = 2;
    info->geometry.aspect_ratio = 1.0f;
    info->timing.fps = 60.0;
    info->timing.sample_rate = 44100.0;
}

__attribute__((visibility("default"))) bool retro_load_game(const retro_game_info* game) {
    if (!g_initialized || game == nullptr || game->path == nullptr || game->data == nullptr || game->size == 0) {
        return false;
    }
    g_loaded = true;
    g_frame = 0;
    g_input = 0;
    g_save_ram = {0, 1, 2, 3};
    return true;
}

__attribute__((visibility("default"))) void retro_unload_game(void) {
    g_loaded = false;
}

__attribute__((visibility("default"))) void retro_run(void) {
    if (!g_loaded || g_video == nullptr) return;
    if (g_input_poll != nullptr) g_input_poll();
    const bool button_a = g_input_state != nullptr &&
                          g_input_state(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_A) != 0;
    g_input = static_cast<std::uint16_t>(button_a ? 1u : 0u);
    ++g_frame;
    g_save_ram[0] = static_cast<std::uint8_t>(g_frame & 0xffu);

    std::array<std::uint32_t, 4> pixels{
        0xff000000u | static_cast<std::uint32_t>((g_frame + g_input) & 0xffu),
        0xff000100u | static_cast<std::uint32_t>((g_frame * 3u) & 0xffu),
        0xff010000u | static_cast<std::uint32_t>((g_frame * 5u) & 0xffu),
        0xff010100u | static_cast<std::uint32_t>((g_frame * 7u) & 0xffu),
    };
    g_video(pixels.data(), 2, 2, 2 * sizeof(std::uint32_t));

    const std::array<std::int16_t, 4> audio{
        static_cast<std::int16_t>(g_input + 10), static_cast<std::int16_t>(-g_input - 10),
        static_cast<std::int16_t>(g_input + 20), static_cast<std::int16_t>(-g_input - 20),
    };
    if (g_audio_batch != nullptr) {
        (void)g_audio_batch(audio.data(), 2);
    } else if (g_audio_sample != nullptr) {
        g_audio_sample(audio[0], audio[1]);
        g_audio_sample(audio[2], audio[3]);
    }
}

__attribute__((visibility("default"))) void* retro_get_memory_data(unsigned id) {
    return id == RETRO_MEMORY_SAVE_RAM ? g_save_ram.data() : nullptr;
}

__attribute__((visibility("default"))) size_t retro_get_memory_size(unsigned id) {
    return id == RETRO_MEMORY_SAVE_RAM ? g_save_ram.size() : 0;
}

__attribute__((visibility("default"))) size_t retro_serialize_size(void) {
    return sizeof(g_frame) + sizeof(g_input) + g_save_ram.size();
}

__attribute__((visibility("default"))) bool retro_serialize(void* data, size_t size) {
    if (data == nullptr || size != retro_serialize_size()) return false;
    auto* bytes = static_cast<std::uint8_t*>(data);
    std::memcpy(bytes, &g_frame, sizeof(g_frame));
    std::memcpy(bytes + sizeof(g_frame), &g_input, sizeof(g_input));
    std::memcpy(bytes + sizeof(g_frame) + sizeof(g_input), g_save_ram.data(), g_save_ram.size());
    return true;
}

__attribute__((visibility("default"))) bool retro_unserialize(const void* data, size_t size) {
    if (data == nullptr || size != retro_serialize_size()) return false;
    const auto* bytes = static_cast<const std::uint8_t*>(data);
    std::memcpy(&g_frame, bytes, sizeof(g_frame));
    std::memcpy(&g_input, bytes + sizeof(g_frame), sizeof(g_input));
    std::memcpy(g_save_ram.data(), bytes + sizeof(g_frame) + sizeof(g_input), g_save_ram.size());
    return true;
}

}  // extern "C"
