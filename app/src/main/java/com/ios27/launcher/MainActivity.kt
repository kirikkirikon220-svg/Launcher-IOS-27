package com.ios27.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.BatteryManager
import android.os.Bundle
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.animation.PathInterpolator
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// IOS27_CONTROL_CENTER_ANIMATION_V15
// Основано на покадровом анализе референсной записи.
// Статусная правая группа фиксирована.
// Время только fade.
// Control Center materialize идёт каскадом.
class MainActivity : Activity() {

    private lateinit var launcherView: LauncherView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // LAUNCHER_CRASH_HANDLER_V1
        // Сохраняем необработанные исключения прямо на Android,
        // чтобы можно было определить точную причину краша.
        val previousCrashHandler =
            Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->

            try {
                val time =
                    java.text.SimpleDateFormat(
                        "yyyy-MM-dd HH:mm:ss",
                        java.util.Locale.getDefault()
                    ).format(java.util.Date())

                val report = buildString {
                    appendLine("iOS 27 Launcher crash report")
                    appendLine("Time: $time")
                    appendLine("Thread: ${thread.name}")
                    appendLine("Android: ${android.os.Build.VERSION.RELEASE}")
                    appendLine("SDK: ${android.os.Build.VERSION.SDK_INT}")
                    appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    appendLine()
                    appendLine("Exception:")
                    throwable.printStackTrace(
                        java.io.PrintWriter(
                            java.io.StringWriter()
                        )
                    )

                    val sw = java.io.StringWriter()
                    throwable.printStackTrace(
                        java.io.PrintWriter(sw)
                    )
                    appendLine(sw.toString())
                }

                // 1. Надёжное место внутри внешнего хранилища приложения.
                try {
                    val dir =
                        getExternalFilesDir(
                            android.os.Environment.DIRECTORY_DOCUMENTS
                        )

                    if (dir != null) {
                        dir.mkdirs()

                        java.io.File(
                            dir,
                            "launcher_crash.txt"
                        ).writeText(
                            report,
                            Charsets.UTF_8
                        )
                    }
                } catch (_: Throwable) {
                }

                // 2. Копия непосредственно в "Загрузки"
                // через MediaStore — без запроса storage permission
                // на Android 10/11+.
                if (
                    android.os.Build.VERSION.SDK_INT >=
                    android.os.Build.VERSION_CODES.Q
                ) {
                    try {
                        val values =
                            android.content.ContentValues().apply {
                                put(
                                    android.provider.MediaStore.Downloads.DISPLAY_NAME,
                                    "launcher_crash_${System.currentTimeMillis()}.txt"
                                )
                                put(
                                    android.provider.MediaStore.Downloads.MIME_TYPE,
                                    "text/plain"
                                )
                                put(
                                    android.provider.MediaStore.Downloads.RELATIVE_PATH,
                                    android.os.Environment.DIRECTORY_DOWNLOADS
                                )
                            }

                        val resolver = contentResolver

                        val uri =
                            resolver.insert(
                                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                                values
                            )

                        if (uri != null) {
                            resolver.openOutputStream(uri)?.use {
                                it.write(
                                    report.toByteArray(
                                        Charsets.UTF_8
                                    )
                                )
                            }
                        }
                    } catch (_: Throwable) {
                    }
                }

                // Записываем также в Logcat, если система его примет.
                try {
                    android.util.Log.e(
                        "IOS27_LAUNCHER_CRASH",
                        report,
                        throwable
                    )
                } catch (_: Throwable) {
                }

            } catch (_: Throwable) {
                // Сам обработчик никогда не должен вызвать
                // второй необработанный краш.
            }

