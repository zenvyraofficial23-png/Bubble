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
import java.util.Locale
import kotlin.math.abs

class BubbleService : Service() {

    companion object {
        private val C_BIG = Color.parseColor("#FF9800")      // orange
        private val C_SMALL = Color.parseColor("#2196F3")    // blue
        private val C_PURPLE = Color.parseColor("#6200EE")
        private val C_GREY = Color.parseColor("#454556")
        private val C_PANEL = Color.parseColor("#F01E1E2C")
        private val C_SOFT = Color.parseColor("#B4B4C8")
        private val C_GREEN = Color.parseColor("#2E7D32")
        private val C_RED = Color.parseColor("#D32F2F")
        private val C_RED_SOFT = Color.parseColor("#FF6B6B")

        // Tumhari history se: jo stakes tum sach me lagate ho
        private val STAKES = intArrayOf(1, 5, 10, 20, 50, 100, 200, 500)
        private val TYPES = arrayOf("Big/Small", "Colour", "Number")
        // Jeetne par net profit = stake x ye (game ka asli payout, 2% fee ke baad)
        private val PROFIT = doubleArrayOf(0.96, 0.96, 7.82)
    }

    private class MoneyState(
        val net: Double,
        val wins: Int,
        val losses: Int,
        val lossStreak: Int,
        val staked: Double,
        val lastStake: Int,
        val lastLost: Boolean
    )

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var bubble: TextView
    private lateinit var bubbleBg: GradientDrawable
    private lateinit var panel: LinearLayout
    private lateinit var mainBox: LinearLayout
    private lateinit var moneyBox: LinearLayout
    private lateinit var predText: TextView
    private lateinit var dotsText: TextView
    private lateinit var statText: TextView
    private lateinit var hitText: TextView
    private lateinit var moneyInfo: TextView
    private lateinit var barBig: View
    private lateinit var barSmall: View
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var panelLp: WindowManager.LayoutParams

    // 'B' = Big, 'S' = Small (sirf last 50 rakhte hain)
    private val history = StringBuilder()
    private var hits = 0
    private var total = 0
    private var pred = '-'
    private var lastCounted = -1   // -1 = kuch nahi, 0 = miss, 1 = hit (undo ke liye)
    private var panelOpen = false

