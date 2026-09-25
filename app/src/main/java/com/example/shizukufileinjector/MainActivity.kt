package com.example.shizukufileinjector

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var keyInput: EditText
    private lateinit var btnInject: Button
    private lateinit var btnOfflineMode: Button

    private var service: IFileInjectorService? = null
    private val requestCode = 1000

    private val remoteKeyUrl = "https://raw.githubusercontent.com/mranshurx/ShizukuFileInjector/main/key.txt"

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { code, grantResult ->
        if (code == requestCode) {
            runOnUiThread {
                if (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    log("SUCCESS: Shizuku permission granted!")
                    bindService()
                } else {
                    log("ERROR: Shizuku permission denied.")
                }
                refreshStatus()
            }
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { 
            log("Shizuku binder connected.")
            refreshStatus()
            checkAndRequestStartupPermission()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            service = null
            log("WARNING: Shizuku binder died.")
            refreshStatus()
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

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)
        keyInput = findViewById(R.id.keyInput)
        btnInject = findViewById(R.id.btnInject)
        btnOfflineMode = findViewById(R.id.btnOfflineMode)

        findViewById<Button>(R.id.btnRequestPermission).setOnClickListener {
            requestShizukuPermission()
        }
        
        findViewById<Button>(R.id.btnVerifyKey).setOnClickListener {
            verifyKeyOnline()
        }

        findViewById<Button>(R.id.btnInject).setOnClickListener {
            doInjectAssets()
        }

        findViewById<Button>(R.id.btnOfflineMode).setOnClickListener {
            doOfflineMode()
        }
        
        findViewById<Button>(R.id.btnDiagnostics).setOnClickListener {
            doDiagnostics()
        }

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        refreshStatus()
        checkAndRequestStartupPermission()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED && service == null) {
            bindService()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        if (Shizuku.pingBinder()) {
            try {
                Shizuku.unbindUserService(userServiceArgs(), serviceConnection, true)
            } catch (_: Exception) {
            }
        }
    }

    private fun refreshStatus() {
        val text = when {
            !Shizuku.pingBinder() -> "Shizuku: NOT RUNNING"
            Shizuku.isPreV11() -> "Shizuku: version too old"
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED ->
                "Shizuku: running, permission GRANTED"
            else -> "Shizuku: running, permission NOT granted"
        }
        statusText.text = text
    }

    private fun checkAndRequestStartupPermission() {
        if (Shizuku.pingBinder()) {
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                bindService()
            } else {
                log("Prompting for Shizuku permission on startup...")
                Shizuku.requestPermission(requestCode)
            }
        } else {
            log("Shizuku service is not running. Please start Shizuku app first.")
        }
    }

    private fun requestShizukuPermission() {
        if (!Shizuku.pingBinder()) {
            log("ERROR: Shizuku service isn't running.")
            return
        }
        if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            log("Permission already granted.")
            bindService()
            return
        }
        Shizuku.requestPermission(requestCode)
    }

    private fun verifyKeyOnline() {
        val inputKey = keyInput.text.toString().trim()
        if (inputKey.isEmpty()) {
            log("Please enter a key first.")
            return
        }

        log("Checking key with online server...")

        Thread {
            try {
                val url = URL(remoteKeyUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 7000
                connection.readTimeout = 7000
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android)")
                connection.instanceFollowRedirects = true

                val responseCode = connection.responseCode
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val rawServerText = connection.inputStream.bufferedReader().use { it.readText() }
                    val currentServerKey = rawServerText.replace("\r", "").replace("\n", "").trim()
                    val cleanedInputKey = inputKey.replace("\r", "").replace("\n", "").trim()

                    runOnUiThread {
                        log("Server Key Verified.")
                        if (cleanedInputKey == currentServerKey && cleanedInputKey.isNotEmpty()) {
                            log("SUCCESS: Key authorized! App unlocked.")
                            btnInject.isEnabled = true
                            btnOfflineMode.isEnabled = true
                        } else {
                            log("ERROR: Key mismatch! Access denied.")
                            btnInject.isEnabled = false
                            btnOfflineMode.isEnabled = false
                        }
                    }
                } else {
                    runOnUiThread { log("ERROR: HTTP Code $responseCode from server.") }
                }
            } catch (e: Exception) {
                runOnUiThread { log("FAILED: ${e.message}") }
            }
        }.start()
    }

    private fun userServiceArgs() = Shizuku.UserServiceArgs(
        ComponentName(packageName, FileInjectorService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("injector")
        .debuggable(false)
        .version(1)

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
            log("ERROR: Service not connected! Grant Shizuku permission first.")
            return
        }

        log("Activating Proxy...")

        Thread {
            val result = try {
                svc.injectAssetsFolder()
            } catch (e: Exception) {
                "FAILED (binder error): ${e.message}"
            }

            runOnUiThread {
                if (result.isEmpty()) {
                    log("SUCCESS: Proxy activated successfully!")
                } else {
                    log(result)
                }
            }
        }.start()
    }

    private fun doOfflineMode() {
        val svc = service
        if (svc == null) {
            log("ERROR: Service not connected! Grant Shizuku permission first.")
            return
        }

        log("Activating Offline Mode (removing files)...")

        Thread {
            val result = try {
                svc.deleteInjectedFiles()
            } catch (e: Exception) {
                "FAILED (binder error): ${e.message}"
            }

            runOnUiThread {
                if (result.isEmpty()) {
                    log("SUCCESS: Offline mode active, files cleared!")
                } else {
                    log(result)
                }
            }
        }.start()
    }

    private fun doDiagnostics() {
        val svc = service
        if (svc == null) {
            log("ERROR: Service not connected!")
            return
        }
        
        val targetDir = "/storage/emulated/0/Android/data/com.dts.freefireth/files"

        Thread {
            val id = try { svc.runShell("id") } catch (e: Exception) { "error: ${e.message}" }
            val ls = try { svc.runShell("ls -la '$targetDir'") } catch (e: Exception) { "error: ${e.message}" }
            runOnUiThread {
                log("id -> $id")
                log("ls ->\n$ls")
            }
        }.start()
    }

    private fun log(msg: String) {
        logText.text = "${logText.text}\n$msg".trim()
    }
}