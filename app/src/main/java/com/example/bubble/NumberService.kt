package com.example.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

// Number bubble: pichle numbers (0-9) manual daalo, 3 numbers ki prediction dikhti hai.
class NumberService : Service() {

    companion object {
        private val C_BIG = Color.parseColor("#FF9800")      // 5-9 orange
        private val C_SMALL = Color.parseColor("#2196F3")    // 0-4 blue
        private val C_TEAL = Color.parseColor("#00897B")
        private val C_GREY = Color.parseColor("#454556")
        private val C_PANEL = Color.parseColor("#F01E1E2C")
        private val C_SOFT = Color.parseColor("#B4B4C8")
        private val NAMES = arrayOf("Hot-3", "Cold-3")
        private const val NR = 2
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var bubble: TextView
    private lateinit var bubbleBg: GradientDrawable
    private lateinit var panel: LinearLayout
    private lateinit var titleText: TextView
    private lateinit var predText: TextView
    private lateinit var stripText: TextView
    private lateinit var statText: TextView
    private lateinit var hitText: TextView
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var panelLp: WindowManager.LayoutParams

    // Sirf digits '0'..'9' (last 50, purana pehle)
    private val history = StringBuilder()
    private val hits = IntArray(NR)
    private val totals = IntArray(NR)
    private val picks = Array(NR) { IntArray(3) { -1 } }
    private var snapH: IntArray? = null
    private var snapT: IntArray? = null
    private var sel = 0
    private var panelOpen = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(2, notification())
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        prefs = getSharedPreferences("numbubble", MODE_PRIVATE)
        load()
        buildBubble()
        buildPanel()
        refresh()
    }

