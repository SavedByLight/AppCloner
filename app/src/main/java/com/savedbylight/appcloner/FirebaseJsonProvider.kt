package com.savedbylight.appcloner

/**
 * Holds a user‑supplied replacement google‑services.json.
 * The clone engine will use this to replace the original Firebase config
 * inside the cloned APK.
 */
object FirebaseJsonProvider {
    @Volatile
    private var replacementJson: ByteArray? = null

    fun setJson(json: ByteArray?) {
        replacementJson = json
        Logger.log("Firebase JSON ${if (json != null) "set (${json.size} bytes)" else "cleared"}")
    }

    fun getJson(): ByteArray? = replacementJson

    fun clear() {
        replacementJson = null
        Logger.log("Firebase JSON cleared")
    }
}