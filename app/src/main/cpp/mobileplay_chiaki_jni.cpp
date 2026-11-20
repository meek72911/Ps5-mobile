// MobilePlay Native JNI Bridge for upstream chiaki-ng (pinned v1.10.1 / commit 7ea0b0c).
// Licensed under GNU AGPL-3.0 when linked against libchiaki (see /COPYING).
//
// Security & Thread-Safety Guarantees:
// - Never logs PSN Account IDs, PINs, rp_regist_key, or rp_key/morning bytes.
// - Rejects zero-filled or wrong-length cryptographic session credentials before session init.
// - Uses ChiakiOpusDecoder to decode incoming PS5 Opus audio packets into 16-bit PCM.
// - Protects session lifecycle with mutex + atomic generation; deletes JNI global refs ONLY
//   after chiaki_session_join() guarantees zero active worker threads.

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>

#ifdef MOBILEPLAY_WITH_LIBCHIAKI
#include <chiaki/session.h>
#include <chiaki/regist.h>
#include <chiaki/controller.h>
#include <chiaki/opusdecoder.h>
#include <chiaki/log.h>
#endif

#define MP_LOG_TAG "MobilePlayChiakiJNI"
#define MP_LOGI(...) __android_log_print(ANDROID_LOG_INFO, MP_LOG_TAG, __VA_ARGS__)
#define MP_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, MP_LOG_TAG, __VA_ARGS__)

namespace {

JavaVM* g_vm = nullptr;

constexpr jint ERR_NATIVE_UNAVAILABLE = -1;
constexpr jint ERR_ALREADY_ACTIVE = -2;
constexpr jint ERR_INVALID_ARGS = -3;
constexpr jint ERR_ZERO_CREDENTIALS = -4;
constexpr jint ERR_CALLBACK_LOOKUP = -5;
constexpr jint ERR_REGIST_TIMEOUT = -6;
constexpr jint ERR_REGIST_FAILED = -7;

bool isAllZero(const uint8_t* data, size_t len) {
    uint8_t acc = 0;
    for (size_t i = 0; i < len; ++i) {
        acc |= data[i];
    }
    return acc == 0;
}

void secureZero(void* ptr, size_t len) {
    volatile uint8_t* p = reinterpret_cast<volatile uint8_t*>(ptr);
    while (len--) {
        *p++ = 0;
    }
}

int64_t monotonicMicros() {
    auto now = std::chrono::steady_clock::now().time_since_epoch();
    return std::chrono::duration_cast<std::chrono::microseconds>(now).count();
}

JNIEnv* attachCurrentThread(bool* did_attach) {
    *did_attach = false;
    if (!g_vm) return nullptr;
    JNIEnv* env = nullptr;
    jint res = g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (g_vm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
            *did_attach = true;
            return env;
        }
        return nullptr;
    }
    return (res == JNI_OK) ? env : nullptr;
}

void detachIfNeeded(bool did_attach) {
    if (did_attach && g_vm) {
        g_vm->DetachCurrentThread();
    }
}

struct NativeSessionContext {
    std::mutex session_lock;
    std::mutex regist_lock;
    std::atomic<bool> active{false};
    std::atomic<bool> regist_in_progress{false};
    std::atomic<uint64_t> generation{0};
    std::atomic<uint32_t> audio_channels{2};

    jobject callback_global = nullptr;
    jmethodID on_video_packet = nullptr;
    jmethodID on_audio_header = nullptr;
    jmethodID on_audio_pcm = nullptr;
    jmethodID on_session_event = nullptr;

#ifdef MOBILEPLAY_WITH_LIBCHIAKI
    ChiakiLog logger{};
    ChiakiSession session{};
    ChiakiOpusDecoder opus_decoder{};
    bool session_initialized = false;
    bool opus_initialized = false;
#endif
};

NativeSessionContext g_ctx;

#ifdef MOBILEPLAY_WITH_LIBCHIAKI

