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

class BubbleService : Service() {

    companion object {
        private val C_BIG = Color.parseColor("#FF9800")      // orange
        private val C_SMALL = Color.parseColor("#2196F3")    // blue
        private val C_PURPLE = Color.parseColor("#6200EE")
        private val C_GREY = Color.parseColor("#454556")
        private val C_PANEL = Color.parseColor("#F01E1E2C")
        private val C_SOFT = Color.parseColor("#B4B4C8")
        private val BS_NAMES = arrayOf("Cold number", "Ulta streak", "Majority", "Last ulta", "Last copy")
        private val NUM_NAMES = arrayOf("Cold-3", "Hot-3")
        private const val NB = 5      // Big/Small ke rules
        private const val NN = 2      // number ke rules
        private const val PW = 290    // panel width (dp)
    }

    private lateinit var wm: WindowManager
    private lateinit var prefs: SharedPreferences
    private lateinit var bubble: TextView
    private lateinit var bubbleBg: GradientDrawable
    private lateinit var panel: LinearLayout
    private lateinit var predText: TextView
    private lateinit var numText: TextView
    private lateinit var stripText: TextView
    private lateinit var statText: TextView
    private lateinit var hitText: TextView
    private lateinit var barBig: View
    private lateinit var barSmall: View
    private lateinit var bubbleLp: WindowManager.LayoutParams
    private lateinit var panelLp: WindowManager.LayoutParams

    // Sirf digits '0'..'9' (last 50 result, purana pehle). 0-4 = Small, 5-9 = Big.
    private val history = StringBuilder()

    // Big/Small rules ka hit/total
    private val sHits = IntArray(NB)
    private val sTotal = IntArray(NB)
    private val bsPicks = CharArray(NB) { '-' }

    // 3-number rules ka hit/total
    private val nHits = IntArray(NN)
    private val nTotal = IntArray(NN)
    private val numPicks = Array(NN) { IntArray(3) { -1 } }

    // Undo ke liye snapshot
    private var snapSH: IntArray? = null
    private var snapST: IntArray? = null
    private var snapNH: IntArray? = null
    private var snapNT: IntArray? = null

    private var selBs = 0
    private var selNum = 0
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val d = intent?.getStringExtra("digits")
        if (d != null) {
            for (ch in d) {
                if (ch in '0'..'9') addDigit(ch - '0', false)
            }
        }
        return START_STICKY
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
        numText = TextView(this).apply {
            textSize = 18f
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

        val rowCtl1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(colorBtn("UNDO", C_GREY, 12f, 40) { undo() })
            addView(colorBtn("RULE", C_GREY, 12f, 40) { nextBsRule() })
            addView(colorBtn("NUM", C_GREY, 12f, 40) { nextNumRule() })
        }
        val rowCtl2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(colorBtn("PASTE", C_GREY, 12f, 40) { openInput() })
            addView(colorBtn("RESET", C_GREY, 12f, 40) { reset() })
            addView(colorBtn("EXIT", C_GREY, 12f, 40) { stopSelf() })
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
            addView(predText)
            addView(numText)
            addView(stripText)
            addView(bar)
            addView(statText)
            addView(hitText)
            addView(numRow(0, 4))
            addView(numRow(5, 9))
            addView(rowCtl1)
            addView(rowCtl2)
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

