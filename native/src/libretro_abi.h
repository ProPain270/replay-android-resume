#pragma once

// Stable, C-compatible Libretro ABI declarations used at the dynamic-core
// boundary.  The host deliberately exposes only the core entry points and
// environment records it implements; their names, types, and layouts match
// the public Libretro ABI.  Keep this file free of C++ types so it can also be
// included by a test core or another C translation unit.

#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define RETRO_API_VERSION 1u

// Environment commands used by the host.  Values are part of the Libretro
// ABI and must not be renumbered.
#define RETRO_ENVIRONMENT_GET_CAN_DUPE 3u
#define RETRO_ENVIRONMENT_SET_MESSAGE 6u
#define RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY 9u
#define RETRO_ENVIRONMENT_SET_PIXEL_FORMAT 10u
#define RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS 11u
#define RETRO_ENVIRONMENT_GET_LOG_INTERFACE 27u
#define RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY 31u
#define RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION 52u

#define RETRO_HW_FRAME_BUFFER_VALID ((uintptr_t)-1)

enum retro_pixel_format {
    RETRO_PIXEL_FORMAT_0RGB1555 = 0,
    RETRO_PIXEL_FORMAT_XRGB8888 = 1,
    RETRO_PIXEL_FORMAT_RGB565 = 2,
};

#define RETRO_DEVICE_NONE 0u
#define RETRO_DEVICE_JOYPAD 1u
#define RETRO_DEVICE_ANALOG 5u
#define RETRO_DEVICE_ID_ANALOG_X 0u
#define RETRO_DEVICE_ID_ANALOG_Y 1u
#define RETRO_DEVICE_ID_JOYPAD_B 0u
#define RETRO_DEVICE_ID_JOYPAD_Y 1u
#define RETRO_DEVICE_ID_JOYPAD_SELECT 2u
#define RETRO_DEVICE_ID_JOYPAD_START 3u
#define RETRO_DEVICE_ID_JOYPAD_UP 4u
#define RETRO_DEVICE_ID_JOYPAD_DOWN 5u
#define RETRO_DEVICE_ID_JOYPAD_LEFT 6u
#define RETRO_DEVICE_ID_JOYPAD_RIGHT 7u
#define RETRO_DEVICE_ID_JOYPAD_A 8u
#define RETRO_DEVICE_ID_JOYPAD_X 9u
#define RETRO_DEVICE_ID_JOYPAD_L 10u
#define RETRO_DEVICE_ID_JOYPAD_R 11u
#define RETRO_DEVICE_ID_JOYPAD_L2 12u
#define RETRO_DEVICE_ID_JOYPAD_R2 13u
#define RETRO_DEVICE_ID_JOYPAD_L3 14u
#define RETRO_DEVICE_ID_JOYPAD_R3 15u

#define RETRO_MEMORY_SAVE_RAM 0u

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

struct retro_message {
    const char *msg;
    unsigned frames;
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
typedef void *(*retro_get_memory_data_t)(unsigned id);
typedef size_t (*retro_get_memory_size_t)(unsigned id);
typedef size_t (*retro_serialize_size_t)(void);
typedef bool (*retro_serialize_t)(void *data, size_t size);
typedef bool (*retro_unserialize_t)(const void *data, size_t size);

#ifdef __cplusplus
}
#endif