    // ---------- UI ----------

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun buildBubble() {
        val size = dp(56)
        bubbleBg = GradientDrawable().apply {
            setShape(GradientDrawable.OVAL)
            setColor(C_TEAL)
        }
        bubble = TextView(this).apply {
            text = "N"
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = bubbleBg
        }
        bubbleLp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(290)
        }
        bubble.setOnTouchListener(dragListener())
        wm.addView(bubble, bubbleLp)
    }

    private fun buildPanel() {
        titleText = TextView(this).apply {
            text = "NEXT 3 NUMBERS"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(C_SOFT)
            setPadding(0, dp(2), 0, dp(2))
        }
        predText = TextView(this).apply {
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(2), 0, dp(4))
        }
        stripText = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(6))
        }
        statText = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(2))
        }
        hitText = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(C_SOFT)
            setPadding(0, dp(2), 0, dp(6))
        }

        val panelBg = GradientDrawable().apply {
            setColor(C_PANEL)
            cornerRadius = dp(18).toFloat()
        }
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg
            setPadding(dp(12), dp(10), dp(12), dp(10))
            visibility = View.GONE
            addView(titleText)
            addView(predText)
            addView(stripText)
            addView(statText)
            addView(hitText)
            addView(numRow(0, 4))
            addView(numRow(5, 9))
            addView(
                row(
                    colorBtn("UNDO", C_GREY, 12f, 44) { undo() },
                    colorBtn("RULE", C_GREY, 12f, 44) { nextRule() },
                    colorBtn("RESET", C_GREY, 12f, 44) { reset() },
                    colorBtn("EXIT", C_GREY, 12f, 44) { stopSelf() }
                )
            )
        }

        panelLp = WindowManager.LayoutParams(
            dp(280), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
        wm.addView(panel, panelLp)
    }

    private fun numRow(from: Int, to: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (d in from..to) {
            addView(colorBtn(d.toString(), if (d >= 5) C_BIG else C_SMALL, 18f, 44) { addDigit(d) })
        }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }

    private fun colorBtn(
        label: String,
        bgColor: Int,
        textSz: Float = 14f,
        minH: Int = 44,
        onClick: () -> Unit
    ) = Button(this).apply {
        val shape = GradientDrawable()
        shape.setColor(bgColor)
        shape.cornerRadius = dp(12).toFloat()
        text = label
        textSize = textSz
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        minHeight = dp(minH)
        minimumHeight = dp(minH)
        setPadding(0, 0, 0, 0)
        background = shape
        val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        lp.setMargins(dp(2), dp(2), dp(2), dp(2))
        layoutParams = lp
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
        val w = dp(280)
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = panel.measuredHeight
        panelLp.x = bubbleLp.x.coerceAtMost(dm.widthPixels - w).coerceAtLeast(0)
        val wanted = if (bubbleLp.y > dm.heightPixels / 2) {
            bubbleLp.y - h            // bubble neeche hai to panel upar
        } else {
            bubbleLp.y + dp(64)       // warna panel neeche
        }
        panelLp.y = wanted.coerceAtMost(dm.heightPixels - h).coerceAtLeast(0)
        wm.updateViewLayout(panel, panelLp)
    }

    // ---------- Logic ----------

    private fun computePicks() {
        for (j in 0 until NR) {
            for (k in 0 until 3) picks[j][k] = -1
        }
        val n = history.length
        if (n < 10) return

        val cnt = IntArray(10)
        val lastIdx = IntArray(10) { -1 }
        for (i in 0 until n) {
            val d = history[i] - '0'
            cnt[d]++
            lastIdx[d] = i
        }
        val gap = IntArray(10) { if (lastIdx[it] < 0) n + 1 else n - 1 - lastIdx[it] }
        // Hot-3: last 50 me sabse zyada aaye 3 number
        val hot = (0..9).sortedWith(compareBy<Int>({ -cnt[it] }, { -lastIdx[it] }, { it })).take(3)
        // Cold-3: sabse zyada samay se na aaye 3 number
        val cold = (0..9).sortedWith(compareBy<Int>({ -gap[it] }, { it })).take(3)
        for (k in 0 until 3) {
            picks[0][k] = hot[k]
            picks[1][k] = cold[k]
        }
    }

    private fun addDigit(d: Int) {
        snapH = hits.copyOf()
        snapT = totals.copyOf()
        for (j in 0 until NR) {
            if (picks[j][0] >= 0) {
                totals[j]++
                if (picks[j].contains(d)) hits[j]++
            }
        }
        history.append('0' + d)
        if (history.length > 50) history.deleteCharAt(0)
        computePicks()
        save()
        refresh()
    }

    private fun undo() {
        val h = snapH ?: return
        val t = snapT ?: return
        if (history.isEmpty()) return
        history.deleteCharAt(history.length - 1)
        for (j in 0 until NR) {
            hits[j] = h[j]
            totals[j] = t[j]
        }
        snapH = null
        snapT = null
        computePicks()
        save()
        refresh()
    }

    private fun reset() {
        history.setLength(0)
        hits.fill(0)
        totals.fill(0)
        snapH = null
        snapT = null
        computePicks()
        save()
        refresh()
    }

    private fun nextRule() {
        sel = (sel + 1) % NR
        save()
        refresh()
    }

    // ---------- Show ----------

    private fun refresh() {
        val n = history.length
        val pk = picks[sel]

        val pred = SpannableStringBuilder()
        if (pk[0] >= 0) {
            for (k in 0 until 3) {
                val start = pred.length
                pred.append(pk[k].toString())
                pred.setSpan(
                    ForegroundColorSpan(if (pk[k] >= 5) C_BIG else C_SMALL),
                    start, pred.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (k < 2) pred.append("     ")
            }
            predText.textSize = 32f
            bubble.text = "${pk[0]} ${pk[1]} ${pk[2]}"
        } else {
            pred.append("Pehle 10 number daalo ($n/10)")
            predText.textSize = 16f
            bubble.text = "N"
        }
        predText.text = pred

        // Last 18 number (purana left, naya right)
        val strip = SpannableStringBuilder()
        for (ch in history.toString().takeLast(18)) {
            val start = strip.length
            strip.append(ch.toString())
            strip.setSpan(
                ForegroundColorSpan(if (ch >= '5') C_BIG else C_SMALL),
                start, strip.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            strip.append(" ")
        }
        stripText.text = strip

        var b = 0
        for (c in history) {
            if (c >= '5') b++
        }
        statText.text = "Last $n numbers  |  Big $b  Small ${n - b}"
        val rate = if (totals[sel] > 0) hits[sel] * 100 / totals[sel] else 0
        hitText.text = "[${NAMES[sel]}]  Hit ${hits[sel]}/${totals[sel]} ($rate%)\n" +
                "random 30%  \u2022  break-even 34%"
    }

    // ---------- Save / Load ----------

    private fun load() {
        val saved = prefs.getString("digits", null) ?: ""
        history.append(saved.filter { it in '0'..'9' }.takeLast(50))
        for (j in 0 until NR) {
            hits[j] = prefs.getInt("h$j", 0)
            totals[j] = prefs.getInt("t$j", 0)
        }
        sel = prefs.getInt("sel", 0).coerceIn(0, NR - 1)
        computePicks()
    }

    private fun save() {
        val e = prefs.edit()
        e.putString("digits", history.toString())
        for (j in 0 until NR) {
            e.putInt("h$j", hits[j])
            e.putInt("t$j", totals[j])
        }
        e.putInt("sel", sel)
        e.apply()
    }

    // ---------- Service ----------

    private fun notification(): Notification {
        val ch = "bubble"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ch, "Bubble", NotificationManager.IMPORTANCE_LOW)
        )
        return Notification.Builder(this, ch)
            .setContentTitle("Number bubble chal raha hai")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
    }

    override fun onDestroy() {
        wm.removeView(bubble)
        wm.removeView(panel)
        super.onDestroy()
    }
}