    private fun numRow(from: Int, to: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        for (d in from..to) {
            addView(colorBtn(d.toString(), if (d >= 5) C_BIG else C_SMALL, 17f, 38) { addDigit(d, true) })
        }
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
            bubbleLp.y + dp(64)       // warna panel neeche
        }
        panelLp.y = wanted.coerceAtMost(dm.heightPixels - h).coerceAtLeast(0)
        wm.updateViewLayout(panel, panelLp)
    }

    private fun openInput() {
        panelOpen = false
        panel.visibility = View.GONE
        val i = Intent(this, InputActivity::class.java)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    // ---------- Predictions ----------

    private fun computePicks() {
        for (i in 0 until NB) bsPicks[i] = '-'
        for (j in 0 until NN) {
            for (k in 0 until 3) numPicks[j][k] = -1
        }
        val n = history.length
        if (n < 10) return

        val nums = IntArray(n) { history[it] - '0' }
        val sides = CharArray(n) { if (nums[it] >= 5) 'B' else 'S' }
        val b = sides.count { it == 'B' }
        val s = n - b
        val last = sides[n - 1]
        val opp = if (last == 'B') 'S' else 'B'
        var streak = 0
        for (k in n - 1 downTo 0) {
            if (sides[k] == last) streak++ else break
        }
        val major = if (b >= s) 'B' else 'S'

        // 0: Cold number. Pichle 15 round me jo number nahi aaya, unme sabse purana; uska side.
        if (n >= 15) {
            var coldDigit = -1
            var coldLast = Int.MAX_VALUE
            for (d in 0..9) {
                var lastSeen = -1
                for (i in 0 until n) {
                    if (nums[i] == d) lastSeen = i
                }
                val seenRecently = lastSeen >= n - 15
                if (!seenRecently && lastSeen < coldLast) {
                    coldLast = lastSeen
                    coldDigit = d
                }
            }
            if (coldDigit >= 0) bsPicks[0] = if (coldDigit >= 5) 'B' else 'S'
        }
        bsPicks[1] = if (streak >= 3) opp else major   // Ulta streak
        bsPicks[2] = major                             // Majority
        bsPicks[3] = opp                               // Last ulta
        bsPicks[4] = last                              // Last copy

        // 3-number rules
        val cnt = IntArray(10)
        val lastIdx = IntArray(10) { -1 }
        for (i in 0 until n) {
            cnt[nums[i]]++
            lastIdx[nums[i]] = i
        }
        val gap = IntArray(10) { if (lastIdx[it] < 0) n + 1 else n - 1 - lastIdx[it] }
        val cold = (0..9).sortedWith(compareBy<Int>({ -gap[it] }, { it })).take(3)
        val hot = (0..9).sortedWith(compareBy<Int>({ -cnt[it] }, { -lastIdx[it] }, { it })).take(3)
        for (k in 0 until 3) {
            numPicks[0][k] = cold[k]
            numPicks[1][k] = hot[k]
        }
    }

    private fun addDigit(d: Int, score: Boolean) {
        if (score) {
            snapSH = sHits.copyOf()
            snapST = sTotal.copyOf()
            snapNH = nHits.copyOf()
            snapNT = nTotal.copyOf()
            val side = if (d >= 5) 'B' else 'S'
            for (i in 0 until NB) {
                if (bsPicks[i] != '-') {
                    sTotal[i]++
                    if (bsPicks[i] == side) sHits[i]++
                }
            }
            for (j in 0 until NN) {
                if (numPicks[j][0] >= 0) {
                    nTotal[j]++
                    if (numPicks[j].contains(d)) nHits[j]++
                }
            }
        } else {
            clearSnap()
        }
        history.append('0' + d)
        if (history.length > 50) history.deleteCharAt(0)
        computePicks()
        save()
        refresh()
    }

    private fun clearSnap() {
        snapSH = null
        snapST = null
        snapNH = null
        snapNT = null
    }

    private fun undo() {
        val a = snapSH ?: return
        val b = snapST ?: return
        val c = snapNH ?: return
        val e = snapNT ?: return
        if (history.isEmpty()) return
        history.deleteCharAt(history.length - 1)
        for (i in 0 until NB) {
            sHits[i] = a[i]
            sTotal[i] = b[i]
        }
        for (j in 0 until NN) {
            nHits[j] = c[j]
            nTotal[j] = e[j]
        }
        clearSnap()
        computePicks()
        save()
        refresh()
    }

    private fun reset() {
        history.setLength(0)
        sHits.fill(0)
        sTotal.fill(0)
        nHits.fill(0)
        nTotal.fill(0)
        clearSnap()
        computePicks()
        save()
        refresh()
    }

    private fun nextBsRule() {
        selBs = (selBs + 1) % NB
        save()
        refresh()
    }

    private fun nextNumRule() {
        selNum = (selNum + 1) % NN
        save()
        refresh()
    }

    // ---------- Show ----------

    private fun refresh() {
        val n = history.length
        var b = 0
        for (c in history) {
            if (c >= '5') b++
        }
        val s = n - b

        val pr = bsPicks[selBs]
        when (pr) {
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
                predText.text = if (n < 10) "Pehle 10 number daalo ($n/10)" else "Is rule ka abhi koi signal nahi"
                predText.setTextColor(Color.WHITE)
            }
        }

        // 3 numbers
        val nm = SpannableStringBuilder()
        val pk = numPicks[selNum]
        if (pk[0] >= 0) {
            nm.append("3 NUMBERS:  ")
            for (k in 0 until 3) {
                val start = nm.length
                nm.append(pk[k].toString())
                nm.setSpan(
                    ForegroundColorSpan(if (pk[k] >= 5) C_BIG else C_SMALL),
                    start, nm.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
                if (k < 2) nm.append("   ")
            }
        } else {
            nm.append("3 NUMBERS: -")
        }
        numText.text = nm

        // Last 16 number (purana left, naya right)
        val strip = SpannableStringBuilder()
        for (ch in history.toString().takeLast(16)) {
            val start = strip.length
            strip.append(ch.toString())
            strip.setSpan(
                ForegroundColorSpan(if (ch >= '5') C_BIG else C_SMALL),
                start, strip.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            strip.append(" ")
        }
        stripText.text = strip

        val wb = if (n == 0) 1f else b.toFloat()
        val ws = if (n == 0) 1f else s.toFloat()
        barBig.layoutParams = LinearLayout.LayoutParams(0, dp(8), wb)
        barSmall.layoutParams = LinearLayout.LayoutParams(0, dp(8), ws)

        statText.text = "Big $b  |  Small $s   (last $n)"
        val r1 = if (sTotal[selBs] > 0) sHits[selBs] * 100 / sTotal[selBs] else 0
        val r2 = if (nTotal[selNum] > 0) nHits[selNum] * 100 / nTotal[selNum] else 0
        hitText.text = "B/S [${BS_NAMES[selBs]}]  ${sHits[selBs]}/${sTotal[selBs]} ($r1%)  coin 50%\n" +
                "Num [${NUM_NAMES[selNum]}]  ${nHits[selNum]}/${nTotal[selNum]} ($r2%)  random 30%"

        bubble.text = when (pr) {
            'B' -> "B"
            'S' -> "S"
            else -> "?"
        }
        bubbleBg.setColor(
            when (pr) {
                'B' -> C_BIG
                'S' -> C_SMALL
                else -> C_PURPLE
            }
        )
    }

    // ---------- Save / Load ----------

    private fun load() {
        val saved = prefs.getString("log", null) ?: ""
        history.append(saved.filter { it in '0'..'9' }.takeLast(50))
        for (i in 0 until NB) {
            sHits[i] = prefs.getInt("bsh$i", 0)
            sTotal[i] = prefs.getInt("bst$i", 0)
        }
        for (j in 0 until NN) {
            nHits[j] = prefs.getInt("nh$j", 0)
            nTotal[j] = prefs.getInt("nt$j", 0)
        }
        selBs = prefs.getInt("selbs", 0).coerceIn(0, NB - 1)
        selNum = prefs.getInt("selnum", 0).coerceIn(0, NN - 1)
        computePicks()
    }

    private fun save() {
        val e = prefs.edit()
        e.putString("log", history.toString())
        for (i in 0 until NB) {
            e.putInt("bsh$i", sHits[i])
            e.putInt("bst$i", sTotal[i])
        }
        for (j in 0 until NN) {
            e.putInt("nh$j", nHits[j])
            e.putInt("nt$j", nTotal[j])
        }
        e.putInt("selbs", selBs)
        e.putInt("selnum", selNum)
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
