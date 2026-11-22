// MobilePlay Native JNI Bridge for upstream chiaki-ng (pinned v1.10.1 / commit f30da0a).
// Licensed under GNU AGPL-3.0 when linked against libchiaki (see /COPYING).
//
// Security & Thread-Safety Guarantees:
// - Never logs PSN Account IDs, PINs, rp_regist_key, or rp_key/morning bytes.
// - Rejects zero-filled or wrong-length cryptographic session credentials before session init.
// - Uses ChiakiOpusDecoder to decode incoming PS5 Opus audio packets into 16-bit PCM.
// - Protects session lifecycle with a mutex and deletes JNI global refs
//   after chiaki_session_join() guarantees zero active worker threads.

#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iterator>
#include <limits>
#include <mutex>
#include <string>

#ifdef MOBILEPLAY_WITH_LIBCHIAKI
#include <chiaki/session.h>
#include <chiaki/regist.h>
#include <chiaki/controller.h>
#include <chiaki/discovery.h>
#include <chiaki/opusdecoder.h>
#include <chiaki/opusencoder.h>
#include <chiaki/log.h>
#include <opus/opus.h>
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
constexpr jint EVENT_SESSION_QUIT = 1;
constexpr jint EVENT_SESSION_CONNECTED = 2;
constexpr jint EVENT_LOGIN_PIN = 3;
constexpr jint EVENT_KEYBOARD_OPEN = 4;
constexpr jint EVENT_KEYBOARD_UPDATE = 5;
constexpr jint EVENT_KEYBOARD_CLOSE = 6;
constexpr int TOUCHPAD_WIDTH = 1920;
constexpr int TOUCHPAD_HEIGHT = 942;

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
    std::atomic<bool> transport_connected{false};
    std::atomic<bool> login_pin_pending{false};
    std::atomic<bool> keyboard_open{false};
    std::atomic<uint64_t> video_bytes{0};
    int64_t bitrate_sample_us = 0;
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
    ChiakiOpusEncoder mic_encoder{};
    ChiakiControllerState controller{};
    bool session_initialized = false;
    bool session_started = false;
    bool opus_initialized = false;
    bool mic_encoder_initialized = false;
    bool mic_connected = false;
    bool mic_enabled = false;
#endif
};

NativeSessionContext g_ctx;

#ifdef MOBILEPLAY_WITH_LIBCHIAKI

// The pinned library's public mic helpers write directly on the control socket.
// Queue the same control messages to serialize encryption and writes on its control thread.
constexpr uint16_t MIC_CONNECT_MESSAGE = 0x30;
constexpr uint16_t MIC_TOGGLE_MESSAGE = 0x36;

ChiakiErrorCode queueMicrophoneMute(ChiakiSession* session, bool muted) {
    const uint8_t payload[] = {0, 1, static_cast<uint8_t>(!muted), 89};
    return chiaki_ctrl_send_message(&session->ctrl, MIC_TOGGLE_MESSAGE, payload, sizeof(payload));
}

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
    if (!ctx || !ctx->active.load(std::memory_order_acquire) || !buf ||
        buf_size == 0 || buf_size > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        return false;
    }
    ctx->video_bytes.fetch_add(buf_size, std::memory_order_relaxed);

    bool attached = false;
    JNIEnv* env = attachCurrentThread(&attached);
    if (!env || !ctx->callback_global || !ctx->on_video_packet) {
        detachIfNeeded(attached);
        return false;
    }

    jbyteArray arr = env->NewByteArray(static_cast<jsize>(buf_size));
    bool delivered = false;
    if (arr) {
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(buf_size), reinterpret_cast<const jbyte*>(buf));
        if (!env->ExceptionCheck()) {
            delivered = env->CallBooleanMethod(ctx->callback_global, ctx->on_video_packet, arr,
                                               static_cast<jlong>(monotonicMicros())) == JNI_TRUE;
            delivered = delivered && !env->ExceptionCheck();
        }
        env->DeleteLocalRef(arr);
    }
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    detachIfNeeded(attached);
    return delivered;
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

