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

// IOS27_CONTROL_CENTER_ANIMATION_V11
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

        launcherView.startHomeAnimation()
    }

    override fun onResume() {
        super.onResume()

        if (::launcherView.isInitialized) {
            launcherView.reloadApps()
        }
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

        private fun drawIOSStatusIndicators(
            canvas: Canvas
        ) {

            val p =
                controlCenterProgress
                    .coerceIn(0f, 1f)

            // Первые ~2 см движения:
            // индикаторы уходят,
            // а Control Center ещё не полностью виден.

            val leftProgress =
                ccReveal(
                    p,
                    0.00f,
                    0.34f
                )

            val leftAlpha =
                (
                    1f -
                    leftProgress
                )

            // IOS27_STATUS_BAR_LIQUID_V10
            // Правая группа НЕ исчезает.
            // Wi-Fi / сеть / батарея остаются видимыми
            // на всём протяжении раскрытия Control Center.

            val rightAlpha = 1f

            val time =
                SimpleDateFormat(
                    "HH:mm",
                    Locale.getDefault()
                ).format(Date())

            // =============================================
            // LEFT — TIME
            // =============================================

            if (leftAlpha > 0f) {

                textPaint.color =
                    Color.WHITE

                textPaint.alpha =
                    (
                        255f *
                        leftAlpha *
                        homeAlpha.coerceAtLeast(0.85f)
                    )
                        .toInt()
                        .coerceIn(0, 255)

                textPaint.textSize =
                    dp(15f)

                textPaint.typeface =
                    Typeface.create(
                        "sans",
                        Typeface.BOLD
                    )

                textPaint.textAlign =
                    Paint.Align.LEFT

                // IOS27_STATUS_BAR_TIME_FADE_V11
                //
                // Время физически не двигается.
                // Оно остаётся в одной координате
                // и только постепенно исчезает
                // вместе с раскрытием Control Center.

                canvas.drawText(
                    time,
                    dp(22f),
                    dp(31f),
                    textPaint
                )
            }

            // =============================================
            // RIGHT — SIGNAL / WIFI / BATTERY
            // =============================================

            if (rightAlpha > 0f) {

                textPaint.color =
                    Color.WHITE

                textPaint.alpha =
                    (
                        255f *
                        rightAlpha
                    )
                        .toInt()
                        .coerceIn(0, 255)

                val batteryManager =
                    context.getSystemService(
                        Context.BATTERY_SERVICE
                    ) as? BatteryManager

                val battery =
                    batteryManager
                        ?.getIntProperty(
                            BatteryManager
                                .BATTERY_PROPERTY_CAPACITY
                        )
                        ?.coerceIn(
                            0,
                            100
                        )
                        ?: 100

                // IOS27_STATUS_BAR_RIGHT_FIXED_V11
                //
                // Wi-Fi / сеть / батарея:
                // строго фиксированная позиция.
                // Никакого translate.
                // Никакого вертикального смещения.
                // Никакого fade.
                //
                // Эта группа остаётся физически
                // на одном месте на протяжении
                // всего жеста.

                val groupY =
                    dp(25f)

                // -----------------------------------------
                // BATTERY
                // -----------------------------------------

                val batteryRight =
                    width -
                    dp(16f)

                val batteryLeft =
                    batteryRight -
                    dp(24f)

                val batteryTop =
                    groupY -
                    dp(6f)

                bgPaint.shader = null
                bgPaint.style =
                    Paint.Style.STROKE

                bgPaint.strokeWidth =
                    dp(1.5f)

                bgPaint.color =
                    Color.WHITE

                bgPaint.alpha =
                    textPaint.alpha

                canvas.drawRoundRect(
                    batteryLeft,
                    batteryTop,
                    batteryRight,
                    batteryTop +
                        dp(12f),
                    dp(3f),
                    dp(3f),
                    bgPaint
                )

                bgPaint.style =
                    Paint.Style.FILL

                canvas.drawRoundRect(
                    batteryLeft +
                        dp(2f),
                    batteryTop +
                        dp(2f),
                    batteryLeft +
                        dp(2f) +
                        (
                            dp(20f) *
                            battery /
                            100f
                        ),
                    batteryTop +
                        dp(10f),
                    dp(2f),
                    dp(2f),
                    bgPaint
                )

                canvas.drawRoundRect(
                    batteryRight,
                    batteryTop +
                        dp(3.5f),
                    batteryRight +
                        dp(2f),
                    batteryTop +
                        dp(8.5f),
                    dp(1f),
                    dp(1f),
                    bgPaint
                )

                // -----------------------------------------
                // BATTERY %
                // -----------------------------------------

                textPaint.textAlign =
                    Paint.Align.RIGHT

                textPaint.textSize =
                    dp(10f)

                textPaint.typeface =
                    Typeface.DEFAULT_BOLD

                canvas.drawText(
                    "$battery%",
                    batteryLeft -
                        dp(5f),
                    groupY +
                        dp(3.5f),
                    textPaint
                )

                // -----------------------------------------
                // WIFI
                // -----------------------------------------

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
                    textPaint.alpha

                canvas.drawArc(
                    RectF(
                        wifiCx -
                            dp(9f),
                        groupY -
                            dp(7f),
                        wifiCx +
                            dp(9f),
                        groupY +
                            dp(9f)
                    ),
                    225f,
                    90f,
                    false,
                    bgPaint
                )

                canvas.drawArc(
                    RectF(
                        wifiCx -
                            dp(6f),
                        groupY -
                            dp(4f),
                        wifiCx +
                            dp(6f),
                        groupY +
                            dp(8f)
                    ),
                    225f,
                    90f,
                    false,
                    bgPaint
                )

                bgPaint.style =
                    Paint.Style.FILL

                canvas.drawCircle(
                    wifiCx,
                    groupY +
                        dp(4f),
                    dp(1.8f),
                    bgPaint
                )

                // -----------------------------------------
                // MOBILE SIGNAL
                // -----------------------------------------

                val signalRight =
                    wifiCx -
                    dp(19f)

                bgPaint.color =
                    Color.WHITE

                for (i in 0 until 4) {

                    val barHeight =
                        dp(
                            3f +
                            i * 2.5f
                        )

                    val x =
                        signalRight -
                        dp(4f) *
                        (3 - i)

                    canvas.drawRoundRect(
                        x,
                        groupY -
                            barHeight,
                        x +
                            dp(3f),
                        groupY,
                        dp(1.3f),
                        dp(1.3f),
                        bgPaint
                    )
                }

            }

            textPaint.alpha = 255
            textPaint.textAlign =
                Paint.Align.LEFT

            bgPaint.alpha = 255
            bgPaint.style =
                Paint.Style.FILL
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

            // IOS27_STATUS_BAR_LIQUID_V8_FIXED
            // Верхние индикаторы являются частью
            // интерактивного перехода в Control Center.
            drawIOSStatusIndicators(canvas)

            if (searchMode) {

                drawSearch(canvas)

                return
            }

            drawHome(canvas)

            // IOS27_CONTROL_CENTER_GESTURE_V7
            //
            // Важно:
            // Control Center должен рисоваться уже во время
            // самого свайпа, даже когда controlCenter == false.
            //
            // Иначе пользователь двигает палец, progress меняется,
            // но визуально ничего не происходит, а после отпускания
            // панель сразу появляется почти полностью открытой.

            if (
                controlCenter ||
                controlCenterProgress > 0f
            ) {

                drawControlCenter(canvas)
            }
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
        val margin = dp(12f)
        val dockHeight = dp(82f)

        val top = height - dp(103f)
        val left = margin
        val right = width - margin
        val bottom = top + dockHeight

        glassPaint.style = Paint.Style.FILL
        glassPaint.color = Color.argb(105, 255, 255, 255)

        canvas.drawRoundRect(
            left,
            top,
            right,
            bottom,
            dp(27f),
            dp(27f),
            glassPaint
        )

        glassPaint.color = Color.argb(55, 255, 255, 255)

        canvas.drawRoundRect(
            left + dp(1f),
            top + dp(1f),
            right - dp(1f),
            top + dp(25f),
            dp(26f),
            dp(26f),
            glassPaint
        )

        val dockApps = dockAppsCache
        val count = min(4, dockApps.size)

        if (count > 0) {
            val slot = (right - left) / count

            for (i in 0 until count) {
                val cx = left + slot * i + slot / 2f
                val cy = top + dockHeight / 2f

                drawApp(
                    canvas,
                    dockApps[i],
                    cx,
                    cy
                )
            }
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

        private fun drawControlCenter(canvas: Canvas) {

        // IOS27_CONTROL_CENTER_VISUAL_V5
        //
        // Панель выходит СВЕРХУ вниз.
        // Движение пальца напрямую управляет progress.
        // После отпускания open/close анимируется отдельно.
        //
        // Визуально:
        // - затемнение появляется постепенно;
        // - вся панель приезжает сверху;
        // - panel scale/alpha идут вместе с движением;
        // - glass surfaces остаются прозрачными;
        // - блоки имеют лёгкий stagger.

        val p =
            controlCenterProgress.coerceIn(0f, 1f)

        if (p <= 0f) {
            return
        }

        val w =
            width.toFloat()

        val h =
            height.toFloat()

        // IOS27_CONTROL_CENTER_GESTURE_V7
        //
        // При движении пальца НИКАКОГО дополнительного easing:
        // 30% движения пальца = примерно 30% открытия панели.
        //
        // После отпускания включается отдельная плавная easing-анимация.

        val eased =
            if (controlCenterInteractive) {

                p

            } else {

                1f -
                (1f - p) *
                (1f - p) *
                (1f - p)
            }

        // IOS27_STATUS_BAR_LIQUID_V8_FIXED
        //
        // Первые ~2 см свайпа используются
        // для перехода статусной области.
        //
        // После этого Control Center
        // начинает materialize.

        val panelEased =
            ccReveal(
                eased,
                0.34f,
                0.96f
            )

        // -----------------------------------------
        // BACKDROP
        // -----------------------------------------

        // IOS27_CONTROL_CENTER_BACKDROP_V11
        //
        // Home Screen затемняется ещё до появления
        // основной панели. Это соответствует записи:
        // сначала исчезает яркость Home,
        // затем materialize сам Control Center.

        bgPaint.color =
            Color.argb(
                (115f * eased).toInt(),
                0,
                0,
                0
            )

        canvas.drawRect(
            0f,
            0f,
            w,
            h,
            bgPaint
        )

        // -----------------------------------------
        // PANEL GEOMETRY
        // -----------------------------------------

        val margin =
            dp(14f)

        val top =
            dp(18f)

        val gap =
            dp(10f)

        val groupWidth =
            (w - margin * 2f - gap) / 2f

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
            bottomControlsTop +
            dp(78f) -
            top +
            dp(18f)

        // -----------------------------------------
        // TOP-DOWN MOTION
        // -----------------------------------------

        val slideY =
            -panelHeight *
            (1f - panelEased)

        val scale =
            0.965f +
            0.035f * panelEased

        val panelAlpha =
            (255f * panelEased)
                .toInt()
                .coerceIn(0, 255)

        val centerX =
            w / 2f

        val centerY =
            top +
            panelHeight / 2f

        canvas.save()

        canvas.translate(
            centerX,
            centerY + slideY
        )

        canvas.scale(
            scale,
            scale
        )

        canvas.translate(
            -centerX,
            -centerY
        )

        // -----------------------------------------
        // CONNECTIVITY GLASS
        // -----------------------------------------

        val connectivityProgress =
            ccReveal(
                eased,
                0.34f,
                0.54f
            )

        drawCCReveal(
            canvas,
            connectivityProgress,
            dp(24f)
        ) {

        glassPaint.style =
            Paint.Style.FILL

        glassPaint.color =
            Color.argb(
                (92f * eased).toInt(),
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
                (36f * eased).toInt(),
                255,
                255,
                255
            )

        canvas.drawRoundRect(
            margin + dp(1f),
            top + dp(1f),
            margin + groupWidth - dp(1f),
            top + dp(31f),
            dp(27f),
            dp(27f),
            glassPaint
        )

        drawCCCircle(
            canvas,
            margin + dp(37f),
            top + dp(36f),
            dp(23f),
            "Wi",
            true
        )

        drawCCCircle(
            canvas,
            margin + dp(96f),
            top + dp(36f),
            dp(23f),
            "BT",
            true
        )

        drawCCCircle(
            canvas,
            margin + dp(37f),
            top + dp(86f),
            dp(23f),
            "✈",
            false
        )

        drawCCCircle(
            canvas,
            margin + dp(96f),
            top + dp(86f),
            dp(23f),
            "M",
            false
        )

        }

        // -----------------------------------------
        // MEDIA GLASS
        // -----------------------------------------

        val mediaProgress =
            ccReveal(
                eased,
                0.43f,
                0.64f
            )

        drawCCReveal(
            canvas,
            mediaProgress,
            dp(26f)
        ) {

        val mediaLeft =
            margin +
            groupWidth +
            gap

        glassPaint.color =
            Color.argb(
                (92f * eased).toInt(),
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
                (30f * eased).toInt(),
                255,
                255,
                255
            )

        canvas.drawRoundRect(
            mediaLeft + dp(1f),
            top + dp(1f),
            mediaLeft + groupWidth - dp(1f),
            top + dp(31f),
            dp(27f),
            dp(27f),
            glassPaint
        )

        textPaint.alpha =
            panelAlpha

        textPaint.color =
            Color.WHITE

        textPaint.textSize =
            dp(12f)

        textPaint.typeface =
            Typeface.DEFAULT_BOLD

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
                (190f * eased).toInt(),
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

        // -----------------------------------------
        // SLIDERS
        // -----------------------------------------

        val slidersProgress =
            ccReveal(
                eased,
                0.52f,
                0.74f
            )

        drawCCReveal(
            canvas,
            slidersProgress,
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

        // -----------------------------------------
        // BOTTOM CONTROLS
        // -----------------------------------------

        val bottomProgress =
            ccReveal(
                eased,
                0.62f,
                0.88f
            )

        drawCCReveal(
            canvas,
            bottomProgress,
            dp(34f)
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
                (smallWidth + dp(10f)) * 2f,
            smallY,
            smallWidth,
            "QR"
        )

        drawCCSmall(
            canvas,
            margin +
                (smallWidth + dp(10f)) * 3f,
            smallY,
            smallWidth,
            "+"
        )

        }

        textPaint.alpha = 255

        canvas.restore()
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

        private fun openControlCenter() {

        // IOS27_CONTROL_CENTER_ANIMATION_V7

        controlCenter = true
        controlCenterGesture = false
        controlCenterInteractive = false

        ccAnimator?.cancel()

        val startProgress =
            controlCenterProgress.coerceIn(0f, 1f)

        ccAnimator =
            ValueAnimator.ofFloat(
                startProgress,
                1f
            ).apply {

                // iOS-like settle:
                // быстрое движение в начале +
                // мягкое замедление в конце.
                // Быстрый iOS-like settle после отпускания.
                duration = 280L

                interpolator =
                    PathInterpolator(
                        0.16f,
                        1f,
                        0.30f,
                        1f
                    )

                addUpdateListener {

                    controlCenterProgress =
                        it.animatedValue as Float

                    invalidate()
                }

                start()
            }
    }


        private fun closeControlCenter() {

        // IOS27_CONTROL_CENTER_ANIMATION_V7

        controlCenterGesture = false
        controlCenterInteractive = false

        ccAnimator?.cancel()

        val startProgress =
            controlCenterProgress.coerceIn(0f, 1f)

        ccAnimator =
            ValueAnimator.ofFloat(
                startProgress,
                0f
            ).apply {

                // Закрытие быстрее раскрытия:
                // панель быстро возвращается к Home Screen.
                duration = 220L

                interpolator =
                    PathInterpolator(
                        0.55f,
                        0f,
                        0.85f,
                        0.25f
                    )

                addUpdateListener {

                    controlCenterProgress =
                        it.animatedValue as Float

                    if (
                        controlCenterProgress <= 0.005f
                    ) {

                        controlCenterProgress = 0f
                        controlCenter = false
                        controlCenterInteractive = false
                    }

                    invalidate()
                }

                start()
            }
    }


    // TOUCH
    // -----------------------------------------

    override fun onTouchEvent(
        event: MotionEvent
    ): Boolean {

        val x = event.x
        val y = event.y

        when (event.actionMasked) {

            MotionEvent.ACTION_DOWN -> {

                downX = x
                downY = y
                dragging = false

                // Control Center уже открыт.
                if (controlCenter) {

                    controlCenterGesture = true
                    controlCenterInteractive = true

                    controlCenterStartY = y
                    controlCenterStartX = x

                    ccAnimator?.cancel()

                    pressedIndex = -1
                    pressedScale = 1f

                    return true
                }

                // iPhone Face ID:
                // начало Control Center только
                // из верхнего правого участка.
                controlCenterGesture =
                    y <= dp(88f) &&
                    x >= width * 0.55f

                if (controlCenterGesture) {

                    controlCenterStartY = y
                    controlCenterStartX = x

                    pressedIndex = -1
                    pressedScale = 1f

                    return true
                }

                pressedIndex =
                    getAppIndex(x, y)

                if (pressedIndex >= 0) {

                    pressedScale = 0.94f
                    invalidate()
                }

                return true
            }

            MotionEvent.ACTION_MOVE -> {

                val dx = x - downX
                val dy = y - downY

                // OPEN CONTROL CENTER

                if (
                    !controlCenter &&
                    controlCenterGesture &&
                    dy > dp(2f)
                ) {

                    dragging = true

                    // IOS27_CONTROL_CENTER_GESTURE_V7
                    //
                    // Настоящее интерактивное открытие.
                    // Панель видна уже с первых пикселей свайпа.
                    // controlCenter остаётся false до момента
                    // завершения жеста.

                    controlCenterInteractive = true

                    ccAnimator?.cancel()

                    controlCenterProgress =
                        (
                            dy / dp(260f)
                        ).coerceIn(0f, 1f)

                    invalidate()

                    return true
                }

                // CONTROL CENTER OPEN

                if (controlCenter) {

                    val closeDistance =
                        controlCenterStartY - y

                    // Как только пользователь начинает двигать
                    // открытый Control Center, управление снова
                    // передаётся пальцу.

                    controlCenterInteractive = true

                    // Свайп вверх — закрытие.
                    if (closeDistance > 0f) {

                        controlCenterProgress =
                            1f -
                            (
                                closeDistance / dp(260f)
                            ).coerceIn(0f, 1f)

                        invalidate()

                        return true
                    }

                    // Движение вниз.
                    if (dy > 0f) {

                        controlCenterProgress = 1f

                        invalidate()
                    }

                    return true
                }

                // HOME PAGE SWIPE

                if (abs(dx) > dp(12f)) {
                    dragging = true
                }

                return true
            }

            MotionEvent.ACTION_UP -> {

                val dx = x - downX
                val dy = y - downY

                pressedScale = 1f

                // CONTROL CENTER

                if (controlCenter) {

                    controlCenterGesture = false

                    val closeDistance =
                        controlCenterStartY - y

                    // Свайп вверх закрывает.
                    if (
                        closeDistance > dp(55f)
                    ) {

                        closeControlCenter()

                        return true
                    }

                    // Более трети — открываем.
                    if (
                        controlCenterProgress >= 0.35f
                    ) {

                        openControlCenter()

                    } else {

                        closeControlCenter()
                    }

                    return true
                }

                // IOS27_CONTROL_CENTER_GESTURE_V6
                // Завершение интерактивного открытия.

                if (
                    controlCenterGesture &&
                    dy > dp(2f)
                ) {

                    val shouldOpen =
                        controlCenterProgress >= 0.35f ||
                        dy >= dp(90f)

                    controlCenterGesture = false

                    // Заканчиваем интерактивный режим.
                    // Дальше панель сама плавно доедет
                    // до конечного состояния.

                    controlCenterInteractive = false

                    if (shouldOpen) {
                        openControlCenter()
                    } else {
                        closeControlCenter()
                    }

                    return true
                }

                controlCenterGesture = false

                // HOME PAGE SWIPE

                if (dragging) {

                    if (
                        abs(dx) > dp(55f)
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

                // APP TAP

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

            MotionEvent.ACTION_CANCEL -> {

                pressedIndex = -1
                pressedScale = 1f

                controlCenterGesture = false
                controlCenterInteractive = false

                if (
                    controlCenter ||
                    controlCenterProgress > 0f
                ) {

                    if (
                        controlCenterProgress >= 0.35f
                    ) {

                        openControlCenter()

                    } else {

                        closeControlCenter()
                    }
                }

                invalidate()

                return true
            }
        }

        return true
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
            pageAnimator.cancel()
            pressAnimator.cancel()
            ccAnimator?.cancel()
            ccAnimator = null

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
