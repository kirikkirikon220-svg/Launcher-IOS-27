package com.ios27.launcher

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.EditText
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

        launcherView = LauncherView(this)
        setContentView(launcherView)
    }

    override fun onResume() {
        super.onResume()
        if (::launcherView.isInitialized) launcherView.reloadApps()
    }

    inner class LauncherView(context: Context) : View(context) {

        private data class AppItem(
            val label: String,
            val packageName: String,
            val icon: Drawable
        )

        private val pm = packageManager
        private val apps = mutableListOf<AppItem>()

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        private var downX = 0f
        private var downY = 0f
        private var page = 0
        private var controlCenter = false
        private var searchMode = false
        private var editMode = false

        private val columns = 4
        private val rows = 6

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val glassPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            isFocusable = true

            textPaint.typeface = Typeface.create(
                "sans",
                Typeface.NORMAL
            )

            reloadApps()
        }

        fun reloadApps() {
            apps.clear()

            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }

            val resolved = pm.queryIntentActivities(
                intent,
                PackageManager.MATCH_ALL
            )

            resolved
                .distinctBy { it.activityInfo.packageName }
                .sortedBy { it.loadLabel(pm).toString().lowercase() }
                .forEach {
                    val info = it.activityInfo.applicationInfo

                    apps.add(
                        AppItem(
                            it.loadLabel(pm).toString(),
                            info.packageName,
                            info.loadIcon(pm)
                        )
                    )
                }

            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            drawWallpaper(canvas)

            if (searchMode) {
                drawSearch(canvas)
                return
            }

            drawStatusBar(canvas)
            drawHome(canvas)

            if (controlCenter) {
                drawControlCenter(canvas)
            }
        }

        private fun drawWallpaper(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()

            val gradient = LinearGradient(
                0f, 0f, w, h,
                Color.rgb(40, 70, 120),
                Color.rgb(8, 12, 30),
                Shader.TileMode.CLAMP
            )

            bgPaint.shader = gradient
            canvas.drawRect(0f, 0f, w, h, bgPaint)

            val glow = RadialGradient(
                w * 0.25f,
                h * 0.18f,
                w * 0.65f,
                Color.argb(150, 80, 150, 255),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )

            bgPaint.shader = glow
            canvas.drawCircle(
                w * 0.25f,
                h * 0.18f,
                w * 0.65f,
                bgPaint
            )

            val glow2 = RadialGradient(
                w * 0.85f,
                h * 0.75f,
                w * 0.55f,
                Color.argb(120, 180, 90, 220),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP
            )

            bgPaint.shader = glow2
            canvas.drawCircle(
                w * 0.85f,
                h * 0.75f,
                w * 0.55f,
                bgPaint
            )

            bgPaint.shader = null
        }

        private fun drawStatusBar(canvas: Canvas) {
            textPaint.color = Color.WHITE
            textPaint.textSize = dp(15f)
            textPaint.typeface = Typeface.create("sans", Typeface.BOLD)

            val time = SimpleDateFormat(
                "HH:mm",
                Locale.getDefault()
            ).format(Date())

            canvas.drawText(
                time,
                dp(24f),
                dp(30f),
                textPaint
            )

            textPaint.textAlign = Paint.Align.RIGHT
            textPaint.textSize = dp(13f)

            canvas.drawText(
                "▮▮▮  Wi-Fi  ▰",
                width - dp(20f),
                dp(30f),
                textPaint
            )

            textPaint.textAlign = Paint.Align.LEFT
        }

        private fun drawHome(canvas: Canvas) {
            val top = dp(62f)
            val bottom = height - dp(128f)

            val availableHeight = bottom - top
            val cellW = width.toFloat() / columns
            val cellH = availableHeight / rows

            val perPage = columns * rows
            val start = page * perPage
            val end = min(start + perPage, apps.size)

            for (i in start until end) {
                val local = i - start
                val col = local % columns
                val row = local / columns

                val cx = cellW * col + cellW / 2f
                val cy = top + cellH * row + cellH * 0.48f

                drawApp(
                    canvas,
                    apps[i],
                    cx,
                    cy
                )
            }

            drawPageDots(canvas, bottom - dp(15f))
            drawDock(canvas)
        }

        private fun drawApp(
            canvas: Canvas,
            app: AppItem,
            cx: Float,
            cy: Float
        ) {
            val size = dp(62f)
            val left = cx - size / 2f
            val top = cy - size / 2f

            app.icon.setBounds(
                left.toInt(),
                top.toInt(),
                (left + size).toInt(),
                (top + size).toInt()
            )

            app.icon.alpha = 255
            app.icon.draw(canvas)

            textPaint.color = Color.WHITE
            textPaint.textSize = dp(11.5f)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.typeface = Typeface.create(
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

            textPaint.textAlign = Paint.Align.LEFT
        }

        private fun drawDock(canvas: Canvas) {
            val margin = dp(12f)
            val heightDock = dp(88f)
            val top = height - dp(108f)

            glassPaint.shader = null
            glassPaint.color = Color.argb(80, 255, 255, 255)

            canvas.drawRoundRect(
                margin,
                top,
                width - margin,
                top + heightDock,
                dp(28f),
                dp(28f),
                glassPaint
            )

            glassPaint.color = Color.argb(70, 255, 255, 255)

            canvas.drawRoundRect(
                margin + dp(2f),
                top + dp(2f),
                width - margin - dp(2f),
                top + heightDock - dp(2f),
                dp(26f),
                dp(26f),
                glassPaint
            )

            val dockApps = apps.take(4)

            val slot = (width - dp(32f)) / 4f

            dockApps.forEachIndexed { index, app ->
                val cx = dp(16f) + slot * index + slot / 2f
                val cy = top + heightDock / 2f

                val size = dp(57f)

                app.icon.setBounds(
                    (cx - size / 2).toInt(),
                    (cy - size / 2).toInt(),
                    (cx + size / 2).toInt(),
                    (cy + size / 2).toInt()
                )

                app.icon.draw(canvas)
            }
        }

        private fun drawPageDots(
            canvas: Canvas,
            y: Float
        ) {
            val pages = max(
                1,
                (apps.size + columns * rows - 1) /
                    (columns * rows)
            )

            val totalWidth = pages * dp(7f) +
                    (pages - 1) * dp(6f)

            var x = width / 2f - totalWidth / 2f

            for (i in 0 until pages) {
                paint.color =
                    if (i == page)
                        Color.WHITE
                    else
                        Color.argb(100, 255, 255, 255)

                canvas.drawCircle(
                    x + dp(3.5f),
                    y,
                    dp(3.5f),
                    paint
                )

                x += dp(13f)
            }
        }

        private fun drawSearch(canvas: Canvas) {
            paint.color = Color.argb(150, 5, 8, 20)
            canvas.drawRect(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                paint
            )

            glassPaint.color = Color.argb(150, 255, 255, 255)

            canvas.drawRoundRect(
                dp(20f),
                dp(58f),
                width - dp(20f),
                dp(112f),
                dp(27f),
                dp(27f),
                glassPaint
            )

            textPaint.color = Color.DKGRAY
            textPaint.textSize = dp(17f)

            canvas.drawText(
                "⌕   Поиск",
                dp(40f),
                dp(92f),
                textPaint
            )

            textPaint.color = Color.WHITE
            textPaint.textSize = dp(26f)
            textPaint.typeface = Typeface.DEFAULT_BOLD

            canvas.drawText(
                "Поиск приложений",
                dp(25f),
                dp(165f),
                textPaint
            )

            textPaint.typeface = Typeface.DEFAULT
            textPaint.textSize = dp(15f)

            canvas.drawText(
                "Свайп вниз, чтобы закрыть",
                dp(25f),
                dp(195f),
                textPaint
            )
        }

        private fun drawControlCenter(canvas: Canvas) {
            paint.color = Color.argb(120, 0, 0, 0)
            canvas.drawRect(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                paint
            )

            val margin = dp(15f)

            glassPaint.color = Color.argb(
                145,
                245,
                248,
                255
            )

            canvas.drawRoundRect(
                margin,
                dp(48f),
                width - margin,
                height - dp(24f),
                dp(34f),
                dp(34f),
                glassPaint
            )

            textPaint.color = Color.BLACK
            textPaint.textSize = dp(26f)
            textPaint.typeface = Typeface.DEFAULT_BOLD

            canvas.drawText(
                "Пункт управления",
                dp(35f),
                dp(100f),
                textPaint
            )

            drawCCButton(
                canvas,
                dp(35f),
                dp(135f),
                "✈",
                "Авиарежим"
            )

            drawCCButton(
                canvas,
                dp(190f),
                dp(135f),
                "Wi",
                "Wi-Fi"
            )

            drawCCButton(
                canvas,
                dp(35f),
                dp(235f),
                "☼",
                "Яркость"
            )

            drawCCButton(
                canvas,
                dp(190f),
                dp(235f),
                "◉",
                "Фокус"
            )

            textPaint.textSize = dp(14f)
            textPaint.typeface = Typeface.DEFAULT

            canvas.drawText(
                "Нажмите вне панели для закрытия",
                dp(35f),
                height - dp(55f),
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
            glassPaint.color = Color.argb(
                115,
                255,
                255,
                255
            )

            canvas.drawRoundRect(
                x,
                y,
                x + dp(125f),
                y + dp(78f),
                dp(24f),
                dp(24f),
                glassPaint
            )

            textPaint.color = Color.BLACK
            textPaint.textSize = dp(25f)
            textPaint.typeface = Typeface.DEFAULT_BOLD

            canvas.drawText(
                icon,
                x + dp(17f),
                y + dp(34f),
                textPaint
            )

            textPaint.textSize = dp(12f)
            textPaint.typeface = Typeface.DEFAULT

            canvas.drawText(
                title,
                x + dp(17f),
                y + dp(59f),
                textPaint
            )
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {

                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    return true
                }

                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY

                    if (controlCenter) {
                        if (abs(dy) > dp(80f) ||
                            event.y < dp(40f)) {
                            controlCenter = false
                            invalidate()
                        }
                        return true
                    }

                    if (searchMode) {
                        if (dy > dp(60f)) {
                            searchMode = false
                            invalidate()
                        }
                        return true
                    }

                    if (abs(dx) > dp(80f)) {
                        if (dx < 0) {
                            val maxPage =
                                max(
                                    0,
                                    (apps.size - 1) /
                                        (columns * rows)
                                )

                            page = min(
                                page + 1,
                                maxPage
                            )
                        } else {
                            page = max(0, page - 1)
                        }

                        invalidate()
                        return true
                    }

                    if (dy > dp(100f)) {
                        searchMode = true
                        invalidate()
                        return true
                    }

                    if (dy < -dp(100f) &&
                        downX > width * 0.55f) {
                        controlCenter = true
                        invalidate()
                        return true
                    }

                    if (abs(dx) < dp(30f) &&
                        abs(dy) < dp(30f)) {
                        openAppAt(event.x, event.y)
                    }

                    return true
                }
            }

            return true
        }

        private fun openAppAt(
            x: Float,
            y: Float
        ) {
            val top = dp(62f)
            val bottom = height - dp(128f)

            if (y < top || y > bottom) return

            val cellW = width.toFloat() / columns
            val cellH =
                (bottom - top) / rows

            val col = (x / cellW).toInt()
            val row = ((y - top) / cellH).toInt()

            if (col !in 0 until columns ||
                row !in 0 until rows) return

            val index =
                page * columns * rows +
                row * columns +
                col

            if (index !in apps.indices) return

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

        private fun dp(value: Float): Float =
            value * resources.displayMetrics.density
    }
}