    // Money tracker
    private var net = 0.0
    private var staked = 0.0
    private var wins = 0
    private var losses = 0
    private var lossStreak = 0
    private var lastStake = 0
    private var lastLost = false
    private var stakeIdx = 2          // Rs 10
    private var typeIdx = 0           // Big/Small
    private var lossLimit = 300
    private var moneySnap: MoneyState? = null

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
        bubbleBg = GradientDrawable().apply {
            setShape(GradientDrawable.OVAL)
            setColor(C_PURPLE)
        }
        bubble = TextView(this).apply {
            text = "?"
            textSize = 20f
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
            y = dp(200)
        }
        bubble.setOnTouchListener(dragListener())
        wm.addView(bubble, bubbleLp)
    }

    private fun buildPanel() {
        predText = TextView(this).apply {
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(2), 0, dp(2))
        }
        dotsText = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(6))
        }
        barBig = View(this).apply {
            setBackgroundColor(C_BIG)
            layoutParams = LinearLayout.LayoutParams(0, dp(8), 1f)
        }
        barSmall = View(this).apply {
            setBackgroundColor(C_SMALL)
            layoutParams = LinearLayout.LayoutParams(0, dp(8), 1f)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(barBig)
            addView(barSmall)
        }
        statText = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, dp(2))
        }
        hitText = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(C_SOFT)
            setPadding(0, dp(2), 0, dp(6))
        }

        // ---- Main view (v4 jaisa) ----
        mainBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(predText)
            addView(dotsText)
            addView(bar)
            addView(statText)
            addView(hitText)
            addView(row(colorBtn("BIG", C_BIG) { addResult('B') }, colorBtn("SMALL", C_SMALL) { addResult('S') }))
            addView(
                row(
                    colorBtn("UNDO", C_GREY, 12f, 44) { undo() },
                    colorBtn("RESET", C_GREY, 12f, 44) { reset() },
                    colorBtn("MONEY", C_GREY, 12f, 44) { showMoney(true) },
                    colorBtn("EXIT", C_GREY, 12f, 44) { stopSelf() }
                )
            )
        }

        // ---- Money view ----
        moneyInfo = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(6))
        }
        moneyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(moneyInfo)
            addView(
                row(
                    colorBtn("STAKE -", C_GREY, 12f, 40) { changeStake(-1) },
                    colorBtn("STAKE +", C_GREY, 12f, 40) { changeStake(1) },
                    colorBtn("TYPE", C_GREY, 12f, 40) { nextType() }
                )
            )
            addView(
                row(
                    colorBtn("WON", C_GREEN, 15f, 46) { recordBet(true) },
                    colorBtn("LOST", C_RED, 15f, 46) { recordBet(false) },
                    colorBtn("UNDO", C_GREY, 12f, 46) { undoMoney() }
                )
            )
            addView(
                row(
                    colorBtn("LIMIT -", C_GREY, 12f, 40) { changeLimit(-100) },
                    colorBtn("LIMIT +", C_GREY, 12f, 40) { changeLimit(100) },
                    colorBtn("RESET", C_GREY, 12f, 40) { resetMoney() }
                )
            )
            addView(row(colorBtn("BACK", C_PURPLE, 13f, 42) { showMoney(false) }))
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
            addView(mainBox)
            addView(moneyBox)
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
        lp.setMargins(dp(3), dp(3), dp(3), dp(3))
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

    private fun showMoney(on: Boolean) {
        mainBox.visibility = if (on) View.GONE else View.VISIBLE
        moneyBox.visibility = if (on) View.VISIBLE else View.GONE
        refresh()
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

    // ---------- Big/Small logic (v4 jaisa) ----------

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

    // ---------- Money logic ----------

    private fun stake() = STAKES[stakeIdx]

    private fun changeStake(d: Int) {
        stakeIdx = (stakeIdx + d).coerceIn(0, STAKES.size - 1)
        save()
        refresh()
    }

    private fun nextType() {
        typeIdx = (typeIdx + 1) % TYPES.size
        save()
        refresh()
    }

    private fun changeLimit(d: Int) {
        lossLimit = (lossLimit + d).coerceAtLeast(100)
        save()
        refresh()
    }

    private fun recordBet(won: Boolean) {
        moneySnap = MoneyState(net, wins, losses, lossStreak, staked, lastStake, lastLost)
        val s = stake()
        staked += s
        if (won) {
            net += s * PROFIT[typeIdx]
            wins++
            lossStreak = 0
        } else {
            net -= s
            losses++
            lossStreak++
        }
        lastStake = s
        lastLost = !won
        save()
        refresh()
    }

    private fun undoMoney() {
        val m = moneySnap ?: return
        net = m.net
        wins = m.wins
        losses = m.losses
        lossStreak = m.lossStreak
        staked = m.staked
        lastStake = m.lastStake
        lastLost = m.lastLost
        moneySnap = null
        save()
        refresh()
    }

    private fun resetMoney() {
        net = 0.0
        staked = 0.0
        wins = 0
        losses = 0
        lossStreak = 0
        lastStake = 0
        lastLost = false
        moneySnap = null
        save()
        refresh()
    }

    // ---------- Show ----------

    private fun refresh() {
        val n = history.length
        val b = history.count { it == 'B' }
        val s = n - b

        when (pred) {
            'B' -> {
                predText.textSize = 26f
                predText.text = "NEXT: BIG"
                predText.setTextColor(C_BIG)
            }
            'S' -> {
                predText.textSize = 26f
                predText.text = "NEXT: SMALL"
                predText.setTextColor(C_SMALL)
            }
            else -> {
                predText.textSize = 16f
                predText.text = "Pehle 10 result daalo ($n/10)"
                predText.setTextColor(Color.WHITE)
            }
        }

        // Last 18 result ke rangeen dots (purana left, naya right)
        val dots = SpannableStringBuilder()
        for (ch in history.toString().takeLast(18)) {
            val start = dots.length
            dots.append("\u25CF\u2009")
            dots.setSpan(
                ForegroundColorSpan(if (ch == 'B') C_BIG else C_SMALL),
                start, dots.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        dotsText.text = dots

        val wb = if (n == 0) 1f else b.toFloat()
        val ws = if (n == 0) 1f else s.toFloat()
        barBig.layoutParams = LinearLayout.LayoutParams(0, dp(8), wb)
        barSmall.layoutParams = LinearLayout.LayoutParams(0, dp(8), ws)

        statText.text = "Big $b  |  Small $s   (last $n)"
        val rate = if (total > 0) hits * 100 / total else 0
        hitText.text = "Hit rate: $hits/$total ($rate%) \u2022 50% = coin flip"

        // Money
        val limitHit = net <= -lossLimit.toDouble()
        val chasing = lastLost && stake() > lastStake
        val sign = if (net >= 0) "+" else "-"
        val mb = StringBuilder()
        mb.append("Net: ").append(sign).append("Rs ")
            .append(String.format(Locale.US, "%.2f", abs(net))).append("\n")
        mb.append("Lagaya: Rs ").append(staked.toInt())
            .append("  |  Won ").append(wins).append("  Lost ").append(losses).append("\n")
        mb.append("Stake: Rs ").append(stake()).append("  |  ").append(TYPES[typeIdx]).append("\n")
        mb.append("Haar lagatar: ").append(lossStreak).append("  |  Limit: -Rs ").append(lossLimit)
        if (limitHit) {
            mb.append("\n!! LIMIT CROSS. Aaj ruk jao.")
        } else if (lossStreak >= 3) {
            mb.append("\n! ").append(lossStreak).append(" haar lagatar. Ruk jao.")
        } else if (chasing) {
            mb.append("\n! Haar ke baad bet badha rahe ho.")
        }
        moneyInfo.text = mb.toString()
        moneyInfo.setTextColor(if (limitHit || lossStreak >= 3 || chasing) C_RED_SOFT else Color.WHITE)

        // Circle: limit cross ho to laal "!"
        if (limitHit) {
            bubble.text = "!"
            bubbleBg.setColor(C_RED)
        } else {
            bubble.text = when (pred) {
                'B' -> "B"
                'S' -> "S"
                else -> "?"
            }
            bubbleBg.setColor(
                when (pred) {
                    'B' -> C_BIG
                    'S' -> C_SMALL
                    else -> C_PURPLE
                }
            )
        }
    }

    // ---------- Save / Load ----------

    private fun load() {
        val saved = prefs.getString("log", null) ?: prefs.getString("h", "") ?: ""
        history.append(saved.takeLast(50))
        hits = prefs.getInt("hits", 0)
        total = prefs.getInt("total", 0)
        lastCounted = prefs.getInt("lc", -1)
        pred = predict()

        net = prefs.getString("net", "0")?.toDoubleOrNull() ?: 0.0
        staked = prefs.getString("staked", "0")?.toDoubleOrNull() ?: 0.0
        wins = prefs.getInt("mwins", 0)
        losses = prefs.getInt("mlosses", 0)
        lossStreak = prefs.getInt("mstreak", 0)
        lastStake = prefs.getInt("mlaststake", 0)
        lastLost = prefs.getBoolean("mlastlost", false)
        stakeIdx = prefs.getInt("mstakeidx", 2).coerceIn(0, STAKES.size - 1)
        typeIdx = prefs.getInt("mtype", 0).coerceIn(0, TYPES.size - 1)
        lossLimit = prefs.getInt("mlimit", 300)
    }

    private fun save() {
        prefs.edit()
            .putString("log", history.toString())
            .putInt("hits", hits)
            .putInt("total", total)
            .putInt("lc", lastCounted)
            .putString("net", net.toString())
            .putString("staked", staked.toString())
            .putInt("mwins", wins)
            .putInt("mlosses", losses)
            .putInt("mstreak", lossStreak)
            .putInt("mlaststake", lastStake)
            .putBoolean("mlastlost", lastLost)
            .putInt("mstakeidx", stakeIdx)
            .putInt("mtype", typeIdx)
            .putInt("mlimit", lossLimit)
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
