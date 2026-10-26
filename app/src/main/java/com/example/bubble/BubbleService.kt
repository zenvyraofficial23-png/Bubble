package com.example.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

class BubbleService : Service() {

    companion object {
        private val C_BIG = Color.parseColor("#FF9800")      // orange
        private val C_SMALL = Color.parseColor("#2196F3")    // blue
        private val C_PURPLE = Color.parseColor("#6200EE")
        private val C_GREY = Color.parseColor("#454556")
        private val C_SOFT = Color.parseColor("#B4B4C8")
        private val C_GREEN = Color.parseColor("#2E7D32")
        private val C_RED = Color.parseColor("#D32F2F")
        private val C_RED_SOFT = Color.parseColor("#FF6B6B")
        private val C_GOOD = Color.parseColor("#66BB6A")
        private val C_AMBER = Color.parseColor("#FFC107")
        private val T_BIG = Color.parseColor("#33FF9800")
        private val T_SMALL = Color.parseColor("#332196F3")
        private val T_NONE = Color.parseColor("#22FFFFFF")
        private val RING = Color.parseColor("#B3FFFFFF")

        // Tumhari history se: jo stakes tum sach me lagate ho
        private val STAKES = intArrayOf(1, 5, 10, 20, 50, 100, 200, 500)
        private val TYPES = arrayOf("Big/Small", "Colour", "Number")
        // Jeetne par net profit = stake x ye (game ka asli payout, 2% fee ke baad)
        private val PROFIT = doubleArrayOf(0.96, 0.96, 7.82)
        private const val BREAK_EVEN = 0.51f
        private const val PW = 290          // panel width (dp)
        private const val MAX_FULL = 300    // stats ke liye itne round yaad rakhte hain
    }

