#include "corelab/libretro_mock.h"

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

bool g_initialized = false;
bool g_loaded = false;
uint64_t g_frame = 0;
uint64_t g_content_hash = 0;
uint16_t g_last_input = 0;
std::array<uint8_t, 32> g_save_ram{};

uint64_t fnv1a(const void *data, size_t size, uint64_t seed = 1469598103934665603ULL) {
    const auto *bytes = static_cast<const uint8_t *>(data);
    uint64_t hash = seed;
    for (size_t i = 0; i < size; ++i) {
        hash ^= bytes[i];
        hash *= 1099511628211ULL;
    }
    return hash;
}

void notify_pixel_format() {
    if (g_environment != nullptr) {
        unsigned format = RETRO_PIXEL_FORMAT_XRGB8888;
        (void)g_environment(RETRO_ENVIRONMENT_SET_PIXEL_FORMAT, &format);
    }
}

void notify_directories() {
    if (g_environment == nullptr) {
        return;
    }
    const char *system_directory = nullptr;
    (void)g_environment(RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY, &system_directory);
    const char *save_directory = nullptr;
    (void)g_environment(RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY, &save_directory);
    retro_log_callback log_callback{};
    (void)g_environment(RETRO_ENVIRONMENT_GET_LOG_INTERFACE, &log_callback);
    unsigned options_version = 0;
    (void)g_environment(RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION, &options_version);
}

#ifndef CORELAB_DUMMY_NO_PERSISTENCE
void write_u64(uint8_t *destination, uint64_t value) {
    for (unsigned i = 0; i < 8; ++i) {
        destination[i] = static_cast<uint8_t>((value >> (i * 8)) & 0xffu);
    }
}

uint64_t read_u64(const uint8_t *source) {
    uint64_t value = 0;
    for (unsigned i = 0; i < 8; ++i) {
        value |= static_cast<uint64_t>(source[i]) << (i * 8);
    }
    return value;
}
#endif

} // namespace

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
    g_content_hash = 0;
    g_last_input = 0;
    g_save_ram.fill(0);
    notify_pixel_format();
    notify_directories();
}

__attribute__((visibility("default"))) void retro_deinit(void) {
    g_loaded = false;
    g_initialized = false;
}

__attribute__((visibility("default"))) void retro_get_system_info(retro_system_info *info) {
    if (info == nullptr) {
        return;
    }
    std::memset(info, 0, sizeof(*info));
    info->library_name = "CoreLab Dummy";
    info->library_version = "1.0.0-test";
    info->valid_extensions = "clfixture";
    info->need_fullpath = false;
    info->block_extract = true;
}

__attribute__((visibility("default"))) void retro_get_system_av_info(retro_system_av_info *info) {
    if (info == nullptr) {
        return;
    }
    std::memset(info, 0, sizeof(*info));
    info->geometry.base_width = 4;
    info->geometry.base_height = 4;
    info->geometry.max_width = 4;
    info->geometry.max_height = 4;
    info->geometry.aspect_ratio = 1.0f;
    info->timing.fps = 60.0;
    info->timing.sample_rate = 44100.0;
}

__attribute__((visibility("default"))) bool retro_load_game(const retro_game_info *game) {
    if (!g_initialized || game == nullptr || game->data == nullptr || game->size < 8) {
        return false;
    }
    g_content_hash = fnv1a(game->data, game->size);
    g_frame = 0;
    g_last_input = 0;
    g_save_ram.fill(0);
    g_loaded = true;
    return true;
}

__attribute__((visibility("default"))) void retro_unload_game(void) {
    g_loaded = false;
}

