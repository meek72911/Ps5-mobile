# MobilePlay — Milestone 2 Critical Engineering Repair Plan

Repair MobilePlay's native Remote Play integration so the existing Android application can perform genuine PS5 PIN registration, store authentic encrypted session credentials, decode real H.264/HEVC video and Opus-to-PCM audio streams, and transmit multi-touch DualSense controls over a verified, reproducible `arm64-v8a` NDK build pipeline.

---

## User Review & Critical Decisions

> [!IMPORTANT]
> The architectural choices below reflect your confirmed preferences from our clarification step. Please review and approve this plan before any source code changes begin.

- **Confirmed Decision 1 — Native JNI Strategy**: Repair and complete MobilePlay's dedicated C++ JNI bridge pinned to an upstream `chiaki-ng` release commit rather than replacing the app's JNI layer with Momiji's full Android stack.
- **Confirmed Decision 2 — Sony PlayStation Account Identification**: Support **Manual PSN Account ID** (8-byte Base64 or 64-bit numeric) as the primary reliable pairing path alongside a **hardened Sony browser OAuth redirect flow** with strict origin/path validation and cryptographically verified state nonces (never collecting PSN passwords or inventing OAuth secrets).
- **Confirmed Decision 3 — Target ABI & Default Stream Profile**: Target **`arm64-v8a` only** for the initial verified native NDK build and default local Wi-Fi streaming to **720p at 60 FPS using H.264**, keeping 1080p selectable after live hardware validation.

> [!WARNING]
> **Cloud Container Build Constraint**: Because the AI Studio cloud environment lacks the Android NDK, CMake, Ninja, and Clang toolchains, native `.so` compilation cannot execute inside this container. Every Kotlin, C++, CMake, credential, security, licensing, and automated test fix will be implemented and verified here, and a complete self-contained GitHub Actions workflow using the Gradle Wrapper will build and verify the `arm64-v8a` native APK on GitHub runners.

---

## 1. Overview & Core Concept

- **What It Does**: Upgrades MobilePlay's Remote Play engine from a placeholder adapter into a protocol-accurate PS5 client backed by upstream `chiaki-ng` (`libchiaki`), while preserving 100% of the working Milestone 1–3 touchscreen controls, visual layout editor, presets, calibration, Keystore vault, and diagnostics.
- **Target Audience / Persona**: PlayStation 5 owners who want low-latency local Wi-Fi gameplay on Android phones using customizable PUBG/BGMI-style multi-touch DualSense controls without carrying a physical controller or running a PC bridge.
- **Key Value**: Eliminates every identified native integration blocker—unimplemented PIN registration, zero-filled session keys, Opus-vs-PCM audio callback mismatch, JNI callback teardown races, unverified OAuth state, and an incomplete CI pipeline—without altering the polished handheld UI or introducing fake streaming states.

---

## 2. User Experience & Visual Design

- **UI Preservation Policy**: MobilePlay's existing dark handheld aesthetic, navigation rail (`Home`, `Consoles`, `Controls`, `Settings`), fullscreen `Gameplay`, `Visual Layout Editor`, `5-Step Onboarding Wizard`, and `Diagnostics` screens remain visually intact—no redesign or extra feature sprawl.
- **Visual Identity & Theme**:
  - *Aesthetic Direction*: Tactical handheld gaming console software—high contrast, low glare, zero clutter.
  - *Color Palette*: Obsidian charcoal canvas (`#0B0F14`), deep slate surfaces (`#151B23` / `#202936`), muted cyan interactive accents (`#4DD8E8`), crisp light ice primary text (`#F5F7FA`), muted slate secondary copy (`#94A3B8`), emerald live status (`#3BD49A`), and coral alert/emergency states (`#FF6478`).
  - *Typography*: Bundled local `Space Grotesk` for interface headings/labels paired with `JetBrains Mono` for live telemetry, stick axes, and network status readouts.
- **Honest User Feedback Across Pairing & Streaming Flows**:
  - *Console Pairing*: Runs real `libchiaki` PIN registration when the native binary is present, persists the returned cryptographic registration keys into the encrypted Keystore vault, and clearly distinguishes "Saved without native registration" from "Cryptographically Paired with PS5."
  - *Session Startup*: Rejects any console missing valid non-zero registration keys before touching the native layer, preventing silent failures or false "Connected" indicators.
  - *Connection & Failure States*: Surface real `libchiaki` error reasons, prevent duplicate overlapping connection attempts, and immediately zero all sticks, triggers, buttons, and toggles upon disconnection, Wi-Fi loss, backgrounding, or incoming calls.

---

## 3. Key Product Decisions & Trade-Offs

