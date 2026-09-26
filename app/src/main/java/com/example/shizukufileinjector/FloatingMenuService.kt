package com.example.shizukufileinjector

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.PixelFormat
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import rikka.shizuku.Shizuku

class FloatingMenuService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var expandedMenu: LinearLayout
    private lateinit var bubbleView: View

    private var isExpanded = false
    private var injectorService: IFileInjectorService? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            injectorService = IFileInjectorService.Stub.asInterface(binder)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            injectorService = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Bind to the Shizuku user service so we have permission to delete files
        try {
            val args = Shizuku.UserServiceArgs(ComponentName(packageName, FileInjectorService::class.java.name))
                .daemon(false)
                .processNameSuffix("injector")
                .debuggable(false)
                .version(1)
            Shizuku.bindUserService(args, serviceConnection)
        } catch (_: Exception) {}

        val layoutInflater = LayoutInflater.from(this)
        floatingView = layoutInflater.inflate(R.layout.layout_floating_menu, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 200

        bubbleView = floatingView.findViewById(R.id.bubbleIcon)
        expandedMenu = floatingView.findViewById(R.id.expandedMenu)
        val btnGoOffline = floatingView.findViewById<Button>(R.id.btnGoOfflineClear)

        // Dragging & clicking behavior for the floating bubble
        bubbleView.setOnTouchListener(object : View.OnTouchListener {
            private var initialX = 0
            private var initialY = 0
            private var initialTouchX = 0.0f
            private var initialTouchY = 0.0f

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        val diffX = Math.abs(event.rawX - initialTouchX)
                        val diffY = Math.abs(event.rawY - initialTouchY)
                        if (diffX < 10 && diffY < 10) {
                            isExpanded = !isExpanded
                            expandedMenu.visibility = if (isExpanded) View.VISIBLE else View.GONE
                        }
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - initialTouchX).toInt()
                        params.y = initialY + (event.rawY - initialTouchY).toInt()
                        windowManager.updateViewLayout(floatingView, params)
                        return true
                    }
                }
                return false
            }
        })

        btnGoOffline.setOnClickListener {
            Thread {
                try {
                    // Use Shizuku binder service to execute cleanup & force-stop securely
                    injectorService?.deleteInjectedFiles()
                    injectorService?.runShell("am force-stop com.dts.freefireth")
                } catch (_: Exception) {
                    // Fallback to runtime command if binder isn't active
                    try {
                        Runtime.getRuntime().exec(arrayOf("sh", "-c", "rm -rf /storage/emulated/0/Android/data/com.dts.freefireth/files/*"))
                        Runtime.getRuntime().exec(arrayOf("sh", "-c", "am force-stop com.dts.freefireth"))
                    } catch (_: Exception) {}
                }
                
                // Cleanup window and stop service
                stopSelf()
            }.start()
        }

        windowManager.addView(floatingView, params)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (Shizuku.pingBinder()) {
            try {
                val args = Shizuku.UserServiceArgs(ComponentName(packageName, FileInjectorService::class.java.name))
                Shizuku.unbindUserService(args, serviceConnection, true)
            } catch (_: Exception) {}
        }
        if (::floatingView.isInitialized) {
            windowManager.removeView(floatingView)
        }
    }
}