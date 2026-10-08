#include "corelab/libretro_mock.h"

#include <algorithm>
#include <array>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <filesystem>
#include <fstream>
#include <functional>
#include <iomanip>
#include <iostream>
#include <map>
#include <optional>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace fs = std::filesystem;

namespace {

class Sha256 {
public:
    Sha256() { reset(); }

    void update(const uint8_t *data, size_t length) {
        for (size_t i = 0; i < length; ++i) {
            buffer_[buffer_length_++] = data[i];
            if (buffer_length_ == 64) {
                transform(buffer_.data());
                bit_length_ += 512;
                buffer_length_ = 0;
            }
        }
    }

    std::array<uint8_t, 32> finish() {
        const uint64_t original_bit_length = bit_length_ + buffer_length_ * 8;
        buffer_[buffer_length_++] = 0x80;
        if (buffer_length_ > 56) {
            while (buffer_length_ < 64) {
                buffer_[buffer_length_++] = 0;
            }
            transform(buffer_.data());
            buffer_length_ = 0;
        }
        while (buffer_length_ < 56) {
            buffer_[buffer_length_++] = 0;
        }
        for (int i = 7; i >= 0; --i) {
            buffer_[buffer_length_++] = static_cast<uint8_t>((original_bit_length >> (i * 8)) & 0xffu);
        }
        transform(buffer_.data());

        std::array<uint8_t, 32> digest{};
        for (unsigned i = 0; i < 8; ++i) {
            digest[i * 4] = static_cast<uint8_t>((state_[i] >> 24) & 0xffu);
            digest[i * 4 + 1] = static_cast<uint8_t>((state_[i] >> 16) & 0xffu);
            digest[i * 4 + 2] = static_cast<uint8_t>((state_[i] >> 8) & 0xffu);
            digest[i * 4 + 3] = static_cast<uint8_t>(state_[i] & 0xffu);
        }
        return digest;
    }

private:
    static uint32_t rotr(uint32_t value, uint32_t bits) {
        return (value >> bits) | (value << (32 - bits));
    }

    void reset() {
        state_ = {0x6a09e667u, 0xbb67ae85u, 0x3c6ef372u, 0xa54ff53au,
                  0x510e527fu, 0x9b05688cu, 0x1f83d9abu, 0x5be0cd19u};
        bit_length_ = 0;
        buffer_length_ = 0;
        buffer_.fill(0);
    }

    void transform(const uint8_t *chunk) {
        static constexpr uint32_t k[64] = {
            0x428a2f98u, 0x71374491u, 0xb5c0fbcfu, 0xe9b5dba5u,
            0x3956c25bu, 0x59f111f1u, 0x923f82a4u, 0xab1c5ed5u,
            0xd807aa98u, 0x12835b01u, 0x243185beu, 0x550c7dc3u,
            0x72be5d74u, 0x80deb1feu, 0x9bdc06a7u, 0xc19bf174u,
            0xe49b69c1u, 0xefbe4786u, 0x0fc19dc6u, 0x240ca1ccu,
            0x2de92c6fu, 0x4a7484aau, 0x5cb0a9dcu, 0x76f988dau,
            0x983e5152u, 0xa831c66du, 0xb00327c8u, 0xbf597fc7u,
            0xc6e00bf3u, 0xd5a79147u, 0x06ca6351u, 0x14292967u,
            0x27b70a85u, 0x2e1b2138u, 0x4d2c6dfcu, 0x53380d13u,
            0x650a7354u, 0x766a0abbu, 0x81c2c92eu, 0x92722c85u,
            0xa2bfe8a1u, 0xa81a664bu, 0xc24b8b70u, 0xc76c51a3u,
            0xd192e819u, 0xd6990624u, 0xf40e3585u, 0x106aa070u,
            0x19a4c116u, 0x1e376c08u, 0x2748774cu, 0x34b0bcb5u,
            0x391c0cb3u, 0x4ed8aa4au, 0x5b9cca4fu, 0x682e6ff3u,
            0x748f82eeu, 0x78a5636fu, 0x84c87814u, 0x8cc70208u,
            0x90befffau, 0xa4506cebu, 0xbef9a3f7u, 0xc67178f2u};
        uint32_t w[64]{};
        for (unsigned i = 0; i < 16; ++i) {
            w[i] = (static_cast<uint32_t>(chunk[i * 4]) << 24) |
                   (static_cast<uint32_t>(chunk[i * 4 + 1]) << 16) |
                   (static_cast<uint32_t>(chunk[i * 4 + 2]) << 8) |
                   static_cast<uint32_t>(chunk[i * 4 + 3]);
        }
        for (unsigned i = 16; i < 64; ++i) {
            const uint32_t s0 = rotr(w[i - 15], 7) ^ rotr(w[i - 15], 18) ^ (w[i - 15] >> 3);
            const uint32_t s1 = rotr(w[i - 2], 17) ^ rotr(w[i - 2], 19) ^ (w[i - 2] >> 10);
            w[i] = w[i - 16] + s0 + w[i - 7] + s1;
        }

        uint32_t a = state_[0], b = state_[1], c = state_[2], d = state_[3];
        uint32_t e = state_[4], f = state_[5], g = state_[6], h = state_[7];
        for (unsigned i = 0; i < 64; ++i) {
            const uint32_t s1 = rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25);
            const uint32_t ch = (e & f) ^ ((~e) & g);
            const uint32_t temp1 = h + s1 + ch + k[i] + w[i];
            const uint32_t s0 = rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22);
            const uint32_t maj = (a & b) ^ (a & c) ^ (b & c);
            const uint32_t temp2 = s0 + maj;
            h = g;
            g = f;
            f = e;
            e = d + temp1;
            d = c;
            c = b;
            b = a;
            a = temp1 + temp2;
        }
        state_[0] += a;
        state_[1] += b;
        state_[2] += c;
        state_[3] += d;
        state_[4] += e;
        state_[5] += f;
        state_[6] += g;
        state_[7] += h;
    }

    std::array<uint32_t, 8> state_{};
    uint64_t bit_length_ = 0;
    size_t buffer_length_ = 0;
    std::array<uint8_t, 64> buffer_{};
};