- **Decision 1 — Pinned Upstream `chiaki-ng` Revision + Dedicated C++ JNI Bridge**
  - *Chosen Approach*: Pin upstream `chiaki-ng` to a verified stable release (`v1.9.9`, commit `3f8fd71`) with recursive submodules and repair MobilePlay's C++ JNI bridge to call genuine `ChiakiRegist`, `ChiakiSession`, `ChiakiOpusDecoder`, and `ChiakiControllerState` APIs.
  - *Why*: Keeps MobilePlay's pure-Kotlin multi-touch engine, Keystore vault, and Compose architecture cleanly decoupled from Momiji's separate Android app scaffolding while guaranteeing reproducible native builds and exact struct/signature compatibility.
  - *Alternatives Considered*: Importing Momiji's full Android JNI + app wrapper was passed over to avoid conflicting lifecycle assumptions, duplicated input code, and brittle cross-project coupling.

- **Decision 2 — Native Opus Decoding to 16-Bit PCM Before Crossing JNI**
  - *Chosen Approach*: Wire `libchiaki`'s `ChiakiOpusDecoder` inside the native JNI layer so incoming compressed Opus frames from the PS5 are decoded into interleaved 16-bit PCM before being delivered to Android's low-latency `AudioTrack`.
  - *Why*: `chiaki-ng`'s raw audio sink emits compressed Opus payloads rather than raw PCM. Decoding via `ChiakiOpusDecoder` matches upstream desktop/handheld clients, ensures exact channel/sample-rate handoffs, and prevents garbled audio or buffer underruns.
  - *Alternatives Considered*: Passing raw Opus packets across JNI into a second Android `MediaCodec` decoder adds latency and extra buffer lifecycle failure modes.

- **Decision 3 — Strict Credential Validation & Hardened OAuth State Verification**
  - *Chosen Approach*: Extend the encrypted console record to store authentic `rpRegistKey` and `rpKey` (`morning`) byte payloads returned by `libchiaki` registration, forbid zero-filled fallback arrays, and enforce time-bounded OAuth `state` nonce + exact host/path verification on Sony browser redirects.
  - *Why*: Ensures cryptographic handshakes can actually authenticate with a real PS5 while defending against spoofed redirect URLs and preventing plaintext credential leakage.

- **Decision 4 — Self-Contained `arm64-v8a` GitHub Actions NDK Pipeline with Binary Verification**
  - *Chosen Approach*: Package a complete Gradle Wrapper (`gradlew` + wrapper JAR), enable native CMake compilation for `arm64-v8a` with 16 KB ELF page alignment (`-Wl,-z,max-page-size=16384`), and fail CI if `libchiaki_jni.so` is missing from the APK or fails alignment inspection.
  - *Why*: Eliminates environment drift across runners and guarantees CI never reports a false green build when native compilation is skipped.

---

## 4. Technical Architecture & Data Strategy *(Technical Reference)*

### System Architecture & Data Flow

```text
┌──────────────────────────────────────────────────────────────────────────────┐
│                         MOBILEPLAY ANDROID APP (KOTLIN)                      │
├──────────────────────────────┬───────────────────────────────────────────────┤
│  Touch & Layout Subsystem    │  Auth, Security & Session Management          │
│  ┌────────────────────────┐  │  ┌─────────────────────────────────────────┐  │
│  │ TouchOverlayView       │  │  │ SonyAuthManager                         │  │
│  │ TouchInputEngine       │──┼─▶│  • Official browser OAuth + state nonce │  │
│  │ 4 Presets + LayoutEd   │  │  │  • Strict redirect host/path check      │  │
│  └───────────┬────────────┘  │  │  • Manual 8-byte Base64/Numeric ID      │  │
│              │ PadState      │  └────────────────────┬────────────────────┘  │
│              │ (clear on     │                       ▼                       │
│              │  pause/stop/  │  ┌─────────────────────────────────────────┐  │
│              │  disconnect)  │  │ KeystoreCredentialVault (AES-256-GCM)   │  │
│              ▼               │  │  • Encrypted rpRegistKey & rpKey bytes  │  │
│  ┌────────────────────────┐  │  │  • Rejects empty/zero-filled keys       │  │
│  │ ConnectionManager &    │◀─┼──┴─────────────────────────────────────────┘  │
│  │ MomijiLibchiakiAdapter │  │                                               │
│  └───────────┬────────────┘  │  ┌─────────────────────────────────────────┐  │
│              │               │  │ Media Output Pipeline                   │  │
│              │               │  │  • VideoRenderer (Hardware MediaCodec   │  │
│              │               │  │    + SPS/PPS/VPS + dynamic resolution)  │  │
│              │               │  │  • AudioRenderer (Low-latency PCM       │  │
│              │               │  │    AudioTrack + underrun telemetry)     │  │
└──────────────┼───────────────┴───────────────────────▲───────────────────────┘
               │ JNI Bridge (thread-safe mutex +       │ Video NALs (H.264/HEVC)
               │ atomic session generation +           │ Audio Header & 16-bit PCM
               │ safe Attach/Detach + 16KB aligned)    │ Session/Regist Events
               ▼                                       │
┌──────────────────────────────────────────────────────────────────────────────┐
│            NATIVE C++ LAYER (libchiaki_jni.so — arm64-v8a, AGPL-3.0)         │
│  ┌──────────────────────┐  ┌─────────────────────┐  ┌─────────────────────┐  │
│  │ ChiakiRegist         │  │ ChiakiSession       │  │ ChiakiOpusDecoder   │  │
│  │  • Real PS5 PIN reg  │  │  • Authentic keys   │  │  • Opus -> PCM16    │  │
│  │  • Returns real keys │  │  • Video/Event CBs  │  │  • Sample rate/ch   │  │
│  │                      │  │  • DualSense state  │  │                     │  │
│  └──────────┬───────────┘  └──────────┬──────────┘  └──────────┬──────────┘  │
└─────────────┼─────────────────────────┼────────────────────────┼─────────────┘
              └─────────────────────────┴────────────────────────┘
                                        │ Local 5 GHz Wi-Fi LAN
                                        ▼
                            ┌───────────────────────┐
                            │   PlayStation 5 Host  │
                            │  (UDP 9302 / RP Port) │
                            └───────────────────────┘
```

