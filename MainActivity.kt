package com.example.deskmode

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * DeskMode: واجهة سطح مكتب (Launcher) بأسلوب وضع الحاسوب.
 * - سطح مكتب بالأيقونات
 * - شريط مهام (قائمة ابدأ + تطبيقات مثبتة + المفتوحة + ساعة وبطارية)
 * - فتح التطبيقات في نوافذ عائمة (يتطلب تفعيل Freeform windows)
 */
class MainActivity : AppCompatActivity() {

    private var allApps: List<AppInfo> = emptyList()
    private val byPackage = HashMap<String, AppInfo>()
    private val openApps = LinkedHashSet<String>()
    private var windowCounter = 0

    private lateinit var startMenu: View
    private lateinit var taskbarApps: LinearLayout
    private lateinit var battery: TextView
    private lateinit var menuAdapter: AppAdapter
    private lateinit var desktopAdapter: AppAdapter

    private val prefs by lazy { getSharedPreferences("deskmode", Context.MODE_PRIVATE) }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (scale > 0) battery.text = "${level * 100 / scale}%"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        startMenu = findViewById(R.id.startMenu)
        taskbarApps = findViewById(R.id.taskbarApps)
        battery = findViewById(R.id.battery)

        loadApps()

        desktopAdapter = AppAdapter(allApps, { launch(it, true) }, { showOptions(it) })
        findViewById<RecyclerView>(R.id.desktop).apply {
            // أيقونات تترتب عمودياً ثم تنتقل لعمود جديد كسطح المكتب
            layoutManager = GridLayoutManager(
                this@MainActivity, 5, GridLayoutManager.HORIZONTAL, false
            )
            adapter = desktopAdapter
        }

        menuAdapter = AppAdapter(allApps, {
            hideMenu()
            launch(it, true)
        }, { showOptions(it) })
        findViewById<RecyclerView>(R.id.menuList).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 4)
            adapter = menuAdapter
        }

        findViewById<EditText>(R.id.search).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty().lowercase()
                menuAdapter.submit(
                    if (q.isEmpty()) allApps
                    else allApps.filter { it.label.lowercase().contains(q) }
                )
            }
        })

        findViewById<ImageView>(R.id.startButton).setOnClickListener { toggleMenu() }
        findViewById<View>(R.id.desktop).setOnClickListener { hideMenu() }
        findViewById<ImageView>(R.id.wifiButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        findViewById<ImageView>(R.id.settingsButton).setOnClickListener {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        findViewById<ImageView>(R.id.settingsButton).setOnLongClickListener {
            Toast.makeText(this, R.string.freeform_hint, Toast.LENGTH_LONG).show()
            true
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // الـ Launcher لا يُغلق؛ فقط نُخفي القائمة
                hideMenu()
            }
        })

        refreshTaskbar()
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(
            this, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // الضغط على زر Home يعيدنا لسطح المكتب
        hideMenu()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            // إخفاء شريط الحالة ليبدو كسطح مكتب كامل
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.statusBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    // ---------------------------------------------------------------- التطبيقات

    private fun loadApps() {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        allApps = packageManager.queryIntentActivities(query, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map {
                AppInfo(
                    it.loadLabel(packageManager).toString(),
                    it.activityInfo.packageName,
                    it.loadIcon(packageManager)
                )
            }
            .distinctBy { it.packageName }
            .sortedBy { it.label.lowercase() }
        byPackage.clear()
        allApps.forEach { byPackage[it.packageName] = it }
    }

    private fun pinned(): List<String> {
        val saved = prefs.getString("pinned", null)
        if (saved == null) {
            // افتراضياً: أول 5 تطبيقات
            return allApps.take(5).map { it.packageName }
        }
        return saved.split(",").filter { it.isNotBlank() }
    }

    private fun togglePin(app: AppInfo) {
        val list = pinned().toMutableList()
        if (!list.remove(app.packageName)) list.add(app.packageName)
        prefs.edit().putString("pinned", list.joinToString(",")).apply()
        refreshTaskbar()
    }

    // ---------------------------------------------------------------- التشغيل

    private fun launch(app: AppInfo, windowed: Boolean) {
        val intent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent == null) {
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        val options = ActivityOptions.makeBasic()
        // فتح التطبيق على نفس الشاشة التي يعمل عليها سطح المكتب (الهاتف أو الشاشة الخارجية)
        @Suppress("DEPRECATION")
        options.setLaunchDisplayId(windowManager.defaultDisplay.displayId)
        if (windowed) options.setLaunchBounds(nextWindowBounds())

        try {
            startActivity(intent, options.toBundle())
            openApps.add(app.packageName)
            refreshTaskbar()
        } catch (e: Exception) {
            Toast.makeText(this, R.string.launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** مستطيل نافذة جديد، يُزاح قليلاً عن السابق ليظهر التراص (Cascade). */
    private fun nextWindowBounds(): Rect {
        val dm = resources.displayMetrics
        val w = (dm.widthPixels * 0.62f).toInt()
        val h = (dm.heightPixels * 0.72f).toInt()
        val step = (40 * dm.density).toInt()
        val offset = (windowCounter++ % 6) * step
        val left = (dm.widthPixels * 0.08f).toInt() + offset
        val top = (dm.heightPixels * 0.05f).toInt() + offset
        return Rect(left, top, minOf(left + w, dm.widthPixels), minOf(top + h, dm.heightPixels))
    }

    private fun showOptions(app: AppInfo) {
        val isPinned = pinned().contains(app.packageName)
        val items = arrayOf(
            getString(R.string.open_window),
            getString(R.string.open_full),
            getString(if (isPinned) R.string.unpin else R.string.pin),
            getString(R.string.app_info)
        )
        AlertDialog.Builder(this)
            .setTitle(app.label)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> launch(app, true)
                    1 -> launch(app, false)
                    2 -> togglePin(app)
                    3 -> startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${app.packageName}")
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            .show()
    }

    // ---------------------------------------------------------------- الواجهة

    private fun toggleMenu() {
        if (startMenu.visibility == View.VISIBLE) hideMenu() else {
            startMenu.visibility = View.VISIBLE
            findViewById<EditText>(R.id.search).apply {
                setText("")
                clearFocus()
            }
        }
    }

    private fun hideMenu() {
        startMenu.visibility = View.GONE
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** يبني أيقونات شريط المهام: المثبتة + المفتوحة حالياً مع خط تحتها. */
    private fun refreshTaskbar() {
        taskbarApps.removeAllViews()
        val shown = (pinned() + openApps).distinct()
        for (pkg in shown) {
            val app = byPackage[pkg] ?: continue
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = ContextCompat.getDrawable(context, R.drawable.bg_ripple)
                layoutParams = LinearLayout.LayoutParams(dp(46), dp(46)).apply { marginEnd = dp(4) }
                setPadding(dp(5), dp(5), dp(5), dp(2))
                setOnClickListener { launch(app, true) }
                setOnLongClickListener { showOptions(app); true }
            }
            cell.addView(ImageView(this).apply {
                setImageDrawable(app.icon)
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30))
            })
            cell.addView(View(this).apply {
                setBackgroundColor(
                    if (openApps.contains(pkg)) ContextCompat.getColor(context, R.color.accent)
                    else 0
                )
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(3)).apply { topMargin = dp(2) }
            })
            taskbarApps.addView(cell)
        }
    }
}