std::string hex_digest(const std::array<uint8_t, 32> &digest) {
    std::ostringstream out;
    out << std::hex << std::setfill('0');
    for (uint8_t byte : digest) {
        out << std::setw(2) << static_cast<unsigned>(byte);
    }
    return out.str();
}

std::string sha256_bytes(const std::vector<uint8_t> &bytes) {
    Sha256 sha;
    if (!bytes.empty()) {
        sha.update(bytes.data(), bytes.size());
    }
    return hex_digest(sha.finish());
}

std::string sha256_file(const fs::path &path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) {
        return {};
    }
    Sha256 sha;
    std::array<uint8_t, 8192> buffer{};
    while (input) {
        input.read(reinterpret_cast<char *>(buffer.data()), static_cast<std::streamsize>(buffer.size()));
        const std::streamsize count = input.gcount();
        if (count > 0) {
            sha.update(buffer.data(), static_cast<size_t>(count));
        }
    }
    return hex_digest(sha.finish());
}

std::string json_escape(const std::string &value) {
    std::ostringstream out;
    for (unsigned char c : value) {
        switch (c) {
            case '"': out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (c < 0x20) {
                    out << "\\u" << std::hex << std::setw(4) << std::setfill('0') << static_cast<unsigned>(c);
                } else {
                    out << c;
                }
        }
    }
    return out.str();
}

std::string trim(std::string value) {
    auto not_space = [](unsigned char c) { return !std::isspace(c); };
    value.erase(value.begin(), std::find_if(value.begin(), value.end(), not_space));
    value.erase(std::find_if(value.rbegin(), value.rend(), not_space).base(), value.end());
    return value;
}

struct Profile {
    std::map<std::string, std::string> values;

    std::string get(const std::string &key, const std::string &fallback = {}) const {
        auto found = values.find(key);
        return found == values.end() ? fallback : found->second;
    }

    int integer(const std::string &key, int fallback = 0) const {
        const std::string value = get(key);
        if (value.empty()) return fallback;
        size_t consumed = 0;
        const int result = std::stoi(value, &consumed);
        if (consumed != value.size()) throw std::runtime_error("invalid integer for " + key);
        return result;
    }
};

Profile read_profile(const fs::path &path) {
    std::ifstream input(path);
    if (!input) throw std::runtime_error("cannot open profile: " + path.string());
    Profile profile;
    std::string line;
    unsigned line_number = 0;
    while (std::getline(input, line)) {
        ++line_number;
        line = trim(line);
        if (line.empty() || line[0] == '#') continue;
        const size_t separator = line.find('=');
        if (separator == std::string::npos) {
            throw std::runtime_error("profile line " + std::to_string(line_number) + " is missing '='");
        }
        const std::string key = trim(line.substr(0, separator));
        const std::string value = trim(line.substr(separator + 1));
        if (key.empty() || value.empty()) throw std::runtime_error("profile line " + std::to_string(line_number) + " is empty");
        if (!profile.values.emplace(key, value).second) throw std::runtime_error("duplicate profile key: " + key);
    }
    if (profile.get("profile_version") != "1") throw std::runtime_error("unsupported profile_version");
    return profile;
}

std::vector<int> parse_trace(const std::string &value) {
    std::vector<int> trace;
    std::stringstream stream(value);
    std::string token;
    while (std::getline(stream, token, ',')) {
        token = trim(token);
        if (token.empty()) throw std::runtime_error("input.trace contains an empty token");
        trace.push_back(std::stoi(token));
    }
    return trace;
}

std::vector<uint8_t> make_fixture(uint64_t seed, size_t size) {
    if (size < 8) throw std::runtime_error("content.bytes must be at least 8");
    std::vector<uint8_t> fixture(size);
    static constexpr char magic[] = "CORELAB-SYNTHETIC-V1";
    for (size_t i = 0; i < size; ++i) {
        const uint8_t seed_byte = static_cast<uint8_t>((seed >> ((i % 8) * 8)) & 0xffu);
        fixture[i] = static_cast<uint8_t>(seed_byte ^ static_cast<uint8_t>(i * 37u + 0x5au));
    }
    for (size_t i = 0; i < sizeof(magic) - 1 && i < fixture.size(); ++i) fixture[i] = static_cast<uint8_t>(magic[i]);
    return fixture;
}

std::string platform_abi() {
#if defined(__aarch64__)
    return "arm64-v8a";
#elif defined(__x86_64__)
    return "x86_64";
#elif defined(__arm__)
    return "armeabi-v7a";
#else
    return "unknown";
#endif
}

std::string report_name(const fs::path &path) {
    return path.filename().empty() ? "<artifact>" : path.filename().string();
}

std::string thread_id_string() {
    return std::to_string(std::hash<std::thread::id>{}(std::this_thread::get_id()));
}

struct FrameRecord {
    unsigned index = 0;
    unsigned width = 0;
    unsigned height = 0;
    size_t pitch = 0;
    std::string digest;
    std::vector<uint8_t> canonical;
    bool oracle_frame = false;
};

