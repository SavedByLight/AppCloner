package com.savedbylight.appcloner

import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.File
import java.io.InputStreamReader

/**
 * Installs already-signed APKs by driving the `pm` command line tool
 * through a root (`su`) shell, instead of handing the APK to the system
 * PackageInstaller UI via ACTION_VIEW or a user-facing session confirmation.
 *
 * This is the same session-based flow PackageInstaller itself uses under
 * the hood (`pm install-create` / `install-write` / `install-commit`) —
 * the only difference is the shell driving it runs as root, so there's no
 * confirmation dialog and no dependency on any particular installer app
 * being present. Requires the device to actually be rooted and grant this
 * app's `su` request; call [isRootAvailable] first if you want to decide
 * whether to fall back to [CloneEngine]'s PackageInstaller path instead.
 */
object RootInstaller {

    /**
     * Quick capability check: does `su` exist and will it grant us root?
     * Safe to call from a background thread; runs a trivial `id` command.
     */
    fun isRootAvailable(): Boolean {
        return try {
            val result = runAsRoot("id")
            result.exitCode == 0 && result.stdout.contains("uid=0")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Installs [apkFiles] (base APK first, then any splits) as a single
     * atomic session, entirely through root shell commands — no
     * PackageInstaller UI, no FileProvider/ACTION_VIEW handoff.
     *
     * @param packageName optional expected package name for the session
     *   (`pm install-create -p <name>`); matches what
     *   [CloneEngine.installPackageSession] passes via
     *   `SessionParams.setAppPackageName`.
     * @return true if the commit succeeded.
     */
    fun installApksAsRoot(packageName: String?, apkFiles: List<File>): Boolean {
        if (apkFiles.isEmpty()) {
            throw IllegalArgumentException("No APK files provided for root install.")
        }
        apkFiles.forEach {
            if (!it.exists()) throw java.io.IOException("APK file does not exist: ${it.absolutePath}")
        }

        // -r: replace existing install if present (re-cloning over a
        // previous clone of the same target package).
        // -g: grant all runtime permissions the manifest requests, since
        // there's no installer UI here for the user to grant them through
        // post-install.
        val createArgs = mutableListOf("pm", "install-create", "-r", "-g")
        if (!packageName.isNullOrEmpty()) {
            createArgs += listOf("-p", packageName)
        }
        val createResult = runAsRoot(createArgs.joinToString(" "))
        Logger.log("root: ${createArgs.joinToString(" ")} -> exit=${createResult.exitCode} out=${createResult.stdout.trim()} err=${createResult.stderr.trim()}")
        if (createResult.exitCode != 0) {
            throw IllegalStateException("pm install-create failed: ${createResult.stderr.ifBlank { createResult.stdout }}")
        }

        // Output looks like: "Success: created install session [1234567890]"
        val sessionId = SESSION_ID_REGEX.find(createResult.stdout)?.groupValues?.get(1)
            ?: throw IllegalStateException("Could not parse session id from: ${createResult.stdout}")

        try {
            apkFiles.forEachIndexed { index, file ->
                val streamName = if (index == 0) "base.apk" else "split_$index.apk"
                // File already lives in this app's cache dir, which root
                // can read directly by path — no need to stream bytes
                // through the su pipe ourselves.
                val writeCmd = "pm install-write -S ${file.length()} $sessionId $streamName '${file.absolutePath}'"
                val writeResult = runAsRoot(writeCmd)
                Logger.log("root: install-write $streamName (${file.length()} bytes) -> exit=${writeResult.exitCode} err=${writeResult.stderr.trim()}")
                if (writeResult.exitCode != 0) {
                    throw IllegalStateException("pm install-write failed for $streamName: ${writeResult.stderr.ifBlank { writeResult.stdout }}")
                }
            }

            val commitResult = runAsRoot("pm install-commit $sessionId")
            Logger.log("root: install-commit $sessionId -> exit=${commitResult.exitCode} out=${commitResult.stdout.trim()} err=${commitResult.stderr.trim()}")
            if (commitResult.exitCode != 0 || !commitResult.stdout.contains("Success")) {
                throw IllegalStateException("pm install-commit failed: ${commitResult.stderr.ifBlank { commitResult.stdout }}")
            }

            Logger.log("Root install committed for session $sessionId (${apkFiles.size} APK(s))")
            return true
        } catch (e: Exception) {
            // Best-effort cleanup so a failed clone doesn't leave a
            // dangling session around.
            runCatching { runAsRoot("pm install-abandon $sessionId") }
            throw e
        }
    }

    private data class ShellResult(val exitCode: Int, val stdout: String, val stderr: String)

    /**
     * Runs a single command line as root via `su -c`, capturing stdout,
     * stderr, and exit code. Uses `Runtime.exec("su")` + writing to its
     * stdin (rather than `su -c "<cmd>"` as exec argv) so shell quoting in
     * [command] (e.g. the single-quoted file path above) is interpreted by
     * the root shell itself, matching how `su -c` is normally invoked
     * interactively.
     */
    private fun runAsRoot(command: String): ShellResult {
        val process = ProcessBuilder("su").redirectErrorStream(false).start()
        DataOutputStream(process.outputStream).use { stdin ->
            stdin.writeBytes("$command\n")
            stdin.writeBytes("exit \$?\n")
            stdin.flush()
        }
        val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
        val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
        val exitCode = process.waitFor()
        return ShellResult(exitCode, stdout, stderr)
    }

    private val SESSION_ID_REGEX = Regex("""\[(\d+)]""")
}
