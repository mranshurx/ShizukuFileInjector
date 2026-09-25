package com.example.shizukufileinjector

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView

    private var service: IFileInjectorService? = null

    private val requestCode = 1000

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

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        runOnUiThread { refreshStatus() }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        runOnUiThread {
            service = null
            log("Shizuku binder died (service was stopped / device restarted).")
            refreshStatus()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            // Note: If using Shizuku remote service stub casting
            service = IFileInjectorService.Stub.asInterface(binder)
            log("Privileged user service connected.")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            log("Privileged user service disconnected.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        logText = findViewById(R.id.logText)

        findViewById<Button>(R.id.btnRequestPermission).setOnClickListener {
            requestShizukuPermission()
        }
        
        // Hooked up to inject the anshu-on-top asset bundle directly
        findViewById<Button>(R.id.btnInject).setOnClickListener {
            doInjectAssets()
        }
        
        findViewById<Button>(R.id.btnDiagnostics).setOnClickListener {
            doDiagnostics()
        }

        Shizuku.addRequestPermissionResultListener(permissionListener)
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        refreshStatus()
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

    // ---- Shizuku permission / service plumbing ----

    private fun refreshStatus() {
        val text = when {
            !Shizuku.pingBinder() -> "Shizuku: NOT RUNNING (open the Shizuku app / Sui and start the service first)"
            Shizuku.isPreV11() -> "Shizuku: running, but version too old (pre-v11)"
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED ->
                "Shizuku: running, permission GRANTED"
            else -> "Shizuku: running, permission NOT granted yet"
        }
        statusText.text = text

        if (Shizuku.pingBinder() &&
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED &&
            service == null
        ) {
            bindService()
        }
    }

    private fun requestShizukuPermission() {
        if (!Shizuku.pingBinder()) {
            log("Shizuku service isn't running. Open the Shizuku app (or Sui module) and start it first.")
            return
        }
        if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            log("Permission already granted.")
            bindService()
            return
        }
        if (Shizuku.shouldShowRequestPermissionRationale()) {
            log("User previously denied permission; showing rationale then re-requesting.")
        }
        Shizuku.requestPermission(requestCode)
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

    // ---- Actions ----

    private fun doInjectAssets() {
        val svc = service
        if (svc == null) {
            log("Not connected to the privileged service yet. Grant permission first.")
            return
        }

        log("Starting injection of 'anshu-on-top' folder...")

        Thread {
            val result = try {
                // Cast or call the custom method implemented in the service wrapper
                // If your AIDL doesn't expose it directly, we handle it via the implementation reference 
                // or you can call it through an extended interface. Assuming direct extension or reflection/cast:
                val concreteService = svc as? FileInjectorService
                if (concreteService != null) {
                    concreteService.injectAssetsFolder()
                } else {
                    // Fallback using general shell or binder method if proxy wrapping prevents direct cast
                    "FAILED: Service reference cast failed."
                }
            } catch (e: Exception) {
                "FAILED (binder error): ${e.message}"
            }

            runOnUiThread {
                if (result.isEmpty()) {
                    log("SUCCESS: 'anshu-on-top' successfully injected into Free Fire files!")
                } else {
                    log(result)
                }
            }
        }.start()
    }

    private fun doDiagnostics() {
        val svc = service
        if (svc == null) {
            log("Not connected to the privileged service yet. Grant permission first.")
            return
        }
        
        val targetDir = "/storage/emulated/0/Android/data/com.dts.freefireth/files"

        Thread {
            val id = try { svc.runShell("id") } catch (e: Exception) { "error: ${e.message}" }
            val ls = try { svc.runShell("ls -la '$targetDir'") } catch (e: Exception) { "error: ${e.message}" }
            runOnUiThread {
                log("id -> $id")
                log("ls -la $targetDir ->\n$ls")
            }
        }.start()
    }

    private fun log(msg: String) {
        logText.text = "${logText.text}\n$msg".trim()
    }
}