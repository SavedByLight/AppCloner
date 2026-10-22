package com.savedbylight.appclonerclassic

import android.content.Context
import android.content.pm.PackageManager
import org.json.JSONArray

/**
 * Utilities for generating stealth clone package names and tracking them.
 * Sequential .clone1, .clone2 suffixes are trivially pattern-matched by
 * apps doing simple package-name heuristics. Random alphanumeric suffixes
 * (e.g. com.foo.xk7m9a) are far less obvious and avoid collisions
 * without leaking clone count.
 *
 * Android package-name rules (PackageParser): each dot-separated segment
 * must match [a-zA-Z][a-zA-Z0-9_]*. A segment may NOT start with a digit —
 * names like com.snapchat.android.8x1pyf fail with
 * INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME ("bad character '8'").
 */
object CloneUtils {

    private const val PREFS_CLONES = "clone_registry"
    private const val KEY_CLONE_LIST = "clone_list"
    private const val SUFFIX_LENGTH = 6

    /** First character of a package segment must be a letter. */
    private val FIRST_CHARS = ('a'..'z').toList()
    /** Remaining characters may be letter or digit. */
    private val REST_CHARS = ('a'..'z') + ('0'..'9')

    /**
     * Random suffix that is a valid Android package-name segment:
     * letter first, then letters/digits only.
     */
    fun randomSuffix(length: Int = SUFFIX_LENGTH): String {
        require(length >= 1)
        val first = FIRST_CHARS.random()
        if (length == 1) return first.toString()
        val rest = (1 until length).map { REST_CHARS.random() }.joinToString("")
        return first + rest
    }

    /** True if [name] is a valid Android application package name. */
    fun isValidPackageName(name: String): Boolean {
        if (name.isEmpty() || name.startsWith('.') || name.endsWith('.')) return false
        val segmentRegex = Regex("^[a-zA-Z][a-zA-Z0-9_]*$")
        return name.split('.').all { it.isNotEmpty() && segmentRegex.matches(it) }
    }

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
            if (!isValidPackageName(candidate) || candidate in existing) {
                attempts++
                continue
            }
            try {
                pm.getPackageInfo(candidate, 0)
                attempts++
                continue
            } catch (_: PackageManager.NameNotFoundException) {
                return candidate
            }
        }
        // Letter-prefixed base36 timestamp so the last segment never starts with a digit
        val ts = java.lang.Long.toString(System.currentTimeMillis(), 36)
        val safeTs = if (ts.first().isDigit()) "t$ts" else ts
        return "$basePackageName.$safeTs"
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
     * Registry first, then base + dot + 6 alphanumeric chars (letter-leading).
     */
    fun isStealthClone(context: Context, packageName: String): Boolean {
        if (packageName in loadCloneList(context)) return true
        return packageName.matches(Regex("""^([a-zA-Z][a-zA-Z0-9_.]*)\.[a-z][a-z0-9]{5}$"""))
    }

    fun extractBasePackage(clonePackage: String): String? {
        val parts = clonePackage.split(".")
        if (parts.size >= 2 && parts.last().length == SUFFIX_LENGTH) {
            return parts.dropLast(1).joinToString(".")
        }
        return null
    }
}
