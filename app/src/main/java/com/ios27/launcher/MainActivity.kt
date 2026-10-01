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

        private var downX = 0f
        private var downY = 0f

        private var page = 0

        private var pageOffset = 0f

        private var controlCenter = false
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

        private fun drawWallpaper(
            canvas: Canvas
        ) {

            val w = width.toFloat()
            val h = height.toFloat()

            val gradient =
                LinearGradient(
                    0f,
                    0f,
                    w,
                    h,
                    Color.rgb(20, 42, 82),
                    Color.rgb(4, 7, 18),
                    Shader.TileMode.CLAMP
                )

            bgPaint.shader = gradient

            canvas.drawRect(
                0f,
                0f,
                w,
                h,
                bgPaint
            )

            val glow1 =
                RadialGradient(
                    w * 0.22f,
                    h * 0.12f,
                    w * 0.7f,
                    Color.argb(
                        180,
                        80,
                        150,
                        255
                    ),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )

            bgPaint.shader = glow1

            canvas.drawCircle(
                w * 0.22f,
                h * 0.12f,
                w * 0.7f,
                bgPaint
            )

            val glow2 =
                RadialGradient(
                    w * 0.85f,
                    h * 0.68f,
                    w * 0.62f,
                    Color.argb(
                        130,
                        180,
                        90,
                        235
                    ),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )

            bgPaint.shader = glow2

            canvas.drawCircle(
                w * 0.85f,
                h * 0.68f,
                w * 0.62f,
                bgPaint
            )

            val glow3 =
                RadialGradient(
                    w * 0.35f,
                    h * 0.95f,
                    w * 0.5f,
                    Color.argb(
                        90,
                        40,
                        180,
                        255
                    ),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP
                )

            bgPaint.shader = glow3

            canvas.drawCircle(
                w * 0.35f,
                h * 0.95f,
                w * 0.5f,
                bgPaint
            )

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
                dp(72f)

            val bottom =
                height - dp(190f)

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
                    height - dp(160f)
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

            val perPage =
                columns * rows

            val start =
                targetPage * perPage

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
                cy + dp(46f),
                textPaint
            )

            textPaint.alpha = 255

            textPaint.textAlign =
                Paint.Align.LEFT
        }

        // -----------------------------------------
        // DOCK
        // -----------------------------------------

        private fun drawDock(
            canvas: Canvas
        ) {

            val margin =
                dp(12f)

            val dockHeight =
                dp(82f)

            val top =
                height - dp(103f)

            // Soft depth under the glass.
            shadowPaint.color =
                Color.argb(
                    45,
                    0,
                    0,
                    0
                )

            canvas.drawRoundRect(
                margin,
                top + dp(2f),
                width - margin,
                top + dockHeight + dp(2f),
                dp(27f),
                dp(27f),
                shadowPaint
            )

            // Main Liquid Glass surface.
            glassPaint.color =
                Color.argb(
                    82,
                    255,
                    255,
                    255
                )

            canvas.drawRoundRect(
                margin,
                top,
                width - margin,
                top + dockHeight,
                dp(27f),
                dp(27f),
                glassPaint
            )

            // Very subtle inner glass highlight.
            glassPaint.color =
                Color.argb(
                    24,
                    255,
                    255,
                    255
                )

            canvas.drawRoundRect(
                margin + dp(1.5f),
                top + dp(1.5f),
                width - margin - dp(1.5f),
                top + dockHeight - dp(1.5f),
                dp(25f),
                dp(25f),
                glassPaint
            )

            val dockApps =
                getDockApps()

            val slot =
                (width - dp(24f)) / 4f

            dockApps.forEachIndexed {
                index,
                app ->

                val cx =
                    dp(12f) +
                    slot * index +
                    slot / 2f

                val cy =
                    top +
                    dockHeight / 2f

                val size =
                    dp(57f)

                app.icon.setBounds(
                    (cx - size / 2f).toInt(),
                    (cy - size / 2f).toInt(),
                    (cx + size / 2f).toInt(),
                    (cy + size / 2f).toInt()
                )

                app.icon.alpha =
                    (255 * homeAlpha).toInt()

                app.icon.draw(canvas)
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

        private fun drawControlCenter(
            canvas: Canvas
        ) {

            paint.color =
                Color.argb(
                    125,
                    0,
                    0,
                    0
                )

            canvas.drawRect(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                paint
            )

            val margin =
                dp(14f)

            glassPaint.color =
                Color.argb(
                    185,
                    245,
                    248,
                    255
                )

            canvas.drawRoundRect(
                margin,
                dp(45f),
                width - margin,
                height - dp(22f),
                dp(34f),
                dp(34f),
                glassPaint
            )

            textPaint.color =
                Color.BLACK

            textPaint.textSize =
                dp(26f)

            textPaint.typeface =
                Typeface.DEFAULT_BOLD

            canvas.drawText(
                "Пункт управления",
                dp(33f),
                dp(98f),
                textPaint
            )

            drawCCButton(
                canvas,
                dp(30f),
                dp(125f),
                "✈",
                "Авиарежим"
            )

            drawCCButton(
                canvas,
                dp(188f),
                dp(125f),
                "Wi",
                "Wi-Fi"
            )

            drawCCButton(
                canvas,
                dp(30f),
                dp(220f),
                "☼",
                "Яркость"
            )

            drawCCButton(
                canvas,
                dp(188f),
                dp(220f),
                "◉",
                "Фокус"
            )

            textPaint.textSize =
                dp(14f)

            textPaint.typeface =
                Typeface.DEFAULT

            canvas.drawText(
                "Нажмите вне панели для закрытия",
                dp(33f),
                height - dp(52f),
                textPaint
            )
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
        // TOUCH
        // -----------------------------------------

        override fun onTouchEvent(
            event: MotionEvent
        ): Boolean {

            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {

                    downX =
                        event.x

                    downY =
                        event.y

                    dragging = true

                    pressedIndex =
                        getAppIndex(
                            event.x,
                            event.y
                        )

                    if (pressedIndex >= 0) {
                        pressAnimator.cancel()
                        pressAnimator.start()
                    }

                    return true
                }

                MotionEvent.ACTION_MOVE -> {

                    val dx =
                        event.x - downX

                    if (abs(dx) > dp(15f) &&
                        !controlCenter &&
                        !searchMode
                    ) {

                        val maxDistance =
                            width.toFloat()

                        pageOffset =
                            (-dx / maxDistance)
                                .coerceIn(
                                    -1f,
                                    1f
                                )

                        invalidate()
                    }

                    return true
                }

                MotionEvent.ACTION_UP -> {

                    dragging = false

                    val dx =
                        event.x - downX

                    val dy =
                        event.y - downY

                    if (controlCenter) {

                        controlCenter = false

                        invalidate()

                        return true
                    }

                    if (searchMode) {

                        if (dy > dp(50f)) {
                            searchMode = false
                        }

                        invalidate()

                        return true
                    }

                    if (abs(dx) > dp(80f)) {

                        if (dx < 0f &&
                            page < maxPage()
                        ) {

                            animatePage(
                                page + 1
                            )

                        } else if (
                            dx > 0f &&
                            page > 0
                        ) {

                            animatePage(
                                page - 1
                            )

                        } else {

                            animatePage(
                                page
                            )
                        }

                        pressedIndex = -1

                        return true
                    }

                    val searchTop =
                        height - dp(149f)

                    val searchBottom =
                        height - dp(117f)

                    val searchLeft =
                        width / 2f - dp(46f)

                    val searchRight =
                        width / 2f + dp(46f)

                    if (
                        event.x >= searchLeft &&
                        event.x <= searchRight &&
                        event.y >= searchTop &&
                        event.y <= searchBottom
                    ) {

                        searchMode = true

                        pressedIndex = -1

                        invalidate()

                        return true
                    }

                    if (
                        downY < dp(80f) &&
                        downX > width * 0.50f &&
                        dy > dp(80f)
                    ) {

                        controlCenter = true

                        pressedIndex = -1

                        pageOffset = 0f

                        invalidate()

                        return true
                    }

                    if (
                        abs(dx) < dp(30f) &&
                        abs(dy) < dp(30f)
                    ) {

                        openAppAt(
                            event.x,
                            event.y
                        )
                    }

                    pressedIndex = -1

                    pageOffset = 0f

                    invalidate()

                    return true
                }

                MotionEvent.ACTION_CANCEL -> {

                    pressedIndex = -1
                    pageOffset = 0f

                    invalidate()

                    return true
                }
            }

            return true
        }

        private fun animatePage(
            target: Int
        ) {

            val start =
                pageOffset

            val direction =
                if (target > page)
                    1f
                else if (target < page)
                    -1f
                else
                    0f

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

                                page = target
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
                dp(72f)

            val bottom =
                height - dp(190f)

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

        private fun dp(
            value: Float
        ): Float =
            value *
                resources
                    .displayMetrics
                    .density
    }
}
