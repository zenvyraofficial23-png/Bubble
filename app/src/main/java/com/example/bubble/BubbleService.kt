package com.example.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt
import kotlin.random.Random

class BubbleService : Service() {

    companion object {
        private val NAMES = arrayOf("Majority", "Ulta streak", "Last copy", "Last ulta", "Random")
        private const val N = 5
        private const val MAX_LOG = 2000
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var bubble: TextView
    private lateinit var bubbleBg: GradientDrawable
    private lateinit var panel: LinearLayout
    private lateinit var resultBox: LinearLayout
    private lateinit var moneyBox: LinearLayout
    private lateinit var testBox: LinearLayout
    private lateinit var resultInfo: TextView
    private lateinit var moneyInfo: TextView
    private lateinit var testInfo: TextView
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var panelLp: WindowManager.LayoutParams

    // Poora log (max 2000 result): 'B' = Big, 'S' = Small. Test aur CSV isi se bante hain.
    private val log = StringBuilder()

    // Rules sirf last 50 result dekhte hain
    private val history = StringBuilder()

    // Har rule ka apna hit/total
    private val sHits = IntArray(N)
    private val sTotal = IntArray(N)
    private val picks = CharArray(N) { '-' }
    private var snapHits: IntArray? = null
    private var snapTotal: IntArray? = null
    private var sel = 0

    // Money tracker
    private var net = 0
    private var bet = 10
    private var lossLimit = 500
    private var wins = 0
    private var losses = 0
    private var lastDelta: Int? = null

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
        bubbleBg = GradientDrawable().apply {
            setShape(GradientDrawable.OVAL)
            setColor(0xFF6200EE.toInt())
        }
        bubble = TextView(this).apply {
            text = "?"
            textSize = 18f
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
        val bg = GradientDrawable().apply {
            setColor(0xEE212121.toInt())
            cornerRadius = dp(16).toFloat()
        }
        resultInfo = infoText()
        moneyInfo = infoText()
        testInfo = infoText()

        resultBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(resultInfo)
            addView(row(btn("BIG") { addResult('B') }, btn("SMALL") { addResult('S') }))
            addView(
                row(
                    btn("UNDO") { undoResult() },
                    btn("RULE") { nextRule() },
                    btn("RESET") { resetResults() }
                )
            )
        }

        moneyBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(moneyInfo)
            addView(row(btn("BET -") { changeBet(-10) }, btn("BET +") { changeBet(10) }))
            addView(
                row(
                    btn("WON") { moneyWon() },
                    btn("LOST") { moneyLost() },
                    btn("UNDO") { undoMoney() }
                )
            )
            addView(
                row(
                    btn("LIMIT -") { changeLimit(-100) },
                    btn("LIMIT +") { changeLimit(100) },
                    btn("RESET") { resetMoney() }
                )
            )
        }

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(230))
            addView(testInfo)
        }
        testBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(scroll)
            addView(row(btn("EXPORT CSV") { exportCsv() }))
        }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            setPadding(dp(12), dp(12), dp(12), dp(12))
            visibility = View.GONE
            addView(
                row(
                    btn("RESULT") { showTab(0) },
                    btn("MONEY") { showTab(1) },
                    btn("TEST") { showTab(2) }
                )
            )
            addView(resultBox)
            addView(moneyBox)
            addView(testBox)
            addView(row(btn("EXIT") { stopSelf() }))
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

    private fun infoText() = TextView(this).apply {
        setTextColor(Color.WHITE)
        textSize = 12f
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it) }
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        textSize = 11f
        minHeight = dp(36)
        minimumHeight = dp(36)
        setPadding(dp(2), 0, dp(2), 0)
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

    // tab: 0 = RESULT, 1 = MONEY, 2 = TEST
    private fun showTab(tab: Int) {
        resultBox.visibility = if (tab == 0) View.VISIBLE else View.GONE
        moneyBox.visibility = if (tab == 1) View.VISIBLE else View.GONE
        testBox.visibility = if (tab == 2) View.VISIBLE else View.GONE
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

    // ---------- Results / Rules ----------

    private fun syncHistory() {
        history.setLength(0)
        history.append(log.toString().takeLast(50))
    }

    private fun pickFor(i: Int): Char {
        val n = history.length
        if (n < 10) return '-'
        val b = history.count { it == 'B' }
        val s = n - b
        val last = history[n - 1]
        val opp = if (last == 'B') 'S' else 'B'
        var streak = 0
        for (k in n - 1 downTo 0) {
            if (history[k] == last) streak++ else break
        }
        return when (i) {
            0 -> if (b >= s) 'B' else 'S'
            1 -> if (streak >= 3) opp else if (b >= s) 'B' else 'S'
            2 -> last
            3 -> opp
            else -> if (Random.nextBoolean()) 'B' else 'S'
        }
    }

    private fun computePicks() {
        for (i in 0 until N) picks[i] = pickFor(i)
    }

    private fun addResult(c: Char) {
        snapHits = sHits.copyOf()
        snapTotal = sTotal.copyOf()
        for (i in 0 until N) {
            if (picks[i] != '-') {
                sTotal[i]++
                if (picks[i] == c) sHits[i]++
            }
        }
        log.append(c)
        if (log.length > MAX_LOG) log.deleteCharAt(0)
        syncHistory()
        computePicks()
        save()
        refresh()
    }

    private fun undoResult() {
        val sh = snapHits ?: return
        val st = snapTotal ?: return
        if (log.isEmpty()) return
        log.deleteCharAt(log.length - 1)
        for (i in 0 until N) {
            sHits[i] = sh[i]
            sTotal[i] = st[i]
        }
        snapHits = null
        snapTotal = null
        syncHistory()
        computePicks()
        save()
        refresh()
    }

    private fun resetResults() {
        log.setLength(0)
        syncHistory()
        sHits.fill(0)
        sTotal.fill(0)
        snapHits = null
        snapTotal = null
        computePicks()
        save()
        refresh()
    }

    private fun nextRule() {
        sel = (sel + 1) % N
        save()
        refresh()
    }

    // ---------- Money ----------

    private fun changeBet(d: Int) {
        bet = (bet + d).coerceAtLeast(10)
        save()
        refresh()
    }

    private fun changeLimit(d: Int) {
        lossLimit = (lossLimit + d).coerceAtLeast(100)
        save()
        refresh()
    }

    private fun moneyWon() {
        net += bet
        wins++
        lastDelta = bet
        save()
        refresh()
    }

    private fun moneyLost() {
        net -= bet
        losses++
        lastDelta = -bet
        save()
        refresh()
    }

    private fun undoMoney() {
        val d = lastDelta ?: return
        net -= d
        if (d > 0) wins-- else losses--
        lastDelta = null
        save()
        refresh()
    }

    private fun resetMoney() {
        net = 0
        wins = 0
        losses = 0
        lastDelta = null
        save()
        refresh()
    }

    // ---------- Randomness test ----------

    // Abramowitz-Stegun 7.1.26 approximation, x >= 0
    private fun erfc(x: Double): Double {
        val t = 1.0 / (1.0 + 0.3275911 * x)
        val poly = ((((1.061405429 * t - 1.453152027) * t + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t
        return minOf(1.0, poly * exp(-x * x))
    }

    // chi-square, 1 degree of freedom
    private fun pFromChi1(chi: Double) = erfc(sqrt(chi / 2.0))

    // z-score, two-sided
    private fun pFromZ(z: Double) = erfc(abs(z) / sqrt(2.0))

    private fun fmt(x: Double) = String.format(Locale.US, "%.3f", x)

    private fun verdict(p: Double) = if (p >= 0.05) "-> random jaisa (OK)" else "-> alag dikh raha"

    private fun testText(): String {
        val s = log.toString()
        val n = s.length
        val sb = StringBuilder()
        sb.append("Total result daale: $n\n")
        if (n < 30) {
            sb.append("\nData abhi bahut kam hai. Kam se kam 100 result daalo (zyada ho to behtar), tab test ka matlab hoga.")
            return sb.toString()
        }

        var flagged = 0
        val b = s.count { it == 'B' }
        val sm = n - b

        // 1) Big/Small barabar aate hain? (chi-square)
        val chi = (b - sm).toDouble() * (b - sm).toDouble() / n
        val p1 = pFromChi1(chi)
        if (p1 < 0.05) flagged++
        sb.append("\n1) Big/Small barabar aate hain?\n")
        sb.append("   Big $b | Small $sm\n")
        sb.append("   chi2=${fmt(chi)}, p=${fmt(p1)} ${verdict(p1)}\n")

        // 2) Runs test: streak zyada ya kam banti hain?
        if (b > 0 && sm > 0) {
            var runs = 1
            for (i in 1 until n) {
                if (s[i] != s[i - 1]) runs++
            }
            val mu = 2.0 * b * sm / n + 1.0
            val v = (mu - 1.0) * (mu - 2.0) / (n - 1.0)
            if (v > 0) {
                val z = (runs - mu) / sqrt(v)
                val p2 = pFromZ(z)
                if (p2 < 0.05) flagged++
                sb.append("\n2) Streak normal hain? (runs test)\n")
                sb.append("   Runs: $runs, expected ${fmt(mu)}\n")
                sb.append("   z=${fmt(z)}, p=${fmt(p2)} ${verdict(p2)}\n")
            }
        }

        // 3) Pichla result agle ko affect karta hai? (chi-square, 2x2)
        var bb = 0
        var bs = 0
        var sb2 = 0
        var ss = 0
        for (i in 1 until n) {
            val a = s[i - 1]
            val c = s[i]
            if (a == 'B') {
                if (c == 'B') bb++ else bs++
            } else {
                if (c == 'B') sb2++ else ss++
            }
        }
        val m = bb + bs + sb2 + ss
        val den = (bb + bs).toDouble() * (sb2 + ss) * (bb + sb2) * (bs + ss)
        if (den > 0) {
            val cross = bb.toDouble() * ss - bs.toDouble() * sb2
            val chi3 = m * cross * cross / den
            val p3 = pFromChi1(chi3)
            if (p3 < 0.05) flagged++
            val afterB = bb * 100 / (bb + bs)
            val afterS = sb2 * 100 / (sb2 + ss)
            sb.append("\n3) Pichla result agle ko affect karta hai?\n")
            sb.append("   Big ke baad Big: $afterB% | Small ke baad Big: $afterS%\n")
            sb.append("   chi2=${fmt(chi3)}, p=${fmt(p3)} ${verdict(p3)}\n")
        }

        // 4) Sabse achha rule sach me 50% se upar hai?
        var best = -1
        var bestZ = -1e9
        for (i in 0 until N) {
            if (sTotal[i] >= 20) {
                val z = (sHits[i] - 0.5 * sTotal[i]) / sqrt(0.25 * sTotal[i])
                if (z > bestZ) {
                    bestZ = z
                    best = i
                }
            }
        }
        if (best >= 0) {
            val rate = sHits[best] * 100 / sTotal[best]
            val p = pFromZ(bestZ)
            val adj = minOf(1.0, p * N)
            if (adj < 0.05) flagged++
            sb.append("\n4) Sabse achha rule: ${NAMES[best]}\n")
            sb.append("   Hit ${sHits[best]}/${sTotal[best]} ($rate%)\n")
            sb.append("   p=${fmt(p)}. $N rules me se best chuna, isliye adjusted p=${fmt(adj)} ${verdict(adj)}\n")
        }

        sb.append("\n")
        if (flagged == 0) {
            sb.append("Nateeja: koi pattern nahi mila. Pichle results se agla result predict karne ka koi aadhar nahi dikh raha.")
        } else {
            sb.append("Nateeja: $flagged test 'alag' dikha rahe hain. Itne data me ye aksar sanyog hota hai, kyunki har test 20 me se 1 baar galti se 'alag' bata deta hai. Aur result daalo; sach me pattern hota to bade sample me bhi rehta, par aksar ye wapas random ho jata hai.")
        }
        return sb.toString()
    }

    // ---------- CSV export ----------

    private fun buildCsv(): String {
        val s = log.toString()
        val sb = StringBuilder("round,result,big_total,small_total\n")
        var b = 0
        var sm = 0
        for (i in s.indices) {
            val name: String
            if (s[i] == 'B') {
                b++
                name = "Big"
            } else {
                sm++
                name = "Small"
            }
            sb.append("${i + 1},$name,$b,$sm\n")
        }
        return sb.toString()
    }

    private fun exportCsv() {
        if (log.isEmpty()) {
            Toast.makeText(this, "Abhi koi result nahi hai", Toast.LENGTH_SHORT).show()
            return
        }
        val csv = buildCsv()
        val name = "bubble_results_" + System.currentTimeMillis() + ".csv"
        var msg = ""
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                if (uri != null) {
                    contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray()) }
                    msg = "Downloads me save hua: $name"
                } else {
                    msg = "File save nahi ho payi"
                }
            } else {
                val f = File(getExternalFilesDir(null), name)
                f.writeText(csv)
                msg = "Save hua: ${f.absolutePath}"
            }
        } catch (e: Exception) {
            msg = "Export fail: ${e.message}"
        }
        try {
            val cm = getSystemService(ClipboardManager::class.java)
            cm.setPrimaryClip(ClipData.newPlainText("bubble", csv))
            msg += " (clipboard me bhi copy hua)"
        } catch (e: Exception) {
            // clipboard na chale to koi baat nahi
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }

    // ---------- Show ----------

    private fun refresh() {
        val n = history.length
        val b = history.count { it == 'B' }

        val sb = StringBuilder()
        sb.append("Last $n: Big $b | Small ${n - b}\n")
        for (i in 0 until N) {
            val p = when (picks[i]) {
                'B' -> "BIG"
                'S' -> "SMALL"
                else -> "-"
            }
            val rate = if (sTotal[i] > 0) sHits[i] * 100 / sTotal[i] else 0
            val mark = if (i == sel) "> " else "   "
            sb.append("$mark${NAMES[i]}: $p  ${sHits[i]}/${sTotal[i]} ($rate%)\n")
        }
        if (n < 10) sb.append("Prediction ke liye ${10 - n} result aur daalo")
        resultInfo.text = sb.toString().trimEnd()

        val limitHit = net <= -lossLimit
        val sign = if (net >= 0) "+" else "-"
        var m = "Net P/L: ${sign}Rs ${abs(net)}\n" +
                "Won $wins | Lost $losses\n" +
                "Bet: Rs $bet | Loss limit: Rs $lossLimit"
        if (limitHit) m += "\n!! LIMIT CROSS !! Ab ruk jao."
        moneyInfo.text = m

        if (testBox.visibility == View.VISIBLE) testInfo.text = testText()

        bubble.text = when (picks[sel]) {
            'B' -> "B"
            'S' -> "S"
            else -> "?"
        }
        bubbleBg.setColor(if (limitHit) 0xFFD32F2F.toInt() else 0xFF6200EE.toInt())
    }

    // ---------- Save / Load ----------

    private fun load() {
        val saved = prefs.getString("log", null) ?: prefs.getString("h", "") ?: ""
        log.append(saved)
        while (log.length > MAX_LOG) log.deleteCharAt(0)
        syncHistory()
        for (i in 0 until N) {
            sHits[i] = prefs.getInt("sh$i", 0)
            sTotal[i] = prefs.getInt("st$i", 0)
        }
        sel = prefs.getInt("sel", 0).coerceIn(0, N - 1)
        net = prefs.getInt("net", 0)
        bet = prefs.getInt("bet", 10)
        lossLimit = prefs.getInt("lim", 500)
        wins = prefs.getInt("wins", 0)
        losses = prefs.getInt("losses", 0)
        computePicks()
    }

    private fun save() {
        val e = prefs.edit()
        e.putString("log", log.toString())
        for (i in 0 until N) {
            e.putInt("sh$i", sHits[i])
            e.putInt("st$i", sTotal[i])
        }
        e.putInt("sel", sel)
        e.putInt("net", net)
        e.putInt("bet", bet)
        e.putInt("lim", lossLimit)
        e.putInt("wins", wins)
        e.putInt("losses", losses)
        e.apply()
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
