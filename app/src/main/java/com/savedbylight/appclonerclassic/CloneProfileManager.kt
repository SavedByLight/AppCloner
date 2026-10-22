package com.savedbylight.appclonerclassic

import android.content.Context

/**
 * Root-backed adapter for Android's OS-level clone profile. This works only on
 * ROM builds which implement android.os.usertype.profile.CLONE; stock Android
 * generally does not expose an end-to-end clone-profile feature to regular apps.
 */
data class CloneProfile(val userId: Int, val name: String, val createdByApp: Boolean)

class CloneProfileManager(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("clone_profiles", Context.MODE_PRIVATE)

    fun requireRoot() {
        if (!RootShell.isAvailable()) {
            throw IllegalStateException(
                "ROM clone profiles need root access. Grant root to App Cloner and try again. " +
                    "On an unrooted device, use Standalone APK clone instead."
            )
        }
    }

    fun suggestedName(): String {
        val names = preferences.getStringSet(KEY_TRACKED_PROFILES, mutableSetOf<String>()).orEmpty()
            .mapNotNull(::decodeTrackedProfile)
            .map { it.second }
            .toSet()
        var index = 1
        while ("App Clone $index" in names) index++
        return "App Clone $index"
    }

    fun listProfiles(): List<CloneProfile> {
        requireRoot()
        val usersResult = RootShell.run("pm list users")
        if (!usersResult.success) {
            throw IllegalStateException("Couldn't query Android users: ${usersResult.output.ifBlank { "pm list users failed" }}")
        }
        val dumpResult = RootShell.run("dumpsys user")
        val tracked = preferences.getStringSet(KEY_TRACKED_PROFILES, mutableSetOf<String>()).orEmpty()
            .mapNotNull(::decodeTrackedProfile)
            .associate { it.first to it.second }

        val userRows = Regex("UserInfo\\{(\\d+):([^:}]+):").findAll(usersResult.output).toList()
        val userNames = userRows.mapNotNull { row ->
            row.groupValues[1].toIntOrNull()?.let { it to row.groupValues[2].trim() }
        }.toMap()
        val userIds = userNames.keys
        val discovered = discoverCloneProfiles(dumpResult.output)
        val result = mutableMapOf<Int, CloneProfile>()

        // Profiles with the explicit clone type are native OS clone profiles.
        discovered.forEach { (id, name) ->
            if (id != 0 && id in userIds) result[id] = CloneProfile(id, name, id in tracked)
        }

        // Keep profiles created by this app visible on ROMs whose user dump
        // omits the profile type, but never show a profile that no longer exists.
        tracked.forEach { (id, name) ->
            if (id != 0 && id in userIds && id !in result && userNames[id] == name) {
                result[id] = CloneProfile(id, name, true)
            }
        }

        // User IDs may be reused after a profile is removed outside this app.
        // Do not treat a different user with the same ID as an app-managed clone.
        val stale = tracked.filter { (id, name) -> id !in userIds || (id !in discovered.map { it.first }.toSet() && userNames[id] != name) }
            .map { encodeTrackedProfile(it.key, it.value) }.toSet()
        if (stale.isNotEmpty()) {
            preferences.edit().putStringSet(KEY_TRACKED_PROFILES, preferences.getStringSet(KEY_TRACKED_PROFILES, mutableSetOf<String>()).orEmpty() - stale).apply()
        }
        return result.values.sortedBy { it.userId }
    }

    fun createProfile(requestedName: String = suggestedName()): CloneProfile {
        requireRoot()
        val name = requestedName.trim().replace('\n', ' ').replace('\r', ' ').take(48)
            .ifBlank { suggestedName() }
        val result = RootShell.run(
            "pm create-user --profileOf 0 --user-type android.os.usertype.profile.CLONE ${RootShell.quote(name)}"
        )
        val id = Regex("(?i)(?:created user id|user id)\\s*[:=]?\\s*(\\d+)")
            .find(result.output)?.groupValues?.get(1)?.toIntOrNull()
        if (!result.success || id == null || id == 0) {
            val reason = result.output.ifBlank { "The command failed without output." }
            val extra = if (
                reason.contains("unknown user type", true) ||
                reason.contains("not found", true) ||
                reason.contains("unsupported", true) ||
                reason.contains("not allowed", true)
            ) {
                " This ROM may not implement Android clone profiles; try Standalone APK clone instead."
            } else ""
            throw IllegalStateException("Couldn't create an Android clone profile: $reason.$extra")
        }

        val tracked = preferences.getStringSet(KEY_TRACKED_PROFILES, mutableSetOf<String>()).orEmpty().toMutableSet()
        tracked.removeAll { decodeTrackedProfile(it)?.first == id }
        tracked.add(encodeTrackedProfile(id, name))
        preferences.edit().putStringSet(KEY_TRACKED_PROFILES, tracked).apply()
        // Starting the profile is best-effort. Installation can proceed while
        // the profile is stopped; Android/ROM policy determines how it is surfaced.
        val start = RootShell.run("am start-user -w $id")
        Logger.log("Clone profile $id created ('$name'); start-user exit=${start.exitCode}: ${start.output}")
        return CloneProfile(id, name, true)
    }

    fun installApp(packageName: String, profile: CloneProfile) {
        requireRoot()
        requireSafePackageName(packageName)
        ensureProfileExists(profile.userId)
        val result = RootShell.run("pm install-existing --user ${profile.userId} ${RootShell.quote(packageName)}")
        Logger.log("ROM clone: install-existing ${profile.userId}/$packageName -> exit=${result.exitCode}: ${result.output}")
        if (!result.success || result.output.contains("Failure", ignoreCase = true)) {
            throw IllegalStateException(
                "Android couldn't install $packageName into ${profile.name}: ${result.output.ifBlank { "pm install-existing failed" }}"
            )
        }
        registerInstalledApp(packageName, profile.userId)
    }

    /**
     * Apps available in clone profiles: registry + live `pm list packages --user`
     * so installs done before tracking still appear in My Clones.
     */
    fun listInstalledApps(): List<Pair<String, Int>> {
        val found = linkedSetOf<Pair<String, Int>>()
        preferences.getStringSet(KEY_PROFILE_APPS, emptySet()).orEmpty().forEach { raw ->
            val split = raw.lastIndexOf('|')
            if (split <= 0) return@forEach
            val pkg = raw.substring(0, split)
            val uid = raw.substring(split + 1).toIntOrNull() ?: return@forEach
            if (uid > 0) found.add(pkg to uid)
        }
        // Scan each known clone profile for packages also present on user 0
        // (dual-install). Skip the profile's own packages we don't care about.
        runCatching {
            listProfiles().forEach { profile ->
                val result = RootShell.run("pm list packages --user ${profile.userId}")
                if (!result.success) return@forEach
                result.output.lineSequence()
                    .map { it.removePrefix("package:").trim() }
                    .filter { it.isNotEmpty() && it.contains('.') }
                    .forEach { pkg -> found.add(pkg to profile.userId) }
            }
        }
        return found.sortedWith(compareBy({ it.first }, { it.second }))
    }

    fun unregisterInstalledApp(packageName: String, userId: Int) {
        val key = "$packageName|$userId"
        val set = preferences.getStringSet(KEY_PROFILE_APPS, mutableSetOf()).orEmpty().toMutableSet()
        set.remove(key)
        preferences.edit().putStringSet(KEY_PROFILE_APPS, set).apply()
    }

    private fun registerInstalledApp(packageName: String, userId: Int) {
        val key = "$packageName|$userId"
        val set = preferences.getStringSet(KEY_PROFILE_APPS, mutableSetOf()).orEmpty().toMutableSet()
        set.add(key)
        preferences.edit().putStringSet(KEY_PROFILE_APPS, set).apply()
    }

    fun launchApp(packageName: String, profile: CloneProfile): Boolean {
        requireRoot()
        requireSafePackageName(packageName)
        ensureProfileExists(profile.userId)
        val uid = profile.userId
        val pkg = RootShell.quote(packageName)

        // Prefer am start --user (monkey does not support --user on many devices).
        var last = RootShell.run(
            "am start --user $uid -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER -p $pkg"
        )
        Logger.log("ROM clone: launch am-start ${uid}/$packageName -> exit=${last.exitCode}: ${last.output}")
        if (last.success && !looksLikeStartFailure(last.output)) return true

        // Resolve launcher activity for this user, then am start -n
        val resolve = RootShell.run(
            "cmd package resolve-activity --user $uid --brief $pkg"
        )
        Logger.log("ROM clone: resolve-activity ${uid}/$packageName -> ${resolve.output}")
        val component = resolve.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.contains('/') && !it.startsWith("No activity", ignoreCase = true) }
        if (component != null) {
            last = RootShell.run("am start --user $uid -n ${RootShell.quote(component)}")
            Logger.log("ROM clone: launch -n $component -> exit=${last.exitCode}: ${last.output}")
            if (last.success && !looksLikeStartFailure(last.output)) return true
        }

        // Some ROMs accept am start-activity --user
        last = RootShell.run(
            "am start-activity --user $uid -a android.intent.action.MAIN " +
                "-c android.intent.category.LAUNCHER -p $pkg"
        )
        Logger.log("ROM clone: launch start-activity ${uid}/$packageName -> exit=${last.exitCode}: ${last.output}")
        return last.success && !looksLikeStartFailure(last.output)
    }

    private fun looksLikeStartFailure(output: String): Boolean {
        val o = output.lowercase()
        return o.contains("error") ||
            o.contains("exception") ||
            o.contains("not found") ||
            o.contains("does not exist") ||
            o.contains("unable to resolve") ||
            o.contains("no activities")
    }

    fun removeProfile(profile: CloneProfile) {
        requireRoot()
        if (profile.userId <= 0) throw IllegalArgumentException("Refusing to remove Android's primary user.")
        ensureProfileExists(profile.userId)
        val result = RootShell.run("pm remove-user ${profile.userId}")
        Logger.log("ROM clone: remove profile ${profile.userId} -> exit=${result.exitCode}: ${result.output}")
        if (!result.success || result.output.contains("Error", ignoreCase = true) || result.output.contains("Failure", ignoreCase = true)) {
            throw IllegalStateException("Couldn't remove ${profile.name}: ${result.output.ifBlank { "pm remove-user failed" }}")
        }
        val tracked = preferences.getStringSet(KEY_TRACKED_PROFILES, mutableSetOf<String>()).orEmpty()
            .filterNot { decodeTrackedProfile(it)?.first == profile.userId }
            .toSet()
        preferences.edit().putStringSet(KEY_TRACKED_PROFILES, tracked).apply()
    }

    private fun ensureProfileExists(userId: Int) {
        if (userId <= 0) throw IllegalArgumentException("Invalid clone profile ID: $userId")
        val result = RootShell.run("pm list users")
        if (!result.success || !Regex("UserInfo\\{$userId:").containsMatchIn(result.output)) {
            throw IllegalStateException("Clone profile $userId no longer exists. Refresh the profile list.")
        }
    }

    private fun requireSafePackageName(packageName: String) {
        if (!PACKAGE_NAME.matches(packageName)) throw IllegalArgumentException("Invalid package name: $packageName")
    }

    private fun discoverCloneProfiles(dump: String): List<Pair<Int, String>> {
        // Use raw string and escape braces. An unescaped '}' after [^}]* caused
        // PatternSyntaxException on device ("Syntax error in regexp pattern near index 39").
        val userInfo = Regex("""UserInfo\{(\d+):([^:}]+):[^\}]*\}""")
        val matches = userInfo.findAll(dump).toList()
        return matches.mapIndexedNotNull { index, match ->
            val startIdx = match.range.last + 1
            val endIdx = matches.getOrNull(index + 1)?.range?.first ?: dump.length
            val block = dump.substring(
                startIdx.coerceAtMost(dump.length),
                endIdx.coerceAtLeast(startIdx).coerceAtMost(dump.length)
            )
            val name = match.groupValues[2].trim().ifBlank { "Clone profile" }
            val isProfile = match.value.contains("isProfile", ignoreCase = true) ||
                block.contains("isProfile", ignoreCase = true)
            val isClone = block.contains("android.os.usertype.profile.CLONE", ignoreCase = true) ||
                block.contains("profile.CLONE", ignoreCase = true) ||
                (isProfile && name.contains("clone", ignoreCase = true)) ||
                name.contains("App Clone", ignoreCase = true)
            val id = match.groupValues[1].toIntOrNull()
            if (isClone && id != null && id != 0) id to name else null
        }
    }

    private fun decodeTrackedProfile(value: String): Pair<Int, String>? {
        val split = value.indexOf('|')
        if (split <= 0) return null
        val id = value.substring(0, split).toIntOrNull() ?: return null
        return id to value.substring(split + 1)
    }

    private fun encodeTrackedProfile(id: Int, name: String): String = "$id|$name"

    companion object {
        private const val KEY_TRACKED_PROFILES = "tracked_profile_entries"
        private const val KEY_PROFILE_APPS = "profile_installed_apps"
        private val PACKAGE_NAME = Regex("[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)+")
    }
}
