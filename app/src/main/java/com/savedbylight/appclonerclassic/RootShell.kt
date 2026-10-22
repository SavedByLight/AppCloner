package com.savedbylight.appclonerclassic

import java.io.DataOutputStream

/** Runs small, explicit shell commands through the root manager when available. */
internal object RootShell {
    data class Result(val exitCode: Int, val output: String) {
        val success: Boolean get() = exitCode == 0
    }

    fun isAvailable(): Boolean = run("id").let {
        it.success && Regex("uid=0(?:\\(|\\b)").containsMatchIn(it.output)
    }

    fun run(command: String): Result {
        val process = try {
            ProcessBuilder("su").redirectErrorStream(true).start()
        } catch (e: Exception) {
            return Result(-1, "Unable to start su: ${e.message}")
        }

        try {
            DataOutputStream(process.outputStream).use { stdin ->
                stdin.writeBytes("$command\n")
                stdin.writeBytes("__appcloner_rc=\$?\n")
                stdin.writeBytes("printf '\\n__APPCLONER_EXIT__=%s\\n' \"\$__appcloner_rc\"\n")
                stdin.writeBytes("exit\n")
                stdin.flush()
            }

            val combined = process.inputStream.bufferedReader().use { it.readText() }
            val shellExit = runCatching { process.waitFor() }.getOrDefault(-1)
            val marker = Regex("(?m)^__APPCLONER_EXIT__=(\\d+)\\s*$").find(combined)
            if (marker == null) return Result(shellExit, combined.trim())
            val exitCode = marker.groupValues[1].toIntOrNull() ?: shellExit
            val output = combined.substring(0, marker.range.first).trim()
            return Result(exitCode, output)
        } catch (e: Exception) {
            runCatching { process.destroyForcibly() }
            return Result(-1, "Root command failed: ${e.message}")
        }
    }

    /** POSIX single-quote escaping for package names, profile names, and paths. */
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
