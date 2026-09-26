package com.example.shizukufileinjector

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class FileInjectorService : Service() {

    private val binder = object : IFileInjectorService.Stub() {
        
        override fun injectFile(srcPath: String?, destPath: String?, chmod: String?): String {
            return try {
                val targetDir = File(destPath ?: "/storage/emulated/0/Android/data/com.dts.freefireth/files/netcache")
                if (!targetDir.exists()) {
                    runShell("mkdir -p ${targetDir.absolutePath}")
                }

                // Remote GitHub raw link pointing to your hosted file under 'anshu-on-top'
                val remoteFileUrl = "https://raw.githubusercontent.com/mranshurx/ShizukuFileInjector/main/anshu-on-top/your_file.dat"
                val destinationFile = File(targetDir, "injected_proxy.dat")

                val url = URL(remoteFileUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connect()

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    connection.inputStream.use { input ->
                        FileOutputStream(destinationFile).use { output ->
                            input.copyTo(output)
                        }
                    }
                    // Apply permissions using privileged shell execution
                    runShell("chmod ${chmod ?: "777"} ${destinationFile.absolutePath}")
                    "" // Success returns an empty string
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
    }

    override fun onBind(intent: Intent?): IBinder = binder
}