    // Hit rate ka meter: range (95%) + break-even line
    private class Meter(context: Context) : View(context) {
        var rate = -1f
        var lo = 0f
        var hi = 1f
        var dotColor = Color.WHITE
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val th = h * 0.36f
            val top = (h - th) / 2f
            p.style = Paint.Style.FILL
            p.color = Color.parseColor("#33FFFFFF")
            rect.set(0f, top, w, top + th)
            canvas.drawRoundRect(rect, th / 2f, th / 2f, p)
            if (rate >= 0f) {
                p.color = Color.parseColor("#66FFFFFF")
                rect.set(lo * w, top, hi * w, top + th)
                canvas.drawRoundRect(rect, th / 2f, th / 2f, p)
            }
            p.color = Color.WHITE
            p.strokeWidth = 3f
            canvas.drawLine(BREAK_EVEN * w, 0f, BREAK_EVEN * w, h, p)
            if (rate >= 0f) {
                p.color = dotColor
                canvas.drawCircle(rate * w, h / 2f, h * 0.38f, p)
            }
        }
    }

    private class MoneyState(
        val net: Double,
        val peakNet: Double,
        val wins: Int,
        val losses: Int,
        val lossStreak: Int,
        val staked: Double,
        val lastStake: Int,
        val lastLost: Boolean,
        val betLog: String
    )

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var bubble: TextView
    private lateinit var bubbleBg: GradientDrawable
    private lateinit var panel: LinearLayout
    private lateinit var mainBox: LinearLayout
    private lateinit var moneyBox: LinearLayout
    private lateinit var statsBox: LinearLayout
    private lateinit var predBg: GradientDrawable
    private lateinit var headerText: TextView
    private lateinit var predText: TextView
    private lateinit var dotsText: TextView
    private lateinit var statText: TextView
    private lateinit var hitText: TextView
    private lateinit var verdictText: TextView
    private lateinit var moneyInfo: TextView
    private lateinit var statsInfo: TextView
    private lateinit var meter: Meter
    private lateinit var barBig: View
    private lateinit var barSmall: View
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var panelLp: WindowManager.LayoutParams

    // 'B' = Big, 'S' = Small. full = last 300 (stats), history = last 50 (prediction)
    private val full = StringBuilder()
    private val history = StringBuilder()
    private var hits = 0
    private var total = 0
    private var pred = '-'
    private var shownPred = ' '
    private var lastCounted = -1   // -1 = kuch nahi, 0 = miss, 1 = hit (undo ke liye)
    private var panelOpen = false

    // Money tracker
    private var net = 0.0
    private var peakNet = 0.0
    private var staked = 0.0
    private var wins = 0
    private var losses = 0
    private var lossStreak = 0
    private var lastStake = 0
    private var lastLost = false
    private var stakeIdx = 2          // Rs 10
    private var typeIdx = 0           // Big/Small
    private var lossLimit = 300
    private var bankroll = 1000
    private val betLog = StringBuilder()   // 'W' / 'L', last 60 bets
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

    private fun lighten(c: Int, f: Float): Int {
        val r = (Color.red(c) + (255 - Color.red(c)) * f).toInt()
        val g = (Color.green(c) + (255 - Color.green(c)) * f).toInt()
        val b = (Color.blue(c) + (255 - Color.blue(c)) * f).toInt()
        return Color.rgb(r, g, b)
    }

    private fun buildBubble() {
        val size = dp(58)
        bubbleBg = GradientDrawable()
        bubbleBg.setShape(GradientDrawable.OVAL)
        bubbleBg.setColor(C_PURPLE)
        bubbleBg.setStroke(dp(2), RING)
        bubble = TextView(this).apply {
            text = "?"
            textSize = 21f
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
        headerText = TextView(this).apply {
            text = "BIG / SMALL"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.15f
            gravity = Gravity.CENTER
            setTextColor(C_SOFT)
            setPadding(0, 0, 0, dp(2))
        }

        predText = TextView(this).apply {
            textSize = 26f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
        }
        predBg = GradientDrawable()
        predBg.cornerRadius = dp(14).toFloat()
        predBg.setColor(T_NONE)
        predBg.setStroke(dp(1), C_GREY)
        val predCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = predBg
            setPadding(dp(8), dp(10), dp(8), dp(10))
            addView(predText)
        }
        val cardLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        cardLp.setMargins(0, dp(4), 0, dp(4))
        predCard.layoutParams = cardLp

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
        val barBg = GradientDrawable()
        barBg.setColor(C_GREY)
        barBg.cornerRadius = dp(4).toFloat()
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = barBg
            clipToOutline = true
            outlineProvider = ViewOutlineProvider.BACKGROUND
            addView(barBig)
            addView(barSmall)
        }
        statText = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(6), 0, dp(6))
        }

        meter = Meter(this)
        meter.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(16))

        hitText = TextView(this).apply {
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(C_SOFT)
            setPadding(0, dp(4), 0, dp(1))
        }
        verdictText = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(C_SOFT)
            setPadding(0, 0, 0, dp(6))
        }

        // ---- Main view ----
        mainBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(headerText)
            addView(predCard)
            addView(dotsText)
            addView(bar)
            addView(statText)
            addView(meter)
            addView(hitText)
            addView(verdictText)
            addView(
                row(
                    colorBtn("BIG", C_BIG, 17f, 50) { addResult('B') },
                    colorBtn("SMALL", C_SMALL, 17f, 50) { addResult('S') }
                )
            )
            addView(
                row(
                    colorBtn("UNDO", C_GREY, 12f, 40) { undo() },
                    colorBtn("RESET", C_GREY, 12f, 40) { reset() },
                    colorBtn("EXIT", C_GREY, 12f, 40) { stopSelf() }
                )
            )
            addView(
                row(
                    colorBtn("STATS", C_GREY, 12f, 40) { showView(2) },
                    colorBtn("MONEY", C_PURPLE, 12f, 40) { showView(1) }
                )
            )
        }

        // ---- Money view ----
        moneyInfo = TextView(this).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(8))
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
                    colorBtn("WON", C_GREEN, 16f, 48) { recordBet(true) },
                    colorBtn("LOST", C_RED, 16f, 48) { recordBet(false) },
                    colorBtn("UNDO", C_GREY, 12f, 48) { undoMoney() }
                )
            )
            addView(
                row(
                    colorBtn("BANK -", C_GREY, 11f, 40) { changeBank(-500) },
                    colorBtn("BANK +", C_GREY, 11f, 40) { changeBank(500) },
                    colorBtn("LIMIT -", C_GREY, 11f, 40) { changeLimit(-100) },
                    colorBtn("LIMIT +", C_GREY, 11f, 40) { changeLimit(100) }
                )
            )
            addView(
                row(
                    colorBtn("RESET", C_GREY, 12f, 42) { resetMoney() },
                    colorBtn("BACK", C_PURPLE, 13f, 42) { showView(0) }
                )
            )
        }

        // ---- Stats view ----
        statsInfo = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(0, dp(4), 0, dp(8))
        }
        statsBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(statsInfo)
            addView(row(colorBtn("BACK", C_PURPLE, 13f, 44) { showView(0) }))
        }

        val panelBg = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#F02A2A44"), Color.parseColor("#F0141420"))
        )
        panelBg.cornerRadius = dp(22).toFloat()
        panelBg.setStroke(dp(1), Color.parseColor("#33FFFFFF"))
        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = panelBg
            setPadding(dp(14), dp(12), dp(14), dp(12))
            visibility = View.GONE
            addView(mainBox)
            addView(moneyBox)
            addView(statsBox)
        }

        panelLp = WindowManager.LayoutParams(
            dp(PW), WindowManager.LayoutParams.WRAP_CONTENT,
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
        val shape = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(lighten(bgColor, 0.22f), bgColor)
        )
        shape.cornerRadius = dp(13).toFloat()
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
        setOnClickListener {
            it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
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
        if (panelOpen) {
            panel.alpha = 0f
            panel.visibility = View.VISIBLE
            positionPanel()
            panel.animate().alpha(1f).setDuration(160).start()
        } else {
            panel.animate().cancel()
            panel.visibility = View.GONE
        }
    }

    private fun popBubble() {
        bubble.animate().cancel()
        bubble.scaleX = 1f
        bubble.scaleY = 1f
        bubble.animate().scaleX(1.18f).scaleY(1.18f).setDuration(120).withEndAction {
            bubble.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
        }.start()
    }

    // v: 0 = main, 1 = money, 2 = stats
    private fun showView(v: Int) {
        mainBox.visibility = if (v == 0) View.VISIBLE else View.GONE
        moneyBox.visibility = if (v == 1) View.VISIBLE else View.GONE
        statsBox.visibility = if (v == 2) View.VISIBLE else View.GONE
        refresh()
        if (panelOpen) positionPanel()
    }

    private fun positionPanel() {
        val dm = resources.displayMetrics
        val w = dp(PW)
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val h = panel.measuredHeight
        panelLp.x = bubbleLp.x.coerceAtMost(dm.widthPixels - w).coerceAtLeast(0)
        val wanted = if (bubbleLp.y > dm.heightPixels / 2) {
            bubbleLp.y - h            // bubble neeche hai to panel upar
        } else {
            bubbleLp.y + dp(66)       // warna panel neeche
        }
        panelLp.y = wanted.coerceAtMost(dm.heightPixels - h).coerceAtLeast(0)
        wm.updateViewLayout(panel, panelLp)
    }

    // ---------- Big/Small logic ----------

    private fun syncHistory() {
        history.setLength(0)
        history.append(full.toString().takeLast(50))
    }

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
        full.append(c)
        if (full.length > MAX_FULL) full.deleteCharAt(0)
        syncHistory()
        pred = predict()
        save()
        refresh()
    }

    private fun undo() {
        if (full.isEmpty()) return
        full.deleteCharAt(full.length - 1)
        syncHistory()
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
        full.setLength(0)
        syncHistory()
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

    private fun curStreak(): Int {
        val n = full.length
        if (n == 0) return 0
        var k = 1
        for (i in n - 2 downTo 0) {
            if (full[i] == full[n - 1]) k++ else break
        }
        return k
    }

    // Wilson 95% range: hit rate asli me kitna ho sakta hai
    private fun ci(h: Int, t: Int): FloatArray {
        if (t == 0) return floatArrayOf(0f, 1f)
        val p = h.toDouble() / t
        val z = 1.96
        val denom = 1.0 + z * z / t
        val centre = (p + z * z / (2.0 * t)) / denom
        val half = z * sqrt(p * (1.0 - p) / t + z * z / (4.0 * t * t)) / denom
        return floatArrayOf((centre - half).toFloat(), (centre + half).toFloat())
    }

    // ---------- Stats (randomness tests) ----------

    private fun erfc(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * x)
        val poly = ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t
        return minOf(1.0, poly * exp(-x * x))
    }

    private fun pChi1(c: Double) = erfc(sqrt(c / 2.0))

    private fun pZ(z: Double) = erfc(abs(z) / sqrt(2.0))

    private fun f2(x: Double) = String.format(Locale.US, "%.2f", x)

    private fun statsString(): String {
        val s = full.toString()
        val n = s.length
        val sb = StringBuilder()
        sb.append("Rounds daale: ").append(n).append("\n")
        if (n < 20) {
            sb.append("\nStats ke liye kam se kam 20 round chahiye.")
            return sb.toString()
        }
        val b = s.count { it == 'B' }
        val sm = n - b
        sb.append("Big ").append(b).append(" (").append(b * 100 / n).append("%)  |  Small ").append(sm).append("\n")

        var best = 1
        var cur = 1
        for (i in 1 until n) {
            if (s[i] == s[i - 1]) {
                cur++
                if (cur > best) best = cur
            } else {
                cur = 1
            }
        }
        val nowName = if (s[n - 1] == 'B') "BIG" else "SMALL"
        sb.append("Abhi streak: ").append(nowName).append(" x").append(curStreak())
            .append("  |  sabse lambi: ").append(best).append("\n")

        var bb = 0
        var bs = 0
        var sb2 = 0
        var ss = 0
        var runs = 1
        for (i in 1 until n) {
            val a = s[i - 1]
            val c = s[i]
            if (a != c) runs++
            if (a == 'B') {
                if (c == 'B') bb++ else bs++
            } else {
                if (c == 'B') sb2++ else ss++
            }
        }
        val afterB = if (bb + bs > 0) bb * 100 / (bb + bs) else 0
        val afterS = if (sb2 + ss > 0) sb2 * 100 / (sb2 + ss) else 0
        sb.append("Big ke baad Big: ").append(afterB).append("%  |  Small ke baad Big: ").append(afterS).append("%\n")

        val chi = (b - sm).toDouble() * (b - sm).toDouble() / n
        val p1 = pChi1(chi)
        val mu = 2.0 * b * sm / n + 1.0
        val v = (mu - 1.0) * (mu - 2.0) / (n - 1.0)
        val p2 = if (v > 0) pZ((runs - mu) / sqrt(v)) else 1.0
        val m = bb + bs + sb2 + ss
        val den = (bb + bs).toDouble() * (sb2 + ss) * (bb + sb2) * (bs + ss)
        val p3 = if (den > 0) {
            val cross = bb.toDouble() * ss - bs.toDouble() * sb2
            pChi1(m * cross * cross / den)
        } else {
            1.0
        }

        var flagged = 0
        if (p1 < 0.05) flagged++
        if (p2 < 0.05) flagged++
        if (p3 < 0.05) flagged++

        sb.append("\nRandom hai ya nahi (test):\n")
        sb.append(" Big/Small barabar?   p=").append(f2(p1)).append(if (p1 >= 0.05) "  OK" else "  alag").append("\n")
        sb.append(" Streak normal?       p=").append(f2(p2)).append(if (p2 >= 0.05) "  OK" else "  alag").append("\n")
        sb.append(" Pichla -> agla?      p=").append(f2(p3)).append(if (p3 >= 0.05) "  OK" else "  alag").append("\n")
        sb.append("\n")
        if (flagged == 0) {
            sb.append("Nateeja: data random jaisa hai. Pichle results se agla predict karne ka aadhar nahi dikh raha.")
        } else {
            sb.append("Nateeja: kuch test alag dikha rahe hain, par itne data me ye sanyog ho sakta hai.")
        }
        sb.append("\nKisi 55% ke fayde ko pakka karne ke liye ~800 round chahiye.")
        return sb.toString()
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

    private fun changeBank(d: Int) {
        bankroll = (bankroll + d).coerceAtLeast(500)
        save()
        refresh()
    }

    private fun recordBet(won: Boolean) {
        moneySnap = MoneyState(net, peakNet, wins, losses, lossStreak, staked, lastStake, lastLost, betLog.toString())
        val s = stake()
        staked += s
        if (won) {
            net += s * PROFIT[typeIdx]
            wins++
            lossStreak = 0
            betLog.append('W')
        } else {
            net -= s
            losses++
            lossStreak++
            betLog.append('L')
        }
        if (betLog.length > 60) betLog.deleteCharAt(0)
        if (net > peakNet) peakNet = net
        lastStake = s
        lastLost = !won
        save()
        refresh()
    }

    private fun undoMoney() {
        val m = moneySnap ?: return
        net = m.net
        peakNet = m.peakNet
        wins = m.wins
        losses = m.losses
        lossStreak = m.lossStreak
        staked = m.staked
        lastStake = m.lastStake
        lastLost = m.lastLost
        betLog.setLength(0)
        betLog.append(m.betLog)
        moneySnap = null
        save()
        refresh()
    }

    private fun resetMoney() {
        net = 0.0
        peakNet = 0.0
        staked = 0.0
        wins = 0
        losses = 0
        lossStreak = 0
        lastStake = 0
        lastLost = false
        betLog.setLength(0)
        moneySnap = null
        save()
        refresh()
    }

    // ---------- Show ----------

    private fun refresh() {
        val n = history.length
        val b = history.count { it == 'B' }
        val s = n - b

        // Header: abhi ki streak
        if (full.isEmpty()) {
            headerText.text = "BIG / SMALL"
        } else {
            val nm = if (full[full.length - 1] == 'B') "BIG" else "SMALL"
            headerText.text = "BIG / SMALL  \u2022  streak $nm x${curStreak()}"
        }

        when (pred) {
            'B' -> {
                predText.textSize = 28f
                predText.text = "NEXT: BIG"
                predText.setTextColor(C_BIG)
                predBg.setColor(T_BIG)
                predBg.setStroke(dp(1), C_BIG)
            }
            'S' -> {
                predText.textSize = 28f
                predText.text = "NEXT: SMALL"
                predText.setTextColor(C_SMALL)
                predBg.setColor(T_SMALL)
                predBg.setStroke(dp(1), C_SMALL)
            }
            else -> {
                predText.textSize = 15f
                predText.text = "Pehle 10 result daalo ($n/10)"
                predText.setTextColor(Color.WHITE)
                predBg.setColor(T_NONE)
                predBg.setStroke(dp(1), C_GREY)
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

        val st = SpannableStringBuilder()
        var from = st.length
        st.append("Big $b")
        st.setSpan(ForegroundColorSpan(C_BIG), from, st.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        st.append("   |   ")
        from = st.length
        st.append("Small $s")
        st.setSpan(ForegroundColorSpan(C_SMALL), from, st.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        st.append("   (last $n)")
        statText.text = st

        // Hit rate + sach-meter
        val rate = if (total > 0) hits * 100 / total else 0
        val range = ci(hits, total)
        var verdict = ""
        var vcol = C_SOFT
        when {
            total < 30 -> {
                verdict = "Kam data: 30+ rounds chahiye"
                vcol = C_SOFT
            }
            range[0] > BREAK_EVEN -> {
                verdict = "Break-even ke upar (pakka)"
                vcol = C_GOOD
            }
            range[1] < BREAK_EVEN -> {
                verdict = "Break-even ke neeche"
                vcol = C_RED_SOFT
            }
            else -> {
                verdict = "Abhi pakka nahi"
                vcol = C_AMBER
            }
        }
        meter.rate = if (total > 0) hits.toFloat() / total else -1f
        meter.lo = range[0]
        meter.hi = range[1]
        meter.dotColor = vcol
        meter.invalidate()
        hitText.text = if (total > 0) {
            "Hit $hits/$total = $rate%  \u2022  range ${(range[0] * 100).toInt()}-${(range[1] * 100).toInt()}%  \u2022  break-even 51%"
        } else {
            "Hit 0/0  \u2022  break-even 51% (safed line)"
        }
        verdictText.text = verdict
        verdictText.setTextColor(vcol)

        // Money
        val limitHit = net <= -lossLimit.toDouble()
        val curBank = bankroll + net
        val pct = if (curBank > 0) stake() * 100.0 / curBank else 100.0
        val chasing = lastLost && stake() > lastStake
        val sign = if (net >= 0) "+" else "-"
        val mi = SpannableStringBuilder()
        val netStr = sign + "Rs " + String.format(Locale.US, "%.2f", abs(net))
        val ns = mi.length
        mi.append(netStr)
        mi.setSpan(
            ForegroundColorSpan(if (net >= 0) C_GOOD else C_RED_SOFT),
            ns, mi.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        mi.setSpan(RelativeSizeSpan(2.0f), ns, mi.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        mi.append("\nBankroll Rs ").append(bankroll.toString())
        mi.append(" -> ab Rs ").append(curBank.toInt().toString())
        mi.append("\nPeak se neeche: Rs ").append((peakNet - net).toInt().toString())
        mi.append("  |  Lagaya Rs ").append(staked.toInt().toString())
        mi.append("\nWon ").append(wins.toString()).append("  Lost ").append(losses.toString())
        mi.append("  |  Haar lagatar ").append(lossStreak.toString())
        mi.append("\nLimit -Rs ").append(lossLimit.toString())

        // Stake line (bankroll ke % ke hisaab se rang)
        val pctStr = String.format(Locale.US, "%.1f", pct)
        val stakeColor = when {
            pct > 5.0 -> C_RED_SOFT
            pct > 2.0 -> C_AMBER
            else -> C_GOOD
        }
        val sl = mi.length
        mi.append("\nStake Rs ").append(stake().toString()).append(" = ").append(pctStr)
            .append("% bankroll  |  ").append(TYPES[typeIdx])
        mi.setSpan(ForegroundColorSpan(stakeColor), sl, mi.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)

        // Last bets ke dots (green = jeet, laal = haar)
        mi.append("\nBets: ")
        for (ch in betLog.toString().takeLast(24)) {
            val d0 = mi.length
            mi.append("\u25CF")
            mi.setSpan(
                ForegroundColorSpan(if (ch == 'W') C_GOOD else C_RED_SOFT),
                d0, mi.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        var alert = ""
        if (limitHit) {
            alert = "\n!! LIMIT CROSS. Aaj ruk jao."
        } else if (curBank <= 0) {
            alert = "\n!! Bankroll khatam."
        } else if (lossStreak >= 3) {
            alert = "\n! $lossStreak haar lagatar. Ruk jao."
        } else if (chasing) {
            alert = "\n! Haar ke baad bet badha rahe ho."
        } else if (pct > 5.0) {
            alert = "\n! Stake bankroll ke 5% se zyada hai."
        }
        if (alert.isNotEmpty()) {
            val a0 = mi.length
            mi.append(alert)
            mi.setSpan(ForegroundColorSpan(C_RED_SOFT), a0, mi.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        moneyInfo.text = mi

        // Stats view
        if (statsBox.visibility == View.VISIBLE) statsInfo.text = statsString()

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
        if (shownPred != pred) {
            shownPred = pred
            popBubble()
        }
    }

    // ---------- Save / Load ----------

    private fun load() {
        val saved = prefs.getString("log", null) ?: prefs.getString("h", "") ?: ""
        full.append(saved.takeLast(MAX_FULL))
        syncHistory()
        hits = prefs.getInt("hits", 0)
        total = prefs.getInt("total", 0)
        lastCounted = prefs.getInt("lc", -1)
        pred = predict()

        net = prefs.getString("net", "0")?.toDoubleOrNull() ?: 0.0
        peakNet = prefs.getString("mpeak", "0")?.toDoubleOrNull() ?: 0.0
        staked = prefs.getString("staked", "0")?.toDoubleOrNull() ?: 0.0
        wins = prefs.getInt("mwins", 0)
        losses = prefs.getInt("mlosses", 0)
        lossStreak = prefs.getInt("mstreak", 0)
        lastStake = prefs.getInt("mlaststake", 0)
        lastLost = prefs.getBoolean("mlastlost", false)
        stakeIdx = prefs.getInt("mstakeidx", 2).coerceIn(0, STAKES.size - 1)
        typeIdx = prefs.getInt("mtype", 0).coerceIn(0, TYPES.size - 1)
        lossLimit = prefs.getInt("mlimit", 300)
        bankroll = prefs.getInt("mbank", 1000)
        betLog.append((prefs.getString("mbetlog", "") ?: "").takeLast(60))
    }

    private fun save() {
        prefs.edit()
            .putString("log", full.toString())
            .putInt("hits", hits)
            .putInt("total", total)
            .putInt("lc", lastCounted)
            .putString("net", net.toString())
            .putString("mpeak", peakNet.toString())
            .putString("staked", staked.toString())
            .putInt("mwins", wins)
            .putInt("mlosses", losses)
            .putInt("mstreak", lossStreak)
            .putInt("mlaststake", lastStake)
            .putBoolean("mlastlost", lastLost)
            .putInt("mstakeidx", stakeIdx)
            .putInt("mtype", typeIdx)
            .putInt("mlimit", lossLimit)
            .putInt("mbank", bankroll)
            .putString("mbetlog", betLog.toString())
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