void chiakiLogCallback(ChiakiLogLevel level, const char* msg, void*) {
    // Route non-verbose libchiaki protocol diagnostics to logcat without logging credentials.
    int prio = ANDROID_LOG_INFO;
    if (level & CHIAKI_LOG_ERROR) prio = ANDROID_LOG_ERROR;
    else if (level & CHIAKI_LOG_WARNING) prio = ANDROID_LOG_WARN;
    __android_log_print(prio, "libchiaki", "%s", msg ? msg : "");
}

// ---- Registration Synchronization Context ----

struct RegistSyncContext {
    std::mutex mutex;
    std::condition_variable cv;
    bool finished = false;
    bool success = false;
    int error_code = 0;
    std::string error_message;
    ChiakiRegisteredHost host{};
};

void registEventCallback(ChiakiRegistEvent* event, void* user) {
    auto* reg = reinterpret_cast<RegistSyncContext*>(user);
    if (!reg || !event) return;

    std::lock_guard<std::mutex> lock(reg->mutex);
    switch (event->type) {
        case CHIAKI_REGIST_EVENT_TYPE_FINISHED_SUCCESS:
            if (event->registered_host) {
                reg->host = *event->registered_host;
                reg->success = true;
            } else {
                reg->success = false;
                reg->error_code = ERR_REGIST_FAILED;
                reg->error_message = "PS5 registration succeeded without host credentials.";
            }
            reg->finished = true;
            reg->cv.notify_all();
            break;

        case CHIAKI_REGIST_EVENT_TYPE_FINISHED_FAILED:
            reg->success = false;
            reg->error_code = ERR_REGIST_FAILED;
            reg->error_message = "PS5 rejected registration. Verify 8-digit PIN (valid 5 min) and PSN Account ID.";
            reg->finished = true;
            reg->cv.notify_all();
            break;

        case CHIAKI_REGIST_EVENT_TYPE_FINISHED_CANCELED:
            reg->success = false;
            reg->error_code = ERR_REGIST_TIMEOUT;
            reg->error_message = "PS5 registration was canceled or timed out.";
            reg->finished = true;
            reg->cv.notify_all();
            break;

        default:
            break;
    }
}

// ---- Active Streaming Session Callbacks ----

bool videoSampleCallback(uint8_t* buf, size_t buf_size, int32_t frames_lost, bool frame_recovered, void* user) {
    (void)frames_lost;
    (void)frame_recovered;
    auto* ctx = reinterpret_cast<NativeSessionContext*>(user);
    if (!ctx || !ctx->active.load(std::memory_order_acquire) || !buf || buf_size == 0) {
        return false;
    }

    bool attached = false;
    JNIEnv* env = attachCurrentThread(&attached);
    if (!env || !ctx->callback_global || !ctx->on_video_packet) {
        detachIfNeeded(attached);
        return false;
    }

    jbyteArray arr = env->NewByteArray(static_cast<jsize>(buf_size));
    if (arr) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(buf_size), reinterpret_cast<const jbyte*>(buf));
        env->CallVoidMethod(
            ctx->callback_global,
            ctx->on_video_packet,
            arr,
            static_cast<jlong>(monotonicMicros())
        );
        env->DeleteLocalRef(arr);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    detachIfNeeded(attached);
    return true;
}

