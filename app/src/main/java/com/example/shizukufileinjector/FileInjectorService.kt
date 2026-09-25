package com.example.shizukufileinjector

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * This class is instantiated by Shizuku itself, NOT by your app's normal process.
 * Shizuku forks a new process running as the shell (or root, if the device is
 * rooted and the user granted Shizuku root) and loads this class there, then
 * hands your Activity an IBinder to talk to it. That's what gives injectFile()
 * and runShell() elevated privilege compared to the rest of the app.
 *
 * Registered as the entry point via ShizukuUserServiceArgs in MainActivity.
 */
class FileInjectorService : IFileInjectorService.Stub() {

    override fun runShell(command: String): String {
        return try {
            val process = ProcessBuilder("sh", "-c", command)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().use(BufferedReader::readText)
            process.waitFor()
            output
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }

    override fun injectFile(srcPath: String, destPath: String, chmod: String): String {
        return try {
            // Make sure the destination directory exists.
            val destDir = destPath.substringBeforeLast('/')
            var out = runShell("mkdir -p '$destDir' && cp -f '$srcPath' '$destPath' 2>&1")

            if (out.contains("No such file") || out.contains("Permission denied") ||
                out.contains("Operation not permitted") || out.contains("ERROR")
            ) {
                return "FAILED: $out"
            }

            if (chmod.isNotBlank()) {
                out = runShell("chmod $chmod '$destPath' 2>&1")
                if (out.isNotBlank()) return "COPIED but chmod failed: $out"
            }

            "" // empty string = success
        } catch (e: Exception) {
            "FAILED: ${e.message}"
        }
    }

    override fun destroy() {
        // Optional cleanup; Shizuku will kill the process when unbound anyway.
    }
}
