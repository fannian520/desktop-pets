package com.pet

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.sin

/* ============================ MainActivity ============================ */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(60, 120, 60, 60)
            setBackgroundColor(Color.parseColor("#0E1420"))
        }

        ll.addView(TextView(this).apply {
            text = "桌面宠物"
            textSize = 26f
            setTextColor(Color.parseColor("#8FE6FF"))
            setPadding(0, 0, 0, 30)
        })

        ll.addView(TextView(this).apply {
            text = "1. 点「授予悬浮窗权限」并打开开关\n2. 点「启动宠物」\n3. 回到桌面即可看到它\n\n可以拖动宠物，点它会互动。"
            textSize = 15f
            setTextColor(Color.parseColor("#93A4C8"))
            setLineSpacing(10f, 1f)
            setPadding(0, 0, 0, 40)
        })

        ll.addView(makeButton("授予悬浮窗权限") {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "已授权", Toast.LENGTH_SHORT).show()
            }
        })

        ll.addView(makeButton("启动宠物") {
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "请先授予悬浮窗权限", Toast.LENGTH_LONG).show()
                return@makeButton
            }
            val i = Intent(this, PetService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
            else startService(i)
            Toast.makeText(this, "宠物已出现，回到桌面看看", Toast.LENGTH_SHORT).show()
        })

        ll.addView(makeButton("关闭宠物") {
            stopService(Intent(this, PetService::class.java))
            Toast.makeText(this, "已关闭", Toast.LENGTH_SHORT).show()
        })

        setContentView(ll)
    }

    private fun makeButton(text: String, onClick: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 16f
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 20 }
        }
}

/* ============================ PetService ============================ */
class PetService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var petView: PetView
    private lateinit var params: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            petView.onBattery(level, charging)
        }
    }

    private val tickTask = object : Runnable {
        override fun run() {
            petView.onTick()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager

        petView = PetView(this)
        params = WindowManager.LayoutParams(
            420, 420,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 80
            y = 700
        }
        wm.addView(petView, params)

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        handler.post(tickTask)
    }

    private fun startForegroundCompat() {
        val channelId = "pet"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(channelId) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(channelId, "桌面宠物", NotificationManager.IMPORTANCE_MIN)
                )
            }
            val n = Notification.Builder(this, channelId)
                .setContentTitle("桌面宠物运行中")
                .setContentText("回到桌面即可看到它")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()
            startForeground(1, n)
        } else {
            val n = Notification.Builder(this)
                .setContentTitle("桌面宠物运行中")
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .build()
            startForeground(1, n)
        }
    }

    fun moveBy(dx: Float, dy: Float) {
        params.x += dx.toInt()
        params.y += dy.toInt()
        wm.updateViewLayout(petView, params)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        try { wm.removeView(petView) } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(i: Intent?): IBinder? = null
}

/* ============================ PetView ============================ */
@SuppressLint("ViewConstructor")
class PetView(private val svc: PetService) : View(svc) {

    enum class Mood { IDLE, HAPPY, SLEEP, HUNGRY, CHARGING }

    private var t = 0f
    private var mood = Mood.IDLE
    private var bubble: String? = null
    private var bubbleUntil = 0L

    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f

