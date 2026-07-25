package kz.asyk.clicker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Панель тестировщика.
 *
 * Два режима:
 *  • Таймер — просто клики по координатам с интервалом (ничего не видит).
 *  • Автопилот — захват экрана + анализ кадра + клики по ситуации.
 * Оба работают поверх любого другого приложения, пока это окно свёрнуто.
 */
class MainActivity : AppCompatActivity() {

    private companion object { const val REQ_PROJECTION = 4321 }

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var stats: TextView

    private val refresh = object : Runnable {
        override fun run() {
            stats.text = buildString {
                append(if (ClickerService.instance == null)
                    "Сервис выключен — включите в Спецвозможностях\n"
                else "Сервис активен\n")
                append("Состояние: ").append(if (ClickerService.running) "кликает" else "пауза")
                append("\nУспешных кликов: ").append(ClickerService.clicksSent)
                append("\nОшибок жеста: ").append(ClickerService.clicksFailed)
                append("\nЗахват экрана: ").append(String.format("%.1f fps", CaptureService.fps))
                append("\nY игрока: ").append(CaptureService.lastPlayerY.toInt())
            }
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        stats = findViewById(R.id.stats)
        val xField = findViewById<EditText>(R.id.x)
        val yField = findViewById<EditText>(R.id.y)
        val intervalField = findViewById<EditText>(R.id.interval)
        val jitterField = findViewById<EditText>(R.id.jitter)

        findViewById<Button>(R.id.openSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        // Режим 1: клики по таймеру
        findViewById<Button>(R.id.start).setOnClickListener {
            if (ClickerService.instance == null) { toastNoService(); return@setOnClickListener }
            startService(Intent(this, ClickerService::class.java).apply {
                action = ClickerService.ACTION_START
                putExtra(ClickerService.EXTRA_X, xField.text.toString().toFloatOrNull() ?: 540f)
                putExtra(ClickerService.EXTRA_Y, yField.text.toString().toFloatOrNull() ?: 1200f)
                putExtra(ClickerService.EXTRA_INTERVAL, intervalField.text.toString().toLongOrNull() ?: 120L)
                putExtra(ClickerService.EXTRA_JITTER, jitterField.text.toString().toLongOrNull() ?: 0L)
            })
            moveTaskToBack(true)
        }

        // Режим 2: автопилот со зрением
        findViewById<Button>(R.id.autopilot).setOnClickListener {
            if (ClickerService.instance == null) { toastNoService(); return@setOnClickListener }
            val mgr = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(mgr.createScreenCaptureIntent(), REQ_PROJECTION)
        }

        findViewById<Button>(R.id.stop).setOnClickListener {
            ClickerService.instance?.stop()
            stopService(Intent(this, CaptureService::class.java))
        }
    }

    @Deprecated("простота важнее для тестового билда")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            val svc = Intent(this, CaptureService::class.java).apply {
                putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(CaptureService.EXTRA_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc)
            else startService(svc)
            moveTaskToBack(true)   // сворачиваемся, бот играет в чужом приложении
        }
    }

    private fun toastNoService() {
        android.widget.Toast.makeText(
            this, "Сначала включите сервис в Спецвозможностях", android.widget.Toast.LENGTH_LONG
        ).show()
    }

    override fun onResume() { super.onResume(); ui.post(refresh) }
    override fun onPause() { super.onPause(); ui.removeCallbacks(refresh) }
}
