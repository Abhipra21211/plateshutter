package com.nish.plateshutter

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

class ClickService : AccessibilityService() {

    companion object {
        const val DEF_KW = "transformer,kva,serial,volts,vector,impedance,phase,frequency"
    }

    private lateinit var wm: WindowManager
    private var panel: View? = null
    private var dot: View? = null
    private lateinit var panelParams: WindowManager.LayoutParams
    private lateinit var dotParams: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var startBtn: Button
    private lateinit var moveBtn: Button
    private lateinit var modeBtn: Button
    private lateinit var statusView: TextView

    private var running = false
    private var moveMode = false
    private var mode = 0 // 0 TIMER, 1 WATCH
    private val watchOk = Build.VERSION.SDK_INT >= 30

    private var keywords: List<String> = emptyList()
    private var minMatch = 3
    private var sharpMin = 60
    private var pauseSec = 5
    private var timerSec = 10
    private var shotLimit = 50
    private var shots = 0
    private var countdown = 0
    private var locked = false
    private var busy = false
    private var recognizer: TextRecognizer? = null

    private val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { stopClicking() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (panel == null) buildOverlay()
    }

    override fun onDestroy() {
        stopClicking()
        removeOverlay()
        try { recognizer?.close() } catch (_: Exception) {}
        super.onDestroy()
    }
// end s1

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun setStatus(s: String) { if (::statusView.isInitialized) statusView.text = s }

    // ---------- settings ----------
    private fun num(sp: SharedPreferences, k: String, d: Int): Int =
        sp.getString(k, null)?.trim()?.toIntOrNull() ?: d

    private fun loadCfg() {
        val sp = getSharedPreferences("cfg", Context.MODE_PRIVATE)
        keywords = (sp.getString("kw", DEF_KW) ?: DEF_KW).split(",")
            .map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        minMatch = num(sp, "min", 3).coerceAtLeast(1)
        sharpMin = num(sp, "sharp", 60)
        pauseSec = num(sp, "pause", 5).coerceAtLeast(1)
        timerSec = num(sp, "timer", 10).coerceAtLeast(1)
        shotLimit = num(sp, "limit", 50).coerceAtLeast(1)
    }

    // ---------- start / stop ----------
    private fun startRun() {
        if (moveMode) moveBtn.performClick()
        loadCfg()
        shots = 0; locked = false; busy = false
        running = true
        startBtn.text = "STOP"
        if (mode == 0) {
            countdown = timerSec
            handler.post(timerTick)
        } else {
            setStatus("Looking...")
            handler.post(scanTick)
        }
    }

    private fun stopClicking() {
        running = false
        busy = false
        handler.removeCallbacksAndMessages(null)
        if (::startBtn.isInitialized) startBtn.text = "START"
    }

    private fun endRun(msg: String) {
        stopClicking()
        setStatus(msg)
    }

    // ---------- shutter tap ----------
    private fun tapOnce() {
        val path = Path().apply {
            moveTo(dotParams.x + dotParams.width / 2f, dotParams.y + dotParams.height / 2f)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50)).build()
        try { dispatchGesture(g, null, null) } catch (_: Exception) {}
    }

    // ---------- TIMER mode ----------
    private val timerTick = object : Runnable {
        override fun run() {
            if (!running) return
            if (countdown <= 0) {
                tapOnce(); shots++
                if (shots >= shotLimit) { endRun("Done: $shots shots"); return }
                countdown = timerSec
            }
            setStatus("Shot ${shots + 1}/$shotLimit in ${countdown}s")
            countdown--
            handler.postDelayed(this, 1000)
        }
    }
// end s2

    // ---------- WATCH mode ----------
    private val scanTick = Runnable { if (running) scan() }
    private fun next(ms: Long) { handler.postDelayed(scanTick, ms) }

    private fun scan() {
        if (Build.VERSION.SDK_INT < 30 || busy) return
        busy = true
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor,
            object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(r: AccessibilityService.ScreenshotResult) {
                    var bmp: Bitmap? = null
                    try {
                        val hb = r.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, r.colorSpace)
                        bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hb.close()
                    } catch (_: Exception) {}
                    if (bmp == null) { busy = false; next(1200); return }
                    analyze(bmp)
                }
                override fun onFailure(code: Int) {
                    busy = false
                    setStatus("Screenshot blocked ($code), use TIMER")
                    next(1500)
                }
            })
    }

    private fun analyze(bmp: Bitmap) {
        val sharp = sharpness(bmp)
        val rec = recognizer
            ?: TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).also { recognizer = it }
        rec.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { t ->
                bmp.recycle()
                val text = t.text.lowercase()
                handleResult(keywords.count { text.contains(it) }, sharp)
            }
            .addOnFailureListener {
                bmp.recycle()
                busy = false
                setStatus("Text engine loading, wait")
                next(3000)
            }
    }

    private fun handleResult(hits: Int, sharp: Double) {
        busy = false
        if (!running) return
        if (hits < minMatch) {
            locked = false
            setStatus("Looking... words $hits/$minMatch")
            next(1200); return
        }
        if (locked) { setStatus("Plate seen, show the next one"); next(1200); return }
        if (sharp < sharpMin) {
            setStatus("Blurry ${sharp.toInt()} < $sharpMin, hold steady")
            next(1200); return
        }
        tapOnce(); shots++; locked = true
        if (shots >= shotLimit) { endRun("Done: $shots shots"); return }
        setStatus("Shot $shots/$shotLimit taken (sharp ${sharp.toInt()})")
        next(pauseSec * 1000L)
    }

    private fun sharpness(src: Bitmap): Double {
        val w = 480
        val h = (src.height * w / src.width.toFloat()).toInt().coerceAtLeast(3)
        val sm = Bitmap.createScaledBitmap(src, w, h, true)
        val px = IntArray(w * h)
        sm.getPixels(px, 0, w, 0, 0, w, h)
        val g = IntArray(w * h) {
            val c = px[it]
            (((c shr 16) and 255) * 30 + ((c shr 8) and 255) * 59 + (c and 255) * 11) / 100
        }
        var sum = 0.0; var sq = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val l = 4 * g[i] - g[i - 1] - g[i + 1] - g[i - w] - g[i + w]
            sum += l; sq += l.toDouble() * l; n++
        }
        if (sm !== src) sm.recycle()
        val mean = sum / n
        return sq / n - mean * mean
    }