jstring makeUtf8String(JNIEnv* env, const char* text) {
    // The console sends standard UTF-8, which cannot be passed to NewStringUTF
    // for supplementary code points such as emoji.
    const auto length = static_cast<jsize>(strnlen(text, 65536));
    jbyteArray bytes = env->NewByteArray(length);
    jclass stringClass = env->FindClass("java/lang/String");
    jstring charset = env->NewStringUTF("UTF-8");
    jstring result = nullptr;
    if (bytes && stringClass && charset) {
        env->SetByteArrayRegion(bytes, 0, length, reinterpret_cast<const jbyte*>(text));
        auto constructor = env->GetMethodID(stringClass, "<init>", "([BLjava/lang/String;)V");
        if (constructor && !env->ExceptionCheck()) {
            result = static_cast<jstring>(env->NewObject(stringClass, constructor, bytes, charset));
        }
    }
    if (bytes) env->DeleteLocalRef(bytes);
    if (stringClass) env->DeleteLocalRef(stringClass);
    if (charset) env->DeleteLocalRef(charset);
    return result;
}

void sessionEventCallback(ChiakiEvent* event, void* user) {
    auto* ctx = reinterpret_cast<NativeSessionContext*>(user);
    if (!ctx || !event) return;
    jint eventType;
    const char* reason = "";
    switch (event->type) {
        case CHIAKI_EVENT_CONNECTED:
            eventType = EVENT_SESSION_CONNECTED;
            ctx->login_pin_pending.store(false, std::memory_order_release);
            queueMicrophoneMute(&ctx->session, true);
            ctx->transport_connected.store(true, std::memory_order_release);
            break;
        case CHIAKI_EVENT_QUIT:
            eventType = EVENT_SESSION_QUIT;
            reason = chiaki_quit_reason_string(event->quit.reason);
            break;
        case CHIAKI_EVENT_LOGIN_PIN_REQUEST:
            eventType = EVENT_LOGIN_PIN;
            ctx->login_pin_pending.store(true, std::memory_order_release);
            reason = event->login_pin_request.pin_incorrect ? "Incorrect passcode. Try again." : "";
            break;
        case CHIAKI_EVENT_KEYBOARD_OPEN:
            eventType = EVENT_KEYBOARD_OPEN;
            ctx->keyboard_open.store(true, std::memory_order_release);
            reason = event->keyboard.text_str;
            break;
        case CHIAKI_EVENT_KEYBOARD_TEXT_CHANGE:
            eventType = EVENT_KEYBOARD_UPDATE;
            reason = event->keyboard.text_str;
            break;
        case CHIAKI_EVENT_KEYBOARD_REMOTE_CLOSE:
            eventType = EVENT_KEYBOARD_CLOSE;
            ctx->keyboard_open.store(false, std::memory_order_release);
            break;
        default:
            return;
    }
    if (eventType == EVENT_SESSION_QUIT) {
        ctx->active.store(false, std::memory_order_release);
        ctx->transport_connected.store(false, std::memory_order_release);
        ctx->login_pin_pending.store(false, std::memory_order_release);
        ctx->keyboard_open.store(false, std::memory_order_release);
    }

    bool attached = false;
    JNIEnv* env = attachCurrentThread(&attached);
    if (env && ctx->callback_global && ctx->on_session_event) {
        jstring msg = makeUtf8String(env, reason ? reason : "");
        if (msg) {
            env->CallVoidMethod(
                ctx->callback_global,
                ctx->on_session_event,
                eventType,
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
    g_ctx.transport_connected.store(false, std::memory_order_release);
    g_ctx.login_pin_pending.store(false, std::memory_order_release);
    g_ctx.keyboard_open.store(false, std::memory_order_release);

    if (g_ctx.session_started) {
        if (g_ctx.mic_enabled) queueMicrophoneMute(&g_ctx.session, true);
        chiaki_controller_state_set_idle(&g_ctx.controller);
        chiaki_session_set_controller_state(&g_ctx.session, &g_ctx.controller);
        chiaki_session_stop(&g_ctx.session);
        chiaki_session_join(&g_ctx.session);
        g_ctx.session_started = false;
    }
    if (g_ctx.mic_encoder_initialized) {
        chiaki_opus_encoder_fini(&g_ctx.mic_encoder);
        g_ctx.mic_encoder = {};
        g_ctx.mic_encoder_initialized = false;
    }
    g_ctx.mic_connected = false;
    g_ctx.mic_enabled = false;
    if (g_ctx.session_initialized) {
        chiaki_session_fini(&g_ctx.session);
        secureZero(&g_ctx.session, sizeof(g_ctx.session));
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
    if (chiaki_lib_init() != CHIAKI_ERR_SUCCESS) {
        MP_LOGE("Failed to initialize libchiaki.");
        return JNI_ERR;
    }
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
    if (!jHost || !jPsnAccountId || !callbackObj || pin < 0 || pin > 99999999) {
        return ERR_INVALID_ARGS;
    }
    if (env->GetArrayLength(jPsnAccountId) != CHIAKI_PSN_ACCOUNT_ID_SIZE) {
        return ERR_INVALID_ARGS;
    }

    std::unique_lock<std::mutex> guard(g_ctx.regist_lock, std::try_to_lock);
    if (!guard.owns_lock()) {
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
        return ERR_CALLBACK_LOOKUP;
    }

    const char* hostChars = env->GetStringUTFChars(jHost, nullptr);
    if (!hostChars) {
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
    if (env->ExceptionCheck() || isAllZero(info.psn_account_id, sizeof(info.psn_account_id))) {
        env->ReleaseStringUTFChars(jHost, hostChars);
        secureZero(info.psn_account_id, sizeof(info.psn_account_id));
        return ERR_INVALID_ARGS;
    }

    ChiakiLog regLog{};
    chiaki_log_init(&regLog, CHIAKI_LOG_ERROR | CHIAKI_LOG_WARNING, chiakiLogCallback, nullptr);

    RegistSyncContext syncCtx{};
    ChiakiRegist regist{};
    ChiakiErrorCode startErr = chiaki_regist_start(&regist, &regLog, &info, registEventCallback, &syncCtx);

    env->ReleaseStringUTFChars(jHost, hostChars);
    secureZero(info.psn_account_id, sizeof(info.psn_account_id));

    if (startErr != CHIAKI_ERR_SUCCESS) {
        const char* errText = chiaki_error_string(startErr);
        jstring jErr = env->NewStringUTF(errText ? errText : "chiaki_regist_start failed");
        if (jErr) {
            env->CallVoidMethod(callbackObj, onError, static_cast<jint>(startErr), jErr);
            env->DeleteLocalRef(jErr);
        }
        return static_cast<jint>(startErr);
    }

    const int safeTimeout = std::clamp(static_cast<int>(timeoutMs), 3000, 30000);
    bool completed;
    {
        std::unique_lock<std::mutex> lock(syncCtx.mutex);
        completed = syncCtx.cv.wait_for(
            lock,
            std::chrono::milliseconds(safeTimeout),
            [&syncCtx]() { return syncCtx.finished; }
        );
    }

    if (!completed) chiaki_regist_stop(&regist);
    chiaki_regist_fini(&regist);
    secureZero(&regist, sizeof(regist));
    if (!completed) {
        syncCtx.success = false;
        syncCtx.error_code = ERR_REGIST_TIMEOUT;
        syncCtx.error_message = "Timed out waiting for PS5 registration response on local Wi-Fi.";
    }

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
                h.server_mac[0], h.server_mac[1], h.server_mac[2],
                h.server_mac[3], h.server_mac[4], h.server_mac[5]
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
    if (!jHost || !jRegistKey || !jMorning || !callbackObj ||
        width <= 0 || height <= 0 || fps <= 0 || bitrateKbps <= 0) {
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
    jmethodID onVideo = env->GetMethodID(cls, "onVideoPacket", "([BJ)Z");
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
    if (!g_ctx.callback_global) {
        secureZero(registKeyBytes, sizeof(registKeyBytes));
        secureZero(morningBytes, sizeof(morningBytes));
        return ERR_CALLBACK_LOOKUP;
    }
    g_ctx.on_video_packet = onVideo;
    g_ctx.on_audio_header = onAudioHeader;
    g_ctx.on_audio_pcm = onAudioPcm;
    g_ctx.on_session_event = onEvent;
    g_ctx.audio_channels.store(2, std::memory_order_release);

    const char* host = env->GetStringUTFChars(jHost, nullptr);
    if (!host) {
        secureZero(registKeyBytes, sizeof(registKeyBytes));
        secureZero(morningBytes, sizeof(morningBytes));
        teardownSessionLocked(env);
        return ERR_INVALID_ARGS;
    }
    ChiakiConnectInfo info{};
    info.ps5 = true;
    info.host = host;
    info.enable_dualsense = true;
    info.enable_keyboard = true;
    info.enable_idr_on_fec_failure = true;
    info.packet_loss_max = 0.05;
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
    chiaki_controller_state_set_idle(&g_ctx.controller);

    // Wire genuine Opus decoder so incoming PS5 Opus audio is decoded to 16-bit PCM.
    chiaki_opus_decoder_init(&g_ctx.opus_decoder, &g_ctx.logger);
    g_ctx.opus_initialized = true;
    chiaki_opus_decoder_set_cb(&g_ctx.opus_decoder, opusSettingsCallback, opusFrameCallback, &g_ctx);

    ChiakiAudioSink audioSink{};
    chiaki_opus_decoder_get_sink(&g_ctx.opus_decoder, &audioSink);
    chiaki_session_set_audio_sink(&g_ctx.session, &audioSink);

    chiaki_session_set_video_sample_cb(&g_ctx.session, videoSampleCallback, &g_ctx);
    chiaki_session_set_event_cb(&g_ctx.session, sessionEventCallback, &g_ctx);

    g_ctx.video_bytes.store(0, std::memory_order_relaxed);
    g_ctx.bitrate_sample_us = monotonicMicros();
    g_ctx.active.store(true, std::memory_order_release);
    err = chiaki_session_start(&g_ctx.session);
    if (err != CHIAKI_ERR_SUCCESS) {
        MP_LOGE("chiaki_session_start failed: %d", static_cast<int>(err));
        teardownSessionLocked(env);
        return static_cast<jint>(err);
    }
    g_ctx.session_started = true;
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
    std::unique_lock<std::mutex> guard(g_ctx.session_lock, std::try_to_lock);
    if (!guard.owns_lock() || !g_ctx.active.load(std::memory_order_acquire)) return;
    auto& state = g_ctx.controller;
    state.buttons = static_cast<uint32_t>(buttons);
    state.l2_state = static_cast<uint8_t>(std::clamp(static_cast<int>(l2), 0, 255));
    state.r2_state = static_cast<uint8_t>(std::clamp(static_cast<int>(r2), 0, 255));
    state.left_x = leftX;
    state.left_y = leftY;
    state.right_x = rightX;
    state.right_y = rightY;
    const int8_t touchId = state.touches[0].id;
    if (touchActive) {
        auto x = static_cast<uint16_t>(std::clamp(static_cast<int>(touchX), 0, TOUCHPAD_WIDTH - 1));
        auto y = static_cast<uint16_t>(std::clamp(static_cast<int>(touchY), 0, TOUCHPAD_HEIGHT - 1));
        if (touchId < 0) chiaki_controller_state_start_touch(&state, x, y);
        else chiaki_controller_state_set_touch_pos(&state, static_cast<uint8_t>(touchId), x, y);
    } else if (touchId >= 0) {
        chiaki_controller_state_stop_touch(&state, static_cast<uint8_t>(touchId));
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

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_mobileplay_remoteplay_ChiakiNativeBridge_nativeRequestIdr(JNIEnv*, jobject) {
#ifdef MOBILEPLAY_WITH_LIBCHIAKI
    std::lock_guard<std::mutex> guard(g_ctx.session_lock);
    if (!g_ctx.transport_connected.load(std::memory_order_acquire) ||
        !g_ctx.active.load(std::memory_order_acquire)) return JNI_FALSE;
    auto& stream = g_ctx.session.stream_connection;
    if (chiaki_mutex_lock(&stream.feedback_sender_mutex) != CHIAKI_ERR_SUCCESS) return JNI_FALSE;
    const bool sent = stream.feedback_sender_active &&
        chiaki_session_request_idr(&g_ctx.session) == CHIAKI_ERR_SUCCESS;
    chiaki_mutex_unlock(&stream.feedback_sender_mutex);
    return sent;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_com_example_mobileplay_remoteplay_ChiakiNativeBridge_nativeGetStreamStats(JNIEnv* env, jobject) {
    jdouble values[] = {-1.0, -1.0, -1.0};
#ifdef MOBILEPLAY_WITH_LIBCHIAKI
    {
        std::lock_guard<std::mutex> guard(g_ctx.session_lock);
        if (g_ctx.transport_connected.load(std::memory_order_acquire) &&
            g_ctx.active.load(std::memory_order_acquire)) {
            if (g_ctx.session.rtt_us > 0) values[0] = g_ctx.session.rtt_us / 1000.0;
            const auto now = monotonicMicros();
            const auto duration = now - g_ctx.bitrate_sample_us;
            if (duration > 0) {
                const auto bytes = g_ctx.video_bytes.exchange(0, std::memory_order_relaxed);
                values[1] = bytes * 8000.0 / duration;
                g_ctx.bitrate_sample_us = now;
            }
            auto& packets = g_ctx.session.stream_connection.packet_stats;
            // Generation counters are protected by the stats lock. Do not read the
            // independent audio sequence counters or reset Chiaki's congestion sample.
            if (chiaki_mutex_lock(&packets.mutex) == CHIAKI_ERR_SUCCESS) {
                const auto total = packets.gen_received + packets.gen_lost;
                if (total > 0) values[2] = static_cast<double>(packets.gen_lost) / total;
                chiaki_mutex_unlock(&packets.mutex);
            }
        }
    }
#endif
    jdoubleArray stats = env->NewDoubleArray(3);
    if (stats) env->SetDoubleArrayRegion(stats, 0, 3, values);
    return stats;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_mobileplay_remoteplay_ChiakiNativeBridge_nativeWakeConsole(
    JNIEnv* env, jobject, jstring jHost, jbyteArray jRegistKey
) {
#ifdef MOBILEPLAY_WITH_LIBCHIAKI
    if (!jHost || !jRegistKey || env->GetArrayLength(jRegistKey) != CHIAKI_SESSION_AUTH_SIZE) {
        return ERR_INVALID_ARGS;
    }
    uint8_t key[CHIAKI_SESSION_AUTH_SIZE]{};
    env->GetByteArrayRegion(jRegistKey, 0, sizeof(key), reinterpret_cast<jbyte*>(key));
    uint64_t credential = 0;
    for (auto character : key) {
        if (character == 0) break;
        uint8_t digit;
        if (character >= '0' && character <= '9') digit = character - '0';
        else if (character >= 'a' && character <= 'f') digit = character - 'a' + 10;
        else if (character >= 'A' && character <= 'F') digit = character - 'A' + 10;
        else {
            secureZero(key, sizeof(key));
            return ERR_INVALID_ARGS;
        }
        credential = (credential << 4) | digit;
    }
    secureZero(key, sizeof(key));
    if (credential == 0 || env->ExceptionCheck()) return ERR_INVALID_ARGS;
    const char* host = env->GetStringUTFChars(jHost, nullptr);
    if (!host) return ERR_INVALID_ARGS;
    ChiakiLog log{};
    chiaki_log_init(&log, CHIAKI_LOG_ERROR | CHIAKI_LOG_WARNING, chiakiLogCallback, nullptr);
    const auto result = chiaki_discovery_wakeup(&log, nullptr, host, credential, true);
    env->ReleaseStringUTFChars(jHost, host);
    secureZero(&credential, sizeof(credential));
    return result;
#else
    return ERR_NATIVE_UNAVAILABLE;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_mobileplay_remoteplay_ChiakiNativeBridge_nativeSetLoginPin(JNIEnv* env, jobject, jbyteArray jPin) {
#ifdef MOBILEPLAY_WITH_LIBCHIAKI
    uint8_t pin[4]{};
    if (!jPin || env->GetArrayLength(jPin) != sizeof(pin)) return ERR_INVALID_ARGS;
    env->GetByteArrayRegion(jPin, 0, sizeof(pin), reinterpret_cast<jbyte*>(pin));
    std::lock_guard<std::mutex> guard(g_ctx.session_lock);
    const bool digits = std::all_of(std::begin(pin), std::end(pin), [](uint8_t digit) {
        return digit >= '0' && digit <= '9';
    });
    jint result = ERR_INVALID_ARGS;
    if (digits && !env->ExceptionCheck() && g_ctx.session_started &&
        g_ctx.active.load(std::memory_order_acquire) &&
        g_ctx.login_pin_pending.exchange(false, std::memory_order_acq_rel)) {
        result = chiaki_session_set_login_pin(&g_ctx.session, pin, sizeof(pin));
        if (result != CHIAKI_ERR_SUCCESS) g_ctx.login_pin_pending.store(true, std::memory_order_release);
    }
    secureZero(pin, sizeof(pin));
    return result;
#else
    return ERR_NATIVE_UNAVAILABLE;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_mobileplay_remoteplay_ChiakiNativeBridge_nativeFinishKeyboard(
    JNIEnv* env, jobject, jbyteArray jText
) {
#ifdef MOBILEPLAY_WITH_LIBCHIAKI
    std::lock_guard<std::mutex> guard(g_ctx.session_lock);
    if (!g_ctx.session_started || !g_ctx.active.load(std::memory_order_acquire) ||
        !g_ctx.keyboard_open.load(std::memory_order_acquire)) return ERR_INVALID_ARGS;
    if (!jText) return chiaki_session_keyboard_reject(&g_ctx.session);
    const auto length = env->GetArrayLength(jText);
    if (length > 4096) return ERR_INVALID_ARGS;
    char text[4097]{};
    env->GetByteArrayRegion(jText, 0, length, reinterpret_cast<jbyte*>(text));
    if (env->ExceptionCheck() || std::memchr(text, '\0', length)) {
        secureZero(text, sizeof(text));
        return ERR_INVALID_ARGS;
    }
    const auto result = chiaki_session_keyboard_set_text(&g_ctx.session, text);
    secureZero(text, sizeof(text));
    if (result != CHIAKI_ERR_SUCCESS) return result;
    return chiaki_session_keyboard_accept(&g_ctx.session);
#else
    return ERR_NATIVE_UNAVAILABLE;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_mobileplay_remoteplay_ChiakiNativeBridge_nativeRequestStandby(JNIEnv*, jobject) {
#ifdef MOBILEPLAY_WITH_LIBCHIAKI
    std::lock_guard<std::mutex> guard(g_ctx.session_lock);
    if (!g_ctx.session_started || !g_ctx.active.load(std::memory_order_acquire) ||
        !g_ctx.transport_connected.load(std::memory_order_acquire)) return ERR_INVALID_ARGS;
    return chiaki_session_goto_bed(&g_ctx.session);
#else
    return ERR_NATIVE_UNAVAILABLE;
#endif
}

extern "C" JNIEXPORT jint JNICALL
Java_com_example_mobileplay_remoteplay_ChiakiNativeBridge_nativeSetMicrophoneEnabled(
    JNIEnv*, jobject, jboolean enabled
) {
#ifdef MOBILEPLAY_WITH_LIBCHIAKI
    std::lock_guard<std::mutex> guard(g_ctx.session_lock);
    if (!enabled) {
        const bool was_enabled = g_ctx.mic_enabled;
        g_ctx.mic_enabled = false;
        if (!was_enabled || !g_ctx.active.load(std::memory_order_acquire)) return CHIAKI_ERR_SUCCESS;
        return queueMicrophoneMute(&g_ctx.session, true);
    }
    if (!g_ctx.session_started || !g_ctx.active.load(std::memory_order_acquire) ||
        !g_ctx.transport_connected.load(std::memory_order_acquire)) return ERR_INVALID_ARGS;
    if (g_ctx.mic_enabled) return CHIAKI_ERR_SUCCESS;

    if (!g_ctx.mic_encoder_initialized) {
        ChiakiAudioHeader header{};
        chiaki_audio_header_set(&header, 2, 16, 48000, 480);
        chiaki_opus_encoder_init(&g_ctx.mic_encoder, &g_ctx.logger);
        chiaki_opus_encoder_header(&header, &g_ctx.mic_encoder, &g_ctx.session);
        // The Remote Play microphone channel uses fixed 40-byte Opus frames at 100 Hz.
        auto* encoder = g_ctx.mic_encoder.opus_encoder;
        if (!encoder || opus_encoder_ctl(encoder, OPUS_SET_BITRATE(32000)) != OPUS_OK ||
            opus_encoder_ctl(encoder, OPUS_SET_VBR(0)) != OPUS_OK) {
            chiaki_opus_encoder_fini(&g_ctx.mic_encoder);
            g_ctx.mic_encoder = {};
            return CHIAKI_ERR_UNKNOWN;
        }
        g_ctx.mic_encoder_initialized = true;
    } else {
        auto* sender = g_ctx.mic_encoder.audio_sender;
        // Keep the packet sequence and discard redundant frames from the last capture.
        std::free(sender->framea);
        std::free(sender->frameb);
        sender->framea = nullptr;
        sender->frameb = nullptr;
        opus_encoder_ctl(g_ctx.mic_encoder.opus_encoder, OPUS_RESET_STATE);
    }
    if (!g_ctx.mic_connected) {
        const uint8_t payload[] = {0, 0};
        const auto result = chiaki_ctrl_send_message(
            &g_ctx.session.ctrl, MIC_CONNECT_MESSAGE, payload, sizeof(payload)
        );
        if (result != CHIAKI_ERR_SUCCESS) return result;
        g_ctx.mic_connected = true;
    }
    const auto result = queueMicrophoneMute(&g_ctx.session, false);
    g_ctx.mic_enabled = result == CHIAKI_ERR_SUCCESS;
    return result;
#else
    return ERR_NATIVE_UNAVAILABLE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_mobileplay_remoteplay_ChiakiNativeBridge_nativeSendMicrophonePcm(
    JNIEnv* env, jobject, jshortArray samples
) {
#ifdef MOBILEPLAY_WITH_LIBCHIAKI
    constexpr jsize frame_samples = 480;
    if (!samples || env->GetArrayLength(samples) != frame_samples) return JNI_FALSE;
    std::lock_guard<std::mutex> guard(g_ctx.session_lock);
    if (!g_ctx.mic_enabled || !g_ctx.active.load(std::memory_order_acquire) ||
        !g_ctx.transport_connected.load(std::memory_order_acquire)) return JNI_FALSE;
    auto& stream = g_ctx.session.stream_connection;
    if (chiaki_mutex_lock(&stream.feedback_sender_mutex) != CHIAKI_ERR_SUCCESS) return JNI_FALSE;
    const bool ready = stream.feedback_sender_active;
    if (ready) {
        jshort mono[frame_samples];
        int16_t stereo[2 * frame_samples];
        env->GetShortArrayRegion(samples, 0, frame_samples, mono);
        if (!env->ExceptionCheck()) {
            for (jsize i = 0; i < frame_samples; ++i) {
                stereo[2 * i] = mono[i];
                stereo[2 * i + 1] = mono[i];
            }
            chiaki_opus_encoder_frame(stereo, &g_ctx.mic_encoder);
        }
        secureZero(mono, sizeof(mono));
        secureZero(stereo, sizeof(stereo));
    }
    chiaki_mutex_unlock(&stream.feedback_sender_mutex);
    return ready && !env->ExceptionCheck();
#else
    return JNI_FALSE;
#endif
}