__attribute__((visibility("default"))) void retro_run(void) {
    if (!g_loaded || g_video == nullptr || g_input_poll == nullptr || g_input_state == nullptr) {
        return;
    }

    g_input_poll();
    const bool button_a = g_input_state(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_A) != 0;
    const bool button_b = g_input_state(0, RETRO_DEVICE_JOYPAD, 0, RETRO_DEVICE_ID_JOYPAD_B) != 0;
    g_last_input = static_cast<uint16_t>((button_a ? 1u : 0u) | (button_b ? 2u : 0u));
    ++g_frame;

    g_save_ram[0] = static_cast<uint8_t>(g_frame & 0xffu);
    g_save_ram[1] = static_cast<uint8_t>((g_frame >> 8) & 0xffu);
    g_save_ram[2] = static_cast<uint8_t>(g_last_input);
    const uint64_t state_mix = g_content_hash ^ (g_frame * 0x9e3779b97f4a7c15ULL) ^ g_last_input;
    for (size_t i = 3; i < g_save_ram.size(); ++i) {
        g_save_ram[i] = static_cast<uint8_t>((state_mix >> ((i % 8) * 8)) + i * 17u);
    }

    std::array<uint32_t, 16> pixels{};
    for (unsigned y = 0; y < 4; ++y) {
        for (unsigned x = 0; x < 4; ++x) {
            const uint32_t red = static_cast<uint32_t>((g_frame * 13u + x * 19u + g_last_input * 31u) & 0xffu);
            const uint32_t green = static_cast<uint32_t>((g_content_hash + y * 23u + g_frame) & 0xffu);
            const uint32_t blue = static_cast<uint32_t>((g_content_hash >> ((x + y) % 8) * 8) & 0xffu);
            pixels[y * 4 + x] = 0xff000000u | (red << 16) | (green << 8) | blue;
        }
    }
    g_video(pixels.data(), 4, 4, 4 * sizeof(uint32_t));

    std::array<int16_t, 16> audio{};
    for (size_t i = 0; i < 8; ++i) {
        const int32_t value = static_cast<int32_t>(((g_frame * 97u + i * 211u + g_last_input * 503u + (g_content_hash & 0xffu)) % 24001u) - 12000);
        audio[i * 2] = static_cast<int16_t>(value);
        audio[i * 2 + 1] = static_cast<int16_t>(-value);
    }
    if (g_audio_batch != nullptr) {
        (void)g_audio_batch(audio.data(), 8);
    } else if (g_audio_sample != nullptr) {
        for (size_t i = 0; i < 8; ++i) {
            g_audio_sample(audio[i * 2], audio[i * 2 + 1]);
        }
    }
}

__attribute__((visibility("default"))) size_t retro_serialize_size(void) {
#ifdef CORELAB_DUMMY_NO_PERSISTENCE
    return 0;
#else
    return 64;
#endif
}

__attribute__((visibility("default"))) bool retro_serialize(void *data, size_t size) {
#ifdef CORELAB_DUMMY_NO_PERSISTENCE
    (void)data;
    (void)size;
    return false;
#else
    if (!g_loaded || data == nullptr || size < retro_serialize_size()) {
        return false;
    }
    auto *bytes = static_cast<uint8_t *>(data);
    std::memset(bytes, 0, size);
    std::memcpy(bytes, "CLSTATE1", 8);
    write_u64(bytes + 8, g_content_hash);
    write_u64(bytes + 16, g_frame);
    write_u64(bytes + 24, g_last_input);
    std::memcpy(bytes + 32, g_save_ram.data(), g_save_ram.size());
    return true;
#endif
}

__attribute__((visibility("default"))) bool retro_unserialize(const void *data, size_t size) {
#ifdef CORELAB_DUMMY_NO_PERSISTENCE
    (void)data;
    (void)size;
    return false;
#else
    if (!g_loaded || data == nullptr || size < retro_serialize_size()) {
        return false;
    }
    const auto *bytes = static_cast<const uint8_t *>(data);
    if (std::memcmp(bytes, "CLSTATE1", 8) != 0 || read_u64(bytes + 8) != g_content_hash) {
        return false;
    }
    g_frame = read_u64(bytes + 16);
    g_last_input = static_cast<uint16_t>(read_u64(bytes + 24));
    std::memcpy(g_save_ram.data(), bytes + 32, g_save_ram.size());
    return true;
#endif
}

__attribute__((visibility("default"))) void *retro_get_memory_data(unsigned id) {
#ifdef CORELAB_DUMMY_NO_PERSISTENCE
    (void)id;
    return nullptr;
#else
    return id == RETRO_MEMORY_SAVE_RAM && g_loaded ? g_save_ram.data() : nullptr;
#endif
}

__attribute__((visibility("default"))) size_t retro_get_memory_size(unsigned id) {
#ifdef CORELAB_DUMMY_NO_PERSISTENCE
    (void)id;
    return 0;
#else
    return id == RETRO_MEMORY_SAVE_RAM && g_loaded ? g_save_ram.size() : 0;
#endif
}

} // extern "C"
