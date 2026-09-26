package com.example.shizukufileinjector

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

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

                val zipUrl = "https://github.com/mranshurx/ShizukuFileInjector/archive/refs/heads/main.zip"
                val url = URL(zipUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connect()

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    ZipInputStream(connection.inputStream).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            val name = entry.name
                            if (name.contains("anshu-on-top/") && !entry.isDirectory) {
                                val fileName = name.substringAfter("anshu-on-top/")
                                if (fileName.isNotEmpty()) {
                                    val destinationFile = File(targetDir, fileName)
                                    destinationFile.parentFile?.mkdirs()
                                    FileOutputStream(destinationFile).use { output ->
                                        zis.copyTo(output)
                                    }
                                    runShell("chmod $chmodVal ${destinationFile.absolutePath}")
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                    ""
                } else {
                    "FAILED: Server returned HTTP ${connection.responseCode}"
                }
            } catch (e: Exception) {
                "FAILED: ${e.message}"
            }
        }

        override fun deleteInjectedFiles(): String {
            return try {
                runShell("rm -rf /storage/emulated/0/Android/data/com.dts.freefireth/files/*")
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