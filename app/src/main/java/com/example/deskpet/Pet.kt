package com.example.deskpet

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

// ---------------- Главный экран ----------------
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "DeskRex\nДинозавр гуляет по экрану поверх других приложений. Нажми на него, чтобы найти файл, или перетащи."
            textSize = 18f
            gravity = Gravity.CENTER
        })
        root.addView(Button(this).apply {
            text = "Запустить Рекса"
            setOnClickListener { startPet() }
        })
        root.addView(Button(this).apply {
            text = "Убрать Рекса"
            setOnClickListener { stopService(Intent(this@MainActivity, PetService::class.java)) }
        })
        setContentView(root)
    }

    private fun startPet() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Разреши показ поверх других приложений и вернись", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        ContextCompat.startForegroundService(this, Intent(this, PetService::class.java))
    }
}

// ---------------- Поиск файлов ----------------
class SearchActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private val files = ArrayList<File>()
    private val labels = ArrayList<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var input: EditText
    private lateinit var info: TextView
    private var searchId = 0
    private var asked = false
    private val root: File = Environment.getExternalStorageDirectory()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }
        input = EditText(this).apply {
            hint = "Название файла"
            textSize = 18f
            isSingleLine = true
        }
        info = TextView(this).apply { setPadding(0, pad / 2, 0, pad / 2) }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        val list = ListView(this).apply {
            this.adapter = this@SearchActivity.adapter
            setOnItemClickListener { _, _, pos, _ -> openFile(files[pos]) }
        }
        box.addView(input)
        box.addView(info)
        box.addView(list)
        setContentView(box)

        var pending: Runnable? = null
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                pending?.let { ui.removeCallbacks(it) }
                val q = s.toString().trim()
                pending = Runnable { search(q) }
                ui.postDelayed(pending!!, 400)
            }
        })
    }

    override fun onResume() {
        super.onResume()
        if (hasAccess()) {
            info.text = "Введи название файла"
        } else {
            info.text = "Разреши доступ ко всем файлам и вернись сюда"
            if (!asked) { asked = true; askAccess() }
        }
    }

    private fun hasAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    private fun askAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            Toast.makeText(this, "Включи доступ ко всем файлам для DeskRex", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 2)
        }
    }

    private fun search(query: String) {
        val id = ++searchId
        if (query.length < 2 || !hasAccess()) {
            show(emptyList(), id, query.isNotEmpty())
            return
        }
        info.text = "Ищу…"
        Thread {
            val found = ArrayList<File>()
            val q = query.lowercase()
            val stack = ArrayDeque<File>()
            stack.add(root)
            while (stack.isNotEmpty() && found.size < 100 && id == searchId) {
                val dir = stack.removeLast()
                val children = dir.listFiles() ?: continue
                for (f in children) {
                    if (f.isDirectory) {
                        if (!f.name.startsWith(".")) stack.add(f)
                    } else if (f.name.lowercase().contains(q)) {
                        found.add(f)
                    }
                }
            }
            found.sortByDescending { it.lastModified() }
            runOnUiThread { show(found, id, true) }
        }.start()
    }

    private fun show(found: List<File>, id: Int, searched: Boolean) {
        if (id != searchId) return
        files.clear(); files.addAll(found)
        labels.clear()
        for (f in found) {
            val where = f.parentFile?.path?.removePrefix(root.path) ?: ""
            labels.add(f.name + "\n" + (if (where.isEmpty()) "/" else where))
        }
        adapter.notifyDataSetChanged()
        info.text = when {
            found.isNotEmpty() -> "Найдено: ${found.size}"
            searched && input.text.trim().length >= 2 -> "Ничего не найдено"
            else -> "Введи название файла"
        }
    }

    private fun openFile(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "com.example.deskpet.fileprovider", f)
            val mime = MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть файл", Toast.LENGTH_LONG).show()
        }
    }
}

