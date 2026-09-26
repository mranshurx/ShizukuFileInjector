package com.example.shizukufileinjector

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var authScreen: LinearLayout
    private lateinit var mainDashboard: LinearLayout
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var keyInput: EditText
    private lateinit var btnInject: Button

    private var service: IFileInjectorService? = null
    private val requestCode = 1000
    private val overlayRequestCode = 1001

    private val remoteKeyUrl = "https://raw.githubusercontent.com/mranshurx/ShizukuFileInjector/refs/heads/main/key.txt"

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, grantResult ->
        if (code == requestCode) {
            runOnUiThread {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    log("Shizuku permission granted!")
                    bindService()
                } else {
                    log("ERROR: Shizuku permission was denied!")
                }
                refreshStatus()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IFileInjectorService.Stub.asInterface(binder)
            log("SUCCESS: Privileged service linked!")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            log("WARNING: Privileged service unlinked.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        authScreen = findViewById(R.id.authScreen)
        mainDashboard = findViewById(R.id.mainDashboard)
        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        keyInput = findViewById(R.id.keyInput)
        btnInject = findViewById(R.id.btnInject)

        findViewById<Button>(R.id.btnVerifyKey).setOnClickListener { verifyKeyOnline() }
        findViewById<Button>(R.id.btnRequestPermission).setOnClickListener { requestShizukuPermission() }
        findViewById<Button>(R.id.btnInject).setOnClickListener { checkAndRequestOverlayPermission() }

        try {
            Shizuku.addRequestPermissionResultListener(permissionListener)
        } catch (_: Exception) {}
        
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED && service == null) {
            bindService()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener)
        } catch (_: Exception) {}
        
        triggerEmergencyCleanup()

        if (Shizuku.pingBinder()) {
            try {
                service?.destroy()
                Shizuku.unbindUserService(userServiceArgs(), serviceConnection, true)
            } catch (_: Exception) {}
        }
    }

    private fun refreshStatus() {
        val status = when {
            !Shizuku.pingBinder() -> "Shizuku: OFFLINE (Open Shizuku App)"
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "Shizuku: ONLINE (Permission Needed)"
            else -> "Shizuku: READY & CONNECTED"
        }
        statusText.text = status
    }

    private fun verifyKeyOnline() {
        val inputKey = keyInput.text.toString().trim()
        if (inputKey.isEmpty()) {
            log("ERROR: Enter key first.")
            return
        }

        log("Authenticating key online...")
        Thread {
            try {
                val url = URL(remoteKeyUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val serverKey = connection.inputStream.bufferedReader().use { it.readText() }.trim()
                    
                    runOnUiThread {
                        if (inputKey == serverKey) {
                            log("SUCCESS: Key Verified!")
                            authScreen.visibility = View.GONE
                            mainDashboard.visibility = View.VISIBLE
                            
                            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                                bindService()
                            } else {
                                requestShizukuPermission()
                            }
                        } else {
                            log("ERROR: Invalid Key! Purging files...")
                            triggerEmergencyCleanup()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { log("Auth Error: ${e.message}") }
            }
        }.start()
    }

    private fun requestShizukuPermission() {
        if (!Shizuku.pingBinder()) {
            log("ERROR: Shizuku is not running!")
            return
        }
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            try {
                Shizuku.requestPermission(requestCode)
                log("Prompting for Shizuku authorization...")
            } catch (e: Exception) {
                log("Permission request error: ${e.message}")
            }
        } else {
            log("Permission already granted.")
            bindService()
        }
    }

    private fun checkAndRequestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivityForResult(intent, overlayRequestCode)
        } else {
            doInjectAssets()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == overlayRequestCode) {
            if (Settings.canDrawOverlays(this)) {
                doInjectAssets()
            } else {
                log("ERROR: Overlay permission is required.")
            }
        }
    }

    private fun userServiceArgs() = Shizuku.UserServiceArgs(
        ComponentName(packageName, FileInjectorService::class.java.name)
    ).daemon(false).processNameSuffix("injector").debuggable(false).version(1)

    private fun bindService() {
        if (service != null) return
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return
        }
        try {
            Shizuku.bindUserService(userServiceArgs(), serviceConnection)
        } catch (_: Exception) {}
    }

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

        log("Executing Proxy Injection via Shizuku...")
        Thread {
            try {
                val svc = service
                if (svc != null) {
                    val result = svc.injectAssetsFolder()
                    runOnUiThread {
                        if (result.isEmpty()) {
                            log("SUCCESS: Proxy Injected via Service!")
                            startService(Intent(this, FloatingMenuService::class.java))
                        } else {
                            log(result)
                        }
                    }
                } else {
                    // Fallback direct Shizuku execution if service binder is unlinked
                    val targetDir = File("/storage/emulated/0/Android/data/com.dts.freefireth/files/netcache")
                    if (!targetDir.exists()) {
                        executeShizukuShell("mkdir -p ${targetDir.absolutePath}")
                    }

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
                        executeShizukuShell("chmod 777 ${destinationFile.absolutePath}")
                        
                        runOnUiThread {
                            log("SUCCESS: Proxy Injected via Direct Shizuku Shell!")
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

    private fun executeShizukuShell(command: String) {
        try {
            val p = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)
            p.waitFor()
        } catch (_: Exception) {}
    }

    private fun triggerEmergencyCleanup() {
        Thread {
            try {
                service?.deleteInjectedFiles()
            } catch (_: Exception) {
                try {
                    executeShizukuShell("rm -rf /storage/emulated/0/Android/data/com.dts.freefireth/files/*")
                } catch (_: Exception) {}
            }
        }.start()
    }

    private fun log(msg: String) {
        logText.text = "${logText.text}\n$msg".trim()
    }
}