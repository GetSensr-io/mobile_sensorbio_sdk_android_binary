package com.sensorbio.example

import android.content.Context
import android.content.SharedPreferences

/**
 * The example app's credential store: the organization SDK Key, the `client_sdk_user_id`, and the
 * `organization_id` the last token exchange resolved. Persisted so relaunch-and-register logs you
 * straight back into the same demo user instead of retyping a key on every run.
 *
 * **Your app stores none of this.** It holds no SDK Key at all (its backend does), and its user id
 * comes from its own user store. This exists because the example app is standing in for that
 * backend — see [SdkTokenExchange].
 *
 * Plain `SharedPreferences`, deliberately un-encrypted: a key you can read out of a debug build's
 * prefs is one more reminder that a device is not where it belongs.
 */
class Creds(private val prefs: SharedPreferences) {

    fun sdkKey(): String = prefs.getString(KEY_SDK_KEY, "").orEmpty()
    fun userId(): String = prefs.getString(KEY_USER_ID, "").orEmpty()

    /** Whatever the last exchange resolved — the key determines the org, so nobody types this. */
    fun orgId(): String = prefs.getString(KEY_ORG_ID, "").orEmpty()

    fun save(sdkKey: String, userId: String) {
        prefs.edit()
            .putString(KEY_SDK_KEY, sdkKey.trim())
            .putString(KEY_USER_ID, userId.trim())
            .apply()
    }

    fun saveOrgId(orgId: String) {
        prefs.edit().putString(KEY_ORG_ID, orgId).apply()
    }

    companion object {
        private const val PREFS = "example_prefs"
        private const val KEY_SDK_KEY = "sdk_key"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_ORG_ID = "org_id"

        fun from(context: Context): Creds =
            Creds(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