// ---------------- Рекс ----------------
enum class PetState { WALK, IDLE, SLEEP, HAPPY }

class PetView(context: Context) : View(context) {
    var state = PetState.IDLE
    var facing = 1

    // один пиксель спрайта = 5dp, сетка 20 x 22
    private val p = (5 * resources.displayMetrics.density).toInt()
    val size = 20 * p
    val pixelHeight = 22 * p

    private var t = 0f
    private var oy = 0
    private val paint = Paint().apply { isAntiAlias = false }

    private val MAIN = 0xFF5BAE6A.toInt()
    private val SHADE = 0xFF3E8A4F.toInt()
    private val DK = 0xFF2F6B3D.toInt()
    private val BELLY = 0xFFD2EBB8.toInt()
    private val EYE = 0xFF1B2A1F.toInt()
    private val WHITE = 0xFFFFFFFF.toInt()
    private val PINK = 0xFFE5566D.toInt()
    private val ZZ = 0xFF8D9BE0.toInt()

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(size, pixelHeight)

    fun step() { t += 0.25f; invalidate() }

    private fun r(c: Canvas, x: Int, y: Int, w: Int, h: Int, col: Int) {
        val xx = if (facing < 0) 20 - x - w else x
        paint.color = col
        val top = (y + 6 + oy) * p
        c.drawRect((xx * p).toFloat(), top.toFloat(), ((xx + w) * p).toFloat(), (top + h * p).toFloat(), paint)
    }

    override fun onDraw(c: Canvas) {
        val up = state == PetState.HAPPY
        val walk = state == PetState.WALK
        val sleep = state == PetState.SLEEP
        val fr = (t / 1.9f).toInt() % 2
        val bob = when {
            walk -> fr
            sleep -> (t / 5f).toInt() % 2
            up -> -(abs(sin(t * 0.8f)) * 3).roundToInt()
            else -> 0
        }

        // ноги (стоят на земле, прыгают только вместе с телом)
        oy = if (up) bob else 0
        val aH = if (walk && fr == 1) 2 else 3
        val bH = if (walk && fr == 0) 2 else 3
        r(c, 6, 13, 2, aH, DK); r(c, 8, 12 + aH, 1, 1, DK)
        r(c, 10, 13, 2, bH, DK); r(c, 12, 12 + bH, 1, 1, DK)

        oy = bob
        // хвост
        val tip = if (walk) fr else 0
        r(c, 4, 7, 2, 3, MAIN); r(c, 2, 8, 2, 2, MAIN); r(c, 0, 9 + tip, 2, 1, SHADE)
        // шипы
        r(c, 6, 5, 1, 1, DK); r(c, 8, 5, 1, 1, DK); r(c, 10, 5, 1, 1, DK)
        // тело и пузо
        r(c, 5, 6, 8, 7, MAIN)
        r(c, 5, 12, 4, 1, SHADE)
        r(c, 9, 9, 4, 4, BELLY)
        // шея и голова
        r(c, 11, 4, 3, 3, MAIN)
        r(c, 11, 2, 8, 5, MAIN)
        r(c, 12, 1, 6, 1, MAIN)
        r(c, 18, 3, 1, 1, SHADE)
        // пасть
        if (up) {
            r(c, 13, 7, 4, 1, PINK)
            r(c, 12, 8, 6, 1, SHADE)
        } else {
            r(c, 12, 7, 6, 1, SHADE)
            r(c, 15, 7, 1, 1, WHITE); r(c, 17, 7, 1, 1, WHITE)
        }
        // глаз
        when {
            up -> { r(c, 14, 4, 1, 1, EYE); r(c, 15, 3, 1, 1, EYE); r(c, 16, 4, 1, 1, EYE) }
            sleep || (t % 24f) < 1.5f -> r(c, 14, 4, 2, 1, EYE)
            else -> { r(c, 14, 3, 2, 2, EYE); r(c, 14, 3, 1, 1, WHITE) }
        }
        // лапка
        if (up) { r(c, 13, 8, 2, 1, DK); r(c, 14, 7, 1, 1, DK) }
        else r(c, 13, 9, 2, 1, DK)

        // «Z» во сне
        if (sleep) {
            val zy = -2 - ((t % 12f) / 4f).toInt()
            r(c, 17, zy, 3, 1, ZZ); r(c, 18, zy + 1, 1, 1, ZZ); r(c, 17, zy + 2, 3, 1, ZZ)
        }
        // сердечко
        if (up) {
            r(c, 14, -3, 1, 1, PINK); r(c, 16, -3, 1, 1, PINK)
            r(c, 13, -2, 5, 1, PINK); r(c, 14, -1, 3, 1, PINK); r(c, 15, 0, 1, 1, PINK)
        }
    }
}

