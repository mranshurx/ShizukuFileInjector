private fun doInjectAssets() {
        if (!Shizuku.pingBinder()) {
            log("ERROR: Shizuku is not running! Open Shizuku app.")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            log("ERROR: Grant Shizuku permission first!")
            requestShizukuPermission()
            return
        }

        log("Downloading and extracting all files from 'anshu-on-top'...")
        Thread {
            try {
                val svc = service
                if (svc != null) {
                    val result = svc.injectAssetsFolder()
                    runOnUiThread {
                        if (result.isEmpty()) {
                            log("SUCCESS: All files injected successfully!")
                            startService(Intent(this, FloatingMenuService::class.java))
                        } else {
                            log(result)
                        }
                    }
                } else {
                    val targetDir = File("/storage/emulated/0/Android/data/com.dts.freefireth/files/netcache")
                    if (!targetDir.exists()) {
                        executeShellCommand("mkdir -p ${targetDir.absolutePath}")
                    }

                    val zipUrl = "https://github.com/mranshurx/ShizukuFileInjector/archive/refs/heads/main.zip"
                    val url = URL(zipUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connect()

                    if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                        java.util.zip.ZipInputStream(connection.inputStream).use { zis ->
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
                                        executeShellCommand("chmod 777 ${destinationFile.absolutePath}")
                                    }
                                }
                                zis.closeEntry()
                                entry = zis.nextEntry
                            }
                        }
                        
                        runOnUiThread {
                            log("SUCCESS: All files injected via fallback!")
                            startService(Intent(this, FloatingMenuService::class.java))
                        }
                    } else {
                        runOnUiThread { log("FAILED: HTTP Error ${connection.responseCode}") }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { log("FAILED: ${e.message}") }
            }
        }.start()
    }