// end s3

    // ---------- overlay ----------
    private fun updateUi() {
        modeBtn.text = if (mode == 0) "MODE: TIMER" else "MODE: WATCH"
        setStatus((if (mode == 0) "Timed shots" else "Auto shoot") + " | Android " + Build.VERSION.RELEASE)
        panel?.let { try { wm.updateViewLayout(it, panelParams) } catch (_: Exception) {} }
    }

    private fun buildOverlay() {
        val dm = resources.displayMetrics
        val type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        val size = dp(56)

        val dotView = TextView(this).apply {
            text = "1"
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 16f
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x99FF0000.toInt())
                setStroke(dp(2), Color.WHITE)
            }
        }
        dotParams = WindowManager.LayoutParams(
            size, size, type,
            baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels / 2 - size / 2
            y = (0.85f * dm.heightPixels).toInt() - size / 2
        }
        var sx = 0; var sy = 0; var tx = 0f; var ty = 0f
        dotView.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = dotParams.x; sy = dotParams.y; tx = e.rawX; ty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    dotParams.x = sx + (e.rawX - tx).toInt()
                    dotParams.y = sy + (e.rawY - ty).toInt()
                    wm.updateViewLayout(dotView, dotParams)
                }
            }
            true
        }
        dot = dotView
        wm.addView(dotView, dotParams)

        startBtn = Button(this).apply { text = "START" }
        moveBtn = Button(this).apply { text = "MOVE: OFF" }
        modeBtn = Button(this).apply { text = "MODE: TIMER" }
        statusView = TextView(this).apply { setTextColor(Color.YELLOW); textSize = 12f; gravity = Gravity.CENTER }
        val closeBtn = Button(this).apply { text = "CLOSE" }

        startBtn.setOnClickListener { if (running) stopClicking() else startRun() }

        moveBtn.setOnClickListener {
            moveMode = !moveMode
            if (moveMode) stopClicking()
            moveBtn.text = if (moveMode) "MOVE: ON" else "MOVE: OFF"
            dotParams.flags = if (moveMode) baseFlags
            else baseFlags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            wm.updateViewLayout(dotView, dotParams)
        }

        modeBtn.setOnClickListener {
            stopClicking()
            if (watchOk) {
                mode = (mode + 1) % 2
                updateUi()
            } else {
                updateUi()
                setStatus("WATCH needs Android 11+")
            }
        }

        closeBtn.setOnClickListener {
            stopClicking(); removeOverlay(); disableSelf()
        }
// end s4

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(modeBtn); addView(startBtn); addView(moveBtn)
            addView(statusView); addView(closeBtn)
        }
        val handle = TextView(this).apply {
            text = "DRAG HERE"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(12), dp(6), dp(12))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val minBtn = Button(this).apply {
            text = "-"
            minWidth = 0
            minimumWidth = dp(44)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(handle); addView(minBtn)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xCC000000.toInt())
            setPadding(dp(8), dp(4), dp(8), dp(8))
            addView(header); addView(body)
        }
        panelParams = WindowManager.LayoutParams(
            dp(160), WindowManager.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = dp(4); y = dm.heightPixels / 4 }

        var px = 0; var py = 0; var ptx = 0f; var pty = 0f
        handle.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { px = panelParams.x; py = panelParams.y; ptx = e.rawX; pty = e.rawY }
                MotionEvent.ACTION_MOVE -> {
                    panelParams.x = px + (e.rawX - ptx).toInt()
                    panelParams.y = py + (e.rawY - pty).toInt()
                    wm.updateViewLayout(box, panelParams)
                }
            }
            true
        }
        minBtn.setOnClickListener {
            val hide = body.visibility == View.VISIBLE
            body.visibility = if (hide) View.GONE else View.VISIBLE
            minBtn.text = if (hide) "+" else "-"
            panelParams.width = if (hide) dp(130) else dp(160)
            wm.updateViewLayout(box, panelParams)
        }
        panel = box
        wm.addView(box, panelParams)
        updateUi()
    }

    private fun removeOverlay() {
        try { panel?.let { wm.removeView(it) } } catch (_: Exception) {}
        try { dot?.let { wm.removeView(it) } } catch (_: Exception) {}
        panel = null; dot = null
    }
}
// end s5