class PetService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var pet: PetView
    private lateinit var lp: WindowManager.LayoutParams
    private val handler = Handler(Looper.getMainLooper())
    private var stateUntil = 0L
    private var dragging = false

    private val loop = object : Runnable {
        override fun run() { tick(); handler.postDelayed(this, 33) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        pet = PetView(this)
        val dm = resources.displayMetrics
        lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dm.widthPixels / 3
            y = dm.heightPixels - pet.pixelHeight - (160 * dm.density).toInt()
        }
        pet.setOnTouchListener(touch())
        wm.addView(pet, lp)
        handler.post(loop)
    }

    private fun touch(): View.OnTouchListener {
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        return View.OnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    sx = e.rawX; sy = e.rawY; ox = lp.x; oy = lp.y
                    moved = false; dragging = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX - sx) > 12 || abs(e.rawY - sy) > 12) moved = true
                    if (moved) {
                        lp.x = ox + (e.rawX - sx).toInt()
                        lp.y = oy + (e.rawY - sy).toInt()
                        wm.updateViewLayout(pet, lp)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    dragging = false
                    if (moved) {
                        pet.state = PetState.IDLE
                        stateUntil = SystemClock.uptimeMillis() + 800
                    } else {
                        pet.state = PetState.HAPPY
                        stateUntil = SystemClock.uptimeMillis() + 1600
                        startActivity(
                            Intent(this@PetService, SearchActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
            true
        }
    }

    private fun tick() {
        val now = SystemClock.uptimeMillis()
        if (!dragging && now >= stateUntil) {
            when (Random.nextInt(10)) {
                in 0..5 -> { pet.state = PetState.WALK; stateUntil = now + Random.nextLong(3000, 8000); pet.facing = if (Random.nextBoolean()) 1 else -1 }
                in 6..7 -> { pet.state = PetState.IDLE; stateUntil = now + Random.nextLong(2000, 4000) }
                else -> { pet.state = PetState.SLEEP; stateUntil = now + Random.nextLong(6000, 12000) }
            }
        }
        if (!dragging && pet.state == PetState.WALK) {
            val dm = resources.displayMetrics
            lp.x += pet.facing * (dm.density * 2.5f).toInt()
            if (lp.x < 0) { lp.x = 0; pet.facing = 1 }
            if (lp.x > dm.widthPixels - pet.size) { lp.x = dm.widthPixels - pet.size; pet.facing = -1 }
            wm.updateViewLayout(pet, lp)
        }
        pet.step()
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("pet", "DeskRex", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, PetService::class.java).setAction("STOP"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "pet")
            .setSmallIcon(android.R.drawable.star_on)
            .setContentTitle("Рекс гуляет")
            .addAction(Notification.Action.Builder(null, "Убрать", stop).build())
            .build()
        ServiceCompat.startForeground(
            this, 1, n,
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopSelf(); return START_NOT_STICKY }
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(loop)
        if (::pet.isInitialized) wm.removeView(pet)
        super.onDestroy()
    }
}