            // Передаём управление предыдущему обработчику,
            // если Android/система его предоставила.
            try {
                previousCrashHandler?.uncaughtException(
                    thread,
                    throwable
                )
            } catch (_: Throwable) {
                android.os.Process.killProcess(
                    android.os.Process.myPid()
                )
                kotlin.system.exitProcess(10)
            }
        }

        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        launcherView = LauncherView(this)

        setContentView(launcherView)

        // IOS27_NAVIGATION_SYSTEM_REPLACEMENT_V13
        configureIOS27NavigationBar()

        launcherView.startHomeAnimation()
    }

    // ========================================================
    // IOS27_NAVIGATION_SYSTEM_REPLACEMENT_V13
    // ========================================================
    //
    // В V12 приложение рисовало собственный Home Indicator,
    // но системная Android navigation bar всё ещё оставалась
    // активной.
    //
    // Поэтому пользователь видел старую системную анимацию.
    //
    // V13 полностью скрывает системную navigation bar.
    // После этого нижний индикатор рисует только Launcher.
    //

    private fun configureIOS27NavigationBar() {

        try {

            window.navigationBarColor =
                Color.TRANSPARENT

            if (
                android.os.Build.VERSION.SDK_INT >=
                android.os.Build.VERSION_CODES.Q
            ) {

                window.isNavigationBarContrastEnforced =
                    false

                window.navigationBarDividerColor =
                    Color.TRANSPARENT
            }

            val flags =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

            window.decorView.systemUiVisibility =
                flags

            if (
                android.os.Build.VERSION.SDK_INT >=
                android.os.Build.VERSION_CODES.R
            ) {

                window.setDecorFitsSystemWindows(
                    false
                )

                val controller =
                    window.insetsController

                if (controller != null) {

                    controller.hide(
                        android.view.WindowInsets.Type.navigationBars()
                    )

                    controller.systemBarsBehavior =
                        android.view.WindowInsetsController
                            .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }

        } catch (_: Throwable) {
        }
    }

    override fun onWindowFocusChanged(
        hasFocus: Boolean
    ) {

        super.onWindowFocusChanged(
            hasFocus
        )

        if (hasFocus) {

            configureIOS27NavigationBar()
        }
    }

    override fun onBackPressed() {

        if (
            ::launcherView.isInitialized &&
            (
                launcherView.isControlCenterVisibleV17()
            )
        ) {

            launcherView.closeControlCenterFromActivityV17()
            return
        }

        super.onBackPressed()
    }

    override fun onPause() {

        if (::launcherView.isInitialized) {
            launcherView.stopSystemIndicatorObserversV17()
        }

        super.onPause()
    }


    override fun onResume() {
        super.onResume()

        configureIOS27NavigationBar()

        if (::launcherView.isInitialized) {
            launcherView.reloadApps()
        }


        if (::launcherView.isInitialized) {
            launcherView.startSystemIndicatorObserversV17()
        }
    }

    override fun onDestroy() {

        if (::launcherView.isInitialized) {
            launcherView.stopSystemIndicatorObserversV17()
        }

        super.onDestroy()
    }

    private data class AppItem(
        val label: String,
        val packageName: String,
        val icon: Drawable
    )

    inner class LauncherView(
        context: Context
    ) : View(context) {

        private val pm = packageManager

        private val apps =
            mutableListOf<AppItem>()

        private val paint =
            Paint(Paint.ANTI_ALIAS_FLAG)

        private val textPaint =
            Paint(Paint.ANTI_ALIAS_FLAG)

        private val glassPaint =
            Paint(Paint.ANTI_ALIAS_FLAG)

        private val shadowPaint =
            Paint(Paint.ANTI_ALIAS_FLAG)

        private val bgPaint =
            Paint(Paint.ANTI_ALIAS_FLAG)

        // Cached wallpaper shaders.
        // They are recreated only when the View size changes,
        // not on every onDraw() call.
        private var wallpaperWidth = 0
        private var wallpaperHeight = 0

        private var wallpaperGradient: LinearGradient? = null
        private var wallpaperGlow1: RadialGradient? = null
        private var wallpaperGlow2: RadialGradient? = null
        private var wallpaperGlow3: RadialGradient? = null

        // Dock cache.
        // getDockApps() must not allocate lists on every frame.
        private var dockAppsCache: List<AppItem> = emptyList()

        private var downX = 0f
        private var downY = 0f

        private var page = 0

        private var pageOffset = 0f

        private var controlCenter = false

        private var controlCenterProgress = 0f

        private var ccAnimator: ValueAnimator? = null

        // ============================================================
        // IOS27 CONTROL CENTER V17 - GESTURE PHYSICS
        // ============================================================

        private var ccVelocityTracker: android.view.VelocityTracker? = null
        private var ccGestureStartProgress = 0f
        private var ccGestureLastY = 0f
        private var ccGestureDownTime = 0L

        // ============================================================
        // IOS27 CONTROL CENTER V17 - REAL SYSTEM STATE
        // ============================================================

        private var systemIndicatorsStarted = false

        private var wifiConnected = false
        private var wifiLevel = -1

        private var mobileConnected = false
        private var mobileLevel = -1
        private var mobileType = "—"

        private var bluetoothState = -1

        private var batteryPercent = 100
        private var batteryCharging = false

        private var ccNetworkCallback:
            android.net.ConnectivityManager.NetworkCallback? = null

        private var ccPhoneStateListener:
            android.telephony.PhoneStateListener? = null

        private val ccSystemReceiver =
            object : android.content.BroadcastReceiver() {

                override fun onReceive(
                    context: android.content.Context?,
                    intent: android.content.Intent?
                ) {
                    refreshSystemIndicatorsV17()
                    invalidate()
                }
            }

        // =================================================
        // IOS27_NAVIGATION_BAR_PHYSICS_V12
        // =================================================

        private var navigationBarSpringScale = 1f

        private var navigationBarAnimator: ValueAnimator? = null

        // IOS27_CONTROL_CENTER_INTERACTIVE_V7
        //
        // true  = progress напрямую следует за пальцем.
        // false = используется самостоятельная settle-анимация.
        private var controlCenterInteractive = false

        // =================================================
        // IOS27_STATUS_BAR_LIQUID_V8_FIXED
        // =================================================
        //
        // Интерактивная переходная зона:
        //
        // 0.00 -> 0.35
        // статусные индикаторы начинают двигаться
        //
        // 0.35 -> 0.55
        // появляется первый блок Control Center
        //
        // 0.45 -> 0.66
        // появляется media
        //
        // 0.54 -> 0.76
        // появляются слайдеры
        //
        // 0.64 -> 0.88
        // появляются нижние элементы
        //
        // Вся анимация напрямую связана
        // с controlCenterProgress.

        private fun ccReveal(
            value: Float,
            start: Float,
            end: Float
        ): Float {

            if (value <= start)
                return 0f

            if (value >= end)
                return 1f

            return (
                (value - start) /
                (end - start)
            ).coerceIn(0f, 1f)
        }

        private fun drawCCReveal(
            canvas: Canvas,
            progress: Float,
            travel: Float,
            block: () -> Unit
        ) {

            val p =
                progress.coerceIn(
                    0f,
                    1f
                )

            if (p <= 0f)
                return

            canvas.save()

            // IOS27_CONTROL_CENTER_ANIMATION_V10
            // Materialize не ограничивается alpha:
            // блок одновременно приезжает и слегка масштабируется.

            // IOS27_CONTROL_CENTER_BLOCK_SCALE_V11
            //
            // Основное масштабирование выполняет сама панель.
            // Отдельные блоки получают только очень лёгкий
            // materialize-scale, как в записи.

            val revealScale =
                0.985f +
                0.015f * p

            canvas.translate(
                width / 2f,
                travel * (1f - p)
            )

            canvas.scale(
                revealScale,
                revealScale,
                0f,
                0f
            )

            canvas.translate(
                -width / 2f,
                0f
            )

            val save =
                canvas.saveLayerAlpha(
                    0f,
                    0f,
                    width.toFloat(),
                    height.toFloat(),
                    (255f * p)
                        .toInt()
                        .coerceIn(0, 255)
                )

            block()

            canvas.restoreToCount(save)

            canvas.restore()
        }

        // IOS27_CONTROL_CENTER_STATUS_MORPH_V10
        // Левая группа dematerialize.
        // Правая группа остаётся видимой.
        // Control Center materialize идёт по progress пальца.

        // ============================================================
        // IOS27 CONTROL CENTER V17 - REAL SYSTEM INDICATORS
        // ============================================================

        fun startSystemIndicatorObserversV17() {

            if (systemIndicatorsStarted)
                return

            systemIndicatorsStarted = true

            refreshSystemIndicatorsV17()

            try {

                val filter =
                    android.content.IntentFilter().apply {

                        addAction(
                            android.content.Intent.ACTION_BATTERY_CHANGED
                        )

                        addAction(
                            android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION
                        )

                        addAction(
                            android.net.wifi.WifiManager.NETWORK_STATE_CHANGED_ACTION
                        )

                        addAction(
                            android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED
                        )

                    }

                if (
                    android.os.Build.VERSION.SDK_INT >=
                    android.os.Build.VERSION_CODES.TIRAMISU
                ) {

                    context.registerReceiver(
                        ccSystemReceiver,
                        filter,
                        android.content.Context.RECEIVER_NOT_EXPORTED
                    )

                } else {

                    @Suppress("DEPRECATION")
                    context.registerReceiver(
                        ccSystemReceiver,
                        filter
                    )
                }

            } catch (_: Throwable) {
            }

            try {

                val connectivity =
                    context.getSystemService(
                        android.content.Context.CONNECTIVITY_SERVICE
                    ) as? android.net.ConnectivityManager

                if (connectivity != null) {

                    val callback =
                        object :
                            android.net.ConnectivityManager.NetworkCallback() {

                            override fun onAvailable(
                                network: android.net.Network
                            ) {
                                refreshSystemIndicatorsV17()
                                postInvalidateOnAnimation()
                            }

                            override fun onLost(
                                network: android.net.Network
                            ) {
                                refreshSystemIndicatorsV17()
                                postInvalidateOnAnimation()
                            }

                            override fun onCapabilitiesChanged(
                                network: android.net.Network,
                                networkCapabilities:
                                    android.net.NetworkCapabilities
                            ) {
                                refreshSystemIndicatorsV17()
                                postInvalidateOnAnimation()
                            }

                        }

                    ccNetworkCallback = callback

                    connectivity.registerDefaultNetworkCallback(
                        callback
                    )
                }

            } catch (_: Throwable) {
                ccNetworkCallback = null
            }

            try {

                if (
                    android.os.Build.VERSION.SDK_INT >=
                    android.os.Build.VERSION_CODES.M &&
                    context.checkSelfPermission(
                        android.Manifest.permission.READ_PHONE_STATE
                    ) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {

                    val telephony =
                        context.getSystemService(
                            android.content.Context.TELEPHONY_SERVICE
                        ) as? android.telephony.TelephonyManager

                    if (telephony != null) {

                        val listener =
                            object :
                                android.telephony.PhoneStateListener() {

                                override fun onSignalStrengthsChanged(
                                    signalStrength:
                                        android.telephony.SignalStrength
                                ) {

                                    try {

                                        mobileLevel =
                                            signalStrength.level
                                                .coerceIn(0, 4)

                                    } catch (_: Throwable) {
                                        mobileLevel = -1
                                    }

                                    postInvalidateOnAnimation()
                                }

                            }

                        ccPhoneStateListener = listener

                        @Suppress("DEPRECATION")
                        telephony.listen(
                            listener,
                            android.telephony.PhoneStateListener
                                .LISTEN_SIGNAL_STRENGTHS
                        )
                    }
                }

            } catch (_: Throwable) {
                ccPhoneStateListener = null
            }
        }

        fun stopSystemIndicatorObserversV17() {

            if (!systemIndicatorsStarted)
                return

            systemIndicatorsStarted = false

            try {
                context.unregisterReceiver(
                    ccSystemReceiver
                )
            } catch (_: Throwable) {
            }

            try {

                val connectivity =
                    context.getSystemService(
                        android.content.Context.CONNECTIVITY_SERVICE
                    ) as? android.net.ConnectivityManager

                val callback =
                    ccNetworkCallback

                if (
                    connectivity != null &&
                    callback != null
                ) {
                    connectivity.unregisterNetworkCallback(
                        callback
                    )
                }

            } catch (_: Throwable) {
            }

            ccNetworkCallback = null

            try {

                val telephony =
                    context.getSystemService(
                        android.content.Context.TELEPHONY_SERVICE
                    ) as? android.telephony.TelephonyManager

                val listener =
                    ccPhoneStateListener

                if (
                    telephony != null &&
                    listener != null
                ) {

                    @Suppress("DEPRECATION")
                    telephony.listen(
                        listener,
                        android.telephony.PhoneStateListener.LISTEN_NONE
                    )
                }

            } catch (_: Throwable) {
            }

            ccPhoneStateListener = null
        }

        private fun refreshSystemIndicatorsV17() {

            // --------------------------------------------------------
            // BATTERY
            // --------------------------------------------------------

            try {

                val batteryIntent =
                    context.registerReceiver(
                        null,
                        android.content.IntentFilter(
                            android.content.Intent.ACTION_BATTERY_CHANGED
                        )
                    )

                if (batteryIntent != null) {

                    val level =
                        batteryIntent.getIntExtra(
                            android.os.BatteryManager.EXTRA_LEVEL,
                            -1
                        )

                    val scale =
                        batteryIntent.getIntExtra(
                            android.os.BatteryManager.EXTRA_SCALE,
                            100
                        )

                    batteryPercent =
                        if (
                            level >= 0 &&
                            scale > 0
                        ) {
                            (
                                level.toFloat() /
                                scale.toFloat() *
                                100f
                            )
                                .toInt()
                                .coerceIn(0, 100)
                        } else {
                            batteryPercent
                        }

                    val status =
                        batteryIntent.getIntExtra(
                            android.os.BatteryManager.EXTRA_STATUS,
                            -1
                        )

                    batteryCharging =
                        status ==
                        android.os.BatteryManager
                            .BATTERY_STATUS_CHARGING ||
                        status ==
                        android.os.BatteryManager
                            .BATTERY_STATUS_FULL
                }

            } catch (_: Throwable) {
            }

            // --------------------------------------------------------
            // NETWORK
            // --------------------------------------------------------

            wifiConnected = false
            mobileConnected = false

            try {

                val connectivity =
                    context.getSystemService(
                        android.content.Context.CONNECTIVITY_SERVICE
                    ) as? android.net.ConnectivityManager

                if (connectivity != null) {

                    for (
                        network
                        in connectivity.allNetworks
                    ) {

                        val capabilities =
                            connectivity.getNetworkCapabilities(
                                network
                            )
                                ?: continue

                        if (
                            capabilities.hasTransport(
                                android.net.NetworkCapabilities
                                    .TRANSPORT_WIFI
                            )
                        ) {

                            wifiConnected = true
                        }

                        if (
                            capabilities.hasTransport(
                                android.net.NetworkCapabilities
                                    .TRANSPORT_CELLULAR
                            )
                        ) {

                            mobileConnected = true
                        }
                    }
                }

            } catch (_: Throwable) {
            }

            // --------------------------------------------------------
            // WIFI RSSI
            // --------------------------------------------------------

            wifiLevel = -1

            try {

                val wifi =
                    context.getSystemService(
                        android.content.Context.WIFI_SERVICE
                    ) as? android.net.wifi.WifiManager

                if (
                    wifi != null &&
                    wifiConnected
                ) {

                    val rssi =
                        wifi.connectionInfo.rssi

                    if (
                        rssi !=
                        -127
                    ) {

                        wifiLevel =
                            when {
                                rssi >= -55 -> 4
                                rssi >= -67 -> 3
                                rssi >= -75 -> 2
                                rssi >= -85 -> 1
                                else -> 0
                            }
                    }
                }

            } catch (_: Throwable) {
            }

            // --------------------------------------------------------
            // MOBILE SIGNAL / NETWORK TYPE
            // --------------------------------------------------------

            if (!mobileConnected) {
                mobileLevel = -1
                mobileType = "—"
            }

            try {

                val telephony =
                    context.getSystemService(
                        android.content.Context.TELEPHONY_SERVICE
                    ) as? android.telephony.TelephonyManager

                if (telephony != null) {

                    try {

                        val strength =
                            telephony.signalStrength

                        if (strength != null) {

                            mobileLevel =
                                strength.level
                                    .coerceIn(0, 4)
                        }

                    } catch (_: Throwable) {
                        mobileLevel = -1
                    }

                    try {

                        mobileType =
                            when (
                                telephony.dataNetworkType
                            ) {

                                android.telephony.TelephonyManager
                                    .NETWORK_TYPE_NR ->
                                    "5G"

                                android.telephony.TelephonyManager
                                    .NETWORK_TYPE_LTE ->
                                    "4G"

                                android.telephony.TelephonyManager
                                    .NETWORK_TYPE_HSPAP,
                                android.telephony.TelephonyManager
                                    .NETWORK_TYPE_HSPA,
                                android.telephony.TelephonyManager
                                    .NETWORK_TYPE_HSDPA,
                                android.telephony.TelephonyManager
                                    .NETWORK_TYPE_HSUPA ->
                                    "3G"

                                android.telephony.TelephonyManager
                                    .NETWORK_TYPE_EDGE,
                                android.telephony.TelephonyManager
                                    .NETWORK_TYPE_GPRS ->
                                    "2G"

                                else ->
                                    if (mobileConnected) "LTE" else "—"
                            }

                    } catch (_: Throwable) {

                        mobileType =
                            if (mobileConnected)
                                "CELL"
                            else
                                "—"
                    }
                }

            } catch (_: Throwable) {
            }

            // --------------------------------------------------------
            // BLUETOOTH
            // --------------------------------------------------------

            bluetoothState = -1

            try {

                val adapter =
                    android.bluetooth.BluetoothAdapter
                        .getDefaultAdapter()

                if (adapter != null) {

                    if (
                        android.os.Build.VERSION.SDK_INT < 31 ||
                        context.checkSelfPermission(
                            android.Manifest.permission.BLUETOOTH_CONNECT
                        ) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
                    ) {

                        bluetoothState =
                            adapter.state
                    }
                }

            } catch (_: Throwable) {
                bluetoothState = -1
            }
        }

        private fun drawCCBarsV17(
            canvas: Canvas,
            cx: Float,
            baseline: Float,
            level: Int
        ) {

            bgPaint.style = Paint.Style.FILL
            bgPaint.color = Color.WHITE
            bgPaint.alpha = 235

            if (level < 0) {

                textPaint.color = Color.WHITE
                textPaint.alpha = 180
                textPaint.textSize = dp(12f)
                textPaint.textAlign = Paint.Align.CENTER
                textPaint.typeface = Typeface.DEFAULT_BOLD

                canvas.drawText(
                    "—",
                    cx,
                    baseline,
                    textPaint
                )

                return
            }

            val safeLevel =
                level.coerceIn(0, 4)

            for (i in 0 until 4) {

                val barHeight =
                    dp(
                        4f +
                        i * 3.5f
                    )

                val alpha =
                    if (i < safeLevel)
                        235
                    else
                        65

                bgPaint.alpha = alpha

                val left =
                    cx -
                    dp(12f) +
                    dp(i * 5f)

                canvas.drawRoundRect(
                    left,
                    baseline - barHeight,
                    left + dp(3.2f),
                    baseline,
                    dp(1.2f),
                    dp(1.2f),
                    bgPaint
                )
            }
        }

        private fun drawCCBatteryV17(
            canvas: Canvas,
            left: Float,
            top: Float,
            width: Float,
            height: Float
        ) {

            bgPaint.style = Paint.Style.STROKE
            bgPaint.strokeWidth = dp(1.4f)
            bgPaint.color = Color.WHITE
            bgPaint.alpha = 235

            canvas.drawRoundRect(
                left,
                top,
                left + width,
                top + height,
                dp(4f),
                dp(4f),
                bgPaint
            )

            bgPaint.style = Paint.Style.FILL

            val innerWidth =
                (
                    width -
                    dp(4f)
                ) *
                batteryPercent /
                100f

            canvas.drawRoundRect(
                left + dp(2f),
                top + dp(2f),
                left + dp(2f) + innerWidth.coerceAtLeast(0f),
                top + height - dp(2f),
                dp(2.5f),
                dp(2.5f),
                bgPaint
            )

            canvas.drawRoundRect(
                left + width,
                top + dp(4f),
                left + width + dp(2f),
                top + height - dp(4f),
                dp(1f),
                dp(1f),
                bgPaint
            )
        }

        private fun ccIndicatorRevealV17(
            progress: Float,
            start: Float,
            end: Float
        ): Float {

            return ccEaseV17(
                ccReveal(
                    progress.coerceIn(0f, 1f),
                    start,
                    end
                )
            )
        }


        private fun drawIOSStatusIndicators(
            canvas: Canvas
        ) {

            // ========================================================
            // IOS27 STATUS INDICATORS V17
            // ========================================================
            //
            // Верхняя системная группа не является отдельным
            // Control Center слоем.
            //
            // При раскрытии:
            // - время плавно dematerialize;
            // - Wi-Fi/сеть/батарея слегка опускаются;
            // - alpha постепенно уменьшается;
            // - реальные данные берутся из Android API.
            // ========================================================

            val p =
                controlCenterProgress.coerceIn(
                    0f,
                    1f
                )

            val morph =
                ccEaseV17(
                    ccReveal(
                        p,
                        0.05f,
                        0.72f
                    )
                )

            val leftAlpha =
                (
                    1f -
                    morph
                )
                    .coerceIn(
                        0.12f,
                        1f
                    )

            val rightAlpha =
                (
                    1f -
                    morph * 0.82f
                )
                    .coerceIn(
                        0.18f,
                        1f
                    )

            val rightShift =
                dp(8f) *
                morph

            val time =
                SimpleDateFormat(
                    "HH:mm",
                    Locale.getDefault()
                ).format(Date())

            // --------------------------------------------------------
            // TIME
            // --------------------------------------------------------

            textPaint.color = Color.WHITE
            textPaint.alpha =
                (
                    255f *
                    leftAlpha *
                    homeAlpha.coerceAtLeast(0.85f)
                )
                    .toInt()
                    .coerceIn(0, 255)

            textPaint.textSize = dp(15f)
            textPaint.typeface =
                Typeface.create(
                    "sans",
                    Typeface.BOLD
                )
            textPaint.textAlign =
                Paint.Align.LEFT

            canvas.drawText(
                time,
                dp(22f),
                dp(31f) + rightShift * 0.15f,
                textPaint
            )

            // --------------------------------------------------------
            // RIGHT SYSTEM INDICATORS
            // --------------------------------------------------------

            canvas.save()

            canvas.translate(
                0f,
                rightShift
            )

            textPaint.color = Color.WHITE
            textPaint.alpha =
                (
                    255f *
                    rightAlpha
                )
                    .toInt()
                    .coerceIn(0, 255)

            val groupY =
                dp(25f)

            val batteryRight =
                width -
                dp(16f)

            val batteryLeft =
                batteryRight -
                dp(24f)

            // Battery
            drawCCBatteryV17(
                canvas,
                batteryLeft,
                groupY - dp(6f),
                dp(24f),
                dp(12f)
            )

            // Battery percentage
            textPaint.textAlign =
                Paint.Align.RIGHT

            textPaint.textSize =
                dp(10f)

            textPaint.typeface =
                Typeface.DEFAULT_BOLD

            canvas.drawText(
                buildString {
                    append(batteryPercent)
                    append("%")
                },
                batteryLeft - dp(5f),
                groupY + dp(3.5f),
                textPaint
            )

            // Wi-Fi
            val wifiCx =
                batteryLeft -
                dp(34f)

            bgPaint.style =
                Paint.Style.STROKE

            bgPaint.strokeWidth =
                dp(1.7f)

            bgPaint.strokeCap =
                Paint.Cap.ROUND

            bgPaint.color =
                Color.WHITE

            bgPaint.alpha =
                (
                    235f *
                    rightAlpha
                )
                    .toInt()
                    .coerceIn(0, 255)

            val wifiBars =
                if (wifiConnected) {
                    wifiLevel.coerceIn(1, 4)
                } else {
                    0
                }

            if (wifiConnected) {

                val arcs =
                    min(
                        3,
                        wifiBars
                    )

                for (i in 0 until arcs) {

                    val radius =
                        dp(
                            5f +
                            i * 4f
                        )

                    canvas.drawArc(
                        RectF(
                            wifiCx - radius,
                            groupY - radius,
                            wifiCx + radius,
                            groupY + radius + dp(2f)
                        ),
                        225f,
                        90f,
                        false,
                        bgPaint
                    )
                }

                bgPaint.style =
                    Paint.Style.FILL

                canvas.drawCircle(
                    wifiCx,
                    groupY + dp(4f),
                    dp(1.7f),
                    bgPaint
                )

            } else {

                bgPaint.style =
                    Paint.Style.STROKE

                bgPaint.alpha =
                    (
                        150f *
                        rightAlpha
                    )
                        .toInt()
                        .coerceIn(0, 255)

                canvas.drawCircle(
                    wifiCx,
                    groupY,
                    dp(2f),
                    bgPaint
                )
            }

            // Mobile signal
            val signalCx =
                wifiCx -
                dp(19f)

            drawCCBarsV17(
                canvas,
                signalCx,
                groupY,
                mobileLevel
            )

            canvas.restore()

            textPaint.alpha = 255
            textPaint.textAlign =
                Paint.Align.LEFT

            bgPaint.alpha = 255
            bgPaint.style =
                Paint.Style.FILL
            bgPaint.strokeCap =
                Paint.Cap.BUTT
        }

        private var searchMode = false
        private var editMode = false

        private var pressedIndex = -1
        private var pressedScale = 1f

        private var homeAlpha = 0f

        private var dragging = false

        // IOS27_CONTROL_CENTER_GESTURE_V4
        // Интерактивный жест Control Center.
        private var controlCenterGesture = false
        private var controlCenterStartY = 0f
        private var controlCenterStartX = 0f

        private val columns = 4
        private val rows = 6

        private val pageAnimator =
            ValueAnimator.ofFloat(0f, 1f)

        private val pressAnimator =
            ValueAnimator.ofFloat(1f, 0.88f, 1f)

        init {

            isFocusable = true

            textPaint.typeface =
                Typeface.create(
                    "sans",
                    Typeface.NORMAL
                )

            pageAnimator.interpolator =
                DecelerateInterpolator()

            pageAnimator.duration = 280L

            pageAnimator.addUpdateListener {
                pageOffset =
                    it.animatedValue as Float

                invalidate()
            }

            pressAnimator.duration = 140L

            pressAnimator.addUpdateListener {
                pressedScale =
                    it.animatedValue as Float

                invalidate()
            }

            reloadApps()


            startSystemIndicatorObserversV17()
        }
        fun isControlCenterVisibleV17(): Boolean {

            return controlCenter ||
                controlCenterProgress > 0.01f
        }

        fun closeControlCenterFromActivityV17() {

            settleControlCenterV17(
                opening = false,
                releaseVelocityPx = 0f
            )
        }


        fun startHomeAnimation() {

            homeAlpha = 0f

            ValueAnimator
                .ofFloat(0f, 1f)
                .apply {

                    duration = 520L

                    interpolator =
                        DecelerateInterpolator()

                    addUpdateListener {

                        homeAlpha =
                            it.animatedValue as Float

                        invalidate()
                    }

                    start()
                }
        }

        fun reloadApps() {

            apps.clear()

            val intent =
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(
                        Intent.CATEGORY_LAUNCHER
                    )
                }

            val resolved =
                pm.queryIntentActivities(
                    intent,
                    PackageManager.MATCH_ALL
                )

            resolved
                .distinctBy {
                    it.activityInfo.packageName
                }
                .sortedBy {
                    it.loadLabel(pm)
                        .toString()
                        .lowercase()
                }
                .forEach {

                    val info =
                        it.activityInfo.applicationInfo

                    apps.add(
                        AppItem(
                            it.loadLabel(pm).toString(),
                            info.packageName,
                            info.loadIcon(pm)
                        )
                    )
                }

            val maxPage = max(
                0,
                (apps.size - 1) /
                    (columns * rows)
            )

            page =
                min(page, maxPage)

            // Rebuild Dock cache only when the installed app list changes.
            dockAppsCache = getDockApps()

            invalidate()
        }


        override fun onDraw(canvas: Canvas) {

            super.onDraw(canvas)

            drawWallpaper(canvas)

            drawIOSStatusIndicators(canvas)

            if (searchMode) {

                drawSearch(canvas)

                return
            }

            drawHome(canvas)

            if (
                controlCenter ||
                controlCenterProgress > 0f
            ) {

                drawControlCenter(canvas)
            }

            // IOS27_NAVIGATION_BAR_TOP_LAYER_V12
            //
            // Home Indicator рисуется ПОСЛЕ Control Center.
            // Поэтому остаётся поверх Liquid Glass панели.

            drawNavigationHomeIndicator(canvas)
        }


        // -----------------------------------------
        // WALLPAPER
        // -----------------------------------------

        private fun prepareWallpaperShaders() {
            val w = width
            val h = height

            if (w <= 0 || h <= 0) {
                return
            }

            if (
                wallpaperWidth == w &&
                wallpaperHeight == h &&
                wallpaperGradient != null &&
                wallpaperGlow1 != null &&
                wallpaperGlow2 != null &&
                wallpaperGlow3 != null
            ) {
                return
            }

            wallpaperWidth = w
            wallpaperHeight = h

            val wf = w.toFloat()
            val hf = h.toFloat()

            wallpaperGradient =
                LinearGradient(
                    0f,
                    0f,
                    wf,
                    hf,
                    Color.rgb(20, 42, 82),
                    Color.rgb(4, 7, 18),
                    Shader.TileMode.CLAMP
                )

            wallpaperGlow1 =
                RadialGradient(
                    wf * 0.22f,
                    hf * 0.12f,
                    wf * 0.7f,
                    Color.argb(
                        180,
                        80,
                        150,
                        255
                    ),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )

            wallpaperGlow2 =
                RadialGradient(
                    wf * 0.85f,
                    hf * 0.68f,
                    wf * 0.62f,
                    Color.argb(
                        130,
                        180,
                        90,
                        235
                    ),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )

            wallpaperGlow3 =
                RadialGradient(
                    wf * 0.35f,
                    hf * 0.95f,
                    wf * 0.5f,
                    Color.argb(
                        90,
                        40,
                        180,
                        255
                    ),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )
        }

        override fun onSizeChanged(
            w: Int,
            h: Int,
            oldw: Int,
            oldh: Int
        ) {
            super.onSizeChanged(w, h, oldw, oldh)

            wallpaperWidth = 0
            wallpaperHeight = 0

            prepareWallpaperShaders()
        }

        private fun drawWallpaper(
            canvas: Canvas
        ) {
            prepareWallpaperShaders()

            // IOS27_STARTUP_BACKGROUND_FIX_V15
            // Не допускаем сохранённый alpha/цвет от предыдущего
            // draw-pass.
            bgPaint.alpha = 255
            bgPaint.style = Paint.Style.FILL

            val w = width.toFloat()
            val h = height.toFloat()

            wallpaperGradient?.let {
                bgPaint.shader = it

                canvas.drawRect(
                    0f,
                    0f,
                    w,
                    h,
                    bgPaint
                )
            }

            wallpaperGlow1?.let {
                bgPaint.shader = it

                canvas.drawCircle(
                    w * 0.22f,
                    h * 0.12f,
                    w * 0.7f,
                    bgPaint
                )
            }

            wallpaperGlow2?.let {
                bgPaint.shader = it

                canvas.drawCircle(
                    w * 0.85f,
                    h * 0.68f,
                    w * 0.62f,
                    bgPaint
                )
            }

            wallpaperGlow3?.let {
                bgPaint.shader = it

                canvas.drawCircle(
                    w * 0.35f,
                    h * 0.95f,
                    w * 0.5f,
                    bgPaint
                )
            }

            bgPaint.shader = null
        }

        // -----------------------------------------
        // STATUS BAR
        // -----------------------------------------

        private fun drawStatusBar(
            canvas: Canvas
        ) {

            textPaint.color =
                Color.WHITE

            textPaint.alpha =
                (255 * homeAlpha).toInt()

            textPaint.textSize =
                dp(15f)

            textPaint.typeface =
                Typeface.create(
                    "sans",
                    Typeface.BOLD
                )

            val time =
                SimpleDateFormat(
                    "HH:mm",
                    Locale.getDefault()
                ).format(Date())

            canvas.drawText(
                time,
                dp(22f),
                dp(31f),
                textPaint
            )

            textPaint.alpha = 255

            textPaint.textAlign =
                Paint.Align.LEFT
        }

        // -----------------------------------------
        // HOME
        // -----------------------------------------

        private fun drawHome(
            canvas: Canvas
        ) {

            // iOS-like Home Screen spacing.
            // Leave room for Search and the translucent Dock.
            val top =
                dp(67f)

            val bottom =
                height - dp(184f)

            val availableHeight =
                bottom - top

            val cellW =
                width.toFloat() /
                    columns

            val cellH =
                availableHeight /
                    rows

            val perPage =
                columns * rows

            val currentPage =
                page

            val nextPage =
                if (pageOffset > 0f)
                    min(
                        page + 1,
                        maxPage()
                    )
                else
                    max(
                        page - 1,
                        0
                    )

            /*
             * Both pages move together during the swipe.
             * This prevents the current page from looking stuck.
             */

            val currentOffset =
                -pageOffset * width.toFloat()

            drawPage(
                canvas,
                currentPage,
                currentOffset,
                top,
                bottom,
                cellW,
                cellH
            )

            if (
                pageOffset != 0f &&
                nextPage != currentPage
            ) {

                val neighbourOffset =
                    if (pageOffset > 0f) {
                        width.toFloat() + currentOffset
                    } else {
                        -width.toFloat() + currentOffset
                    }

                drawPage(
                    canvas,
                    nextPage,
                    neighbourOffset,
                    top,
                    bottom,
                    cellW,
                    cellH
                )
            }

            if (maxPage() > 0) {
                drawPageDots(
                    canvas,
                    height - dp(158f)
                )
            }

            drawSearchPill(canvas)

            drawDock(canvas)
        }

        private fun drawPage(
            canvas: Canvas,
            targetPage: Int,
            offset: Float,
            top: Float,
            bottom: Float,
            cellW: Float,
            cellH: Float
        ) {

            // IOS27_PAGE_CRASH_FIX_V2
            // Никогда не позволяем странице выйти за диапазон.
            val safePage =
                targetPage.coerceIn(
                    0,
                    maxPage()
                )

            val perPage =
                columns * rows

            val start =
                safePage * perPage

            val end =
                min(
                    start + perPage,
                    apps.size
                )

            canvas.save()

            canvas.translate(
                offset,
                0f
            )

            for (i in start until end) {

                val local =
                    i - start

                val col =
                    local % columns

                val row =
                    local / columns

                val cx =
                    cellW * col +
                    cellW / 2f

                val cy =
                    top +
                    cellH * row +
                    cellH * 0.48f

                val scale =
                    if (i == pressedIndex)
                        pressedScale
                    else
                        1f

                canvas.save()

                canvas.scale(
                    scale,
                    scale,
                    cx,
                    cy
                )

                drawApp(
                    canvas,
                    apps[i],
                    cx,
                    cy
                )

                canvas.restore()
            }

            canvas.restore()
        }

        private fun drawApp(
            canvas: Canvas,
            app: AppItem,
            cx: Float,
            cy: Float
        ) {

            val size =
                dp(59f)

            val left =
                cx - size / 2f

            val top =
                cy - size / 2f

            // No artificial gray square behind the icon.
            // iOS-style depth comes from the icon itself.
            app.icon.setBounds(
                left.toInt(),
                top.toInt(),
                (left + size).toInt(),
                (top + size).toInt()
            )

            app.icon.alpha =
                (255 * homeAlpha).toInt()

            app.icon.draw(canvas)

            textPaint.color =
                Color.WHITE

            textPaint.alpha =
                (255 * homeAlpha).toInt()

            textPaint.textSize =
                dp(11.5f)

            textPaint.textAlign =
                Paint.Align.CENTER

            textPaint.typeface =
                Typeface.create(
                    "sans",
                    Typeface.NORMAL
                )

            val label =
                if (app.label.length > 14)
                    app.label.take(13) + "…"
                else
                    app.label

            canvas.drawText(
                label,
                cx,
                cy + dp(43f),
                textPaint
            )

            textPaint.alpha = 255

            textPaint.textAlign =
                Paint.Align.LEFT
        }

        // -----------------------------------------
        // DOCK
        // -----------------------------------------



    private fun drawDock(canvas: Canvas) {

        // =================================================
        // IOS27_DOCK_PHYSICS_V12
        // =================================================

        val rawProgress =
            controlCenterProgress
                .coerceIn(0f, 1f)

        val physicsProgress =
            if (controlCenterInteractive) {

                rawProgress

            } else {

                rawProgress *
                    rawProgress *
                    (3f - 2f * rawProgress)
            }

        val margin =
            dp(12f)

        val dockHeight =
            dp(82f)

        val baseTop =
            height - dp(103f)

        val left =
            margin

        val right =
            width - margin

        val baseBottom =
            baseTop + dockHeight

        val centerX =
            (left + right) / 2f

        val centerY =
            (baseTop + baseBottom) / 2f

        // Лёгкое движение вниз.
        val translationY =
            dp(5f) *
            physicsProgress

        // Лёгкое сжатие Dock.
        val dockScale =
            1f -
            0.014f *
            physicsProgress

        // Уменьшаем визуальную выраженность.
        val dockAlpha =
            (
                1f -
                0.30f *
                physicsProgress
            )
                .coerceIn(
                    0.65f,
                    1f
                )

        canvas.save()

        canvas.translate(
            centerX,
            centerY + translationY
        )

        canvas.scale(
            dockScale,
            dockScale
        )

        canvas.translate(
            -centerX,
            -centerY
        )

        glassPaint.style =
            Paint.Style.FILL

        glassPaint.color =
            Color.argb(
                (105f * dockAlpha)
                    .toInt()
                    .coerceIn(
                        0,
                        255
                    ),
                255,
                255,
                255
            )

        canvas.drawRoundRect(
            left,
            baseTop,
            right,
            baseBottom,
            dp(27f),
            dp(27f),
            glassPaint
        )

        glassPaint.color =
            Color.argb(
                (55f * dockAlpha)
                    .toInt()
                    .coerceIn(
                        0,
                        255
                    ),
                255,
                255,
                255
            )

        canvas.drawRoundRect(
            left + dp(1f),
            baseTop + dp(1f),
            right - dp(1f),
            baseTop + dp(25f),
            dp(26f),
            dp(26f),
            glassPaint
        )

        val dockApps =
            dockAppsCache

        val count =
            min(
                4,
                dockApps.size
            )

        if (count > 0) {

            val slot =
                (right - left) /
                    count

            for (i in 0 until count) {

                val cx =
                    left +
                    slot * i +
                    slot / 2f

                val cy =
                    baseTop +
                    dockHeight / 2f

                drawApp(
                    canvas,
                    dockApps[i],
                    cx,
                    cy
                )
            }
        }

        canvas.restore()
    }

        // =================================================
        // IOS27_HOME_INDICATOR_V12
        // =================================================


        private fun drawNavigationHomeIndicator(
            canvas: Canvas
        ) {

            // ========================================================
            // IOS27_HOME_INDICATOR_PHYSICS_V13
            // ========================================================

            val progress =
                controlCenterProgress
                    .coerceIn(
                        0f,
                        1f
                    )

            // Во время пальца индикатор следует
            // непосредственно за жестом.
            //
            // После отпускания используется spring.

            val interactiveScale =
                if (controlCenterInteractive) {

                    1f +
                    0.22f *
                    progress

                } else {

                    navigationBarSpringScale
                }


            // --------------------------------------------------------
            // WIDTH
            // --------------------------------------------------------

            val baseWidth =
                dp(134f)

            val indicatorWidth =
                baseWidth *
                interactiveScale


            // --------------------------------------------------------
            // HEIGHT / SQUASH
            // --------------------------------------------------------

            val stretch =
                (
                    interactiveScale -
                    1f
                )
                    .coerceIn(
                        -0.25f,
                        0.30f
                    )

            val indicatorHeight =
                dp(5.5f) *
                (
                    1f -
                    0.28f *
                    stretch
                )


            // --------------------------------------------------------
            // VERTICAL MOVEMENT
            // --------------------------------------------------------

            val baseY =
                height -
                dp(10f)

            val indicatorY =
                baseY -
                dp(5f) *
                progress


            // --------------------------------------------------------
            // HORIZONTAL
            // --------------------------------------------------------

            val cx =
                width / 2f

            val left =
                cx -
                indicatorWidth / 2f

            val right =
                cx +
                indicatorWidth / 2f

            val top =
                indicatorY -
                indicatorHeight

            val bottom =
                indicatorY


            // --------------------------------------------------------
            // SHADOW / GLOW
            // --------------------------------------------------------

            shadowPaint.style =
                Paint.Style.FILL

            shadowPaint.color =
                Color.argb(
                    75,
                    0,
                    0,
                    0
                )

            shadowPaint.setShadowLayer(
                dp(3f),
                0f,
                dp(1f),
                Color.argb(
                    100,
                    0,
                    0,
                    0
                )
            )

            setLayerType(
                View.LAYER_TYPE_SOFTWARE,
                shadowPaint
            )

            canvas.drawRoundRect(
                left,
                top,
                right,
                bottom,
                indicatorHeight / 2f,
                indicatorHeight / 2f,
                shadowPaint
            )

            shadowPaint.clearShadowLayer()


            // --------------------------------------------------------
            // WHITE GLASS INDICATOR
            // --------------------------------------------------------

            glassPaint.style =
                Paint.Style.FILL

            glassPaint.color =
                Color.argb(
                    245,
                    255,
                    255,
                    255
                )

            canvas.drawRoundRect(
                left,
                top,
                right,
                bottom,
                indicatorHeight / 2f,
                indicatorHeight / 2f,
                glassPaint
            )
        }


        // =================================================
        // IOS27_NAVIGATION_BAR_SPRING_V12
        // =================================================


        private fun animateNavigationBarSpring(
            opening: Boolean
        ) {

            // ========================================================
            // IOS27_NAVIGATION_SPRING_V13
            // ========================================================

            navigationBarAnimator?.cancel()


            val values =
                if (opening) {

                    // При открытии:
                    //
                    // normal
                    // -> strong stretch
                    // -> compression
                    // -> overshoot
                    // -> settle

                    floatArrayOf(
                        1.00f,
                        1.18f,
                        1.28f,
                        0.94f,
                        1.045f,
                        1.00f
                    )

                } else {

                    // При закрытии:
                    //
                    // normal
                    // -> compression
                    // -> overshoot
                    // -> settle

                    floatArrayOf(
                        1.00f,
                        0.82f,
                        0.91f,
                        1.06f,
                        0.985f,
                        1.00f
                    )
                }


            navigationBarAnimator =
                ValueAnimator.ofFloat(
                    *values
                ).apply {

                    duration =
                        if (opening) {
                            620L
                        } else {
                            430L
                        }


                    // Быстрое начало + мягкое физическое
                    // завершение.

                    interpolator =
                        PathInterpolator(
                            0.18f,
                            0.90f,
                            0.20f,
                            1.00f
                        )


                    addUpdateListener {

                        navigationBarSpringScale =
                            it.animatedValue
                                as Float

                        invalidate()
                    }


                    start()
                }
        }




        private fun getDockApps(): List<AppItem> {

            val preferredPackages =
                listOf(
                    // Phone
                    listOf(
                        "com.google.android.dialer",
                        "com.samsung.android.dialer",
                        "com.android.dialer"
                    ),

                    // Browser
                    listOf(
                        "com.android.chrome",
                        "com.sec.android.app.sbrowser",
                        "com.google.android.googlequicksearchbox"
                    ),

                    // Messages
                    listOf(
                        "com.google.android.apps.messaging",
                        "com.samsung.android.messaging",
                        "com.android.mms"
                    ),

                    // Music
                    listOf(
                        "com.google.android.apps.youtube.music",
                        "com.samsung.android.app.music",
                        "com.spotify.music"
                    )
                )

            val result =
                mutableListOf<AppItem>()

            for (group in preferredPackages) {

                val found =
                    apps.firstOrNull {
                        it.packageName in group &&
                        result.none { selected ->
                            selected.packageName ==
                                it.packageName
                        }
                    }

                if (found != null) {
                    result.add(found)
                }
            }

            // Fill missing Dock slots with remaining apps.
            for (app in apps) {

                if (result.size >= 4)
                    break

                if (
                    result.none {
                        it.packageName ==
                            app.packageName
                    }
                ) {
                    result.add(app)
                }
            }

            return result.take(4)
        }

        private fun drawSearchPill(
            canvas: Canvas
        ) {

            val pillWidth =
                dp(92f)

            val pillHeight =
                dp(32f)

            val left =
                width / 2f -
                    pillWidth / 2f

            val top =
                height - dp(149f)

            val right =
                left + pillWidth

            val bottom =
                top + pillHeight

            // Liquid Glass search surface.
            glassPaint.color =
                Color.argb(
                    82,
                    255,
                    255,
                    255
                )

            canvas.drawRoundRect(
                left,
                top,
                right,
                bottom,
                dp(17f),
                dp(17f),
                glassPaint
            )

            // Search symbol.
            paint.color =
                Color.argb(
                    220,
                    255,
                    255,
                    255
                )

            paint.style =
                Paint.Style.STROKE

            paint.strokeWidth =
                dp(1.7f)

            val iconCx =
                left + dp(20f)

            val iconCy =
                top + pillHeight / 2f

            canvas.drawCircle(
                iconCx,
                iconCy - dp(1f),
                dp(5f),
                paint
            )

            canvas.drawLine(
                iconCx + dp(3.5f),
                iconCy + dp(3f),
                iconCx + dp(7f),
                iconCy + dp(6.5f),
                paint
            )

            paint.style =
                Paint.Style.FILL

            textPaint.color =
                Color.WHITE

            textPaint.alpha =
                (220 * homeAlpha).toInt()

            textPaint.textSize =
                dp(12.5f)

            textPaint.textAlign =
                Paint.Align.LEFT

            textPaint.typeface =
                Typeface.create(
                    "sans",
                    Typeface.NORMAL
                )

            canvas.drawText(
                "Поиск",
                left + dp(31f),
                top + dp(21f),
                textPaint
            )

            textPaint.alpha = 255
        }

        // -----------------------------------------
        // PAGE DOTS
        // -----------------------------------------

        private fun drawPageDots(
            canvas: Canvas,
            y: Float
        ) {

            val pages =
                maxPage() + 1

            if (pages <= 1)
                return

            val dot =
                dp(6f)

            val gap =
                dp(6f)

            val total =
                pages * dot +
                (pages - 1) * gap

            var x =
                width / 2f -
                total / 2f

            for (i in 0 until pages) {

                paint.color =
                    if (i == page)
                        Color.WHITE
                    else
                        Color.argb(
                            100,
                            255,
                            255,
                            255
                        )

                canvas.drawCircle(
                    x + dot / 2f,
                    y,
                    dot / 2f,
                    paint
                )

                x +=
                    dot + gap
            }
        }

        private fun maxPage(): Int {

            if (apps.isEmpty())
                return 0

            return max(
                0,
                (apps.size - 1) /
                    (columns * rows)
            )
        }

        // -----------------------------------------
        // SEARCH
        // -----------------------------------------

        private fun drawSearch(
            canvas: Canvas
        ) {

            paint.color =
                Color.argb(
                    185,
                    4,
                    7,
                    18
                )

            canvas.drawRect(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                paint
            )

            glassPaint.color =
                Color.argb(
                    165,
                    255,
                    255,
                    255
                )

            canvas.drawRoundRect(
                dp(18f),
                dp(55f),
                width - dp(18f),
                dp(111f),
                dp(28f),
                dp(28f),
                glassPaint
            )

            textPaint.color =
                Color.DKGRAY

            textPaint.textSize =
                dp(17f)

            canvas.drawText(
                "⌕   Поиск",
                dp(39f),
                dp(91f),
                textPaint
            )

            textPaint.color =
                Color.WHITE

            textPaint.textSize =
                dp(26f)

            textPaint.typeface =
                Typeface.DEFAULT_BOLD

            canvas.drawText(
                "Поиск",
                dp(25f),
                dp(165f),
                textPaint
            )

            textPaint.textSize =
                dp(15f)

            textPaint.typeface =
                Typeface.DEFAULT

        }

        // -----------------------------------------
        // CONTROL CENTER
        // -----------------------------------------


        // IOS27_CONTROL_CENTER_ANIMATION_V9
        // V9: интерактивное раскрытие Control Center.
        // Progress напрямую связан с движением пальца.


        // ============================================================
        // IOS27 CONTROL CENTER V17
        // ============================================================
        //
        // Gesture driven:
        // - direct finger tracking
        // - velocity
        // - inertia
        // - damped spring
        // - overshoot
        // - elastic stretch
        // - internal stagger
        // - indicator spring
        // ============================================================

        private fun ccEaseV17(
            value: Float
        ): Float {

            val t =
                value.coerceIn(
                    0f,
                    1f
                )

            return t * t * (
                3f -
                2f * t
            )
        }

        private fun ccRubberV17(
            value: Float
        ): Float {

            val v =
                value.coerceAtLeast(0f)

            return (
                v /
                (
                    1f +
                    v * 5.5f
                )
            )
                .coerceIn(
                    0f,
                    0.12f
                )
        }

        private fun ccSpringScalarV17(
            value: Float
        ): Float {

            val t =
                value.coerceIn(
                    0f,
                    1f
                )

            val envelope =
                kotlin.math.exp(
                    (-7.2f * t).toDouble()
                )
                    .toFloat()

            val wave =
                kotlin.math.cos(
                    (10.8f * t).toDouble()
                )
                    .toFloat()

            return (
                1f -
                envelope * wave
            )
                .coerceIn(
                    0f,
                    1.05f
                )
        }

        private fun ccSpringProgressV17(
            start: Float,
            target: Float,
            initialVelocity: Float,
            fraction: Float,
            durationMs: Long
        ): Float {

            val zeta = 0.72f
            val omega0 = 12.0f

            val omegaD =
                omega0 *
                kotlin.math.sqrt(
                    1f -
                    zeta * zeta
                )

            val time =
                (
                    fraction.coerceIn(
                        0f,
                        1f
                    ) *
                    durationMs.toFloat() /
                    1000f
                )

            val x0 =
                start -
                target

            val v0 =
                initialVelocity.coerceIn(
                    -3.5f,
                    3.5f
                )

            val b =
                (
                    v0 +
                    zeta *
                    omega0 *
                    x0
                ) /
                omegaD

            val envelope =
                kotlin.math.exp(
                    (
                        -zeta *
                        omega0 *
                        time
                    ).toDouble()
                )
                    .toFloat()

            val oscillation =
                x0 *
                kotlin.math.cos(
                    (
                        omegaD *
                        time
                    ).toDouble()
                )
                    .toFloat() +
                b *
                kotlin.math.sin(
                    (
                        omegaD *
                        time
                    ).toDouble()
                )
                    .toFloat()

            return (
                target +
                envelope *
                oscillation
            )
                .coerceIn(
                    0f,
                    1.14f
                )
        }

        private fun controlCenterPanelHeightV17(): Float {

            val top =
                dp(10f)

            val groupHeight =
                dp(118f)

            val gap =
                dp(10f)

            val sliderHeight =
                dp(58f)

            val sliderGap =
                dp(10f)

            val bottomControlsTop =
                top +
                groupHeight +
                gap +
                sliderHeight +
                sliderGap +
                sliderHeight +
                dp(20f)

            return (
                bottomControlsTop +
                dp(78f) -
                top +
                dp(18f)
            )
        }

        private fun controlCenterTravelV17(): Float {

            return (
                controlCenterPanelHeightV17() +
                dp(16f)
            )
        }

        private fun drawCCBackdropV17(
            canvas: Canvas,
            progress: Float
        ) {

            val p =
                progress.coerceIn(
                    0f,
                    1f
                )

            if (p <= 0f)
                return

            val alpha =
                (
                    132f *
                    ccEaseV17(p)
                )
                    .toInt()
                    .coerceIn(
                        0,
                        132
                    )

            bgPaint.style =
                Paint.Style.FILL

            bgPaint.color =
                Color.argb(
                    alpha,
                    0,
                    0,
                    0
                )

            canvas.drawRect(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                bgPaint
            )
        }

        private fun ccRevealV17(
            progress: Float,
            start: Float,
            end: Float
        ): Float {

            return ccEaseV17(
                ccReveal(
                    progress.coerceIn(
                        0f,
                        1f
                    ),
                    start,
                    end
                )
            )
        }

        private fun drawCCPhysicalV17(
            canvas: Canvas,
            progress: Float,
            start: Float,
            end: Float,
            travel: Float,
            block: () -> Unit
        ) {

            val reveal =
                ccRevealV17(
                    progress,
                    start,
                    end
                )

            if (reveal <= 0f)
                return

            canvas.save()

            val spring =
                ccSpringScalarV17(
                    reveal
                )

            val elementScale =
                0.90f +
                0.10f *
                spring

            val translation =
                travel *
                (
                    1f -
                    reveal
                )

            canvas.translate(
                width / 2f,
                translation
            )

            canvas.scale(
                elementScale,
                elementScale,
                0f,
                0f
            )

            canvas.translate(
                -width / 2f,
                0f
            )

            val alpha =
                (
                    245f *
                    ccEaseV17(
                        reveal
                    )
                )
                    .toInt()
                    .coerceIn(
                        0,
                        245
                    )

            val layer =
                canvas.saveLayerAlpha(
                    0f,
                    0f,
                    width.toFloat(),
                    height.toFloat(),
                    alpha
                )

            block()

            canvas.restoreToCount(
                layer
            )

            canvas.restore()
        }

        private fun drawControlCenter(
            canvas: Canvas
        ) {

            // ========================================================
            // IOS27 CONTROL CENTER RENDERER V17
            // ========================================================

            val rawProgress =
                controlCenterProgress.coerceIn(
                    0f,
                    1.14f
                )

            if (
                rawProgress <= 0f &&
                !controlCenter
            ) {
                return
            }

            val p =
                rawProgress.coerceIn(
                    0f,
                    1f
                )

            val overscroll =
                (
                    rawProgress -
                    1f
                )
                    .coerceAtLeast(0f)

            val w =
                width.toFloat()

            val margin =
                dp(14f)

            val top =
                dp(10f)

            val gap =
                dp(10f)

            val groupWidth =
                (
                    w -
                    margin * 2f -
                    gap
                ) / 2f

            val groupHeight =
                dp(118f)

            val sliderHeight =
                dp(58f)

            val sliderGap =
                dp(10f)

            val bottomControlsTop =
                top +
                groupHeight +
                gap +
                sliderHeight +
                sliderGap +
                sliderHeight +
                dp(20f)

            val panelHeight =
                controlCenterPanelHeightV17()

            drawCCBackdropV17(
                canvas,
                p
            )

            // --------------------------------------------------------
            // MAIN PANEL PHYSICS
            // --------------------------------------------------------

            val slide =
                -panelHeight *
                (
                    1f -
                    p
                )

            val elastic =
                ccRubberV17(
                    overscroll
                )

            // Stretch grows from the top anchor.
            val scaleX =
                (
                    0.94f +
                    0.06f * p +
                    elastic * 0.12f
                )
                    .coerceIn(
                        0.94f,
                        1.055f
                    )

            val scaleY =
                (
                    0.80f +
                    0.20f * p +
                    elastic * 0.18f
                )
                    .coerceIn(
                        0.80f,
                        1.09f
                    )

            val centerX =
                w / 2f

            canvas.save()

            canvas.translate(
                centerX,
                top + slide
            )

            canvas.scale(
                scaleX,
                scaleY
            )

            canvas.translate(
                -centerX,
                -top
            )

            // --------------------------------------------------------
            // CONNECTIVITY + REAL INDICATORS
            // --------------------------------------------------------

            drawCCPhysicalV17(
                canvas,
                rawProgress,
                0.06f,
                0.40f,
                dp(20f)
            ) {

                glassPaint.style =
                    Paint.Style.FILL

                glassPaint.color =
                    Color.argb(
                        150,
                        245,
                        248,
                        255
                    )

                canvas.drawRoundRect(
                    margin,
                    top,
                    margin + groupWidth,
                    top + groupHeight,
                    dp(28f),
                    dp(28f),
                    glassPaint
                )

                glassPaint.color =
                    Color.argb(
                        38,
                        255,
                        255,
                        255
                    )

                canvas.drawRoundRect(
                    margin + dp(1f),
                    top + dp(1f),
                    margin + groupWidth - dp(1f),
                    top + dp(32f),
                    dp(27f),
                    dp(27f),
                    glassPaint
                )

                val indicatorReveal =
                    ccIndicatorRevealV17(
                        rawProgress,
                        0.12f,
                        0.58f
                    )

                canvas.save()

                val indicatorOffset =
                    dp(10f) *
                    (
                        1f -
                        indicatorReveal
                    )

                val indicatorSpring =
                    ccSpringScalarV17(
                        indicatorReveal
                    )

                canvas.translate(
                    0f,
                    indicatorOffset -
                    dp(2f) *
                    indicatorSpring
                )

                val indicatorAlpha =
                    (
                        255f *
                        indicatorReveal
                    )
                        .toInt()
                        .coerceIn(
                            0,
                            255
                        )

                val oldAlpha =
                    textPaint.alpha

                textPaint.alpha =
                    indicatorAlpha

                // Wi-Fi
                drawCCCircle(
                    canvas,
                    margin + dp(37f),
                    top + dp(38f),
                    dp(23f),
                    "Wi",
                    wifiConnected
                )

                // Bluetooth
                drawCCCircle(
                    canvas,
                    margin + dp(96f),
                    top + dp(38f),
                    dp(23f),
                    "BT",
                    bluetoothState ==
                        android.bluetooth.BluetoothAdapter
                            .STATE_ON
                )

                // Mobile network
                drawCCCircle(
                    canvas,
                    margin + dp(37f),
                    top + dp(88f),
                    dp(23f),
                    mobileType.take(3),
                    mobileConnected
                )

                // Airplane remains a local visual control.
                drawCCCircle(
                    canvas,
                    margin + dp(96f),
                    top + dp(88f),
                    dp(23f),
                    "✈",
                    false
                )

                // Wi-Fi signal number/bars
                drawCCBarsV17(
                    canvas,
                    margin + dp(145f),
                    top + dp(39f),
                    wifiLevel
                )

                // Cellular signal bars
                drawCCBarsV17(
                    canvas,
                    margin + dp(145f),
                    top + dp(89f),
                    mobileLevel
                )

                textPaint.color =
                    Color.WHITE

                textPaint.textSize =
                    dp(9f)

                textPaint.typeface =
                    Typeface.DEFAULT_BOLD

                textPaint.textAlign =
                    Paint.Align.LEFT

                canvas.drawText(
                    if (wifiConnected)
                        "Wi-Fi"
                    else
                        "Нет Wi-Fi",
                    margin + dp(16f),
                    top + dp(18f),
                    textPaint
                )

                textPaint.textSize =
                    dp(8f)

                textPaint.typeface =
                    Typeface.DEFAULT

                canvas.drawText(
                    if (mobileConnected)
                        mobileType
                    else
                        "Нет сети",
                    margin + dp(74f),
                    top + dp(18f),
                    textPaint
                )

                canvas.drawText(
                    if (batteryCharging)
                        "Зарядка"
                    else
                        "$batteryPercent%",
                    margin + dp(126f),
                    top + dp(18f),
                    textPaint
                )

                textPaint.alpha =
                    oldAlpha

                canvas.restore()

                // Battery block remains inside the main panel transform.
                drawCCBatteryV17(
                    canvas,
                    margin + groupWidth - dp(54f),
                    top + dp(86f),
                    dp(36f),
                    dp(16f)
                )
            }

            // --------------------------------------------------------
            // MEDIA
            // --------------------------------------------------------

            drawCCPhysicalV17(
                canvas,
                rawProgress,
                0.15f,
                0.49f,
                dp(26f)
            ) {

                val mediaLeft =
                    margin +
                    groupWidth +
                    gap

                glassPaint.style =
                    Paint.Style.FILL

                glassPaint.color =
                    Color.argb(
                        150,
                        245,
                        248,
                        255
                    )

                canvas.drawRoundRect(
                    mediaLeft,
                    top,
                    mediaLeft + groupWidth,
                    top + groupHeight,
                    dp(28f),
                    dp(28f),
                    glassPaint
                )

                glassPaint.color =
                    Color.argb(
                        36,
                        255,
                        255,
                        255
                    )

                canvas.drawRoundRect(
                    mediaLeft + dp(1f),
                    top + dp(1f),
                    mediaLeft + groupWidth - dp(1f),
                    top + dp(32f),
                    dp(27f),
                    dp(27f),
                    glassPaint
                )

                textPaint.alpha = 245
                textPaint.color = Color.WHITE
                textPaint.typeface =
                    Typeface.DEFAULT_BOLD
                textPaint.textAlign =
                    Paint.Align.LEFT

                textPaint.textSize =
                    dp(12f)

                canvas.drawText(
                    "Сейчас играет",
                    mediaLeft + dp(17f),
                    top + dp(28f),
                    textPaint
                )

                textPaint.textSize =
                    dp(18f)

                canvas.drawText(
                    "Музыка",
                    mediaLeft + dp(17f),
                    top + dp(55f),
                    textPaint
                )

                textPaint.color =
                    Color.argb(
                        190,
                        255,
                        255,
                        255
                    )

                textPaint.textSize =
                    dp(11f)

                textPaint.typeface =
                    Typeface.DEFAULT

                canvas.drawText(
                    "Ничего не воспроизводится",
                    mediaLeft + dp(17f),
                    top + dp(77f),
                    textPaint
                )

                drawCCCircle(
                    canvas,
                    mediaLeft + groupWidth - dp(31f),
                    top + dp(88f),
                    dp(20f),
                    "▶",
                    false
                )
            }

            // --------------------------------------------------------
            // BRIGHTNESS / VOLUME
            // --------------------------------------------------------

            drawCCPhysicalV17(
                canvas,
                rawProgress,
                0.29f,
                0.69f,
                dp(30f)
            ) {

                val sliderY =
                    top +
                    groupHeight +
                    gap

                drawCCSlider(
                    canvas,
                    margin,
                    sliderY,
                    w - margin * 2f,
                    sliderHeight,
                    "☀"
                )

                drawCCSlider(
                    canvas,
                    margin,
                    sliderY +
                    sliderHeight +
                    sliderGap,
                    w - margin * 2f,
                    sliderHeight,
                    "♪"
                )
            }

            // --------------------------------------------------------
            // BOTTOM CONTROLS
            // --------------------------------------------------------

            drawCCPhysicalV17(
                canvas,
                rawProgress,
                0.45f,
                0.90f,
                dp(36f)
            ) {

                val smallY =
                    bottomControlsTop

                val smallWidth =
                    (
                        w -
                        margin * 2f -
                        dp(30f)
                    ) / 4f

                drawCCSmall(
                    canvas,
                    margin,
                    smallY,
                    smallWidth,
                    "Фокус"
                )

                drawCCSmall(
                    canvas,
                    margin +
                    smallWidth +
                    dp(10f),
                    smallY,
                    smallWidth,
                    "Камера"
                )

                drawCCSmall(
                    canvas,
                    margin +
                    (
                        smallWidth +
                        dp(10f)
                    ) * 2f,
                    smallY,
                    smallWidth,
                    "QR"
                )

                drawCCSmall(
                    canvas,
                    margin +
                    (
                        smallWidth +
                        dp(10f)
                    ) * 3f,
                    smallY,
                    smallWidth,
                    "+"
                )
            }

            canvas.restore()

            textPaint.alpha = 255
            textPaint.textAlign =
                Paint.Align.LEFT

            glassPaint.alpha = 255
            bgPaint.alpha = 255
            bgPaint.style =
                Paint.Style.FILL
        }


    private fun drawCCCircle(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        label: String,
        active: Boolean
    ) {
        glassPaint.color =
            if (active) {
                Color.rgb(30, 110, 245)
            } else {
                Color.argb(
                    105,
                    205,
                    205,
                    210
                )
            }

        canvas.drawCircle(
            cx,
            cy,
            radius,
            glassPaint
        )

        textPaint.color =
            if (active) {
                Color.WHITE
            } else {
                Color.BLACK
            }

        textPaint.textSize = dp(12f)

        textPaint.typeface =
            Typeface.create(
                Typeface.DEFAULT,
                Typeface.BOLD
            )

        textPaint.textAlign =
            Paint.Align.CENTER

        canvas.drawText(
            label,
            cx,
            cy + dp(4f),
            textPaint
        )

        textPaint.textAlign =
            Paint.Align.LEFT
    }

    private fun drawCCSlider(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        height: Float,
        icon: String
    ) {
        glassPaint.color = Color.argb(
            105,
            210,
            210,
            215
        )

        canvas.drawRoundRect(
            x,
            y,
            x + width,
            y + height,
            dp(24f),
            dp(24f),
            glassPaint
        )

        glassPaint.color = Color.argb(
            245,
            255,
            255,
            255
        )

        canvas.drawRoundRect(
            x + dp(5f),
            y + dp(5f),
            x + width * 0.68f,
            y + height - dp(5f),
            dp(20f),
            dp(20f),
            glassPaint
        )

        textPaint.color = Color.BLACK
        textPaint.textSize = dp(22f)
        textPaint.textAlign = Paint.Align.CENTER

        canvas.drawText(
            icon,
            x + dp(30f),
            y + height / 2f + dp(7f),
            textPaint
        )

        textPaint.textAlign =
            Paint.Align.LEFT
    }

    private fun drawCCSmall(
        canvas: Canvas,
        x: Float,
        y: Float,
        width: Float,
        title: String
    ) {
        glassPaint.color = Color.argb(
            105,
            210,
            210,
            215
        )

        canvas.drawRoundRect(
            x,
            y,
            x + width,
            y + dp(58f),
            dp(20f),
            dp(20f),
            glassPaint
        )

        textPaint.color = Color.BLACK
        textPaint.textSize = dp(11f)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.DEFAULT_BOLD

        canvas.drawText(
            title,
            x + width / 2f,
            y + dp(34f),
            textPaint
        )

        textPaint.textAlign =
            Paint.Align.LEFT
    }

        private fun drawCCButton(
            canvas: Canvas,
            x: Float,
            y: Float,
            icon: String,
            title: String
        ) {

            glassPaint.color =
                Color.argb(
                    125,
                    255,
                    255,
                    255
                )

            canvas.drawRoundRect(
                x,
                y,
                x + dp(130f),
                y + dp(78f),
                dp(23f),
                dp(23f),
                glassPaint
            )

            textPaint.color =
                Color.BLACK

            textPaint.textSize =
                dp(25f)

            textPaint.typeface =
                Typeface.DEFAULT_BOLD

            canvas.drawText(
                icon,
                x + dp(17f),
                y + dp(34f),
                textPaint
            )

            textPaint.textSize =
                dp(12f)

            textPaint.typeface =
                Typeface.DEFAULT

            canvas.drawText(
                title,
                x + dp(17f),
                y + dp(59f),
                textPaint
            )
        }

        // -----------------------------------------

                        // ============================================================
        // IOS27 CONTROL CENTER V17 - SPRING SETTLE
        // ============================================================

        private fun settleControlCenterV17(
            opening: Boolean,
            releaseVelocityPx: Float
        ) {

            val start =
                controlCenterProgress
                    .coerceIn(
                        0f,
                        1.14f
                    )

            val target =
                if (opening)
                    1f
                else
                    0f

            if (opening) {
                controlCenter = true
            }

            controlCenterGesture = false
            controlCenterInteractive = false

            navigationBarAnimator?.cancel()
            ccAnimator?.cancel()

            ccVelocityTracker?.recycle()
            ccVelocityTracker = null

            val travel =
                controlCenterTravelV17()
                    .coerceAtLeast(
                        dp(1f)
                    )

            val initialVelocity =
                (
                    releaseVelocityPx /
                    travel
                )
                    .coerceIn(
                        -3.5f,
                        3.5f
                    )

            val duration =
                if (opening)
                    620L
                else
                    500L

            animateNavigationBarSpring(
                opening = opening
            )

            ccAnimator =
                ValueAnimator
                    .ofFloat(
                        0f,
                        1f
                    )
                    .apply {

                        interpolator =
                            android.view.animation
                                .LinearInterpolator()

                        this.duration =
                            duration

                        addUpdateListener {

                            val fraction =
                                it.animatedValue
                                    as Float

                            controlCenterProgress =
                                ccSpringProgressV17(
                                    start,
                                    target,
                                    initialVelocity,
                                    fraction,
                                    duration
                                )

                            postInvalidateOnAnimation()
                        }

                        addListener(
                            object :
                                android.animation.Animator.AnimatorListener {

                                override fun onAnimationStart(
                                    animation:
                                        android.animation.Animator
                                ) {
                                }

                                override fun onAnimationEnd(
                                    animation:
                                        android.animation.Animator
                                ) {

                                    controlCenterProgress =
                                        target

                                    if (!opening) {
                                        controlCenter = false
                                    }

                                    controlCenterInteractive =
                                        false

                                    ccAnimator = null

                                    postInvalidateOnAnimation()
                                }

                                override fun onAnimationCancel(
                                    animation:
                                        android.animation.Animator
                                ) {
                                }

                                override fun onAnimationRepeat(
                                    animation:
                                        android.animation.Animator
                                ) {
                                }
                            }
                        )

                        start()
                    }
        }

        private fun openControlCenter() {

            settleControlCenterV17(
                opening = true,
                releaseVelocityPx = 0f
            )
        }

        private fun closeControlCenter() {

            settleControlCenterV17(
                opening = false,
                releaseVelocityPx = 0f
            )
        }

    // TOUCH
    // -----------------------------------------

    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {

        val x = event.x
        val y = event.y

        when (event.actionMasked) {

            // ========================================================
            // DOWN
            // ========================================================

            MotionEvent.ACTION_DOWN -> {

                downX = x
                downY = y
                ccGestureLastY = y
                ccGestureDownTime =
                    System.currentTimeMillis()

                dragging = false

                ccVelocityTracker?.recycle()

                ccVelocityTracker =
                    android.view.VelocityTracker.obtain()

                ccVelocityTracker?.addMovement(
                    event
                )

                // ----------------------------------------------------
                // EXISTING CONTROL CENTER
                // ----------------------------------------------------

                if (controlCenter) {

                    controlCenterGesture = true
                    controlCenterInteractive = true

                    controlCenterStartY = y
                    controlCenterStartX = x

                    ccGestureStartProgress =
                        controlCenterProgress
                            .coerceIn(
                                0f,
                                1.14f
                            )

                    ccAnimator?.cancel()

                    pressedIndex = -1
                    pressedScale = 1f

                    return true
                }

                // ----------------------------------------------------
                // CLOSED CONTROL CENTER
                // ----------------------------------------------------
                //
                // Absolute top edge can be consumed by Android/Samsung.
                // Therefore the application accepts a slightly lower
                // right-corner region while preserving the system edge
                // gesture as much as Android permits.
                // ----------------------------------------------------

                val topGestureLimit =
                    dp(120f)

                val rightGestureLimit =
                    width *
                    0.50f

                controlCenterGesture =
                    y <= topGestureLimit &&
                    x >= rightGestureLimit

                if (controlCenterGesture) {

                    controlCenterStartY = y
                    controlCenterStartX = x

                    ccGestureStartProgress = 0f

                    pressedIndex = -1
                    pressedScale = 1f

                    return true
                }

                pressedIndex =
                    getAppIndex(
                        x,
                        y
                    )

                if (pressedIndex >= 0) {

                    pressedScale = 0.94f

                    invalidate()
                }

                return true
            }

            // ========================================================
            // MOVE
            // ========================================================

            MotionEvent.ACTION_MOVE -> {

                ccVelocityTracker?.addMovement(
                    event
                )

                val dx =
                    x -
                    downX

                val dy =
                    y -
                    downY

                val touchSlop =
                    (
                        ViewConfiguration
                            .get(context)
                            .scaledTouchSlop
                    )
                        .coerceAtLeast(
                            dp(4f)
                                .toInt()
                        )

                // ----------------------------------------------------
                // OPENING
                // ----------------------------------------------------

                if (
                    !controlCenter &&
                    controlCenterGesture
                ) {

                    if (
                        dy >
                        touchSlop
                    ) {

                        dragging = true

                        controlCenterInteractive =
                            true

                        navigationBarAnimator?.cancel()
                        ccAnimator?.cancel()

                        navigationBarSpringScale =
                            1f

                        val travel =
                            controlCenterTravelV17()

                        val raw =
                            dy /
                            travel

                        val progress =
                            if (raw <= 1f) {
                                raw.coerceIn(
                                    0f,
                                    1f
                                )
                            } else {
                                (
                                    1f +
                                    ccRubberV17(
                                        raw - 1f
                                    )
                                )
                                    .coerceIn(
                                        0f,
                                        1.12f
                                    )
                            }

                        controlCenterProgress =
                            progress

                        ccGestureLastY = y

                        postInvalidateOnAnimation()

                        return true
                    }

                    return true
                }

                // ----------------------------------------------------
                // ALREADY OPEN - INTERACTIVE CLOSE / OVERSCROLL
                // ----------------------------------------------------

                if (controlCenter) {

                    controlCenterInteractive =
                        true

                    val travel =
                        controlCenterTravelV17()

                    val deltaFromStart =
                        y -
                        controlCenterStartY

                    if (
                        deltaFromStart < 0f
                    ) {

                        // Finger moving up:
                        // panel follows finger directly.
                        val raw =
                            1f +
                            (
                                deltaFromStart /
                                travel
                            )

                        controlCenterProgress =
                            raw.coerceIn(
                                0f,
                                1f
                            )

                    } else {

                        // Downward pull while already open:
                        // small elastic overscroll.
                        val raw =
                            1f +
                            (
                                deltaFromStart /
                                travel
                            )

                        controlCenterProgress =
                            (
                                1f +
                                ccRubberV17(
                                    raw - 1f
                                )
                            )
                                .coerceIn(
                                    1f,
                                    1.12f
                                )
                    }

                    ccGestureLastY = y

                    postInvalidateOnAnimation()

                    return true
                }

                // ----------------------------------------------------
                // HOME PAGE SWIPE
                // ----------------------------------------------------

                if (
                    abs(dx) >
                    touchSlop
                ) {

                    dragging = true
                }

                return true
            }

            // ========================================================
            // UP
            // ========================================================

            MotionEvent.ACTION_UP -> {

                ccVelocityTracker?.addMovement(
                    event
                )

                ccVelocityTracker?.computeCurrentVelocity(
                    1000
                )

                val velocityY =
                    ccVelocityTracker
                        ?.yVelocity
                        ?: 0f

                val dx =
                    x -
                    downX

                val dy =
                    y -
                    downY

                pressedScale = 1f

                // ----------------------------------------------------
                // CONTROL CENTER OPEN
                // ----------------------------------------------------

                if (controlCenter) {

                    controlCenterGesture = false

                    val smallTap =
                        abs(dx) <
                        dp(14f) &&
                        abs(dy) <
                        dp(14f)

                    // Tap outside the visible panel closes it.
                    if (
                        smallTap &&
                        !isInsideControlCenterV17(
                            x,
                            y
                        )
                    ) {

                        settleControlCenterV17(
                            opening = false,
                            releaseVelocityPx =
                                min(
                                    -900f,
                                    velocityY
                                )
                        )

                        ccVelocityTracker?.recycle()
                        ccVelocityTracker = null

                        return true
                    }

                    val progress =
                        controlCenterProgress
                            .coerceIn(
                                0f,
                                1f
                            )

                    val fastClose =
                        velocityY <
                        -850f

                    val shouldClose =
                        fastClose ||
                        progress <
                        0.55f

                    settleControlCenterV17(
                        opening = !shouldClose,
                        releaseVelocityPx =
                            velocityY
                    )

                    ccVelocityTracker?.recycle()
                    ccVelocityTracker = null

                    return true
                }

                // ----------------------------------------------------
                // INTERACTIVE OPENING FINISH
                // ----------------------------------------------------

                if (
                    controlCenterGesture &&
                    dy >
                    dp(2f)
                ) {

                    val progress =
                        controlCenterProgress
                            .coerceIn(
                                0f,
                                1f
                            )

                    val fastOpen =
                        velocityY >
                        850f

                    val shouldOpen =
                        fastOpen ||
                        progress >=
                        0.28f ||
                        dy >=
                        dp(70f)

                    controlCenterGesture = false
                    controlCenterInteractive = false

                    settleControlCenterV17(
                        opening = shouldOpen,
                        releaseVelocityPx =
                            velocityY
                    )

                    ccVelocityTracker?.recycle()
                    ccVelocityTracker = null

                    return true
                }

                controlCenterGesture = false

                ccVelocityTracker?.recycle()
                ccVelocityTracker = null

                // ----------------------------------------------------
                // HOME PAGE SWIPE
                // ----------------------------------------------------

                if (dragging) {

                    if (
                        abs(dx) >
                        dp(55f)
                    ) {

                        if (dx < 0) {
                            animatePage(1)
                        } else {
                            animatePage(-1)
                        }
                    }

                    invalidate()

                    return true
                }

                // ----------------------------------------------------
                // APP TAP
                // ----------------------------------------------------

                if (pressedIndex >= 0) {

                    val index =
                        pressedIndex

                    pressedIndex = -1

                    invalidate()

                    launchApp(index)

                    return true
                }

                pressedIndex = -1

                invalidate()

                return true
            }

            // ========================================================
            // CANCEL
            // ========================================================

            MotionEvent.ACTION_CANCEL -> {

                ccVelocityTracker?.computeCurrentVelocity(
                    1000
                )

                val velocityY =
                    ccVelocityTracker
                        ?.yVelocity
                        ?: 0f

                pressedIndex = -1
                pressedScale = 1f

                val progress =
                    controlCenterProgress
                        .coerceIn(
                            0f,
                            1f
                        )

                if (
                    controlCenter ||
                    progress > 0f
                ) {

                    val shouldOpen =
                        progress >=
                        0.28f ||
                        velocityY >
                        700f

                    settleControlCenterV17(
                        opening = shouldOpen,
                        releaseVelocityPx =
                            velocityY
                    )

                } else {

                    controlCenterGesture = false
                    controlCenterInteractive = false
                }

                ccVelocityTracker?.recycle()
                ccVelocityTracker = null

                invalidate()

                return true
            }
        }

        return true
    }

    private fun isInsideControlCenterV17(
        x: Float,
        y: Float
    ): Boolean {

        val p =
            controlCenterProgress
                .coerceIn(
                    0f,
                    1f
                )

        val panelHeight =
            controlCenterPanelHeightV17()

        val top =
            dp(10f)

        val slide =
            -panelHeight *
            (
                1f -
                p
            )

        val scaleX =
            0.94f +
            0.06f *
            p

        val scaleY =
            0.80f +
            0.20f *
            p

        val left =
            width / 2f +
            (
                dp(14f) -
                width / 2f
            ) *
            scaleX

        val right =
            width / 2f +
            (
                width -
                dp(14f) -
                width / 2f
            ) *
            scaleX

        val panelTop =
            top +
            slide

        val panelBottom =
            panelTop +
            panelHeight *
            scaleY

        return (
            x >= left &&
            x <= right &&
            y >= panelTop &&
            y <= panelBottom
        )
    }

        private fun animatePage(
            target: Int
        ) {

            // IOS27_PAGE_CRASH_FIX_V2
            // Не разрешаем свайпнуть дальше первой/последней страницы.
            val safeTarget =
                target.coerceIn(
                    0,
                    maxPage()
                )

            // Если уже на нужной странице — ничего не запускаем.
            if (safeTarget == page) {
                pageOffset = 0f
                invalidate()
                return
            }

            val start =
                pageOffset

            val direction =
                if (safeTarget > page)
                    1f
                else
                    -1f

            ValueAnimator
                .ofFloat(
                    start,
                    direction
                )
                .apply {

                    duration = 220L

                    interpolator =
                        DecelerateInterpolator()

                    addUpdateListener {
                        pageOffset =
                            it.animatedValue
                                as Float

                        invalidate()
                    }

                    addListener(
                        object :
                            android.animation.Animator.AnimatorListener {

                            override fun onAnimationStart(
                                animation:
                                android.animation.Animator
                            ) {}

                            override fun onAnimationEnd(
                                animation:
                                android.animation.Animator
                            ) {

                                page = safeTarget
                                pageOffset = 0f

                                invalidate()
                            }

                            override fun onAnimationCancel(
                                animation:
                                android.animation.Animator
                            ) {

                                pageOffset = 0f
                                invalidate()
                            }

                            override fun onAnimationRepeat(
                                animation:
                                android.animation.Animator
                            ) {}
                        }
                    )

                    start()
                }
        }

        private fun getAppIndex(
            x: Float,
            y: Float
        ): Int {

            // EXACTLY the same geometry as drawHome().
            val top =
                dp(67f)

            val bottom =
                height - dp(184f)

            if (y < top || y > bottom)
                return -1

            val cellW =
                width.toFloat() /
                    columns

            val cellH =
                (bottom - top) /
                    rows

            val col =
                (x / cellW).toInt()

            val row =
                ((y - top) / cellH).toInt()

            if (
                col !in 0 until columns ||
                row !in 0 until rows
            )
                return -1

            val index =
                page *
                    columns *
                    rows +
                row *
                    columns +
                col

            return if (
                index in apps.indices
            )
                index
            else
                -1
        }

        private fun launchApp(index: Int) {

            if (index !in apps.indices) {
                return
            }

            val app = apps[index]

            try {

                val launch =
                    pm.getLaunchIntentForPackage(
                        app.packageName
                    )

                if (launch != null) {

                    launch.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )

                    startActivity(launch)
                }

            } catch (_: Exception) {
            }
        }

        private fun openAppAt(
            x: Float,
            y: Float
        ) {

            val index =
                getAppIndex(x, y)

            if (index < 0)
                return

            val app =
                apps[index]

            try {

                val launch =
                    pm.getLaunchIntentForPackage(
                        app.packageName
                    )

                if (launch != null) {

                    launch.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )

                    startActivity(launch)
                }

            } catch (_: Exception) {
            }
        }

        override fun onDetachedFromWindow() {

            stopSystemIndicatorObserversV17()

            ccAnimator?.cancel()
            ccAnimator = null

            navigationBarAnimator?.cancel()
            navigationBarAnimator = null

            ccVelocityTracker?.recycle()
            ccVelocityTracker = null

            wallpaperGradient = null
            wallpaperGlow1 = null
            wallpaperGlow2 = null
            wallpaperGlow3 = null

            wallpaperWidth = 0
            wallpaperHeight = 0

            super.onDetachedFromWindow()
        }

        private fun dp(
            value: Float
        ): Float =
            value *
                resources
                    .displayMetrics
                    .density
    }
}
