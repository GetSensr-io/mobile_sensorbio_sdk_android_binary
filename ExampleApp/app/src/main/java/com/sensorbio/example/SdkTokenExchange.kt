package com.sensorbio.example

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sensorbio.sensorbiosdk.SensorBioSDK
import com.sensorbio.sensorbiosdk.datatypes.SB_Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The SDK-Key → SDK-token exchange (`POST /sdk/v1/token`), performed here in the app **only because
 * this is the example app**.
 *
 * ## Read this before copying it
 *
 * In a real integration this code belongs on **your server**, not in your app. The organization SDK
 * Key (`sbsk_…`) is long-lived, org-wide, and identical for every one of your users: whoever holds it
 * can mint tokens for any user id they can guess or copy out of a dashboard. That is the whole reason
 * the exchange exists — the key stays on your backend, and the only credential that ever reaches a
 * device is a single-use `sdk_token` (`sbst_…`) worth one register-or-login, for one user, for a few
 * minutes.
 *
 * The shape your backend should serve is:
 *
 *  1. authenticate your own user, however you already do;
 *  2. `POST /sdk/v1/token` with `Authorization: SDKKey sbsk_…`;
 *  3. return `sdk_token` + `organization_id` to the app.
 *
 * The app then hands both to the SDK as `SensorBioSDK.sdkCredentials`, or installs
 * [makeProvider] so the SDK can ask for a token whenever it needs one. Steps 2 and 3 are what this
 * file fakes, so the example app can demonstrate the token flow end to end without a backend.
 *
 * `SDK_INTERFACE.md` § 6 is the guide to building the real thing: the contract, reference
 * implementations, the errors, and key rotation.
 *
 * Tokens are single use: mint a fresh one for every register and never cache one. A reused token
 * fails inside `registerUser` as an authentication error, far from its cause.
 */
object SdkTokenExchange {

    /**
     * The two fields a real backend must return. `sdkKeyId` / `expiresInSeconds` are echoed for the
     * Profile screen — diagnostics, not inputs.
     */
    data class MintedToken(
        val sdkToken: String,
        val organizationId: String,
        val sdkKeyId: String,
        val expiresInSeconds: Int,
    )

    /** Sensor Bio answered and said no. [guidance] is the thing to actually do about it. */
    class RejectedException(val status: Int, val detail: String, val guidance: String) :
        IOException(
            buildString {
                append("Token exchange failed (HTTP ").append(status).append(')')
                if (detail.isNotBlank()) append(": ").append(detail)
                if (guidance.isNotBlank()) append(" — ").append(guidance)
            }
        )

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /** The HTTP/2 requirement is why this is OkHttp and not HttpURLConnection, which is HTTP/1.1. */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Mints one fresh `sdk_token` for [sdkKey].
     *
     * `organizationId` comes back from the exchange, so the app never has to be told its own org id —
     * the key already determines it. That is why the register form no longer asks for one.
     */
    suspend fun mintToken(
        sdkKey: String,
        environment: SB_Environment = SensorBioSDK.environment,
    ): MintedToken = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl(environment) + "/sdk/v1/token")
            .header("Authorization", "SDKKey $sdkKey")
            .header("Accept", "application/json")
            // The body is optional and so is every field in it: an empty object takes the 5-minute
            // default TTL and the presented key as the anchor.
            .post("{}".toRequestBody(JSON))
            .header("Cache-Control", "no-store")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // Sensor Bio's JSON error shape is {status, title, detail}; a proxy or a 464
                // (HTTP/1.x) may answer with neither.
                val detail = runCatching {
                    val json = JSONObject(body)
                    json.optString("detail").ifBlank { json.optString("title") }
                }.getOrNull().orEmpty().ifBlank { body.take(300).ifBlank { "empty response body" } }
                throw RejectedException(response.code, detail, guidanceFor(response.code))
            }

            val json = runCatching { JSONObject(body) }.getOrElse {
                throw IOException("Unexpected token-exchange response: ${body.take(300)}")
            }
            val token = json.optString("sdk_token")
            val orgId = json.optString("organization_id")
            if (token.isBlank() || orgId.isBlank()) {
                throw IOException("Unexpected token-exchange response: missing sdk_token or organization_id")
            }
            MintedToken(
                sdkToken = token,
                organizationId = orgId,
                sdkKeyId = json.optString("sdk_key_id"),
                expiresInSeconds = json.optInt("expires_in_seconds"),
            )
        }
    }


    /**
     * The REST public API, which is a different host family from the SDK's gRPC one
     * ([SB_Environment.host]) — hence the second mapping here.
     */
    private fun baseUrl(environment: SB_Environment): String = when (environment) {
        SB_Environment.DEVELOPMENT -> "https://staging.api.sensorbio.com"
        else -> "https://api.sensorbio.com"
    }

    /** Guidance per status, so the four realistic failures read the same as they do on iOS. */
    private fun guidanceFor(status: Int): String = when (status) {
        401 ->
            "The SDK Key was rejected. Sensor Bio answers identically for a key that is unknown, " +
                "revoked, or expired, so check all three: that the whole key was copied, that it " +
                "hasn't been revoked in Developer Settings, and that it hasn't passed its expiry."
        403 -> "The organization has no usable SDK Key. Create one under Developer Settings."
        400 -> "Sensor Bio rejected the request parameters."
        404 ->
            "No exchange endpoint at this base URL — check the environment toggle, and that the " +
                "deployment you're pointing at has the SDK token exchange released."
        in 500..599 ->
            "Sensor Bio returned a server error. Retry; if it persists, contact developers@sensorbio.com."
        else -> ""
    }
}

/**
 * The token the current launch minted, so the Profile screen can show what the register call actually
 * presented.
 *
 * **In memory only, and deliberately so.** A minted token is spent by the register that used it, and
 * persisting a credential — even a spent one — teaches the wrong habit for a file customers read.
 * After a cold launch that hydrates a session there is nothing here, which is the honest answer: the
 * token that created that session no longer exists anywhere.
 */
object SdkTokenRecord {
    var token by mutableStateOf<SdkTokenExchange.MintedToken?>(null)
        private set

    /** Wall-clock epoch millis of the mint, for the expiry line on the Profile screen. */
    var mintedAtMillis by mutableStateOf<Long?>(null)
        private set

    fun record(minted: SdkTokenExchange.MintedToken) {
        token = minted
        mintedAtMillis = System.currentTimeMillis()
    }
}
