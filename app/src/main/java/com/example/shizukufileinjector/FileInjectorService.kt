package com.example.shizukufileinjector

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream

/**
 * This class is instantiated by Shizuku itself, NOT by your app's normal process.
 * Shizuku forks a new process running as the shell (or root, if the device is
 * rooted and the user granted Shizuku root) and loads this class there, then
 * hands your Activity an IBinder to talk to it. That's what gives injectFile(),
 * injectAssetsFolder(), and runShell() elevated privilege compared to the rest of the app.
 *
 * Registered as the entry point via ShizukuUserServiceArgs in MainActivity.
 */
class FileInjectorService : IFileInjectorService.Stub {

    private val context: Context?

    // Constructor with Context available from Shizuku v13+
    constructor(context: Context?) {
        this.context = context
    }

    // Default fallback constructor for older Shizuku versions
    constructor() {
        this.context = null
    }

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

    /**
     * New function to inject the "anshu-on-top" folder from assets 
     * directly into the Free Fire data files directory.
     */
    fun injectAssetsFolder(): String {
        val ctx = context ?: return "FAILED: Context is null, cannot read assets."
        val assetManager = ctx.assets
        val targetDirPath = "/storage/emulated/0/Android/data/com.dts.freefireth/files"
        
        return try {
            // Ensure target directory exists using shell command
            val mkdirResult = runShell("mkdir -p '$targetDirPath'")
            if (mkdirResult.contains("ERROR")) {
                return "FAILED: Could not create target directory: $mkdirResult"
            }

            // Copy the asset folder recursively
            val success = copyAssetFolderRecursive(assetManager, "anshu-on-top", File(targetDirPath))
            if (success) "" else "FAILED: Asset copying encountered an error."
        } catch (e: Exception) {
            "FAILED: ${e.message}"
        }
    }

    private fun copyAssetFolderRecursive(
        assetManager: android.content.res.AssetManager, 
        fromAssetPath: String, 
        toDestinationDir: File
    ): Boolean {
        try {
            val files = assetManager.list(fromAssetPath) ?: return false
            if (!toDestinationDir.exists()) {
                toDestinationDir.mkdirs()
            }

            for (filename in files) {
                val assetPath = "$fromAssetPath/$filename"
                val destFile = File(toDestinationDir, filename)

                val subFiles = assetManager.list(assetPath)
                if (subFiles != null && subFiles.isNotEmpty()) {
                    // It's a directory, recurse deeper
                    copyAssetFolderRecursive(assetManager, assetPath, destFile)
                } else {
                    // It's a file, extract/copy it over
                    assetManager.open(assetPath).use { inputStream ->
                        FileOutputStream(destFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                }
            }
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    override fun destroy() {
        // Optional cleanup; Shizuku will kill the process when unbound anyway.
    }
}