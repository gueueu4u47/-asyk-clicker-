package kz.asyk.clicker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import kotlin.concurrent.thread

/**
 * Глаза бота: захват экрана через MediaProjection.
 *
 * Каждый кадр уменьшается, передаётся в Autopilot, и если нужен прыжок —
 * вызывается ClickerService.click(). Работает, пока ваше приложение свёрнуто.
 */
class CaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val CHANNEL = "asyk_capture"
        private const val TAG = "AsykCapture"
        const val DOWNSCALE = 4          // анализируем кадр 1/4 — быстрее и точности хватает

        @Volatile var fps = 0.0
        @Volatile var lastPlayerY = -1f
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val autopilot = Autopilot()
    @Volatile private var alive = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, notification())

        val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: return START_NOT_STICKY
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA) ?: return START_NOT_STICKY
        val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mgr.getMediaProjection(code, data)

        val metrics = DisplayMetrics().also {
            @Suppress("DEPRECATION")
            (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager)
                .defaultDisplay.getRealMetrics(it)
        }
        val w = metrics.widthPixels / DOWNSCALE
        val h = metrics.heightPixels / DOWNSCALE

        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        display = projection?.createVirtualDisplay(
            "asyk-capture", w, h, metrics.densityDpi / DOWNSCALE,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, null
        )

        thread(name = "asyk-loop") { loop(w, h, metrics.widthPixels, metrics.heightPixels) }
        return START_STICKY
    }

    private fun loop(w: Int, h: Int, fullW: Int, fullH: Int) {
        var frames = 0
        var t0 = System.nanoTime()
        val pixels = IntArray(w * h)

        while (alive) {
            val image = reader?.acquireLatestImage()
            if (image == null) { Thread.sleep(4); continue }
            try {
                val plane = image.planes[0]
                val rowStride = plane.rowStride / 4
                val bmp = Bitmap.createBitmap(rowStride, h, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                bmp.recycle()

                val decision = autopilot.update(pixels, w, h)
                lastPlayerY = decision.playerY
                if (decision.shouldClick) {
                    ClickerService.instance?.click(fullW / 2f, fullH / 2f, 24L)
                }
            } catch (e: Exception) {
                Log.w(TAG, "frame error", e)
            } finally {
                image.close()
            }

            frames++
            val dt = (System.nanoTime() - t0) / 1e9
            if (dt >= 1.0) { fps = frames / dt; frames = 0; t0 = System.nanoTime() }
        }
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Asyk Capture", NotificationManager.IMPORTANCE_LOW)
            )
        }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Asyk Clicker активен")
            .setContentText("Анализ экрана и автоклики выполняются")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        alive = false
        display?.release()
        reader?.close()
        projection?.stop()
        super.onDestroy()
    }
}
