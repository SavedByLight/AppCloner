package com.savedbylight.appclonerclassic

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray

/**
 * Utilities for generating stealth clone package names and tracking them.
 * Sequential .clone1, .clone2 suffixes are trivially pattern-matched by
 * apps doing simple package-name heuristics. Random alphanumeric suffixes
 * (e.g. com.foo.x7k9m2) are far less obvious and avoid collisions
 * without leaking clone count.
 */
object CloneUtils {

    private const val PREFS_CLONES = "clone_registry"
    private const val KEY_CLONE_LIST = "clone_list"
    private const val SUFFIX_LENGTH = 6

    private val RANDOM_CHARS = ('a'..'z') + ('0'..'9')

    fun randomSuffix(length: Int = SUFFIX_LENGTH): String =
        (1..length).map { RANDOM_CHARS.random() }.joinToString("")

    /**
     * Generates a candidate package name and verifies it is not already
     * installed. Does NOT use sequential numbering — every suffix is random.
     */
    fun nextStealthClonePackage(context: Context, basePackageName: String): String {
        val pm = context.packageManager
        val existing = loadCloneList(context)
        var attempts = 0
        while (attempts < 1000) {
            val candidate = "$basePackageName.${randomSuffix()}"
            if (candidate in existing) {
                attempts++
                continue
            }
            try {
                pm.getPackageInfo(candidate, 0)
                // already installed by something else → try again
                attempts++
                continue
            } catch (_: PackageManager.NameNotFoundException) {
                // free slot
                return candidate
            }
        }
        // Fallback with timestamp to guarantee uniqueness
        return "$basePackageName.${System.currentTimeMillis()}"
    }

    fun registerClone(context: Context, packageName: String) {
        val list = loadCloneList(context).toMutableList()
        if (packageName !in list) {
            list.add(packageName)
            saveCloneList(context, list)
        }
    }

    fun unregisterClone(context: Context, packageName: String) {
        val list = loadCloneList(context).toMutableList()
        list.remove(packageName)
        saveCloneList(context, list)
    }

    fun loadCloneList(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS_CLONES, Context.MODE_PRIVATE)
            .getString(KEY_CLONE_LIST, "[]") ?: "[]"
        return try {
            JSONArray(raw).let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveCloneList(context: Context, list: List<String>) {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        context.getSharedPreferences(PREFS_CLONES, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CLONE_LIST, arr.toString())
            .apply()
    }

    /**
     * Heuristic: does this package name look like one of our stealth clones?
     * We check the registry first, then fall back to a loose pattern match
     * (base package + dot + 6 alphanumeric chars) so existing clones
     * created before this update are still recognised.
     */
    fun isStealthClone(context: Context, packageName: String): Boolean {
        if (packageName in loadCloneList(context)) return true
        // Loose fallback for legacy / unregistered clones
        return packageName.matches(Regex("""^([a-zA-Z0-9_.]+)\.[a-z0-9]{6}$"""))
    }

    fun extractBasePackage(clonePackage: String): String? {
        // Registry-based lookup: find longest registered base that is a prefix
        // Fallback: strip last 7 chars (dot + 6 suffix)
        val parts = clonePackage.split(".")
        if (parts.size >= 2 && parts.last().length == SUFFIX_LENGTH) {
            return parts.dropLast(1).joinToString(".")
        }
        return null
    }
}
