package com.example.shizukufileinjector

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import rikka.shizuku.Shizuku
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var sourcePathText: TextView
    private lateinit var logText: TextView
    private lateinit var destPathInput: EditText
    private lateinit var chmodInput: EditText

    private var stagedSourceFile: File? = null
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
            service = IFileInjectorService.Stub.asInterface(binder)
            log("Privileged user service connected.")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            log("Privileged user service disconnected.")
        }
    }

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        stageSourceFile(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        sourcePathText = findViewById(R.id.sourcePathText)
        logText = findViewById(R.id.logText)
        destPathInput = findViewById(R.id.destPathInput)
        chmodInput = findViewById(R.id.chmodInput)

        findViewById<Button>(R.id.btnRequestPermission).setOnClickListener {
            requestShizukuPermission()
        }
        findViewById<Button>(R.id.btnPickFile).setOnClickListener {
            pickFileLauncher.launch(arrayOf("*/*"))
        }
        findViewById<Button>(R.id.btnInject).setOnClickListener {
            doInject()
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
        .daemon(false)          // set true if you want it to survive your app closing
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

    // ---- File staging ----

    /**
     * The privileged process runs as "shell" (or root), which generally cannot
     * read content:// Uris or your app's private internal storage. So we first
     * copy the picked file into external app-specific storage
     * (/storage/emulated/0/Android/data/<pkg>/cache/...), which IS reachable
     * by the shell user, then hand that plain filesystem path to the service.
     */
    private fun stageSourceFile(uri: Uri) {
        try {
            val name = queryDisplayName(uri) ?: "staged_file"
            val outDir = externalCacheDir ?: cacheDir
            val outFile = File(outDir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                outFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            stagedSourceFile = outFile
            sourcePathText.text = outFile.absolutePath
            log("Staged source file at: ${outFile.absolutePath}")
        } catch (e: Exception) {
            log("Failed to stage file: ${e.message}")
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }

    // ---- Actions ----

    private fun doInject() {
        val src = stagedSourceFile
        val dest = destPathInput.text.toString().trim()
        val chmod = chmodInput.text.toString().trim()

        if (src == null) {
            log("Pick a source file first.")
            return
        }
        if (dest.isEmpty()) {
            log("Enter a destination path first.")
            return
        }
        val svc = service
        if (svc == null) {
            log("Not connected to the privileged service yet. Grant permission first.")
            return
        }

        Thread {
            val result = try {
                svc.injectFile(src.absolutePath, dest, chmod)
            } catch (e: Exception) {
                "FAILED (binder error): ${e.message}"
            }
            runOnUiThread {
                if (result.isEmpty()) {
                    log("SUCCESS: copied to $dest" + if (chmod.isNotEmpty()) " (chmod $chmod)" else "")
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
        val dest = destPathInput.text.toString().trim()
        val targetDir = if (dest.isNotEmpty()) dest.substringBeforeLast('/') else "/data/data"

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