void opusSettingsCallback(uint32_t channels, uint32_t rate, void* user) {
    auto* ctx = reinterpret_cast<NativeSessionContext*>(user);
    if (!ctx || !ctx->active.load(std::memory_order_acquire)) return;

    const uint32_t safe_channels = (channels >= 1 && channels <= 8) ? channels : 2;
    ctx->audio_channels.store(safe_channels, std::memory_order_release);

    bool attached = false;
    JNIEnv* env = attachCurrentThread(&attached);
    if (env && ctx->callback_global && ctx->on_audio_header) {
        env->CallVoidMethod(
            ctx->callback_global,
            ctx->on_audio_header,
            static_cast<jint>(safe_channels),
            static_cast<jint>(rate)
        );
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    detachIfNeeded(attached);
}

void opusFrameCallback(int16_t* buf, size_t samples_count, void* user) {
    auto* ctx = reinterpret_cast<NativeSessionContext*>(user);
    if (!ctx || !ctx->active.load(std::memory_order_acquire) || !buf || samples_count == 0) return;

    const uint32_t channels = ctx->audio_channels.load(std::memory_order_acquire);
    const size_t total_shorts = samples_count * static_cast<size_t>(channels);
    if (total_shorts == 0 || total_shorts > 131072) return;

    bool attached = false;
    JNIEnv* env = attachCurrentThread(&attached);
    if (env && ctx->callback_global && ctx->on_audio_pcm) {
        jshortArray pcm = env->NewShortArray(static_cast<jsize>(total_shorts));
        if (pcm) {
            env->SetShortArrayRegion(pcm, 0, static_cast<jsize>(total_shorts), buf);
            env->CallVoidMethod(ctx->callback_global, ctx->on_audio_pcm, pcm);
            env->DeleteLocalRef(pcm);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    detachIfNeeded(attached);
}

void sessionEventCallback(ChiakiEvent* event, void* user) {
    auto* ctx = reinterpret_cast<NativeSessionContext*>(user);
    if (!ctx || !event) return;

    // If the session is quitting, disable further media callbacks immediately.
    if (event->type == CHIAKI_EVENT_QUIT) {
        ctx->active.store(false, std::memory_order_release);
    }

    bool attached = false;
    JNIEnv* env = attachCurrentThread(&attached);
    if (env && ctx->callback_global && ctx->on_session_event) {
        const char* reason = "";
        if (event->type == CHIAKI_EVENT_QUIT) {
            if (event->quit.reason_str && event->quit.reason_str[0] != '\0') {
                reason = event->quit.reason_str;
            } else {
                reason = chiaki_quit_reason_string(event->quit.reason);
            }
        }
        jstring msg = env->NewStringUTF(reason ? reason : "");
        if (msg) {
            env->CallVoidMethod(
                ctx->callback_global,
                ctx->on_session_event,
                static_cast<jint>(event->type),
                msg
            );
            env->DeleteLocalRef(msg);
        }
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    detachIfNeeded(attached);
}

void teardownSessionLocked(JNIEnv* env) {
    // Signal callbacks to drop immediately BEFORE joining worker threads.
    g_ctx.active.store(false, std::memory_order_release);
    g_ctx.generation.fetch_add(1, std::memory_order_acq_rel);

    if (g_ctx.session_initialized) {
        // Send an idle DualSense state prior to shutdown so PS5 never sees stuck inputs.
        ChiakiControllerState idle{};
        chiaki_controller_state_set_idle(&idle);
        chiaki_session_set_controller_state(&g_ctx.session, &idle);

        chiaki_session_stop(&g_ctx.session);
        chiaki_session_join(&g_ctx.session);
        chiaki_session_fini(&g_ctx.session);
        g_ctx.session_initialized = false;
    }

    if (g_ctx.opus_initialized) {
        chiaki_opus_decoder_fini(&g_ctx.opus_decoder);
        g_ctx.opus_initialized = false;
    }

    // Safe to delete global ref ONLY after chiaki_session_join() guarantees zero active workers.
    if (g_ctx.callback_global && env) {
        env->DeleteGlobalRef(g_ctx.callback_global);
        g_ctx.callback_global = nullptr;
    }
    g_ctx.on_video_packet = nullptr;
    g_ctx.on_audio_header = nullptr;
    g_ctx.on_audio_pcm = nullptr;
    g_ctx.on_session_event = nullptr;
}

#endif // MOBILEPLAY_WITH_LIBCHIAKI

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_vm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_example_mobileplay_remoteplay_ChiakiNativeBridge_nativeGetBuildInfo(JNIEnv* env, jobject) {
#ifdef MOBILEPLAY_WITH_LIBCHIAKI
    return env->NewStringUTF("libchiaki-ng " MOBILEPLAY_CHIAKI_PINNED_TAG " JNI active (arm64-v8a + Opus)");
#else
    return env->NewStringUTF("libchiaki-ng headers not linked");
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_mobileplay_remoteplay_ChiakiNativeBridge_nativeRegisterConsole(
    JNIEnv* env,
    jobject,
    jstring jHost,
    jbyteArray jPsnAccountId,
    jint pin,
    jint timeoutMs,
    jobject callbackObj
) {
#ifdef MOBILEPLAY_WITH_LIBCHIAKI
    if (!jHost || !jPsnAccountId || !callbackObj) {
        return ERR_INVALID_ARGS;
    }
    if (env->GetArrayLength(jPsnAccountId) != CHIAKI_PSN_ACCOUNT_ID_SIZE) {
        return ERR_INVALID_ARGS;
    }

    std::lock_guard<std::mutex> guard(g_ctx.regist_lock);
    if (g_ctx.regist_in_progress.exchange(true)) {
        return ERR_ALREADY_ACTIVE;
    }

    jclass cbClass = env->GetObjectClass(callbackObj);
    jmethodID onSuccess = env->GetMethodID(
        cbClass,
        "onRegistSuccess",
        "(Ljava/lang/String;Ljava/lang/String;[B[BI)V"
    );
    jmethodID onError = env->GetMethodID(
        cbClass,
        "onRegistError",
        "(ILjava/lang/String;)V"
    );
    env->DeleteLocalRef(cbClass);
    if (!onSuccess || !onError) {
        g_ctx.regist_in_progress.store(false);
        return ERR_CALLBACK_LOOKUP;
    }

    const char* hostChars = env->GetStringUTFChars(jHost, nullptr);
    if (!hostChars) {
        g_ctx.regist_in_progress.store(false);
        return ERR_INVALID_ARGS;
    }

    ChiakiRegistInfo info{};
    info.target = CHIAKI_TARGET_PS5_1;
    info.host = hostChars;
    info.broadcast = false;
    info.pin = static_cast<uint32_t>(pin);
    info.psn_online_id = nullptr;
    env->GetByteArrayRegion(
        jPsnAccountId,
        0,
        CHIAKI_PSN_ACCOUNT_ID_SIZE,
        reinterpret_cast<jbyte*>(info.psn_account_id)
    );

    ChiakiLog regLog{};
    chiaki_log_init(&regLog, CHIAKI_LOG_ERROR | CHIAKI_LOG_WARNING, chiakiLogCallback, nullptr);

    RegistSyncContext syncCtx{};
    ChiakiRegist regist{};
    ChiakiErrorCode startErr = chiaki_regist_start(&regist, &regLog, &info, registEventCallback, &syncCtx);

    env->ReleaseStringUTFChars(jHost, hostChars);
    secureZero(info.psn_account_id, sizeof(info.psn_account_id));

    if (startErr != CHIAKI_ERR_SUCCESS) {
        g_ctx.regist_in_progress.store(false);
        const char* errText = chiaki_error_string(startErr);
        jstring jErr = env->NewStringUTF(errText ? errText : "chiaki_regist_start failed");
        if (jErr) {
            env->CallVoidMethod(callbackObj, onError, static_cast<jint>(startErr), jErr);
            env->DeleteLocalRef(jErr);
        }
        return static_cast<jint>(startErr);
    }

    const int safeTimeout = std::clamp(static_cast<int>(timeoutMs), 3000, 30000);
    {
        std::unique_lock<std::mutex> lock(syncCtx.mutex);
        bool completed = syncCtx.cv.wait_for(
            lock,
            std::chrono::milliseconds(safeTimeout),
            [&syncCtx]() { return syncCtx.finished; }
        );
        if (!completed) {
            chiaki_regist_stop(&regist);
            syncCtx.success = false;
            syncCtx.error_code = ERR_REGIST_TIMEOUT;
            syncCtx.error_message = "Timed out waiting for PS5 registration response on local Wi-Fi.";
        }
    }

    chiaki_regist_fini(&regist);
    g_ctx.regist_in_progress.store(false);

    if (syncCtx.success) {
        const auto& h = syncCtx.host;
        const size_t registKeyLen = sizeof(h.rp_regist_key);
        const size_t rpKeyLen = sizeof(h.rp_key);

        if (isAllZero(reinterpret_cast<const uint8_t*>(h.rp_regist_key), registKeyLen) ||
            isAllZero(h.rp_key, rpKeyLen)) {
            jstring jErr = env->NewStringUTF("PS5 registration returned empty cryptographic keys.");
            if (jErr) {
                env->CallVoidMethod(callbackObj, onError, ERR_ZERO_CREDENTIALS, jErr);
                env->DeleteLocalRef(jErr);
            }
            secureZero(&syncCtx.host, sizeof(syncCtx.host));
            return ERR_ZERO_CREDENTIALS;
        }

        jbyteArray jRegistKey = env->NewByteArray(static_cast<jsize>(registKeyLen));
        jbyteArray jRpKey = env->NewByteArray(static_cast<jsize>(rpKeyLen));
        if (jRegistKey && jRpKey) {
            env->SetByteArrayRegion(
                jRegistKey, 0, static_cast<jsize>(registKeyLen),
                reinterpret_cast<const jbyte*>(h.rp_regist_key)
            );
            env->SetByteArrayRegion(
                jRpKey, 0, static_cast<jsize>(rpKeyLen),
                reinterpret_cast<const jbyte*>(h.rp_key)
            );

            char macBuf[32] = {0};
            snprintf(
                macBuf, sizeof(macBuf),
                "%02x:%02x:%02x:%02x:%02x:%02x",
                h.server_mac.mac[0], h.server_mac.mac[1], h.server_mac.mac[2],
                h.server_mac.mac[3], h.server_mac.mac[4], h.server_mac.mac[5]
            );

            jstring jNickname = env->NewStringUTF(h.server_nickname);
            jstring jMac = env->NewStringUTF(macBuf);
            env->CallVoidMethod(
                callbackObj,
                onSuccess,
                jNickname,
                jMac,
                jRegistKey,
                jRpKey,
                static_cast<jint>(h.rp_key_type)
            );
            if (jNickname) env->DeleteLocalRef(jNickname);
            if (jMac) env->DeleteLocalRef(jMac);
        }
        if (jRegistKey) env->DeleteLocalRef(jRegistKey);
        if (jRpKey) env->DeleteLocalRef(jRpKey);
        secureZero(&syncCtx.host, sizeof(syncCtx.host));
        return 0;
    } else {
        jstring jErr = env->NewStringUTF(syncCtx.error_message.c_str());
        if (jErr) {
            env->CallVoidMethod(callbackObj, onError, static_cast<jint>(syncCtx.error_code), jErr);
            env->DeleteLocalRef(jErr);
        }
        secureZero(&syncCtx.host, sizeof(syncCtx.host));
        return static_cast<jint>(syncCtx.error_code);
    }
#else
    (void)env; (void)jHost; (void)jPsnAccountId; (void)pin; (void)timeoutMs; (void)callbackObj;
    return ERR_NATIVE_UNAVAILABLE;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_mobileplay_remoteplay_ChiakiNativeBridge_nativeStartSession(
    JNIEnv* env,
    jobject,
    jstring jHost,
    jbyteArray jRegistKey,
    jbyteArray jMorning,
    jint width,
    jint height,
    jint fps,
    jint bitrateKbps,
    jboolean useHevc,
    jobject callbackObj
) {
#ifdef MOBILEPLAY_WITH_LIBCHIAKI
    if (!jHost || !jRegistKey || !jMorning || !callbackObj) {
        return ERR_INVALID_ARGS;
    }
    if (env->GetArrayLength(jRegistKey) != CHIAKI_SESSION_AUTH_SIZE ||
        env->GetArrayLength(jMorning) != 16) {
        return ERR_INVALID_ARGS;
    }

    std::lock_guard<std::mutex> guard(g_ctx.session_lock);
    if (g_ctx.session_initialized) {
        teardownSessionLocked(env);
    }

    uint8_t registKeyBytes[CHIAKI_SESSION_AUTH_SIZE]{};
    uint8_t morningBytes[16]{};
    env->GetByteArrayRegion(jRegistKey, 0, CHIAKI_SESSION_AUTH_SIZE, reinterpret_cast<jbyte*>(registKeyBytes));
    env->GetByteArrayRegion(jMorning, 0, 16, reinterpret_cast<jbyte*>(morningBytes));

    if (isAllZero(registKeyBytes, sizeof(registKeyBytes)) ||
        isAllZero(morningBytes, sizeof(morningBytes))) {
        secureZero(registKeyBytes, sizeof(registKeyBytes));
        secureZero(morningBytes, sizeof(morningBytes));
        MP_LOGE("Refusing session start with zero-filled registration credentials.");
        return ERR_ZERO_CREDENTIALS;
    }

    jclass cls = env->GetObjectClass(callbackObj);
    jmethodID onVideo = env->GetMethodID(cls, "onVideoPacket", "([BJ)V");
    jmethodID onAudioHeader = env->GetMethodID(cls, "onAudioHeader", "(II)V");
    jmethodID onAudioPcm = env->GetMethodID(cls, "onAudioPcm", "([S)V");
    jmethodID onEvent = env->GetMethodID(cls, "onSessionEvent", "(ILjava/lang/String;)V");
    env->DeleteLocalRef(cls);

    if (!onVideo || !onAudioHeader || !onAudioPcm || !onEvent) {
        secureZero(registKeyBytes, sizeof(registKeyBytes));
        secureZero(morningBytes, sizeof(morningBytes));
        return ERR_CALLBACK_LOOKUP;
    }

    g_ctx.callback_global = env->NewGlobalRef(callbackObj);
    g_ctx.on_video_packet = onVideo;
    g_ctx.on_audio_header = onAudioHeader;
    g_ctx.on_audio_pcm = onAudioPcm;
    g_ctx.on_session_event = onEvent;
    g_ctx.audio_channels.store(2, std::memory_order_release);

    const char* host = env->GetStringUTFChars(jHost, nullptr);
    ChiakiConnectInfo info{};
    info.ps5 = true;
    info.host = host;
    info.enable_dualsense = true;
    info.video_profile.width = static_cast<unsigned int>(width);
    info.video_profile.height = static_cast<unsigned int>(height);
    info.video_profile.max_fps = static_cast<unsigned int>(fps);
    info.video_profile.bitrate = static_cast<unsigned int>(bitrateKbps);
    info.video_profile.codec = useHevc ? CHIAKI_CODEC_H265 : CHIAKI_CODEC_H264;
    std::memcpy(info.regist_key, registKeyBytes, CHIAKI_SESSION_AUTH_SIZE);
    std::memcpy(info.morning, morningBytes, 16);

    secureZero(registKeyBytes, sizeof(registKeyBytes));
    secureZero(morningBytes, sizeof(morningBytes));

    chiaki_log_init(&g_ctx.logger, CHIAKI_LOG_ERROR | CHIAKI_LOG_WARNING, chiakiLogCallback, nullptr);
    ChiakiErrorCode err = chiaki_session_init(&g_ctx.session, &info, &g_ctx.logger);
    env->ReleaseStringUTFChars(jHost, host);
    secureZero(info.regist_key, sizeof(info.regist_key));
    secureZero(info.morning, sizeof(info.morning));

    if (err != CHIAKI_ERR_SUCCESS) {
        MP_LOGE("chiaki_session_init failed: %d", static_cast<int>(err));
        teardownSessionLocked(env);
        return static_cast<jint>(err);
    }
    g_ctx.session_initialized = true;

    // Wire genuine Opus decoder so incoming PS5 Opus audio is decoded to 16-bit PCM.
    chiaki_opus_decoder_init(&g_ctx.opus_decoder, &g_ctx.logger);
    g_ctx.opus_initialized = true;
    chiaki_opus_decoder_set_cb(&g_ctx.opus_decoder, opusSettingsCallback, opusFrameCallback, &g_ctx);

    ChiakiAudioSink audioSink{};
    chiaki_opus_decoder_get_sink(&g_ctx.opus_decoder, &audioSink);
    chiaki_session_set_audio_sink(&g_ctx.session, &audioSink);

    chiaki_session_set_video_sample_cb(&g_ctx.session, videoSampleCallback, &g_ctx);
    chiaki_session_set_event_cb(&g_ctx.session, sessionEventCallback, &g_ctx);

    g_ctx.active.store(true, std::memory_order_release);
    err = chiaki_session_start(&g_ctx.session);
    if (err != CHIAKI_ERR_SUCCESS) {
        MP_LOGE("chiaki_session_start failed: %d", static_cast<int>(err));
        teardownSessionLocked(env);
        return static_cast<jint>(err);
    }
    return 0;
#else
    (void)env; (void)jHost; (void)jRegistKey; (void)jMorning;
    (void)width; (void)height; (void)fps; (void)bitrateKbps; (void)useHevc; (void)callbackObj;
    return ERR_NATIVE_UNAVAILABLE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_mobileplay_remoteplay_ChiakiNativeBridge_nativeSendControllerState(
    JNIEnv*,
    jobject,
    jint buttons,
    jint l2,
    jint r2,
    jshort leftX,
    jshort leftY,
    jshort rightX,
    jshort rightY,
    jboolean touchActive,
    jint touchX,
    jint touchY
) {
#ifdef MOBILEPLAY_WITH_LIBCHIAKI
    if (!g_ctx.active.load(std::memory_order_acquire)) return;
    ChiakiControllerState state{};
    chiaki_controller_state_set_idle(&state);
    state.buttons = static_cast<uint32_t>(buttons);
    state.l2_state = static_cast<uint8_t>(std::clamp(static_cast<int>(l2), 0, 255));
    state.r2_state = static_cast<uint8_t>(std::clamp(static_cast<int>(r2), 0, 255));
    state.left_x = leftX;
    state.left_y = leftY;
    state.right_x = rightX;
    state.right_y = rightY;
    if (touchActive) {
        int id = chiaki_controller_state_start_touch(
            &state,
            static_cast<uint16_t>(std::clamp(static_cast<int>(touchX), 0, CHIAKI_CONTROLLER_TOUCHPAD_WIDTH - 1)),
            static_cast<uint16_t>(std::clamp(static_cast<int>(touchY), 0, CHIAKI_CONTROLLER_TOUCHPAD_HEIGHT - 1))
        );
        (void)id;
    }
    chiaki_session_set_controller_state(&g_ctx.session, &state);
#else
    (void)buttons; (void)l2; (void)r2; (void)leftX; (void)leftY; (void)rightX; (void)rightY;
    (void)touchActive; (void)touchX; (void)touchY;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_mobileplay_remoteplay_ChiakiNativeBridge_nativeStopSession(JNIEnv* env, jobject) {
#ifdef MOBILEPLAY_WITH_LIBCHIAKI
    std::lock_guard<std::mutex> guard(g_ctx.session_lock);
    teardownSessionLocked(env);
#else
    (void)env;
#endif
}