struct HostContext {
    Profile profile;
    std::vector<int> input_trace;
    std::string system_dir = "corelab-system";
    std::string save_dir = "corelab-save";
    std::thread::id emulation_thread = std::this_thread::get_id();
    bool initialized = false;
    bool loaded = false;
    bool deinitialized = false;
    unsigned pixel_format = RETRO_PIXEL_FORMAT_RGB565;
    bool pixel_format_seen = false;
    bool system_dir_seen = false;
    bool save_dir_seen = false;
    bool log_seen = false;
    bool options_seen = false;
    bool input_descriptors_seen = false;
    unsigned callback_depth = 0;
    unsigned run_index = 0;
    int forced_input = -1;
    int current_input = 0;
    unsigned video_calls_this_run = 0;
    unsigned audio_calls_this_run = 0;
    unsigned input_polls_this_run = 0;
    size_t audio_frames = 0;
    uint64_t event_sequence = 0;
    unsigned thread_violations = 0;
    std::vector<std::string> failures;
    std::vector<std::string> events;
    std::vector<FrameRecord> frames;
    std::vector<uint8_t> oracle_video_bytes;
    std::vector<uint8_t> oracle_audio_bytes;
    std::vector<int16_t> audio_samples;

    bool thread_ok() {
        if (std::this_thread::get_id() == emulation_thread) return true;
        ++thread_violations;
        failure("thread contract violation: callback arrived on unexpected thread");
        return false;
    }

    void event(const std::string &kind, const std::string &detail) {
        std::ostringstream line;
        line << "{\"seq\":" << event_sequence++
             << ",\"kind\":\"" << json_escape(kind)
             << "\",\"detail\":\"" << json_escape(detail)
             << "\",\"thread\":\"" << thread_id_string() << "\"}";
        events.push_back(line.str());
    }

    void failure(const std::string &message) {
        failures.push_back(message);
        event("failure", message);
    }

    void entry(const char *name) {
        thread_ok();
        event("entry", name);
    }

    void begin_run(unsigned index, int forced = -1) {
        run_index = index;
        forced_input = forced;
        video_calls_this_run = 0;
        audio_calls_this_run = 0;
        input_polls_this_run = 0;
    }

    bool oracle_collection_enabled() const {
        return forced_input < 0 && run_index < static_cast<unsigned>(profile.integer("execution.frames"));
    }

    void finish_run() {
        if (video_calls_this_run != 1) failure("expected exactly one valid video callback per retro_run");
        if (audio_calls_this_run == 0) failure("expected an audio callback per retro_run");
        if (input_polls_this_run != 1) failure("expected exactly one input poll per retro_run");
        forced_input = -1;
    }
};

thread_local HostContext *current_context = nullptr;

struct CallbackScope {
    HostContext &context;
    explicit CallbackScope(HostContext &value, const char *name) : context(value) {
        context.thread_ok();
        ++context.callback_depth;
        context.event("callback", name);
    }
    ~CallbackScope() { --context.callback_depth; }
};

void host_log(int, const char *, ...) {
    if (current_context == nullptr) return;
    current_context->log_seen = true;
    current_context->event("environment", "log callback");
}

bool host_environment(unsigned command, void *data) {
    if (current_context == nullptr) return false;
    HostContext &context = *current_context;
    CallbackScope scope(context, "environment");
    switch (command) {
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT:
            if (data == nullptr) {
                context.failure("SET_PIXEL_FORMAT received null data");
                return false;
            }
            context.pixel_format = *static_cast<unsigned *>(data);
            context.pixel_format_seen = true;
            context.event("environment", "SET_PIXEL_FORMAT accepted");
            if (context.pixel_format != RETRO_PIXEL_FORMAT_XRGB8888) {
                context.failure("dummy core requested an unsupported pixel format");
                return false;
            }
            return true;
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY:
            if (data == nullptr) {
                context.failure("GET_SYSTEM_DIRECTORY received null data");
                return false;
            }
            *static_cast<const char **>(data) = context.system_dir.c_str();
            context.system_dir_seen = true;
            return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY:
            if (data == nullptr) {
                context.failure("GET_SAVE_DIRECTORY received null data");
                return false;
            }
            *static_cast<const char **>(data) = context.save_dir.c_str();
            context.save_dir_seen = true;
            return true;
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE:
            if (data == nullptr) {
                context.failure("GET_LOG_INTERFACE received null data");
                return false;
            }
            static_cast<retro_log_callback *>(data)->log = host_log;
            context.log_seen = true;
            return true;
        case RETRO_ENVIRONMENT_GET_CORE_OPTIONS_VERSION:
            if (data == nullptr) {
                context.failure("GET_CORE_OPTIONS_VERSION received null data");
                return false;
            }
            *static_cast<unsigned *>(data) = 0;
            context.options_seen = true;
            return true;
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
            context.input_descriptors_seen = true;
            return data != nullptr;
        default:
            context.event("environment-unsupported", std::to_string(command));
            return false;
    }
}

void host_video(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (current_context == nullptr) return;
    HostContext &context = *current_context;
    CallbackScope scope(context, "video");
    ++context.video_calls_this_run;
    const unsigned max_width = static_cast<unsigned>(context.profile.integer("execution.max_video_width", 1024));
    const unsigned max_height = static_cast<unsigned>(context.profile.integer("execution.max_video_height", 1024));
    if (data == nullptr) {
        context.failure("video callback supplied null data");
        return;
    }
    if (width == 0 || height == 0 || width > max_width || height > max_height || pitch < width * 4 || pitch > max_width * 4) {
        context.failure("video callback supplied invalid geometry or pitch");
        return;
    }
    const auto *source = static_cast<const uint8_t *>(data);
    std::vector<uint8_t> canonical(static_cast<size_t>(width) * height * 4);
    for (unsigned row = 0; row < height; ++row) {
        std::memcpy(canonical.data() + static_cast<size_t>(row) * width * 4,
                    source + static_cast<size_t>(row) * pitch, static_cast<size_t>(width) * 4);
    }
    FrameRecord record;
    record.index = static_cast<unsigned>(context.frames.size());
    record.width = width;
    record.height = height;
    record.pitch = pitch;
    record.digest = sha256_bytes(canonical);
    record.oracle_frame = context.oracle_collection_enabled();
    record.canonical = canonical;
    context.frames.push_back(record);
    if (record.oracle_frame) context.oracle_video_bytes.insert(context.oracle_video_bytes.end(), canonical.begin(), canonical.end());
}