    private val bodyPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#5BC8FF") }
    private val bodyDark    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#3A8FC0") }
    private val eyePaint    = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#0A2030") }
    private val antennaPaint= Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#3AA0D8"); strokeWidth = 6f; strokeCap = Paint.Cap.ROUND
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val textPaint   = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#16202A"); textSize = 30f
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#40000000") }
    private val hlPaint     = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#80FFFFFF") }
    private val mouthPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#0A2030"); strokeWidth = 5f
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    }
    private val glintPaint  = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val frame = object : Runnable {
        override fun run() {
            t += 0.055f
            invalidate()
            postDelayed(this, 16)
        }
    }

    init { post(frame) }

    fun onBattery(level: Int, charging: Boolean) {
        when {
            charging && level >= 100 -> showBubble("满电啦 ✨", Mood.HAPPY)
            charging                  -> showBubble("充电中…", Mood.CHARGING)
            level <= 15               -> showBubble("饿了…", Mood.HUNGRY)
        }
    }

    fun onTick() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        if (h >= 23 || h < 6) {
            if (mood != Mood.SLEEP) { mood = Mood.SLEEP; bubble = null }
        } else if (mood == Mood.SLEEP) {
            mood = Mood.IDLE; bubble = null
        }
        if (bubble != null && System.currentTimeMillis() > bubbleUntil) bubble = null
    }

    private fun showBubble(text: String, m: Mood) {
        bubble = text
        mood = m
        bubbleUntil = System.currentTimeMillis() + 6000
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val cx = w / 2
        val bob = sin(t) * 10f
        val cy = h * 0.58f + bob

        // 地面影子
        val sw = w * 0.34f
        canvas.drawOval(RectF(cx - sw, h * 0.82f, cx + sw, h * 0.88f), shadowPaint)

        // 触角
        val rx = w * 0.20f
        val ry = h * 0.16f
        canvas.drawLine(cx - rx * 0.45f, cy - ry, cx - rx * 1.15f, cy - ry * 2.1f, antennaPaint)
        canvas.drawLine(cx + rx * 0.45f, cy - ry, cx + rx * 1.15f, cy - ry * 2.1f, antennaPaint)
        canvas.drawCircle(cx - rx * 1.15f, cy - ry * 2.1f, 7f, bodyDark)
        canvas.drawCircle(cx + rx * 1.15f, cy - ry * 2.1f, 7f, bodyDark)

        // 身体
        val squish = if (mood == Mood.SLEEP) 0.90f else 1f
        val bodyR = RectF(cx - rx * squish, cy - ry / squish, cx + rx * squish, cy + ry / squish)
        canvas.drawOval(bodyR, bodyPaint)

        // 高光
        canvas.save()
        val bodyPath = Path().apply { addOval(bodyR, Path.Direction.CW) }
        canvas.clipPath(bodyPath)
        canvas.drawOval(
            RectF(cx - rx * 0.65f, cy - ry * 0.9f, cx + rx * 0.10f, cy - ry * 0.20f),
            hlPaint
        )
        canvas.restore()

        // 眼睛
        val eyeY = cy - ry * 0.10f
        val eyeR = w * 0.045f
        val eyeDx = rx * 0.34f
        when (mood) {
            Mood.SLEEP -> {
                canvas.drawLine(cx - eyeDx - eyeR, eyeY, cx - eyeDx + eyeR, eyeY, mouthPaint)
                canvas.drawLine(cx + eyeDx - eyeR, eyeY, cx + eyeDx + eyeR, eyeY, mouthPaint)
            }
            Mood.HAPPY -> {
                val p1 = Path()
                p1.addArc(RectF(cx - eyeDx - eyeR, eyeY - eyeR, cx - eyeDx + eyeR, eyeY + eyeR), 180f, 180f)
                canvas.drawPath(p1, eyePaint)
                val p2 = Path()
                p2.addArc(RectF(cx + eyeDx - eyeR, eyeY - eyeR, cx + eyeDx + eyeR, eyeY + eyeR), 180f, 180f)
                canvas.drawPath(p2, eyePaint)
            }
            else -> {
                canvas.drawCircle(cx - eyeDx, eyeY, eyeR, eyePaint)
                canvas.drawCircle(cx + eyeDx, eyeY, eyeR, eyePaint)
                canvas.drawCircle(cx - eyeDx - eyeR * 0.3f, eyeY - eyeR * 0.35f, eyeR * 0.32f, glintPaint)
                canvas.drawCircle(cx + eyeDx - eyeR * 0.3f, eyeY - eyeR * 0.35f, eyeR * 0.32f, glintPaint)
            }
        }

        // 嘴
        val my = cy + ry * 0.42f
        when (mood) {
            Mood.HAPPY, Mood.CHARGING -> {
                val p = Path()
                p.addArc(RectF(cx - rx * 0.25f, my - ry * 0.18f, cx + rx * 0.25f, my + ry * 0.18f), 0f, 180f)
                canvas.drawPath(p, mouthPaint)
            }
            Mood.HUNGRY -> {
                val p = Path()
                p.addArc(RectF(cx - rx * 0.20f, my - ry * 0.12f, cx + rx * 0.20f, my + ry * 0.20f), 180f, 180f)
                canvas.drawPath(p, mouthPaint)
            }
            else -> {
                canvas.drawLine(cx - rx * 0.12f, my, cx + rx * 0.12f, my, mouthPaint)
            }
        }

        // 气泡
        bubble?.let {
            val tw = textPaint.measureText(it)
            val bw = tw + 56f
            val bh = 78f
            val bx = cx - bw / 2
            val by = cy - ry - bh - 40f
            val r = 30f
            canvas.drawRoundRect(RectF(bx, by, bx + bw, by + bh), r, r, bubblePaint)
            val tail = Path()
            tail.moveTo(cx - 16f, by + bh - 2)
            tail.lineTo(cx, by + bh + 22f)
            tail.lineTo(cx + 16f, by + bh - 2)
            tail.close()
            canvas.drawPath(tail, bubblePaint)
            canvas.drawText(it, bx + 28f, by + bh * 0.62f, textPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                dragging = false
                downRawX = e.rawX; downRawY = e.rawY
                lastRawX = e.rawX; lastRawY = e.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(e.rawX - downRawX) > 14 || abs(e.rawY - downRawY) > 14) dragging = true
                if (dragging) {
                    svc.moveBy(e.rawX - lastRawX, e.rawY - lastRawY)
                    lastRawX = e.rawX; lastRawY = e.rawY
                }
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging) showBubble("嘿嘿~", Mood.HAPPY)
            }
        }
        return true
    }
}
