package kz.asyk.clicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlin.random.Random

/**
 * Системный фоновый кликер на Accessibility Service.
 *
 * Это единственный легальный способ кликать поверх чужих приложений без root:
 * пользователь явно включает сервис в Настройки -> Спецвозможности.
 * Сервис работает, когда ваше приложение свёрнуто — это и есть "фон".
 *
 * Важно: Android не даёт Accessibility-сервису читать пиксели экрана.
 * Для анализа картинки нужен MediaProjection (см. CaptureService в README).
 * Здесь — точные клики по координатам и телеметрия для тестов.
 */
class ClickerService : AccessibilityService() {

    companion object {
        const val ACTION_START = "kz.asyk.clicker.START"
        const val ACTION_STOP = "kz.asyk.clicker.STOP"
        const val EXTRA_X = "x"
        const val EXTRA_Y = "y"
        const val EXTRA_INTERVAL = "intervalMs"
        const val EXTRA_JITTER = "jitterMs"
        const val EXTRA_DURATION = "holdMs"
        private const val TAG = "AsykClicker"

        @Volatile var instance: ClickerService? = null
        @Volatile var running = false
        @Volatile var clicksSent = 0L
        @Volatile var clicksFailed = 0L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var x = 540f
    private var y = 1200f
    private var interval = 120L
    private var jitter = 0L
    private var holdMs = 30L

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            click(x, y, holdMs)
            val next = interval + if (jitter > 0) Random.nextLong(-jitter, jitter + 1) else 0L
            handler.postDelayed(this, next.coerceAtLeast(16L))
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "service connected")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                x = intent.getFloatExtra(EXTRA_X, x)
                y = intent.getFloatExtra(EXTRA_Y, y)
                interval = intent.getLongExtra(EXTRA_INTERVAL, interval)
                jitter = intent.getLongExtra(EXTRA_JITTER, 0L)
                holdMs = intent.getLongExtra(EXTRA_DURATION, holdMs)
                start()
            }
            ACTION_STOP -> stop()
        }
        return START_STICKY
    }

    fun start() {
        if (running) return
        running = true
        clicksSent = 0
        clicksFailed = 0
        handler.post(tick)
        Log.i(TAG, "clicker started at ($x,$y) every ${interval}ms")
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        Log.i(TAG, "clicker stopped, sent=$clicksSent failed=$clicksFailed")
    }

    /** Одиночный системный тап поверх любого приложения. */
    fun click(px: Float, py: Float, hold: Long = 30L): Boolean {
        val path = Path().apply { moveTo(px, py) }
        val stroke = GestureDescription.StrokeDescription(path, 0, hold.coerceAtLeast(1L))
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val ok = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { clicksSent++ }
            override fun onCancelled(d: GestureDescription?) { clicksFailed++ }
        }, null)
        if (!ok) clicksFailed++
        return ok
    }

    /** Свайп — нужен для тестов жестовых игр. */
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 150L): Boolean {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        return dispatchGesture(gesture, null, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* не используется */ }

    override fun onInterrupt() { stop() }

    override fun onDestroy() {
        stop()
        instance = null
        super.onDestroy()
    }
}