void host_audio_sample(int16_t left, int16_t right) {
    if (current_context == nullptr) return;
    HostContext &context = *current_context;
    CallbackScope scope(context, "audio_sample");
    ++context.audio_calls_this_run;
    context.audio_samples.push_back(left);
    context.audio_samples.push_back(right);
    if (context.oracle_collection_enabled()) {
        context.oracle_audio_bytes.push_back(static_cast<uint8_t>(left & 0xff));
        context.oracle_audio_bytes.push_back(static_cast<uint8_t>((left >> 8) & 0xff));
        context.oracle_audio_bytes.push_back(static_cast<uint8_t>(right & 0xff));
        context.oracle_audio_bytes.push_back(static_cast<uint8_t>((right >> 8) & 0xff));
    }
    ++context.audio_frames;
}

size_t host_audio_batch(const int16_t *data, size_t frames) {
    if (current_context == nullptr) return 0;
    HostContext &context = *current_context;
    CallbackScope scope(context, "audio_batch");
    ++context.audio_calls_this_run;
    if (frames > 4096 || (frames > 0 && data == nullptr)) {
        context.failure("audio callback supplied invalid buffer/count");
        return 0;
    }
    for (size_t i = 0; i < frames * 2; ++i) {
        const int16_t sample = data[i];
        context.audio_samples.push_back(sample);
        if (context.oracle_collection_enabled()) {
            context.oracle_audio_bytes.push_back(static_cast<uint8_t>(sample & 0xff));
            context.oracle_audio_bytes.push_back(static_cast<uint8_t>((sample >> 8) & 0xff));
        }
    }
    context.audio_frames += frames;
    return frames;
}

void host_input_poll() {
    if (current_context == nullptr) return;
    HostContext &context = *current_context;
    CallbackScope scope(context, "input_poll");
    ++context.input_polls_this_run;
    if (context.forced_input >= 0) {
        context.current_input = context.forced_input;
    } else if (context.run_index < context.input_trace.size()) {
        context.current_input = context.input_trace[context.run_index];
    } else if (context.profile.get("input.exhaustion") == "neutral") {
        context.current_input = 0;
        context.event("input", "trace exhausted; neutral input applied");
    } else {
        context.current_input = 0;
        context.failure("input trace exhausted");
    }
}

int16_t host_input_state(unsigned port, unsigned device, unsigned index, unsigned id) {
    if (current_context == nullptr) return 0;
    HostContext &context = *current_context;
    CallbackScope scope(context, "input_state");
    if (port != 0 || device != RETRO_DEVICE_JOYPAD || index != 0 || (id != RETRO_DEVICE_ID_JOYPAD_A && id != RETRO_DEVICE_ID_JOYPAD_B)) {
        context.failure("unexpected input device query");
        return 0;
    }
    const int bit = id == RETRO_DEVICE_ID_JOYPAD_A ? 1 : 2;
    return (context.current_input & bit) != 0 ? 1 : 0;
}

struct CoreApi {
    void *handle = nullptr;
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
    retro_serialize_size_t serialize_size = nullptr;
    retro_serialize_t serialize = nullptr;
    retro_unserialize_t unserialize = nullptr;
    retro_get_memory_data_t get_memory_data = nullptr;
    retro_get_memory_size_t get_memory_size = nullptr;

    template <typename T>
    bool resolve(T &target, const char *name) {
        target = reinterpret_cast<T>(dlsym(handle, name));
        return target != nullptr;
    }

    bool load(const fs::path &path, HostContext &context) {
        handle = dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL);
        if (handle == nullptr) {
            context.failure("core load failed: artifact unavailable");
            return false;
        }
        if (!resolve(api_version, "retro_api_version")) { context.failure("missing required symbol: retro_api_version"); return false; }
        if (!resolve(set_environment, "retro_set_environment")) { context.failure("missing required symbol: retro_set_environment"); return false; }
        if (!resolve(set_video_refresh, "retro_set_video_refresh")) { context.failure("missing required symbol: retro_set_video_refresh"); return false; }
        if (!resolve(set_audio_sample, "retro_set_audio_sample")) { context.failure("missing required symbol: retro_set_audio_sample"); return false; }
        if (!resolve(set_audio_sample_batch, "retro_set_audio_sample_batch")) { context.failure("missing required symbol: retro_set_audio_sample_batch"); return false; }
        if (!resolve(set_input_poll, "retro_set_input_poll")) { context.failure("missing required symbol: retro_set_input_poll"); return false; }
        if (!resolve(set_input_state, "retro_set_input_state")) { context.failure("missing required symbol: retro_set_input_state"); return false; }
        if (!resolve(init, "retro_init")) { context.failure("missing required symbol: retro_init"); return false; }
        if (!resolve(deinit, "retro_deinit")) { context.failure("missing required symbol: retro_deinit"); return false; }
        if (!resolve(get_system_info, "retro_get_system_info")) { context.failure("missing required symbol: retro_get_system_info"); return false; }
        if (!resolve(get_system_av_info, "retro_get_system_av_info")) { context.failure("missing required symbol: retro_get_system_av_info"); return false; }
        if (!resolve(load_game, "retro_load_game")) { context.failure("missing required symbol: retro_load_game"); return false; }
        if (!resolve(unload_game, "retro_unload_game")) { context.failure("missing required symbol: retro_unload_game"); return false; }
        if (!resolve(run, "retro_run")) { context.failure("missing required symbol: retro_run"); return false; }
        if (!resolve(serialize_size, "retro_serialize_size")) { context.failure("missing required symbol: retro_serialize_size"); return false; }
        if (!resolve(serialize, "retro_serialize")) { context.failure("missing required symbol: retro_serialize"); return false; }
        if (!resolve(unserialize, "retro_unserialize")) { context.failure("missing required symbol: retro_unserialize"); return false; }
        if (!resolve(get_memory_data, "retro_get_memory_data")) { context.failure("missing required symbol: retro_get_memory_data"); return false; }
        if (!resolve(get_memory_size, "retro_get_memory_size")) { context.failure("missing required symbol: retro_get_memory_size"); return false; }
        return true;
    }

    void close() {
        if (handle != nullptr) {
            dlclose(handle);
            handle = nullptr;
        }
    }
};

