package com.ios27.launcher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.*
import android.view.animation.DecelerateInterpolator
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
        private var searchMode = false
        private var editMode = false

        private var pressedIndex = -1
        private var pressedScale = 1f

        private var homeAlpha = 0f

        private var dragging = false

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

            if (searchMode) {

                drawSearch(canvas)

                return
            }

            drawHome(canvas)

            if (controlCenter) {

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

            canvas.drawText(
                "Потяните вниз, чтобы закрыть",
                dp(25f),
                dp(195f),
                textPaint
            )
        }

        // -----------------------------------------
        // CONTROL CENTER
        // -----------------------------------------


    private fun drawControlCenter(canvas: Canvas) {
        val p = controlCenterProgress

        if (p <= 0f) return

        val scrimAlpha = (125f * p).toInt().coerceIn(0, 255)

        bgPaint.color = Color.argb(
            scrimAlpha,
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

        val w = width.toFloat()
        val h = height.toFloat()

        val panelLeft = dp(12f)
        val panelTop = dp(18f)
        val panelRight = w - dp(12f)
        val panelBottom = h - dp(18f)

        val fromLeft = w - dp(18f)
        val fromTop = -dp(30f)

        val left = fromLeft +
                (panelLeft - fromLeft) * p

        val top = fromTop +
                (panelTop - fromTop) * p

        val right = panelRight
        val bottom = panelBottom

        canvas.save()

        val scale = 0.94f + 0.06f * p

        canvas.scale(
            scale,
            scale,
            w - dp(12f),
            dp(18f)
        )

        glassPaint.style = Paint.Style.FILL
        glassPaint.color = Color.argb(
            225,
            245,
            245,
            250
        )

        canvas.drawRoundRect(
            left,
            top,
            right,
            bottom,
            dp(30f),
            dp(30f),
            glassPaint
        )

        glassPaint.color = Color.argb(
            45,
            255,
            255,
            255
        )

        canvas.drawRoundRect(
            left + dp(1f),
            top + dp(1f),
            right - dp(1f),
            top + dp(82f),
            dp(29f),
            dp(29f),
            glassPaint
        )

        textPaint.color = Color.BLACK
        textPaint.textSize = dp(24f)
        textPaint.typeface = Typeface.create(
            Typeface.DEFAULT,
            Typeface.BOLD
        )
        textPaint.textAlign = Paint.Align.LEFT

        canvas.drawText(
            "Пункт управления",
            left + dp(22f),
            top + dp(39f),
            textPaint
        )

        val gap = dp(10f)

        val contentLeft = left + dp(18f)
        val contentTop = top + dp(60f)

        val groupWidth =
            (right - left - dp(46f)) / 2f

        val groupHeight = dp(112f)

        glassPaint.color = Color.argb(
            100,
            220,
            220,
            225
        )

        canvas.drawRoundRect(
            contentLeft,
            contentTop,
            contentLeft + groupWidth,
            contentTop + groupHeight,
            dp(23f),
            dp(23f),
            glassPaint
        )

        drawCCCircle(
            canvas,
            contentLeft + dp(34f),
            contentTop + dp(34f),
            dp(22f),
            "Wi",
            true
        )

        drawCCCircle(
            canvas,
            contentLeft + dp(91f),
            contentTop + dp(34f),
            dp(22f),
            "BT",
            true
        )

        drawCCCircle(
            canvas,
            contentLeft + dp(34f),
            contentTop + dp(82f),
            dp(22f),
            "✈",
            false
        )

        drawCCCircle(
            canvas,
            contentLeft + dp(91f),
            contentTop + dp(82f),
            dp(22f),
            "M",
            false
        )

        val mediaLeft =
            contentLeft + groupWidth + gap

        glassPaint.color = Color.argb(
            100,
            220,
            220,
            225
        )

        canvas.drawRoundRect(
            mediaLeft,
            contentTop,
            mediaLeft + groupWidth,
            contentTop + groupHeight,
            dp(23f),
            dp(23f),
            glassPaint
        )

        textPaint.color = Color.DKGRAY
        textPaint.textSize = dp(12f)
        textPaint.typeface = Typeface.DEFAULT_BOLD

        canvas.drawText(
            "Сейчас играет",
            mediaLeft + dp(16f),
            contentTop + dp(27f),
            textPaint
        )

        textPaint.color = Color.BLACK
        textPaint.textSize = dp(17f)

        canvas.drawText(
            "Музыка",
            mediaLeft + dp(16f),
            contentTop + dp(51f),
            textPaint
        )

        textPaint.color = Color.DKGRAY
        textPaint.textSize = dp(11f)

        canvas.drawText(
            "Ничего не воспроизводится",
            mediaLeft + dp(16f),
            contentTop + dp(73f),
            textPaint
        )

        drawCCCircle(
            canvas,
            mediaLeft + groupWidth - dp(31f),
            contentTop + dp(76f),
            dp(20f),
            "▶",
            false
        )

        val sliderY =
            contentTop + groupHeight + gap

        drawCCSlider(
            canvas,
            left + dp(18f),
            sliderY,
            right - left - dp(36f),
            dp(58f),
            "☀"
        )

        drawCCSlider(
            canvas,
            left + dp(18f),
            sliderY + dp(68f),
            right - left - dp(36f),
            dp(58f),
            "♪"
        )

        val smallY =
            sliderY + dp(138f)

        val smallWidth =
            (right - left - dp(56f)) / 4f

        drawCCSmall(
            canvas,
            left + dp(18f),
            smallY,
            smallWidth,
            "Фокус"
        )

        drawCCSmall(
            canvas,
            left + dp(28f) + smallWidth,
            smallY,
            smallWidth,
            "Air"
        )

        drawCCSmall(
            canvas,
            left + dp(38f) + smallWidth * 2f,
            smallY,
            smallWidth,
            "QR"
        )

        drawCCSmall(
            canvas,
            left + dp(48f) + smallWidth * 3f,
            smallY,
            smallWidth,
            "+"
        )

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
        controlCenter = true

        ccAnimator?.cancel()

        ccAnimator = ValueAnimator.ofFloat(
            controlCenterProgress,
            1f
        ).apply {
            duration = 330L
            interpolator =
                DecelerateInterpolator(1.6f)

            addUpdateListener {
                controlCenterProgress =
                    it.animatedValue as Float

                invalidate()
            }

            start()
        }
    }

    private fun closeControlCenter() {
        ccAnimator?.cancel()

        ccAnimator = ValueAnimator.ofFloat(
            controlCenterProgress,
            0f
        ).apply {
            duration = 260L
            interpolator =
                DecelerateInterpolator(1.5f)

            addUpdateListener {
                controlCenterProgress =
                    it.animatedValue as Float

                if (controlCenterProgress <= 0.01f) {
                    controlCenter = false
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

                if (controlCenter) {
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

                if (
                    !controlCenter &&
                    downY < dp(85f) &&
                    downX > width * 0.52f &&
                    dy > dp(10f)
                ) {
                    dragging = true

                    controlCenter = true

                    controlCenterProgress =
                        (dy / dp(260f))
                            .coerceIn(0f, 1f)

                    invalidate()

                    return true
                }

                if (controlCenter) {

                    if (dy < -dp(60f)) {
                        closeControlCenter()
                    }

                    return true
                }

                if (kotlin.math.abs(dx) > dp(12f)) {
                    dragging = true
                }

                return true
            }

            MotionEvent.ACTION_UP -> {

                val dx = x - downX
                val dy = y - downY

                pressedScale = 1f

                if (controlCenter) {

                    if (dy < -dp(45f)) {
                        closeControlCenter()
                    }

                    invalidate()

                    return true
                }

                if (dragging) {

                    if (
                        kotlin.math.abs(dx) >
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

                if (pressedIndex >= 0) {

                    val index = pressedIndex

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