### Data Model & State Strategy

- **Registered Console Record**: Stored exclusively inside the hardware-backed Android Keystore AES-256-GCM vault. Holds console identity (`hostId`, `hostName`, `hostAddress`, `serverMac`), validated 8-byte `psnAccountId`, authentic `rpRegistKeyBase64` (16 bytes) and `rpKeyBase64` (`morning`, 16 bytes), `rpKeyType`, pairing status flag (`isCryptographicallyPaired`), and timestamp.
- **Session Lifecycle State Machine**: Explicit transitions (`StandaloneSimulator` → `Discovering` / `Pairing` / `Connecting` → `Connected` / `NativeEngineRequired` / `Error`), with duplicate-request guards so concurrent `connectToConsole` or `pairAndSaveConsole` calls cannot corrupt native session ownership.
- **Media Pipeline State**: Thread-safe `VideoRenderer` synchronized across surface creation/destruction, codec configuration (handling H.264 SPS/PPS and HEVC VPS/SPS/PPS flags plus output format changes), monotonic microsecond timestamps, and graceful fault recovery; `AudioRenderer` receives decoded 16-bit PCM frames with synchronized start/stop teardown.

### Security, Privacy & Licensing Safeguards

- **Zero-Secret Policy & Credential Integrity**: PSN passwords are never requested in-app, OAuth authorization codes, state nonces, and registration keys are never written to Android logs, and session startup refuses to proceed with missing or zero-filled cryptographic keys.
- **OAuth Redirect & State Verification**: Every browser sign-in request generates a fresh cryptographic state token stored ephemerally with an expiry window; redirect parsing enforces exact `https://remoteplay.dl.playstation.net/remoteplay/redirect` origin/path matching and constant-time state comparison before accepting any code.
- **Native Memory & Lifecycle Isolation**: Native callbacks check an atomic session generation and only execute while the session is active; JNI global references are released strictly after `chiaki_session_join()` finishes, preventing use-after-free crashes across repeated connect/disconnect cycles.
- **Verbatim GNU AGPL-3.0 Compliance**: Replace the abbreviated license summary in `COPYING` with the complete unmodified GNU Affero General Public License v3.0 text while preserving upstream copyright and attribution notices (`chiaki-ng`, `momiji`).

### Verification Strategy (6 Distinct Milestones)

1. **Unit & Robolectric Tests**: Preserve all 31 existing passing tests and add targeted tests for missing/invalid/zero-filled registration credentials, session startup failure, native library load failure, JNI callback cleanup, duplicate connection requests, mid-stream disconnect, backgrounding input reset, malformed Sony redirects, invalid/expired OAuth state, Wi-Fi loss, and decoder initialization failure.
2. **Android APK Compilation**: Verify clean Kotlin/Compose/Resource build inside AI Studio without breaking existing features or signing configs.
3. **Native `arm64-v8a` Compilation (GitHub Actions)**: Pin `chiaki-ng` `v1.9.9` (`3f8fd71`) with submodules, build `libchiaki_jni.so` via `./gradlew`, assert `.so` presence inside the debug APK, verify 16 KB ELF LOAD alignment, run unit tests + Android Lint, and upload APK + build logs.
4. **Device Installation**, **Real PS5 Pairing**, and **Real PS5 Video/Audio Streaming**: Remain explicitly marked `UNVERIFIED` until validated on physical hardware with a concise, safe physical-device test checklist (keeping test sessions brief to respect the test PS5's thermal/power stability).
