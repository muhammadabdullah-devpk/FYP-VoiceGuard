package com.voiceguard.app.audio

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.voiceguard.app.R

/**
 * Optional floating warning banner. During a call the dialer is in front of VoiceGuard, so this
 * banner draws the warning on top of it. It needs the "Display over other apps" permission.
 */
object OverlayBanner {

    private val handler = Handler(Looper.getMainLooper())
    private var view: View? = null
    private var windowManager: WindowManager? = null
    private val autoHide = Runnable { hide() }

    fun canShow(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun show(context: Context, title: String, detail: String, reminder: String) {
        handler.post {
            val app = context.applicationContext
            if (!canShow(app)) return@post
            hideNow()

            val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val root = LayoutInflater.from(app).inflate(R.layout.overlay_banner, null)
            root.findViewById<TextView>(R.id.tvOverlayTitle).text = title
            root.findViewById<TextView>(R.id.tvOverlayDetail).text = detail
            root.findViewById<TextView>(R.id.tvOverlayReminder).text = reminder
            root.findViewById<Button>(R.id.btnOverlayDismiss).setOnClickListener { hideNow() }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP

            try {
                wm.addView(root, params)
                view = root
                windowManager = wm
                handler.postDelayed(autoHide, AUTO_HIDE_MS)
            } catch (e: Exception) {
                view = null
            }
        }
    }

    fun hide() {
        handler.post { hideNow() }
    }

    private fun hideNow() {
        handler.removeCallbacks(autoHide)
        val v = view
        if (v != null) {
            try {
                windowManager?.removeView(v)
            } catch (e: Exception) {
                // already removed
            }
        }
        view = null
    }

    private const val AUTO_HIDE_MS = 20_000L
}