void call_entry(HostContext &context, const char *name, const std::function<void()> &call) {
    context.entry(name);
    call();
}

std::vector<uint8_t> read_bytes(const fs::path &path) {
    std::ifstream input(path, std::ios::binary);
    if (!input) return {};
    input.seekg(0, std::ios::end);
    const std::streamoff size = input.tellg();
    input.seekg(0, std::ios::beg);
    if (size < 0) return {};
    std::vector<uint8_t> bytes(static_cast<size_t>(size));
    if (!bytes.empty()) input.read(reinterpret_cast<char *>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
    return input ? bytes : std::vector<uint8_t>{};
}

bool write_atomic(const fs::path &destination, const std::vector<uint8_t> &bytes, HostContext &context) {
    const fs::path temporary = destination.string() + ".tmp";
    std::ofstream output(temporary, std::ios::binary | std::ios::trunc);
    if (!output) {
        context.failure("native save temporary file could not be opened");
        return false;
    }
    if (!bytes.empty()) output.write(reinterpret_cast<const char *>(bytes.data()), static_cast<std::streamsize>(bytes.size()));
    output.flush();
    if (!output) {
        context.failure("native save flush failed");
        return false;
    }
    output.close();
    std::error_code error;
    fs::rename(temporary, destination, error);
    if (error) {
        context.failure("native save replace failed: " + error.message());
        return false;
    }
    return true;
}

std::string capability(bool advertised, bool observed, bool tested, bool passed, const std::string &status, const std::string &details) {
    std::ostringstream out;
    out << "{\"advertised\":" << (advertised ? "true" : "false")
        << ",\"observed\":" << (observed ? "true" : "false")
        << ",\"tested\":" << (tested ? "true" : "false")
        << ",\"passed\":" << (passed ? "true" : "false")
        << ",\"status\":\"" << json_escape(status)
        << "\",\"details\":\"" << json_escape(details) << "\"}";
    return out.str();
}

std::string persistence(bool applicable, const std::string &status, size_t size, const std::string &digest, const std::string &details) {
    std::ostringstream out;
    out << "{\"applicable\":" << (applicable ? "true" : "false")
        << ",\"status\":\"" << json_escape(status)
        << "\",\"size\":" << size
        << ",\"digest\":\"" << json_escape(digest)
        << "\",\"details\":\"" << json_escape(details) << "\"}";
    return out.str();
}

void write_text(const fs::path &path, const std::string &text) {
    std::ofstream output(path, std::ios::binary | std::ios::trunc);
    if (!output) throw std::runtime_error("cannot write report: " + path.string());
    output << text;
}

struct RunData {
    Profile profile;
    fs::path profile_path;
    fs::path core_path;
    fs::path report_dir;
    std::vector<uint8_t> fixture;
    std::string fixture_digest;
    std::string artifact_digest;
    std::string core_name = "unknown";
    std::string core_version = "unknown";
    unsigned core_api = 0;
    bool core_need_fullpath = false;
    std::string core_status = "blocked";
    std::string native_save_json = persistence(false, "blocked", 0, {}, "not executed");
    std::string save_state_json = persistence(false, "blocked", 0, {}, "not executed");
};

void run_frames(CoreApi &core, HostContext &context, unsigned count, bool collect_oracle, int forced_input = -1) {
    for (unsigned index = 0; index < count; ++index) {
        context.begin_run(index, collect_oracle ? -1 : forced_input);
        call_entry(context, "retro_run", [&] { core.run(); });
        context.finish_run();
    }
}

void exercise_save_state(CoreApi &core, HostContext &context, RunData &data) {
    const size_t state_size = core.serialize_size();
    if (state_size == 0) {
        data.save_state_json = persistence(false, "not_applicable", 0, {}, "core reports no serialization support");
        if (context.profile.get("persistence.save_state") == "required") context.failure("save state required but core reports size zero");
        return;
    }
    if (state_size > 1024 * 1024) {
        context.failure("save state exceeds harness safety bound");
        data.save_state_json = persistence(true, "failed", state_size, {}, "serialization size exceeds 1 MiB bound");
        return;
    }
    std::vector<uint8_t> state(state_size);
    context.entry("retro_serialize");
    if (!core.serialize(state.data(), state.size())) {
        context.failure("retro_serialize returned false");
        data.save_state_json = persistence(true, "failed", state_size, {}, "serialize failed");
        return;
    }
    const std::string digest = sha256_bytes(state);
    const size_t frame_before = context.frames.size();
    const size_t audio_before = context.audio_samples.size();
    run_frames(core, context, 1, false, 0);
    if (context.frames.size() <= frame_before) {
        context.failure("save state replay did not produce a frame");
        data.save_state_json = persistence(true, "failed", state_size, digest, "missing post-state frame");
        return;
    }
    const std::vector<uint8_t> first_frame = context.frames.back().canonical;
    const std::vector<int16_t> first_audio(context.audio_samples.begin() + static_cast<std::ptrdiff_t>(audio_before), context.audio_samples.end());
    context.entry("retro_unserialize");
    if (!core.unserialize(state.data(), state.size())) {
        context.failure("retro_unserialize returned false");
        data.save_state_json = persistence(true, "failed", state_size, digest, "unserialize failed");
        return;
    }
    const size_t replay_audio_before = context.audio_samples.size();
    run_frames(core, context, 1, false, 0);
    const bool same_frame = !context.frames.empty() && context.frames.back().canonical == first_frame;
    const std::vector<int16_t> replay_audio(context.audio_samples.begin() + static_cast<std::ptrdiff_t>(replay_audio_before), context.audio_samples.end());
    const bool same_audio = replay_audio == first_audio;
    if (!same_frame || !same_audio) context.failure("save state replay diverged from deterministic checkpoint");
    data.save_state_json = persistence(true, same_frame && same_audio ? "passed" : "failed", state_size, digest,
                                       same_frame && same_audio ? "serialize/unserialize replay matched video and audio" : "replay mismatch");
}

void exercise_native_save(CoreApi &core, HostContext &context, RunData &data) {
    const size_t save_size = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    if (save_size == 0) {
        data.native_save_json = persistence(false, "not_applicable", 0, {}, "core exposes no SAVE_RAM region");
        if (context.profile.get("persistence.native_save") == "required") context.failure("native save required but core exposes no SAVE_RAM");
        return;
    }
    if (save_size > 1024 * 1024) {
        context.failure("native save exceeds harness safety bound");
        data.native_save_json = persistence(true, "failed", save_size, {}, "save size exceeds 1 MiB bound");
        return;
    }
    auto *memory = static_cast<uint8_t *>(core.get_memory_data(RETRO_MEMORY_SAVE_RAM));
    if (memory == nullptr) {
        context.failure("SAVE_RAM size is nonzero but data pointer is null");
        data.native_save_json = persistence(true, "failed", save_size, {}, "null SAVE_RAM pointer");
        return;
    }
    std::vector<uint8_t> original(memory, memory + save_size);
    const std::string digest = sha256_bytes(original);
    const fs::path destination = data.report_dir / "native-save.bin";
    const bool committed = write_atomic(destination, original, context);

    context.entry("retro_unload_game");
    core.unload_game();
    context.loaded = false;
    context.entry("retro_load_game");
    retro_game_info game{};
    game.path = "corelab-synthetic.clfixture";
    game.data = data.fixture.data();
    game.size = data.fixture.size();
    if (!core.load_game(&game)) {
        context.failure("core failed native-save reload");
        data.native_save_json = persistence(true, "failed", save_size, digest, "reload failed");
        return;
    }
    context.loaded = true;
    auto *reloaded_memory = static_cast<uint8_t *>(core.get_memory_data(RETRO_MEMORY_SAVE_RAM));
    if (reloaded_memory == nullptr) {
        context.failure("reloaded SAVE_RAM pointer is null");
        data.native_save_json = persistence(true, "failed", save_size, digest, "reloaded pointer null");
        return;
    }
    const std::vector<uint8_t> persisted = read_bytes(destination);
    const bool file_ok = persisted == original && sha256_bytes(persisted) == digest;
    if (file_ok) std::memcpy(reloaded_memory, persisted.data(), persisted.size());
    const bool round_trip = file_ok && std::equal(original.begin(), original.end(), reloaded_memory);
    if (!committed || !round_trip) context.failure("native save round-trip failed");
    data.native_save_json = persistence(true, committed && round_trip ? "passed" : "failed", save_size, digest,
                                        committed && round_trip ? "private temp-file, flush, replace, reload, and digest check passed" : "save round-trip did not verify");
}

void write_reports(const RunData &data, const HostContext &context) {
    fs::create_directories(data.report_dir);
    const std::string video_digest = sha256_bytes(context.oracle_video_bytes);
    const std::string audio_digest = sha256_bytes(context.oracle_audio_bytes);
    const bool thread_passed = context.thread_violations == 0;
    const bool video_passed = context.failures.empty() && video_digest == data.profile.get("expect.video_sha256");
    const bool audio_passed = context.failures.empty() && audio_digest == data.profile.get("expect.audio_sha256");
    const bool required_native_ok = data.profile.get("persistence.native_save") != "required" || data.native_save_json.find("\"status\":\"passed\"") != std::string::npos;
    const bool required_state_ok = data.profile.get("persistence.save_state") != "required" || data.save_state_json.find("\"status\":\"passed\"") != std::string::npos;
    const bool overall = context.failures.empty() && thread_passed && video_passed && audio_passed && required_native_ok && required_state_ok;

    std::ostringstream events;
    for (const std::string &event : context.events) events << event << '\n';
    write_text(data.report_dir / "events.jsonl", events.str());

    std::ostringstream video;
    video << "{\"oracle_sha256\":\"" << video_digest << "\",\"expected_sha256\":\"" << json_escape(data.profile.get("expect.video_sha256")) << "\",\"checkpoints\":[";
    for (size_t i = 0; i < context.frames.size(); ++i) {
        if (i != 0) video << ',';
        const auto &frame = context.frames[i];
        video << "{\"index\":" << frame.index << ",\"width\":" << frame.width << ",\"height\":" << frame.height
              << ",\"pitch\":" << frame.pitch << ",\"sha256\":\"" << frame.digest << "\",\"oracle\":" << (frame.oracle_frame ? "true" : "false") << "}";
    }
    video << "]}";
    write_text(data.report_dir / "video-checkpoints.json", video.str());

    std::ostringstream audio;
    audio << "{\"oracle_sha256\":\"" << audio_digest << "\",\"expected_sha256\":\"" << json_escape(data.profile.get("expect.audio_sha256"))
          << "\",\"sample_rate\":44100,\"channels\":2,\"frames\":" << context.audio_frames << ",\"samples\":" << context.audio_samples.size() << "}";
    write_text(data.report_dir / "audio-checkpoints.json", audio.str());

    std::ostringstream persistence_report;
    persistence_report << "{\"native_save\":" << data.native_save_json << ",\"save_state\":" << data.save_state_json << "}";
    write_text(data.report_dir / "persistence.json", persistence_report.str());

    std::ostringstream capabilities;
    capabilities << "{\"content\":" << capability(true, data.fixture.size() > 0, true, context.failures.empty(), context.failures.empty() ? "passed" : "failed", "synthetic memory-backed content")
                 << ",\"video\":" << capability(true, context.pixel_format_seen, true, video_passed, video_passed ? "passed" : "failed", "software XRGB8888 callbacks")
                 << ",\"audio\":" << capability(true, context.audio_frames > 0, true, audio_passed, audio_passed ? "passed" : "failed", "normalized stereo signed-16 batch callbacks")
                 << ",\"input\":" << capability(true, context.input_polls_this_run > 0, true, context.failures.empty(), context.failures.empty() ? "passed" : "failed", "deterministic poll/state trace")
                 << ",\"directories\":" << capability(true, context.system_dir_seen && context.save_dir_seen, true, context.system_dir_seen && context.save_dir_seen, context.system_dir_seen && context.save_dir_seen ? "passed" : "failed", "controlled system/save directories")
                 << ",\"thread_contract\":" << capability(true, true, true, thread_passed, thread_passed ? "passed" : "failed", "all entry points and callbacks confined to one thread") << "}";
    write_text(data.report_dir / "capabilities.json", capabilities.str());

    std::ostringstream run;
    run << "{\"schema_version\":1,\"profile\":\"" << json_escape(report_name(data.profile_path)) << "\",\"core\":\"" << json_escape(report_name(data.core_path))
        << "\",\"fixture_sha256\":\"" << data.fixture_digest << "\",\"video_sha256\":\"" << video_digest << "\",\"audio_sha256\":\"" << audio_digest
        << "\",\"failure_count\":" << context.failures.size() << ",\"thread_violation_count\":" << context.thread_violations << ",\"status\":\"" << (overall ? "passed" : "failed") << "\"}";
    write_text(data.report_dir / "run.json", run.str());
    write_text(data.report_dir / "crash.json", "{\"status\":\"not_observed\",\"worker_process\":false,\"last_event_count\":" + std::to_string(context.events.size()) + "}");
    write_text(data.report_dir / "environment.json", "{\"target_abi\":\"" + platform_abi() + "\",\"host_build_id\":\"corelab-runner-cxx17-v1\",\"native_page_size_check\":\"not_applicable\",\"fixture_is_generated\":true}");

    std::ostringstream report;
    report << "{\"schema_version\":1,\"run\":{\"profile\":\"" << json_escape(report_name(data.profile_path))
           << "\",\"host_build_id\":\"corelab-runner-cxx17-v1\",\"target_abi\":\"" << platform_abi()
           << "\",\"fixture_id\":\"synthetic:" << data.profile.get("content.seed") << ":" << data.fixture.size()
           << "\",\"fixture_sha256\":\"" << data.fixture_digest << "\"},\"core\":{\"id\":\"" << json_escape(data.profile.get("core.id"))
           << "\",\"library_name\":\"" << json_escape(data.core_name) << "\",\"library_version\":\"" << json_escape(data.core_version)
           << "\",\"artifact_sha256\":\"" << data.artifact_digest << "\",\"api_version\":" << data.core_api
           << ",\"need_fullpath\":" << (data.core_need_fullpath ? "true" : "false") << ",\"status\":\"" << data.core_status << "\"}"
           << ",\"content\":" << capability(true, !data.fixture.empty(), true, !data.fixture.empty() && context.failures.empty(), !data.fixture.empty() && context.failures.empty() ? "passed" : "failed", "generated synthetic fixture; memory-backed")
           << ",\"video\":" << capability(true, context.pixel_format_seen, true, video_passed, video_passed ? "passed" : "failed", "software XRGB8888")
           << ",\"audio\":" << capability(true, context.audio_frames > 0, true, audio_passed, audio_passed ? "passed" : "failed", "batch callback normalized to stereo signed-16")
           << ",\"input\":" << capability(true, context.input_polls_this_run > 0, true, context.failures.empty(), context.failures.empty() ? "passed" : "failed", "trace-driven input")
           << ",\"persistence\":{\"native_save\":" << data.native_save_json << ",\"save_state\":" << data.save_state_json << "}"
           << ",\"thread_contract\":" << capability(true, true, true, thread_passed, thread_passed ? "passed" : "failed", "single emulation thread")
           << ",\"result\":{\"status\":\"" << (overall ? "passed" : "failed") << "\",\"failure_count\":" << context.failures.size()
           << ",\"violation_count\":" << context.thread_violations << "}}";
    write_text(data.report_dir / "capability-report.json", report.str());

    std::cout << "CORELAB " << (overall ? "PASS" : "FAIL") << "\n"
              << "  report: " << (data.report_dir / "capability-report.json") << "\n"
              << "  video_sha256: " << video_digest << "\n"
              << "  audio_sha256: " << audio_digest << "\n"
              << "  failures: " << context.failures.size() << "\n";
    if (!overall) {
        for (const auto &failure : context.failures) std::cerr << "  failure: " << failure << "\n";
    }
}

} // namespace

