package com.example.shizukufileinjector

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class FileInjectorService : Service() {

    companion object {
        private const val TAG = "FileInjectorService"
    }

    private val binder = object : IFileInjectorService.Stub() {
        
        override fun injectFile(srcPath: String?, destPath: String?, chmod: String?): String {
            return injectAssetsFolderWithChmod(chmod ?: "777")
        }

        override fun runShell(command: String): String {
            return try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                output
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
        }

        override fun injectAssetsFolder(): String {
            return injectAssetsFolderWithChmod("777")
        }

        private fun injectAssetsFolderWithChmod(chmodVal: String): String {
            return try {
                val targetDir = File("/storage/emulated/0/Android/data/com.dts.freefirth/files")
                if (!targetDir.exists()) {
                    runShell("mkdir -p ${targetDir.absolutePath}")
                }

                // Copy files from local assets/anshu-on-top/
                val assetManager = assets
                val files = assetManager.list("anshu-on-top") ?: emptyArray()

                if (files.isEmpty()) {
                    return "FAILED: No files found in assets/anshu-on-top/"
                }

                for (filename in files) {
                    assetManager.open("anshu-on-top/$filename").use { inputStream ->
                        val outFile = File(targetDir, filename)
                        FileOutputStream(outFile).use { outputStream ->
                            inputStream.copyTo(outputStream)
                        }
                        runShell("chmod $chmodVal ${outFile.absolutePath}")
                    }
                }
                ""
            } catch (e: Exception) {
                "FAILED: ${e.message}"
            }
        }

        override fun deleteInjectedFiles(): String {
            return try {
                runShell("rm -rf /storage/emulated/0/Android/data/com.dts.freefirth/files/*")
                "SUCCESS"
            } catch (e: Exception) {
                "FAILED: ${e.message}"
            }
        }

        override fun destroy() {
            try {
                stopSelf()
            } catch (_: Exception) {}
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Log.d(TAG, "Service bound successfully via Shizuku")
        return binder
    }
}
