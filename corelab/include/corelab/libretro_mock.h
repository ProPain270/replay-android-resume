#ifndef CORELAB_LIBRETRO_MOCK_H
#define CORELAB_LIBRETRO_MOCK_H

// A deliberately small, self-contained subset of the public Libretro C ABI.
// It is sufficient for the deterministic CoreLab fixture and is not a copy of
// a full third-party core header.

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define RETRO_API_VERSION 1u
#define RETRO_ENVIRONMENT_SET_MESSAGE 6u
#define RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY 9u
#define RETRO_ENVIRONMENT_SET_PIXEL_FORMAT 10u
#define RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS 11u
#define RETRO_ENVIRONMENT_GET_LOG_INTERFACE 27u
#define RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY 31u
#define RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION 52u

#define RETRO_PIXEL_FORMAT_RGB565 0u
#define RETRO_PIXEL_FORMAT_XRGB8888 1u
#define RETRO_PIXEL_FORMAT_RGB1555 2u

#define RETRO_MEMORY_SAVE_RAM 0u

#define RETRO_DEVICE_NONE 0u
#define RETRO_DEVICE_JOYPAD 1u
#define RETRO_DEVICE_ID_JOYPAD_B 0u
#define RETRO_DEVICE_ID_JOYPAD_A 8u

typedef bool (*retro_environment_t)(unsigned cmd, void *data);
typedef void (*retro_video_refresh_t)(const void *data, unsigned width, unsigned height, size_t pitch);
typedef void (*retro_audio_sample_t)(int16_t left, int16_t right);
typedef size_t (*retro_audio_sample_batch_t)(const int16_t *data, size_t frames);
typedef void (*retro_input_poll_t)(void);
typedef int16_t (*retro_input_state_t)(unsigned port, unsigned device, unsigned index, unsigned id);
typedef void (*retro_log_printf_t)(int level, const char *format, ...);

struct retro_log_callback {
    retro_log_printf_t log;
};

struct retro_input_descriptor {
    unsigned port;
    unsigned device;
    unsigned index;
    unsigned id;
    const char *description;
};

struct retro_game_info {
    const char *path;
    const void *data;
    size_t size;
    const char *meta;
};

struct retro_system_info {
    const char *library_name;
    const char *library_version;
    const char *valid_extensions;
    bool need_fullpath;
    bool block_extract;
};

struct retro_game_geometry {
    unsigned base_width;
    unsigned base_height;
    unsigned max_width;
    unsigned max_height;
    float aspect_ratio;
};

struct retro_system_timing {
    double fps;
    double sample_rate;
};

struct retro_system_av_info {
    struct retro_game_geometry geometry;
    struct retro_system_timing timing;
};

typedef unsigned (*retro_api_version_t)(void);
typedef void (*retro_set_environment_t)(retro_environment_t);
typedef void (*retro_set_video_refresh_t)(retro_video_refresh_t);
typedef void (*retro_set_audio_sample_t)(retro_audio_sample_t);
typedef void (*retro_set_audio_sample_batch_t)(retro_audio_sample_batch_t);
typedef void (*retro_set_input_poll_t)(retro_input_poll_t);
typedef void (*retro_set_input_state_t)(retro_input_state_t);
typedef void (*retro_init_t)(void);
typedef void (*retro_deinit_t)(void);
typedef void (*retro_get_system_info_t)(struct retro_system_info *);
typedef void (*retro_get_system_av_info_t)(struct retro_system_av_info *);
typedef bool (*retro_load_game_t)(const struct retro_game_info *);
typedef void (*retro_unload_game_t)(void);
typedef void (*retro_run_t)(void);
typedef size_t (*retro_serialize_size_t)(void);
typedef bool (*retro_serialize_t)(void *data, size_t size);
typedef bool (*retro_unserialize_t)(const void *data, size_t size);
typedef void *(*retro_get_memory_data_t)(unsigned id);
typedef size_t (*retro_get_memory_size_t)(unsigned id);

#ifdef __cplusplus
}
#endif

#endif
