package com.example.mobileplay.remoteplay

import android.net.Uri
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom

data class SonyAuthSession(
    val psnAccountIdBase64: String,
    val methodLabel: String,
    val hasAuthCode: Boolean
)

/**
 * Legitimate Sony Remote Play browser authentication & PSN Account ID normalizer
 * aligned with upstream chiaki-ng / Momiji.
 *
 * Security guarantees:
 * - Never requests, captures, or stores PSN passwords inside MobilePlay.
 * - Uses Sony's official browser OAuth endpoint so MFA & passkeys run natively on Sony's domain.
 * - Generates, persists ephemerally (with TTL), and verifies cryptographic OAuth `state` nonces
 *   using constant-time comparison before accepting any redirect URL.
 * - Strictly validates redirect origin (https://remoteplay.dl.playstation.net) and path (/remoteplay/redirect).
 * - Never invents private Sony OAuth client secrets; manual PSN Account ID remains the primary
 *   reliable path for local PS5 PIN registration.
 * - Never logs authorization codes, state tokens, cookies, or account identifiers.
 */
object SonyAuthManager {
    private const val SONY_AUTHZ_ENDPOINT = "https://ca.account.sony.com/api/authz/v3/oauth/authorize"
    const val SONY_REDIRECT_SCHEME = "https"
    const val SONY_REDIRECT_HOST = "remoteplay.dl.playstation.net"
    const val SONY_REDIRECT_PATH = "/remoteplay/redirect"
    const val SONY_REDIRECT_URI = "$SONY_REDIRECT_SCHEME://$SONY_REDIRECT_HOST$SONY_REDIRECT_PATH"
    private const val REMOTE_PLAY_SCOPE = "psn:clientapp"
    private const val STATE_TTL_MS = 10 * 60 * 1000L // 10 minutes

    private val stateLock = Any()
    private var pendingStateNonce: String? = null
    private var pendingStateIssuedAtMs: Long = 0L

    /**
     * Builds the legitimate Sony Remote Play browser sign-in URI and stores the cryptographic
     * `state` nonce ephemerally for single-use verification upon redirect return.
     */
    fun buildBrowserSignInUri(nowMs: Long = System.currentTimeMillis()): Uri {
        val randomBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val stateNonce = Base64.encodeToString(
            randomBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        synchronized(stateLock) {
            pendingStateNonce = stateNonce
            pendingStateIssuedAtMs = nowMs
        }
        return Uri.parse(SONY_AUTHZ_ENDPOINT).buildUpon()
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", REMOTE_PLAY_SCOPE)
            .appendQueryParameter("redirect_uri", SONY_REDIRECT_URI)
            .appendQueryParameter("state", stateNonce)
            .build()
    }

    /** Clears any pending ephemeral OAuth state (e.g., on user cancel or logout). */
    fun clearPendingState() {
        synchronized(stateLock) {
            pendingStateNonce = null
            pendingStateIssuedAtMs = 0L
        }
    }

    /**
     * Strictly validates a Sony OAuth redirect URL pasted by the user:
     * 1. Enforces exact scheme (`https`), host (`remoteplay.dl.playstation.net`), and path (`/remoteplay/redirect`).
     * 2. Detects and reports OAuth cancellation or errors cleanly.
     * 3. Requires a non-expired pending OAuth `state` nonce and verifies constant-time equality (single-use).
     * 4. Extracts the authorization `code` parameter without logging it.
     */
    fun extractAuthorizationCode(
        redirectUrl: String,
        nowMs: Long = System.currentTimeMillis(),
        requireStateVerification: Boolean = true
    ): Result<String> {
        val trimmed = redirectUrl.trim()
        if (trimmed.isBlank()) {
            return Result.failure(IllegalArgumentException("Paste the full Sony redirect URL from your browser."))
        }
        val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("Malformed redirect URL."))

        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()
        val path = uri.path
        if (scheme != SONY_REDIRECT_SCHEME || host != SONY_REDIRECT_HOST || path != SONY_REDIRECT_PATH) {
            return Result.failure(
                IllegalArgumentException(
                    "Invalid redirect URL origin or path. Expected $SONY_REDIRECT_URI"
                )
            )
        }

        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            clearPendingState()
            val errorDesc = uri.getQueryParameter("error_description")
            val message = when (error.lowercase()) {
                "access_denied" -> "Sony sign-in was canceled or access was denied."
                "login_required" -> "Sony sign-in expired. Start browser sign-in again."
                else -> errorDesc ?: "Sony sign-in returned error: $error"
            }
            return Result.failure(IllegalArgumentException(message))
        }

