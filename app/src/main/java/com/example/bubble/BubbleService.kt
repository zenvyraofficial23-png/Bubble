package com.example.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

class BubbleService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var bubble: TextView
    private lateinit var panel: LinearLayout
    private lateinit var info: TextView
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var panelLp: WindowManager.LayoutParams

    // 'B' = Big, 'S' = Small (sirf last 50 rakhte hain)
    private val history = StringBuilder()
    private var hits = 0
    private var total = 0
    private var pred = '-'
    private var lastCounted = -1   // -1 = kuch nahi, 0 = miss, 1 = hit (undo ke liye)
    private var panelOpen = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(1, notification())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("bubble", MODE_PRIVATE)
        load()
        buildBubble()
        buildPanel()
        refresh()
    }

    // ---------- UI ----------

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun buildBubble() {
        val size = dp(56)
        bubble = TextView(this).apply {
            text = "?"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.bubble_bg)
        }
        bubbleLp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(200)
        }
        bubble.setOnTouchListener(dragListener())
        wm.addView(bubble, bubbleLp)
    }

    private fun buildPanel() {
        val bg = GradientDrawable().apply {
            setColor(0xEE212121.toInt())
            cornerRadius = dp(16).toFloat()
        }
        info = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(dp(12), dp(12), dp(12), dp(12))
            visibility = View.GONE
            addView(info)
            addView(row(btn("BIG") { addResult('B') }, btn("SMALL") { addResult('S') }))
            addView(row(btn("UNDO") { undo() }, btn("RESET") { reset() }, btn("EXIT") { stopSelf() }))
        }
        panelLp = WindowManager.LayoutParams(
            dp(260), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        wm.addView(panel, panelLp)
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 12f
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        setOnClickListener { onClick() }
    }

    private fun dragListener() = object : View.OnTouchListener {
        var sx = 0
        var sy = 0
        var tx = 0f
        var ty = 0f
        var moved = false

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = bubbleLp.x
                    sy = bubbleLp.y
                    tx = e.rawX
                    ty = e.rawY
                    moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - tx).toInt()
                    val dy = (e.rawY - ty).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) {
                        bubbleLp.x = sx + dx
                        bubbleLp.y = sy + dy
                        wm.updateViewLayout(bubble, bubbleLp)
                        if (panelOpen) positionPanel()
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) togglePanel()
                }
            }
            return true
        }
    }

    private fun togglePanel() {
        panelOpen = !panelOpen
        panel.visibility = if (panelOpen) View.VISIBLE else View.GONE
        if (panelOpen) positionPanel()
    }

    private fun positionPanel() {
        val dm = resources.displayMetrics
        panelLp.x = bubbleLp.x.coerceAtMost(dm.widthPixels - dp(260)).coerceAtLeast(0)
        panelLp.y = if (bubbleLp.y > dm.heightPixels / 2) {
            bubbleLp.y - dp(190)          // bubble neeche hai to panel upar
        } else {
            bubbleLp.y + dp(64)           // warna panel neeche
        }
        wm.updateViewLayout(panel, panelLp)
    }

    // ---------- Data / Logic ----------

    private fun addResult(c: Char) {
        if (pred != '-') {
            total++
            if (pred == c) {
                hits++
                lastCounted = 1
            } else {
                lastCounted = 0
            }
        } else {
            lastCounted = -1
        }
        history.append(c)
        if (history.length > 50) history.deleteCharAt(0)
        pred = predict()
        save()
        refresh()
    }

    private fun undo() {
        if (history.isEmpty()) return
        history.deleteCharAt(history.length - 1)
        if (lastCounted != -1) {
            total--
            if (lastCounted == 1) hits--
            lastCounted = -1
        }
        pred = predict()
        save()
        refresh()
    }

    private fun reset() {
        history.setLength(0)
        hits = 0
        total = 0
        lastCounted = -1
        pred = '-'
        save()
        refresh()
    }

    // Simple rule: 3+ streak ho to ulta, warna jo zyada aaya wahi.
    private fun predict(): Char {
        if (history.length < 10) return '-'
        val b = history.count { it == 'B' }
        val s = history.length - b
        val last = history[history.length - 1]
        var streak = 0
        for (i in history.length - 1 downTo 0) {
            if (history[i] == last) streak++ else break
        }
        return when {
            streak >= 3 -> if (last == 'B') 'S' else 'B'
            b >= s -> 'B'
            else -> 'S'
        }
    }

    private fun refresh() {
        val b = history.count { it == 'B' }
        val s = history.length - b
        val rate = if (total > 0) hits * 100 / total else 0
        val p = when (pred) {
            'B' -> "BIG"
            'S' -> "SMALL"
            else -> "pehle 10 result daalo"
        }
        info.text = "Last ${history.length}: Big $b | Small $s\n" +
                "Next: $p\n" +
                "Hit rate: $hits/$total ($rate%)"
        bubble.text = when (pred) {
            'B' -> "B"
            'S' -> "S"
            else -> "?"
        }
    }

    private fun load() {
        history.append(prefs.getString("h", "") ?: "")
        hits = prefs.getInt("hits", 0)
        total = prefs.getInt("total", 0)
        lastCounted = prefs.getInt("lc", -1)
        pred = predict()
    }

    private fun save() {
        prefs.edit()
            .putString("h", history.toString())
            .putInt("hits", hits)
            .putInt("total", total)
            .putInt("lc", lastCounted)
            .apply()
    }

    // ---------- Service ----------

    private fun notification(): Notification {
        val ch = "bubble"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ch, "Bubble", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, ch)
            .setContentTitle("Bubble chal raha hai")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
    }

    override fun onDestroy() {
        wm.removeView(bubble)
        wm.removeView(panel)
        super.onDestroy()
    }
}
