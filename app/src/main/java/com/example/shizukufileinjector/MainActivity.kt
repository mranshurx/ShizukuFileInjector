package com.example.shizukufileinjector

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
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
                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    log("Shizuku permission granted.")
                    bindService()
                } else {
                    log("Shizuku permission denied.")
                }
                refreshStatus()
            }
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            service = IFileInjectorService.Stub.asInterface(binder)
            log("SUCCESS: Privileged kernel service connected!")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            log("WARNING: Privileged kernel service disconnected.")
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

        findViewById<Button>(R.id.btnVerifyKey).setOnClickListener {
            verifyKeyOnline()
        }

        findViewById<Button>(R.id.btnRequestPermission).setOnClickListener {
            requestShizukuPermission()
        }

        findViewById<Button>(R.id.btnInject).setOnClickListener {
            checkAndRequestOverlayPermission()
        }

        Shizuku.addRequestPermissionResultListener(permissionListener)
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        
        // Automatically delete files when app closes
        triggerEmergencyCleanup()

        if (Shizuku.pingBinder()) {
            try {
                Shizuku.unbindUserService(userServiceArgs(), serviceConnection, true)
            } catch (_: Exception) {}
        }
    }

    private fun refreshStatus() {
        val text = when {
            !Shizuku.pingBinder() -> "Shizuku: NOT RUNNING"
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED -> "Shizuku: GRANTED"
            else -> "Shizuku: NOT GRANTED"
        }
        statusText.text = text
    }

    private fun verifyKeyOnline() {
        val inputKey = keyInput.text.toString().trim()
        if (inputKey.isEmpty()) {
            log("Enter key first.")
            return
        }

        log("Authenticating key online...")
        Thread {
            try {
                val url = URL(remoteKeyUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
                
                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val serverKey = connection.inputStream.bufferedReader().use { it.readText() }.trim()
                    
                    runOnUiThread {
                        if (inputKey == serverKey) {
                            log("SUCCESS: Key Verified! Unlocking Dashboard.")
                            authScreen.visibility = View.GONE
                            mainDashboard.visibility = View.VISIBLE
                            
                            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
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
                runOnUiThread { log("Auth Failed: ${e.message}") }
            }
        }.start()
    }

    private fun requestShizukuPermission() {
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Shizuku.requestPermission(requestCode)
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
                log("ERROR: Overlay permission is required for floating menu.")
            }
        }
    }

    private fun userServiceArgs() = Shizuku.UserServiceArgs(
        ComponentName(packageName, FileInjectorService::class.java.name)
    ).daemon(false).processNameSuffix("injector").debuggable(false).version(1)

    private fun bindService() {
        if (service != null) return
        try {
            Shizuku.bindUserService(userServiceArgs(), serviceConnection)
        } catch (e: Exception) {
            log("bindUserService failed: ${e.message}")
        }
    }

    private fun doInjectAssets() {
        val svc = service
        if (svc == null) {
            log("ERROR: Service not connected!")
            return
        }

        log("Activating Proxy & Floating Menu...")
        Thread {
            val result = try {
                svc.injectAssetsFolder()
            } catch (e: Exception) {
                "FAILED: ${e.message}"
            }

            runOnUiThread {
                if (result.isEmpty()) {
                    log("SUCCESS: Proxy Activated!")
                    startService(Intent(this, FloatingMenuService::class.java))
                } else {
                    log(result)
                }
            }
        }.start()
    }

    private fun triggerEmergencyCleanup() {
        Thread {
            try {
                service?.deleteInjectedFiles()
                service?.runShell("rm -rf /storage/emulated/0/Android/data/com.dts.freefireth/files/*")
            } catch (_: Exception) {
                try {
                    Runtime.getRuntime().exec(arrayOf("sh", "-c", "rm -rf /storage/emulated/0/Android/data/com.dts.freefireth/files/*"))
                } catch (_: Exception) {}
            }
        }.start()
    }

    private fun log(msg: String) {
        logText.text = "${logText.text}\n$msg".trim()
    }
}