        if (requireStateVerification) {
            val returnedState = uri.getQueryParameter("state")
            if (returnedState.isNullOrBlank()) {
                return Result.failure(
                    IllegalArgumentException("Redirect URL is missing the required OAuth state token. Start sign-in from MobilePlay first.")
                )
            }
            val expectedState: String?
            val issuedAt: Long
            synchronized(stateLock) {
                expectedState = pendingStateNonce
                issuedAt = pendingStateIssuedAtMs
            }
            if (expectedState.isNullOrBlank()) {
                return Result.failure(
                    IllegalArgumentException("No active Sony sign-in session. Launch Sony Browser Sign-In before pasting a redirect URL.")
                )
            }
            if (nowMs - issuedAt > STATE_TTL_MS || nowMs < issuedAt) {
                clearPendingState()
                return Result.failure(
                    IllegalArgumentException("Sony sign-in state expired. Launch Sony Browser Sign-In again.")
                )
            }
            val matches = MessageDigest.isEqual(
                expectedState.toByteArray(StandardCharsets.UTF_8),
                returnedState.toByteArray(StandardCharsets.UTF_8)
            )
            if (!matches) {
                return Result.failure(
                    IllegalArgumentException("OAuth state mismatch. Rejected redirect URL for security.")
                )
            }
        }

        val code = uri.getQueryParameter("code")?.trim()
        if (code.isNullOrBlank() || code.length < 4) {
            return Result.failure(
                IllegalArgumentException("Redirect URL does not contain a valid OAuth authorization code.")
            )
        }

        // Consume single-use state token on successful validation.
        if (requireStateVerification) {
            clearPendingState()
        }
        return Result.success(code)
    }

    /**
     * Validates or converts a manual PSN Account ID into the exact 8-byte little-endian Base64
     * format required by libchiaki PS5 registration.
     *
     * Accepts either:
     * 1. Already Base64-encoded 8-byte Account ID (e.g. "AAAAAAAAAAA="), OR
     * 2. Unsigned 64-bit decimal PSN Account ID (converted to 8-byte little-endian Base64).
     */
    fun normalizeAccountIdToBase64(rawInput: String): Result<String> {
        val clean = rawInput.trim()
        if (clean.isBlank()) {
            return Result.failure(IllegalArgumentException("Enter your PSN Account ID (8-byte Base64 or numeric)."))
        }

        // Case 1: Unsigned 64-bit decimal PSN Account ID -> little-endian 8-byte Base64 (chiaki-ng spec)
        if (clean.all { it.isDigit() } && clean.length in 6..20) {
            val unsigned = runCatching { clean.toULong() }.getOrNull()
                ?: return Result.failure(IllegalArgumentException("Numeric PSN Account ID is out of 64-bit range."))
            val bytes = ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putLong(unsigned.toLong())
                .array()
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            return Result.success(encoded)
        }

        // Case 2: Standard Base64 8-byte PSN Account ID
        val decoded = runCatching { Base64.decode(clean, Base64.DEFAULT) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("Account ID is not valid Base64 or a 64-bit numeric ID."))
        if (decoded.size != 8) {
            return Result.failure(
                IllegalArgumentException(
                    "Base64 PSN Account ID must decode to exactly 8 bytes (decoded ${decoded.size} bytes)."
                )
            )
        }
        return Result.success(Base64.encodeToString(decoded, Base64.NO_WRAP))
    }

    /** Decodes a normalized 8-byte Base64 PSN Account ID into raw bytes for libchiaki registration. */
    fun decodeAccountIdBytes(normalizedBase64: String): Result<ByteArray> {
        val decoded = runCatching { Base64.decode(normalizedBase64.trim(), Base64.NO_WRAP) }.getOrNull()
            ?: return Result.failure(IllegalArgumentException("Invalid Base64 PSN Account ID."))
        if (decoded.size != ChiakiNativeBridge.REQUIRED_PSN_ACCOUNT_ID_BYTES) {
            return Result.failure(
                IllegalArgumentException("PSN Account ID must decode to exactly 8 bytes.")
            )
        }
        return Result.success(decoded)
    }
}