int main(int argc, char **argv) {
    try {
        fs::path profile_path;
        fs::path core_path;
        fs::path report_dir = "corelab-report";
        bool print_goldens = false;
        for (int i = 1; i < argc; ++i) {
            const std::string argument = argv[i];
            auto next = [&](const char *name) -> fs::path {
                if (i + 1 >= argc) throw std::runtime_error(std::string("missing value for ") + name);
                return fs::path(argv[++i]);
            };
            if (argument == "--profile") profile_path = next("--profile");
            else if (argument == "--core") core_path = next("--core");
            else if (argument == "--report-dir") report_dir = next("--report-dir");
            else if (argument == "--print-goldens") print_goldens = true;
            else if (argument == "--help") {
                std::cout << "usage: corelab_runner --profile PROFILE --core CORE [--report-dir DIR] [--print-goldens]\n";
                return 0;
            } else throw std::runtime_error("unknown argument: " + argument);
        }
        if (profile_path.empty() || core_path.empty()) throw std::runtime_error("--profile and --core are required");

        RunData data;
        data.profile_path = profile_path;
        data.core_path = core_path;
        data.report_dir = report_dir;
        data.profile = read_profile(profile_path);
        if (data.profile.get("content.mode") != "synthetic") throw std::runtime_error("only synthetic content.mode is supported by this harness");
        data.fixture = make_fixture(static_cast<uint64_t>(std::stoull(data.profile.get("content.seed"))), static_cast<size_t>(data.profile.integer("content.bytes")));
        data.fixture_digest = sha256_bytes(data.fixture);
        data.artifact_digest = sha256_file(core_path);
        fs::create_directories(data.report_dir);

        HostContext context;
        context.profile = data.profile;
        context.input_trace = parse_trace(data.profile.get("input.trace"));
        const std::string expected_fixture_digest = data.profile.get("expect.fixture_sha256");
        if (!expected_fixture_digest.empty() && expected_fixture_digest != data.fixture_digest) context.failure("synthetic fixture digest does not match manifest");
        current_context = &context;
        context.event("session", "start");

        CoreApi core;
        if (!core.load(core_path, context)) {
            data.core_status = "blocked";
            write_reports(data, context);
            current_context = nullptr;
            core.close();
            return 2;
        }

        call_entry(context, "retro_api_version", [&] { data.core_api = core.api_version(); });
        if (data.core_api != static_cast<unsigned>(data.profile.integer("core.api_version", RETRO_API_VERSION))) context.failure("incompatible Libretro API version");
        call_entry(context, "retro_set_environment", [&] { core.set_environment(host_environment); });
        call_entry(context, "retro_set_video_refresh", [&] { core.set_video_refresh(host_video); });
        call_entry(context, "retro_set_audio_sample", [&] { core.set_audio_sample(host_audio_sample); });
        call_entry(context, "retro_set_audio_sample_batch", [&] { core.set_audio_sample_batch(host_audio_batch); });
        call_entry(context, "retro_set_input_poll", [&] { core.set_input_poll(host_input_poll); });
        call_entry(context, "retro_set_input_state", [&] { core.set_input_state(host_input_state); });
        call_entry(context, "retro_init", [&] { core.init(); });
        context.initialized = true;

        retro_system_info system_info{};
        call_entry(context, "retro_get_system_info", [&] { core.get_system_info(&system_info); });
        data.core_name = system_info.library_name == nullptr ? "unknown" : system_info.library_name;
        data.core_version = system_info.library_version == nullptr ? "unknown" : system_info.library_version;
        data.core_need_fullpath = system_info.need_fullpath;
        if (system_info.need_fullpath) context.failure("dummy profile requires memory-backed content but core requires full path");

        retro_game_info game{};
        game.path = "corelab-synthetic.clfixture";
        game.data = data.fixture.data();
        game.size = data.fixture.size();
        call_entry(context, "retro_load_game", [&] {
            context.loaded = core.load_game(&game);
        });
        if (!context.loaded) context.failure("retro_load_game rejected the synthetic fixture");

        retro_system_av_info av_info{};
        if (context.loaded) {
            call_entry(context, "retro_get_system_av_info", [&] { core.get_system_av_info(&av_info); });
            if (av_info.geometry.base_width == 0 || av_info.geometry.base_height == 0 || av_info.timing.sample_rate <= 0) context.failure("invalid AV info");
            run_frames(core, context, static_cast<unsigned>(data.profile.integer("execution.frames")), true);
            exercise_save_state(core, context, data);
            exercise_native_save(core, context, data);
        }

        if (context.loaded) {
            call_entry(context, "retro_unload_game", [&] { core.unload_game(); });
            context.loaded = false;
        }
        if (context.initialized) {
            call_entry(context, "retro_deinit", [&] { core.deinit(); });
            context.deinitialized = true;
        }
        data.core_status = context.failures.empty() ? "passed" : "failed";
        write_reports(data, context);
        current_context = nullptr;
        core.close();
        if (print_goldens) {
            std::cout << "GOLDEN video_sha256=" << sha256_bytes(context.oracle_video_bytes) << "\n"
                      << "GOLDEN audio_sha256=" << sha256_bytes(context.oracle_audio_bytes) << "\n";
            return 0;
        }
        return context.failures.empty() ? 0 : 1;
    } catch (const std::exception &error) {
        std::cerr << "CORELAB ERROR: " << error.what() << "\n";
        return 3;
    }
}
